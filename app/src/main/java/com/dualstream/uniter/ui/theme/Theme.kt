package com.dualstream.uniter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF4C8DFF)
private val AccentDark = Color(0xFF1F6FEB)
private val Good = Color(0xFF3FB950)
private val Warn = Color(0xFFD29922)
private val Bad = Color(0xFFF85149)

object StatusColors {
    val good = Good
    val warn = Warn
    val bad = Bad
    val accent = Accent
}

private val Dark = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Color(0xFF8B949E),
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    surfaceVariant = Color(0xFF21262D),
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
    error = Bad
)

private val Light = lightColorScheme(
    primary = AccentDark,
    onPrimary = Color.White,
    secondary = Color(0xFF57606A),
    background = Color(0xFFF6F8FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFEAEEF2),
    onBackground = Color(0xFF1F2328),
    onSurface = Color(0xFF1F2328),
    error = Bad
)

@Composable
fun DualStreamTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = Typography(),
        content = content
    )
}
