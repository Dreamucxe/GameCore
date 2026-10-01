package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dock's action vocabulary and the rules that arrange it (§3.7.1, features 4–6).
 *
 * [DockActionId] is persisted by *name*, and [DockActions] is the whole of what the customisation screen,
 * the panel and the preference store are allowed to know about arranging it — so these tests are the
 * contract all three depend on, asserted without a device. They sit in a sibling class to
 * [DockModelsTest] rather than inside it because that suite is about [DockConfig]'s clamps and its
 * position memory, while this one is about the list algebra.
 *
 * Three promises are what the sections below exist to hold. **Identity survives storage**: an id resolves
 * from its own stored name and nothing else, so a build that adds or removes an action cannot make an old
 * file resolve to whatever now sits at that ordinal. **An order is always complete**: whatever a stored
 * list was missing, held twice, or never heard of, what comes back out is one of each id exactly once —
 * feature 6's "a corrupted setting falls back to defaults", narrowed so the parts of the arrangement that
 * are fine are kept rather than thrown away with the parts that are not. **Hiding is not forgetting**:
 * switching a control off leaves its position alone, so switching it back on returns it to where the user
 * put it instead of appending it to the end.
 */
class DockActionsTest {

    // ------------------------------------------------------------------------------ identity by name

    @Test
    fun `every action resolves from its own stored name`() {
        for (id in DockActionId.entries) {
            assertEquals(id, DockActionId.of(id.name))
        }
    }

    /**
     * The null-and-drop half of the contract. A single stored *mode* resolves an unknown name to its
     * default, but a stored *list* must drop the entry instead — substituting a default would silently put
     * a control on the dock that the user never chose. Mirrors `QuickToggle.of`.
     */
    @Test
    fun `an unknown, differently cased, blank or absent name resolves to nothing`() {
        assertNull(DockActionId.of("NOT_A_REAL_ACTION"))
        assertNull(DockActionId.of("crosshair"))
        assertNull(DockActionId.of(""))
        assertNull(DockActionId.of(null))
    }

    // ------------------------------------------------------------------------------------- defaults

    /**
     * The upgrade promise. These five are exactly what the dock's hardcoded panel drew in 3.7.0 — three
     * toggles and two chips — so a user who updates into this release opens the panel they already had
     * rather than one with every control switched on.
     */
    @Test
    fun `the shipped arrangement is the five controls the dock already drew`() {
        assertEquals(
            listOf(
                DockActionId.CROSSHAIR,
                DockActionId.STATS,
                DockActionId.WHEELS,
                DockActionId.FULL_PANEL,
                DockActionId.SCREENSHOT,
            ),
            DockActionId.DEFAULT_VISIBLE,
        )
        assertEquals(
            DockActionId.DEFAULT_VISIBLE,
            DockActions.visible(DockActionId.DEFAULT_ORDER, DockActionId.DEFAULT_HIDDEN),
        )
    }

    @Test
    fun `the default order is every action exactly once, shown ones first`() {
        assertEquals(DockActionId.entries.size, DockActionId.DEFAULT_ORDER.size)
        assertEquals(DockActionId.entries.toSet(), DockActionId.DEFAULT_ORDER.toSet())
        assertEquals(DockActionId.DEFAULT_VISIBLE, DockActionId.DEFAULT_ORDER.take(DockActionId.DEFAULT_VISIBLE.size))
    }

    @Test
    fun `the default hidden set is exactly what the default order does not show`() {
        assertEquals(
            DockActionId.entries.filterNot { it in DockActionId.DEFAULT_VISIBLE }.toSet(),
            DockActionId.DEFAULT_HIDDEN,
        )
        assertTrue(DockActionId.DEFAULT_VISIBLE.none { it in DockActionId.DEFAULT_HIDDEN })
    }

    /** The shipped panel has to be drawable: a cap smaller than the default set would truncate it. */
    @Test
    fun `the shipped arrangement fits inside the cap`() {
        assertTrue(DockActionId.DEFAULT_VISIBLE.size <= DockActions.MAX_VISIBLE)
    }

