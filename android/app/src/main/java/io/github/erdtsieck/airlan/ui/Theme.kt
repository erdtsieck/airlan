package io.github.erdtsieck.airlan.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import io.github.erdtsieck.airlan.wfrac.Mode

/**
 * The web app's palette: a colour per operating mode (cool blue, heat orange, auto green, dry
 * teal, fan violet) and a neutral grey for "off".
 */
@Immutable
data class AirColors(
    val bg: Color,
    val card: Color,
    val text: Color,
    val muted: Color,
    val line: Color,
    val cool: Color,
    val heat: Color,
    val auto: Color,
    val dry: Color,
    val fan: Color,
    val off: Color,
    val danger: Color,
)

private val Light = AirColors(
    bg = Color(0xFFF4F5F7), card = Color.White, text = Color(0xFF16181D), muted = Color(0xFF6B7280),
    line = Color(0xFFE3E5EA), cool = Color(0xFF1D7FE0), heat = Color(0xFFE8641B),
    auto = Color(0xFF16A34A), dry = Color(0xFF0D9488), fan = Color(0xFF7C3AED), off = Color(0xFF9AA1AD),
    danger = Color(0xFFC62828),
)

private val Dark = AirColors(
    bg = Color(0xFF0F1115), card = Color(0xFF191C22), text = Color(0xFFEEF0F3), muted = Color(0xFF9098A5),
    line = Color(0xFF2A2E36), cool = Color(0xFF4EA3FF), heat = Color(0xFFFF8A3D),
    auto = Color(0xFF4ADE80), dry = Color(0xFF2DD4BF), fan = Color(0xFFA78BFA), off = Color(0xFF5D6470),
    danger = Color(0xFFFF6B6B),
)

/** The colour of an operating mode; [AirColors.text] for one this app does not know. */
fun AirColors.of(mode: Mode?): Color = when (mode) {
    Mode.COOL -> cool
    Mode.HEAT -> heat
    Mode.AUTO -> auto
    Mode.DRY -> dry
    Mode.FAN -> fan
    null -> text
}

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
