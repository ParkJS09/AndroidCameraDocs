package com.eden.neucam.ui.camera

import android.content.Intent
import android.os.SystemClock
import android.util.Size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import com.eden.neucam.ui.kit.glass
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Crop169
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eden.neucam.AppViewModel
import com.eden.neucam.camera.CameraDesc
import com.eden.neucam.camera.CameraUiState
import com.eden.neucam.camera.CaptureMode
import com.eden.neucam.camera.ExtensionNames
import com.eden.neucam.camera.FlashMode
import com.eden.neucam.camera.LiveResult
import com.eden.neucam.camera.Meta
import com.eden.neucam.camera.PreviewMode
import com.eden.neucam.ui.kit.Cam
import com.eden.neucam.ui.kit.CamButton
import com.eden.neucam.ui.kit.CamChip
import com.eden.neucam.ui.kit.CamIconButton
import com.eden.neucam.ui.kit.CamSegmented
import com.eden.neucam.ui.kit.CamSlider
import com.eden.neucam.ui.kit.CamSurface
import com.eden.neucam.ui.kit.CamSwitch
import kotlinx.coroutines.delay
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import android.hardware.camera2.CameraMetadata as CM

/** 카메라 탭 오버레이 UI. [vertical] 이면 가로 화면용(셔터 열이 오른쪽) 배치 */
@Composable
fun CameraControls(
    vm: AppViewModel,
    state: CameraUiState,
    live: LiveResult,
    desc: CameraDesc?,
    cameras: List<CameraDesc>,
    vertical: Boolean,
    grid: Boolean,
    onGrid: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cfg = state.config ?: return
    val controller = vm.controller
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showHud by rememberSaveable { mutableStateOf(true) }

    Box(modifier) {
        // ---- 상단 퀵바
        Column(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TopBar(
                state, desc,
                onFlash = { controller.updateControls { c -> c.copy(flash = FlashMode.entries[(c.flash.ordinal + 1) % FlashMode.entries.size]) } },
                onPreviewMode = {
                    controller.updateConfig { it.copy(previewMode = if (it.previewMode == PreviewMode.FULL) PreviewMode.RATIO_16_9 else PreviewMode.FULL) }
                },
                grid = grid, onGrid = onGrid,
                hud = showHud, onHud = { showHud = it },
                onSettings = { showSettings = true },
            )
            if (state.recording) RecordingTimer(state.recordStartMs)
        }
        if (showHud) Hud(state, live, Modifier.align(Alignment.TopStart).padding(top = if (state.recording) 124.dp else 72.dp, start = 12.dp))
        Toast(state.message, state.messageSeq, Modifier.align(Alignment.TopCenter).padding(top = if (state.recording) 120.dp else 72.dp))

        val stack: @Composable () -> Unit = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModePanel(vm, state, live, desc, onOpenSettings = { showSettings = true })
                ZoomChips(state, desc) { z -> controller.updateControls { it.copy(zoom = z) } }
                val modes = CaptureMode.entries.filter { it != CaptureMode.EXTENSION || desc?.caps?.extensions?.isNotEmpty() == true }
                CamSegmented(
                    modes, cfg.mode, { it.label },
                    { m ->
                        controller.updateConfig {
                            it.copy(mode = m, extension = if (m == CaptureMode.EXTENSION) it.extension ?: desc?.caps?.extensions?.firstOrNull() else it.extension)
                        }
                    },
                    Modifier.widthIn(max = 380.dp).fillMaxWidth(),
                )
            }
        }
        val shutter: @Composable () -> Unit = {
            Shutter(state, cfg.mode) { if (cfg.mode == CaptureMode.VIDEO) controller.toggleRecording() else controller.takePhoto() }
        }
        val gallery: @Composable () -> Unit = { GalleryButton(state) }
        val switcher: @Composable () -> Unit = {
            CamIconButton(Icons.Filled.Cameraswitch, "카메라 전환", {
                if (cameras.isNotEmpty() && !state.recording) {
                    val i = cameras.indexOfFirst { it.id == cfg.cameraId }
                    val next = cameras[(i + 1) % cameras.size]
                    controller.updateConfig { it.copy(cameraId = next.id, photoSize = null, videoSize = null, extension = null, mode = if (it.mode == CaptureMode.EXTENSION) CaptureMode.PHOTO else it.mode) }
                }
            }, size = 52.dp)
        }

        if (!vertical) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                stack()
                Row(Modifier.widthIn(max = 380.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    gallery(); shutter(); switcher()
                }
            }
        } else {
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { switcher(); shutter(); gallery() }
            Box(Modifier.align(Alignment.BottomCenter).padding(end = 110.dp, bottom = 10.dp).widthIn(max = 560.dp)) { stack() }
        }

        if (showSettings && desc != null) {
            SettingsSheet(vm, state, desc, cameras) { showSettings = false }
        }
    }
}

