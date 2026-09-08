package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay configs' clamps, which are what make a config assembled anywhere safe to draw, and the rule
 * that decides which saved crosshair or HUD layout a request actually means.
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

    /**
     * The layout the panel opens in, which is the one field on this config that is a choice rather than a
     * figure — and therefore the one the clamp has nothing to say about.
     *
     * Asserted anyway, and asserted first, because the default is a promise to existing users: a build that
     * shipped [PanelLayoutStyle.SPLIT_EDGES] as the default would rearrange the panel of everyone who never
     * asked for it. The enum's own declaration order carries the same promise, since a settings screen that
     * lists the styles as declared would otherwise offer the new one first.
     */
    @Test
    fun `the panel opens centered until the user says otherwise`() {
        assertEquals(PanelLayoutStyle.CENTERED, FloatingButtonConfig().panelLayout)
        assertEquals(PanelLayoutStyle.CENTERED, PanelLayoutStyle.entries.first())
    }

    /**
     * Both directions of the switch, and the width surviving the round trip.
     *
     * The width is the point. Split edges derives its plates from the screen and ignores the stored figure,
     * so the temptation is to clear it on the way in — which would mean a user who tried the other layout
     * and came back found the default width instead of the one they set. `normalised()` runs on every read
     * from the store and on every slider release, so it is where that loss would happen if it happened.
     */
    @Test
    fun `switching layout carries the stored width untouched in both directions`() {
        val chosen = FloatingButtonConfig(panelWidthDp = 300)
        val split = chosen.copy(panelLayout = PanelLayoutStyle.SPLIT_EDGES).normalised()
        assertEquals(PanelLayoutStyle.SPLIT_EDGES, split.panelLayout)
        assertEquals(300, split.panelWidthDp)

        val back = split.copy(panelLayout = PanelLayoutStyle.CENTERED).normalised()
        assertEquals(PanelLayoutStyle.CENTERED, back.panelLayout)
        assertEquals(300, back.panelWidthDp)
    }

    /** Every style is offered with copy to offer it by, since the settings screen renders both fields. */
    @Test
    fun `every layout style says what it is and what it does`() {
        for (style in PanelLayoutStyle.entries) {
            assertTrue("${style.name} has no label", style.label.isNotBlank())
            assertTrue("${style.name} has no description", style.description.isNotBlank())
        }
    }

    /**
     * The panel's Layout tile, which is a tap rather than a choice and therefore rests on this.
     *
     * Two properties, and the second is the one that matters. That the switch is an involution — twice is
     * where you started — is what makes a single tile honest: a player who taps it by accident gets back to
     * the layout they had with the same tap, and the lit plate they see is the state the next tap undoes.
     * A cycle over a list would satisfy the first assertion and fail this one the moment a third style
     * existed, which is why `other()` is written as a `when` that would not compile then.
     */
    @Test
    fun `each layout has the other one behind the panel's tile, and twice is where you started`() {
        assertEquals(PanelLayoutStyle.SPLIT_EDGES, PanelLayoutStyle.CENTERED.other())
        assertEquals(PanelLayoutStyle.CENTERED, PanelLayoutStyle.SPLIT_EDGES.other())
        for (style in PanelLayoutStyle.entries) {
            assertEquals("${style.name} does not come back", style, style.other().other())
            assertTrue("${style.name} is its own other", style != style.other())
        }
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

    // ------------------------------------------------------------------- which crosshair, which layout

    /**
     * The bug these four cover, reported as "only one crosshair does the job".
     *
     * Every path that switches the crosshair on other than the crosshair screen's own button used to pass
     * no preset id — the control panel's toggle, a profile with the switch on and the picker left at None,
     * and any fresh process, because the id the user picked was stored and never read back. The renderer
     * cannot distinguish a request with no id from one naming a deleted preset, so it fell back to the
     * lowest-numbered saved preset for both, and every design the user selected came out as that one.
     */
    @Test
    fun `a named preset is the one that gets drawn`() {
        val request = OverlayRequest().withCrosshair(visible = true, presetId = 7L, remembered = 2L)
        assertTrue(request.crosshair)
        assertEquals(7L, request.crosshairPresetId)
    }

    @Test
    fun `a toggle that names nothing keeps the preset already in the request`() {
        val showing = OverlayRequest(crosshair = true, crosshairPresetId = 7L)
        val hidden = showing.withCrosshair(visible = false, presetId = null, remembered = 2L)
        assertEquals(7L, hidden.crosshairPresetId)
        val back = hidden.withCrosshair(visible = true, presetId = null, remembered = 2L)
        assertEquals(7L, back.crosshairPresetId)
        assertTrue(back.crosshair)
    }

    /** The control panel's toggle in a process that has not been to the crosshair screen. */
    @Test
    fun `a toggle with nothing to keep falls back to the remembered pick`() {
        val request = OverlayRequest.NONE.withCrosshair(visible = true, presetId = null, remembered = 2L)
        assertEquals(2L, request.crosshairPresetId)
    }

    @Test
    fun `an id survives only as null when there has never been a pick`() {
        val request = OverlayRequest.NONE.withCrosshair(visible = true, presetId = null, remembered = null)
        assertTrue(request.crosshair)
        assertNull(request.crosshairPresetId)
    }

    /** Same rule, same reason: a HUD layout the user arranged is not interchangeable with another. */
    @Test
    fun `the hud resolves its layout the same way`() {
        assertEquals(9L, OverlayRequest().withHud(true, layoutId = 9L, remembered = 4L).hudLayoutId)
        assertEquals(4L, OverlayRequest.NONE.withHud(true, layoutId = null, remembered = 4L).hudLayoutId)
        assertEquals(
            9L,
            OverlayRequest(hud = true, hudLayoutId = 9L)
                .withHud(visible = false, layoutId = null, remembered = 4L)
                .hudLayoutId,
        )
    }

    /** Neither helper is a way to change anything else about the request. */
    @Test
    fun `resolving a crosshair leaves the rest of the request alone`() {
        val driven = OverlayRequest(
            button = true,
            pill = true,
            hud = true,
            hudLayoutId = 9L,
            gameLabel = "Some Game",
            fromProfile = true,
        )
        val after = driven.withCrosshair(visible = true, presetId = 7L, remembered = null)
        assertEquals(driven.copy(crosshair = true, crosshairPresetId = 7L), after)
    }
}
