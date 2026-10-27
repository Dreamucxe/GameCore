package com.gamecore.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.core.view.WindowCompat
import com.gamecore.core.model.AnimationsMode
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.ThemeChoice
import com.gamecore.core.plugin.theme.PluginTheme

/**
 * Rounded, and consistently so. §27 asks for rounded cards; this decides "rounded" once rather than as
 * a different literal on each screen.
 */
val GameCoreShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// THEME_BODY

/**
 * The app's theme: three settings turned into a `MaterialTheme`, and a plugin's theme when one is active.
 *
 * [settings] is passed in rather than read from the preference store here, so this stays a pure
 * function of its input — a screen preview supplies an [AppSettings] literal and gets the real theme,
 * and there is no composable in the tree that needs Hilt to draw itself. [pluginTheme] is the second
 * input for the same reason: it arrives already resolved and already validated, so this function has no
 * plugin failure case to handle and null simply means GameCore's own theme.
 *
 * Five decisions worth stating:
 *
 *  - **Dynamic colour is opt-in and off by default.** Material You is the platform's preference and it
 *    is offered, but GameCore's own accents are chosen to stay legible against the near-black surfaces
 *    the graphs are drawn on, and a wallpaper-derived pastel primary can leave a thermal line and a CPU
 *    line the same value of grey.
 *  - **A plugin theme outranks both dynamic colour and the theme setting.** Selecting one is a specific,
 *    deliberate act on the Plugins screen, where a toggle in Settings is a standing preference; if the
 *    setting won, that selection would appear to succeed and change nothing, which §24 does not allow.
 *    The plugin also decides light or dark, because its six colours were chosen for one or the other and
 *    using them at the wrong polarity is how a dark theme ends up with black text on a black surface.
 *    The host AMOLED *setting* is skipped for the same reason — its true-black surface is the one the
 *    plugin is replacing — but a schema-2 plugin may opt its own dark base onto that true-black surface
 *    family through its `amoled` flag, which `toColorScheme` honours.
 *  - **UI scale scales the whole interface, not just the text.** The multiplier goes on `density`
 *    rather than on `fontScale`, so at 130% a stat card, its padding and its touch target all grow
 *    together. Scaling only the text would push type into layouts sized for smaller type, which is how
 *    a scale setting ends up clipping the number it was turned on to make readable. The user's own
 *    system font scale is left where it is and still applies on top. A plugin only *modulates* this —
 *    see `resolvedUiScalePercent`, which clamps the product so a plugin cannot shrink the controls past
 *    what a user can hit.
 *  - **The system bar icons follow the resolved scheme.** With edge-to-edge on, the bars are
 *    transparent and their icons are drawn over this app's background; light icons on a light scheme
 *    would be invisible. `SideEffect` rather than `LaunchedEffect` because it is a window property that
 *    must match the composition that has just been produced, not a job.
 *  - **A plugin may ask for motion; it may not take it.** From schema 3 a theme can carry a `glitch`
 *    block, and this function hands it to [GlitchLayer] — but only behind two vetoes the plugin cannot
 *    see, let alone set: the user's Animations preference and the platform animator scale. A theme that
 *    tears the screen is a style choice for the person who installed it, and an unreadable app is not
 *    something a package should be able to inflict on the person who did not.
 *  - **A plugin may ask for a material; the user dials it in.** From schema 4 a theme can carry a
 *    `glass` block. [glassScale] is where it lands, and it is not the theme's number — it is the
 *    position of the Control Center's slider, which is the whole reason the block names one of the
 *    plugin's own settings rather than carrying its intensity alone. `1f` is "as the author wrote
 *    it" and is what every caller that does not pass a value gets, so a preview of a glass theme
 *    draws the material the theme designed.
 */
