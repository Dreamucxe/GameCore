package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dock config's clamps and its per-orientation position memory (feature 1).
 *
 * [DockConfig] is the floating button's [FloatingButtonConfig] template, so these tests are the button's
 * position and clamp tests re-aimed at the dock: `normalised()` is the single point a size, an opacity, a
 * panel width or a stored fraction assembled anywhere — a slider, a file read back, a drag mid-game — passes
 * through before it is drawn, and each test below is the bug that would follow if it did not. The fraction
 * round-trip is the same additive, orientation-independent memory the button keeps, for the same reason: a
 * fraction set on a portrait screen is meaningless on a landscape one, so each orientation is stored and read
 * back on its own and a config that has never been placed reports null rather than a corner the user never
 * picked.
 */
class DockModelsTest {

    // ------------------------------------------------------------------------------- opacity clamp

    @Test
    fun `an opacity below the floor comes back up to it`() {
        assertEquals(DockConfig.MIN_OPACITY, DockConfig(opacity = 0f).normalised().opacity, DELTA)
        assertEquals(DockConfig.MIN_OPACITY, DockConfig(opacity = -1f).normalised().opacity, DELTA)
    }

    @Test
    fun `an opacity above the ceiling comes back down to it`() {
        assertEquals(DockConfig.MAX_OPACITY, DockConfig(opacity = 2f).normalised().opacity, DELTA)
    }

    @Test
    fun `an opacity inside the range is left exactly as it was chosen`() {
        assertEquals(0.5f, DockConfig(opacity = 0.5f).normalised().opacity, DELTA)
    }

    // ------------------------------------------------------------------------------- size clamp

    @Test
    fun `a size below the floor comes back up to it`() {
        assertEquals(DockConfig.MIN_SIZE_DP, DockConfig(sizeDp = 0).normalised().sizeDp)
        assertEquals(DockConfig.MIN_SIZE_DP, DockConfig(sizeDp = -20).normalised().sizeDp)
    }

    @Test
    fun `a size above the ceiling comes back down to it`() {
        assertEquals(DockConfig.MAX_SIZE_DP, DockConfig(sizeDp = 4_000).normalised().sizeDp)
    }

    @Test
    fun `a size inside the range is left exactly as it was chosen`() {
        for (sizeDp in DockConfig.SIZE_RANGE) {
            assertEquals(sizeDp, DockConfig(sizeDp = sizeDp).normalised().sizeDp)
        }
    }

    // ------------------------------------------------------------------------------- panel width clamp

    @Test
    fun `a panel width below the floor comes back up to it`() {
        val floor = DockConfig.MIN_PANEL_WIDTH_DP
        assertEquals(floor, DockConfig(panelWidthDp = 20).normalised().panelWidthDp)
        assertEquals(floor, DockConfig(panelWidthDp = -400).normalised().panelWidthDp)
    }

    @Test
    fun `a panel width above the ceiling comes back down to it`() {
        assertEquals(DockConfig.MAX_PANEL_WIDTH_DP, DockConfig(panelWidthDp = 4_000).normalised().panelWidthDp)
    }

    @Test
    fun `a panel width inside the range is left exactly as it was chosen`() {
        for (widthDp in DockConfig.PANEL_WIDTH_RANGE) {
            assertEquals(widthDp, DockConfig(panelWidthDp = widthDp).normalised().panelWidthDp)
        }
    }

    // ------------------------------------------------------------------------------- the shipped default

    /**
     * A default outside its own range would normalise to something other than what the app shipped with — a
     * "Reset dock to default" that reset to a different figure than the one its copy names. Asserted for the
     * dock the same way it is for the button, because the default is a promise to existing users.
     */
    @Test
    fun `the config the dock ships at is one the clamp agrees with`() {
        val shipped = DockConfig()
        assertEquals(shipped, shipped.normalised())
        assertTrue(shipped.sizeDp in DockConfig.SIZE_RANGE)
        assertTrue(shipped.panelWidthDp in DockConfig.PANEL_WIDTH_RANGE)
        assertTrue(shipped.opacity in DockConfig.MIN_OPACITY..DockConfig.MAX_OPACITY)
    }

