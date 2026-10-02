package com.eden.neucam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 멀티 카메라 조합 */
sealed interface MultiPair {
    val label: String

    /** 논리 멀티 카메라 하나를 열고 두 물리 카메라 스트림을 동시에 받는다 (OutputConfiguration.setPhysicalCameraId) */
    data class Logical(val logicalId: String, val physA: String, val physB: String) : MultiPair {
        override val label get() = "논리 #$logicalId → 물리 $physA + $physB"
    }

    /** 서로 다른 두 카메라를 동시에 연다. guaranteed=true 면 getConcurrentCameraIds() 가 보장한 조합 */
    data class Concurrent(val idA: String, val idB: String, val guaranteed: Boolean) : MultiPair {
        override val label get() = "동시 #$idA + #$idB" + if (guaranteed) " (보장)" else " (시도)"
    }
}

@Immutable
data class MultiView(val label: String, val size: Size, val sensorOrientation: Int, val facing: Int)

@Immutable
data class MultiState(
    val status: CameraStatus = CameraStatus.CLOSED,
    val error: String? = null,
    val pair: MultiPair? = null,
    val views: List<MultiView?> = listOf(null, null),
    val live: List<String> = listOf("", ""),
    val info: String = "",
    val message: String? = null,
    val messageSeq: Int = 0,
    val lastThumb: android.graphics.Bitmap? = null,
    val capturing: Boolean = false,
)

class MultiCameraController(private val context: Context, private val repo: CameraRepository) {

    private val thread = HandlerThread("NeuCam-Multi").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val scope = CoroutineScope(SupervisorJob() + handler.asCoroutineDispatcher("multi"))
    private val mutex = Mutex()

    private val _state = MutableStateFlow(MultiState())
    val state: StateFlow<MultiState> = _state.asStateFlow()

    private var active = false
    private var pair: MultiPair? = null
    private val targets = arrayOfNulls<PreviewTarget>(2)

    private val devices = mutableListOf<CameraDevice>()
    private val sessions = mutableListOf<CameraCaptureSession>()
    private val readers = arrayOfNulls<ImageReader>(2)
    private val channels = arrayOfNulls<Channel<Image>>(2)
    private var signature: Any? = null
    private var lastLive = 0L

