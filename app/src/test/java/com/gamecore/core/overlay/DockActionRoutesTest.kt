package com.gamecore.core.overlay

import com.gamecore.core.model.DockActionId
import com.gamecore.core.model.DockActionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dock control → behaviour mapping of [DockActionId.route] (§3.7.1, feature 5).
 *
 * [QuickToggleActionsTest] pins the same kind of thing for the quick sheet, and these tests are its
 * sibling with one extra job. The sheet's mapping lands on a single [OverlayAction], so "every pin resolves"
 * and "no two pins collide" is the whole contract. The dock's lands on a [DockRoute] with five shapes, so
 * there are invariants *between* a control's declared nature and the route it takes: a cell declared
 * [DockActionKind.TOGGLE] must end at an action that actually holds a state, a cell declaring
 * [DockActionId.opensAppScreen] must end at a screen and nothing else may, and the Refresh chip must not
 * end at the panel toggle of the same name. Each of those is a wiring mistake that compiles, draws, and is
 * only visible by pressing the chip over a live game — which is the reason they are asserted here instead.
 *
 * Pure JUnit on a plain JVM, no `Context` and no running overlay, because [DockActionRoutes] is pure: that
 * is exactly what naming the destination — rather than handing the service a lambda — bought.
 */
class DockActionRoutesTest {

    // --------------------------------------------------------------------- every control has a route

    /**
     * Total by construction — the `when` in [DockActionId.route] has no `else`, so a fourteenth-and-one
     * control could not compile without a branch. Asserted at runtime as well so the guarantee reads as a
     * promise of the suite and not only as a property of the compiler: no control the dock can draw is a
     * chip that does nothing.
     */
    @Test
    fun `every dock control has a route`() {
        for (id in DockActionId.entries) {
            assertNotNull("${id.name} is drawn in the panel with no route behind it", id.route())
        }
    }

    // --------------------------------------------------------------------------- the fourteen routes

    @Test
    fun `each control routes where the service must send it`() {
        assertEquals(DockRoute.Overlay(OverlayAction.CROSSHAIR), DockActionId.CROSSHAIR.route())
        // Stats and Wheels absorb a rename here rather than in either enum, since both persist by name.
        assertEquals(DockRoute.Overlay(OverlayAction.PILL), DockActionId.STATS.route())
        assertEquals(DockRoute.Overlay(OverlayAction.WHEEL), DockActionId.WHEELS.route())
        assertEquals(DockRoute.Overlay(OverlayAction.HUD), DockActionId.HUD.route())
        assertEquals(DockRoute.Overlay(OverlayAction.MAGNIFIER), DockActionId.MAGNIFIER.route())
        assertEquals(DockRoute.Overlay(OverlayAction.SCOUT), DockActionId.SCOUT.route())
        assertEquals(DockRoute.Overlay(OverlayAction.HUNT), DockActionId.HUNT.route())
        assertEquals(DockRoute.Overlay(OverlayAction.SCREENSHOT), DockActionId.SCREENSHOT.route())
        assertEquals(DockRoute.FullPanel, DockActionId.FULL_PANEL.route())
        assertEquals(DockRoute.NextRefreshRate, DockActionId.REFRESH_RATE.route())
        assertEquals(DockRoute.TriggerPoint, DockActionId.TRIGGER_POINT.route())
        assertEquals(DockRoute.Screen(DockScreen.SYSTEM_STATS), DockActionId.SYSTEM_STATS.route())
        assertEquals(DockRoute.Screen(DockScreen.SCREEN_EXTRACTION), DockActionId.SCREEN_EXTRACTION.route())
        assertEquals(DockRoute.Screen(DockScreen.TOUCH_SAMPLING), DockActionId.TOUCH_SAMPLING.route())
    }

    // --------------------------------------------------------------------------- one action per cell

    /**
     * The overlay half of the map is injective, for [QuickToggleActionsTest]'s reason made worse by the
     * dock's layout. Two dock cells landing on one [OverlayAction] would both be drawn from that action's
     * state and both route their tap to it, so the two plates would light and unlight together and a user
     * who tapped each in turn would double-toggle — the overlay back where it started, from two presses
     * that each looked like a change. The arrangement screen lets eight of the fourteen be on at once, so
     * the pair would sit in the same panel rather than being a theoretical collision.
     */
    @Test
    fun `no two controls drive the same overlay action`() {
        val actions = DockActionId.entries
            .map { it.route() }
            .filterIsInstance<DockRoute.Overlay>()
            .map { it.action }
        assertEquals(actions.distinct(), actions)
    }

