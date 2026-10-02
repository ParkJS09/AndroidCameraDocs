package com.eden.neucam.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MeteringRectangle
import android.net.Uri
import android.util.Range
import android.util.Size
import androidx.compose.runtime.Immutable

enum class CaptureMode(val label: String) { PHOTO("사진"), VIDEO("동영상"), PRO("프로"), EXTENSION("확장") }

/** FULL: 디바이스 전체 화면 (center-crop) / RATIO_16_9: 16:9 박스에 맞춤 */
enum class PreviewMode(val label: String) { FULL("전체"), RATIO_16_9("16:9") }

enum class PhotoFormat(val label: String, val formats: List<Int>) {
    JPEG("JPEG", listOf(ImageFormat.JPEG)),
    HEIC("HEIC", listOf(ImageFormat.HEIC)),
    JPEG_R("Ultra HDR", listOf(ImageFormat.JPEG_R)),
    RAW("RAW(DNG)", listOf(ImageFormat.RAW_SENSOR)),
    RAW_JPEG("RAW+JPEG", listOf(ImageFormat.RAW_SENSOR, ImageFormat.JPEG)),
}

enum class FlashMode(val label: String) { OFF("끔"), AUTO("자동"), ON("켬"), TORCH("토치") }

enum class VideoCodec(val label: String) { H264("H.264"), HEVC("HEVC") }

@Immutable
data class SessionConfig(
    val cameraId: String,
    val mode: CaptureMode = CaptureMode.PHOTO,
    val previewMode: PreviewMode = PreviewMode.FULL,
    /** 창의 긴 변/짧은 변 비율. FULL 모드에서 프리뷰 버퍼 비율 선택에 쓴다 */
    val windowAspect: Float = 20f / 9f,
    val photoFormat: PhotoFormat = PhotoFormat.JPEG,
    val photoSize: Size? = null,
    val videoSize: Size? = null,
    val videoFps: Int = 30,
    val videoCodec: VideoCodec = VideoCodec.HEVC,
    val dynamicRange: Long = DynamicRangeProfiles.STANDARD,
    val extension: Int? = null,
)

@Immutable
data class ManualControls(
    val template: Int = CameraDevice.TEMPLATE_PREVIEW,
    val aeManual: Boolean = false,
    val iso: Int = 100,
    val exposureNs: Long = 16_666_666L,
    val ev: Int = 0,
    val aeLock: Boolean = false,
    val afManual: Boolean = false,
    val focusDiopter: Float = 0f,
    val awbMode: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    /** null 이 아니면 COLOR_CORRECTION_MODE_CCT(API 36) 로 색온도를 직접 지정 */
    val cct: Int? = null,
    val awbLock: Boolean = false,
    val zoom: Float = 1f,
    val flash: FlashMode = FlashMode.OFF,
    val ois: Boolean? = null,
    val videoStabilization: Int? = null,
    val extensionStrength: Int? = null,
    val jpegQuality: Int = 95,
    val meteringRegion: MeteringRectangle? = null,
)

@Immutable
data class LiveResult(
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val frameDurationNs: Long? = null,
    val focusDiopter: Float? = null,
    val afState: Int? = null,
    val aeState: Int? = null,
    val awbState: Int? = null,
    val zoom: Float? = null,
    val activePhysicalId: String? = null,
    val aperture: Float? = null,
    val focalLength: Float? = null,
    val cct: Int? = null,
    val fps: Float? = null,
)

enum class CameraStatus { CLOSED, OPENING, RUNNING, ERROR }

@Immutable
data class CameraUiState(
    val status: CameraStatus = CameraStatus.CLOSED,
    val error: String? = null,
    val config: SessionConfig? = null,
    val controls: ManualControls = ManualControls(),
    val previewSize: Size? = null,
    val sensorOrientation: Int = 90,
    val facing: Int = CameraMetadata.LENS_FACING_BACK,
    val sessionKind: String = "",
    val recording: Boolean = false,
    val recordStartMs: Long = 0L,
    val capturing: Boolean = false,
    val lastCapture: Uri? = null,
    val lastThumb: android.graphics.Bitmap? = null,
    val message: String? = null,
    val messageSeq: Int = 0,
    val overrides: Map<String, Any> = emptyMap(),
)

/** 카메라 하나의 UI 용 기능 요약 (CameraCharacteristics 에서 계산) */
@Immutable
data class CameraCaps(
    val photoFormats: List<PhotoFormat>,
    val photoSizes: Map<PhotoFormat, List<Size>>,
    val videoSizes: List<Size>,
    val fpsFor: Map<Size, List<Int>>,
    val highSpeedFps: Map<Size, List<Int>>,
    val dynamicRanges: List<Long>,
    val extensions: List<Int>,
    val isoRange: Range<Int>?,
    val exposureRange: Range<Long>?,
    val minFocusDiopter: Float,
    val evRange: Range<Int>?,
    val evStep: Float,
    val zoomRange: Range<Float>,
    val cctRange: Range<Int>?,
    val awbModes: IntArray,
    val afModes: IntArray,
    val flash: Boolean,
    val oisModes: IntArray,
    val stabilizationModes: IntArray,
    val manualSensor: Boolean,
    val raw: Boolean,
)

@Immutable
data class CameraDesc(
    val id: String,
    val facing: Int,
    val label: String,
    val isLogical: Boolean,
    val physicalIds: List<String>,
    val sensorOrientation: Int,
    val hwLevel: Int,
    val capabilities: Set<Int>,
    val focalLengths: FloatArray,
    val equivFocal: Float?,
    val deviceType: Int?,
    val caps: CameraCaps,
) {
    val facingLabel: String
        get() = when (facing) {
            CameraMetadata.LENS_FACING_FRONT -> "전면"
            CameraMetadata.LENS_FACING_BACK -> "후면"
            else -> "외부"
        }
}

object ExtensionNames {
    fun name(ext: Int): String = when (ext) {
        0 -> "자동"
        1 -> "Face Retouch"
        2 -> "Bokeh"
        3 -> "HDR"
        4 -> "Night"
        else -> "EXT $ext"
    }
}