    // -------------------------------------------------------------------------------- normaliseOrder

    @Test
    fun `an order missing an action this build added comes back complete`() {
        val stored = listOf(DockActionId.SCREENSHOT, DockActionId.CROSSHAIR)
        val normalised = DockActions.normaliseOrder(stored)
        assertEquals(stored, normalised.take(2))
        assertEquals(DockActionId.entries.size, normalised.size)
        assertEquals(DockActionId.entries.toSet(), normalised.toSet())
    }

    @Test
    fun `a duplicate is dropped and the first position is the one kept`() {
        val stored = listOf(DockActionId.STATS, DockActionId.CROSSHAIR, DockActionId.STATS)
        val normalised = DockActions.normaliseOrder(stored)
        assertEquals(listOf(DockActionId.STATS, DockActionId.CROSSHAIR), normalised.take(2))
        assertEquals(DockActionId.entries.size, normalised.size)
    }

    @Test
    fun `an empty order normalises to the whole vocabulary in declaration order`() {
        assertEquals(DockActionId.entries.toList(), DockActions.normaliseOrder(emptyList()))
    }

    // --------------------------------------------------------------------------------------- visible

    @Test
    fun `visible drops what the user switched off`() {
        val visible = DockActions.visible(
            DockActionId.DEFAULT_ORDER,
            DockActionId.DEFAULT_HIDDEN + DockActionId.WHEELS,
        )
        assertFalse(visible.contains(DockActionId.WHEELS))
        assertEquals(DockActionId.DEFAULT_VISIBLE.size - 1, visible.size)
    }

    /**
     * With nothing hidden the vocabulary is longer than the panel, and the cap is what keeps the window a
     * window rather than a scrolling list. Only reachable with an explicit empty hidden set — the shipped
     * default shows five, so the cap is not exercised by [DockConfig]'s own default.
     */
    @Test
    fun `visible stops at the cap when everything is switched on`() {
        val visible = DockActions.visible(DockActionId.DEFAULT_ORDER, emptySet())
        assertEquals(DockActions.MAX_VISIBLE, visible.size)
        assertEquals(DockActionId.DEFAULT_ORDER.take(DockActions.MAX_VISIBLE), visible)
    }

    /** The panel draws toggles and one-shot chips in two separate grids, and this is the split. */
    @Test
    fun `the two kinds split the way the shipped panel drew them`() {
        val toggles = DockActions.visibleOfKind(
            DockActionId.DEFAULT_ORDER,
            DockActionId.DEFAULT_HIDDEN,
            DockActionKind.TOGGLE,
        )
        val actions = DockActions.visibleOfKind(
            DockActionId.DEFAULT_ORDER,
            DockActionId.DEFAULT_HIDDEN,
            DockActionKind.ACTION,
        )
        assertEquals(listOf(DockActionId.CROSSHAIR, DockActionId.STATS, DockActionId.WHEELS), toggles)
        assertEquals(listOf(DockActionId.FULL_PANEL, DockActionId.SCREENSHOT), actions)
        // Together they are the shown set and nothing else — no control can fall between the two grids.
        assertEquals(
            DockActions.visible(DockActionId.DEFAULT_ORDER, DockActionId.DEFAULT_HIDDEN).size,
            toggles.size + actions.size,
        )
    }

    // ---------------------------------------------------------------------------------- show and hide

    @Test
    fun `show switches a control on`() {
        val hidden = DockActions.show(DockActionId.DEFAULT_ORDER, DockActionId.DEFAULT_HIDDEN, DockActionId.HUD)
        assertFalse(hidden.contains(DockActionId.HUD))
        assertTrue(DockActions.visible(DockActionId.DEFAULT_ORDER, hidden).contains(DockActionId.HUD))
    }

    @Test
    fun `showing something already on changes nothing, so a double tap is harmless`() {
        assertEquals(
            DockActionId.DEFAULT_HIDDEN,
            DockActions.show(DockActionId.DEFAULT_ORDER, DockActionId.DEFAULT_HIDDEN, DockActionId.CROSSHAIR),
        )
    }

