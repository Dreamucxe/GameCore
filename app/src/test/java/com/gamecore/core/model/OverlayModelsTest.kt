package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay configs' clamps, which are what make a config assembled anywhere safe to draw.
 *
 * The panel width is the one worth the most care, because it arrives from three places that cannot check
 * each other: a slider, a typed figure in a dialog, and a drag on the panel's own grip in the middle of a
 * game. `normalised()` is the single point all three pass through, and the tests below are the bugs that
 * would follow if it did not — a panel stored wider than any screen, or narrower than the two tiles per row
 * `actionsPerRow` guarantees.
 */
class OverlayModelsTest {

    @Test
    fun `a panel width below the floor comes back up to it`() {
        val floor = FloatingButtonConfig.MIN_PANEL_WIDTH_DP
        assertEquals(floor, FloatingButtonConfig(panelWidthDp = 20).normalised().panelWidthDp)
        assertEquals(floor, FloatingButtonConfig(panelWidthDp = 0).normalised().panelWidthDp)
        assertEquals(floor, FloatingButtonConfig(panelWidthDp = -400).normalised().panelWidthDp)
    }

    @Test
    fun `a panel width above the ceiling comes back down to it`() {
        val wide = FloatingButtonConfig(panelWidthDp = 4_000).normalised()
        assertEquals(FloatingButtonConfig.MAX_PANEL_WIDTH_DP, wide.panelWidthDp)
    }

    @Test
    fun `a panel width inside the range is left exactly as it was chosen`() {
        for (widthDp in FloatingButtonConfig.PANEL_WIDTH_RANGE) {
            assertEquals(widthDp, FloatingButtonConfig(panelWidthDp = widthDp).normalised().panelWidthDp)
        }
    }

    /**
     * A default outside its own range would normalise to something other than what the app shipped with —
     * a "Reset to default size" that reset to a different figure than the one its copy names.
     */
    @Test
    fun `the width the panel ships at is one the clamp agrees with`() {
        val shipped = FloatingButtonConfig()
        assertEquals(FloatingButtonConfig.DEFAULT_PANEL_WIDTH_DP, shipped.panelWidthDp)
        assertEquals(shipped, shipped.normalised())
        assertTrue(shipped.panelWidthDp in FloatingButtonConfig.PANEL_WIDTH_RANGE)
    }

    /**
     * The position is stored raw and snapped at layout time, so nothing in the width's clamp may touch it:
     * a normalise triggered by a slider release must not move a button the user dragged somewhere.
     */
    @Test
    fun `clamping the panel width leaves the button where it was dragged`() {
        val dragged = FloatingButtonConfig(x = 940, y = 1_600, panelWidthDp = 9_000).normalised()
        assertEquals(940, dragged.x)
        assertEquals(1_600, dragged.y)
        assertEquals(FloatingButtonConfig.MAX_PANEL_WIDTH_DP, dragged.panelWidthDp)
    }

    /** The pill's own clamps, alongside, since the two configs are normalised by the same call sites. */
    @Test
    fun `the pill keeps its stats distinct and its interval usable`() {
        val messy = OverlayConfig(
            stats = listOf(HudStat.CPU_USAGE, HudStat.CPU_USAGE, HudStat.RAM_USAGE),
            updateIntervalMillis = 10,
            textSizeSp = 99,
            opacityPercent = 0,
            cornerRadiusDp = 999,
        ).normalised()
        assertEquals(listOf(HudStat.CPU_USAGE, HudStat.RAM_USAGE), messy.stats)
        assertEquals(OverlayConfig.MIN_INTERVAL_MILLIS, messy.updateIntervalMillis)
        assertEquals(OverlayConfig.TEXT_SIZE_RANGE.last, messy.textSizeSp)
        assertEquals(OverlayConfig.OPACITY_RANGE.first, messy.opacityPercent)
        assertEquals(OverlayConfig.CORNER_RANGE.last, messy.cornerRadiusDp)
    }

    @Test
    fun `the pill takes no more stats than it can draw`() {
        val crowded = OverlayConfig(stats = HudStat.entries.toList()).normalised()
        assertTrue(HudStat.entries.size > OverlayConfig.MAX_STATS)
        assertEquals(OverlayConfig.MAX_STATS, crowded.stats.size)
    }
}
