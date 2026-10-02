package com.eden.neucam.ui

import android.graphics.Color as AColor
import android.util.Size
import android.view.Gravity
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.eden.neucam.camera.PreviewTarget
import com.eden.neucam.camera.PreviewTransform
import kotlinx.coroutines.Job
import kotlin.math.roundToInt

/**
 * Camera2 출력용 SurfaceView 프리뷰.
 *
 * - 회전: 합성기가 센서 방향과 디스플레이 회전을 처리하므로 행렬 변환이 없다.
 * - 비율: SurfaceView 는 버퍼를 자기 크기로 늘려 그리므로, 화면에 보이는 콘텐츠 비율로 SurfaceView 크기를 정한다.
 *   fill = 컨테이너를 덮도록 더 크게(가운데 정렬, 부모 FrameLayout 이 잘라냄) / fit = 컨테이너 안에 맞춤(레터박스)
 * - 모서리: SurfaceView 는 별도 레이어라 Compose clip 이 적용되지 않으므로 [cornerRadius] 바깥을 [maskColor] 로 덮는다.
 */
@Composable
fun SurfacePreview(
    bufferSize: Size?,
    sensorOrientation: Int,
    displayRotation: Int,
    fill: Boolean,
    onAvailable: (PreviewTarget) -> Unit,
    onDestroyed: (PreviewTarget) -> Job,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 0.dp,
    maskColor: Color = Color.Transparent,
    mediaOverlay: Boolean = false,
) {
    val available by rememberUpdatedState(onAvailable)
    val destroyed by rememberUpdatedState(onDestroyed)
    BoxWithConstraints(modifier) {
        val cw = constraints.maxWidth
        val ch = constraints.maxHeight
        val aspect = bufferSize?.let { PreviewTransform.displayedAspect(it.width, it.height, sensorOrientation, displayRotation) }
        val (sw, sh) = when {
            aspect == null || cw <= 0 || ch <= 0 -> cw to ch
            else -> {
                val byWidth = cw to (cw / aspect).roundToInt()
                val byHeight = (ch * aspect).roundToInt() to ch
                if (fill) (if (byWidth.second >= ch) byWidth else byHeight)
                else (if (byWidth.second <= ch) byWidth else byHeight)
            }
        }
        AndroidView(
            factory = { ctx ->
                val sv = SurfaceView(ctx).apply { if (mediaOverlay) setZOrderMediaOverlay(true) }
                PreviewTarget(sv, onCreated = { available(it) }, onDestroyed = { destroyed(it) })
                FrameLayout(ctx).apply {
                    clipChildren = true
                    setBackgroundColor(AColor.BLACK)
                    addView(sv, FrameLayout.LayoutParams(sw, sh, Gravity.CENTER))
                }
            },
            update = { frame ->
                val sv = frame.getChildAt(0)
                val lp = sv.layoutParams as FrameLayout.LayoutParams
                if (lp.width != sw || lp.height != sh) {
                    lp.width = sw
                    lp.height = sh
                    sv.layoutParams = lp
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (cornerRadius > 0.dp && maskColor != Color.Transparent) {
            Canvas(Modifier.fillMaxSize()) {
                val r = cornerRadius.toPx()
                val mask = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                    addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r)))
                }
                drawPath(mask, maskColor)
            }
        }
    }
}
