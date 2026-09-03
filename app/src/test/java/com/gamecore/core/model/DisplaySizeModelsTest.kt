package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the Aspect Ratio chips, which is the whole of the feature that can be tested here.
 *
 * A display override is the most consequential thing GameCore writes. It is set with a shell command that
 * prints nothing, it survives a reboot, and a wrong number leaves a screen the user cannot read well enough
 * to undo it with. None of that is observable on this host, so what is checked here is everything the device
 * is not needed for: that a preset keeps the short side and only ever shortens the long one, that a size is
 * refused before it is written rather than after, that a `WxH` argument means the same thing whichever way
 * round the user typed it, and that no outcome of a size change reports success without a read-back.
 *
 * The numbers are a real 20:9 panel, because the interesting cases are the ones a phone actually has: a
 * ratio that reduces to something nobody recognises, and a preset that is native under another name.
 */
class DisplaySizeModelsTest {

    private val panel = DisplaySize(1080, 2400)

    @Test
    fun `a size knows its sides, its shape and the argument the shell takes`() {
        assertEquals(2400, panel.longSide)
        assertEquals(1080, panel.shortSide)
        assertEquals("1080 × 2400", panel.label)
        assertEquals("1080x2400", panel.argument)
        assertEquals("20:9", panel.aspectLabel)

        // The rotated panel is the same panel. Everything that verifies a change compares this way,
        // because the shell reports the display upright and GameCore's own window does not.
        assertTrue(panel.matches(DisplaySize(2400, 1080)))
        assertTrue(panel.matches(panel))
        assertFalse(panel.matches(DisplaySize(1080, 2340)))
    }

    @Test
    fun `a ratio nothing reduces to is stated as a decimal rather than invented`() {
        assertEquals("13:6", DisplaySize(1080, 2340).aspectLabel)
        assertEquals("2.16:1", DisplaySize(1080, 2337).aspectLabel)
        assertEquals("1:1", DisplaySize(1080, 1080).aspectLabel)
        assertEquals("—", DisplaySize(0, 0).aspectLabel)
    }

    @Test
    fun `a preset keeps the short side and shortens the long one`() {
        // This is what makes it a stretch. The panel still lights 1080 pixels across; the game is handed
        // fewer down the length and the compositor spreads them over the whole screen.
        assertEquals(DisplaySize(1080, 2160), AspectPreset.TALL_18_9.sizeFor(panel))
        assertEquals(DisplaySize(1080, 1920), AspectPreset.WIDE_16_9.sizeFor(panel))
        assertEquals(DisplaySize(1080, 1440), AspectPreset.CLASSIC_4_3.sizeFor(panel))
        assertEquals(panel, AspectPreset.NATIVE.sizeFor(panel))

        AspectPreset.entries.mapNotNull { it.sizeFor(panel) }.forEach { size ->
            assertEquals(size.label, panel.shortSide, size.shortSide)
            assertTrue(size.label, size.longSide <= panel.longSide)
        }
    }

    @Test
    fun `a landscape panel gets landscape presets`() {
        // `wm size` reads its argument upright, so which of the two numbers grows is not cosmetic: a
        // portrait argument on a landscape display is a request to rotate the desktop.
        assertEquals(DisplaySize(1440, 1080), AspectPreset.CLASSIC_4_3.sizeFor(DisplaySize(2400, 1080)))
        assertEquals(DisplaySize(1080, 1440), AspectPreset.CLASSIC_4_3.sizeFor(panel))
    }

    @Test
    fun `a preset that is not shorter than the panel is not offered at all`() {
        // On a 16:9 display, "16:9" is the native size under another name and "18:9" would be an
        // enlargement. Offering either would be a chip that does nothing, or one that costs frame rate.
        val sixteenByNine = DisplaySize(1080, 1920)
        assertNull(AspectPreset.WIDE_16_9.sizeFor(sixteenByNine))
        assertNull(AspectPreset.TALL_18_9.sizeFor(sixteenByNine))
        assertEquals(
            listOf(AspectPreset.NATIVE, AspectPreset.CLASSIC_4_3),
            AspectPreset.optionsFor(sixteenByNine).map { it.preset },
        )

        // And a panel too small to stretch still has one chip, so the row never renders empty.
        assertEquals(listOf(AspectPreset.NATIVE), AspectPreset.optionsFor(DisplaySize(240, 480)).map { it.preset })
    }

    @Test
    fun `a long side that lands on an odd number is rounded down to an even one`() {
        // Half a pixel of accuracy for an even width, which is what every scaler and encoder in the stack
        // expects. The short side is passed through untouched, odd or not.
        val stretched = DisplaySize(1081, 2400).stretchedTo(4f / 3f)
        assertEquals(DisplaySize(1081, 1440), stretched)
        assertNull("a ratio below 1 is not a stretch", panel.stretchedTo(0.5f))
        assertNull("a panel this narrow has nothing to give", DisplaySize(240, 480).stretchedTo(4f / 3f))
    }

