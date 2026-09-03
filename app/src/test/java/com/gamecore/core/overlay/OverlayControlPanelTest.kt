package com.gamecore.core.overlay

import com.gamecore.core.model.FloatingButtonConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The control panel's grid arithmetic, which is the whole of what makes a resizable panel reflow.
 *
 * [actionsPerRow] is the one figure the panel, the settings preview and [FloatingButtonConfig]'s floor all
 * have to agree on, and the bugs below are the ones that agreement exists to prevent: a row of tiles wider
 * than the plate it is drawn on (which is what the old fixed 57 dp grid was at the default width), a
 * "grid" of one tile per row at the narrowest setting, and a default width that quietly stopped drawing
 * the four across the panel has always had.
 */
class OverlayControlPanelTest {

    @Test
    fun `the narrowest panel the model allows still gets a grid rather than a list`() {
        assertEquals(2, actionsPerRow(FloatingButtonConfig.MIN_PANEL_WIDTH_DP))
    }

    @Test
    fun `the default width draws the four tiles across it has always had`() {
        assertEquals(4, actionsPerRow(FloatingButtonConfig.DEFAULT_PANEL_WIDTH_DP))
    }

    /**
     * The clipping bug, stated as the inequality [actionsPerRow]'s KDoc claims: `n` tiles at their minimum
     * width, plus the `n - 1` gaps between them, inside the plate less its padding. Four fixed 57 dp tiles
     * in a 268 dp panel came to 270 dp and overhung by two, which nothing in the layout would have
     * reported — the last tile was simply cut off.
     */
    @Test
    fun `a row of tiles fits inside the plate at every width the model allows`() {
        for (widthDp in FloatingButtonConfig.PANEL_WIDTH_RANGE) {
            val perRow = actionsPerRow(widthDp)
            val needed = perRow * MIN_ACTION_WIDTH_DP + (perRow - 1) * ACTION_GAP_DP
            val available = widthDp - PANEL_PADDING_DP * 2
            assertTrue("$perRow tiles need $needed dp of $available dp at $widthDp dp", needed <= available)
        }
    }

    @Test
    fun `a wider panel never draws fewer tiles across than a narrower one`() {
        var previous = actionsPerRow(FloatingButtonConfig.MIN_PANEL_WIDTH_DP)
        for (widthDp in FloatingButtonConfig.PANEL_WIDTH_RANGE) {
            val perRow = actionsPerRow(widthDp)
            assertTrue("$perRow across at $widthDp dp, after $previous", perRow >= previous)
            previous = perRow
        }
    }

    /**
     * The floor and the ceiling, for widths the range does not contain.
     *
     * A stored width outside the range should be impossible — [FloatingButtonConfig.normalised] clamps it —
     * but this function is also handed the live screen-clamped figure by the service, so it has to answer
     * for a width the model never approved rather than divide its way to zero tiles per row.
     */
    @Test
    fun `an impossible width is answered rather than divided into nothing`() {
        assertEquals(2, actionsPerRow(0))
        assertEquals(2, actionsPerRow(-100))
        assertEquals(OverlayAction.entries.size, actionsPerRow(10_000))
    }

    /** At its widest the panel is still short of a row per action, so the ceiling is not what binds. */
    @Test
    fun `the widest panel the model allows is bound by its width and not by the action count`() {
        val perRow = actionsPerRow(FloatingButtonConfig.MAX_PANEL_WIDTH_DP)
        assertTrue("$perRow across at the maximum width", perRow < OverlayAction.entries.size)
        assertTrue("$perRow across at the maximum width", perRow > 4)
    }
}
