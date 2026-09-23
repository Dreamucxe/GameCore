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

// The AMOLED neutrals (§3 "AMOLED black"). A true #000000 background so an OLED panel switches those
// pixels off entirely, and near-black surfaces a hair above it so a card is still separable from the page.
// The separation on AMOLED comes from the outline, not from a lighter fill — a tonal surface bright enough
// to read as "raised" on pure black would defeat the point of turning the pixels off — so the outline is
// lifted a little relative to the standard dark scheme to carry that job alone. The on-colours and the
// accent-driven roles are shared with the dark scheme; only the background/surface/outline change.
private val AmoledBackground = Color(0xFF000000)
private val AmoledSurface = Color(0xFF060809)
private val AmoledSurfaceVariant = Color(0xFF141A24)
private val AmoledOutline = Color(0xFF303A4A)

// The light neutrals. Cool-tinted rather than pure white, because the same graphs and the same stat
// cards are drawn in both and a white card with a thin outline disappears on an LCD in daylight.
private val LightBackground = Color(0xFFF6F8FC)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFE6EAF2)
private val LightOutline = Color(0xFFC3CAD8)
private val LightOnSurface = Color(0xFF101623)
private val LightOnSurfaceVariant = Color(0xFF4A5468)

/** The dark scheme for one of the palette accents. */
fun darkSchemeFor(accent: AccentChoice): ColorScheme = darkSchemeFor(Color(accent.argb))

/**
 * The dark scheme for an arbitrary accent [primary].
 *
 * Taking a [Color] rather than an [AccentChoice] is what lets the custom-accent picker (§3) build the
 * real theme from a colour that is not one of the eight swatches — the scheme is a function of the accent
 * colour, not of which enum it came from. The palette overload above just unwraps [AccentChoice.argb].
 *
 * [background], [surface], [surfaceVariant] and [outline] are parameters with the standard-dark defaults
 * so the AMOLED variant can reuse this whole body and change only those four, rather than copying every
 * accent-derived role and risking the two schemes drifting apart.
 */
fun darkSchemeFor(
    primary: Color,
    background: Color = DarkBackground,
    surface: Color = DarkSurface,
    surfaceVariant: Color = DarkSurfaceVariant,
    outline: Color = DarkOutline,
): ColorScheme = darkColorScheme(
    primary = primary,
    onPrimary = readableOn(primary),
    primaryContainer = lerp(surface, primary, 0.20f),
    onPrimaryContainer = lerp(primary, Color.White, 0.55f),
    inversePrimary = lerp(primary, Color.Black, 0.35f),
    secondary = lerp(primary, DarkOnSurfaceVariant, 0.45f),
    onSecondary = readableOn(lerp(primary, DarkOnSurfaceVariant, 0.45f)),
    secondaryContainer = lerp(surface, primary, 0.10f),
    onSecondaryContainer = DarkOnSurface,
    tertiary = ChartAlternate,
    onTertiary = readableOn(ChartAlternate),
    tertiaryContainer = lerp(surface, ChartAlternate, 0.16f),
    onTertiaryContainer = lerp(ChartAlternate, Color.White, 0.5f),
    background = background,
    onBackground = DarkOnSurface,
    surface = surface,
    onSurface = DarkOnSurface,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = primary,
    inverseSurface = DarkOnSurface,
    inverseOnSurface = background,
    error = ThermalCritical,
    onError = readableOn(ThermalCritical),
    errorContainer = lerp(surface, ThermalCritical, 0.18f),
    onErrorContainer = lerp(ThermalCritical, Color.White, 0.55f),
    outline = outline,
    outlineVariant = lerp(outline, background, 0.45f),
    scrim = Color.Black,
)

/**
 * The AMOLED dark scheme (§3): the dark scheme on a true #000000 background.
 *
 * Reuses [darkSchemeFor]'s body and swaps in the AMOLED neutrals, so an accent looks identical in dark
 * and AMOLED and only the page behind it goes fully black. The outline is the one that does more work
 * here — see the neutrals' note — so the raised feel survives without a lighter surface fill.
 */
fun amoledSchemeFor(accent: AccentChoice): ColorScheme = amoledSchemeFor(Color(accent.argb))

/** The AMOLED dark scheme for an arbitrary accent [primary] (for the custom-accent picker). */
fun amoledSchemeFor(primary: Color): ColorScheme = darkSchemeFor(
    primary = primary,
    background = AmoledBackground,
    surface = AmoledSurface,
    surfaceVariant = AmoledSurfaceVariant,
    outline = AmoledOutline,
)

// COLOR_LIGHT

/**
 * The light scheme for one accent.
 *
 * The accents are chosen to be legible on a dark plate, so several of them — cyan especially — are too
 * bright to put white text on or to draw as a thin line on white. Each one is darkened toward black
 * before it becomes `primary` here, which keeps the six choices distinguishable in light mode without
 * needing a second set of six literals that could drift out of step with the first.
 */
fun lightSchemeFor(accent: AccentChoice): ColorScheme = lightSchemeFor(Color(accent.argb))

/**
 * The light scheme for an arbitrary accent colour (for the custom-accent picker, §3).
 *
 * [raw] is darkened toward black by the same 0.28 before it becomes `primary`, so a bright custom pick
 * gets the same legibility treatment the palette accents do rather than being drawn as a pale line on
 * white. The [AccentChoice] overload above just unwraps the argb.
 */
fun lightSchemeFor(raw: Color): ColorScheme {
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
 * The accents span cyan near 0.63 relative luminance down to violet at 0.25, so a single hard-coded
 * `onPrimary` would be unreadable for half of them. The threshold is the WCAG large-text boundary, not
 * an eyeballed one: white text (luminance 1.0) clears the 3:1 minimum only while the fill's luminance
 * is at most 0.30 — `1.05 / (L + 0.05) ≥ 3` solves to `L ≤ 0.30` — so any fill brighter than that must
 * take the near-black ink, which itself clears 3:1 for every fill down to luminance ~0.11.
 *
 * The old cutoff sat at 0.42, which left the (0.30, 0.42] band — rose at 0.303 and orange at 0.400 —
 * on white text that measured 2.97:1 and 2.33:1. Dropping to 0.30 flips exactly those two to dark ink
 * (7.06:1 and 8.5:1) and changes nothing else: the clean gap below is blue at 0.278, which keeps white.
 * `SchemeContrastTest` runs every accent through every scheme so this stays true if an accent is added.
 */
internal fun readableOn(background: Color): Color =
    if (background.luminance() > 0.30f) Color(0xFF07090E) else Color.White
