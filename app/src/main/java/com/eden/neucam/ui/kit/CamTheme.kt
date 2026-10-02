package com.eden.neucam.ui.kit

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 카메라 앱 전용 다크 팔레트.
 *
 * 카메라 UI 는 시스템 테마와 무관하게 항상 어둡게 유지한다.
 * - 프리뷰 주변이 밝으면 노출/색을 판단하기 어렵고, 시선이 이미지에서 분산된다.
 * - 프리뷰 위 컨트롤은 반투명 검정([glass]) 위 흰색 → 어떤 장면에서도 읽힌다.
 * - 강조색은 앰버 하나만 사용(선택 상태·수동값). 빨강 = 녹화, 초록 = 초점 고정.
 */
@Immutable
data class CamColors(
    /** 창 배경 (프리뷰 레터박스 포함) */
    val bg: Color,
    /** 목록/시트의 불투명 카드 */
    val card: Color,
    /** 프리뷰 위에 뜨는 반투명 컨트롤 바탕 */
    val glass: Color,
    /** 입력 필드·비선택 트랙 등 한 단계 밝은 채움 */
    val field: Color,
    /** 얇은 외곽선 */
    val stroke: Color,
    val text: Color,
    val textDim: Color,
    val accent: Color,
    /** 앰버 위 글자색 */
    val onAccent: Color,
    val accent2: Color,
    val danger: Color,
)

val CameraDark = CamColors(
    bg = Color(0xFF000000),
    card = Color(0xFF16171A),
    glass = Color(0x8C000000),
    field = Color(0x1FFFFFFF),
    stroke = Color(0x1FFFFFFF),
    text = Color(0xFFF5F5F7),
    textDim = Color(0xFF9A9AA1),
    accent = Color(0xFFFFC53D),
    onAccent = Color(0xFF1A1400),
    accent2 = Color(0xFF34D17A),
    danger = Color(0xFFFF453A),
)

val LocalCam = staticCompositionLocalOf { CameraDark }

object Cam {
    val colors: CamColors
        @Composable get() = LocalCam.current
}

@Composable
fun CamTheme(content: @Composable () -> Unit) {
    val c = CameraDark
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent,
            background = c.bg, surface = c.card, onSurface = c.text, onBackground = c.text,
        ),
    ) {
        CompositionLocalProvider(LocalCam provides c, LocalContentColor provides c.text, content = content)
    }
}
