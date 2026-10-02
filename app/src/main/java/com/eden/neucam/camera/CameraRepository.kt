package com.eden.neucam.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaRecorder
import android.os.Build
import android.util.Range
import android.util.Size
import android.view.SurfaceHolder
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 디바이스의 모든 카메라(논리/물리)를 조회하고 UI 용 기능 요약을 만든다 */
class CameraRepository(context: Context) {

    val manager: CameraManager = context.getSystemService(CameraManager::class.java)

    private val charCache = HashMap<String, CameraCharacteristics>()

    @Synchronized
    fun characteristics(id: String): CameraCharacteristics = charCache.getOrPut(id) { manager.getCameraCharacteristics(id) }

    @Synchronized
    fun invalidate() = charCache.clear()

    fun listCameras(): List<CameraDesc> = manager.cameraIdList.mapNotNull { id -> runCatching { describe(id) }.getOrNull() }

    /** 논리 카메라에 묶인 물리 카메라까지 포함한 모든 ID (정보 탭에서 사용) */
    fun allCameraIds(): List<String> {
        val public = manager.cameraIdList.toList()
        val physical = public.flatMap { id -> runCatching { characteristics(id).physicalCameraIds.toList() }.getOrDefault(emptyList()) }
        return (public + physical).distinct()
    }

    fun concurrentCameraIds(): List<Set<String>> = runCatching { manager.concurrentCameraIds.toList() }.getOrDefault(emptyList())

    fun describe(id: String): CameraDesc {
        val c = characteristics(id)
        val facing = c.get(CameraCharacteristics.LENS_FACING) ?: CameraMetadata.LENS_FACING_EXTERNAL
        val capsSet = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf()
        val physSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val equiv = if (physSize != null && focal.isNotEmpty()) {
            focal.first() * 43.27f / hypot(physSize.width, physSize.height)
        } else null
        val deviceType = if (Build.VERSION.SDK_INT >= 37) c.get(CameraCharacteristics.INFO_DEVICE_TYPE) else null
        val isLogical = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in capsSet
        val physicalIds = c.physicalCameraIds.toList()
        val label = buildString {
            append(
                when (facing) {
                    CameraMetadata.LENS_FACING_FRONT -> "전면"
                    CameraMetadata.LENS_FACING_BACK -> "후면"
                    else -> "외부"
                },
            )
            equiv?.let { append(" ${it.roundToInt()}mm") }
            if (isLogical) append(" · 멀티(${physicalIds.size})")
            append(" #").append(id)
        }
        return CameraDesc(
            id = id,
            facing = facing,
            label = label,
            isLogical = isLogical,
            physicalIds = physicalIds,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            hwLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1,
            capabilities = capsSet,
            focalLengths = focal,
            equivFocal = equiv,
            deviceType = deviceType,
            caps = buildCaps(id, c, capsSet),
        )
    }

    private fun buildCaps(id: String, c: CameraCharacteristics, capsSet: Set<Int>): CameraCaps {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outFormats = map?.outputFormats?.toSet() ?: emptySet()
        val photoFormats = PhotoFormat.entries.filter { pf -> pf.formats.all { it in outFormats } }
        val photoSizes = photoFormats.associateWith { pf ->
            (map?.getOutputSizes(pf.formats.first())?.toList() ?: emptyList()).sortedByDescending { it.width.toLong() * it.height }
        }
        val videoSizes = (map?.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList())
            .filter { it.width <= 7680 }
            .sortedByDescending { it.width.toLong() * it.height }
        val fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val fpsFor = videoSizes.associateWith { size ->
            val minDur = runCatching { map!!.getOutputMinFrameDuration(MediaRecorder::class.java, size) }.getOrDefault(0L)
            val maxFps = if (minDur > 0) (1e9 / minDur).roundToInt() else 30
            fpsRanges.map { it.upper }.filter { it <= maxFps && it >= 15 }.distinct().sorted()
        }
        val hs = if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO in capsSet && map != null) {
            map.highSpeedVideoSizes.associateWith { s -> map.getHighSpeedVideoFpsRangesFor(s).filter { it.lower == it.upper }.map { it.upper }.distinct().sorted() }
        } else emptyMap()
        val dr = if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT in capsSet) {
            c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles?.toList()?.sorted() ?: listOf(DynamicRangeProfiles.STANDARD)
        } else listOf(DynamicRangeProfiles.STANDARD)
        val extensions = runCatching { manager.getCameraExtensionCharacteristics(id).supportedExtensions }.getOrDefault(emptyList())
        val cctRange = if (Build.VERSION.SDK_INT >= 36) c.get(CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE) else null
        return CameraCaps(
            photoFormats = photoFormats,
            photoSizes = photoSizes,
            videoSizes = videoSizes,
            fpsFor = fpsFor,
            highSpeedFps = hs,
            dynamicRanges = dr,
            extensions = extensions,
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            minFocusDiopter = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE),
            evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f,
            zoomRange = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f),
            cctRange = cctRange,
            awbModes = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf(),
            afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf(),
            flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            oisModes = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf(),
            stabilizationModes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf(),
            manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capsSet,
            raw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in capsSet,
        )
    }

    companion object {
        /** 후보 크기 중 목표 비율에 가장 가깝고 maxPixels 이하인 가장 큰 크기 */
        fun chooseByAspect(sizes: List<Size>, aspect: Float, maxLong: Int = 1920, maxShort: Int = 1080): Size? {
            val fit = sizes.filter { maxOf(it.width, it.height) <= maxLong && minOf(it.width, it.height) <= maxShort }
                .ifEmpty { sizes }
            fun ratio(s: Size) = maxOf(s.width, s.height).toFloat() / minOf(s.width, s.height)
            return fit.minWithOrNull(
                compareBy<Size> { kotlin.math.abs(ratio(it) - aspect) > 0.02f }
                    .thenBy { kotlin.math.abs(ratio(it) - aspect) }
                    .thenByDescending { it.width.toLong() * it.height },
            )
        }

        fun previewSizes(c: CameraCharacteristics): List<Size> =
            c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceHolder::class.java)?.toList() ?: emptyList()

        fun aspectOf(s: Size) = maxOf(s.width, s.height).toFloat() / minOf(s.width, s.height)

        val PREFERRED_JPEG = ImageFormat.JPEG
    }
}
