package com.eden.neucam.ui

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.eden.neucam.AppTab
import com.eden.neucam.camera.PreviewMode
import kotlin.math.min
import kotlin.math.roundToInt

/** 폴더블 자세 */
sealed interface Posture {
    data object Flat : Posture
    /** 가로 힌지 반쯤 접힘: 위쪽 = 프리뷰, 아래쪽 = 컨트롤 */
    data class Tabletop(val hinge: Rect) : Posture
    /** 세로 힌지 반쯤 접힘(또는 분리형 듀얼 스크린): 왼쪽 = 프리뷰, 오른쪽 = 컨트롤 */
    data class Book(val hinge: Rect) : Posture
}

@Composable
fun rememberPosture(): Posture {
    val activity = LocalActivity.current ?: return Posture.Flat
    val flow = remember(activity) { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) }
    var posture by remember { androidx.compose.runtime.mutableStateOf<Posture>(Posture.Flat) }
    LaunchedEffect(flow) {
        flow.collect { info ->
            val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
            val b = fold?.bounds
            posture = when {
                fold == null || b == null -> Posture.Flat
                !(fold.state == FoldingFeature.State.HALF_OPENED || fold.isSeparating) -> Posture.Flat
                fold.orientation == FoldingFeature.Orientation.HORIZONTAL ->
                    Posture.Tabletop(Rect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()))
                else -> Posture.Book(Rect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()))
            }
        }
    }
    return posture
}

/**
 * 현재 디스플레이 회전(도).
 * 0↔180 회전은 구성 변경이 일어나지 않으므로 DisplayListener 로 따로 감시한다.
 */
@Composable
fun rememberDisplayRotation(): Int {
    val context = LocalContext.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    var rotation by remember { mutableIntStateOf(view.display?.rotation ?: 0) }
    LaunchedEffect(configuration) { view.display?.let { rotation = it.rotation } }
    DisposableEffect(view) {
        val dm = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                view.display?.let { if (it.displayId == displayId) rotation = it.rotation }
            }
        }
        dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { dm.unregisterDisplayListener(listener) }
    }
    return rotation * 90
}

@Immutable
data class Insets(val left: Float, val top: Float, val right: Float, val bottom: Float)

@Immutable
data class AppLayout(
    val preview: Rect?,
    val previewFill: Boolean,
    val previewRounded: Boolean,
    /** 탭 콘텐츠(카메라 컨트롤 오버레이, 옵션 목록 등) 영역 */
    val content: Rect,
    val tabBar: Rect,
    val tabVertical: Boolean,
    /** 카메라 컨트롤을 세로 열(가로 화면) 배치로 그릴지 */
    val controlsVertical: Boolean,
    val posture: Posture,
)

object LayoutCalc {