    // ----------------------------------------------------------------------------- the three screens

    /**
     * Each [DockScreen] is owned by exactly one control.
     *
     * Two gates guard the screen chips — this mapping and the service's enum-to-token one — and this is the
     * one that can go stale silently, because an orphaned [DockScreen] still compiles on both sides of it.
     * A screen no id reaches is a destination the dock advertises nowhere, which is how a half-finished
     * chip gets shipped; two ids reaching one screen is the same double-wiring the test above forbids for
     * actions, with the user leaving their game twice to land in the same place.
     */
    @Test
    fun `every screen is reachable from exactly one control`() {
        for (screen in DockScreen.entries) {
            val owners = DockActionId.entries.filter { (it.route() as? DockRoute.Screen)?.screen == screen }
            assertEquals("$screen is not reached by exactly one dock control", 1, owners.size)
        }
    }

    // ---------------------------------------------------------------------------------- toggle cells

    /**
     * A toggle cell must end at an action that holds a state.
     *
     * [DockActionKind.TOGGLE] is what sorts a control into the panel's filled-with-a-tick grid rather than
     * its momentary chips, and the tick is read from the action's own on/off. Wire a toggle cell to a
     * one-shot — Screenshot, say — and the panel draws a plate whose state is read from an action that has
     * none: a cell that looks switchable and never changes, pressed repeatedly by a user who believes the
     * first press missed. The two facts live in different files and neither can see the other, so this is
     * the only thing holding them in step.
     */
    @Test
    fun `a toggle cell always routes to a toggle action`() {
        for (id in DockActionId.entries) {
            if (id.kind != DockActionKind.TOGGLE) continue
            val route = id.route()
            assertTrue(
                "${id.name} is a toggle cell but routes nowhere an action can be read from",
                route is DockRoute.Overlay,
            )
            val action = (route as DockRoute.Overlay).action
            assertTrue("${id.name} is a toggle cell wired to the one-shot ${action.name}", action.isToggle)
        }
    }

    // ------------------------------------------------------------------------------ leaving the game

    /**
     * [DockActionId.opensAppScreen] is the panel's promise to warn before the game is swapped out, and this
     * is both halves of it. A control that carries the flag and does not route to a [DockRoute.Screen]
     * warns about an exit that never happens; a control that routes to one without the flag swaps the game
     * away mid-match with no warning at all, which is the worse of the two and the reason the `false` case
     * is asserted rather than left implied.
     */
    @Test
    fun `exactly the controls that declare an exit route to a screen`() {
        for (id in DockActionId.entries) {
            val leaves = id.route() is DockRoute.Screen
            if (id.opensAppScreen) {
                assertTrue("${id.name} warns it leaves the game but routes to no screen", leaves)
            } else {
                assertFalse("${id.name} leaves the game without the panel warning first", leaves)
            }
        }
    }

    // ------------------------------------------------------------------------- the expand-a-row trap

    /**
     * The Refresh chip does **not** route to [OverlayAction.REFRESH_RATE], and this is the one assertion
     * the file was written around.
     *
     * The two share a name, a label and an obvious intent, so the wrong wiring is the one a reasonable
     * person writes. It would also compile, pass every other test here, and fail only in the hand: that
     * action is a toggle whose tap sets no rate — it flips `refreshRatesExpanded` to reveal a chip row
     * *inside the full control panel*. Tapped from the dock it would expand a row on a panel the dock is
     * not showing, so the user would see a chip that does nothing while state quietly changed behind it.
     * The dock instead does what the chip row is for, in one tap, through the service's own rate path.
     */
    @Test
    fun `the refresh chip does not route to the refresh toggle`() {
        assertEquals(DockRoute.NextRefreshRate, DockActionId.REFRESH_RATE.route())
        assertFalse(
            "the Refresh chip would only expand a chip row on a panel the dock is not showing",
            DockActionId.REFRESH_RATE.route() == DockRoute.Overlay(OverlayAction.REFRESH_RATE),
        )
    }

