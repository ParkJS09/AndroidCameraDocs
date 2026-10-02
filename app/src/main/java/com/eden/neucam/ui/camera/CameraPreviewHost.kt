package com.eden.neucam.ui.camera

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eden.neucam.AppViewModel
import com.eden.neucam.camera.CameraStatus
import com.eden.neucam.camera.CameraUiState
import com.eden.neucam.camera.LiveResult
import com.eden.neucam.camera.PreviewTransform
import com.eden.neucam.ui.SurfacePreview
import com.eden.neucam.ui.kit.Cam
import com.eden.neucam.ui.kit.CamButton
import com.eden.neucam.ui.kit.CamSurface
import android.hardware.camera2.CameraMetadata as CM

@Composable
fun CameraPreviewHost(
    vm: AppViewModel,
    state: CameraUiState,
    live: LiveResult,
    zoomRange: ClosedFloatingPointRange<Float>,
    displayRotation: Int,
    fill: Boolean,
    rounded: Boolean,
    interactive: Boolean,
    grid: Boolean,
    modifier: Modifier = Modifier,
) {
    val controller = vm.controller
    val shape = if (rounded) RoundedCornerShape(24.dp) else RectangleShape
    var ring by remember { mutableStateOf<Offset?>(null) }
    var ringSeq by remember { mutableIntStateOf(0) }
    val ringAlpha = remember { Animatable(0f) }
    val cur by rememberUpdatedState(state)
    val range by rememberUpdatedState(zoomRange)

    LaunchedEffect(ringSeq) {
        if (ringSeq == 0) return@LaunchedEffect
        ringAlpha.snapTo(1f)
        kotlinx.coroutines.delay(1400)
        ringAlpha.animateTo(0f, tween(500))
    }

    Box(modifier.clip(shape).background(Color.Black)) {
        SurfacePreview(
            bufferSize = state.previewSize,
            sensorOrientation = state.sensorOrientation,
            displayRotation = displayRotation,
            fill = fill,
            onAvailable = { controller.setPreviewTarget(it) },
            onDestroyed = { controller.releaseTarget(it) },
            modifier = Modifier.fillMaxSize(),
            cornerRadius = if (rounded) 24.dp else 0.dp,
            maskColor = Cam.colors.bg,
        )
        if (interactive) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(displayRotation, fill) {
                        detectTapGestures(
                            onTap = { p ->
                                val s = cur
                                val buf = s.previewSize ?: return@detectTapGestures
                                val (nx, ny) = PreviewTransform.viewToSensor(
                                    p.x, p.y, size.width.toFloat(), size.height.toFloat(),
                                    buf.width, buf.height, s.sensorOrientation, displayRotation, fill,
                                    s.facing == CM.LENS_FACING_FRONT,
                                )
                                controller.focusAt(nx, ny)
                                ring = p
                                ringSeq++
                            },
                            onDoubleTap = { controller.resetFocus(); ring = null },
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (zoom != 1f) controller.updateControls { c ->
                                c.copy(zoom = (c.zoom * zoom).coerceIn(range.start, range.endInclusive))
                            }
                        }
                    },
            )
        }
        if (grid) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Color.White.copy(alpha = 0.35f)
                for (i in 1..2) {
                    drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.5f)
                    drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.5f)
                }
            }
        }
        val r = ring
        if (r != null && ringAlpha.value > 0f) {
            val color = when (live.afState) {
                CM.CONTROL_AF_STATE_FOCUSED_LOCKED, CM.CONTROL_AF_STATE_PASSIVE_FOCUSED -> Cam.colors.accent2
                CM.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> Cam.colors.danger
                else -> Color.White
            }
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(color.copy(alpha = ringAlpha.value), radius = 38.dp.toPx(), center = r, style = Stroke(2.5.dp.toPx()))
                drawCircle(color.copy(alpha = ringAlpha.value * 0.6f), radius = 4.dp.toPx(), center = r)
            }
        }
        when (state.status) {
            CameraStatus.OPENING -> StatusCard("카메라 여는 중…", null) {}
            CameraStatus.ERROR -> StatusCard(state.error ?: "오류", "다시 시도") { vm.retry() }
            else -> {}
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.StatusCard(text: String, action: String?, onAction: () -> Unit) {
    CamSurface(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 320.dp)) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = Cam.colors.text, fontSize = 14.sp)
            if (action != null) {
                CamButton(onAction, Modifier.padding(top = 14.dp)) {
                    Text(action, color = Cam.colors.accent, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                }
            }
        }
    }
}
