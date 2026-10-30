package com.gamecore.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gamecore.core.common.ContrastMath
import com.gamecore.core.model.PluginSectionFont
import com.gamecore.core.plugin.theme.PluginTheme
import com.gamecore.core.plugin.theme.PluginThemeRoles
import com.gamecore.core.plugin.theme.PluginThemeTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plugin theme bridge, held to the two promises `Theme.kt` makes about it.
 *
 * `Theme.kt` states that a plugin theme outranks the theme setting and that a plugin only *modulates*
 * the user's UI scale. Both are testable facts about a pure function, and both are easy to break by
 * accident: a bridge that copied the plugin's six colours straight onto a scheme would satisfy every
 * "the theme applied" check anyone would think to write, while leaving the twenty-eight roles the
 * plugin never mentioned on Material's defaults — which is how the error text on a plugin theme ends up
 * invisible. So the roles the plugin does *not* supply are asserted here as loudly as the ones it does.
 *
 * Four things this file pins, and why each of them is a real risk rather than a formality:
 *
 *  - **The legibility guard, in both directions.** A theme whose ink sits a hair off its own surface is
 *    legal input — the codec has no opinion about a colour *pair* — and it is a usability denial of
 *    service, because the screen the user needs in order to switch the theme off is the screen they can
 *    no longer read. The guard must therefore fire on that theme, and must *not* fire on a theme that is
 *    merely designed at a lower contrast than the house palette; a bridge that substituted too eagerly
 *    would quietly redraw half a marketplace in house colours. One test for each direction keeps the
 *    guard honest, and the "must not fire" case is the one a future widening of the floor would break.
 *  - **Each pair is measured against its own backdrop.** `onSurface` is the ink for both `surface` and
 *    `background`, and Material treats those as two independent roles; a plugin is free to set a dark
 *    surface under a pale page. The split-backdrop theme below is that shape, and it is the case a
 *    guard that computed one verdict and reused it for both backdrops would fail.
 *  - **Nested roles are filled from the house scheme, not from a default.** Asserting that `error`
 *    equals the house `error` is what stops someone "simplifying" the base scheme construction to
 *    `darkColorScheme()` later, which compiles and looks fine on a well-behaved theme.
 *  - **The scale is a product, and both of its clamps are reachable.** Every bound is checked with an
 *    input a real user can produce — the host's own floor of 85 against a plugin's 0.8, and the host's
 *    ceiling of 130 against the plugin's 1.4 — because a clamp that no legal input reaches is a clamp
 *    that is not protecting anything.
 *
 * That all of this runs on the JVM at all is part of the check, the same way it is in
 * [SchemeContrastTest]: the bridge touches nothing but `Color`, `Shapes` and `FontFamily`, and if it
 * ever reached for a `Context` or a font resource this file would be the first to fail. The contrast
 * figures are the real ones — `ContrastMath` is fed `toArgb()` conversions off the Compose colours, so
 * the numbers here are the same sRGB numbers any contrast checker would report and not the linear-light
 * values a `Color` carries internally.
 */
class PluginThemeBridgeTest {

    // ------------------------------------------------------------------------------ fixtures

    /**
     * A dark theme with six supplied colours, none of which is anywhere near another.
     *
     * The defaults are deliberately legible so that each test's contrast behaviour is decided by the one
     * field it overrides. `primary` and `accent` are the same colour, which is the ordinary case: a theme
     * names one accent and Material's `primary` is where it lands, so the house base scheme is built from
     * the same hue the plugin is about to copy over the top.
     */
    private fun theme(
        name: String = "Night",
        dark: Boolean = true,
        primary: Int = 0xFF3A7BFD.toInt(),
        onPrimary: Int = 0xFFFFFFFF.toInt(),
        background: Int = 0xFF0A0A0A.toInt(),
        surface: Int = 0xFF101010.toInt(),
        onSurface: Int = 0xFFFFFFFF.toInt(),
        accent: Int = 0xFF3A7BFD.toInt(),
        cornerRadiusDp: Int = HOUSE_CORNER_REFERENCE_DP,
        font: PluginTheme.Font = PluginTheme.Font.SYSTEM,
        uiScale: Float = 1.0f,
        amoled: Boolean = false,
        roles: PluginThemeRoles? = null,
        typography: PluginThemeTypography? = null,
    ) = PluginTheme(
        name = name,
        dark = dark,
        primary = primary,
        onPrimary = onPrimary,
        background = background,
        surface = surface,
        onSurface = onSurface,
        accent = accent,
        cornerRadiusDp = cornerRadiusDp,
        font = font,
        uiScale = uiScale,
        amoled = amoled,
        roles = roles,
        typography = typography,
    )

