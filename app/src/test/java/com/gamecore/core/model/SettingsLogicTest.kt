package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §7 Settings screen's claims, proved off-device.
 *
 * The group that matters most is the text-size one. §7 requires the Font scale slider and the Text size
 * presets to share one stored multiplier, and the failure mode is not a crash — it is a screen that says
 * "Medium" above a slider reading 122%, which a user cannot resolve and will not trust afterwards. These
 * tests hold the derivation in both directions and pin the presets inside the slider's real bounds.
 */
class SettingsLogicTest {

    // -------------------------------------------------------------------------- text size presets

    @Test
    fun `each named preset is recognised from its stored multiplier`() {
        assertEquals(TextSizePreset.SMALL, textSizePreset(90))
        assertEquals(TextSizePreset.MEDIUM, textSizePreset(100))
        assertEquals(TextSizePreset.LARGE, textSizePreset(115))
    }

    /** The whole point of deriving rather than storing: an off-stop value is named, not rounded away. */
    @Test
    fun `a value between the stops reads as Custom rather than the nearest preset`() {
        assertEquals(TextSizePreset.CUSTOM, textSizePreset(122))
        assertEquals(TextSizePreset.CUSTOM, textSizePreset(99))
        assertEquals(TextSizePreset.CUSTOM, textSizePreset(101))
    }

    /**
     * A preset outside the slider's own range would be selectable and then immediately clamped to a
     * different number, which reads as the app refusing the tap. Every stop must be reachable.
     */
    @Test
    fun `every real preset sits inside the slider's bounds`() {
        TextSizePreset.entries.mapNotNull { it.percent }.forEach { percent ->
            assertTrue(
                "$percent% is outside ${AppSettings.MIN_UI_SCALE}..${AppSettings.MAX_UI_SCALE}",
                percent in AppSettings.MIN_UI_SCALE..AppSettings.MAX_UI_SCALE,
            )
        }
    }

    @Test
    fun `Custom is the only preset without a value of its own`() {
        assertNull(TextSizePreset.CUSTOM.percent)
        TextSizePreset.entries.filter { it != TextSizePreset.CUSTOM }.forEach {
            assertTrue("${it.name} needs a percent", it.percent != null)
        }
    }

    @Test
    fun `tapping a preset stores that preset's multiplier`() {
        assertEquals(90, scaleForPreset(TextSizePreset.SMALL, current = 122))
        assertEquals(100, scaleForPreset(TextSizePreset.MEDIUM, current = 85))
        assertEquals(115, scaleForPreset(TextSizePreset.LARGE, current = 100))
    }

    /** "Custom" names a state; it is not a value to jump to, so tapping it must move nothing. */
    @Test
    fun `tapping Custom leaves the chosen size exactly where it was`() {
        assertEquals(122, scaleForPreset(TextSizePreset.CUSTOM, current = 122))
        assertEquals(85, scaleForPreset(TextSizePreset.CUSTOM, current = 85))
    }

    /** Round trip: what a preset stores must be what the preset row then lights up. */
    @Test
    fun `storing a preset and re-deriving it returns the same preset`() {
        TextSizePreset.entries.filter { it != TextSizePreset.CUSTOM }.forEach { preset ->
            assertEquals(preset, textSizePreset(scaleForPreset(preset, current = 100)))
        }
    }

    @Test
    fun `every preset carries a word, since the row is read as well as seen`() {
        TextSizePreset.entries.forEach { assertTrue(it.name, it.label.isNotBlank()) }
    }

    @Test
    fun `the slider reads out as a percentage`() {
        assertEquals("100%", uiScaleLabel(100))
        assertEquals("130%", uiScaleLabel(130))
    }

    // ------------------------------------------------------------------------------ custom accent

    @Test
    fun `an accent is printed as the six-digit hex a user could paste back`() {
        assertEquals("#00E5FF", accentHex(0xFF00E5FF.toInt()))
        assertEquals("#4CE07A", accentHex(0xFF4CE07A.toInt()))
    }

    /** The alpha byte is forced opaque on the way in, so printing it would show a byte nobody chose. */
    @Test
    fun `the alpha byte is never printed`() {
        assertEquals("#00E5FF", accentHex(0x0000E5FF))
        assertEquals(7, accentHex(0xFF123456.toInt()).length)
    }

    @Test
    fun `a colour that was never picked says so plainly`() {
        assertEquals("Not chosen", customAccentLabel(null, useCustomAccent = false))
        assertEquals("Not chosen", customAccentLabel(null, useCustomAccent = true))
    }

    /**
     * A user who picked a colour and switched it off has not lost it, and must not be told they have —
     * otherwise they pick it again, which is the work the screen was supposed to save them.
     */
    @Test
    fun `a chosen but unused colour is still shown, marked as not in use`() {
        val label = customAccentLabel(0xFF00E5FF.toInt(), useCustomAccent = false)
        assertTrue(label, label.contains("#00E5FF"))
        assertTrue(label, label.contains("not in use"))
        assertNotEquals("Not chosen", label)
    }

    @Test
    fun `a colour in use is shown as just its hex`() {
        assertEquals("#00E5FF", customAccentLabel(0xFF00E5FF.toInt(), useCustomAccent = true))
    }

    // ------------------------------------------------------------------------------------- about

    @Test
    fun `the version line carries the build number in brackets`() {
        assertEquals("3.3 (17)", appVersionLabel("3.3", 17L))
    }

    @Test
    fun `a version with no build number is still worth showing`() {
        assertEquals("3.3", appVersionLabel("3.3", null))
    }

    @Test
    fun `a build number with no name is still worth showing`() {
        assertEquals("Build 17", appVersionLabel(null, 17L))
        assertEquals("Build 17", appVersionLabel("", 17L))
    }

    /**
     * Null, not a fallback string. The caller is then forced onto the honest "Unavailable" path with the
     * real reason attached, rather than printing a plausible-looking version nobody verified.
     */
    @Test
    fun `nothing reported means nothing claimed`() {
        assertNull(appVersionLabel(null, null))
        assertNull(appVersionLabel("", null))
        assertNull(appVersionLabel("   ", null))
    }
}
