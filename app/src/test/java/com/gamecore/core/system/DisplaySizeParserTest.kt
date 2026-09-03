package com.gamecore.core.system

import com.gamecore.core.common.Observed
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.AspectPreset
import com.gamecore.core.model.DisplaySize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `wm size`, parsed.
 *
 * The one command whose output the aspect-ratio feature cannot work without. It supplies the native size
 * every preset is computed from, and it is the read-back that decides whether a size change is reported as
 * applied — so both halves of the honesty contract run through these few lines. What is checked here is that
 * the absent second line means "nothing is set" rather than "could not be read", that an unreadable dump is
 * a failure rather than a physical size of zero, and that neither answer is ever assembled from one line.
 */
class DisplaySizeParserTest {

    @Test
    fun `a panel with nothing set reports itself and no override`() {
        val state = DisplaySizeParser.parse("Physical size: 1080x2400\n").valueOrNull!!
        assertEquals(DisplaySize(1080, 2400), state.physical)
        assertNull(state.override)
        assertFalse(state.isOverridden)
        assertEquals(AspectPreset.NATIVE, state.activePreset)
    }

    @Test
    fun `a stretched panel reports both, and the native size is still the physical one`() {
        val state = DisplaySizeParser.parse(
            """
            Physical size: 1440x3200
            Override size: 1440x1920
            """.trimIndent(),
        ).valueOrNull!!
        assertEquals(DisplaySize(1440, 3200), state.physical)
        assertEquals(DisplaySize(1440, 1920), state.override)
        assertEquals(DisplaySize(1440, 1920), state.active)
        assertEquals(AspectPreset.CLASSIC_4_3, state.activePreset)
    }

    @Test
    fun `an override equal to the panel is still an override`() {
        // It is a row in the display manager's settings that outlives GameCore, and only `wm size reset`
        // removes it. Reporting it as "nothing set" would leave it there for the next reader to find.
        val state = DisplaySizeParser.parse("Physical size: 1080x2400\nOverride size: 1080x2400")
            .valueOrNull!!
        assertTrue(state.isOverridden)
        assertEquals(AspectPreset.NATIVE, state.activePreset)
        assertFalse(state.isCustom)
    }

    @Test
    fun `a dump that does not say what the size is reports a failure, not a zero`() {
        // Including the last one, which is the case a parser assembled from whichever line it found first
        // would get wrong: an override with no physical size is not a 1080×1440 panel.
        listOf(
            "",
            "   ",
            "wm: not found",
            "Physical size:",
            "Physical size: WxH",
            "Override size: 1080x1440",
        ).forEach { text ->
            assertTrue(text, DisplaySizeParser.parse(text) is Observed.Failed)
            assertNull(text, DisplaySizeParser.parse(text).valueOrNull)
        }
    }

    @Test
    fun `the line is read as the shell prints it, spacing and case included`() {
        assertEquals(
            DisplaySize(1080, 2400),
            DisplaySizeParser.parse("  Physical size: 1080 X 2400  \n").valueOrNull?.physical,
        )
    }
}
