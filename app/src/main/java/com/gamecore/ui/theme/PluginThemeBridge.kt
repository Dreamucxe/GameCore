package com.gamecore.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gamecore.core.common.ContrastMath
import com.gamecore.core.plugin.theme.PluginGlass
import com.gamecore.core.plugin.theme.PluginGlitch
import com.gamecore.core.plugin.theme.PluginTheme
import com.gamecore.core.plugin.theme.PluginThemeRoles
import com.gamecore.core.plugin.theme.PluginThemeTypography
import kotlin.math.roundToInt

/**
 * A validated [PluginTheme] turned into the values `MaterialTheme` actually consumes.
 *
 * A theme is the lowest-risk thing a plugin can ship and still the one that can do the most
 * visible damage, because it is the only section that repaints screens the plugin does not own.
 * [com.gamecore.core.plugin.theme.PluginThemeCodec] has already made the values *well-formed* —
 * every colour a valid ARGB int, the radius inside [PluginTheme.MIN_CORNER_DP]..
 * [PluginTheme.MAX_CORNER_DP], the scale inside [PluginTheme.MIN_UI_SCALE]..
 * [PluginTheme.MAX_UI_SCALE], the font one of the known set. What a codec cannot judge is
 * whether the *combination* is usable. That is this file's job, and these are the decisions
 * behind it:
 *
 *  1. **The house scheme is the base; the plugin's colours are copied over it.** A [ColorScheme]
 *     carries thirty-six roles and a plugin supplies six. Hand-building one from those six would
 *     leave `error`, `outline`, `surfaceVariant`, `onSurfaceVariant`, `primaryContainer` and two
 *     dozen more on Material's own defaults — which is exactly how error text turns invisible on
 *     a theme that never mentioned errors. [darkSchemeFor] and [lightSchemeFor] fill every role
 *     from one accent and `SchemeContrastTest` already proves them legible, so starting there
 *     means the worst a plugin can do is recolour the roles it asked for. The cost is that the
 *     container roles are lerped from the *house* neutrals rather than the plugin's surface, so
 *     a container tint can read slightly cool against a warm plugin surface. That is accepted on
 *     purpose: an off-tone container is a cosmetic flaw, an unpopulated role is a bug you cannot
 *     read your way out of.
 *  2. **A plugin may not make the app unreadable.** `onSurface` and `surface` a hair apart is a
 *     perfectly legal theme and a usability denial-of-service — the user cannot read the settings
 *     screen they would have to reach in order to turn the theme back off. So every
 *     plugin-supplied foreground/background pair is measured with [ContrastMath] and, below
 *     [MIN_CONTRAST_RATIO], the *foreground* is replaced while the plugin's own background
 *     stands. One role degrades and the theme still applies; rejecting a whole theme over a
 *     single pair would throw away the thing the user installed it for. Every colour is forced
 *     opaque before any of that is measured — see [opaque], without which the measurement means
 *     nothing.
 *  3. **The floor is the large-text 3:1, not body text's 4.5:1.** §11 holds the app's own palette
 *     to [ContrastMath.BODY_TEXT_MIN] because those neutrals are ours to pick. Held against
 *     someone else's design the same bar rejects themes that are deliberate — a muted dark theme
 *     lives around 3.5:1 — and because a failed pair here is silently repainted rather than
 *     reported, the stricter floor would quietly redraw half the marketplace in house colours.
 *     [ContrastMath.LARGE_TEXT_MIN] is where text stops being readable rather than merely
 *     low-contrast, and that is the only line worth enforcing on a plugin's behalf.
 *  4. **The substitution is silent and unlogged.** There is no action for the user to take, and
 *     plugin-supplied content does not reach logcat in this platform for any reason.
 *  5. **The user's own UI scale is modulated, never overridden** — see [resolvedUiScalePercent].
 *     §32's no-dead-taps rule is why the product is clamped at all.
 *  6. **Pure functions: no `@Composable`, no `LocalContext`, no Android.** `Theme.kt` makes a
 *     point of being a pure function of its inputs, so a preview can hand it a literal and get
 *     the real theme. A bridge that read a composition local or opened a font file would take
 *     that away, and would also put this file beyond the reach of the JVM tests that pin it.
 */

/**
 * The contrast a plugin-supplied foreground/background pair must clear to be used as given.
 *
 * Deliberately the large-text minimum — see decision 3 above. It is an alias rather than a
 * literal `3.0` so that the one place WCAG's numbers live in this codebase stays the one place.
 */