    /** The house scheme a plugin theme bases on, built the same way the bridge builds it. */
    private fun houseFor(t: PluginTheme) =
        if (t.dark) darkSchemeFor(Color(t.accent)) else lightSchemeFor(Color(t.accent))

    private fun ratio(fg: Color, bg: Color): Double =
        ContrastMath.contrastRatio(fg.toArgb(), bg.toArgb())

    private fun assertOpaque(c: Color, what: String) {
        assertNotEquals("$what was left on an empty value", 0, c.toArgb() ushr 24)
    }

    // -------------------------------------------------------------------- the legibility guard

    @Test
    fun `an unreadable onSurface is replaced by the house ink`() {
        // 0x151515 on 0x101010 measures 1.04:1 — a legal theme, and an unreadable one.
        val t = theme(surface = 0xFF101010.toInt(), onSurface = 0xFF151515.toInt())
        val house = houseFor(t)
        assertTrue(
            "fixture: the supplied pair must start below the floor",
            ratio(Color(t.onSurface), Color(t.surface)) < MIN_CONTRAST_RATIO,
        )

        val scheme = t.toColorScheme()

        assertEquals(house.onSurface, scheme.onSurface)
        assertNotEquals(Color(t.onSurface), scheme.onSurface)
        assertTrue(
            "the substituted ink must actually clear the floor",
            ratio(scheme.onSurface, scheme.surface) >= MIN_CONTRAST_RATIO,
        )
    }

    @Test
    fun `a legible onSurface is used as given`() {
        val t = theme(surface = 0xFF101010.toInt(), onSurface = 0xFFFFFFFF.toInt())
        assertTrue(
            "fixture: the supplied pair must start above the floor",
            ratio(Color(t.onSurface), Color(t.surface)) >= MIN_CONTRAST_RATIO,
        )

        val scheme = t.toColorScheme()

        assertEquals(Color(t.onSurface), scheme.onSurface)
    }

    @Test
    fun `each foreground is measured against its own backdrop`() {
        // A dark surface under a pale page, with white ink. White is legible on the surface and not on
        // the background, so the two roles must not get the same verdict.
        val t = theme(
            surface = 0xFF101010.toInt(),
            background = 0xFFF2F2F2.toInt(),
            onSurface = 0xFFFFFFFF.toInt(),
        )
        val scheme = t.toColorScheme()

        assertEquals(Color(t.onSurface), scheme.onSurface)
        assertNotEquals(Color(t.onSurface), scheme.onBackground)
        assertTrue(
            "the substituted onBackground must clear the floor on the plugin's own page",
            ratio(scheme.onBackground, scheme.background) >= MIN_CONTRAST_RATIO,
        )
    }

    @Test
    fun `an unreadable onPrimary is replaced rather than the theme being rejected`() {
        val t = theme(primary = 0xFF3A7BFD.toInt(), onPrimary = 0xFF4080FF.toInt())
        assertTrue(
            "fixture: the supplied pair must start below the floor",
            ratio(Color(t.onPrimary), Color(t.primary)) < MIN_CONTRAST_RATIO,
        )

        val scheme = t.toColorScheme()

        // The theme still applies: the plugin's primary stands even though its ink could not.
        assertEquals(Color(t.primary), scheme.primary)
        assertNotEquals(Color(t.onPrimary), scheme.onPrimary)
        assertTrue(
            "the substituted label colour must clear the floor",
            ratio(scheme.onPrimary, scheme.primary) >= MIN_CONTRAST_RATIO,
        )
    }

