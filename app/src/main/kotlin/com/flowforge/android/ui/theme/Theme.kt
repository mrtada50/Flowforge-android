package com.flowforge.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * OpenBullet-inspired palette: near-black navy surfaces, a cyan "brand" accent for actions and
 * selection, and a fixed set of status colors used everywhere a run/result status is shown
 * (runner tiles, result chips, node headers) so the same status always reads the same color.
 */
object FFColors {
    val bg = Color(0xFF0A0E16)
    val surface = Color(0xFF0F1420)
    val surfaceRaised = Color(0xFF151B2B)
    val border = Color(0xFF232B40)
    val cyan = Color(0xFF34E3C8)
    val cyanDim = Color(0xFF1C7A6C)
    val blue = Color(0xFF4C8DFF)
    val textPrimary = Color(0xFFE7ECF5)
    val textMuted = Color(0xFF8893A8)

    val success = Color(0xFF2ED47A)
    val fail = Color(0xFFFF5470)
    val warn = Color(0xFFFFB23F)
    val ban = Color(0xFFFF8A3D)
    val retry = Color(0xFFC792EA)
    val neutral = Color(0xFF5B6B85)
    val skip = Color(0xFF6B7690)
}

private val FlowForgeColors = darkColorScheme(
    primary = FFColors.cyan,
    onPrimary = Color(0xFF00241F),
    secondary = FFColors.success,
    onSecondary = Color(0xFF002C14),
    tertiary = FFColors.blue,
    background = FFColors.bg,
    onBackground = FFColors.textPrimary,
    surface = FFColors.surface,
    onSurface = FFColors.textPrimary,
    surfaceVariant = FFColors.surfaceRaised,
    onSurfaceVariant = FFColors.textMuted,
    outline = FFColors.border,
    error = FFColors.fail,
    errorContainer = Color(0xFF3A0F1A),
    onErrorContainer = FFColors.fail,
)

@Composable
fun FlowForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FlowForgeColors, content = content)
}