const val MIN_CONTRAST_RATIO = ContrastMath.LARGE_TEXT_MIN

/**
 * Floor for the resolved density percentage.
 *
 * The user's own setting already bottoms out at `AppSettings.MIN_UI_SCALE` (85), and a plugin may
 * ask for [PluginTheme.MIN_UI_SCALE] (0.8) on top of it, so the unclamped product reaches 68 —
 * controls a third smaller than the layouts were measured at. 80 is one notch below the host's
 * own floor: close enough that nothing shrinks past a size the user has already demonstrated
 * they can hit at their own setting, loose enough that a plugin which wants a denser look gets it.
 */
const val MIN_RESOLVED_SCALE_PERCENT = 80

/**
 * Ceiling for the resolved density percentage.
 *
 * The opposite failure, and the less obvious one. `Theme.kt` applies the scale to `density`
 * rather than `fontScale` so padding and type grow together; an unclamped product of the host's
 * 130 and a plugin's [PluginTheme.MAX_UI_SCALE] reaches 182, at which point a row that was
 * growing to be readable starts clipping instead — hiding content in the name of enlarging it,
 * with nothing on screen to say a plugin caused it.
 */
const val MAX_RESOLVED_SCALE_PERCENT = 160

/**
 * The house `medium` corner, in dp, and the reference the plugin's radius is measured against.
 *
 * It mirrors `GameCoreShapes.medium` rather than reading it back, because a `CornerBasedShape`
 * stores a `CornerSize` whose dp value can only be recovered with a `Density` — and taking a
 * `Density` would make [toShapes] an Android-dependent function for no gain. The five house
 * literals are mirrored below for the same reason; `PluginThemeBridgeTest` pins them to the real
 * `GameCoreShapes` so the copy cannot drift silently.
 */
const val HOUSE_CORNER_REFERENCE_DP = 16

// The remaining four steps of GameCoreShapes, in dp. Material3 leans on these being *different*:
// a chip and a bottom sheet flattened to one radius lose the hierarchy that tells them apart, so
// the plugin's radius scales the whole ladder rather than replacing every rung with one number.
private const val HOUSE_EXTRA_SMALL_DP = 6
private const val HOUSE_SMALL_DP = 10
private const val HOUSE_LARGE_DP = 22
private const val HOUSE_EXTRA_LARGE_DP = 28

/**
 * The plugin's theme as a full [ColorScheme].
 *
 * Starts from the house scheme for the plugin's declared mode, built from the plugin's `accent`
 * so that every role the plugin does *not* supply — the containers, the error family, the
 * outlines, the muted ink — is still derived from the plugin's own hue instead of sitting on a
 * Material default. Then the six supplied colours are copied over the top, each foreground
 * passing [legibleInk] first.
 *
 * `onSurface` does double duty: the plugin declares one ink and Material3 draws body copy on both
 * `surface` and `background`, so it is applied to `onSurface` and `onBackground` and guarded
 * separately against each backdrop. A plugin whose surface and background sit at different
 * luminances can therefore end up with the supplied ink on one and the house ink on the other,
 * which is the right outcome: the roles are independent in Material and the alternative is one of
 * the two being unreadable.
 *
 * `primary` is passed through unguarded. It is a fill, not text — the thing that has to be
 * readable is the label on it, which is `onPrimary`, and that is checked.
 *
 * Every one of the six is forced opaque by [opaque] first, `accent` included: the house scheme
 * lerps `accent` into a dozen container roles, so a translucent accent would thin the whole
 * scheme and not just the one role that named it.
 *
 * From schema 2 two more things can happen, both additive. A theme that declared `amoled` and
 * `dark` bases on [amoledSchemeFor] — a true-black surface family — instead of [darkSchemeFor];
 * every other build of the scheme is untouched. And a theme that supplied [PluginTheme.roles] has
 * them copied over the base by [applyRoles], after the v1 six. A theme that did neither — every v1
 * theme, and every v2 theme that set only typography — takes the exact `base` built below, so its
 * scheme is byte-for-byte what it was before these fields existed.
 */
