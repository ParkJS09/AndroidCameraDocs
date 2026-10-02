package com.eden.neucam.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 단일 카메라 Camera2 파이프라인.
 *
 * 모든 카메라 작업은 전용 HandlerThread 위의 코루틴에서 Mutex 로 직렬화된다.
 * UI 는 "원하는 상태"(활성 여부, 프리뷰 SurfaceView(PreviewTarget), SessionConfig, ManualControls, 오버라이드)만 넘기고
 * [reconcile] 이 실제 CameraDevice / 세션을 그 상태에 맞춘다.
 *
 * 세션 종류
 *  - 일반(SESSION_REGULAR): 사진/프로/동영상
 *  - 고속(SESSION_HIGH_SPEED): 120/240fps 등 슬로모션 동영상
 *  - 확장(CameraExtensionSession): Night/HDR/Bokeh/Face Retouch/Auto
 */
class CameraController(private val context: Context, val repo: CameraRepository) {

    private val thread = HandlerThread("NeuCam-Camera").apply { start() }
    val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val scope = CoroutineScope(SupervisorJob() + handler.asCoroutineDispatcher("camera"))
    private val mutex = Mutex()

    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()
    private val _live = MutableStateFlow(LiveResult())
    val live: StateFlow<LiveResult> = _live.asStateFlow()
    private val _resultValues = MutableStateFlow<Map<String, String>>(emptyMap())
    /** 최신 CaptureResult 전체 (옵션 탭이 보일 때만 수집) key.name -> 표시 문자열 */
    val resultValues: StateFlow<Map<String, String>> = _resultValues.asStateFlow()

    @Volatile var collectAllResults = false
    @Volatile var deviceOrientation = 0

    // ---- 원하는 상태
    private var active = false
    private var previewTarget: PreviewTarget? = null
    private var config: SessionConfig? = null
    // UI 스레드에서 바뀌고 카메라 스레드에서 읽힌다 → 불변 객체를 volatile 로 교체
    @Volatile private var controls = ManualControls()
    @Volatile private var overrides: Map<String, Any> = emptyMap()
    private val lock = Any()
    private val repeatingScheduled = AtomicBoolean(false)

    // ---- 실제 상태
    private var device: CameraDevice? = null
    private var chars: CameraCharacteristics? = null
    private var desc: CameraDesc? = null
    private var requestKeys: Map<String, CaptureRequest.Key<*>> = emptyMap()
    private var session: CameraCaptureSession? = null
    private var extSession: CameraExtensionSession? = null
    private var extAllowedKeys: Set<String>? = null
    private var highSpeed = false
    private var hsPreviewRange: Range<Int>? = null
    private var activeVideoSize: Size? = null
    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null
    private val readers = mutableListOf<ImageReader>()
    private val imageChannels = HashMap<ImageReader, Channel<Image>>()
    private var sessionSignature: Any? = null
    @Volatile private var latestResult: CaptureResult? = null

    private var recorder: MediaRecorder? = null
    private var recordUri: Uri? = null
    private var recordPfd: ParcelFileDescriptor? = null
    private var recording = false

    // ------------------------------------------------------------------ public API

    fun setActive(value: Boolean) = locked { active = value; reconcile() }

    fun setPreviewTarget(target: PreviewTarget) = locked { previewTarget = target; reconcile() }

    /** SurfaceView 의 Surface 가 파괴될 때: 이 Job 이 끝나야(세션이 닫혀야) surfaceDestroyed 가 리턴된다 */
    fun releaseTarget(target: PreviewTarget): Job = locked {
        if (previewTarget === target) { previewTarget = null; reconcile() }
    }

    fun updateConfig(transform: (SessionConfig) -> SessionConfig) = locked {
        val cur = config ?: return@locked
        val next = transform(cur)
        if (next == cur) return@locked
        if (next.cameraId != cur.cameraId) synchronized(lock) { controls = controls.copy(zoom = 1f, meteringRegion = null, afManual = false, aeManual = false, cct = null) }
        config = next
        _state.update { it.copy(config = next, controls = controls) }
        reconcile()
    }

    fun setConfig(cfg: SessionConfig) = locked {
        if (config == cfg) return@locked
        config = cfg
        _state.update { it.copy(config = cfg) }
        reconcile()
    }

    /** 슬라이더처럼 빠르게 바뀌는 값: 상태는 즉시 바꾸고 반복 요청 갱신은 하나로 합친다(conflate) */
    fun updateControls(transform: (ManualControls) -> ManualControls) {
        val next = synchronized(lock) { transform(controls).also { controls = it } }
        _state.update { it.copy(controls = next) }
        scheduleRepeating()
    }

    fun setOverride(keyName: String, value: Any?) {
        val next = synchronized(lock) {
            (if (value == null) overrides - keyName else overrides + (keyName to value)).also { overrides = it }
        }
        _state.update { it.copy(overrides = next) }
        scheduleRepeating()
    }

    fun clearOverrides() {
        synchronized(lock) { overrides = emptyMap() }
        _state.update { it.copy(overrides = emptyMap()) }
        scheduleRepeating()
    }

    private fun scheduleRepeating() {
        if (!repeatingScheduled.compareAndSet(false, true)) return
        locked {
            repeatingScheduled.set(false)
            runCatching { startRepeating() }.onFailure { report("요청 갱신 실패: ${it.message}") }
        }
    }