    @Test
    fun `the dock is hidden until the user turns it on`() {
        // Off by default: the dock is opt-in, the sibling the user adds alongside the button rather than a
        // second window forced on everyone. A plain boolean with no clamp, so this and the round trip are all
        // there is to pin.
        assertFalse(DockConfig().show)
        assertTrue(DockConfig(show = true).normalised().show)
    }

    @Test
    fun `the exported default equals a plain construction`() {
        // DEFAULT is the value the store falls back to before the user touches the dock; if it ever drifted
        // from DockConfig() the shipped dock and the fallback dock would disagree.
        assertEquals(DockConfig(), DockConfig.DEFAULT)
    }

    // ------------------------------------------------------------------------------- position fractions

    @Test
    fun `a dock that has never been placed reports no fraction for either orientation`() {
        val fresh = DockConfig()
        assertNull(fresh.positionFraction(portrait = true))
        assertNull(fresh.positionFraction(portrait = false))
        assertFalse(fresh.hasStoredPositionFraction())
    }

    @Test
    fun `each orientation reads back exactly the pair written for it, leaving the other alone`() {
        val placed = DockConfig()
            .withPositionFraction(portrait = true, xFraction = 0.1f, yFraction = 0.2f)
            .withPositionFraction(portrait = false, xFraction = 0.8f, yFraction = 0.9f)
        assertEquals(0.1f to 0.2f, placed.positionFraction(portrait = true))
        assertEquals(0.8f to 0.9f, placed.positionFraction(portrait = false))
        assertTrue(placed.hasStoredPositionFraction())
    }

    @Test
    fun `placing in one orientation does not invent a fraction for the other`() {
        val onlyPortrait = DockConfig()
            .withPositionFraction(portrait = true, xFraction = 0.3f, yFraction = 0.4f)
        assertEquals(0.3f to 0.4f, onlyPortrait.positionFraction(portrait = true))
        assertNull(onlyPortrait.positionFraction(portrait = false))
    }

    @Test
    fun `half a coordinate is no coordinate, so a lone axis falls back to pixels`() {
        val halfP = DockConfig(portraitXFraction = 0.5f, portraitYFraction = null)
        assertNull(halfP.positionFraction(portrait = true))
        val halfL = DockConfig(landscapeYFraction = 0.5f, landscapeXFraction = null)
        assertNull(halfL.positionFraction(portrait = false))
    }

    @Test
    fun `a fraction outside zero-to-one is pulled back onto the display when normalised`() {
        val wild = DockConfig(
            portraitXFraction = 1.4f, portraitYFraction = -0.3f,
            landscapeXFraction = 2f, landscapeYFraction = 0.5f,
        ).normalised()
        assertEquals(1f to 0f, wild.positionFraction(portrait = true))
        assertEquals(1f to 0.5f, wild.positionFraction(portrait = false))
    }

    @Test
    fun `normalising leaves a null fraction null rather than snapping it to an edge`() {
        val normalised = DockConfig().normalised()
        assertNull(normalised.portraitXFraction)
        assertNull(normalised.portraitYFraction)
        assertNull(normalised.landscapeXFraction)
        assertNull(normalised.landscapeYFraction)
    }

    @Test
    fun `reset clears every remembered fraction and touches nothing else`() {
        val placed = DockConfig(x = 940, y = 1_600)
            .withPositionFraction(portrait = true, xFraction = 0.1f, yFraction = 0.2f)
            .withPositionFraction(portrait = false, xFraction = 0.8f, yFraction = 0.9f)
        val cleared = placed.clearedPositionFractions()
        assertNull(cleared.positionFraction(portrait = true))
        assertNull(cleared.positionFraction(portrait = false))
        assertFalse(cleared.hasStoredPositionFraction())
        assertEquals("the pixels stay put for the service to fall back on", placed.copy(
            portraitXFraction = null, portraitYFraction = null,
            landscapeXFraction = null, landscapeYFraction = null,
        ), cleared)
    }