    @Test
    fun `a size is refused before it is written, with the reason the user reads`() {
        assertNull(DisplaySize(1080, 1440).rejectionFor(panel))
        assertNull("the native size itself is never the thing refused", panel.rejectionFor(panel))

        val tooTall = DisplaySize(1080, 2560).rejectionFor(panel)
        assertTrue("$tooTall", tooTall.orEmpty().contains("larger than this display's 1080 × 2400"))
        val tooWide = DisplaySize(1440, 2400).rejectionFor(panel)
        assertTrue("$tooWide", tooWide.orEmpty().contains("larger than this display's"))

        val tiny = DisplaySize(240, 320).rejectionFor(panel)
        assertTrue("$tiny", tiny.orEmpty().contains("at least ${DisplaySize.MIN_SIDE} pixels"))

        val sliver = DisplaySize(320, 1080).rejectionFor(panel)
        assertTrue("$sliver", sliver.orEmpty().contains("sliver"))
        assertNull("exactly at the limit is still a screen", DisplaySize(360, 1080).rejectionFor(panel))

        assertTrue(DisplaySize(0, 1440).rejectionFor(panel).orEmpty().contains("positive"))
        assertTrue(DisplaySize(1080, -1).rejectionFor(panel).orEmpty().contains("positive"))
    }

    @Test
    fun `a custom size typed the other way round means the same display`() {
        assertEquals(DisplaySize(1080, 1920), DisplaySize(1920, 1080).orientedLike(panel))
        assertEquals(DisplaySize(1080, 1920), DisplaySize(1080, 1920).orientedLike(panel))
        assertEquals(DisplaySize(1920, 1080), DisplaySize(1080, 1920).orientedLike(DisplaySize(2400, 1080)))
    }

    @Test
    fun `a size is parsed out of the shell's own spelling, or not at all`() {
        assertEquals(panel, DisplaySize.parse("1080x2400"))
        assertEquals(panel, DisplaySize.parse(" 1080X2400 "))
        listOf("", "abc", "1080", "1080x", "x2400", "1080x2400x3", "0x2400", "-4x2400", "1080.5x2400")
            .forEach { assertNull(it, DisplaySize.parse(it)) }
    }

    @Test
    fun `the state says which shape is on screen and whether it is one of the chips`() {
        val native = DisplaySizeState(physical = panel, override = null)
        assertEquals(panel, native.active)
        assertFalse(native.isOverridden)
        assertEquals(AspectPreset.NATIVE, native.activePreset)
        assertFalse(native.isCustom)
        assertEquals(4, native.options.size)

        val stretched = DisplaySizeState(physical = panel, override = DisplaySize(1080, 1440))
        assertEquals(DisplaySize(1080, 1440), stretched.active)
        assertTrue(stretched.isOverridden)
        assertEquals(AspectPreset.CLASSIC_4_3, stretched.activePreset)
        assertFalse(stretched.isCustom)

        // A size set by hand over adb, or by another app. It is shown as what it is rather than rounded
        // to the nearest chip, and the chips stay unselected.
        val custom = DisplaySizeState(physical = panel, override = DisplaySize(1080, 1700))
        assertNull(custom.activePreset)
        assertTrue(custom.isCustom)

        // Read back the other way up, it is still the 4:3 chip that is lit.
        assertEquals(AspectPreset.CLASSIC_4_3, AspectPreset.of(DisplaySize(1440, 1080), panel))
    }

    @Test
    fun `no outcome of a size change claims success without a read-back`() {
        val outcomes = listOf(
            DisplaySizeOutcome.Applied(DisplaySize(1080, 1440), "Shizuku shell"),
            DisplaySizeOutcome.Restored(panel, "Shizuku shell"),
            DisplaySizeOutcome.NotHonoured(DisplaySize(1080, 1440), panel),
            DisplaySizeOutcome.NotHonoured(DisplaySize(1080, 1440), null),
            DisplaySizeOutcome.AppliedUnverified(DisplaySize(1080, 1440), "the shell stopped answering"),
            DisplaySizeOutcome.SizeUnsupported(DisplaySize(1080, 4000), "too big"),
            DisplaySizeOutcome.RequiresAccess("Needs Shizuku."),
            DisplaySizeOutcome.Failed("the command failed"),
        )
        // Two successes, both of them carrying the source that confirmed them. Everything else — including
        // the request that went through and could not be checked — is not success.
        assertEquals(2, outcomes.count { it.isSuccess })
        outcomes.filter { it.isSuccess }.forEach { assertTrue(it.message, it.message.contains("Display is")) }
        outcomes.forEach { assertTrue(it::class.simpleName, it.message.isNotBlank()) }
        assertTrue(
            outcomes.single { it is DisplaySizeOutcome.AppliedUnverified }.message
                .contains("could not be confirmed"),
        )
    }
}
