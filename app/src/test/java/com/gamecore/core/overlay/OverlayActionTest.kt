package com.gamecore.core.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OverlayAction.heldRow], which is the one fact two unrelated pieces of code both rest on.
 *
 * The button decides whether to offer a long press at all by asking whether this is null, and the service
 * decides which row to open by asking which value it is. Those live in different packages and neither can
 * see the other, so the property is the only thing keeping them in step — a tile that reported itself
 * holdable and then named no row would give the user a gesture that does nothing, and a tile that named the
 * wrong row would open the colour presets from the crosshair button. Both were real possibilities while this
 * was a single boolean shared by the gate and the handler, and these tests are what replaced that boolean's
 * one meaning with two checkable ones.
 */
class OverlayActionTest {

    @Test
    fun `exactly the colour and crosshair tiles open a row on a held press`() {
        val holdable = OverlayAction.entries.filter { it.heldRow != null }
        assertEquals(listOf(OverlayAction.CROSSHAIR, OverlayAction.COLOR), holdable)
    }

    @Test
    fun `each holdable tile opens its own row`() {
        assertEquals(HeldRow.COLOUR_PRESETS, OverlayAction.COLOR.heldRow)
        assertEquals(HeldRow.CROSSHAIR_QUICK_PICK, OverlayAction.CROSSHAIR.heldRow)
    }

    /**
     * No two tiles share a row.
     *
     * Stated as a property rather than by listing the pairs, because the failure it guards against is the
     * one a copied `when` branch produces: a third holdable tile added by copying the second and left
     * naming [HeldRow.CROSSHAIR_QUICK_PICK], which compiles, offers the gesture, and toggles the wrong row.
     */
    @Test
    fun `no row is opened by two different tiles`() {
        val rows = OverlayAction.entries.mapNotNull { it.heldRow }
        assertEquals(rows.distinct(), rows)
    }

    /**
     * The tiles whose chips open on a tap are deliberately not holdable.
     *
     * Aspect and refresh rate both drop a row of chips under the grid, so it would be reasonable to expect
     * them here. They are not, and the enum's KDoc says why: their chips are the whole of the control rather
     * than a shortcut into a screen, so there is nothing else their tap could mean. Asserting it keeps that
     * decision from being quietly reversed by someone who noticed the resemblance and not the reason.
     */
    @Test
    fun `the tiles that open chips on a tap are not also holdable`() {
        assertNull(OverlayAction.ASPECT.heldRow)
        assertNull(OverlayAction.REFRESH_RATE.heldRow)
    }

    /**
     * The Layout tile reads as on/off, and its tap opens nothing at all.
     *
     * Both halves are the design. The plate has to carry a state, because the tile is the only place inside
     * the panel that says which shape the panel is in, and a one-shot tile draws no plate. And it opens no
     * row, unlike the two tiles above: there are exactly two layouts, so chips behind a tap would be two
     * taps to change one bit — and the cost this tile exists to remove is a trip out of the game into the
     * settings screen, which two taps in the panel would only partly refund.
     */
    @Test
    fun `the layout tile is a toggle and opens no row`() {
        assertTrue(OverlayAction.PANEL_LAYOUT.isToggle)
        assertNull(OverlayAction.PANEL_LAYOUT.heldRow)
    }

    /**
     * Every holdable tile is a toggle, which is the constraint that makes the gesture worth having.
     *
     * A held press is only the right home for a row when the tap is already spoken for. If a one-shot tile
     * — Screenshot, say — grew a held row, the honest design would be to put that row on the tap instead,
     * because nothing else was competing for it. So this is not a coincidence between two properties; it is
     * the rule that decides which of the two gestures a new row belongs on.
     */
    @Test
    fun `a tile only hides a row behind a hold when its tap is already spent`() {
        for (action in OverlayAction.entries) {
            if (action.heldRow != null) {
                assertTrue("${action.name} hides a row behind a hold but its tap is free", action.isToggle)
            }
        }
    }
}