    @Test
    fun `the substitution falls through to a readable ink when the house ink is no better`() {
        // A theme that declares dark and then ships a pale surface. The house dark ink is near-white
        // and no better there than the plugin's own, so the last step has to carry the case.
        val t = theme(surface = 0xFFF2F2F2.toInt(), onSurface = 0xFFEFEFEF.toInt())
        val house = houseFor(t)
        assertTrue(
            "fixture: the house ink must also fail on this backdrop",
            ratio(house.onSurface, Color(t.surface)) < MIN_CONTRAST_RATIO,
        )

        val scheme = t.toColorScheme()

        assertEquals(readableOn(Color(t.surface)), scheme.onSurface)
        assertTrue(
            "the fallback must clear the floor",
            ratio(scheme.onSurface, scheme.surface) >= MIN_CONTRAST_RATIO,
        )
    }

    // ------------------------------------------------------------- the roles the plugin never named

    @Test
    fun `a legible theme passes its primary through untouched`() {
        val t = theme(primary = 0xFF3A7BFD.toInt())

        assertEquals(Color(t.primary), t.toColorScheme().primary)
    }

    @Test
    fun `every role the plugin does not supply comes from the house scheme`() {
        val t = theme()
        val house = houseFor(t)
        val scheme = t.toColorScheme()

        // The ten roles a plugin has no way to name. Equality with the house scheme is the assertion
        // that matters — it is what a switch to `darkColorScheme()` as the base would break — and the
        // opacity check is what catches a role left on `Color.Unspecified` or fully transparent.
        assertEquals(house.error, scheme.error)
        assertEquals(house.onError, scheme.onError)
        assertEquals(house.errorContainer, scheme.errorContainer)
        assertEquals(house.outline, scheme.outline)
        assertEquals(house.outlineVariant, scheme.outlineVariant)
        assertEquals(house.surfaceVariant, scheme.surfaceVariant)
        assertEquals(house.onSurfaceVariant, scheme.onSurfaceVariant)
        assertEquals(house.primaryContainer, scheme.primaryContainer)
        assertEquals(house.scrim, scheme.scrim)
        assertEquals(house.surfaceTint, scheme.surfaceTint)

        assertOpaque(scheme.error, "error")
        assertOpaque(scheme.outline, "outline")
        assertOpaque(scheme.surfaceVariant, "surfaceVariant")
        assertOpaque(scheme.onSurfaceVariant, "onSurfaceVariant")
        assertOpaque(scheme.primaryContainer, "primaryContainer")
    }

    @Test
    fun `the base scheme follows the plugin's declared polarity`() {
        val dark = theme(dark = true)
        val light = theme(dark = false)

        assertEquals(darkSchemeFor(Color(dark.accent)).surfaceVariant, dark.toColorScheme().surfaceVariant)
        assertEquals(lightSchemeFor(Color(light.accent)).surfaceVariant, light.toColorScheme().surfaceVariant)
        assertNotEquals(dark.toColorScheme().surfaceVariant, light.toColorScheme().surfaceVariant)
    }

    // ------------------------------------------------------------------------------- the shapes

    @Test
    fun `the house radius returns the house shapes instance itself`() {
        // The common case: the codec defaults the radius to the reference, so this path allocates
        // nothing and `MaterialTheme` is handed the very instance it would have used anyway.
        assertSame(GameCoreShapes, theme(cornerRadiusDp = HOUSE_CORNER_REFERENCE_DP).toShapes())
    }

    @Test
    fun `the house ladder is the one the reference radius mirrors`() {
        // Pins `HOUSE_CORNER_REFERENCE_DP` and the four private house steps to the real `GameCoreShapes`,
        // so a change to the theme file cannot leave the bridge scaling a ladder that no longer exists.
        assertEquals(HOUSE_CORNER_REFERENCE_DP, 16)
        assertEquals(RoundedCornerShape(16.dp), GameCoreShapes.medium)
        assertEquals(RoundedCornerShape(6.dp), GameCoreShapes.extraSmall)
        assertEquals(RoundedCornerShape(10.dp), GameCoreShapes.small)
        assertEquals(RoundedCornerShape(22.dp), GameCoreShapes.large)
        assertEquals(RoundedCornerShape(28.dp), GameCoreShapes.extraLarge)
    }

    @Test
    fun `the radius scales the whole ladder in proportion`() {
        val shapes = theme(cornerRadiusDp = 8).toShapes()

        assertEquals(RoundedCornerShape(3.dp), shapes.extraSmall)
        assertEquals(RoundedCornerShape(5.dp), shapes.small)
        assertEquals(RoundedCornerShape(8.dp), shapes.medium)
        assertEquals(RoundedCornerShape(11.dp), shapes.large)
        assertEquals(RoundedCornerShape(14.dp), shapes.extraLarge)
    }

