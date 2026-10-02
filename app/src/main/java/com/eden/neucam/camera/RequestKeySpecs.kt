package com.eden.neucam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.Capability
import android.util.Range
import android.util.Size
import androidx.compose.runtime.Immutable
import com.eden.neucam.camera.Meta.getOrNull

/** 옵션 탭에서 CaptureRequest 키 하나를 편집하는 방법 */
sealed interface KeyEditor {
    data class Choice(val options: List<Pair<Any, String>>) : KeyEditor
    data class IntSlider(val min: Int, val max: Int) : KeyEditor
    data class ByteSlider(val min: Int, val max: Int) : KeyEditor
    data class LongLogSlider(val min: Long, val max: Long) : KeyEditor
    data class FloatSlider(val min: Float, val max: Float) : KeyEditor
    data object Toggle : KeyEditor
    data object Rggb : KeyEditor
    /** 트리거류: 반복 요청에 넣으면 매 프레임 재트리거되므로 단발 캡처로 전송 */
    data class Trigger(val options: List<Pair<Int, String>>) : KeyEditor
    data class NumberInput(val kind: Kind) : KeyEditor { enum class Kind { INT, LONG, FLOAT } }
    data class ReadOnly(val reason: String) : KeyEditor
}

@Immutable
data class KeySpec(
    val name: String,
    val field: String?,
    val group: String,
    val editor: KeyEditor,
    val sessionKey: Boolean,
    val default: Any?,
)

object RequestKeySpecs {

    private val availabilityOf: Map<String, String> = Meta.charEnumSource.entries.associate { (char, req) -> req to char }

    private val booleans = setOf(
        "BLACK_LEVEL_LOCK", "CONTROL_AE_LOCK", "CONTROL_AWB_LOCK", "CONTROL_ENABLE_ZSL",
        "STATISTICS_HOT_PIXEL_MAP_MODE", "LOGICAL_MULTI_CAMERA_ADDITIONAL_RESULTS",
    )

    fun build(c: CameraCharacteristics, defaults: Map<String, Any?>): List<KeySpec> {
        // 에뮬레이터 등 일부 HAL 은 null 을 돌려준다
        val sessionKeys = c.availableSessionKeys.orEmpty().map { it.name }.toSet()
        return c.availableCaptureRequestKeys.orEmpty().map { key ->
            val field = Meta.requestFieldByKeyName[key.name]
            val default = defaults[key.name]
            KeySpec(
                name = key.name,
                field = field,
                group = Meta.group(key.name),
                editor = runCatching { field?.let { editorFor(it, c, default) } ?: generic(field, default) }
                    .getOrElse { KeyEditor.ReadOnly("편집기 생성 실패: ${it.message}") },
                sessionKey = key.name in sessionKeys,
                default = default,
            )
        }.sortedWith(compareBy<KeySpec> { it.group }.thenBy { it.name })
    }

    private fun enumChoice(field: String, available: IntArray?): KeyEditor {
        val names = Meta.enumNames(field)
        val values = available?.toList() ?: names.keys.sorted()
        if (values.isEmpty()) return KeyEditor.ReadOnly("사용 가능한 값 없음")
        return KeyEditor.Choice(values.map { it to (names[it] ?: it.toString()) })
    }

