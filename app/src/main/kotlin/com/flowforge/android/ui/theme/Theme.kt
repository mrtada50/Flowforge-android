package com.flowforge.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FlowForgeColors = darkColorScheme(
    primary = Color(0xFF7CB7FF),
    onPrimary = Color(0xFF00315C),
    secondary = Color(0xFF9FD6B5),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE3E6EB),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE3E6EB),
    surfaceVariant = Color(0xFF212833),
    onSurfaceVariant = Color(0xFFB4BCC8),
    error = Color(0xFFFF8A80),
)

@Composable
fun FlowForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FlowForgeColors, content = content)
}