    @Test
    fun `a square theme squares every step without going below the floor`() {
        val shapes = theme(cornerRadiusDp = PluginTheme.MIN_CORNER_DP).toShapes()

        assertEquals(RoundedCornerShape(0.dp), shapes.extraSmall)
        assertEquals(RoundedCornerShape(0.dp), shapes.medium)
        assertEquals(RoundedCornerShape(0.dp), shapes.extraLarge)
    }

    @Test
    fun `scaled corners clamp at the format maximum`() {
        // At the legal maximum the factor is 3.0 and the two top steps scale to 66dp and 84dp — past the
        // point where a corner exceeds half a component's short side and every shape draws as a pill.
        val shapes = theme(cornerRadiusDp = PluginTheme.MAX_CORNER_DP).toShapes()

        assertEquals(RoundedCornerShape(18.dp), shapes.extraSmall)
        assertEquals(RoundedCornerShape(30.dp), shapes.small)
        assertEquals(RoundedCornerShape(PluginTheme.MAX_CORNER_DP.dp), shapes.medium)
        assertEquals(RoundedCornerShape(PluginTheme.MAX_CORNER_DP.dp), shapes.large)
        assertEquals(RoundedCornerShape(PluginTheme.MAX_CORNER_DP.dp), shapes.extraLarge)
        // Only the three lower rungs can be asserted distinct here. At the maximum the two top steps
        // both scale past 48dp and clamp onto it, so `large` and `extraLarge` are *meant* to collapse
        // onto `medium` — the clamp is the behaviour under test, not a defect in it.
        assertDistinctSteps(shapes)
    }

    /** The rungs a theme may not collapse at any radius: a chip and a sheet drawn at one radius lose the hierarchy. */
    private fun assertDistinctSteps(shapes: Shapes) {
        assertNotEquals(shapes.extraSmall, shapes.small)
        assertNotEquals(shapes.small, shapes.medium)
    }

    // ------------------------------------------------------------------------------ the ui scale

    @Test
    fun `a plugin at unit scale leaves the user's own setting alone`() {
        // The identity, at both ends of the host's range and in the middle.
        assertEquals(85, theme(uiScale = 1.0f).resolvedUiScalePercent(85))
        assertEquals(100, theme(uiScale = 1.0f).resolvedUiScalePercent(100))
        assertEquals(130, theme(uiScale = 1.0f).resolvedUiScalePercent(130))
    }

    @Test
    fun `the plugin's scale multiplies the user's setting rather than replacing it`() {
        // The same plugin scale against two host settings: the plugin does not pin the result to a
        // value of its own, it moves wherever the user already was.
        assertEquals(120, theme(uiScale = 1.2f).resolvedUiScalePercent(100))
        assertEquals(108, theme(uiScale = 0.9f).resolvedUiScalePercent(120))
    }

    @Test
    fun `the resolved scale is floored so a plugin cannot shrink the controls past a hit target`() {
        // The host's own floor of 85 against the format's 0.8 asks for 68; §32's rule is that a plugin
        // cannot make a control smaller than the user has already proved they can hit.
        assertEquals(MIN_RESOLVED_SCALE_PERCENT, theme(uiScale = PluginTheme.MIN_UI_SCALE).resolvedUiScalePercent(85))
        assertEquals(80, MIN_RESOLVED_SCALE_PERCENT)
    }

    @Test
    fun `the resolved scale is capped so a plugin cannot clip the content it enlarged`() {
        // 130 x 1.4 is 182, where a row that was growing to be readable starts clipping instead.
        assertEquals(MAX_RESOLVED_SCALE_PERCENT, theme(uiScale = PluginTheme.MAX_UI_SCALE).resolvedUiScalePercent(130))
        assertEquals(160, MAX_RESOLVED_SCALE_PERCENT)
    }

    // -------------------------------------------------------------------------------- the fonts

    @Test
    fun `each bundled font maps to a family of its own`() {
        val families = PluginTheme.Font.entries.map { theme(font = it).toFontFamily() }

        // Every entry and every distinct family: a mapping that sent two of them to the same family
        // would make one of the choices selectable and indistinguishable from another.
        assertEquals(PluginTheme.Font.entries.size, families.size)
        assertEquals(families.size, families.distinct().size)
    }

