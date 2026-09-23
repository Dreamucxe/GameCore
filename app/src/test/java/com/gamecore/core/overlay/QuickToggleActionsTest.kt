package com.gamecore.core.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the quick-sheet pin → panel-action mapping (spec §4): a pin must do exactly what the full panel's
 * matching tile does. These tests fix each correspondence and, more importantly, the two invariants that
 * keep the sheet honest as the enums change — every pin resolves to an action, and no two pins collapse
 * onto the same action (which would make one pin silently shadow another's state).
 */
class QuickToggleActionsTest {

    @Test
    fun `each pin maps to the panel action that does the same thing`() {
        assertEquals(OverlayAction.PILL, QuickToggle.STATS.toOverlayAction())
        assertEquals(OverlayAction.CROSSHAIR, QuickToggle.CROSSHAIR.toOverlayAction())
        assertEquals(OverlayAction.HUD, QuickToggle.HUD.toOverlayAction())
        assertEquals(OverlayAction.SCREENSHOT, QuickToggle.SCREENSHOT.toOverlayAction())
        assertEquals(OverlayAction.RECORD, QuickToggle.RECORD.toOverlayAction())
        assertEquals(OverlayAction.FLASHLIGHT, QuickToggle.TORCH.toOverlayAction())
        assertEquals(OverlayAction.DO_NOT_DISTURB, QuickToggle.SILENCE.toOverlayAction())
        assertEquals(OverlayAction.ROTATION_LOCK, QuickToggle.ROTATION.toOverlayAction())
        assertEquals(OverlayAction.REFRESH_RATE, QuickToggle.REFRESH.toOverlayAction())
    }

    /**
     * Total by construction — the `when` has no `else`, so a new [QuickToggle] would not compile without a
     * branch. This asserts it at runtime too, so the invariant is documented as a test and not only as a
     * compiler property: every pin the enum offers resolves to some action.
     */
    @Test
    fun `every pin resolves to an action`() {
        for (toggle in QuickToggle.entries) {
            // Would throw if the when were non-exhaustive; the assertion keeps the intent legible.
            assertTrue(toggle.toOverlayAction() in OverlayAction.entries)
        }
    }

    /**
     * The map is injective across the nine pins: no two collapse to one action. If they did, the sheet would
     * light both pins from one action's state and route both taps to one handler — one pin shadowing another.
     */
    @Test
    fun `no two pins map to the same action`() {
        val actions = QuickToggle.entries.map { it.toOverlayAction() }
        assertEquals(QuickToggle.entries.size, actions.toSet().size)
    }
}