@Composable
private fun TopBar(
    state: CameraUiState,
    desc: CameraDesc?,
    onFlash: () -> Unit,
    onPreviewMode: () -> Unit,
    grid: Boolean, onGrid: (Boolean) -> Unit,
    hud: Boolean, onHud: (Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    val cfg = state.config ?: return
    // 상단 퀵바: 컨테이너 없이 개별 반투명 원형 아이콘 (카메라 앱 관례)
    Box {
        Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (desc?.caps?.flash == true) {
                val icon = when (state.controls.flash) {
                    FlashMode.OFF -> Icons.Filled.FlashOff
                    FlashMode.AUTO -> Icons.Filled.FlashAuto
                    FlashMode.ON -> Icons.Filled.FlashOn
                    FlashMode.TORCH -> Icons.Filled.FlashlightOn
                }
                CamIconButton(icon, "플래시 ${state.controls.flash.label}", onFlash, size = 40.dp, selected = state.controls.flash != FlashMode.OFF)
            }
            CamIconButton(
                if (cfg.previewMode == PreviewMode.FULL) Icons.Filled.Fullscreen else Icons.Filled.Crop169,
                "프리뷰 ${cfg.previewMode.label}", onPreviewMode, size = 40.dp,
            )
            CamIconButton(Icons.Filled.GridOn, "그리드", { onGrid(!grid) }, size = 40.dp, selected = grid)
            CamIconButton(Icons.Filled.Insights, "HUD", { onHud(!hud) }, size = 40.dp, selected = hud)
            CamIconButton(Icons.Filled.Tune, "설정", onSettings, size = 40.dp)
        }
    }
}

@Composable
private fun RecordingTimer(start: Long) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(start) { while (true) { now = SystemClock.elapsedRealtime(); delay(250) } }
    val sec = ((now - start) / 1000).coerceAtLeast(0)
    CamSurface(Modifier.padding(top = 10.dp), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Cam.colors.danger))
            Spacer(Modifier.width(8.dp))
            Text("%02d:%02d".format(sec / 60, sec % 60), color = Cam.colors.text, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }
    }
}

@Composable
private fun Hud(state: CameraUiState, live: LiveResult, modifier: Modifier) {
    val lines = buildList {
        add("ISO ${live.iso ?: "-"} · ${live.exposureNs?.let(Meta::formatNs) ?: "-"} · ${"%.1f".format(live.fps ?: 0f)}fps")
        add(
            "AF ${live.afState?.let { Meta.enumNames("CONTROL_AF_STATE")[it] } ?: "-"} · AE ${live.aeState?.let { Meta.enumNames("CONTROL_AE_STATE")[it] } ?: "-"} · AWB ${live.awbState?.let { Meta.enumNames("CONTROL_AWB_STATE")[it] } ?: "-"}",
        )
        add(
            buildString {
                live.aperture?.let { append("f/%.1f · ".format(it)) }
                live.focalLength?.let { append("%.2fmm · ".format(it)) }
                live.zoom?.let { append("%.2fx".format(it)) }
                live.activePhysicalId?.let { append(" · 물리#$it") }
                live.cct?.let { append(" · ${it}K") }
                live.focusDiopter?.let { append(" · ${if (it <= 0f) "∞" else "%.2fm".format(1f / it)}") }
            },
        )
        if (state.sessionKind.isNotEmpty()) add(state.sessionKind)
        if (state.overrides.isNotEmpty()) add("옵션 오버라이드 ${state.overrides.size}개 적용 중")
    }
    CamSurface(modifier, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            lines.forEach { Text(it, color = Cam.colors.text, fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 13.sp) }
        }
    }
}