    @Test
    fun `system is left alone and rounded names the family it actually gets`() {
        // `Default` is the device's own face, which on most OEM skins is not the AOSP sans — a theme
        // asking for "system" is asking to be left alone. There is no rounded family to hand out, so
        // ROUNDED resolves to the nearest one that exists rather than claiming a face it does not have.
        assertEquals(FontFamily.Default, theme(font = PluginTheme.Font.SYSTEM).toFontFamily())
        assertEquals(FontFamily.SansSerif, theme(font = PluginTheme.Font.ROUNDED).toFontFamily())
        assertEquals(FontFamily.Monospace, theme(font = PluginTheme.Font.MONO).toFontFamily())
        assertEquals(FontFamily.Serif, theme(font = PluginTheme.Font.SERIF).toFontFamily())
        assertEquals(FontFamily.Cursive, theme(font = PluginTheme.Font.CURSIVE).toFontFamily())
        assertEquals(GameCoreFontFamilies.minecraft, theme(font = PluginTheme.Font.MINECRAFT).toFontFamily())
    }

    @Test
    fun `the bundled Liquid Glass font themes map to their own bundled faces`() {
        val expected = mapOf(
            PluginTheme.Font.CINZEL to GameCoreFontFamilies.cinzel,
            PluginTheme.Font.COMIC_NEUE to GameCoreFontFamilies.comicNeue,
            PluginTheme.Font.INTER to GameCoreFontFamilies.inter,
            PluginTheme.Font.JETBRAINS_MONO to GameCoreFontFamilies.jetbrainsMono,
            PluginTheme.Font.ORBITRON to GameCoreFontFamilies.orbitron,
            PluginTheme.Font.PRESS_START_2P to GameCoreFontFamilies.pressStart2P,
            PluginTheme.Font.RUBIK to GameCoreFontFamilies.rubik,
            PluginTheme.Font.VT323 to GameCoreFontFamilies.vt323,
        )
        for ((font, family) in expected) {
            assertEquals(font.id, family, theme(font = font).toFontFamily())
        }
    }

    @Test
    fun `the section's theme choice keeps the current face instead of forcing the platform default`() {
        assertEquals(null, GameCoreFontFamilies.of(PluginSectionFont.THEME))
        assertEquals(FontFamily.Default, GameCoreFontFamilies.of(PluginSectionFont.SYSTEM))
        assertEquals(GameCoreFontFamilies.minecraft, GameCoreFontFamilies.of(PluginSectionFont.MINECRAFT))
    }

    @Test
    fun `the section type override changes the face and scales both size and line height`() {
        val base = androidx.compose.material3.Typography()
        val scaled = base.withPluginSectionType(FontFamily.Monospace, 1.25f)

        assertEquals(FontFamily.Monospace, scaled.bodyMedium.fontFamily)
        assertEquals(base.bodyMedium.fontSize * 1.25f, scaled.bodyMedium.fontSize)
        assertEquals(base.bodyMedium.lineHeight * 1.25f, scaled.bodyMedium.lineHeight)
        // A null family means "leave the current theme's face alone", not "use the platform default".
        assertEquals(base.bodyMedium.fontFamily, base.withPluginSectionType(null, 1f).bodyMedium.fontFamily)
    }

    // ---------------------------------------------------------------------------- the transition

    @Test
    fun `a light plugin theme is legible on its own terms too`() {
        // The light path is a different base scheme with a different `onPrimary` strategy, so the guard
        // is exercised there as well rather than only on the dark fixture every other test uses.
        val t = theme(
            dark = false,
            primary = 0xFF2A5FD0.toInt(),
            onPrimary = 0xFF2E63D4.toInt(),
            background = 0xFFFFFFFF.toInt(),
            surface = 0xFFFFFFFF.toInt(),
            onSurface = 0xFFFAFAFA.toInt(),
        )
        val scheme = t.toColorScheme()

        assertTrue(ratio(scheme.onSurface, scheme.surface) >= MIN_CONTRAST_RATIO)
        assertTrue(ratio(scheme.onPrimary, scheme.primary) >= MIN_CONTRAST_RATIO)
        assertTrue(ratio(scheme.onBackground, scheme.background) >= MIN_CONTRAST_RATIO)
    }

