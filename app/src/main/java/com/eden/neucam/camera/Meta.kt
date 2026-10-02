package com.eden.neucam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.Capability
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Range
import android.util.Rational
import android.util.Size
import java.lang.reflect.Modifier

/**
 * Camera2 메타데이터를 리플렉션으로 해석한다.
 * 프레임워크 공개 상수(CameraMetadata.*, CaptureRequest.* 필드)만 사용하므로 hidden API 제약과 무관하다.
 * 이 덕분에 새 OS(API 37 등)에서 추가된 키/상수도 코드 수정 없이 자동으로 이름이 붙는다.
 */
object Meta {

    /** key.name ("android.control.aeMode") -> Java 필드명 ("CONTROL_AE_MODE") */
    val requestFieldByKeyName: Map<String, String> by lazy { keyFields(CaptureRequest::class.java) }
    val resultFieldByKeyName: Map<String, String> by lazy { keyFields(CaptureResult::class.java) }
    val charFieldByKeyName: Map<String, String> by lazy { keyFields(CameraCharacteristics::class.java) }

    private val allKeyFieldNames: Set<String> by lazy {
        requestFieldByKeyName.values.toSet() + resultFieldByKeyName.values + charFieldByKeyName.values
    }

    private val intConstants: List<Pair<String, Int>> by lazy {
        CameraMetadata::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .mapNotNull { f -> runCatching { f.name to f.getInt(null) }.getOrNull() }
    }

    private val enumCache = HashMap<String, Map<Int, String>>()

    private fun keyFields(cls: Class<*>): Map<String, String> =
        cls.fields.filter { Modifier.isStatic(it.modifiers) }
            .mapNotNull { f ->
                val v = runCatching { f.get(null) }.getOrNull()
                when (v) {
                    is CaptureRequest.Key<*> -> v.name to f.name
                    is CaptureResult.Key<*> -> v.name to f.name
                    is CameraCharacteristics.Key<*> -> v.name to f.name
                    else -> null
                }
            }.toMap()

    /**
     * 필드명 prefix 로 enum 상수 이름 테이블을 만든다.
     * 예) CONTROL_AF_MODE -> {0:"OFF",1:"AUTO",...}
     * CONTROL_AUTOFRAMING_ 처럼 다른 키(CONTROL_AUTOFRAMING_STATE)의 상수와 prefix 가 겹치면 제외한다.
     */
    @Synchronized
    fun enumNames(fieldName: String): Map<Int, String> = enumCache.getOrPut(fieldName) {
        val prefix = fieldName + "_"
        val longerKeys = allKeyFieldNames.filter { it.startsWith(prefix) }.map { it + "_" }
        intConstants
            .filter { (n, _) -> n.startsWith(prefix) && longerKeys.none { n.startsWith(it) } }
            .associate { (n, v) -> v to n.removePrefix(prefix) }
    }

    /** 특성(char) 필드명 -> 그 값이 어떤 request enum 을 나열하는지 */
    val charEnumSource: Map<String, String> = mapOf(
        "COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES" to "COLOR_CORRECTION_ABERRATION_MODE",
        "COLOR_CORRECTION_AVAILABLE_MODES" to "COLOR_CORRECTION_MODE",
        "CONTROL_AE_AVAILABLE_ANTIBANDING_MODES" to "CONTROL_AE_ANTIBANDING_MODE",
        "CONTROL_AE_AVAILABLE_MODES" to "CONTROL_AE_MODE",
        "CONTROL_AE_AVAILABLE_PRIORITY_MODES" to "CONTROL_AE_PRIORITY_MODE",
        "CONTROL_AF_AVAILABLE_MODES" to "CONTROL_AF_MODE",
        "CONTROL_AVAILABLE_EFFECTS" to "CONTROL_EFFECT_MODE",
        "CONTROL_AVAILABLE_MODES" to "CONTROL_MODE",
        "CONTROL_AVAILABLE_SCENE_MODES" to "CONTROL_SCENE_MODE",
        "CONTROL_AVAILABLE_SETTINGS_OVERRIDES" to "CONTROL_SETTINGS_OVERRIDE",
        "CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES" to "CONTROL_VIDEO_STABILIZATION_MODE",
        "CONTROL_AWB_AVAILABLE_MODES" to "CONTROL_AWB_MODE",
        "DISTORTION_CORRECTION_AVAILABLE_MODES" to "DISTORTION_CORRECTION_MODE",
        "EDGE_AVAILABLE_EDGE_MODES" to "EDGE_MODE",
        "HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES" to "HOT_PIXEL_MODE",
        "LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION" to "LENS_OPTICAL_STABILIZATION_MODE",
        "NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES" to "NOISE_REDUCTION_MODE",
        "SCALER_AVAILABLE_ROTATE_AND_CROP_MODES" to "SCALER_ROTATE_AND_CROP",
        "SENSOR_AVAILABLE_TEST_PATTERN_MODES" to "SENSOR_TEST_PATTERN_MODE",
        "SHADING_AVAILABLE_MODES" to "SHADING_MODE",
        "STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES" to "STATISTICS_FACE_DETECT_MODE",
        "STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES" to "STATISTICS_LENS_SHADING_MAP_MODE",
        "STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES" to "STATISTICS_OIS_DATA_MODE",
        "TONEMAP_AVAILABLE_TONE_MAP_MODES" to "TONEMAP_MODE",
        "REQUEST_AVAILABLE_CAPABILITIES" to "REQUEST_AVAILABLE_CAPABILITIES",
    )

