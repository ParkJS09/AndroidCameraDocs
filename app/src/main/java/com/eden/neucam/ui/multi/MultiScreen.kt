package com.eden.neucam.ui.multi

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.eden.neucam.AppViewModel
import com.eden.neucam.camera.CameraStatus
import com.eden.neucam.ui.SurfacePreview
import com.eden.neucam.ui.camera.Toast
import com.eden.neucam.ui.kit.Cam
import com.eden.neucam.ui.kit.CamButton
import com.eden.neucam.ui.kit.CamChipRow
import com.eden.neucam.ui.kit.CamIconButton
import com.eden.neucam.ui.kit.CamSegmented
import com.eden.neucam.ui.kit.glass
import androidx.compose.foundation.border

private enum class MultiLayout(val label: String) { SPLIT("분할"), PIP("PiP") }

@Composable
fun MultiScreen(vm: AppViewModel, displayRotation: Int, modifier: Modifier = Modifier) {
    val multi = vm.multi
    val st by multi.state.collectAsState()
    val pairs by vm.multiPairs.collectAsState()
    var layout by rememberSaveable { mutableStateOf(MultiLayout.SPLIT) }
    var swapped by rememberSaveable { mutableStateOf(false) }
    val c = Cam.colors

    Column(modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text("멀티 카메라", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            if (pairs.isEmpty()) "이 기기에서 사용할 수 있는 조합이 없습니다" else "논리 카메라의 물리 스트림 또는 동시(concurrent) 카메라 조합을 선택하세요",
            color = c.textDim, fontSize = 12.sp,
        )
        CamChipRow(pairs, { it == st.pair }, { it.label }, { multi.setPair(it) })

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
            val w = maxWidth
            val h = maxHeight
            val landscape = w > h
            val gap = 10.dp
            data class R(val x: Dp, val y: Dp, val w: Dp, val h: Dp)
            val rects: List<R> = if (layout == MultiLayout.SPLIT) {
                if (landscape) listOf(R(0.dp, 0.dp, (w - gap) / 2, h), R((w + gap) / 2, 0.dp, (w - gap) / 2, h))
                else listOf(R(0.dp, 0.dp, w, (h - gap) / 2), R(0.dp, (h + gap) / 2, w, (h - gap) / 2))
            } else {
                val sw = if (landscape) w * 0.28f else w * 0.36f
                val sh = if (landscape) h * 0.36f else h * 0.26f
                val big = R(0.dp, 0.dp, w, h)
                val small = R(w - sw - 12.dp, h - sh - 12.dp, sw, sh)
                if (swapped) listOf(small, big) else listOf(big, small)
            }
            for (i in 0..1) key(i) {
                val r = rects[i]
                val x by animateDpAsState(r.x, label = "x$i")
                val y by animateDpAsState(r.y, label = "y$i")
                val rw by animateDpAsState(r.w, label = "w$i")
                val rh by animateDpAsState(r.h, label = "h$i")
                val isSmall = layout == MultiLayout.PIP && (if (swapped) i == 0 else i == 1)
                val view = st.views.getOrNull(i)
                Box(
                    Modifier
                        .zIndex(if (isSmall) 1f else 0f)
                        .offset(x, y)
                        .size(rw, rh)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black)
                        .border(if (isSmall) 2.dp else 0.dp, if (isSmall) Color.White.copy(alpha = 0.85f) else Color.Transparent, RoundedCornerShape(16.dp)),
                ) {
                    // SurfaceView 는 겹칠 때 Z 순서를 붙인 뒤 바꿀 수 없으므로 PiP 역할이 바뀌면 새로 만든다
                    key(isSmall) {
                        SurfacePreview(
                            bufferSize = view?.size,
                            sensorOrientation = view?.sensorOrientation ?: 90,
                            displayRotation = displayRotation,
                            fill = true,
                            onAvailable = { multi.setTarget(i, it) },
                            onDestroyed = { multi.releaseTarget(i, it) },
                            modifier = Modifier.fillMaxSize(),
                            cornerRadius = 16.dp,
                            maskColor = c.bg,
                            mediaOverlay = isSmall,
                        )
                    }
                    Column(Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(view?.label ?: "-", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (!isSmall) Text(st.live.getOrNull(i).orEmpty(), color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            if (st.status == CameraStatus.OPENING) CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.accent)
            if (st.status == CameraStatus.ERROR) {
                Box(Modifier.align(Alignment.Center).zIndex(2f).widthIn(max = 320.dp).glass(RoundedCornerShape(20.dp), c, c.card).padding(18.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(st.error ?: "오류", color = c.text, fontSize = 13.sp)
                        CamButton({ vm.retry() }, Modifier.padding(top = 12.dp)) {
                            Text("다시 시도", color = c.accent, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                }
            }
            Toast(st.message, st.messageSeq, Modifier.align(Alignment.TopCenter).zIndex(3f))
        }

        if (st.info.isNotEmpty()) Text(st.info, color = c.textDim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CamSegmented(MultiLayout.entries, layout, { it.label }, { layout = it }, Modifier.weight(1f))
            CamButton({ multi.capture() }, Modifier.size(64.dp), CircleShape, enabled = !st.capturing) {
                if (st.capturing) CircularProgressIndicator(Modifier.size(30.dp), color = c.accent, strokeWidth = 3.dp)
                else Box(Modifier.size(28.dp).clip(CircleShape).background(c.accent))
            }
            CamIconButton(Icons.Filled.SwapHoriz, "전환", { swapped = !swapped }, size = 48.dp, enabled = layout == MultiLayout.PIP)
        }
    }
}
