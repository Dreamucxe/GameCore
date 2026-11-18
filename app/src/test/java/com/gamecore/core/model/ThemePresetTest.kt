package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The quick theme switch is a pure function of settings, so it is proved here rather than on a device.
 *
 * The two properties that matter are the ones a user would notice lying: [ThemePreset.matching] must not
 * name a preset while an override is painting something else, and [ThemePreset.applyTo] must actually
 * clear those overrides — otherwise tapping a pack would appear to do nothing on a wallpaper-coloured
 * install.
 */
class ThemePresetTest {

    @Test
    fun `matching reads the mode and the accent`() {
        val settings = AppSettings(theme = ThemeChoice.DARK, accent = AccentChoice.CYAN)

        assertEquals(ThemePreset.GRAPHITE, ThemePreset.matching(settings))
    }

    @Test
    fun `a custom accent matches nothing`() {
        val settings = AppSettings(
            theme = ThemeChoice.DARK,
            accent = AccentChoice.CYAN,
            useCustomAccent = true,
            customAccentArgb = 0xFFFF00FF.toInt(),
        )

        assertNull(ThemePreset.matching(settings))
    }

    @Test
    fun `wallpaper colours match nothing`() {
        val settings = AppSettings(
            theme = ThemeChoice.DARK,
            accent = AccentChoice.CYAN,
            useDynamicColour = true,
        )

        assertNull(ThemePreset.matching(settings))
    }

    @Test
    fun `the redline skin matches nothing`() {
        val settings = AppSettings(
            theme = ThemeChoice.DARK,
            accent = AccentChoice.CYAN,
            redlineSkin = true,
        )

        assertNull(ThemePreset.matching(settings))
    }

    @Test
    fun `applying a preset writes its pair and clears the overrides`() {
        val before = AppSettings(
            theme = ThemeChoice.LIGHT,
            accent = AccentChoice.ORANGE,
            useCustomAccent = true,
            customAccentArgb = 0xFFFF00FF.toInt(),
            useDynamicColour = true,
            uiScalePercent = 115,
        )

        val after = ThemePreset.CYBER.applyTo(before)

        assertEquals(ThemeChoice.DARK, after.theme)
        assertEquals(AccentChoice.TEAL, after.accent)
        assertFalse(after.useCustomAccent)
        assertFalse(after.useDynamicColour)
        // Everything else is the user's and must survive a theme tap.
        assertEquals(115, after.uiScalePercent)
        assertEquals(before.copy(theme = ThemeChoice.DARK, accent = AccentChoice.TEAL, useCustomAccent = false, useDynamicColour = false), after)
    }

    @Test
    fun `applying a preset clears the redline skin`() {
        val before = AppSettings(
            theme = ThemeChoice.DARK,
            accent = AccentChoice.CYAN,
            redlineSkin = true,
        )

        val after = ThemePreset.CYBER.applyTo(before)

        assertFalse(after.redlineSkin)
    }

    @Test
    fun `next wraps from the last preset to the first`() {
        val last = ThemePreset.entries.last()
        val settings = AppSettings(theme = last.theme, accent = last.accent)

        assertEquals(ThemePreset.entries.first(), ThemePreset.next(settings))
    }

    @Test
    fun `next from a custom appearance starts at the first preset`() {
        val settings = AppSettings(theme = ThemeChoice.SYSTEM, accent = AccentChoice.CYAN)

        assertEquals(ThemePreset.entries.first(), ThemePreset.next(settings))
    }
}