    /**
     * The cap is refused here rather than absorbed by [DockActions.visible], which is the difference
     * between a screen that can say "the dock holds eight" and one where the user's last tap silently does
     * nothing.
     */
    @Test
    fun `show is refused once the panel is full`() {
        val order = DockActionId.DEFAULT_ORDER
        val hidden = order.drop(DockActions.MAX_VISIBLE).toSet()
        assertEquals(DockActions.MAX_VISIBLE, DockActions.visible(order, hidden).size)
        val next = order[DockActions.MAX_VISIBLE]
        assertEquals(hidden, DockActions.show(order, hidden, next))
    }

    @Test
    fun `hide switches a control off`() {
        val hidden = DockActions.hide(DockActionId.DEFAULT_HIDDEN, DockActionId.STATS)
        assertTrue(hidden.contains(DockActionId.STATS))
        assertFalse(DockActions.visible(DockActionId.DEFAULT_ORDER, hidden).contains(DockActionId.STATS))
    }

    /**
     * The point of storing a full order alongside a hidden *set*. A user who switches Stats off, leaves it
     * off for a week and switches it back on expects it second again, not appended after thirteen others.
     */
    @Test
    fun `hiding and showing again returns a control to where the user put it`() {
        val order = DockActionId.DEFAULT_ORDER
        val off = DockActions.hide(DockActionId.DEFAULT_HIDDEN, DockActionId.STATS)
        val backOn = DockActions.show(order, off, DockActionId.STATS)
        assertEquals(DockActionId.DEFAULT_HIDDEN, backOn)
        assertEquals(DockActionId.DEFAULT_VISIBLE, DockActions.visible(order, backOn))
    }

    // ------------------------------------------------------------------------------------------ move

    @Test
    fun `move trades places with the next control along`() {
        val moved = DockActions.move(DockActionId.DEFAULT_ORDER, emptySet(), DockActionId.STATS, 1)
        assertEquals(
            listOf(DockActionId.CROSSHAIR, DockActionId.WHEELS, DockActionId.STATS),
            moved.take(3),
        )
        assertEquals(DockActionId.entries.size, moved.size)
    }

    @Test
    fun `move the other way walks a control towards the front`() {
        val moved = DockActions.move(DockActionId.DEFAULT_ORDER, emptySet(), DockActionId.WHEELS, -1)
        assertEquals(
            listOf(DockActionId.CROSSHAIR, DockActionId.WHEELS, DockActionId.STATS),
            moved.take(3),
        )
    }

    /**
     * The reason [DockActions.move] takes the hidden set at all: the stored order carries switched-off ids
     * too, so swapping with the literal next entry would trade places with something invisible and the
     * arrow would look broken. Asserted on the *shown* list, which is what the user is actually watching.
     */
    @Test
    fun `move steps over the controls that are switched off`() {
        val order = DockActionId.DEFAULT_ORDER
        val hidden = DockActionId.DEFAULT_HIDDEN + DockActionId.WHEELS
        val before = DockActions.visible(order, hidden)
        val moved = DockActions.move(order, hidden, DockActionId.STATS, 1)
        val after = DockActions.visible(moved, hidden)

        // Stats was second of four shown and is now third — one *visible* place, not one list index.
        assertEquals(DockActionId.STATS, before[1])
        assertEquals(DockActionId.STATS, after[2])
        assertEquals(before.toSet(), after.toSet())
    }

    @Test
    fun `the ends are walls rather than a wrap`() {
        val order = DockActionId.DEFAULT_ORDER
        assertEquals(order, DockActions.move(order, emptySet(), order.first(), -1))
        assertEquals(order, DockActions.move(order, emptySet(), order.last(), 1))
    }

    @Test
    fun `moving a switched-off control does nothing`() {
        val order = DockActionId.DEFAULT_ORDER
        val hidden = DockActionId.DEFAULT_HIDDEN
        val off = hidden.first()
        assertEquals(order, DockActions.move(order, hidden, off, 1))
        assertEquals(order, DockActions.move(order, hidden, off, -1))
    }