@Composable
fun Toast(message: String?, seq: Int, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(seq) {
        if (seq == 0 || message == null) return@LaunchedEffect
        visible = true
        delay(2200)
        visible = false
    }
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        CamSurface(shape = RoundedCornerShape(18.dp)) {
            Text(message.orEmpty(), color = Cam.colors.text, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

/** 표준 카메라 셔터: 흰 링 + 안쪽 원. 동영상 = 빨간 원, 녹화 중 = 빨간 둥근 사각형 */
@Composable
private fun Shutter(state: CameraUiState, mode: CaptureMode, onClick: () -> Unit) {
    val c = Cam.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val video = mode == CaptureMode.VIDEO
    val inner by animateDpAsState(
        when {
            video && state.recording -> 30.dp
            pressed -> 54.dp
            else -> 62.dp
        },
        tween(140), label = "shutterInner",
    )
    val corner by animateDpAsState(if (video && state.recording) 8.dp else 31.dp, tween(160), label = "shutterCorner")
    Box(
        Modifier
            .size(78.dp)
            .border(4.dp, Color.White, CircleShape)
            .clip(CircleShape)
            .clickable(interaction, indication = null, enabled = !state.capturing, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(inner).clip(RoundedCornerShape(corner)).background(if (video) c.danger else Color.White))
        if (state.capturing) CircularProgressIndicator(Modifier.size(70.dp), color = c.accent, strokeWidth = 3.dp)
    }
}

@Composable
private fun GalleryButton(state: CameraUiState) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(50.dp)
            .clip(shape)
            .background(Cam.colors.glass, shape)
            .border(1.5.dp, Color.White.copy(alpha = 0.8f), shape)
            .clickable(role = Role.Button) {
                state.lastCapture?.let { uri ->
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val thumb = state.lastThumb
        if (thumb != null) {
            Image(thumb.asImageBitmap(), "마지막 촬영", Modifier.fillMaxSize().clip(shape), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Filled.Photo, "갤러리", tint = Cam.colors.textDim, modifier = Modifier.size(22.dp))
        }
    }
}

/** 줌 프리셋: 반투명 알약 안의 작은 원. 선택 = 앰버 글자, 현재 배율 표시 */
@Composable
private fun ZoomChips(state: CameraUiState, desc: CameraDesc?, onZoom: (Float) -> Unit) {
    val range = desc?.caps?.zoomRange ?: return
    if (range.upper <= 1.01f && range.lower >= 0.99f) return
    val presets = buildList {
        if (range.lower < 0.99f) add((range.lower * 10).roundToInt() / 10f)
        listOf(1f, 2f, 3f, 5f, 10f, 30f).filter { it in range.lower..range.upper }.forEach(::add)
    }.distinct()
    val z = state.controls.zoom
    val c = Cam.colors
    Row(
        Modifier.glass(RoundedCornerShape(22.dp), c).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val nearest = presets.minByOrNull { kotlin.math.abs(it - z) }
        presets.forEach { p ->
            val sel = p == nearest
            val size by animateDpAsState(if (sel) 38.dp else 32.dp, tween(150), label = "zoom")
            Box(
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(if (sel) Color.White.copy(alpha = 0.16f) else Color.Transparent, CircleShape)
                    .clickable { onZoom(p) },
                contentAlignment = Alignment.Center,
            ) {
                val label = when {
                    sel && kotlin.math.abs(z - p) > 0.05f -> "%.1f×".format(z)
                    sel -> (if (p < 1f) "%.1f".format(p) else "${p.roundToInt()}") + "×"
                    p < 1f -> "%.1f".format(p)
                    else -> "${p.roundToInt()}"
                }
                Text(label, fontSize = if (sel) 12.sp else 11.sp, fontWeight = FontWeight.Bold, color = if (sel) c.accent else c.text)
            }
        }
    }
}

private enum class ProParam(val label: String) { EV("EV"), ISO("ISO"), SHUTTER("셔터"), FOCUS("초점"), WB("WB"), ZOOM("줌") }

@Composable
private fun ModePanel(vm: AppViewModel, state: CameraUiState, live: LiveResult, desc: CameraDesc?, onOpenSettings: () -> Unit) {
    val cfg = state.config ?: return
    val caps = desc?.caps ?: return
    val controller = vm.controller
    when (cfg.mode) {
        CaptureMode.PHOTO -> InfoChips(
            listOf(
                cfg.photoFormat.label,
                (cfg.photoSize ?: caps.photoSizes[cfg.photoFormat]?.firstOrNull())?.let(::sizeLabel) ?: "-",
                "Q${state.controls.jpegQuality}",
            ),
            onOpenSettings,
        )
        CaptureMode.VIDEO -> InfoChips(
            listOfNotNull(
                (cfg.videoSize ?: Size(1920, 1080)).let { "${it.width}x${it.height}" },
                "${cfg.videoFps}fps" + if (cfg.videoFps > 60) " HS" else "",
                cfg.videoCodec.label,
                Meta.dynamicRangeName(cfg.dynamicRange),
                state.controls.videoStabilization?.let { Meta.enumNames("CONTROL_VIDEO_STABILIZATION_MODE")[it] },
            ),
            onOpenSettings,
        )
        CaptureMode.EXTENSION -> CamSurface(Modifier.widthIn(max = 420.dp), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(10.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    caps.extensions.forEach { e ->
                        CamChip(ExtensionNames.name(e), cfg.extension == e, { controller.updateConfig { it.copy(extension = e) } })
                    }
                }
                val ext = cfg.extension
                if (ext != null && remember(cfg.cameraId, ext) { vm.extensionSupportsStrength(cfg.cameraId, ext) }) {
                    LabeledSlider("강도 ${state.controls.extensionStrength ?: 50}", (state.controls.extensionStrength ?: 50).toFloat(), 0f..100f) { v ->
                        controller.updateControls { it.copy(extensionStrength = v.roundToInt()) }
                    }
                }
            }
        }
        CaptureMode.PRO -> ProPanel(vm, state, live, desc)
    }
}

@Composable
private fun InfoChips(items: List<String>, onClick: () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { CamChip(it, false, onClick) }
    }
}

@Composable
private fun ProPanel(vm: AppViewModel, state: CameraUiState, live: LiveResult, desc: CameraDesc) {
    val caps = desc.caps
    val ctl = state.controls
    val controller = vm.controller
    var param by rememberSaveable { mutableStateOf(ProParam.EV) }
    val params = ProParam.entries.filter {
        when (it) {
            ProParam.ISO, ProParam.SHUTTER -> caps.manualSensor && caps.isoRange != null
            ProParam.FOCUS -> caps.minFocusDiopter > 0f
            ProParam.EV -> caps.evRange != null && caps.evRange.upper > caps.evRange.lower
            else -> true
        }
    }
    CamSurface(Modifier.widthIn(max = 460.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                params.forEach { p ->
                    val v = when (p) {
                        ProParam.EV -> if (ctl.aeManual) "M" else "%+.1f".format(ctl.ev * caps.evStep)
                        ProParam.ISO -> if (ctl.aeManual) "${ctl.iso}" else "A ${live.iso ?: ""}"
                        ProParam.SHUTTER -> if (ctl.aeManual) Meta.formatNs(ctl.exposureNs).substringBefore(" ") else "A"
                        ProParam.FOCUS -> if (ctl.afManual) (if (ctl.focusDiopter <= 0f) "∞" else "%.2fm".format(1 / ctl.focusDiopter)) else "AF"
                        ProParam.WB -> ctl.cct?.let { "${it}K" } ?: Meta.enumNames("CONTROL_AWB_MODE")[ctl.awbMode]?.take(4) ?: ""
                        ProParam.ZOOM -> "%.1fx".format(ctl.zoom)
                    }
                    CamChip("${p.label} $v", param == p, { param = p })
                }
            }
            Spacer(Modifier.height(6.dp))
            when (param) {
                ProParam.EV -> {
                    val r = caps.evRange ?: return@Column
                    LabeledSlider("노출 보정 %+.1f EV".format(ctl.ev * caps.evStep), ctl.ev.toFloat(), r.lower.toFloat()..r.upper.toFloat(), steps = (r.upper - r.lower - 1).coerceAtLeast(0), enabled = !ctl.aeManual) {
                        v -> controller.updateControls { it.copy(ev = v.roundToInt()) }
                    }
                    ToggleRow("AE 잠금", ctl.aeLock) { on -> controller.updateControls { it.copy(aeLock = on) } }
                }
                ProParam.ISO, ProParam.SHUTTER -> {
                    ToggleRow("수동 노출 (AE OFF)", ctl.aeManual) { on ->
                        controller.updateControls { it.copy(aeManual = on, iso = live.iso ?: it.iso, exposureNs = live.exposureNs ?: it.exposureNs) }
                    }
                    if (param == ProParam.ISO) {
                        val r = caps.isoRange!!
                        LogSlider("ISO ${ctl.iso}", ctl.iso.toDouble(), r.lower.toDouble(), r.upper.toDouble(), ctl.aeManual) { v ->
                            controller.updateControls { it.copy(iso = v.roundToInt()) }
                        }
                    } else {
                        val r = caps.exposureRange!!
                        val max = minOf(r.upper, 2_000_000_000L).toDouble()
                        LogSlider("셔터 ${Meta.formatNs(ctl.exposureNs)}", ctl.exposureNs.toDouble(), r.lower.toDouble(), max, ctl.aeManual) { v ->
                            controller.updateControls { it.copy(exposureNs = v.toLong()) }
                        }
                    }
                }
                ProParam.FOCUS -> {
                    ToggleRow("수동 초점 (AF OFF)", ctl.afManual) { on ->
                        controller.updateControls { it.copy(afManual = on, focusDiopter = live.focusDiopter ?: it.focusDiopter, meteringRegion = null) }
                    }
                    LabeledSlider(
                        "초점 ${if (ctl.focusDiopter <= 0f) "∞" else "%.2f D (%.2fm)".format(ctl.focusDiopter, 1 / ctl.focusDiopter)}",
                        ctl.focusDiopter, 0f..caps.minFocusDiopter, enabled = ctl.afManual,
                    ) { v -> controller.updateControls { it.copy(focusDiopter = v) } }
                }
                ProParam.WB -> {
                    val names = Meta.enumNames("CONTROL_AWB_MODE")
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        caps.awbModes.filter { it != CM.CONTROL_AWB_MODE_OFF }.forEach { m ->
                            CamChip(names[m] ?: "$m", ctl.cct == null && ctl.awbMode == m, { controller.updateControls { it.copy(awbMode = m, cct = null) } })
                        }
                        if (caps.cctRange != null) CamChip("켈빈", ctl.cct != null, { controller.updateControls { it.copy(cct = it.cct ?: 5000) } })
                    }
                    val cr = caps.cctRange
                    if (cr != null && ctl.cct != null) {
                        LabeledSlider("색온도 ${ctl.cct}K", ctl.cct.toFloat(), cr.lower.toFloat()..cr.upper.toFloat()) { v ->
                            controller.updateControls { it.copy(cct = (v / 50).roundToInt() * 50) }
                        }
                    }
                    ToggleRow("AWB 잠금", ctl.awbLock) { on -> controller.updateControls { it.copy(awbLock = on) } }
                }
                ProParam.ZOOM -> {
                    val r = caps.zoomRange
                    LogSlider("줌 %.2fx".format(ctl.zoom), ctl.zoom.toDouble(), r.lower.toDouble(), r.upper.toDouble(), true) { v ->
                        controller.updateControls { it.copy(zoom = v.toFloat()) }
                    }
                    if (caps.oisModes.size > 1) ToggleRow("광학 손떨림 방지(OIS)", ctl.ois ?: true) { on -> controller.updateControls { it.copy(ois = on) } }
                }
            }
        }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = if (enabled) Cam.colors.text else Cam.colors.textDim, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
        CamSlider(value, onChange, valueRange = range, steps = steps, enabled = enabled)
    }
}