    /** CameraCharacteristics 필드명으로 Key 를 얻는다. 해당 API 레벨에 없으면 null */
    @Suppress("UNCHECKED_CAST")
    fun <T> charKey(field: String): CameraCharacteristics.Key<T>? =
        runCatching { CameraCharacteristics::class.java.getField(field).get(null) as CameraCharacteristics.Key<T> }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    fun <T> requestKey(field: String): CaptureRequest.Key<T>? =
        runCatching { CaptureRequest::class.java.getField(field).get(null) as CaptureRequest.Key<T> }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    fun <T> resultKey(field: String): CaptureResult.Key<T>? =
        runCatching { CaptureResult::class.java.getField(field).get(null) as CaptureResult.Key<T> }.getOrNull()

    fun <T> CameraCharacteristics.getOrNull(field: String): T? = charKey<T>(field)?.let { k -> runCatching { get(k) }.getOrNull() }

    /** "android.control.aeMode" -> "control.aeMode" */
    fun shortName(keyName: String) = keyName.removePrefix("android.")

    fun group(keyName: String): String {
        if (!keyName.startsWith("android.")) return "vendor"
        return keyName.removePrefix("android.").substringBefore('.')
    }

    // ---------------------------------------------------------------- formatting

    fun enumLabel(field: String?, value: Int): String =
        field?.let { enumNames(it)[value] }?.let { "$it($value)" } ?: value.toString()

    /** 키 필드명 문맥을 고려해 사람이 읽을 수 있는 문자열로 변환한다 */
    fun format(value: Any?, field: String? = null): String {
        val enumField = field?.let { charEnumSource[it] ?: it }
        return when (value) {
            null -> "—"
            is Int -> if (enumField != null && enumNames(enumField).isNotEmpty()) enumLabel(enumField, value) else value.toString()
            is IntArray -> if (enumField != null && enumNames(enumField).isNotEmpty()) {
                value.joinToString(", ", "[", "]") { enumNames(enumField)[it] ?: it.toString() }
            } else value.contentToString()
            is Long -> if (field == "SENSOR_EXPOSURE_TIME" || field == "SENSOR_FRAME_DURATION") formatNs(value) else value.toString()
            is Float -> "%.4g".format(value)
            is Byte -> (value.toInt() and 0xFF).toString()
            is FloatArray -> value.joinToString(", ", "[", "]") { "%.4g".format(it) }
            is LongArray -> value.contentToString()
            is BooleanArray -> value.contentToString()
            is ByteArray -> "byte[${value.size}]"
            is Array<*> -> if (value.size > 24) "[${value.take(24).joinToString(", ") { format(it) }}, … (${value.size})]"
            else value.joinToString(", ", "[", "]") { format(it) }
            is Range<*> -> "[${format(value.lower)}, ${format(value.upper)}]"
            is Size -> "${value.width}x${value.height}"
            is Rational -> "${value.numerator}/${value.denominator}"
            is MeteringRectangle -> "(${value.x},${value.y} ${value.width}x${value.height} w${value.meteringWeight})"
            is Capability -> "mode=${enumLabel("CONTROL_EXTENDED_SCENE_MODE", value.mode)} zoom=${value.zoomRatioRange} max=${value.maxStreamingSize}"
            is StreamConfigurationMap -> formatStreamMap(value)
            is DynamicRangeProfiles -> value.supportedProfiles.joinToString(", ", "[", "]") { dynamicRangeName(it) }
            else -> value.toString()
        }
    }

    fun formatNs(ns: Long): String = when {
        ns >= 1_000_000_000L -> "%.2fs".format(ns / 1e9)
        ns >= 1_000_000L -> {
            val denom = 1e9 / ns
            if (denom >= 1.5) "1/%.0fs (%.2fms)".format(denom, ns / 1e6) else "%.1fms".format(ns / 1e6)
        }
        else -> "1/%.0fs (%dµs)".format(1e9 / ns, ns / 1000)
    }

    private fun formatStreamMap(map: StreamConfigurationMap): String = buildString {
        map.outputFormats.forEach { fmt ->
            val sizes = map.getOutputSizes(fmt) ?: emptyArray()
            append(formatName(fmt)).append(": ").append(sizes.take(8).joinToString(" ") { "${it.width}x${it.height}" })
            if (sizes.size > 8) append(" … (${sizes.size})")
            append('\n')
        }
        val hs = map.highSpeedVideoSizes
        if (hs.isNotEmpty()) append("HighSpeed: ").append(hs.joinToString(" ") { s -> "${s.width}x${s.height}@${map.getHighSpeedVideoFpsRangesFor(s).joinToString("/") { it.upper.toString() }}" })
    }.trimEnd()

    private val formatNames: Map<Int, String> by lazy {
        val m = HashMap<Int, String>()
        listOf(android.graphics.PixelFormat::class.java, android.graphics.ImageFormat::class.java).forEach { cls ->
            cls.fields.filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
                .forEach { f ->
                    val v = runCatching { f.getInt(null) }.getOrNull() ?: return@forEach
                    if (f.name !in setOf("UNKNOWN", "OPAQUE", "TRANSLUCENT", "TRANSPARENT", "FLEX_RGB_888", "FLEX_RGBA_8888")) m.putIfAbsent(v, f.name)
                }
        }
        m[0x22] = "PRIVATE"
        m
    }

    fun formatName(format: Int): String = formatNames[format] ?: "0x%X".format(format)

    private val dynamicRangeNames: Map<Long, String> by lazy {
        DynamicRangeProfiles::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Long::class.javaPrimitiveType }
            .mapNotNull { f -> runCatching { f.getLong(null) to f.name }.getOrNull() }
            .toMap()
    }

    fun dynamicRangeName(profile: Long): String = dynamicRangeNames[profile] ?: "0x%X".format(profile)
}
