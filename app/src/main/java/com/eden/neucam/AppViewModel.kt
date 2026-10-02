package com.eden.neucam

import android.app.Application
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.view.OrientationEventListener
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eden.neucam.camera.CameraController
import com.eden.neucam.camera.CameraDesc
import com.eden.neucam.camera.CameraRepository
import com.eden.neucam.camera.MultiCameraController
import com.eden.neucam.camera.MultiPair
import com.eden.neucam.camera.SessionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class AppTab(val label: String) { CAMERA("카메라"), MULTI("멀티캠"), OPTIONS("옵션"), INFO("정보") }

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val repo = CameraRepository(app)
    val controller = CameraController(app, repo)
    val multi = MultiCameraController(app, repo)

    private val _cameras = MutableStateFlow<List<CameraDesc>>(emptyList())
    val cameras: StateFlow<List<CameraDesc>> = _cameras.asStateFlow()
    private val _multiPairs = MutableStateFlow<List<MultiPair>>(emptyList())
    val multiPairs: StateFlow<List<MultiPair>> = _multiPairs.asStateFlow()
    private val _tab = MutableStateFlow(AppTab.CAMERA)
    val tab: StateFlow<AppTab> = _tab.asStateFlow()

    private var foreground = false
    private var permission = false
    private var windowAspect = 20f / 9f
    private val syncMutex = Mutex()

    private val orientationListener = object : OrientationEventListener(app) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation != ORIENTATION_UNKNOWN) controller.deviceOrientation = orientation
        }
    }

    private val availability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (_cameras.value.none { it.id == cameraId }) refreshCameras()
        }

        // Android 17 (API 37): 외장/가상 카메라가 시스템에서 완전히 제거됨
        override fun onCameraRemoved(cameraId: String) = refreshCameras()
    }

    init {
        repo.manager.registerAvailabilityCallback(ContextCompat.getMainExecutor(app), availability)
    }

    fun refreshCameras() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.Default) {
                repo.invalidate()
                repo.listCameras()
            }
            _cameras.value = list
            val cur = controller.state.value.config
            if (cur == null || list.none { it.id == cur.cameraId }) {
                val first = list.firstOrNull { it.facing == CameraMetadata.LENS_FACING_BACK } ?: list.firstOrNull()
                if (first != null) controller.setConfig((cur ?: SessionConfig(first.id)).copy(cameraId = first.id, windowAspect = windowAspect))
            }
            _multiPairs.value = withContext(Dispatchers.Default) { runCatching { multi.pairs() }.getOrDefault(emptyList()) }
            if (multi.state.value.pair == null) _multiPairs.value.firstOrNull()?.let(multi::setPair)
        }
    }

    fun onPermission(granted: Boolean) {
        if (granted == permission) return
        permission = granted
        if (granted) refreshCameras()
        sync()
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) orientationListener.enable() else orientationListener.disable()
        sync()
    }

    fun selectTab(t: AppTab) {
        _tab.value = t
        controller.collectAllResults = t == AppTab.OPTIONS
        sync()
    }

    /** 창 비율(긴 변/짧은 변). 회전으로는 바뀌지 않고 폴더블 펼침/접힘·멀티윈도우 크기 변경에서만 바뀐다 */
    fun setWindowAspect(aspect: Float) {
        val q = (aspect * 20).roundToInt() / 20f
        if (q == windowAspect) return
        windowAspect = q
        controller.updateConfig { it.copy(windowAspect = q) }
    }

    fun retry() {
        viewModelScope.launch {
            syncMutex.withLock {
                controller.setActive(false).join()
                multi.setActive(false).join()
            }
            sync()
        }
    }

    /** 탭/포그라운드/권한에 따라 단일·멀티 컨트롤러 중 하나만 카메라를 점유하게 한다 */
    private fun sync() {
        viewModelScope.launch {
            syncMutex.withLock {
                val on = permission && foreground
                val single = on && (_tab.value == AppTab.CAMERA || _tab.value == AppTab.OPTIONS)
                val dual = on && _tab.value == AppTab.MULTI
                // 먼저 닫고 나서 연다 → 동시 카메라 수 제한(ERROR_MAX_CAMERAS_IN_USE) 회피
                if (!single) controller.setActive(false).join()
                if (!dual) multi.setActive(false).join()
                if (single) controller.setActive(true).join()
                if (dual) multi.setActive(true).join()
            }
        }
    }

    fun extensionSupportsStrength(cameraId: String, ext: Int): Boolean = Build.VERSION.SDK_INT >= 34 && runCatching {
        repo.manager.getCameraExtensionCharacteristics(cameraId).getAvailableCaptureRequestKeys(ext)
            .any { it.name == "android.extension.strength" }
    }.getOrDefault(false)

    override fun onCleared() {
        orientationListener.disable()
        repo.manager.unregisterAvailabilityCallback(availability)
        controller.release()
        multi.release()
    }
}