    @Test
    fun `a move of zero places is not a move`() {
        val order = DockActionId.DEFAULT_ORDER
        assertEquals(order, DockActions.move(order, emptySet(), DockActionId.STATS, 0))
    }

    /** A partial order is still movable — it is normalised on the way in, so the index maths holds. */
    @Test
    fun `move works on an order that was stored incomplete`() {
        val stored = listOf(DockActionId.SCREENSHOT, DockActionId.CROSSHAIR)
        val moved = DockActions.move(stored, emptySet(), DockActionId.SCREENSHOT, 1)
        assertEquals(
            listOf(DockActionId.CROSSHAIR, DockActionId.SCREENSHOT),
            moved.take(2),
        )
        assertEquals(DockActionId.entries.size, moved.size)
    }

    // ------------------------------------------------------------------------------- parsing stored names

    @Test
    fun `a stored order parses by name and keeps the order it was written in`() {
        val names = listOf(DockActionId.SCREENSHOT.name, DockActionId.CROSSHAIR.name)
        assertEquals(
            listOf(DockActionId.SCREENSHOT, DockActionId.CROSSHAIR),
            DockActions.parseOrder(names).take(2),
        )
    }

    /**
     * What a downgrade, a hand-edited backup or a renamed action looks like on the way in. The junk name is
     * gone and the order is still complete, so the panel has something to draw either way.
     */
    @Test
    fun `an unknown name in a stored order is dropped and the rest completed`() {
        val parsed = DockActions.parseOrder(
            listOf(DockActionId.SCREENSHOT.name, "TELEPORT", DockActionId.CROSSHAIR.name),
        )
        assertEquals(
            listOf(DockActionId.SCREENSHOT, DockActionId.CROSSHAIR),
            parsed.take(2),
        )
        assertEquals(DockActionId.entries.toSet(), parsed.toSet())
        assertEquals(DockActionId.entries.size, parsed.size)
    }

    @Test
    fun `an unknown name in a stored hidden set is dropped`() {
        assertEquals(
            setOf(DockActionId.WHEELS),
            DockActions.parseHidden(listOf(DockActionId.WHEELS.name, "TELEPORT", "")),
        )
    }

    /**
     * Present-but-empty is a real state and must not be mistaken for absent. The store decides the
     * fall-back-to-defaults question *before* calling these, so an empty list here has to mean "the user
     * switched everything on", not "this file predates dock customisation".
     */
    @Test
    fun `an empty stored hidden set means everything is switched on`() {
        assertTrue(DockActions.parseHidden(emptyList()).isEmpty())
    }

    // ----------------------------------------------------------------------------------- the vocabulary

    /**
     * An action that opens an in-app screen cannot be a toggle: the dock has nothing to show an on/off
     * state against once the user has left the game. Worth asserting because the panel renders the two
     * kinds in different grids and a mislabelled entry would draw a tick that never changes.
     */
    @Test
    fun `anything that leaves the game to open a screen is a one-shot, not a toggle`() {
        for (id in DockActionId.entries.filter { it.opensAppScreen }) {
            assertEquals(DockActionKind.ACTION, id.kind)
        }
    }

    @Test
    fun `every action carries a label a panel can draw`() {
        for (id in DockActionId.entries) {
            assertTrue("${id.name} has no label", id.label.isNotBlank())
        }
    }

    /**
     * Nothing on the shipped dock takes the user out of the game. Screenshot is on it and is *not*
     * always available — it needs a capture capability, exactly as it did in 3.7.0 — but it fires in
     * place; a default panel that could throw the user into the app mid-match would be a worse surprise
     * than a chip that has to explain itself.
     */
    @Test
    fun `nothing shown by default leaves the game`() {
        for (id in DockActionId.DEFAULT_VISIBLE) {
            assertFalse("${id.name} is shown by default and opens a screen", id.opensAppScreen)
        }
    }
}