fun PluginTheme.toColorScheme(): ColorScheme {
    val accentArgb = opaque(accent)
    val primaryArgb = opaque(primary)
    val backgroundArgb = opaque(background)
    val surfaceArgb = opaque(surface)
    val inkArgb = opaque(onSurface)
    val house = when {
        dark && amoled -> amoledSchemeFor(Color(accentArgb))
        dark -> darkSchemeFor(Color(accentArgb))
        else -> lightSchemeFor(Color(accentArgb))
    }
    val base = house.copy(
        primary = Color(primaryArgb),
        onPrimary = legibleInk(supplied = opaque(onPrimary), backdrop = primaryArgb, houseInk = house.onPrimary),
        background = Color(backgroundArgb),
        onBackground = legibleInk(supplied = inkArgb, backdrop = backgroundArgb, houseInk = house.onBackground),
        surface = Color(surfaceArgb),
        onSurface = legibleInk(supplied = inkArgb, backdrop = surfaceArgb, houseInk = house.onSurface),
    )
    val suppliedRoles = roles
    return if (suppliedRoles == null) base else base.applyRoles(suppliedRoles)
}

/**
 * The schema-2 semantic roles copied over the base scheme, each in the manner that role takes.
 *
 * Fills — [PluginThemeRoles.secondary], [PluginThemeRoles.tertiary], [PluginThemeRoles.surfaceVariant],
 * [PluginThemeRoles.outline], [PluginThemeRoles.error] — are applied as given (forced opaque): they
 * are backgrounds and accents, judged legible by whatever ink sits on them, and the inks that sit on
 * them are guarded here too. The two inks — [PluginThemeRoles.onSurfaceVariant] and
 * [PluginThemeRoles.onError] — pass through [legibleInk] against the fill they sit on, the same guard
 * the v1 inks get, so a role a theme set to something illegible degrades to a readable value rather
 * than being painted blind. A role the theme left null keeps the base scheme's own value — so a v2
 * theme that names three roles inherits the other thirty-odd exactly as the v1 six already do.
 *
 * The backdrop for each guarded ink is the role's own fill when the theme supplied one, and the base
 * scheme's existing fill otherwise, so `onSurfaceVariant` is measured against whatever
 * `surfaceVariant` actually ends up being.
 */
private fun ColorScheme.applyRoles(roles: PluginThemeRoles): ColorScheme {
    val surfaceVariantArgb = roles.surfaceVariant?.let { opaque(it) } ?: surfaceVariant.toArgb()
    val errorArgb = roles.error?.let { opaque(it) } ?: error.toArgb()
    return copy(
        secondary = roles.secondary?.let { Color(opaque(it)) } ?: secondary,
        tertiary = roles.tertiary?.let { Color(opaque(it)) } ?: tertiary,
        surfaceVariant = roles.surfaceVariant?.let { Color(opaque(it)) } ?: surfaceVariant,
        outline = roles.outline?.let { Color(opaque(it)) } ?: outline,
        error = roles.error?.let { Color(opaque(it)) } ?: error,
        onSurfaceVariant = roles.onSurfaceVariant?.let {
            legibleInk(supplied = opaque(it), backdrop = surfaceVariantArgb, houseInk = onSurfaceVariant)
        } ?: onSurfaceVariant,
        onError = roles.onError?.let {
            legibleInk(supplied = opaque(it), backdrop = errorArgb, houseInk = onError)
        } ?: onError,
    )
}

/**
 * The schema-2 type scale to apply, clamped — unit when the theme named no typography.
 *
 * The clamp is the bridge's half of the belt-and-braces the corner radius gets: the codec has
 * already held [PluginThemeTypography.scale] inside its bounds, and this holds it there again so a
 * value that reached the bridge another way still cannot rebuild the ramp. A theme with no
 * typography block leaves the ramp at [PluginThemeTypography.DEFAULT_SCALE].
 */
fun PluginTheme.resolvedTypeScale(): Float =
    (typography?.scale ?: PluginThemeTypography.DEFAULT_SCALE)
        .coerceIn(PluginThemeTypography.MIN_SCALE, PluginThemeTypography.MAX_SCALE)

/**
 * The one global [FontWeight] the theme asked for, or null to keep each Material role's own weight.
 *
 * Null is the common case and the only v1 case: the Material ramp draws display and label text at
 * different weights on purpose, and a theme that only wanted larger text has no reason to flatten
 * that. A non-null weight is the theme deliberately asking for one weight across the whole app,
 * which `Theme.kt` then applies to every role.
 */
fun PluginTheme.resolvedBaseWeight(): FontWeight? = when (typography?.weight) {
    null -> null
    PluginThemeTypography.Weight.LIGHT -> FontWeight.Light
    PluginThemeTypography.Weight.NORMAL -> FontWeight.Normal
    PluginThemeTypography.Weight.MEDIUM -> FontWeight.Medium
    PluginThemeTypography.Weight.BOLD -> FontWeight.Bold
}

