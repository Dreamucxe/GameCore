package com.gamecore.core.overlay

import com.gamecore.core.model.FloatingButtonConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The turbo-deck layout's two pure pieces: how wide the plate is, and which actions it draws where.
 *
 * The geometry is the part that can silently break a control. A plate that takes the whole screen is no
 * longer a panel beside the game, and one that falls below the action grid's floor makes its tiles too
 * small to hit even though every action is technically present. The second test is the same kind of
 * silence for the action set: the deck promotes several controls out of the flat grid, so an action
 * added later must still land in exactly one group or this layout loses its only tile.
 *
 * The drawing itself is checked on a device, not here. What a test can hold are the rules the drawing is
 * built on, and these are the ones with a wrong answer rather than a taste question.
 */
class OverlayTurboDeckTest {

    /**
     * The plate always leaves the screen's right-hand strip to the game.
     *
     * The exception is a screen too narrow to hold both the edge inset and [TURBO_DECK_MIN_GAME_DP]; no
     * arithmetic can leave a strip there, and the plate still draws at least one dp rather than a
     * negative width. Every normal phone is well past that bound.
     */
    @Test
    fun `the deck never takes the screen and leaves a strip of game`() {
        for (screenWidthDp in 2..2400) {
            val deck = turboDeckWidth(screenWidthDp)
            assertTrue(
                "deck width $deck is not positive at $screenWidthDp dp",
                deck >= 1,
            )
            assertTrue(
                "deck width $deck is wider than the $screenWidthDp dp screen",
                deck <= screenWidthDp,
            )
            if (screenWidthDp >= TURBO_DECK_EDGE_INSET_DP + TURBO_DECK_MIN_GAME_DP + 1) {
                val game = screenWidthDp - TURBO_DECK_EDGE_INSET_DP - deck
                assertTrue(
                    "deck $deck leaves only $game dp of game on a $screenWidthDp dp screen",
                    game >= TURBO_DECK_MIN_GAME_DP,
                )
            }
        }
    }

    /**
     * Once the screen can hold it, the plate is never narrower than the centered panel's own floor.
     *
     * That floor is what keeps the action grid two tiles across rather than one. Below it the deck is
     * allowed to shrink with the screen — the alternative is a plate wider than its margin permits —
     * which is why the assertion starts at the smallest width where the floor and the game strip both
     * fit.
     */
    @Test
    fun `a screen with room for both gets the action grid's floor`() {
        val fitsBoth = TURBO_DECK_EDGE_INSET_DP + TURBO_DECK_MIN_GAME_DP + FloatingButtonConfig.MIN_PANEL_WIDTH_DP
        for (screenWidthDp in fitsBoth..2400) {
            assertTrue(
                "deck width ${turboDeckWidth(screenWidthDp)} is below the action-grid floor at " +
                    "$screenWidthDp dp",
                turboDeckWidth(screenWidthDp) >= FloatingButtonConfig.MIN_PANEL_WIDTH_DP,
            )
        }
    }

    /** A tablet does not get a hand's width of plate: the reference's width stops growing. */
    @Test
    fun `the deck stops growing on a wide screen`() {
        for (screenWidthDp in 900..2400) {
            assertEquals(
                "deck grew past its cap at $screenWidthDp dp",
                TURBO_DECK_MAX_DP,
                turboDeckWidth(screenWidthDp),
            )
        }
    }

    /**
     * Every action is drawn exactly once across the deck's five groups.
     *
     * This is the test the layout's split exists for. The quick rail, the switch rows, the primary
     * button, the grid and the wide footer are five lists that must together be the whole enum — no
     * duplicate (two tiles doing the same thing) and no omission (a control that exists on the other
     * three layouts and vanishes on this one).
     */
    @Test
    fun `the five deck groups partition every action`() {
        val flat = TURBO_DECK_ACTION_GROUPS.flatten()
        assertEquals(
            "the deck draws a different set from OverlayAction.entries",
            OverlayAction.entries.toSet(),
            flat.toSet(),
        )
        assertEquals(
            "an action is drawn more than once by the deck",
            OverlayAction.entries.size,
            flat.size,
        )
        assertTrue(
            "the deck has an empty group",
            TURBO_DECK_ACTION_GROUPS.none { it.isEmpty() },
        )
    }

    /** The design's primary button carries the capture action, not a decorative "boost" tile. */
    @Test
    fun `the primary button is record`() {
        assertEquals(listOf(OverlayAction.RECORD), TURBO_DECK_ACTION_GROUPS[2])
    }

    /** The two quick tiles are the capture pair the design puts beside the switches. */
    @Test
    fun `the quick rail is the capture pair`() {
        assertEquals(
            listOf(OverlayAction.SCREENSHOT, OverlayAction.REPLAY),
            TURBO_DECK_ACTION_GROUPS[0],
        )
    }
}