    /** 트리거류 키를 반복 요청이 아닌 단발 캡처로 전송 */
    fun sendOneShot(keyName: String, value: Any) = locked {
        val key = requestKeys[keyName] ?: return@locked
        val preview = previewSurface ?: return@locked
        if (highSpeed || !allowed(keyName)) return@locked report("현재 세션에서 $keyName 단발 전송 불가")
        val template = if (config?.mode == CaptureMode.VIDEO) CameraDevice.TEMPLATE_RECORD else controls.template
        val b = buildRequest(template, listOf(preview))
        @Suppress("UNCHECKED_CAST")
        b.set(key as CaptureRequest.Key<Any>, value)
        val es = extSession
        if (es != null) es.capture(b.build(), executor, extCallback) else session?.capture(b.build(), null, handler)
        report("${Meta.shortName(keyName)} = $value 전송")
    }

    /** 옵션 탭: 요청 템플릿의 기본값 (카메라가 열려 있어야 함) */
    suspend fun templateDefaults(template: Int): Map<String, Any?> = mutex.withLock {
        val dev = device ?: return@withLock emptyMap()
        val b = runCatching { dev.createCaptureRequest(template) }.getOrNull() ?: return@withLock emptyMap()
        requestKeys.mapValues { (_, k) -> runCatching { b.get(k) }.getOrNull() }
    }

    fun takePhoto() = locked { capture() }

    fun toggleRecording() = locked { if (recording) stopRecording() else startRecording() }

    /** nx, ny: 센서 정규화 좌표 */
    fun focusAt(nx: Float, ny: Float) = locked { meter(nx, ny) }

