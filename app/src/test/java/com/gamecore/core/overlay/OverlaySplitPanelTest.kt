package com.gamecore.core.overlay

import com.gamecore.core.model.FloatingButtonConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The split layout's plate arithmetic, which is the whole of what makes it have no width setting.
 *
 * A separate class from [OverlayControlPanelTest] for the same reason [OverlaySplitPanel] is a separate
 * composable from [OverlayControlPanel]: that file's subject is a width the user stored and the tiles that
 * reflow inside it, and this one has neither. [splitPlateWidth] is the only piece of either layout that is
 * a function of the *screen*, and the bugs below are the ones it exists to prevent — two plates that meet
 * in the middle and hide the game the layout was built to show, a plate too narrow to hold a grid, and a
 * plate wider than half the screen, which on a phone would push the second one off the right edge entirely.
 *
 * The screen widths here are dp, not pixels: 360 is a phone in portrait, 800 the same phone in landscape,
 * and 1280 a tablet. They are the three examples [splitPlateWidth]'s own KDoc reasons about, so they are
 * asserted exactly — if the fraction changes, the documented figures have to change with it.
 */
class OverlaySplitPanelTest {

    /**
     * Portrait on a phone: the floor wins and the game is a strip.
     *
     * The interesting case, because it is the one where the rules disagree. A third of 360 dp is 115, which
     * is below [FloatingButtonConfig.MIN_PANEL_WIDTH_DP], so the floor raises it to 144 and the game keeps
     * 72 dp — half of [SPLIT_MIN_GAME_DP] rather than the whole of it. That is the ordering rule 3 of the
     * KDoc argues for, asserted rather than described: a plate that cannot hold two tiles across is a worse
     * outcome than a narrow strip of visible game.
     */
    @Test
    fun `portrait on a phone is bound by the grid floor and not by the game gap`() {
        val plate = splitPlateWidth(360)
        assertEquals(FloatingButtonConfig.MIN_PANEL_WIDTH_DP, plate)
        assertEquals(72, 360 - plate * 2)
        assertTrue("a $plate dp plate should hold a grid", actionsPerRow(plate) >= MIN_ACTIONS_PER_ROW)
    }

    /** Landscape on a phone: the fraction is what binds, and the game keeps a usable third. */
    @Test
    fun `landscape on a phone gets the third each the layout is for`() {
        val plate = splitPlateWidth(800)
        assertEquals(256, plate)
        assertEquals(288, 800 - plate * 2)
    }

    /**
     * A tablet: the model's own ceiling binds, not the fraction.
     *
     * A third of 1280 dp is 410, under [FloatingButtonConfig.MAX_PANEL_WIDTH_DP], so the fraction still
     * decides here. The ceiling starts to bind past 1500 dp, which the last assertion checks — the point
     * being that a very wide screen gets a usable plate and a very wide gap, rather than a third of the
     * screen with a hand's width of empty plate in it.
     */
    @Test
    fun `a tablet is bound by the fraction and a very wide screen by the model's ceiling`() {
        assertEquals(410, splitPlateWidth(1280))
        assertEquals(FloatingButtonConfig.MAX_PANEL_WIDTH_DP, splitPlateWidth(2400))
    }

    /**
     * The overlap bug: two plates side by side can never be wider than the screen holding them.
     *
     * The failure this rules out is silent and total. The plates are laid out in a [androidx.compose.foundation.layout.Row]
     * with the game weighted between them, so a plate of more than half the screen does not clip — the row
     * simply runs out of width and the second plate is drawn past the right edge of a window that cannot
     * scroll sideways, taking every control with it.
     */
    @Test
    fun `two plates never take more room than the screen has`() {
        for (screenWidthDp in 2..2400) {
            val plate = splitPlateWidth(screenWidthDp)
            assertTrue("two $plate dp plates on a $screenWidthDp dp screen", plate * 2 <= screenWidthDp)
        }
    }

    /** Every plate the layout can draw is a plate the model would have accepted as a panel width. */
    @Test
    fun `a plate on any real screen is a width the model allows`() {
        for (screenWidthDp in 288..2400) {
            val plate = splitPlateWidth(screenWidthDp)
            assertTrue(
                "$plate dp at $screenWidthDp dp is outside ${FloatingButtonConfig.PANEL_WIDTH_RANGE}",
                plate in FloatingButtonConfig.PANEL_WIDTH_RANGE,
            )
        }
    }

    /**
     * The gap rule, stated as the inequality rule 2 of the KDoc claims, over the screens where it is
     * reachable at all.
     *
     * From 480 dp up the fraction alone clears the floor, so nothing overrules the gap and the game is
     * guaranteed its [SPLIT_MIN_GAME_DP]. Below that the floor may win, which the portrait test above
     * asserts instead of this one — the two together are the whole of the ordering.
     */
    @Test
    fun `every screen wide enough to honour it keeps the game its minimum strip`() {
        for (screenWidthDp in 480..2400) {
            val gap = screenWidthDp - splitPlateWidth(screenWidthDp) * 2
            assertTrue("$gap dp of game on a $screenWidthDp dp screen", gap >= SPLIT_MIN_GAME_DP)
        }
    }

    /** A larger screen never produces a narrower plate, which is what makes a rotation not shrink the panel. */
    @Test
    fun `a wider screen never gets a narrower plate`() {
        var previous = splitPlateWidth(0)
        for (screenWidthDp in 0..2400) {
            val plate = splitPlateWidth(screenWidthDp)
            assertTrue("$plate dp at $screenWidthDp dp, after $previous", plate >= previous)
            previous = plate
        }
    }

    /**
     * A screen width the platform should never report, answered rather than returned as a negative.
     *
     * The service reads this from [com.gamecore.core.overlay.OverlayWindows]' measured frame and falls back
     * to a constant when the frame is unmeasured, so a zero should not arrive — but the arithmetic subtracts
     * [SPLIT_MIN_GAME_DP] before it clamps, and a negative width handed to `Modifier.width` is an exception
     * rather than a small plate. One dp is the honest answer: degenerate, drawable, and not a crash.
     */
    @Test
    fun `an impossible screen width is clamped rather than returned negative`() {
        assertTrue("${splitPlateWidth(0)} dp on a zero-width screen", splitPlateWidth(0) >= 1)
        assertTrue("${splitPlateWidth(-100)} dp on a negative screen", splitPlateWidth(-100) >= 1)
    }
}
