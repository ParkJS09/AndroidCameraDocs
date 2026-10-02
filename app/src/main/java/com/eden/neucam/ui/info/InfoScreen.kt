package com.eden.neucam.ui.info

import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eden.neucam.camera.CameraRepository
import com.eden.neucam.camera.ExtensionNames
import com.eden.neucam.camera.Meta
import com.eden.neucam.ui.kit.Cam
import com.eden.neucam.ui.kit.CamChipRow
import com.eden.neucam.ui.kit.CamIconButton
import com.eden.neucam.ui.kit.CamSectionTitle
import com.eden.neucam.ui.kit.CamSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val STREAM_USE_CASES = mapOf(
    0L to "DEFAULT", 1L to "PREVIEW", 2L to "STILL_CAPTURE", 3L to "VIDEO_RECORD",
    4L to "PREVIEW_VIDEO_STILL", 5L to "VIDEO_CALL", 6L to "CROPPED_RAW",
)

data class InfoSection(val title: String, val rows: List<Pair<String, String>>)

@Composable
fun InfoScreen(repo: CameraRepository, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ids = remember { runCatching { repo.allCameraIds() }.getOrDefault(emptyList()) }
    val publicIds = remember { repo.manager.cameraIdList.toSet() }
    var selected by rememberSaveable { mutableStateOf(ids.firstOrNull()) }
    var sections by remember { mutableStateOf<List<InfoSection>>(emptyList()) }

    LaunchedEffect(selected) {
        val id = selected ?: return@LaunchedEffect
        sections = withContext(Dispatchers.Default) { runCatching { buildInfo(repo, id) }.getOrElse { listOf(InfoSection("오류", listOf("error" to (it.message ?: "")))) } }
    }

    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("head") {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("카메라 정보", color = Cam.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", color = Cam.colors.textDim, fontSize = 12.sp)
                    }
                    CamIconButton(Icons.Filled.Share, "공유", {
                        val text = sections.joinToString("\n\n") { s -> "## ${s.title}\n" + s.rows.joinToString("\n") { "${it.first}: ${it.second}" } }
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "카메라 정보 공유"))
                    }, size = 44.dp)
                }
                CamSectionTitle("카메라 ID (논리 + 물리)")
                CamChipRow(ids, { it == selected }, { if (it in publicIds) "#$it" else "#$it 물리" }, { selected = it })
            }
        }
        items(sections, key = { it.title }) { s ->
            CamSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), surface = Cam.colors.card) {
                Column(Modifier.padding(14.dp)) {
                    Text(s.title, color = Cam.colors.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                    s.rows.forEach { (k, v) ->
                        Box(Modifier.padding(vertical = 3.dp)) {
                            Column {
                                Text(k, color = Cam.colors.textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                Text(v, color = Cam.colors.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildInfo(repo: CameraRepository, id: String): List<InfoSection> {
    val c = repo.characteristics(id)
    val out = mutableListOf<InfoSection>()

    out += InfoSection(
        "디바이스",
        listOf(
            "cameraIdList" to repo.manager.cameraIdList.joinToString(),
            "concurrentCameraIds" to repo.concurrentCameraIds().joinToString { it.joinToString("+", "{", "}") }.ifEmpty { "없음" },
            "CameraDeviceSetup(API35)" to if (Build.VERSION.SDK_INT >= 35) runCatching { repo.manager.isCameraDeviceSetupSupported(id).toString() }.getOrDefault("-") else "API<35",
        ),
    )

    val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
    out += InfoSection(
        "요약",
        buildList {
            add("LENS_FACING" to Meta.format(c.get(CameraCharacteristics.LENS_FACING), "LENS_FACING"))
            add("INFO_SUPPORTED_HARDWARE_LEVEL" to Meta.format(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL), "INFO_SUPPORTED_HARDWARE_LEVEL"))
            if (Build.VERSION.SDK_INT >= 37) add("INFO_DEVICE_TYPE (API 37)" to Meta.format(c.get(CameraCharacteristics.INFO_DEVICE_TYPE), "INFO_DEVICE_TYPE"))
            add("SENSOR_ORIENTATION" to "${c.get(CameraCharacteristics.SENSOR_ORIENTATION)}°")
            add("REQUEST_AVAILABLE_CAPABILITIES" to Meta.format(caps, "REQUEST_AVAILABLE_CAPABILITIES"))
            add("physicalCameraIds" to c.physicalCameraIds.joinToString().ifEmpty { "-" })
            add("LENS_INFO_AVAILABLE_FOCAL_LENGTHS" to Meta.format(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)))
            add("SENSOR_INFO_PHYSICAL_SIZE" to Meta.format(c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)))
            add("SENSOR_INFO_PIXEL_ARRAY_SIZE" to Meta.format(c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)))
            add("CONTROL_ZOOM_RATIO_RANGE" to Meta.format(c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)))
            add("availableCaptureRequestKeys" to "${c.availableCaptureRequestKeys.orEmpty().size}개")
            add("availableCaptureResultKeys" to "${c.availableCaptureResultKeys.orEmpty().size}개")
            add("availableSessionKeys" to c.availableSessionKeys.orEmpty().joinToString { Meta.shortName(it.name) }.ifEmpty { "-" })
        },
    )

    c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.let { map ->
        out += InfoSection(
            "스트림 구성",
            buildList {
                map.outputFormats.forEach { f ->
                    val sizes = map.getOutputSizes(f) ?: emptyArray()
                    add(Meta.formatName(f) to sizes.joinToString(" ") { s ->
                        val fps = runCatching { map.getOutputMinFrameDuration(f, s) }.getOrDefault(0L).takeIf { it > 0 }?.let { "@%.0f".format(1e9 / it) } ?: ""
                        "${s.width}x${s.height}$fps"
                    })
                }
                add("SurfaceTexture" to (map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()).joinToString(" ") { "${it.width}x${it.height}" })
                if (map.highSpeedVideoSizes.isNotEmpty()) add(
                    "HighSpeedVideo" to map.highSpeedVideoSizes.joinToString(" ") { s ->
                        "${s.width}x${s.height}[${map.getHighSpeedVideoFpsRangesFor(s).joinToString(",") { "${it.lower}-${it.upper}" }}]"
                    },
                )
                add("AE_TARGET_FPS_RANGES" to Meta.format(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)))
            },
        )
    }

    runCatching {
        val ec = repo.manager.getCameraExtensionCharacteristics(id)
        val list = ec.supportedExtensions
        out += InfoSection(
            "카메라 확장 (CameraExtensionCharacteristics)",
            if (list.isEmpty()) listOf("지원" to "없음") else list.map { e ->
                val preview = ec.getExtensionSupportedSizes(e, SurfaceTexture::class.java).joinToString(" ") { "${it.width}x${it.height}" }
                val jpeg = ec.getExtensionSupportedSizes(e, ImageFormat.JPEG).joinToString(" ") { "${it.width}x${it.height}" }
                val keys = if (Build.VERSION.SDK_INT >= 33) ec.getAvailableCaptureRequestKeys(e).joinToString { Meta.shortName(it.name) } else "-"
                val extra = buildString {
                    if (Build.VERSION.SDK_INT >= 34) append("\npostview=${ec.isPostviewAvailable(e)} progress=${ec.isCaptureProcessProgressAvailable(e)}")
                    if (Build.VERSION.SDK_INT >= 37) append(" · isExtensionSupported(API37)=${ec.isExtensionSupported(e)}")
                }
                ExtensionNames.name(e) to "preview: $preview\nJPEG: ${jpeg.ifEmpty { "-" }}\nrequestKeys: $keys$extra"
            },
        )
    }

    val drRows = buildList {
        c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.let { add("DynamicRangeProfiles" to Meta.format(it)) }
        c.get(CameraCharacteristics.REQUEST_RECOMMENDED_TEN_BIT_DYNAMIC_RANGE_PROFILE)?.let { add("Recommended 10-bit" to Meta.dynamicRangeName(it)) }
        if (Build.VERSION.SDK_INT >= 34) c.get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES)?.let { p ->
            add("ColorSpaceProfiles" to p.getSupportedColorSpaces(ImageFormat.UNKNOWN).joinToString { it.name })
        }
        c.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)?.let { add("StreamUseCases" to it.joinToString { u -> STREAM_USE_CASES[u] ?: u.toString() }) }
    }
    if (drRows.isNotEmpty()) out += InfoSection("HDR · 색공간 · 스트림 용도", drRows)

    out += InfoSection(
        "모든 CameraCharacteristics (${c.keys.size})",
        c.keys.sortedBy { it.name }.map { k ->
            val field = Meta.charFieldByKeyName[k.name]
            Meta.shortName(k.name) to runCatching { Meta.format(c.get(k), field) }.getOrElse { "읽기 실패: ${it.message}" }
        },
    )
    return out
}
