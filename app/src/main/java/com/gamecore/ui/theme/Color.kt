package com.gamecore.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.gamecore.core.model.AccentChoice

/**
 * Every colour the app draws, and the two schemes built out of them.
 *
 * The palette is written here rather than in `colors.xml` because the theme is chosen at runtime: the
 * user picks one of [AccentChoice]'s six accents and a light/dark/system mode, so the scheme is a
 * function of two settings rather than a pair of resource folders. The four colours that *are* in
 * `colors.xml` exist for the launch window, before any of this has run.
 *
 * The accent drives the primary roles and nothing else. That is a deliberate limit: an accent applied
 * to every surface produces a differently-coloured app per choice, and this app spends its screen
 * space on figures that have to stay readable — so the neutrals are fixed, the accent highlights, and
 * the two semantic colours below never change with it at all.
 *
 * [ThermalWarning] and [ThermalCritical] are not part of any scheme on purpose. A user whose accent is
 * amber must still be able to tell "the device is throttling" from "this is the accent colour", so
 * heat and failure are drawn in colours the accent cannot take.
 */

/** Amber: the system has started limiting performance. Never used decoratively. */
val ThermalWarning = Color(0xFFFFB300)

/** Red: a critical thermal state, or an operation that failed. */
val ThermalCritical = Color(0xFFFF5A5A)

/** Green: a confirmed success — an optimisation the device acknowledged, a permission granted. */
val StatusGood = Color(0xFF4CE07A)

/** The second series in a two-line graph. Cool, so it reads as "other" against any accent. */
val ChartAlternate = Color(0xFF6C8CFF)

// COLOR_BODY

// The dark neutrals. GameCore is dark-first: this is the scheme the app opens in on a new install and
// the one the launch window matches, so the near-black is the same value as `@color/window_background`.
private val DarkBackground = Color(0xFF07090E)
private val DarkSurface = Color(0xFF0B0F16)
private val DarkSurfaceVariant = Color(0xFF1A2130)
private val DarkOutline = Color(0xFF2A3342)
private val DarkOnSurface = Color(0xFFE8ECF4)
private val DarkOnSurfaceVariant = Color(0xFFA5AEC0)

// The light neutrals. Cool-tinted rather than pure white, because the same graphs and the same stat
// cards are drawn in both and a white card with a thin outline disappears on an LCD in daylight.
private val LightBackground = Color(0xFFF6F8FC)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFE6EAF2)
private val LightOutline = Color(0xFFC3CAD8)
private val LightOnSurface = Color(0xFF101623)
private val LightOnSurfaceVariant = Color(0xFF4A5468)

/** The dark scheme for one accent. */
fun darkSchemeFor(accent: AccentChoice): ColorScheme {
    val primary = Color(accent.argb)
    return darkColorScheme(
        primary = primary,
        onPrimary = readableOn(primary),
        primaryContainer = lerp(DarkSurface, primary, 0.20f),
        onPrimaryContainer = lerp(primary, Color.White, 0.55f),
        inversePrimary = lerp(primary, Color.Black, 0.35f),
        secondary = lerp(primary, DarkOnSurfaceVariant, 0.45f),
        onSecondary = readableOn(lerp(primary, DarkOnSurfaceVariant, 0.45f)),
        secondaryContainer = lerp(DarkSurface, primary, 0.10f),
        onSecondaryContainer = DarkOnSurface,
        tertiary = ChartAlternate,
        onTertiary = readableOn(ChartAlternate),
        tertiaryContainer = lerp(DarkSurface, ChartAlternate, 0.16f),
        onTertiaryContainer = lerp(ChartAlternate, Color.White, 0.5f),
        background = DarkBackground,
        onBackground = DarkOnSurface,
        surface = DarkSurface,
        onSurface = DarkOnSurface,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = DarkOnSurfaceVariant,
        surfaceTint = primary,
        inverseSurface = DarkOnSurface,
        inverseOnSurface = DarkBackground,
        error = ThermalCritical,
        onError = readableOn(ThermalCritical),
        errorContainer = lerp(DarkSurface, ThermalCritical, 0.18f),
        onErrorContainer = lerp(ThermalCritical, Color.White, 0.55f),
        outline = DarkOutline,
        outlineVariant = lerp(DarkOutline, DarkBackground, 0.45f),
        scrim = Color.Black,
    )
}

// COLOR_LIGHT

/**
 * The light scheme for one accent.
 *
 * The accents are chosen to be legible on a dark plate, so several of them — cyan especially — are too
 * bright to put white text on or to draw as a thin line on white. Each one is darkened toward black
 * before it becomes `primary` here, which keeps the six choices distinguishable in light mode without
 * needing a second set of six literals that could drift out of step with the first.
 */
fun lightSchemeFor(accent: AccentChoice): ColorScheme {
    val raw = Color(accent.argb)
    val primary = lerp(raw, Color.Black, 0.28f)
    return lightColorScheme(
        primary = primary,
        onPrimary = readableOn(primary),
        primaryContainer = lerp(LightSurface, raw, 0.22f),
        onPrimaryContainer = lerp(primary, Color.Black, 0.35f),
        inversePrimary = raw,
        secondary = lerp(primary, LightOnSurfaceVariant, 0.40f),
        onSecondary = Color.White,
        secondaryContainer = lerp(LightSurface, raw, 0.12f),
        onSecondaryContainer = LightOnSurface,
        tertiary = lerp(ChartAlternate, Color.Black, 0.25f),
        onTertiary = Color.White,
        tertiaryContainer = lerp(LightSurface, ChartAlternate, 0.18f),
        onTertiaryContainer = lerp(ChartAlternate, Color.Black, 0.45f),
        background = LightBackground,
        onBackground = LightOnSurface,
        surface = LightSurface,
        onSurface = LightOnSurface,
        surfaceVariant = LightSurfaceVariant,
        onSurfaceVariant = LightOnSurfaceVariant,
        surfaceTint = primary,
        inverseSurface = LightOnSurface,
        inverseOnSurface = LightBackground,
        error = lerp(ThermalCritical, Color.Black, 0.25f),
        onError = Color.White,
        errorContainer = lerp(LightSurface, ThermalCritical, 0.16f),
        onErrorContainer = lerp(ThermalCritical, Color.Black, 0.45f),
        outline = LightOutline,
        outlineVariant = lerp(LightOutline, LightBackground, 0.5f),
        scrim = Color.Black,
    )
}

/**
 * Black or white, whichever can be read on top of [background].
 *
 * The accents span cyan at 0.72 relative luminance and violet at 0.24, so a single hard-coded
 * `onPrimary` would be unreadable for half of them. The threshold is deliberately above 0.5: the eye
 * needs less contrast from dark text on a bright fill than the other way round, and cyan with white
 * text on it is the case that fails first.
 */
internal fun readableOn(background: Color): Color =
    if (background.luminance() > 0.42f) Color(0xFF07090E) else Color.White
