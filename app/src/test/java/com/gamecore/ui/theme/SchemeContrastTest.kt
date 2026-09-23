package com.gamecore.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.gamecore.core.common.ContrastMath
import com.gamecore.core.model.AccentChoice
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §11's "contrast for every theme and accent", proved off-device.
 *
 * [com.gamecore.core.common.ContrastMathTest] proves the arithmetic; this proves the schemes the app
 * actually builds from it. Every accent is run through the dark, AMOLED and light scheme, and the pairs a
 * user reads are checked against the WCAG minimums:
 *
 *  - body text (onSurface / onSurfaceVariant / onBackground against their surface) at 4.5:1, because that
 *    is where the words are and where a low ratio is a genuine "I cannot read this";
 *  - the accent's own text (onPrimary on primary, onError on error) at the 3:1 large-text minimum, since
 *    those carry button and chip labels rather than paragraphs.
 *
 * The accent-driven pairs are the reason this iterates rather than spot-checks: `onPrimary` is chosen by
 * [readableOn] per accent, so a single bad accent would slip past a test that only looked at one. The
 * neutral pairs do not vary with the accent, but running them inside the same loop is free and pins them
 * per theme, which is where they do change.
 *
 * That this runs on the JVM at all is part of the check: the schemes are [androidx.compose.material3.ColorScheme]s
 * built from pure colour maths, and if that ever pulled in a piece of the Android runtime this file would
 * be the first to fail.
 */
class SchemeContrastTest {

    private fun ratio(fg: Color, bg: Color): Double =
        ContrastMath.contrastRatio(fg.toArgb(), bg.toArgb())

    private fun assertBody(fg: Color, bg: Color, where: String) {
        val r = ratio(fg, bg)
        assertTrue("$where: body contrast $r < 4.5", r >= 4.5)
    }

    private fun assertLarge(fg: Color, bg: Color, where: String) {
        val r = ratio(fg, bg)
        assertTrue("$where: large-text contrast $r < 3.0", r >= 3.0)
    }

    private fun check(schemeName: String, accent: AccentChoice, scheme: androidx.compose.material3.ColorScheme) {
        val tag = "$schemeName/${accent.name}"
        assertBody(scheme.onSurface, scheme.surface, "$tag onSurface")
        assertBody(scheme.onSurfaceVariant, scheme.surface, "$tag onSurfaceVariant")
        assertBody(scheme.onBackground, scheme.background, "$tag onBackground")
        assertBody(scheme.onSurfaceVariant, scheme.surfaceVariant, "$tag onSurfaceVariant/surfaceVariant")
        assertLarge(scheme.onPrimary, scheme.primary, "$tag onPrimary")
        assertLarge(scheme.onError, scheme.error, "$tag onError")
    }

    @Test
    fun `every accent is legible in the dark scheme`() {
        AccentChoice.entries.forEach { accent -> check("dark", accent, darkSchemeFor(accent)) }
    }

    @Test
    fun `every accent is legible in the AMOLED scheme`() {
        AccentChoice.entries.forEach { accent -> check("amoled", accent, amoledSchemeFor(accent)) }
    }

    @Test
    fun `every accent is legible in the light scheme`() {
        AccentChoice.entries.forEach { accent -> check("light", accent, lightSchemeFor(accent)) }
    }
}