    fun compute(
        w: Float, h: Float, ins: Insets, d: Density,
        tab: AppTab, posture: Posture, previewMode: PreviewMode, contentAspect: Float?,
    ): AppLayout = with(d) {
        val m = 12.dp.toPx()
        val barH = 64.dp.toPx()
        val railW = 72.dp.toPx()
        val landscape = w > h
        // ---- 탭 바
        val (tabBar, vertical) = when {
            posture is Posture.Book -> {
                val left = posture.hinge.right + m
                val width = min(w - ins.right - m - left, 420.dp.toPx())
                val x = left + ((w - ins.right - m - left) - width) / 2
                Rect(x, h - ins.bottom - m - barH, x + width, h - ins.bottom - m) to false
            }
            landscape && posture !is Posture.Tabletop -> {
                val height = min(h - ins.top - ins.bottom - 2 * m, 340.dp.toPx())
                val y = ins.top + ((h - ins.top - ins.bottom) - height) / 2
                Rect(ins.left + m, y, ins.left + m + railW, y + height) to true
            }
            else -> {
                val width = min(w - ins.left - ins.right - 2 * m, 420.dp.toPx())
                val x = (w - width) / 2
                Rect(x, h - ins.bottom - m - barH, x + width, h - ins.bottom - m) to false
            }
        }
        val gap = 8.dp.toPx()
        val base = if (vertical) Rect(tabBar.right + gap, ins.top, w - ins.right, h - ins.bottom)
        else Rect(ins.left, ins.top, w - ins.right, tabBar.top - gap)

        // aspect = 화면에 보이는 콘텐츠의 가로/세로. 모르면 영역 방향에 맞춘 16:9
        fun fit(region: Rect, aspect: Float?): Rect {
            val a = aspect ?: if (region.width > region.height) 16f / 9f else 9f / 16f
            val rw: Float
            val rh: Float
            if (region.width / region.height > a) { rh = region.height; rw = rh * a } else { rw = region.width; rh = rw / a }
            val x = region.left + (region.width - rw) / 2
            val y = region.top + (region.height - rh) / 2
            return Rect(x, y, x + rw, y + rh)
        }

        when (tab) {
            AppTab.CAMERA -> when (posture) {
                is Posture.Tabletop -> {
                    val region = Rect(ins.left, ins.top, w - ins.right, posture.hinge.top)
                    val p = if (previewMode == PreviewMode.FULL) Rect(0f, 0f, w, posture.hinge.top) else fit(region, contentAspect)
                    AppLayout(p, true, previewMode != PreviewMode.FULL, Rect(ins.left, posture.hinge.bottom, w - ins.right, tabBar.top - gap), tabBar, vertical, false, posture)
                }
                is Posture.Book -> {
                    val region = Rect(ins.left, ins.top, posture.hinge.left, h - ins.bottom)
                    val p = if (previewMode == PreviewMode.FULL) Rect(0f, 0f, posture.hinge.left, h) else fit(region, contentAspect)
                    AppLayout(p, true, previewMode != PreviewMode.FULL, Rect(posture.hinge.right, ins.top, w - ins.right, tabBar.top - gap), tabBar, vertical, false, posture)
                }
                Posture.Flat -> {
                    if (previewMode == PreviewMode.FULL) {
                        AppLayout(Rect(0f, 0f, w, h), true, false, base, tabBar, vertical, landscape, posture)
                    } else {
                        val region = if (landscape) Rect(base.left, base.top, base.right - 104.dp.toPx(), base.bottom)
                        else Rect(base.left, base.top + 56.dp.toPx(), base.right, base.bottom)
                        AppLayout(fit(region, contentAspect), true, true, base, tabBar, vertical, landscape, posture)
                    }
                }
            }
            AppTab.OPTIONS -> {
                val pad = 12.dp.toPx()
                when {
                    posture is Posture.Tabletop -> AppLayout(
                        Rect(ins.left + pad, ins.top + pad, w - ins.right - pad, posture.hinge.top - pad), true, true,
                        Rect(ins.left, posture.hinge.bottom, w - ins.right, tabBar.top - gap), tabBar, vertical, false, posture,
                    )
                    posture is Posture.Book -> AppLayout(
                        Rect(ins.left + pad, ins.top + pad, posture.hinge.left - pad, h - ins.bottom - pad), true, true,
                        Rect(posture.hinge.right, ins.top, w - ins.right, tabBar.top - gap), tabBar, vertical, false, posture,
                    )
                    landscape -> {
                        val pw = (base.width * 0.42f)
                        val p = Rect(base.left + pad, base.top + pad, base.left + pad + pw, base.bottom - pad)
                        AppLayout(p, true, true, Rect(p.right + pad, base.top, base.right, base.bottom), tabBar, vertical, false, posture)
                    }
                    else -> {
                        val ph = min(h * 0.3f, (base.width - 2 * pad) * 0.66f)
                        val p = Rect(base.left + pad, base.top + pad, base.right - pad, base.top + pad + ph)
                        AppLayout(p, true, true, Rect(base.left, p.bottom + pad / 2, base.right, base.bottom), tabBar, vertical, false, posture)
                    }
                }
            }
            else -> AppLayout(null, true, false, base, tabBar, vertical, landscape, posture)
        }
    }
}

/** px Rect 위치/크기로 배치 */
@Composable
fun Modifier.place(rect: Rect): Modifier {
    val d = LocalDensity.current
    return this
        .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
        .size(with(d) { rect.width.toDp() }, with(d) { rect.height.toDp() })
}