/**
 * The schema-3 motion block this theme asked for, clamped — or null when it asked for none.
 *
 * Null covers two different cases on purpose: a theme that carries no `effects` block (every v1
 * and v2 theme, and every v3 theme that only took the palette), and one whose `intensity` is
 * zero. Both mean the same thing to the renderer — nothing to draw, so do not even record a
 * layer — and neither is an error the user should ever be told about.
 *
 * The clamp is repeated here for the same reason the type scale's is: the codec has already
 * bounded every field, and this guarantees the renderer's inputs are bounded even if a
 * `PluginTheme` was built by a path that skipped the codec. It is a pure function, so it stays
 * inside this file's no-Android rule.
 */
fun PluginTheme.resolvedGlitch(): PluginGlitch? =
    glitch?.clamped()?.takeIf { it.isVisible }

/**
 * The plugin's corner radius as a full [Shapes] ladder.
 *
 * The radius scales [HOUSE_CORNER_REFERENCE_DP]'s ladder proportionally rather than flattening
 * all five steps to one value, so a theme asking for "rounder" stays a design and does not become
 * a loss of shape hierarchy. Each scaled step is clamped into [PluginTheme.MIN_CORNER_DP]..
 * [PluginTheme.MAX_CORNER_DP] *after* scaling, which is not the same as trusting the codec's clamp
 * on the input: at the maximum radius the factor is 3.0 and `extraLarge` lands at 84 dp, far past
 * the point where a corner exceeds half a component's short side and the shape is drawn as a pill
 * whether or not that was the intent.
 *
 * At the reference radius this returns `GameCoreShapes` itself, not an equal copy. That is the
 * common case — the codec defaults `cornerRadiusDp` to 16 — so it allocates nothing and leaves
 * `MaterialTheme` comparing an identical instance rather than five fresh shapes.
 */
fun PluginTheme.toShapes(): Shapes {
    if (cornerRadiusDp == HOUSE_CORNER_REFERENCE_DP) return GameCoreShapes
    val factor = cornerRadiusDp.toDouble() / HOUSE_CORNER_REFERENCE_DP
    return Shapes(
        extraSmall = RoundedCornerShape(scaledCornerDp(HOUSE_EXTRA_SMALL_DP, factor).dp),
        small = RoundedCornerShape(scaledCornerDp(HOUSE_SMALL_DP, factor).dp),
        medium = RoundedCornerShape(scaledCornerDp(HOUSE_CORNER_REFERENCE_DP, factor).dp),
        large = RoundedCornerShape(scaledCornerDp(HOUSE_LARGE_DP, factor).dp),
        extraLarge = RoundedCornerShape(scaledCornerDp(HOUSE_EXTRA_LARGE_DP, factor).dp),
    )
}

/**
 * The density percentage to apply when this theme is active, given the user's own
 * [hostScalePercent].
 *
 * The plugin's [PluginTheme.uiScale] **multiplies** the user's setting; it never replaces it. A
 * plugin that wants a compact look gets a compact look relative to wherever the user put the
 * slider, and a user who turned the scale up because they could not read the app keeps that gain
 * no matter which theme is installed. Replacing the value instead would make an accessibility
 * setting a function of which plugin happens to be enabled, which is not a setting at all.
 *
 * The product is then clamped into [MIN_RESOLVED_SCALE_PERCENT]..[MAX_RESOLVED_SCALE_PERCENT].
 * Both ends are reachable from legal inputs — 85 x 0.8 = 68 and 130 x 1.4 = 182 — so neither
 * bound is decoration. The floor is §32's: a plugin cannot shrink a control past the size the
 * user has already proved they can hit.
 */
fun PluginTheme.resolvedUiScalePercent(hostScalePercent: Int): Int =
    (hostScalePercent * uiScale).roundToInt()
        .coerceIn(MIN_RESOLVED_SCALE_PERCENT, MAX_RESOLVED_SCALE_PERCENT)

