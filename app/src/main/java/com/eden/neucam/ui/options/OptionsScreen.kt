package com.eden.neucam.ui.options

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.RggbChannelVector
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eden.neucam.AppViewModel
import com.eden.neucam.camera.CameraDesc
import com.eden.neucam.camera.CameraStatus
import com.eden.neucam.camera.CameraUiState
import com.eden.neucam.camera.CaptureMode
import com.eden.neucam.camera.KeyEditor
import com.eden.neucam.camera.KeySpec
import com.eden.neucam.camera.Meta
import com.eden.neucam.camera.RequestKeySpecs
import com.eden.neucam.ui.camera.LabeledSlider
import com.eden.neucam.ui.camera.LogSlider
import com.eden.neucam.ui.camera.Toast
import com.eden.neucam.ui.kit.Cam
import com.eden.neucam.ui.kit.CamButton
import com.eden.neucam.ui.kit.CamChip
import com.eden.neucam.ui.kit.CamChipRow
import com.eden.neucam.ui.kit.CamIconButton
import com.eden.neucam.ui.kit.CamSectionTitle
import com.eden.neucam.ui.kit.CamSurface
import com.eden.neucam.ui.kit.CamSwitch
import com.eden.neucam.ui.kit.field
import kotlin.math.roundToInt

private val TEMPLATES = listOf(
    CameraDevice.TEMPLATE_PREVIEW to "PREVIEW",
    CameraDevice.TEMPLATE_STILL_CAPTURE to "STILL",
    CameraDevice.TEMPLATE_RECORD to "RECORD",
    CameraDevice.TEMPLATE_VIDEO_SNAPSHOT to "SNAPSHOT",
    CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG to "ZSL",
    CameraDevice.TEMPLATE_MANUAL to "MANUAL",
)

/**
 * 디바이스가 노출하는 모든 CaptureRequest 키(availableCaptureRequestKeys)를 나열하고
 * 키 타입/가용 값에 맞는 편집기로 반복 요청에 직접 오버라이드한다.
 */