    private fun editorFor(field: String, c: CameraCharacteristics, default: Any?): KeyEditor? {
        availabilityOf[field]?.let { charField -> return enumChoice(field, c.getOrNull<IntArray>(charField)) }
        if (field in booleans) return KeyEditor.Toggle
        return when (field) {
            "CONTROL_EXTENDED_SCENE_MODE" ->
                enumChoice(field, c.getOrNull<Array<Capability>>("CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES")?.map { it.mode }?.toIntArray())
            "CONTROL_AF_TRIGGER", "CONTROL_AE_PRECAPTURE_TRIGGER" ->
                KeyEditor.Trigger(Meta.enumNames(field).entries.sortedBy { it.key }.map { it.key to it.value })
            "SENSOR_SENSITIVITY" -> c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { KeyEditor.IntSlider(it.lower, it.upper) }
            "SENSOR_EXPOSURE_TIME" -> c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { KeyEditor.LongLogSlider(it.lower, it.upper) }
            "SENSOR_FRAME_DURATION" -> {
                val max = c.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: 1_000_000_000L
                KeyEditor.LongLogSlider(1_000_000_000L / 480, max)
            }
            "LENS_FOCUS_DISTANCE" -> KeyEditor.FloatSlider(0f, c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.takeIf { it > 0f } ?: 10f)
            "CONTROL_ZOOM_RATIO" -> c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { KeyEditor.FloatSlider(it.lower, it.upper) }
            "CONTROL_AE_EXPOSURE_COMPENSATION" -> c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { KeyEditor.IntSlider(it.lower, it.upper) }
            "CONTROL_POST_RAW_SENSITIVITY_BOOST" -> c.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)?.let { KeyEditor.IntSlider(it.lower, it.upper) }
            "CONTROL_AE_TARGET_FPS_RANGE" -> c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { r: Range<Int> -> r as Any to "[${r.lower}, ${r.upper}]" }?.let { KeyEditor.Choice(it) }
            "LENS_APERTURE" -> floatChoice(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES), "f/")
            "LENS_FOCAL_LENGTH" -> floatChoice(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS), "", "mm")
            "LENS_FILTER_DENSITY" -> floatChoice(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FILTER_DENSITIES), "ND ")
            "JPEG_QUALITY", "JPEG_THUMBNAIL_QUALITY" -> KeyEditor.ByteSlider(1, 100)
            "JPEG_ORIENTATION" -> KeyEditor.Choice(listOf(0, 90, 180, 270).map { it to "$it°" })
            "JPEG_THUMBNAIL_SIZE" -> c.get(CameraCharacteristics.JPEG_AVAILABLE_THUMBNAIL_SIZES)
                ?.map { s: Size -> s as Any to "${s.width}x${s.height}" }?.let { KeyEditor.Choice(it) }
            "COLOR_CORRECTION_COLOR_TEMPERATURE" ->
                c.getOrNull<Range<Int>>("COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE")?.let { KeyEditor.IntSlider(it.lower, it.upper) }
            "COLOR_CORRECTION_COLOR_TINT" -> KeyEditor.IntSlider(-50, 50)
            "TONEMAP_GAMMA" -> KeyEditor.FloatSlider(1f, 5f)
            "REPROCESS_EFFECTIVE_EXPOSURE_FACTOR" -> KeyEditor.FloatSlider(1f, 16f)
            "FLASH_STRENGTH_LEVEL" -> {
                val single = c.getOrNull<Int>("FLASH_SINGLE_STRENGTH_MAX_LEVEL") ?: 1
                val torch = c.getOrNull<Int>("FLASH_TORCH_STRENGTH_MAX_LEVEL") ?: 1
                KeyEditor.IntSlider(1, maxOf(single, torch, 1))
            }
            "EXTENSION_STRENGTH" -> KeyEditor.IntSlider(0, 100)
            "COLOR_CORRECTION_GAINS" -> KeyEditor.Rggb
            "CONTROL_AE_REGIONS", "CONTROL_AF_REGIONS", "CONTROL_AWB_REGIONS" -> KeyEditor.ReadOnly("카메라 탭에서 프리뷰를 탭해 지정")
            "SCALER_CROP_REGION" -> KeyEditor.ReadOnly("CONTROL_ZOOM_RATIO 사용 권장")
            else -> null
        }
    }

    private fun floatChoice(values: FloatArray?, prefix: String, suffix: String = ""): KeyEditor? =
        values?.takeIf { it.isNotEmpty() }?.map { v -> v as Any to "$prefix${"%.2f".format(v)}$suffix" }?.let { KeyEditor.Choice(it) }

    private fun generic(field: String?, default: Any?): KeyEditor {
        val names = field?.let { Meta.enumNames(it) }.orEmpty()
        return when (default) {
            is Boolean -> KeyEditor.Toggle
            is Int -> if (names.isNotEmpty()) KeyEditor.Choice(names.entries.sortedBy { it.key }.map { it.key to it.value })
            else KeyEditor.NumberInput(KeyEditor.NumberInput.Kind.INT)
            is Long -> KeyEditor.NumberInput(KeyEditor.NumberInput.Kind.LONG)
            is Float -> KeyEditor.NumberInput(KeyEditor.NumberInput.Kind.FLOAT)
            is Byte -> KeyEditor.ByteSlider(0, 100)
            null -> if (names.isNotEmpty()) KeyEditor.Choice(names.entries.sortedBy { it.key }.map { it.key to it.value })
            else KeyEditor.ReadOnly("템플릿 기본값 없음 → 타입 미상")
            else -> KeyEditor.ReadOnly("복합 타입 ${default.javaClass.simpleName}")
        }
    }
}