    /**
     * The same trap, stated as the rule rather than as the one case: no control may route to a panel toggle
     * whose tap opens a second surface instead of setting a state. Colour and Aspect are the other two, and
     * they are not in the dock's vocabulary today — which is exactly when a guard is worth writing, since
     * whoever adds them will be copying the branch above it.
     */
    @Test
    fun `no control routes to an action whose tap only opens a row`() {
        val actions = DockActionId.entries
            .map { it.route() }
            .filterIsInstance<DockRoute.Overlay>()
            .map { it.action }
        assertFalse(
            "Refresh expands a row on the full panel; route it to NextRefreshRate",
            OverlayAction.REFRESH_RATE in actions,
        )
        assertFalse(
            "Aspect expands a row on the full panel; it needs a route that applies a shape",
            OverlayAction.ASPECT in actions,
        )
        assertFalse(
            "Colour opens the editor from the full panel; it needs a route of its own",
            OverlayAction.COLOR in actions,
        )
    }

    // ---------------------------------------------------------------------------- stepping the rate

    /**
     * One press moves up one mode, from the bottom.
     *
     * The list is handed over unsorted on purpose: a display's mode list comes back in the platform's order,
     * not in ascending Hz, and a cycle that walked it as given would jump about. Sorting inside
     * [nextDockRate] is what makes "next" mean the next rate up rather than the next array slot.
     */
    @Test
    fun `the rate steps up through the modes the display offers`() {
        val offered = listOf(120f, 60f, 90f)
        assertEquals(60f, nextDockRate(current = null, offered = offered))
        assertEquals(90f, nextDockRate(current = 60f, offered = offered))
        assertEquals(120f, nextDockRate(current = 90f, offered = offered))
    }

    /**
     * Off the top it stops holding a rate at all, rather than wrapping to the bottom.
     *
     * The step that makes the control honest. `min_refresh_rate` left pinned outlives the session and costs
     * battery, so a dock that could set it must be able to unset it; wrapping 120 Hz back to 60 Hz would
     * leave a user who pinned from the dock with no way to stop pinning without opening the full panel.
     */
    @Test
    fun `stepping past the highest rate releases the display`() {
        assertNull(nextDockRate(current = 120f, offered = listOf(60f, 90f, 120f)))
    }

    /**
     * A measured rate still matches the mode it is: the comparison is a tolerance, not equality.
     *
     * `Display.Mode.getRefreshRate()` reports floats like 59.95998, and what the controller confirmed is
     * whatever the platform said last time. On equality this test's first case would restart the cycle at the
     * bottom instead of advancing, so a 60 Hz pin would be stuck one step behind for ever.
     */
    @Test
    fun `a measured rate matches the mode it belongs to`() {
        val offered = listOf(59.95998f, 89.94f, 119.98f)
        assertEquals(89.94f, nextDockRate(current = 60f, offered = offered))
        assertEquals(119.98f, nextDockRate(current = 90.001f, offered = offered))
    }

    /**
     * A pin that is no longer in the list restarts from the bottom instead of guessing.
     *
     * Reachable in two ways — an external display swapped under a running service, and a rate GameCore pinned
     * before a mode list the platform has since narrowed. Either way the held value names no offered mode, and
     * the only answer that can be justified is the first one the user can actually be given.
     */
    @Test
    fun `a stale pin restarts the cycle rather than reporting a position`() {
        assertEquals(60f, nextDockRate(current = 144f, offered = listOf(60f, 90f)))
    }

    /**
     * An empty list holds nothing, and says so.
     *
     * Unreachable through the dock — `DockAvailability` dims the control below two selectable rates, so the
     * press cannot land — which is exactly why it is pinned here: the guard is in another file and could be
     * relaxed by someone who never reads this one. Releasing is the safe answer either way; returning the
     * held rate would have the control silently re-apply a pin on a display with nothing to choose.
     */
    @Test
    fun `a display with no modes to offer is released rather than held`() {
        assertNull(nextDockRate(current = 60f, offered = emptyList()))
        assertNull(nextDockRate(current = null, offered = emptyList()))
    }
}
