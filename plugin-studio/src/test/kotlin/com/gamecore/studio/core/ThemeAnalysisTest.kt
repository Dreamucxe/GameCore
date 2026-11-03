package com.gamecore.studio.core

import com.gamecore.studio.model.GlassDraft
import com.gamecore.studio.model.ThemeDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The measured checks the quality panel shows.
 *
 * A theme cannot prove that a button is tappable, so these tests only assert what the
 * draft's own colours and numbers can support: measured contrast, alpha, scale extremes and
 * the interaction between accent and primary.
 */
class ThemeAnalysisTest {

    @Test
    fun `the default theme passes AA on every main pair`() {
        val checks = ThemeAnalysis.contrastChecks(ThemeDraft())
        assertEquals(6, checks.size)
        checks.forEach { check ->
            assertTrue(
                "${check.label} measured ${check.ratio}",
                check.meetsAA,
            )
        }
    }

    @Test
    fun `a white on white pair is reported as an error`() {
        val theme = ThemeDraft(
            onSurface = 0xFFFFFFFFL,
            background = 0xFFFFFFFFL,
            surface = 0xFFFFFFFFL,
        )
        val failures = ThemeAnalysis.warnings(theme).filter { it.severity == Severity.ERROR }
        assertTrue("no error was reported: ${ThemeAnalysis.warnings(theme)}", failures.isNotEmpty())
        assertTrue(
            "the error did not name the ink field: $failures",
            failures.any { it.field == "theme.onSurface" },
        )
        assertTrue(
            "the error was not a very low contrast warning: $failures",
            failures.any { it.title.startsWith("Very low contrast") },
        )
    }

    @Test
    fun `the default theme scores a clean hundred`() {
        assertEquals(100, ThemeAnalysis.score(ThemeDraft()))
    }

    @Test
    fun `the score drops when a warning is present`() {
        val warned = ThemeDraft(uiScale = ThemeDraft.MIN_UI_SCALE)
        assertTrue(
            "expected a warning at the minimum UI scale, got ${ThemeAnalysis.warnings(warned)}",
            ThemeAnalysis.warnings(warned).any { it.severity == Severity.WARNING },
        )
        assertTrue(
            "warning did not lower the score",
            ThemeAnalysis.score(warned) < ThemeAnalysis.score(ThemeDraft()),
        )
    }

    @Test
    fun `the score drops further when an error is present`() {
        val errored = ThemeDraft(
            onSurface = 0xFFFFFFFFL,
            background = 0xFFFFFFFFL,
            surface = 0xFFFFFFFFL,
        )
        val warned = ThemeDraft(uiScale = ThemeDraft.MIN_UI_SCALE)
        assertTrue(
            "an error should cost more than a warning",
            ThemeAnalysis.score(errored) < ThemeAnalysis.score(warned),
        )
    }

    @Test
    fun `glass warning appears only when glass is enabled`() {
        val translucentInk = ThemeDraft(onSurface = 0xB3F6EFE7L)

        val withoutGlass = ThemeAnalysis.warnings(translucentInk)
        assertFalse(
            "a theme without glass reported a glass warning: $withoutGlass",
            withoutGlass.any { it.title.contains("Glass", ignoreCase = true) },
        )

        val withGlass = ThemeAnalysis.warnings(translucentInk.copy(glass = GlassDraft()))
        assertNotNull(
            "glass over translucent body ink was not reported: $withGlass",
            withGlass.firstOrNull {
                it.severity == Severity.WARNING &&
                    it.title.contains("Glass", ignoreCase = true) &&
                    it.field == "theme.onSurface"
            },
        )
    }

    @Test
    fun `translucent ink is called out even when a pair happens to pass`() {
        // The measured ratio may still be acceptable, but ink below 80% opacity is a risk
        // over a busy or translucent backdrop and the editor should say so on its own.
        val theme = ThemeDraft(onPrimary = 0xB324130AL)
        assertTrue(
            "no translucency note: ${ThemeAnalysis.warnings(theme)}",
            ThemeAnalysis.warnings(theme).any {
                it.title.startsWith("Translucent ink") && it.field == "theme.onPrimary"
            },
        )
    }

    @Test
    fun `an accent identical to primary is flagged as undifferentiated`() {
        val theme = ThemeDraft(accent = ThemeDraft().primary)
        assertTrue(
            "matching accent and primary were not flagged: ${ThemeAnalysis.warnings(theme)}",
            ThemeAnalysis.warnings(theme).any {
                it.title.contains("Accent", ignoreCase = true) &&
                    it.severity == Severity.WARNING
            },
        )
    }

    @Test
    fun `a zero corner radius is a note rather than a defect`() {
        val theme = ThemeDraft(cornerRadiusDp = 0)
        val note = ThemeAnalysis.warnings(theme).firstOrNull { it.field == "theme.cornerRadiusDp" }
        assertNotNull("the square-corner note was missing", note)
        assertEquals(Severity.INFO, note?.severity)
        assertEquals("a style note must not lower the score", 100, ThemeAnalysis.score(theme))
    }

    @Test
    fun `an extreme type scale is flagged`() {
        val theme = ThemeDraft(typographyScale = ThemeDraft.MAX_TYPO_SCALE)
        assertTrue(
            "the maximum type scale was not flagged: ${ThemeAnalysis.warnings(theme)}",
            ThemeAnalysis.warnings(theme).any { it.field == "theme.typographyScale" },
        )
    }
}