    // ------------------------------------------------------- schema 2: the amoled base and the roles

    @Test
    fun `a dark amoled theme bases on the true-black scheme, not the ordinary dark one`() {
        val t = theme(dark = true, amoled = true)
        val amoledHouse = amoledSchemeFor(Color(t.accent))
        val scheme = t.toColorScheme()

        // surfaceVariant and outline are roles the v1 copy leaves untouched and where amoled diverges.
        assertEquals(amoledHouse.surfaceVariant, scheme.surfaceVariant)
        assertEquals(amoledHouse.outline, scheme.outline)
        assertNotEquals(darkSchemeFor(Color(t.accent)).surfaceVariant, scheme.surfaceVariant)
    }

    @Test
    fun `amoled is a dark-only base and is ignored on a light theme`() {
        val t = theme(dark = false, amoled = true)

        assertEquals(lightSchemeFor(Color(t.accent)).surfaceVariant, t.toColorScheme().surfaceVariant)
    }

    @Test
    fun `a supplied role fill is copied over the base and forced opaque`() {
        val scheme = theme(roles = PluginThemeRoles(secondary = 0x8000FF00.toInt())).toColorScheme()

        assertEquals(Color(0xFF00FF00.toInt()), scheme.secondary)
        assertOpaque(scheme.secondary, "secondary")
    }

    @Test
    fun `a role the plugin leaves unset still comes from the house scheme`() {
        val t = theme(roles = PluginThemeRoles(secondary = 0xFF00FF00.toInt()))
        val house = houseFor(t)
        val scheme = t.toColorScheme()

        assertEquals(house.tertiary, scheme.tertiary)
        assertEquals(house.outline, scheme.outline)
    }

    @Test
    fun `a role ink is guarded against its own fill the way the v1 inks are`() {
        // onError sits a hair off the error fill — legal input, illegible, must be substituted.
        val scheme = theme(roles = PluginThemeRoles(error = 0xFFB00020.toInt(), onError = 0xFFA10121.toInt())).toColorScheme()

        assertEquals(Color(0xFFB00020.toInt()), scheme.error)
        assertNotEquals(Color(0xFFA10121.toInt()), scheme.onError)
        assertTrue(
            "the substituted onError must clear the floor on the role's own fill",
            ratio(scheme.onError, scheme.error) >= MIN_CONTRAST_RATIO,
        )
    }

    @Test
    fun `a legible role ink is used as given`() {
        val scheme = theme(roles = PluginThemeRoles(error = 0xFFB00020.toInt(), onError = 0xFFFFFFFF.toInt())).toColorScheme()

        assertEquals(Color(0xFFFFFFFF.toInt()), scheme.onError)
    }

    @Test
    fun `a theme with no typography resolves to unit scale and no weight`() {
        assertEquals(PluginThemeTypography.DEFAULT_SCALE, theme().resolvedTypeScale(), 0.0001f)
        assertEquals(null, theme().resolvedBaseWeight())
    }

    @Test
    fun `the resolved type scale is clamped at both ends`() {
        assertEquals(
            PluginThemeTypography.MAX_SCALE,
            theme(typography = PluginThemeTypography(scale = 9.0f)).resolvedTypeScale(),
            0.0001f,
        )
        assertEquals(
            PluginThemeTypography.MIN_SCALE,
            theme(typography = PluginThemeTypography(scale = 0.1f)).resolvedTypeScale(),
            0.0001f,
        )
    }

    @Test
    fun `each named weight maps to its Compose FontWeight`() {
        assertEquals(FontWeight.Light, theme(typography = PluginThemeTypography(weight = PluginThemeTypography.Weight.LIGHT)).resolvedBaseWeight())
        assertEquals(FontWeight.Normal, theme(typography = PluginThemeTypography(weight = PluginThemeTypography.Weight.NORMAL)).resolvedBaseWeight())
        assertEquals(FontWeight.Medium, theme(typography = PluginThemeTypography(weight = PluginThemeTypography.Weight.MEDIUM)).resolvedBaseWeight())
        assertEquals(FontWeight.Bold, theme(typography = PluginThemeTypography(weight = PluginThemeTypography.Weight.BOLD)).resolvedBaseWeight())
    }
}