    fun pairs(): List<MultiPair> {
        val list = mutableListOf<MultiPair>()
        repo.listCameras().filter { it.isLogical && it.physicalIds.size >= 2 }.forEach { cam ->
            val p = cam.physicalIds
            for (i in p.indices) for (j in i + 1 until p.size) list += MultiPair.Logical(cam.id, p[i], p[j])
        }
        val guaranteed = repo.concurrentCameraIds().filter { it.size >= 2 }.flatMap { set ->
            val ids = set.sorted()
            buildList { for (i in ids.indices) for (j in i + 1 until ids.size) add(MultiPair.Concurrent(ids[i], ids[j], true)) }
        }.distinct()
        list += guaranteed
        val ids = repo.manager.cameraIdList.toList()
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            if (guaranteed.none { it.idA == ids[i] && it.idB == ids[j] }) list += MultiPair.Concurrent(ids[i], ids[j], false)
        }
        return list
    }

    fun setActive(value: Boolean) = locked { active = value; reconcile() }
    fun setPair(p: MultiPair) = locked { pair = p; _state.update { it.copy(pair = p) }; reconcile() }
    fun setTarget(index: Int, target: PreviewTarget) = locked { targets[index] = target; reconcile() }
    fun releaseTarget(index: Int, target: PreviewTarget): Job = locked {
        if (targets[index] === target) { targets[index] = null; reconcile() }
    }
    fun capture() = locked { doCapture() }
    fun release() {
        scope.launch { mutex.withLock { closeAll() } }.invokeOnCompletion { thread.quitSafely() }
    }

    private fun locked(block: suspend () -> Unit): Job =
        scope.launch {
            mutex.withLock {
                try { block() } catch (t: Throwable) {
                    Log.e(TAG, "multi op failed", t)
                    closeAll()
                    _state.update { it.copy(status = CameraStatus.ERROR, error = t.message ?: t.javaClass.simpleName) }
                }
            }
        }

    private suspend fun reconcile() {
        val p = pair
        val a = targets[0]
        val b = targets[1]
        if (!active || p == null || a == null || b == null) { closeAll(); return }
        val sig = listOf(p, System.identityHashCode(a), System.identityHashCode(b))
        if (sig == signature && sessions.isNotEmpty()) return
        closeAll()
        _state.update { it.copy(status = CameraStatus.OPENING, error = null) }
        when (p) {
            is MultiPair.Logical -> openLogical(p, a, b)
            is MultiPair.Concurrent -> openConcurrent(p, a, b)
        }
        signature = sig
        _state.update { it.copy(status = CameraStatus.RUNNING) }
    }

    private fun closeAll() {
        sessions.forEach { runCatching { it.close() } }; sessions.clear()
        devices.forEach { runCatching { it.close() } }; devices.clear()
        for (i in 0..1) {
            readers[i]?.close(); readers[i] = null
            channels[i]?.close(); channels[i] = null
        }
        signature = null
        _state.update { it.copy(status = CameraStatus.CLOSED, views = listOf(null, null)) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun open(id: String): CameraDevice = suspendCancellableCoroutine { cont ->
        repo.manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { if (cont.isActive) cont.resume(d) else d.close() }
            override fun onDisconnected(d: CameraDevice) {
                d.close()
                if (cont.isActive) cont.resumeWithException(IllegalStateException("#$id 연결 끊김"))
                else locked { closeAll(); _state.update { it.copy(status = CameraStatus.ERROR, error = "#$id 연결 끊김") } }
            }
            override fun onError(d: CameraDevice, error: Int) {
                d.close()
                val msg = if (error == ERROR_MAX_CAMERAS_IN_USE) "#$id: 동시에 열 수 있는 카메라 수 초과 (이 조합은 기기에서 미지원)" else "#$id 오류 $error"
                if (cont.isActive) cont.resumeWithException(IllegalStateException(msg))
                else locked { closeAll(); _state.update { it.copy(status = CameraStatus.ERROR, error = msg) } }
            }
        })
    }

    private fun newReader(index: Int, size: Size): ImageReader {
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        val ch = Channel<Image>(Channel.UNLIMITED)
        r.setOnImageAvailableListener({ rr ->
            val img = runCatching { rr.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
            if (ch.trySend(img).isFailure) img.close()
        }, handler)
        readers[index] = r
        channels[index] = ch
        return r
    }

    private fun sizesFor(c: CameraCharacteristics): Pair<Size, Size> {
        val preview = CameraRepository.chooseByAspect(CameraRepository.previewSizes(c), 16f / 9f, 1280, 720) ?: Size(1280, 720)
        val jpegs = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        val jpeg = CameraRepository.chooseByAspect(jpegs, 4f / 3f, 1920, 1440) ?: preview
        return preview to jpeg
    }

    private suspend fun session(dev: CameraDevice, outs: List<OutputConfiguration>): CameraCaptureSession {
        val d = CompletableDeferred<CameraCaptureSession>()
        val sc = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { d.complete(s) }
            override fun onConfigureFailed(s: CameraCaptureSession) { d.completeExceptionally(IllegalStateException("세션 구성 실패")) }
        })
        dev.createCaptureSession(sc)
        return withTimeout(6000) { d.await() }
    }

    private suspend fun openLogical(p: MultiPair.Logical, a: PreviewTarget, b: PreviewTarget) {
        val dev = open(p.logicalId).also { devices += it }
        val ca = repo.characteristics(p.physA)
        val cb = repo.characteristics(p.physB)
        val (pa, ja) = sizesFor(ca)
        val (pb, jb) = sizesFor(cb)
        val sa = a.surfaceFor(pa)
        val sb = b.surfaceFor(pb)
        val previewOuts = listOf(
            OutputConfiguration(sa).apply { setPhysicalCameraId(p.physA) },
            OutputConfiguration(sb).apply { setPhysicalCameraId(p.physB) },
        )
        val s = try {
            val ra = newReader(0, ja)
            val rb = newReader(1, jb)
            session(dev, previewOuts + listOf(
                OutputConfiguration(ra.surface).apply { setPhysicalCameraId(p.physA) },
                OutputConfiguration(rb.surface).apply { setPhysicalCameraId(p.physB) },
            ))
        } catch (t: Throwable) {
            Log.w(TAG, "logical + jpeg failed, preview only", t)
            for (i in 0..1) { readers[i]?.close(); readers[i] = null; channels[i] = null }
            report("물리 JPEG 동시 출력 미지원 → 프리뷰만 표시")
            session(dev, previewOuts)
        }
        sessions += s
        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(sa); addTarget(sb)
            if (Build.VERSION.SDK_INT >= 37 && repo.characteristics(p.logicalId).availableCaptureRequestKeys.orEmpty().any { it.name == CaptureRequest.LOGICAL_MULTI_CAMERA_ADDITIONAL_RESULTS.name }) {
                // Android 17: 활성 물리 카메라 외의 물리 카메라 결과 메타데이터도 받기
                set(CaptureRequest.LOGICAL_MULTI_CAMERA_ADDITIONAL_RESULTS, true)
            }
        }.build()
        s.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastLive < 300) return
                lastLive = now
                val phys = result.physicalCameraTotalResults
                _state.update { st ->
                    st.copy(
                        live = listOf(p.physA, p.physB).map { id -> phys[id]?.let(::summary) ?: "결과 없음" },
                        info = "활성 물리: ${result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: "-"}",
                    )
                }
            }
        }, handler)
        _state.update {
            it.copy(
                views = listOf(
                    MultiView("물리 #${p.physA}", pa, ca.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, ca.get(CameraCharacteristics.LENS_FACING) ?: 1),
                    MultiView("물리 #${p.physB}", pb, cb.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, cb.get(CameraCharacteristics.LENS_FACING) ?: 1),
                ),
            )
        }
    }

    private suspend fun openConcurrent(p: MultiPair.Concurrent, a: PreviewTarget, b: PreviewTarget) {
        val ids = listOf(p.idA, p.idB)
        val textures = listOf(a, b)
        val views = mutableListOf<MultiView>()
        val pending = mutableListOf<Triple<CameraDevice, List<OutputConfiguration>, Surface>>()
        ids.forEachIndexed { i, id ->
            val c = repo.characteristics(id)
            val (ps, js) = sizesFor(c)
            val surface = textures[i].surfaceFor(ps)
            val dev = open(id).also { devices += it }
            val reader = newReader(i, js)
            pending += Triple(dev, listOf(OutputConfiguration(surface), OutputConfiguration(reader.surface)), surface)
            views += MultiView("#$id ${if (c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT) "전면" else "후면"}", ps,
                c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, c.get(CameraCharacteristics.LENS_FACING) ?: 1)
        }
        // 동시 세션 조합 지원 여부 질의 (API 30+)
        val supported = runCatching {
            val map = pending.associate { (dev, outs, _) ->
                dev.id to SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {}
                    override fun onConfigureFailed(s: CameraCaptureSession) {}
                })
            }
            repo.manager.isConcurrentSessionConfigurationSupported(map)
        }.getOrNull()
        pending.forEachIndexed { i, (dev, outs, surface) ->
            val s = session(dev, outs)
            sessions += s
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }.build()
            s.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
                private var last = 0L
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - last < 300) return
                    last = now
                    _state.update { st -> st.copy(live = st.live.toMutableList().also { it[i] = summary(result) }) }
                }
            }, handler)
        }
        _state.update {
            it.copy(views = views, info = "동시 세션 지원 질의: ${when (supported) { true -> "지원"; false -> "미지원(동작은 시도됨)"; null -> "알 수 없음" }}")
        }
    }

    private fun summary(r: CaptureResult): String {
        val iso = r.get(CaptureResult.SENSOR_SENSITIVITY)
        val exp = r.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val f = r.get(CaptureResult.LENS_FOCAL_LENGTH)
        return "ISO ${iso ?: "-"} · ${exp?.let(Meta::formatNs) ?: "-"} · f=${f?.let { "%.2f".format(it) } ?: "-"}mm"
    }

    private suspend fun doCapture() {
        if (sessions.isEmpty()) return
        val present = (0..1).filter { readers[it] != null }
        if (present.isEmpty()) return report("이 조합은 캡처 출력이 없습니다")
        _state.update { it.copy(capturing = true) }
        try {
            present.forEach { i -> channels[i]?.let { ch -> while (true) ch.tryReceive().getOrNull()?.close() ?: break } }
            if (pair is MultiPair.Logical) {
                val dev = devices.first()
                val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { present.forEach { addTarget(readers[it]!!.surface) } }.build()
                sessions.first().capture(req, null, handler)
            } else {
                present.forEach { i ->
                    val dev = devices[i]
                    val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(readers[i]!!.surface) }.build()
                    sessions[i].capture(req, null, handler)
                }
            }
            var thumb: android.graphics.Bitmap? = null
            present.forEach { i ->
                val img = withTimeout(6000) { channels[i]!!.receive() }
                try {
                    val bytes = img.planes[0].buffer.let { buf -> ByteArray(buf.remaining()).also { buf.get(it) } }
                    MediaSaver.saveImage(context, bytes, "image/jpeg", "jpg")
                    if (thumb == null) thumb = MediaSaver.thumbnail(bytes, 0)
                } finally { img.close() }
            }
            _state.update { it.copy(lastThumb = thumb ?: it.lastThumb) }
            report("${present.size}장 저장됨")
        } finally {
            _state.update { it.copy(capturing = false) }
        }
    }

    private fun report(msg: String) = _state.update { it.copy(message = msg, messageSeq = it.messageSeq + 1) }

    companion object { private const val TAG = "NeuCamMulti" }
}
