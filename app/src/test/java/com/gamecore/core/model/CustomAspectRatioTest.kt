package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The saved custom shapes that sit beside the four built-in Aspect chips.
 *
 * The rules here are the same ones a preset is held to, plus storage's: parse, turn to the panel's
 * orientation, reject anything that is not shorter than the panel or that [DisplaySize.rejectionFor] would
 * refuse, collapse duplicates, keep the order the user added them in, and stop at eight. None of that needs
 * a device, which is why it is the part of the feature this host can check.
 */
class CustomAspectRatioTest {

    private val panel = DisplaySize(1080, 2400)

    @Test
    fun `a saved shape is parsed, oriented and labelled as a ratio`() {
        val choices = AspectPreset.customChoices(panel, listOf("1080x1440", "1920x1080"))

        assertEquals(2, choices.size)
        assertEquals("4:3", choices[0].label)
        assertEquals("1080 × 1440", choices[0].detail)
        // 1920 × 1080 is the same 16:9 display the other way round; wm size wants it upright.
        assertEquals(DisplaySize(1080, 1920), choices[1].size)
        assertEquals("16:9", choices[1].label)
        choices.forEach {
            assertTrue(it.custom)
            assertFalse(it.active)
            assertEquals(it.size.label, it.detail)
        }
    }

    @Test
    fun `a landscape panel gets landscape custom shapes`() {
        val landscape = DisplaySize(2400, 1080)
        val choices = AspectPreset.customChoices(landscape, listOf("1080x1920", "1920x1080"))

        assertEquals(1, choices.size)
        assertEquals(DisplaySize(1920, 1080), choices.single().size)
        assertEquals("16:9", choices.single().label)
    }

    @Test
    fun `custom shapes keep their order and collapse duplicates either way round`() {
        val choices = AspectPreset.customChoices(
            panel,
            listOf("1080x2160", "1080x1440", "1440x1080", "1080x1920"),
        )

        assertEquals(listOf("18:9", "4:3", "16:9"), choices.map { it.label })
        assertEquals(listOf(1080 to 2160, 1080 to 1440, 1080 to 1920), choices.map {
            it.size.widthPixels to it.size.heightPixels
        })
    }

    @Test
    fun `blank and unreadable entries are dropped rather than stored`() {
        val invalid = listOf(
            "",
            "   ",
            "abc",
            "1080",
            "1080x",
            "x2400",
            "1080x2400x3",
            "0x1000",
            "-4x2400",
            "1080.5x2400",
        )

        assertTrue(AspectPreset.customChoices(panel, invalid).isEmpty())
    }

    @Test
    fun `a shape that is not shorter than the panel is rejected`() {
        // Equal to the panel, taller than it, and wider than it: a custom shape has to cut the long side,
        // exactly as a preset does. Otherwise the chip would do nothing or cost frame rate.
        listOf("1080x2400", "1080x2560", "1440x2400", "1200x1600").forEach {
            assertTrue("$it should not be offered", AspectPreset.customChoices(panel, listOf(it)).isEmpty())
        }

        // And the same size on the landscape panel is a valid 16:9 shape.
        assertEquals(1, AspectPreset.customChoices(DisplaySize(2400, 1080), listOf("1920x1080")).size)
    }

    @Test
    fun `a shape the display could not survive is rejected with the existing rule`() {
        // The narrower side has to stay usable, and the ratio cannot be a sliver. Both sentences come from
        // DisplaySize.rejectionFor rather than from a second copy of the bounds.
        assertTrue(AspectPreset.customChoices(panel, listOf("1080x300")).isEmpty())
        assertTrue(AspectPreset.customChoices(panel, listOf("320x1080")).isEmpty())

        val tooSmall = DisplaySize(1080, 300).orientedLike(panel)
        assertTrue(tooSmall.rejectionFor(panel)!!.contains("at least ${DisplaySize.MIN_SIDE} pixels"))
    }

    @Test
    fun `at most eight custom shapes are offered`() {
        val ten = listOf(
            "1080x1440", "1080x1520", "1080x1600", "1080x1680", "1080x1760",
            "1080x1840", "1080x1920", "1080x2000", "1080x2080", "1080x2160",
        )

        val choices = AspectPreset.customChoices(panel, ten)

        assertEquals(AppSettings.MAX_CUSTOM_ASPECT_RATIOS, choices.size)
        assertEquals(8, choices.size)
        // The first eight, in the order they were saved. Dropping the tail is what a ninth Add is refused for.
        assertEquals(ten.take(8).mapNotNull { DisplaySize.parse(it) }, choices.map { it.size })
    }

    @Test
    fun `optionsWith appends the saved shapes and marks the custom one on screen`() {
        val onScreen = DisplaySize(1080, 1710)
        val state = DisplaySizeState(physical = panel, override = onScreen)

        val options = state.optionsWith(listOf("1080x1710", "1080x1440"))

        assertEquals(4 + 2, options.size)
        assertEquals(AspectPreset.NATIVE, options.first().preset)
        assertEquals(listOf("Native", "18:9", "16:9", "4:3", "19:12", "4:3"), options.map { it.label })
        assertNull("a custom size has no preset", state.activePreset)
        assertTrue(state.isCustom)

        val active = options.single { it.active }
        assertTrue(active.custom)
        assertEquals("19:12", active.label)
        assertEquals(onScreen, active.size)
    }

    @Test
    fun `only the preset is marked when a saved shape duplicates it`() {
        val size = DisplaySize(1080, 1440)
        val state = DisplaySizeState(physical = panel, override = size)

        val options = state.optionsWith(listOf("1080x1440"))

        val active = options.filter { it.active }
        assertEquals(1, active.size)
        assertFalse(active.single().custom)
        assertEquals(AspectPreset.CLASSIC_4_3, active.single().preset)
    }

    @Test
    fun `no override leaves the native chip alone and every custom chip unfilled`() {
        val state = DisplaySizeState(physical = panel, override = null)

        val options = state.optionsWith(listOf("1080x1440"))

        assertEquals(AspectPreset.NATIVE, options.single { it.active }.preset)
        assertTrue(options.last().custom)
        assertFalse(options.last().active)
    }
}
