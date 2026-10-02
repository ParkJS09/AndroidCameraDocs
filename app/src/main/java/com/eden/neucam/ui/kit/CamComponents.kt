package com.eden.neucam.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** 프리뷰 위 반투명 컨트롤 바탕 (SurfaceView 위라 블러는 불가 → 스크림 + 얇은 외곽선) */
fun Modifier.glass(shape: Shape, colors: CamColors, fill: Color = colors.glass): Modifier =
    this.clip(shape).background(fill, shape).border(1.dp, colors.stroke, shape)

/** 입력 필드처럼 한 단계 밝은 채움 */
fun Modifier.field(shape: Shape, colors: CamColors): Modifier =
    this.clip(shape).background(colors.field, shape)

@Composable
fun CamSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    surface: Color = Cam.colors.glass,
    content: @Composable () -> Unit,
) {
    Box(modifier.glass(shape, Cam.colors, surface)) { content() }
}

/** 누르면 살짝 작아지는 버튼. selected 면 한 단계 밝은 바탕 + 앰버 외곽선 */
@Composable
fun CamButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    selected: Boolean = false,
    enabled: Boolean = true,
    surface: Color = Cam.colors.glass,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    val c = Cam.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(90), label = "press")
    val bg by animateColorAsState(if (selected) c.field.copy(alpha = 0.22f) else surface, tween(150), label = "bg")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(bg, shape)
            .border(1.dp, if (selected) c.accent.copy(alpha = 0.7f) else c.stroke, shape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = contentAlignment,
    ) { content() }
}

@Composable
fun CamIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    selected: Boolean = false,
    enabled: Boolean = true,
    tint: Color = if (selected) Cam.colors.accent else Cam.colors.text,
) {
    val c = Cam.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, tween(90), label = "press")
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(c.glass, CircleShape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(size * 0.5f))
    }
}

/** 선택 = 앰버 채움 + 어두운 글자 (옵션/설정 값 선택용) */
@Composable
fun CamChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val c = Cam.colors
    val shape = RoundedCornerShape(16.dp)
    val bg by animateColorAsState(if (selected) c.accent else c.glass, tween(150), label = "chip")
    val fg = when {
        !enabled -> c.textDim.copy(alpha = 0.5f)
        selected -> c.onAccent
        else -> c.text
    }
    Row(
        modifier
            .height(32.dp)
            .clip(shape)
            .background(bg, shape)
            .border(1.dp, if (selected) Color.Transparent else c.stroke, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(15.dp), tint = fg)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = fg, fontSize = 12.5.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun <T> CamChipRow(
    options: List<T>,
    selected: (T) -> Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o -> CamChip(label(o), selected(o), { onSelect(o) }) }
    }
}

@Composable
fun CamSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Cam.colors
    val knobX by animateDpAsState(if (checked) 20.dp else 2.dp, tween(150), label = "knob")
    val track by animateColorAsState(if (checked) c.accent else c.field, tween(150), label = "track")
    Box(
        modifier
            .size(46.dp, 28.dp)
            .clip(CircleShape)
            .background(track, CircleShape)
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset { IntOffset(knobX.roundToPx(), 0) }
                .size(24.dp)
                .shadow(2.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

/** 얇은 트랙 + 앰버 진행 + 흰 원형 썸 */
@Composable
fun CamSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val c = Cam.colors
    val onChange by rememberUpdatedState(onValueChange)
    val onFinish by rememberUpdatedState(onValueChangeFinished)
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    BoxWithConstraints(modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.CenterStart) {
        val thumb = 20.dp
        val maxW = maxWidth
        val widthPx = constraints.maxWidth.toFloat()
        val thumbPx = with(LocalDensity.current) { thumb.toPx() }
        fun toValue(x: Float): Float {
            var f = ((x - thumbPx / 2) / (widthPx - thumbPx)).coerceIn(0f, 1f)
            if (steps > 0) f = (f * (steps + 1)).roundToInt() / (steps + 1).toFloat()
            return valueRange.start + f * span
        }
        val gestures = if (!enabled) Modifier else Modifier
            .pointerInput(valueRange, steps, widthPx) {
                detectTapGestures { onChange(toValue(it.x)); onFinish?.invoke() }
            }
            .pointerInput(valueRange, steps, widthPx) {
                detectHorizontalDragGestures(onDragEnd = { onFinish?.invoke() }) { change, _ ->
                    change.consume(); onChange(toValue(change.position.x))
                }
            }
        Box(Modifier.fillMaxWidth().fillMaxHeight().then(gestures), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.padding(horizontal = thumb / 2).fillMaxWidth().height(3.dp).background(c.field, CircleShape))
            Box(
                Modifier.padding(start = thumb / 2)
                    .width((maxW - thumb) * fraction)
                    .height(3.dp)
                    .background(if (enabled) c.accent else c.textDim.copy(alpha = 0.4f), CircleShape),
            )
            Box(
                Modifier
                    .offset { IntOffset(((widthPx - thumbPx) * fraction).roundToInt(), 0) }
                    .size(thumb)
                    .shadow(3.dp, CircleShape)
                    .background(if (enabled) Color.White else c.textDim, CircleShape),
            )
        }
    }
}

/**
 * 카메라 모드 선택기 스타일: 글자만 있고 선택 항목은 앰버 굵은 글씨 + 반투명 알약 배경.
 */
@Composable
fun <T> CamSegmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Cam.colors
    Row(
        modifier.glass(RoundedCornerShape(20.dp), c).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { o ->
            val sel = o == selected
            val bg by animateColorAsState(if (sel) c.field else Color.Transparent, tween(150), label = "seg")
            Box(
                Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(bg)
                    .clickable(role = Role.Tab) { onSelect(o) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(o),
                    color = if (sel) c.accent else c.text.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun CamSectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), color = Cam.colors.textDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, modifier = Modifier.weight(1f))
        trailing()
    }
}
