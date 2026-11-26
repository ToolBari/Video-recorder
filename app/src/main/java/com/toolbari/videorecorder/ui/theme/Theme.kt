package com.toolbari.videorecorder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Greenline look (owner's theme): dark green-black + green accent.
private val Green = Color(0xFF34E89A)
private val Amber = Color(0xFFFFB020)
private val Background = Color(0xFF060F0A)
private val Surface = Color(0xFF0D1A12)
private val SurfaceVariant = Color(0xFF14261B)
private val Text = Color(0xFFE8F5EE)
private val Muted = Color(0xFF9DB0A4)

private val VideoRecorderColors = darkColorScheme(
    primary = Green,
    onPrimary = Color(0xFF062015),
    secondary = Amber,
    onSecondary = Color(0xFF241600),
    background = Background,
    onBackground = Text,
    surface = Surface,
    onSurface = Text,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = Muted,
    error = Color(0xFFFF6B6B)
)

@Composable
fun VideoRecorderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VideoRecorderColors,
        content = content
    )
}