@Composable
fun GameCoreTheme(
    settings: AppSettings = AppSettings(),
    pluginTheme: PluginTheme? = null,
    engineTint: Color? = null,
    glitchConditions: GlitchConditions = GlitchConditions.None,
    glassScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    // The Theme Engine's override is the one thing above a plugin theme. It is only ever non-null when the
    // user turned the override on AND the engine has a vector, so `null` here means "no opinion" and the
    // ordering below is unchanged from every release before this one.
    val engineOverride = settings.themeEngineOverridesPlugins && engineTint != null
    val effectivePluginTheme = if (engineOverride) null else pluginTheme
    // AMOLED is a dark theme, so it resolves dark like DARK does; the difference is the true-black scheme
    // chosen below, not whether the app is in dark mode.
    val dark = when {
        effectivePluginTheme != null -> effectivePluginTheme.dark
        // The Redline skin is a dark skin by construction, so it forces dark polarity ahead of the theme
        // setting. A plugin theme still outranks it (the branch above), because selecting a plugin is the
        // more specific act.
        settings.redlineSkin -> true
        else -> when (settings.theme) {
            ThemeChoice.SYSTEM -> systemDark
            ThemeChoice.DARK, ThemeChoice.AMOLED -> true
            ThemeChoice.LIGHT -> false
        }
    }
    val amoled = effectivePluginTheme == null && settings.theme == ThemeChoice.AMOLED
    // The accent colour the scheme is actually built from: the user's custom pick when it is turned on and
    // a colour has been chosen, otherwise the selected palette swatch. normalised() has already forced the
    // custom colour opaque, so `onPrimary`'s readability check is never fed a half-transparent accent.
    val accentColour = if (settings.useCustomAccent && settings.customAccentArgb != null) {
        Color(settings.customAccentArgb)
    } else {
        Color(settings.accent.argb)
    }
    val context = LocalContext.current
    val scheme = remember(dark, amoled, accentColour, settings.useDynamicColour, effectivePluginTheme, engineOverride, engineTint) {
        val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        when {
            // The engine's live colour is the accent the whole app is built from. The same scheme builders
            // the house palettes use do the contrast work, so a molten crimson still yields readable text
            // rather than a red-on-red screen.
            engineOverride && engineTint != null ->
                if (dark) darkSchemeFor(engineTint) else lightSchemeFor(engineTint)
            effectivePluginTheme != null -> effectivePluginTheme.toColorScheme()
            // Redline outranks dynamic colour and the house schemes, but not a plugin: its whole point is
            // a specific red-on-black identity, which a wallpaper-derived palette would dissolve.
            settings.redlineSkin -> redlineColorScheme()
            settings.useDynamicColour && dynamicAvailable && dark -> dynamicDarkColorScheme(context)
            settings.useDynamicColour && dynamicAvailable -> dynamicLightColorScheme(context)
            amoled -> amoledSchemeFor(accentColour)
            dark -> darkSchemeFor(accentColour)
            else -> lightSchemeFor(accentColour)
        }
    }

    // A plugin's own shapes win; then Redline's cut corners; otherwise the house rounded set.
    val shapes = remember(effectivePluginTheme, settings.redlineSkin) {
        effectivePluginTheme?.toShapes() ?: if (settings.redlineSkin) RedlineShapes else GameCoreShapes
    }

    // The stat styles in `Type.kt` are deliberately not reached by this: `StatValueStyle` is monospaced
    // because a proportional figure changes width when 9% becomes 10%, and a plugin asking for a serif
    // face has no business making a column of live numbers twitch.
    val typography = remember(effectivePluginTheme) {
        effectivePluginTheme?.let {
            GameCoreTypography.withTypography(it.toFontFamily(), it.resolvedTypeScale(), it.resolvedBaseWeight())
        } ?: GameCoreTypography
    }

    val base = LocalDensity.current
    val scalePercent = effectivePluginTheme?.resolvedUiScalePercent(settings.uiScalePercent)
        ?: settings.uiScalePercent
    val scaled = remember(base, scalePercent) {
        if (scalePercent == 100) {
            base
        } else {
            Density(
                density = base.density * scalePercent / 100f,
                fontScale = base.fontScale,
            )
        }
    }

    // Whether a theme is allowed to move at all. Two independent vetoes, and either one is enough:
    // the app's own Animations setting (Off means off, whatever the system says) and the platform
    // animator scale, which is the knob accessibility "remove animations" and Developer options
    // write. A plugin asks for motion; it never gets to argue with either.
    val animationsEnabled = settings.animations != AnimationsMode.OFF && systemAnimationScale() > 0f

    // The schema-4 material, resolved once for the whole app. Null — the case for every theme before
    // schema 4, and for a glass theme the user has dialled to nothing — costs one composition-local
    // provide and no layer anywhere.
    val glassState = rememberGlassState(effectivePluginTheme, glassScale, animationsEnabled)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        shapes = shapes,
    ) {
        CompositionLocalProvider(
            LocalDensity provides scaled,
            // The schema-4 material, or null. Published here rather than threaded through the
            // component set because the components that have to change are the app's own cards,
            // drawn a hundred call sites deep — a `PlainCard` that took a material parameter would
            // be a change to the whole app for a feature none of those call sites know about.
            LocalGlassMaterial provides glassState,
            // The motion verdict, published for the one thing under this theme that draws motion
            // without being part of its layer stack: the Glass Control Center's preview. It is
            // provided rather than recomputed there so that the preview and the app cannot disagree
            // about whether the user has asked for motion.
            LocalAnimationsEnabled provides animationsEnabled,
        ) {
            // The schema-3 motion block, when the theme carries one and the user has not asked for
            // motion off. Both halves are required: `resolvedGlitch()` already returns null for a
            // theme that asked for no motion, and `animationsEnabled` is the veto that a theme
            // cannot override. A user debugging a glitch theme they can no longer read needs an
            // off switch that does not require navigating with it on.
            GlitchLayer(
                glitch = effectivePluginTheme?.resolvedGlitch(),
                enabled = animationsEnabled,
                // The theme's own two accents are the colours a tear is drawn in, so the fracture
                // belongs to the palette rather than to a hard-coded neon that might clash with it.
                accent = scheme.primary,
                fracture = scheme.secondary,
                conditions = glitchConditions,
                content = content,
            )
        }
    }
}