    fun resetFocus() = locked {
        synchronized(lock) { controls = controls.copy(meteringRegion = null) }
        _state.update { it.copy(controls = controls) }
        triggerAf(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        runCatching { startRepeating() }
    }

    fun release() {
        scope.launch { mutex.withLock { closeDevice() } }.invokeOnCompletion { thread.quitSafely() }
    }

    // ------------------------------------------------------------------ reconcile

    private fun locked(block: suspend () -> Unit): Job =
        scope.launch {
            mutex.withLock {
                try {
                    block()
                } catch (t: Throwable) {
                    Log.e(TAG, "camera op failed", t)
                    report(t.message ?: t.javaClass.simpleName, error = true)
                }
            }
        }

    private suspend fun reconcile() {
        val cfg = config
        val st = previewTarget
        if (!active || cfg == null) {
            closeDevice(); return
        }
        if (st == null) {
            closeSession(); return
        }
        try {
            if (device == null || device?.id != cfg.cameraId) {
                closeDevice()
                openDevice(cfg.cameraId)
            }
            val sig = listOf(cfg, System.identityHashCode(st))
            if (sessionSignature != sig || (session == null && extSession == null)) {
                closeSession()
                createSession(cfg, st)
                sessionSignature = sig
            }
        } catch (t: Throwable) {
            Log.e(TAG, "reconcile failed", t)
            closeSession()
            _state.update { it.copy(status = CameraStatus.ERROR, error = t.message ?: t.javaClass.simpleName) }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openDevice(id: String) {
        _state.update { it.copy(status = CameraStatus.OPENING, error = null) }
        val dev = suspendCancellableCoroutine { cont ->
            repo.manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (cont.isActive) cont.resume(d) else d.close()
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("카메라 연결 끊김 (다른 앱이 사용 중일 수 있음)"))
                    else onDeviceLost(d, "카메라 연결 끊김")
                }

                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    val msg = "카메라 오류 ${errorName(error)}"
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(msg)) else onDeviceLost(d, msg)
                }
            })
        }
        device = dev
        chars = repo.characteristics(id)
        desc = repo.describe(id)
        requestKeys = chars!!.availableCaptureRequestKeys.orEmpty().associateBy { it.name }
        _state.update {
            it.copy(
                facing = desc!!.facing,
                sensorOrientation = desc!!.sensorOrientation,
            )
        }
    }

    private fun onDeviceLost(d: CameraDevice, msg: String) = locked {
        if (device === d) {
            closeSession()
            device = null
            _state.update { it.copy(status = CameraStatus.ERROR, error = msg) }
        }
    }

    private fun closeSession() {
        if (recording) abortRecording()
        runCatching { session?.close() }
        session = null
        runCatching { extSession?.close() }
        extSession = null
        extAllowedKeys = null
        readers.forEach { runCatching { it.close() } }
        readers.clear()
        imageChannels.values.forEach { ch -> ch.close(); while (true) ch.tryReceive().getOrNull()?.close() ?: break }
        imageChannels.clear()
        previewSurface = null // SurfaceView 소유 — 해제하지 않는다
        recorderSurface?.release(); recorderSurface = null
        highSpeed = false
        hsPreviewRange = null
        activeVideoSize = null
        sessionSignature = null
    }

    private fun closeDevice() {
        closeSession()
        runCatching { device?.close() }
        device = null
        chars = null
        desc = null
        _state.update { it.copy(status = CameraStatus.CLOSED, previewSize = null, sessionKind = "") }
    }

    // ------------------------------------------------------------------ sessions

    private suspend fun createSession(cfg: SessionConfig, st: PreviewTarget) {
        val d = desc ?: error("no camera")
        when {
            cfg.mode == CaptureMode.EXTENSION && cfg.extension != null && cfg.extension in d.caps.extensions ->
                createExtensionSession(cfg, st, cfg.extension)
            cfg.mode == CaptureMode.VIDEO -> createVideoSession(cfg, st)
            else -> createPhotoSession(cfg, st)
        }
        _state.update { it.copy(status = CameraStatus.RUNNING, error = null) }
    }

    private fun previewAspect(cfg: SessionConfig) = if (cfg.previewMode == PreviewMode.RATIO_16_9) 16f / 9f else cfg.windowAspect

    /** SurfaceView 버퍼를 카메라 출력 크기로 맞춘다 (setFixedSize → surfaceChanged 대기) */
    private suspend fun preparePreview(target: PreviewTarget, size: Size): Surface =
        target.surfaceFor(size).also { previewSurface = it }

    private fun supportsStreamUseCase() = Build.VERSION.SDK_INT >= 33 &&
        desc?.capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE) == true

    private fun output(surface: Surface, useCase: Long? = null, dynamicRange: Long? = null): OutputConfiguration =
        OutputConfiguration(surface).apply {
            if (useCase != null && supportsStreamUseCase() && Build.VERSION.SDK_INT >= 33) streamUseCase = useCase
            if (dynamicRange != null && dynamicRange != DynamicRangeProfiles.STANDARD && Build.VERSION.SDK_INT >= 33) dynamicRangeProfile = dynamicRange
        }

    private suspend fun createPhotoSession(cfg: SessionConfig, st: PreviewTarget) {
        val d = desc!!
        val caps = d.caps
        val aspect = previewAspect(cfg)
        val pSize = CameraRepository.chooseByAspect(CameraRepository.previewSizes(chars!!), aspect) ?: Size(1920, 1080)
        val fmt = cfg.photoFormat.takeIf { it in caps.photoFormats } ?: PhotoFormat.JPEG
        val sizes = caps.photoSizes[fmt].orEmpty()
        val still = cfg.photoSize?.takeIf { it in sizes } ?: sizes.firstOrNull() ?: Size(1920, 1080)

        data class Attempt(val preview: Size, val outputs: List<Pair<Size, Int>>, val note: String?)
        val attempts = buildList {
            add(Attempt(pSize, fmt.formats.map { f -> (if (f == ImageFormat.RAW_SENSOR) caps.photoSizes[PhotoFormat.RAW]?.firstOrNull() ?: still else still) to f }, null))
            if (fmt.formats.size > 1) add(Attempt(pSize, listOf(still to fmt.formats.last()), "RAW+JPEG 조합 미지원 → ${Meta.formatName(fmt.formats.last())} 만 사용"))
            val jpegs = caps.photoSizes[PhotoFormat.JPEG].orEmpty()
            val safe = CameraRepository.chooseByAspect(jpegs, CameraRepository.aspectOf(still), 4032, 3024)
            if (safe != null) add(Attempt(CameraRepository.chooseByAspect(CameraRepository.previewSizes(chars!!), aspect, 1280, 720) ?: pSize, listOf(safe to ImageFormat.JPEG), "스트림 조합 미지원 → 안전 해상도로 폴백"))
        }
        var last: Throwable? = null
        for (a in attempts) {
            try {
                val surface = preparePreview(st, a.preview)
                val outs = mutableListOf(output(surface, CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong()))
                a.outputs.forEach { (size, f) -> outs += output(newReader(size, f).surface, CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE.toLong()) }
                session = configure(SessionConfiguration.SESSION_REGULAR, outs, null)
                a.note?.let { report(it) }
                _state.update {
                    it.copy(
                        previewSize = a.preview,
                        sessionKind = "REGULAR · ${a.outputs.joinToString(" + ") { (s, f) -> "${Meta.formatName(f)} ${s.width}x${s.height}" }}",
                    )
                }
                startRepeating()
                return
            } catch (t: Throwable) {
                Log.w(TAG, "photo session attempt failed: ${a.note}", t)
                last = t
                readers.forEach { runCatching { it.close() } }; readers.clear(); imageChannels.clear()
            }
        }
        throw last ?: IllegalStateException("세션 생성 실패")
    }

    private suspend fun createVideoSession(cfg: SessionConfig, st: PreviewTarget) {
        val caps = desc!!.caps
        val all = (caps.videoSizes + caps.highSpeedFps.keys).distinct()
        val vSize = cfg.videoSize?.takeIf { it in all }
            ?: caps.videoSizes.firstOrNull { it.width <= 1920 && it.height <= 1080 && kotlin.math.abs(CameraRepository.aspectOf(it) - 16f / 9f) < 0.02f }
            ?: caps.videoSizes.firstOrNull() ?: Size(1920, 1080)
        highSpeed = cfg.videoFps > 60 && caps.highSpeedFps[vSize]?.contains(cfg.videoFps) == true
        activeVideoSize = vSize
        val pSize = if (highSpeed) vSize else {
            CameraRepository.chooseByAspect(CameraRepository.previewSizes(chars!!), CameraRepository.aspectOf(vSize)) ?: vSize
        }
        if (highSpeed) {
            val map = chars!!.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            hsPreviewRange = map.getHighSpeedVideoFpsRangesFor(vSize).firstOrNull { it.upper == cfg.videoFps && it.lower < it.upper }
                ?: Range(cfg.videoFps, cfg.videoFps)
        }
        val fps = if (highSpeed) cfg.videoFps else cfg.videoFps.coerceAtMost(caps.fpsFor[vSize]?.maxOrNull() ?: 30)

        // 영구 입력 Surface: 녹화 시작 전에도 세션에 포함시켜야 녹화 시작 시 세션 재구성이 필요 없다.
        val persistent = MediaCodec.createPersistentInputSurface()
        recorderSurface = persistent
        buildRecorder(File(context.cacheDir, "prime.mp4").absolutePath, null, vSize, fps, audio = false, hint = 0, cfg = cfg).apply {
            try { prepare() } finally { release() }
        }
        val dr = cfg.dynamicRange.takeIf { it in caps.dynamicRanges } ?: DynamicRangeProfiles.STANDARD
        val surface = preparePreview(st, pSize)
        val outs = listOf(
            output(surface, CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong(), dr),
            output(persistent, CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD.toLong(), dr),
        )
        val params = device!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            if (!highSpeed) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange(fps))
        }.build()
        session = configure(if (highSpeed) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR, outs, params)
        _state.update {
            it.copy(
                previewSize = pSize,
                sessionKind = (if (highSpeed) "HIGH_SPEED" else "REGULAR") + " · ${vSize.width}x${vSize.height}@$fps" +
                    if (dr != DynamicRangeProfiles.STANDARD) " · ${Meta.dynamicRangeName(dr)}" else "",
            )
        }
        startRepeating()
    }

    private suspend fun createExtensionSession(cfg: SessionConfig, st: PreviewTarget, ext: Int) {
        val ec = repo.manager.getCameraExtensionCharacteristics(device!!.id)
        // 확장 프리뷰 크기 질의는 SurfaceTexture 클래스만 지원하지만, 결과 크기는 SurfaceView 출력에도 그대로 쓸 수 있다
        val pSizes = ec.getExtensionSupportedSizes(ext, SurfaceTexture::class.java)
        val pSize = CameraRepository.chooseByAspect(pSizes, previewAspect(cfg)) ?: error("확장 프리뷰 크기 없음")
        val jpegSizes = ec.getExtensionSupportedSizes(ext, ImageFormat.JPEG)
        val (fmt, stillSizes) = if (jpegSizes.isNotEmpty()) ImageFormat.JPEG to jpegSizes
        else ImageFormat.YUV_420_888 to ec.getExtensionSupportedSizes(ext, ImageFormat.YUV_420_888)
        val still = stillSizes.maxByOrNull { it.width.toLong() * it.height } ?: error("확장 캡처 크기 없음")
        extAllowedKeys = if (Build.VERSION.SDK_INT >= 33) ec.getAvailableCaptureRequestKeys(ext).map { it.name }.toSet() else emptySet()

        val surface = preparePreview(st, pSize)
        val reader = newReader(still, fmt)
        val deferred = CompletableDeferred<CameraExtensionSession>()
        val esc = ExtensionSessionConfiguration(
            ext,
            listOf(OutputConfiguration(surface), OutputConfiguration(reader.surface)),
            executor,
            object : CameraExtensionSession.StateCallback() {
                override fun onConfigured(session: CameraExtensionSession) { deferred.complete(session) }
                override fun onConfigureFailed(session: CameraExtensionSession) {
                    deferred.completeExceptionally(IllegalStateException("확장 세션 구성 실패"))
                }
            },
        )
        if (Build.VERSION.SDK_INT >= 37) {
            // Android 17: 세션 생성 시점에 확장 강도 등 세션 전역 파라미터 전달
            runCatching { esc.setSessionWideParams(buildRequest(CameraDevice.TEMPLATE_PREVIEW, emptyList()).build()) }
        }
        device!!.createExtensionSession(esc)
        extSession = withTimeout(10_000) { deferred.await() }
        _state.update {
            it.copy(previewSize = pSize, sessionKind = "EXTENSION ${ExtensionNames.name(ext)} · ${Meta.formatName(fmt)} ${still.width}x${still.height}")
        }
        startRepeating()
    }

    private suspend fun configure(type: Int, outputs: List<OutputConfiguration>, params: CaptureRequest?): CameraCaptureSession {
        val deferred = CompletableDeferred<CameraCaptureSession>()
        val sc = SessionConfiguration(type, outputs, executor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { deferred.complete(s) }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                deferred.completeExceptionally(IllegalStateException("세션 구성 실패 (onConfigureFailed)"))
            }
        })
        if (params != null) sc.sessionParameters = params
        if (querySupport(sc) == false) throw IllegalStateException("지원하지 않는 스트림 조합")
        device!!.createCaptureSession(sc)
        return withTimeout(6_000) { deferred.await() }
    }

    /** API 35+: CameraDeviceSetup 로 세션 지원 여부를 미리 질의. 모르면 null */
    private fun querySupport(sc: SessionConfiguration): Boolean? = runCatching {
        val id = device!!.id
        if (Build.VERSION.SDK_INT >= 35 && repo.manager.isCameraDeviceSetupSupported(id)) {
            repo.manager.getCameraDeviceSetup(id).isSessionConfigurationSupported(sc)
        } else {
            device!!.isSessionConfigurationSupported(sc)
        }
    }.getOrNull()

    private fun newReader(size: Size, format: Int): ImageReader {
        val reader = ImageReader.newInstance(size.width, size.height, format, 4)
        val ch = Channel<Image>(Channel.UNLIMITED)
        reader.setOnImageAvailableListener({ r ->
            val img = runCatching { r.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
            if (ch.trySend(img).isFailure) img.close()
        }, handler)
        readers += reader
        imageChannels[reader] = ch
        return reader
    }

    // ------------------------------------------------------------------ requests

    private fun fpsRange(fps: Int): Range<Int> {
        val ranges = chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return Range(fps, fps)
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: ranges.minByOrNull { kotlin.math.abs(it.upper - fps) } ?: Range(fps, fps)
    }

    private fun allowed(name: String): Boolean {
        if (name !in requestKeys) return false
        val ext = extAllowedKeys
        return ext == null || name in ext
    }

    private fun <T> CaptureRequest.Builder.put(key: CaptureRequest.Key<T>, value: T) {
        if (allowed(key.name)) set(key, value)
    }

    private fun buildRequest(template: Int, targets: List<Surface>, still: Boolean = false): CaptureRequest.Builder {
        val b = device!!.createCaptureRequest(template)
        targets.forEach(b::addTarget)
        applyControls(b, still)
        overrides.forEach { (name, value) ->
            val key = requestKeys[name] ?: return@forEach
            if (!allowed(name)) return@forEach
            @Suppress("UNCHECKED_CAST")
            runCatching { b.set(key as CaptureRequest.Key<Any>, value) }.onFailure { Log.w(TAG, "override $name", it) }
        }
        return b
    }

    private fun applyControls(b: CaptureRequest.Builder, still: Boolean) {
        val ctl = controls
        val d = desc ?: return
        val caps = d.caps
        val cfg = config ?: return
        val isExt = extSession != null
        b.put(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.put(CaptureRequest.CONTROL_ZOOM_RATIO, ctl.zoom.coerceIn(caps.zoomRange.lower, caps.zoomRange.upper))

        // --- 노출
        if (ctl.aeManual && caps.manualSensor && !isExt) {
            b.put(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            caps.isoRange?.let { b.put(CaptureRequest.SENSOR_SENSITIVITY, ctl.iso.coerceIn(it.lower, it.upper)) }
            caps.exposureRange?.let { b.put(CaptureRequest.SENSOR_EXPOSURE_TIME, ctl.exposureNs.coerceIn(it.lower, it.upper)) }
            b.put(
                CaptureRequest.FLASH_MODE,
                when {
                    ctl.flash == FlashMode.TORCH -> CameraMetadata.FLASH_MODE_TORCH
                    still && ctl.flash == FlashMode.ON -> CameraMetadata.FLASH_MODE_SINGLE
                    else -> CameraMetadata.FLASH_MODE_OFF
                },
            )
        } else {
            val aeMode = if (!caps.flash) CameraMetadata.CONTROL_AE_MODE_ON else when (ctl.flash) {
                FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                else -> CameraMetadata.CONTROL_AE_MODE_ON
            }
            b.put(CaptureRequest.CONTROL_AE_MODE, aeMode)
            if (caps.flash) b.put(CaptureRequest.FLASH_MODE, if (ctl.flash == FlashMode.TORCH) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
            caps.evRange?.let { b.put(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ctl.ev.coerceIn(it.lower, it.upper)) }
            b.put(CaptureRequest.CONTROL_AE_LOCK, ctl.aeLock)
        }
        if (cfg.mode == CaptureMode.VIDEO && !highSpeed) b.put(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange(cfg.videoFps))

        // --- 초점
        val region = ctl.meteringRegion
        when {
            ctl.afManual && caps.minFocusDiopter > 0f && CameraMetadata.CONTROL_AF_MODE_OFF in caps.afModes -> {
                b.put(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.put(CaptureRequest.LENS_FOCUS_DISTANCE, ctl.focusDiopter.coerceIn(0f, caps.minFocusDiopter))
            }
            region != null && CameraMetadata.CONTROL_AF_MODE_AUTO in caps.afModes -> {
                b.put(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            }
            else -> {
                val preferred = if (cfg.mode == CaptureMode.VIDEO) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                val mode = when {
                    preferred in caps.afModes -> preferred
                    CameraMetadata.CONTROL_AF_MODE_AUTO in caps.afModes -> CameraMetadata.CONTROL_AF_MODE_AUTO
                    else -> null
                }
                mode?.let { b.put(CaptureRequest.CONTROL_AF_MODE, it) }
            }
        }
        if (region != null) {
            val c = chars!!
            if ((c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) b.put(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            if ((c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) b.put(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        }

        // --- 화이트밸런스
        val cct = ctl.cct
        if (cct != null && caps.cctRange != null && Build.VERSION.SDK_INT >= 36) {
            b.put(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.put(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_CCT)
            b.put(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE, cct.coerceIn(caps.cctRange.lower, caps.cctRange.upper))
        } else {
            if (ctl.awbMode in caps.awbModes) b.put(CaptureRequest.CONTROL_AWB_MODE, ctl.awbMode)
        }
        b.put(CaptureRequest.CONTROL_AWB_LOCK, ctl.awbLock)

        // --- 안정화
        ctl.ois?.let { on ->
            val m = if (on) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            if (m in caps.oisModes) b.put(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, m)
        }
        ctl.videoStabilization?.let { if (it in caps.stabilizationModes) b.put(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, it) }

        // --- 확장 강도 (API 34)
        if (Build.VERSION.SDK_INT >= 34) ctl.extensionStrength?.let { b.put(CaptureRequest.EXTENSION_STRENGTH, it.coerceIn(0, 100)) }

        if (still) {
            b.put(CaptureRequest.JPEG_QUALITY, ctl.jpegQuality.coerceIn(1, 100).toByte())
            b.put(CaptureRequest.JPEG_ORIENTATION, currentJpegOrientation())
        }
    }

    private fun currentJpegOrientation(): Int {
        val d = desc ?: return 0
        return PreviewTransform.jpegOrientation(d.sensorOrientation, deviceOrientation, d.facing == CameraMetadata.LENS_FACING_FRONT)
    }

    private fun startRepeating() {
        val preview = previewSurface ?: return
        val es = extSession
        if (es != null) {
            es.setRepeatingRequest(buildRequest(CameraDevice.TEMPLATE_PREVIEW, listOf(preview)).build(), executor, extCallback)
            return
        }
        val s = session ?: return
        val cfg = config ?: return
        val targets = buildList {
            add(preview)
            if (recording) recorderSurface?.let(::add)
        }
        val template = if (cfg.mode == CaptureMode.VIDEO) CameraDevice.TEMPLATE_RECORD else controls.template
        val b = buildRequest(template, targets)
        if (highSpeed && s is CameraConstrainedHighSpeedCaptureSession) {
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, if (recording) Range(cfg.videoFps, cfg.videoFps) else hsPreviewRange ?: Range(cfg.videoFps, cfg.videoFps))
            s.setRepeatingBurst(s.createHighSpeedRequestList(b.build()), repeatingCallback, handler)
        } else {
            s.setRepeatingRequest(b.build(), repeatingCallback, handler)
        }
    }

    // ------------------------------------------------------------------ results

    private var lastLive = 0L
    private var lastAll = 0L
    private var frames = 0

    private val repeatingCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) = onResult(result)
    }

    private val extCallback = object : CameraExtensionSession.ExtensionCaptureCallback() {
        override fun onCaptureResultAvailable(session: CameraExtensionSession, request: CaptureRequest, result: TotalCaptureResult) = onResult(result)
        override fun onCaptureStarted(session: CameraExtensionSession, request: CaptureRequest, timestamp: Long) {
            if (Build.VERSION.SDK_INT < 33) countFrame()
        }
    }

    private fun countFrame() {
        frames++
        val now = SystemClock.elapsedRealtime()
        if (now - lastLive >= 250) {
            val fps = frames * 1000f / (now - lastLive).coerceAtLeast(1)
            frames = 0
            lastLive = now
            _live.update { it.copy(fps = fps) }
        }
    }

    private fun onResult(r: CaptureResult) {
        latestResult = r
        frames++
        val now = SystemClock.elapsedRealtime()
        if (now - lastLive >= 250) {
            val fps = frames * 1000f / (now - lastLive).coerceAtLeast(1)
            frames = 0
            lastLive = now
            _live.value = LiveResult(
                iso = r.get(CaptureResult.SENSOR_SENSITIVITY),
                exposureNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                frameDurationNs = r.get(CaptureResult.SENSOR_FRAME_DURATION),
                focusDiopter = r.get(CaptureResult.LENS_FOCUS_DISTANCE),
                afState = r.get(CaptureResult.CONTROL_AF_STATE),
                aeState = r.get(CaptureResult.CONTROL_AE_STATE),
                awbState = r.get(CaptureResult.CONTROL_AWB_STATE),
                zoom = r.get(CaptureResult.CONTROL_ZOOM_RATIO),
                activePhysicalId = r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID),
                aperture = r.get(CaptureResult.LENS_APERTURE),
                focalLength = r.get(CaptureResult.LENS_FOCAL_LENGTH),
                cct = if (Build.VERSION.SDK_INT >= 36) r.get(CaptureResult.COLOR_CORRECTION_COLOR_TEMPERATURE) else null,
                fps = fps,
            )
        }
        if (collectAllResults && now - lastAll >= 400) {
            lastAll = now
            _resultValues.value = r.keys.associate { k -> k.name to Meta.format(r.get(k), Meta.resultFieldByKeyName[k.name]) }
        }
    }

    // ------------------------------------------------------------------ 3A

    private fun meter(nx: Float, ny: Float) {
        val c = chars ?: return
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val z = controls.zoom.coerceAtLeast(1f)
        val cropW = active.width() / z
        val cropH = active.height() / z
        val left = (active.width() - cropW) / 2f
        val top = (active.height() - cropH) / 2f
        val px = left + nx * cropW
        val py = top + ny * cropH
        val half = (minOf(cropW, cropH) * 0.08f)
        val rect = Rect(
            (px - half).toInt().coerceIn(0, active.width() - 1),
            (py - half).toInt().coerceIn(0, active.height() - 1),
            (px + half).toInt().coerceIn(1, active.width()),
            (py + half).toInt().coerceIn(1, active.height()),
        )
        synchronized(lock) { controls = controls.copy(meteringRegion = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)) }
        _state.update { it.copy(controls = controls) }
        startRepeating()
        if (!controls.afManual) triggerAf(CameraMetadata.CONTROL_AF_TRIGGER_START)
    }

    private fun triggerAf(trigger: Int) {
        val preview = previewSurface ?: return
        if (highSpeed) return
        runCatching {
            val b = buildRequest(if (config?.mode == CaptureMode.VIDEO) CameraDevice.TEMPLATE_RECORD else controls.template, listOf(preview))
            if (!allowed(CaptureRequest.CONTROL_AF_TRIGGER.name)) return
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
            val es = extSession
            if (es != null) es.capture(b.build(), executor, extCallback) else session?.capture(b.build(), null, handler)
        }
    }

    private suspend fun runPrecapture(s: CameraCaptureSession) {
        val preview = previewSurface ?: return
        val b = buildRequest(controls.template, listOf(preview))
        b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
        s.capture(b.build(), null, handler)
        val deadline = SystemClock.elapsedRealtime() + 1500
        var seenPrecapture = false
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(33)
            when (latestResult?.get(CaptureResult.CONTROL_AE_STATE)) {
                CameraMetadata.CONTROL_AE_STATE_PRECAPTURE -> seenPrecapture = true
                CameraMetadata.CONTROL_AE_STATE_CONVERGED, CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED, CameraMetadata.CONTROL_AE_STATE_LOCKED ->
                    if (seenPrecapture || SystemClock.elapsedRealtime() > deadline - 1200) return
            }
        }
    }

    // ------------------------------------------------------------------ still capture

    private suspend fun capture() {
        val cfg = config ?: return
        if (cfg.mode == CaptureMode.VIDEO) return report("동영상 모드에서는 녹화 버튼을 사용하세요")
        if (readers.isEmpty()) return report("캡처 출력이 구성되지 않았습니다")
        _state.update { it.copy(capturing = true) }
        try {
            readers.forEach { r -> val ch = imageChannels[r]!!; while (true) ch.tryReceive().getOrNull()?.close() ?: break }
            val orientation = currentJpegOrientation()
            val es = extSession
            if (es != null) {
                val reader = readers.first()
                val b = buildRequest(CameraDevice.TEMPLATE_STILL_CAPTURE, listOf(reader.surface), still = true)
                val failed = CompletableDeferred<Unit>()
                es.capture(b.build(), executor, object : CameraExtensionSession.ExtensionCaptureCallback() {
                    override fun onCaptureFailed(session: CameraExtensionSession, request: CaptureRequest) {
                        failed.completeExceptionally(IllegalStateException("확장 캡처 실패"))
                    }
                })
                val img = withTimeout(15_000) { imageChannels[reader]!!.receive() }
                save(listOf(img), null, orientation)
            } else {
                val s = session ?: return
                if (!controls.aeManual && (controls.flash == FlashMode.AUTO || controls.flash == FlashMode.ON)) runPrecapture(s)
                val targets = readers.map { it.surface } + listOfNotNull(previewSurface)
                val b = buildRequest(CameraDevice.TEMPLATE_STILL_CAPTURE, targets, still = true)
                val result = CompletableDeferred<TotalCaptureResult>()
                s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, r: TotalCaptureResult) {
                        result.complete(r)
                    }

                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        result.completeExceptionally(IllegalStateException("캡처 실패 reason=${failure.reason}"))
                    }
                }, handler)
                val timeout = 6_000L + (if (controls.aeManual) controls.exposureNs / 1_000_000L * 2 else 0L)
                val images = readers.map { r -> withTimeout(timeout) { imageChannels[r]!!.receive() } }
                val total = withTimeout(timeout) { result.await() }
                save(images, total, orientation)
            }
        } finally {
            _state.update { it.copy(capturing = false) }
        }
    }

    private fun save(images: List<Image>, result: TotalCaptureResult?, orientation: Int) {
        var lastUri: Uri? = null
        var thumb: android.graphics.Bitmap? = null
        val formats = images.joinToString(" + ") { Meta.formatName(it.format) }
        images.forEach { img ->
            try {
                when (img.format) {
                    ImageFormat.JPEG, ImageFormat.JPEG_R, ImageFormat.HEIC -> {
                        val bytes = img.planes[0].buffer.let { buf -> ByteArray(buf.remaining()).also { buf.get(it) } }
                        val (mime, ext) = if (img.format == ImageFormat.HEIC) "image/heic" to "heic" else "image/jpeg" to "jpg"
                        lastUri = MediaSaver.saveImage(context, bytes, mime, ext)
                        if (thumb == null && img.format != ImageFormat.HEIC) thumb = MediaSaver.thumbnail(bytes, orientation)
                    }
                    ImageFormat.RAW_SENSOR -> {
                        val r = result ?: error("RAW 저장에는 CaptureResult 가 필요")
                        val (uri, out) = MediaSaver.openImage(context, "image/x-adobe-dng", "dng")
                        out.use { o ->
                            DngCreator(chars!!, r).use { dng ->
                                dng.setOrientation(exifOrientation(orientation))
                                dng.writeImage(o, img)
                            }
                        }
                        MediaSaver.publish(context, uri)
                        lastUri = lastUri ?: uri
                    }
                    ImageFormat.YUV_420_888 -> {
                        val bytes = yuvToJpeg(img, controls.jpegQuality, orientation)
                        lastUri = MediaSaver.saveImage(context, bytes, "image/jpeg", "jpg")
                        thumb = thumb ?: MediaSaver.thumbnail(bytes, 0)
                    }
                    else -> report("저장 미지원 포맷 ${Meta.formatName(img.format)}")
                }
            } finally {
                img.close()
            }
        }
        _state.update { it.copy(lastCapture = lastUri ?: it.lastCapture, lastThumb = thumb ?: it.lastThumb) }
        report("저장됨: $formats")
    }

    private fun exifOrientation(deg: Int) = when (deg) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    /** 확장 세션이 YUV 만 지원할 때: NV21 로 변환 후 JPEG 인코딩 + EXIF 방향 기록 */
    private fun yuvToJpeg(img: Image, quality: Int, orientation: Int): ByteArray {
        val w = img.width
        val h = img.height
        val nv21 = ByteArray(w * h * 3 / 2)
        val y = img.planes[0]
        var pos = 0
        for (row in 0 until h) {
            y.buffer.position(row * y.rowStride)
            y.buffer.get(nv21, pos, w)
            pos += w
        }
        val u = img.planes[1]
        val v = img.planes[2]
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                val vi = row * v.rowStride + col * v.pixelStride
                val ui = row * u.rowStride + col * u.pixelStride
                nv21[pos++] = v.buffer.get(vi)
                nv21[pos++] = u.buffer.get(ui)
            }
        }
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, out)
        val tmp = File(context.cacheDir, "yuv.jpg")
        tmp.writeBytes(out.toByteArray())
        ExifInterface(tmp).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation(orientation).toString())
            saveAttributes()
        }
        return tmp.readBytes().also { tmp.delete() }
    }

    // ------------------------------------------------------------------ video

    private fun buildRecorder(path: String?, fd: ParcelFileDescriptor?, size: Size, fps: Int, audio: Boolean, hint: Int, cfg: SessionConfig): MediaRecorder =
        MediaRecorder(context).apply {
            val surface = recorderSurface ?: error("recorder surface 없음")
            if (audio) setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (fd != null) setOutputFile(fd.fileDescriptor) else setOutputFile(path)
            val hdr = cfg.dynamicRange != DynamicRangeProfiles.STANDARD
            val base = size.width.toLong() * size.height * fps.coerceAtMost(120) / 8
            setVideoEncodingBitRate((base * (if (hdr) 1.3 else 1.0)).toLong().coerceIn(4_000_000L, 150_000_000L).toInt())
            setVideoFrameRate(fps)
            if (fps > 60) setCaptureRate(fps.toDouble())
            setVideoSize(size.width, size.height)
            setVideoEncoder(if (cfg.videoCodec == VideoCodec.HEVC || hdr) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            if (hdr) {
                val profile = when (cfg.dynamicRange) {
                    DynamicRangeProfiles.HDR10 -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                    DynamicRangeProfiles.HDR10_PLUS -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                    else -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                }
                setVideoEncodingProfileLevel(profile, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51)
            }
            if (audio) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(192_000)
                setAudioSamplingRate(48_000)
            }
            setOrientationHint(hint)
            setInputSurface(surface)
        }

    private fun startRecording() {
        val cfg = config ?: return
        if (cfg.mode != CaptureMode.VIDEO || session == null) return report("동영상 모드가 아닙니다")
        val size = activeVideoSize ?: return
        val fps = if (highSpeed) cfg.videoFps else cfg.videoFps.coerceAtMost(desc!!.caps.fpsFor[size]?.maxOrNull() ?: 30)
        val audio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val (uri, pfd) = MediaSaver.createVideo(context)
        val hint = currentJpegOrientation()
        val rec = try {
            buildRecorder(null, pfd, size, fps, audio && !highSpeed, hint, cfg).apply { prepare() }
        } catch (t: Throwable) {
            Log.w(TAG, "recorder prepare failed, retry without HDR profile", t)
            buildRecorder(null, pfd, size, fps, audio && !highSpeed, hint, cfg.copy(dynamicRange = DynamicRangeProfiles.STANDARD)).apply { prepare() }
        }
        recorder = rec
        recordUri = uri
        recordPfd = pfd
        recording = true
        startRepeating()
        rec.start()
        _state.update { it.copy(recording = true, recordStartMs = SystemClock.elapsedRealtime()) }
    }

    private suspend fun stopRecording() {
        val rec = recorder ?: return
        recording = false
        runCatching { startRepeating() }
        delay(80)
        val ok = runCatching { rec.stop() }.isSuccess
        rec.release()
        recorder = null
        runCatching { recordPfd?.close() }
        recordPfd = null
        val uri = recordUri
        recordUri = null
        if (uri != null) {
            if (ok) {
                MediaSaver.publish(context, uri)
                _state.update { it.copy(lastCapture = uri, lastThumb = MediaSaver.videoThumbnail(context, uri) ?: it.lastThumb) }
                report("동영상 저장됨")
            } else {
                MediaSaver.delete(context, uri)
                report("녹화가 너무 짧아 저장하지 않았습니다")
            }
        }
        _state.update { it.copy(recording = false) }
    }

    private fun abortRecording() {
        recording = false
        recorder?.let { r -> runCatching { r.stop() }; r.release() }
        recorder = null
        runCatching { recordPfd?.close() }
        recordPfd = null
        recordUri?.let { MediaSaver.publish(context, it) }
        recordUri = null
        _state.update { it.copy(recording = false) }
    }

    // ------------------------------------------------------------------ util

    private fun report(msg: String, error: Boolean = false) {
        _state.update { it.copy(message = msg, messageSeq = it.messageSeq + 1, error = if (error) msg else it.error) }
    }

    private fun errorName(code: Int) = when (code) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "IN_USE"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "DISABLED"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "DEVICE"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "SERVICE"
        else -> code.toString()
    }

    companion object {
        private const val TAG = "NeuCam"
    }
}
