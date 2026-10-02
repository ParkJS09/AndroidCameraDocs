package com.eden.neucam.ui.camera

import android.util.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eden.neucam.AppViewModel
import com.eden.neucam.camera.CameraDesc
import com.eden.neucam.camera.CameraUiState
import com.eden.neucam.camera.CaptureMode
import com.eden.neucam.camera.Meta
import com.eden.neucam.camera.PreviewMode
import com.eden.neucam.camera.VideoCodec
import com.eden.neucam.ui.kit.Cam
import com.eden.neucam.ui.kit.CamChipRow
import com.eden.neucam.ui.kit.CamIconButton
import com.eden.neucam.ui.kit.CamSectionTitle
import com.eden.neucam.ui.kit.CamSegmented
import com.eden.neucam.ui.kit.CamSurface
import kotlin.math.roundToInt

@Composable
fun SettingsSheet(vm: AppViewModel, state: CameraUiState, desc: CameraDesc, cameras: List<CameraDesc>, onDismiss: () -> Unit) {
    val cfg = state.config ?: return
    val caps = desc.caps
    val controller = vm.controller
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        CamSurface(
            Modifier
                .padding(16.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .clickable(remember { MutableInteractionSource() }, null) {},
            shape = RoundedCornerShape(24.dp),
            surface = Cam.colors.card,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("촬영 설정", color = Cam.colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    CamIconButton(Icons.Filled.Close, "닫기", onDismiss, size = 40.dp)
                }

                CamSectionTitle("카메라")
                CamChipRow(cameras, { it.id == cfg.cameraId }, { it.label }, { c ->
                    if (!state.recording) controller.updateConfig { it.copy(cameraId = c.id, photoSize = null, videoSize = null, extension = null, mode = if (it.mode == CaptureMode.EXTENSION) CaptureMode.PHOTO else it.mode) }
                })

                CamSectionTitle("프리뷰")
                CamSegmented(PreviewMode.entries, cfg.previewMode, { it.label }, { m -> controller.updateConfig { it.copy(previewMode = m) } }, Modifier.fillMaxWidth())

                if (cfg.mode != CaptureMode.VIDEO) {
                    CamSectionTitle("사진 포맷")
                    CamChipRow(caps.photoFormats, { it == cfg.photoFormat }, { it.label }, { f -> controller.updateConfig { it.copy(photoFormat = f, photoSize = null) } })
                    CamSectionTitle("사진 해상도")
                    val sizes = caps.photoSizes[cfg.photoFormat].orEmpty().take(24)
                    val current = cfg.photoSize ?: sizes.firstOrNull()
                    CamChipRow(sizes, { it == current }, ::sizeLabel, { s -> controller.updateConfig { it.copy(photoSize = s) } })
                    LabeledSlider("JPEG 품질 ${state.controls.jpegQuality}", state.controls.jpegQuality.toFloat(), 50f..100f) { v ->
                        controller.updateControls { it.copy(jpegQuality = v.roundToInt()) }
                    }
                } else {
                    val sizes = (caps.videoSizes + caps.highSpeedFps.keys).distinct().sortedByDescending { it.width.toLong() * it.height }
                    val current = cfg.videoSize ?: sizes.firstOrNull { it.width <= 1920 && it.height <= 1080 } ?: sizes.firstOrNull()
                    CamSectionTitle("동영상 해상도")
                    CamChipRow(sizes.take(24), { it == current }, { "${it.width}x${it.height} ${ratioLabel(it)}" }, { s ->
                        val fpsOk = fpsOptions(desc, s)
                        controller.updateConfig { it.copy(videoSize = s, videoFps = if (it.videoFps in fpsOk) it.videoFps else fpsOk.firstOrNull { f -> f == 30 } ?: fpsOk.firstOrNull() ?: 30) }
                    })
                    if (current != null) {
                        CamSectionTitle("프레임레이트")
                        val normal = caps.fpsFor[current].orEmpty()
                        CamChipRow(fpsOptions(desc, current), { it == cfg.videoFps }, { if (it in normal) "${it}fps" else "${it}fps ⚡HS" }, { f ->
                            controller.updateConfig { it.copy(videoFps = f, videoSize = current) }
                        })
                    }
                    CamSectionTitle("코덱")
                    CamSegmented(VideoCodec.entries, cfg.videoCodec, { it.label }, { c -> controller.updateConfig { it.copy(videoCodec = c) } }, Modifier.fillMaxWidth())
                    if (caps.dynamicRanges.size > 1) {
                        CamSectionTitle("다이나믹 레인지 (10-bit HDR)")
                        CamChipRow(caps.dynamicRanges, { it == cfg.dynamicRange }, Meta::dynamicRangeName, { p -> controller.updateConfig { it.copy(dynamicRange = p) } })
                    }
                    if (caps.stabilizationModes.size > 1) {
                        CamSectionTitle("영상 안정화")
                        val names = Meta.enumNames("CONTROL_VIDEO_STABILIZATION_MODE")
                        CamChipRow(caps.stabilizationModes.toList(), { it == (state.controls.videoStabilization ?: 0) }, { names[it] ?: "$it" }, { m ->
                            controller.updateControls { it.copy(videoStabilization = m) }
                        })
                    }
                }
                if (caps.oisModes.size > 1) {
                    ToggleRow("광학 손떨림 방지(OIS)", state.controls.ois ?: true) { on -> controller.updateControls { it.copy(ois = on) } }
                }
                CamSectionTitle("세션")
                Text(state.sessionKind.ifEmpty { "-" }, color = Cam.colors.textDim, fontSize = 12.sp)
            }
        }
    }
}

private fun fpsOptions(desc: CameraDesc, size: Size): List<Int> =
    (desc.caps.fpsFor[size].orEmpty() + desc.caps.highSpeedFps[size].orEmpty().filter { it > 60 }).distinct().sorted()