/**
 * The plugin's font choice as a Compose family.
 *
 * **A plugin still cannot ship a font file.** The package format has no font entry type —
 * [com.gamecore.core.plugin.PluginPackageLoader] admits `manifest.json`, the declared section
 * files, `previews/` and `assets/` images, and refuses everything else — so a theme names one of
 * the families GameCore itself bundles or a platform family. Nine faces are bundled:
 * [PluginTheme.Font.MINECRAFT] — a pixel font inspired by Minecraft, not the proprietary Minecraft
 * typeface — and the eight open-source families the Liquid Glass font themes name. The theme card
 * names the family it actually gets.
 *
 * Two mappings are worth stating. [PluginTheme.Font.SYSTEM] resolves to [FontFamily.Default]
 * rather than [FontFamily.SansSerif]: `Default` is whatever the device's own default face is,
 * which on most OEM skins is not the AOSP sans, and a theme asking for "system" is asking to be
 * left alone. [PluginTheme.Font.ROUNDED] resolves to [FontFamily.SansSerif] — the nearest family
 * that exists — because there is no rounded face to give it. Naming one anyway would resolve to
 * the default on every device while claiming otherwise, which is the overstatement §24 forbids;
 * the plugin screen describes the font by the family it actually gets.
 */
fun PluginTheme.toFontFamily(): FontFamily = GameCoreFontFamilies.of(font)

/**
 * The ink to draw on [backdrop]: the plugin's [supplied] colour when it is legible there, and a
 * substitute when it is not.
 *
 * The fallback has two steps, in this order, and the order is the point:
 *
 *  1. [houseInk] — the value the house scheme had chosen for this same role. It is the ink
 *     `SchemeContrastTest` already proves legible on the house neutrals, and because a plugin's
 *     surfaces are usually in the same family as the mode it declared, it is legible on the
 *     plugin's backdrop too. Using it keeps a degraded theme looking like GameCore instead of
 *     like a failure.
 *  2. [readableOn] — black or white off the backdrop's own luminance, for the plugin that
 *     declared `dark` and then supplied a pale surface, where the house ink is no better than the
 *     plugin's. This step cannot fail, which is why it is last: white clears the 3:1 bar for any
 *     fill up to luminance 0.30 and the near-black ink clears it for everything above, so the
 *     function always returns something readable rather than a best effort.
 *
 * Both colours are compared as ARGB ints through [ContrastMath], the same arithmetic §11's
 * sign-off rests on — the ratios are defined on sRGB 8-bit channels and a Compose `Color` holds
 * linear ones, so measuring in `Color` space would give numbers that do not match any contrast
 * checker. [supplied] and [backdrop] are expected opaque, which is [ContrastMath]'s own stated
 * precondition; [houseInk] is opaque by construction and forced anyway, so a later change to the
 * house palette cannot quietly reintroduce an alpha this function would then ignore.
 */
private fun legibleInk(supplied: Int, backdrop: Int, houseInk: Color): Color = when {
    ContrastMath.contrastRatio(supplied, backdrop) >= MIN_CONTRAST_RATIO -> Color(supplied)
    ContrastMath.contrastRatio(opaque(houseInk.toArgb()), backdrop) >= MIN_CONTRAST_RATIO -> houseInk
    else -> readableOn(Color(backdrop))
}

/**
 * [argb] with its alpha channel forced to full.
 *
 * The codec accepts `#AARRGGBB` and keeps the alpha it is given — deliberately, and pinned by
 * `PluginThemeCodecTest` — so a theme may arrive holding translucent colours. Nothing downstream
 * can use one. [ContrastMath] reads only the RGB channels and says so in its own contract, so a
 * ratio measured against `#00000000` is the ratio against opaque black and bears no relation to
 * what gets painted; `MaterialTheme` treats `surface`, `background` and `primary` as opaque fills
 * that its elevation overlays and scrims are composited onto. The two together are the hole this
 * closes: a theme supplying a transparent `surface` and a transparent `onSurface` would measure
 * 21:1, sail through [legibleInk], and render every screen in the app as invisible ink on
 * nothing — the usability denial-of-service of decision 2, reached by going around the guard
 * instead of through it.
 *
 * Flattening to opaque rather than rejecting the theme is the same bargain decision 2 strikes
 * everywhere else: the plugin's hue survives, only its transparency is dropped, and the user
 * keeps the theme they installed. It is also why this is the bridge's job and not the codec's —
 * an alpha is well-formed, which is all a codec judges, and unusable, which is all this file does.
 */
private fun opaque(argb: Int): Int = argb or (0xFF shl 24)

/**
 * One house corner step scaled by [factor] and clamped back into the format's bounds.
 *
 * Rounded to whole dp rather than kept fractional: the ladder's steps are whole numbers so the
 * scaled ones should be too, and a half-dp corner is a value no one can see and every screenshot
 * diff can.
 */
private fun scaledCornerDp(houseDp: Int, factor: Double): Int =
    (houseDp * factor).roundToInt().coerceIn(PluginTheme.MIN_CORNER_DP, PluginTheme.MAX_CORNER_DP)
