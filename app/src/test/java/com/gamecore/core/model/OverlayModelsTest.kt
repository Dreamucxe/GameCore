package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * The button remembers where it sits as a fraction of the display, one pair per orientation, so a phone
     * turned sideways puts it back where the hand expects rather than off the short edge. The four fields
     * are additive — a config that has never been placed reports null and the service falls back to pixels.
     */
    @Test
    fun `a button that has never been placed reports no fraction for either orientation`() {
        val fresh = FloatingButtonConfig()
        assertNull(fresh.positionFraction(portrait = true))
        assertNull(fresh.positionFraction(portrait = false))
    }

    @Test
    fun `each orientation reads back exactly the pair written for it, leaving the other alone`() {
        val placed = FloatingButtonConfig()
            .withPositionFraction(portrait = true, xFraction = 0.1f, yFraction = 0.2f)
            .withPositionFraction(portrait = false, xFraction = 0.8f, yFraction = 0.9f)
        assertEquals(0.1f to 0.2f, placed.positionFraction(portrait = true))
        assertEquals(0.8f to 0.9f, placed.positionFraction(portrait = false))
    }

    @Test
    fun `placing in one orientation does not invent a fraction for the other`() {
        val onlyPortrait = FloatingButtonConfig()
            .withPositionFraction(portrait = true, xFraction = 0.3f, yFraction = 0.4f)
        assertEquals(0.3f to 0.4f, onlyPortrait.positionFraction(portrait = true))
        assertNull(onlyPortrait.positionFraction(portrait = false))
    }

    @Test
    fun `half a coordinate is no coordinate, so a lone axis falls back to pixels`() {
        val halfP = FloatingButtonConfig(portraitXFraction = 0.5f, portraitYFraction = null)
        assertNull(halfP.positionFraction(portrait = true))
        val halfL = FloatingButtonConfig(landscapeYFraction = 0.5f, landscapeXFraction = null)
        assertNull(halfL.positionFraction(portrait = false))
    }

    @Test
    fun `reset clears every remembered fraction and touches nothing else`() {
        val placed = FloatingButtonConfig(x = 940, y = 1_600)
            .withPositionFraction(portrait = true, xFraction = 0.1f, yFraction = 0.2f)
            .withPositionFraction(portrait = false, xFraction = 0.8f, yFraction = 0.9f)
        val cleared = placed.clearedPositionFractions()
        assertNull(cleared.positionFraction(portrait = true))
        assertNull(cleared.positionFraction(portrait = false))
        assertEquals("the pixels stay put for the service to fall back on", placed.copy(
            portraitXFraction = null, portraitYFraction = null,
            landscapeXFraction = null, landscapeYFraction = null,
        ), cleared)
    }

    @Test
    fun `a fraction outside zero-to-one is pulled back onto the display when normalised`() {
        val wild = FloatingButtonConfig(
            portraitXFraction = 1.4f, portraitYFraction = -0.3f,
            landscapeXFraction = 2f, landscapeYFraction = 0.5f,
        ).normalised()
        assertEquals(1f to 0f, wild.positionFraction(portrait = true))
        assertEquals(1f to 0.5f, wild.positionFraction(portrait = false))
    }

    @Test
    fun `normalising leaves a null fraction null rather than snapping it to an edge`() {
        val normalised = FloatingButtonConfig().normalised()
        assertNull(normalised.portraitXFraction)
        assertNull(normalised.portraitYFraction)
        assertNull(normalised.landscapeXFraction)
        assertNull(normalised.landscapeYFraction)
    }

    /**
     * The three named sizes are points on the same `sizeDp` scale the slider drives, so a preset chip and
     * the slider can never claim different things: each preset's dp is inside the range, the chip lights up
     * only on an exact match, and a value between two presets belongs to no chip rather than being rounded.
     */
    @Test
    fun `each size preset sits on a real point of the size scale`() {
        for (preset in ButtonSizePreset.entries) {
            assertTrue("${preset.label} is off the scale", preset.sizeDp in FloatingButtonConfig.SIZE_RANGE)
            assertEquals("${preset.label} does not resolve to itself", preset, ButtonSizePreset.of(preset.sizeDp))
        }
    }

    @Test
    fun `the medium preset is the size the button ships at`() {
        assertEquals(ButtonSizePreset.MEDIUM.sizeDp, FloatingButtonConfig().sizeDp)
        assertEquals(ButtonSizePreset.MEDIUM, ButtonSizePreset.of(FloatingButtonConfig().sizeDp))
    }

    @Test
    fun `double-tap-for-panel is off by default so a single tap has no double-tap latency`() {
        // Spec §4 makes double-tap-to-open-panel an optional setting; the shipped default is off, so the
        // button fires a tap the instant the finger lifts (a null onDoubleTap handler) rather than waiting
        // out the double-tap window. normalised() must leave the flag alone — it is a plain preference.
        assertFalse(FloatingButtonConfig().doubleTapForPanel)
        assertTrue(FloatingButtonConfig(doubleTapForPanel = true).normalised().doubleTapForPanel)
        assertFalse(FloatingButtonConfig(doubleTapForPanel = false).normalised().doubleTapForPanel)
    }

    @Test
    fun `the presets span the whole scale, small at the floor and large at the ceiling`() {
        assertEquals(FloatingButtonConfig.MIN_SIZE_DP, ButtonSizePreset.SMALL.sizeDp)
        assertEquals(FloatingButtonConfig.MAX_SIZE_DP, ButtonSizePreset.LARGE.sizeDp)
    }

    @Test
    fun `a size between two presets matches no chip rather than rounding to a name`() {
        val between = (ButtonSizePreset.SMALL.sizeDp + ButtonSizePreset.MEDIUM.sizeDp) / 2
        assertTrue("test picked a value that is itself a preset", ButtonSizePreset.of(between) == null)
        assertNull(ButtonSizePreset.of(FloatingButtonConfig.MAX_SIZE_DP + 1))
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

    // ------------------------------------------------------------ spec §4: quick-sheet pin storage

    /**
     * The pins are stored as `QuickToggle` *names*, not the enum, because `QuickToggle` lives in the overlay
     * layer that depends on this model and not the reverse (same wall the button's `PositionFraction` hit).
     * So this config carries strings and the service resolves them; these tests fix the string-level rules
     * this layer *can* enforce — order, dedup, cap — and deliberately do not assert which names are valid,
     * because that is `QuickToggle.of`'s job at the boundary, not this layer's.
     */
    @Test
    fun `a fresh overlay config pins nothing, so the service falls back to the default set`() {
        // Empty is the "never set" signal — the service maps it to QuickToggle.DEFAULT_SET, matching how
        // QuickSheetPins.normalise collapses an empty list. A default list here would freeze the pin set at
        // this layer, which cannot see availability.
        assertEquals(emptyList<String>(), OverlayConfig().quickPins)
    }

    @Test
    fun `pins keep their order and are de-duplicated, the left-to-right order of the sheet`() {
        val messy = OverlayConfig(quickPins = listOf("STATS", "CROSSHAIR", "STATS", "HUD")).normalised()
        assertEquals(listOf("STATS", "CROSSHAIR", "HUD"), messy.quickPins)
    }

    @Test
    fun `pins are capped at what the sheet can hold`() {
        val overfull = OverlayConfig(
            quickPins = listOf("A", "B", "C", "D", "E", "F", "G", "H"),
        ).normalised()
        assertTrue("test must overflow the cap", 8 > OverlayConfig.MAX_QUICK_PINS)
        assertEquals(OverlayConfig.MAX_QUICK_PINS, overfull.quickPins.size)
        assertEquals(listOf("A", "B", "C", "D", "E", "F"), overfull.quickPins)
    }

    @Test
    fun `an unknown pin name survives this layer, to be dropped later by the service`() {
        // This layer cannot see QuickToggle, so it must not silently eat a name it does not recognise —
        // that resolution is QuickToggle.of's at the service boundary (mirrors readStats dropping unknowns).
        val withStranger = OverlayConfig(quickPins = listOf("STATS", "NOT_A_REAL_TOGGLE")).normalised()
        assertEquals(listOf("STATS", "NOT_A_REAL_TOGGLE"), withStranger.quickPins)
    }

    @Test
    fun `the sheet closes itself unless the user says otherwise`() {
        // Default on, because the sheet is summoned over a running game: a user who opens it mid-match and
        // then goes back to playing should not have to dismiss it. The flag is a plain boolean with no
        // clamp, so this and the round trip below are all there is to pin.
        assertTrue(OverlayConfig().quickAutoClose)
    }

    @Test
    fun `turning auto-close off survives normalisation`() {
        // `normalised()` rebuilds the config field by field, which is exactly how a new flag gets dropped
        // on the floor: the value would revert to its default on the next read and the setting would look
        // like it never saved.
        assertFalse(OverlayConfig(quickAutoClose = false).normalised().quickAutoClose)
    }

    @Test
    fun `the pin cap agrees with the overlay layer's authority of six`() {
        // MAX_QUICK_PINS is a mirror of QuickSheetPins.MAX_PINS; this pins the agreed value so a change to
        // one without the other is caught here rather than by a pin silently vanishing on a device.
        assertEquals(6, OverlayConfig.MAX_QUICK_PINS)
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

    // -------------------------------------------------------------- spec §3: pill display mode

    /**
     * The default is [PillDisplayMode.DETAILED] and it is load-bearing: an install updating over the old
     * app that never opens this setting must see the exact pill it had, and the old pill was the labelled
     * card. A default of COMPACT would silently reshape every existing user's overlay on update.
     */
    @Test
    fun `a fresh overlay config is the detailed pill, unchanged from before the mode existed`() {
        assertEquals(PillDisplayMode.DETAILED, OverlayConfig().displayMode)
    }

    @Test
    fun `an unknown or absent stored mode falls back to detailed rather than throwing`() {
        assertEquals(PillDisplayMode.DETAILED, PillDisplayMode.of(null))
        assertEquals(PillDisplayMode.DETAILED, PillDisplayMode.of(""))
        assertEquals(PillDisplayMode.DETAILED, PillDisplayMode.of("SOMETHING_A_LATER_VERSION_ADDED"))
    }

    @Test
    fun `each mode round-trips through its stored name`() {
        for (mode in PillDisplayMode.entries) {
            assertEquals(mode, PillDisplayMode.of(mode.name))
        }
    }

    /** normalising a config leaves the mode alone — it is a choice, not a value to clamp. */
    @Test
    fun `normalising keeps the chosen display mode`() {
        assertEquals(
            PillDisplayMode.COMPACT,
            OverlayConfig(displayMode = PillDisplayMode.COMPACT).normalised().displayMode,
        )
    }
}