@Composable
fun LogSlider(label: String, value: Double, min: Double, max: Double, enabled: Boolean, onChange: (Double) -> Unit) {
    val lo = min.coerceAtLeast(1e-9)
    val span = ln(max / lo).takeIf { it > 0 } ?: 1.0
    val t = (ln(value.coerceIn(lo, max) / lo) / span).toFloat()
    LabeledSlider(label, t, 0f..1f, enabled = enabled) { f -> onChange(lo * (max / lo).pow(f.toDouble())) }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Cam.colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        CamSwitch(checked, onChange)
    }
}

fun sizeLabel(s: Size): String {
    val mp = s.width.toLong() * s.height / 1_000_000.0
    return "${s.width}x${s.height} · %.1fMP · %s".format(mp, ratioLabel(s))
}

fun ratioLabel(s: Size): String {
    val r = maxOf(s.width, s.height).toFloat() / minOf(s.width, s.height)
    val known = listOf(1f to "1:1", 4f / 3f to "4:3", 3f / 2f to "3:2", 16f / 9f to "16:9", 2f to "2:1", 20f / 9f to "20:9", 21f / 9f to "21:9", 11f / 9f to "11:9", 5f / 4f to "5:4")
    return known.minByOrNull { kotlin.math.abs(it.first - r) }?.takeIf { kotlin.math.abs(it.first - r) < 0.03f }?.second ?: "%.2f:1".format(r)
}