/**
 * The same type scale in a different family, nudged by the plugin's type controls.
 *
 * All fifteen roles, not the eight [GameCoreTypography] overrides: the other seven are Material's own
 * defaults, and leaving those alone would render most of the app in the plugin's face and the odd
 * component in the system one. The family is always applied; [scale] and [weight] are the schema-2
 * additions — [scale] multiplies every role's size and line height together so the ramp keeps its
 * proportions, and [weight], when the plugin asked for one, flattens the whole ramp to it. A v1 plugin
 * resolves to scale 1 and a null weight, so this stays its old self: a family swap and nothing else.
 */
private fun Typography.withTypography(family: FontFamily, scale: Float, weight: FontWeight?): Typography = Typography(
    displayLarge = displayLarge.retune(family, scale, weight),
    displayMedium = displayMedium.retune(family, scale, weight),
    displaySmall = displaySmall.retune(family, scale, weight),
    headlineLarge = headlineLarge.retune(family, scale, weight),
    headlineMedium = headlineMedium.retune(family, scale, weight),
    headlineSmall = headlineSmall.retune(family, scale, weight),
    titleLarge = titleLarge.retune(family, scale, weight),
    titleMedium = titleMedium.retune(family, scale, weight),
    titleSmall = titleSmall.retune(family, scale, weight),
    bodyLarge = bodyLarge.retune(family, scale, weight),
    bodyMedium = bodyMedium.retune(family, scale, weight),
    bodySmall = bodySmall.retune(family, scale, weight),
    labelLarge = labelLarge.retune(family, scale, weight),
    labelMedium = labelMedium.retune(family, scale, weight),
    labelSmall = labelSmall.retune(family, scale, weight),
)

/**
 * One role restyled: the plugin's [family] always, its [weight] when it asked for one global weight,
 * and its [scale] on both `fontSize` and `lineHeight` so the role keeps its proportions. A size the
 * Material default left unspecified is left unspecified — multiplying `TextUnit.Unspecified` throws,
 * and an unset size means "inherit", which scaling must not quietly turn into a concrete value.
 */
private fun TextStyle.retune(family: FontFamily, scale: Float, weight: FontWeight?): TextStyle = copy(
    fontFamily = family,
    fontWeight = weight ?: fontWeight,
    fontSize = if (fontSize.isSpecified) fontSize * scale else fontSize,
    lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight,
)

/**
 * The same ramp with an optional family override and an additional text-only scale.
 *
 * Used by [PluginSectionTheme] rather than by the whole app. Unlike [withTypography] this does not
 * flatten weights: the plugin section keeps the current theme's hierarchy and only changes the face
 * and the size. A null [family] keeps each role's existing family, which is how "Theme" means
 * "leave the current theme's typography alone" rather than "switch to the platform default".
 */
internal fun Typography.withPluginSectionType(family: FontFamily?, scale: Float): Typography = Typography(
    displayLarge = displayLarge.retuneSection(family, scale),
    displayMedium = displayMedium.retuneSection(family, scale),
    displaySmall = displaySmall.retuneSection(family, scale),
    headlineLarge = headlineLarge.retuneSection(family, scale),
    headlineMedium = headlineMedium.retuneSection(family, scale),
    headlineSmall = headlineSmall.retuneSection(family, scale),
    titleLarge = titleLarge.retuneSection(family, scale),
    titleMedium = titleMedium.retuneSection(family, scale),
    titleSmall = titleSmall.retuneSection(family, scale),
    bodyLarge = bodyLarge.retuneSection(family, scale),
    bodyMedium = bodyMedium.retuneSection(family, scale),
    bodySmall = bodySmall.retuneSection(family, scale),
    labelLarge = labelLarge.retuneSection(family, scale),
    labelMedium = labelMedium.retuneSection(family, scale),
    labelSmall = labelSmall.retuneSection(family, scale),
)

private fun TextStyle.retuneSection(family: FontFamily?, scale: Float): TextStyle = copy(
    fontFamily = family ?: fontFamily,
    fontSize = if (fontSize.isSpecified) fontSize * scale else fontSize,
    lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight,
)