@Composable
fun OptionsScreen(vm: AppViewModel, state: CameraUiState, cameras: List<CameraDesc>, modifier: Modifier = Modifier) {
    val cfg = state.config ?: return
    val controller = vm.controller
    val results by controller.resultValues.collectAsState()
    var specs by remember { mutableStateOf<List<KeySpec>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyOverridden by rememberSaveable { mutableStateOf(false) }
    val running = state.status == CameraStatus.RUNNING
    val template = state.controls.template

    LaunchedEffect(cfg.cameraId, running, template) {
        if (!running) return@LaunchedEffect
        val defaults = controller.templateDefaults(template)
        specs = RequestKeySpecs.build(vm.repo.characteristics(cfg.cameraId), defaults)
    }

    val groups = remember(specs) { specs.map { it.group }.distinct() }
    val filtered = specs.filter { s ->
        (group == null || s.group == group) &&
            (!onlyOverridden || s.name in state.overrides) &&
            (query.isBlank() || s.name.contains(query, true) || (s.field?.contains(query, true) == true))
    }

    Box(modifier) {
        LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "header") {
                Column {
                    Text("카메라 옵션", color = Cam.colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "요청 키 ${specs.size}개 · 오버라이드 ${state.overrides.size}개" + if (cfg.mode == CaptureMode.EXTENSION) " · 확장 세션은 지원 키만 적용" else "",
                        color = Cam.colors.textDim, fontSize = 12.sp,
                    )
                    CamSectionTitle("카메라")
                    CamChipRow(cameras, { it.id == cfg.cameraId }, { it.label }, { c ->
                        controller.updateConfig { it.copy(cameraId = c.id, photoSize = null, videoSize = null, extension = null, mode = if (it.mode == CaptureMode.EXTENSION) CaptureMode.PHOTO else it.mode) }
                    })
                    CamSectionTitle("요청 템플릿 (사진/프로 모드 반복 요청)")
                    CamChipRow(TEMPLATES, { it.first == template }, { it.second }, { t -> controller.updateControls { c -> c.copy(template = t.first) } })
                    Spacer(Modifier.height(8.dp))
                    SearchField(query) { query = it }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp, horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CamChip("전체", group == null, { group = null })
                        groups.forEach { g -> CamChip(g, group == g, { group = if (group == g) null else g }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                        Text("오버라이드만 보기", color = Cam.colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        CamSwitch(onlyOverridden, { onlyOverridden = it })
                        Spacer(Modifier.width(12.dp))
                        CamButton({ controller.clearOverrides() }, Modifier.height(34.dp)) {
                            Text("모두 초기화", color = Cam.colors.danger, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp))
                        }
                    }
                    if (!running) Text("카메라가 실행 중일 때 키 목록을 불러옵니다…", color = Cam.colors.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            items(filtered, key = { it.name }) { spec ->
                KeyRow(
                    spec = spec,
                    override = state.overrides[spec.name],
                    live = results[spec.name],
                    onSet = { v -> controller.setOverride(spec.name, v) },
                    onShot = { v -> controller.sendOneShot(spec.name, v) },
                )
            }
        }
        Toast(state.message, state.messageSeq, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val c = Cam.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().height(44.dp).field(shape, c).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = c.textDim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("키 이름 검색 (예: aeMode, NOISE)", color = c.textDim, fontSize = 13.sp)
            BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = c.text, fontSize = 13.sp), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun KeyRow(spec: KeySpec, override: Any?, live: String?, onSet: (Any?) -> Unit, onShot: (Any) -> Unit) {
    val c = Cam.colors
    CamSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), surface = Cam.colors.card) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            Meta.shortName(spec.name),
                            color = if (override != null) c.accent else c.text,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (spec.sessionKey) {
                            Spacer(Modifier.width(6.dp))
                            Text("SESSION", color = c.accent2, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(spec.field ?: "vendor key", color = c.textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                if (override != null) CamIconButton(Icons.Filled.RestartAlt, "초기화", { onSet(null) }, size = 34.dp)
            }
            Text(
                "결과: ${live ?: "—"}\n기본: ${Meta.format(spec.default, spec.field)}" + (override?.let { "\n설정: ${Meta.format(it, spec.field)}" } ?: ""),
                color = c.textDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Editor(spec, override, onSet, onShot)
        }
    }
}

@Composable
private fun Editor(spec: KeySpec, override: Any?, onSet: (Any?) -> Unit, onShot: (Any) -> Unit) {
    val current = override ?: spec.default
    when (val e = spec.editor) {
        is KeyEditor.Choice -> Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp, horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            e.options.forEach { (v, label) -> CamChip(label, override != null && override == v, { onSet(v) }) }
        }
        is KeyEditor.Trigger -> Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp, horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            e.options.forEach { (v, label) -> CamChip("▶ $label", false, { onShot(v) }) }
        }
        is KeyEditor.IntSlider -> {
            val v = (current as? Int ?: e.min).coerceIn(e.min, e.max)
            LabeledSlider("$v", v.toFloat(), e.min.toFloat()..e.max.toFloat(), steps = if (e.max - e.min in 2..60) e.max - e.min - 1 else 0) { onSet(it.roundToInt()) }
        }
        is KeyEditor.ByteSlider -> {
            val v = ((current as? Byte)?.toInt()?.and(0xFF) ?: e.max).coerceIn(e.min, e.max)
            LabeledSlider("$v", v.toFloat(), e.min.toFloat()..e.max.toFloat()) { onSet(it.roundToInt().toByte()) }
        }
        is KeyEditor.LongLogSlider -> {
            val v = (current as? Long ?: e.min).coerceIn(e.min, e.max)
            LogSlider(Meta.formatNs(v), v.toDouble(), e.min.toDouble(), e.max.toDouble(), true) { onSet(it.toLong()) }
        }
        is KeyEditor.FloatSlider -> {
            val v = (current as? Float ?: e.min).coerceIn(e.min, e.max)
            LabeledSlider("%.3f".format(v), v, e.min..e.max) { onSet(it) }
        }
        KeyEditor.Toggle -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (current == true) "ON" else "OFF", color = Cam.colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            CamSwitch(current == true, { onSet(it) })
        }
        KeyEditor.Rggb -> {
            val g = current as? RggbChannelVector ?: RggbChannelVector(1f, 1f, 1f, 1f)
            val parts = floatArrayOf(g.red, g.greenEven, g.greenOdd, g.blue)
            listOf("R", "Gₑ", "Gₒ", "B").forEachIndexed { i, n ->
                LabeledSlider("$n %.2f".format(parts[i]), parts[i], 0f..8f) { v ->
                    val p = parts.copyOf().also { it[i] = v }
                    onSet(RggbChannelVector(p[0], p[1], p[2], p[3]))
                }
            }
        }
        is KeyEditor.NumberInput -> NumberInput(e.kind, current, onSet)
        is KeyEditor.ReadOnly -> Text(e.reason, color = Cam.colors.textDim, fontSize = 11.sp)
    }
}

@Composable
private fun NumberInput(kind: KeyEditor.NumberInput.Kind, current: Any?, onSet: (Any?) -> Unit) {
    val c = Cam.colors
    var text by remember(current) { mutableStateOf(current?.toString() ?: "") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(40.dp).field(RoundedCornerShape(14.dp), c).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                text, { text = it }, singleLine = true,
                textStyle = TextStyle(color = c.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = if (kind == KeyEditor.NumberInput.Kind.FLOAT) KeyboardType.Decimal else KeyboardType.Number),
                cursorBrush = SolidColor(c.accent),
            )
        }
        Spacer(Modifier.width(10.dp))
        CamButton({
            val v: Any? = when (kind) {
                KeyEditor.NumberInput.Kind.INT -> text.trim().toIntOrNull()
                KeyEditor.NumberInput.Kind.LONG -> text.trim().toLongOrNull()
                KeyEditor.NumberInput.Kind.FLOAT -> text.trim().toFloatOrNull()
            }
            if (v != null) onSet(v)
        }, Modifier.height(40.dp)) {
            Text("적용", color = c.accent, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}
