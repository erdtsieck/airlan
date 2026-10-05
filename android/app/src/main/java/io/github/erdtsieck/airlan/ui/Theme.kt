package io.github.erdtsieck.airlan.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The web app's palette: cool blue, heat orange, and a neutral grey for "off". */
@Immutable
data class AirColors(
    val bg: Color,
    val card: Color,
    val text: Color,
    val muted: Color,
    val line: Color,
    val cool: Color,
    val heat: Color,
    val off: Color,
    val danger: Color,
)

private val Light = AirColors(
    bg = Color(0xFFF4F5F7), card = Color.White, text = Color(0xFF16181D), muted = Color(0xFF6B7280),
    line = Color(0xFFE3E5EA), cool = Color(0xFF1D7FE0), heat = Color(0xFFE8641B), off = Color(0xFF9AA1AD),
    danger = Color(0xFFC62828),
)

private val Dark = AirColors(
    bg = Color(0xFF0F1115), card = Color(0xFF191C22), text = Color(0xFFEEF0F3), muted = Color(0xFF9098A5),
    line = Color(0xFF2A2E36), cool = Color(0xFF4EA3FF), heat = Color(0xFFFF8A3D), off = Color(0xFF5D6470),
    danger = Color(0xFFFF6B6B),
)

val LocalAirColors = staticCompositionLocalOf { Light }

@Composable
fun AirLanTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(primary = c.cool, background = c.bg, surface = c.card, onBackground = c.text, onSurface = c.text, error = c.danger)
    } else {
        lightColorScheme(primary = c.cool, background = c.bg, surface = c.card, onBackground = c.text, onSurface = c.text, error = c.danger)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalAirColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
