package com.gamecore.studio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The studio's own colours.
 *
 * Deliberately *not* the colours of the theme being designed. The editor is a tool, and a
 * tool that took on the colours of its subject would make the preview unreadable the moment
 * someone picked a dark background — the preview would disappear into the page.
 */
private val StudioColors = darkColorScheme(
    primary = Color(0xFF7C5CFF),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF2BD4C0),
    onSecondary = Color(0xFF00201C),
    background = Color(0xFF101014),
    onBackground = Color(0xFFE9E6F0),
    surface = Color(0xFF1A1A20),
    onSurface = Color(0xFFE9E6F0),
    surfaceVariant = Color(0xFF26262E),
    onSurfaceVariant = Color(0xFFB9B5C6),
    outline = Color(0xFF474653),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0606),
)

/** The studio's Material theme. */
@Composable
fun PluginStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StudioColors,
        content = content,
    )
}