    @Test
    fun `hasStoredPositionFraction turns true the moment any single axis is set`() {
        // The button's four fraction fields are additive, and this is the "have I ever been placed" probe the
        // service reads to decide pixels-vs-fraction. A lone axis is enough to flip it — a partial write must
        // not read as "never placed" and get silently overwritten.
        assertTrue(DockConfig(portraitXFraction = 0.5f).hasStoredPositionFraction())
        assertTrue(DockConfig(landscapeYFraction = 0.5f).hasStoredPositionFraction())
    }

    // ------------------------------------------------------------------- arrangement and the resets

    /** The 3.7.0 panel, reached without the user arranging anything (§3.7.1, feature 4). */
    @Test
    fun `a dock nobody has arranged opens with the five controls it shipped with`() {
        assertEquals(DockActionId.DEFAULT_VISIBLE, DockConfig().visibleActions())
    }

    @Test
    fun `an order stored without an action this build added still draws a full panel`() {
        val stored = DockConfig(
            actionOrder = listOf(DockActionId.SCREENSHOT),
            hiddenActions = emptySet(),
        ).normalised()
        assertEquals(DockActionId.entries.toSet(), stored.actionOrder.toSet())
        assertEquals(DockActionId.SCREENSHOT, stored.visibleActions().first())
    }

    /**
     * Feature 6's "resetting one feature must not affect unrelated settings", asserted in both directions:
     * the two resets are separate buttons behind separate confirmations, and each has to leave the other's
     * state exactly as the user left it.
     */
    @Test
    fun `resetting the arrangement leaves the dock's size and place alone`() {
        val customised = DockConfig(
            show = true,
            actionOrder = listOf(DockActionId.HUNT, DockActionId.SCOUT),
            hiddenActions = setOf(DockActionId.CROSSHAIR),
            sizeDp = 60,
            opacity = 0.5f,
            panelWidthDp = 300,
            portraitXFraction = 0.25f,
            portraitYFraction = 0.75f,
        ).normalised()
        val reset = customised.withDefaultActions()

        assertEquals(DockActionId.DEFAULT_ORDER, reset.actionOrder)
        assertEquals(DockActionId.DEFAULT_HIDDEN, reset.hiddenActions)
        assertEquals(60, reset.sizeDp)
        assertEquals(0.5f, reset.opacity, DELTA)
        assertEquals(300, reset.panelWidthDp)
        assertEquals(0.25f, reset.portraitXFraction!!, DELTA)
        assertTrue(reset.show)
    }

    @Test
    fun `resetting the look leaves the arrangement alone and leaves the dock on screen`() {
        val customised = DockConfig(
            show = true,
            actionOrder = listOf(DockActionId.HUNT, DockActionId.SCOUT),
            hiddenActions = setOf(DockActionId.CROSSHAIR),
            sizeDp = 60,
            opacity = 0.5f,
            snapToEdge = false,
            hapticFeedback = false,
            panelWidthDp = 300,
            x = 40,
            y = 50,
            portraitXFraction = 0.25f,
            portraitYFraction = 0.75f,
        ).normalised()
        val reset = customised.withDefaultAppearance()

        assertEquals(DockConfig.DEFAULT_SIZE_DP, reset.sizeDp)
        assertEquals(DockConfig.DEFAULT_OPACITY, reset.opacity, DELTA)
        assertEquals(DockConfig.DEFAULT_PANEL_WIDTH_DP, reset.panelWidthDp)
        assertEquals(DockConfig.DEFAULT_X, reset.x)
        assertEquals(DockConfig.DEFAULT_Y, reset.y)
        assertTrue(reset.snapToEdge)
        assertTrue(reset.hapticFeedback)
        // The remembered placement goes, so the dock lands where a fresh install puts it.
        assertFalse(reset.hasStoredPositionFraction())
        // The arrangement does not.
        assertEquals(customised.actionOrder, reset.actionOrder)
        assertEquals(customised.hiddenActions, reset.hiddenActions)
        // And the dock does not vanish — a dock that disappeared the instant the user pressed "reset"
        // would read as a crash rather than a reset.
        assertTrue(reset.show)
    }

    private companion object {
        /** Floats come off a coerceIn, so compare with a delta rather than for bit-exact equality. */
        const val DELTA = 1e-6f
    }
}
