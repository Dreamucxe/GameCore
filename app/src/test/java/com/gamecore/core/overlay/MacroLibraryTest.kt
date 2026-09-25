package com.gamecore.core.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure rules of [MacroLibrary] (§14), the sibling of [QuickSheetPins] and tested the same way: as
 * arithmetic on lists, on a plain JVM with no `Context`, no store and no running overlay.
 *
 * Two things separate these from the pin tests. A macro carries an ordered *set of actions* as well as a
 * place in the list, so the editing helpers come in two layers — the macro list and one macro's actions —
 * and both obey the same wall-not-wrap, no-op-when-the-rule-would-break contract the pin editor gives.
 * And a macro has a notion of *runnable* the pins do not: a name and at least one replayable action,
 * without which normalise drops it rather than draw a chip that does nothing (§32).
 */
class MacroLibraryTest {

    /** A macro built from a vararg of actions, so a test reads as its id, name and the steps it holds. */
    private fun macro(id: Long, name: String, vararg actions: OverlayAction) =
        Macro(id, name, actions.toList())

    // --- normalise --------------------------------------------------------------------------------

    @Test
    fun `normalise sanitises the name at the boundary`() {
        val out = MacroLibrary.normalise(listOf(macro(1L, "  Warm   Up  ", OverlayAction.PILL)))
        assertEquals(1, out.size)
        assertEquals("Warm Up", out[0].name)
    }

    @Test
    fun `normalise drops non-macroable and duplicate actions, keeping order and the first`() {
        val out = MacroLibrary.normalise(
            listOf(
                macro(
                    1L, "Mix",
                    OverlayAction.COLOR,       // barred
                    OverlayAction.PILL,
                    OverlayAction.HUD,
                    OverlayAction.PILL,         // duplicate of the first kept action
                    OverlayAction.OPEN_APP,     // barred
                ),
            ),
        )
        assertEquals(listOf(OverlayAction.PILL, OverlayAction.HUD), out[0].actions)
    }

    @Test
    fun `normalise drops a macro with no name or with nothing runnable left`() {
        val out = MacroLibrary.normalise(
            listOf(
                macro(1L, "Good", OverlayAction.PILL),
                macro(2L, "", OverlayAction.HUD),          // blank name
                macro(3L, "Barred", OverlayAction.COLOR),  // no macroable action survives the filter
            ),
        )
        assertEquals(1, out.size)
        assertEquals("Good", out[0].name)
    }

    @Test
    fun `normalise collapses macros sharing an id to the first`() {
        val out = MacroLibrary.normalise(
            listOf(
                macro(1L, "First", OverlayAction.PILL),
                macro(1L, "Second", OverlayAction.HUD),
            ),
        )
        assertEquals(1, out.size)
        assertEquals("First", out[0].name)
    }

    @Test
    fun `normalise keeps at most MAX_MACROS`() {
        val many = (1..MacroLibrary.MAX_MACROS + 2).map { macro(it.toLong(), "M$it", OverlayAction.PILL) }
        assertEquals(MacroLibrary.MAX_MACROS, MacroLibrary.normalise(many).size)
    }

    // --- nextId -----------------------------------------------------------------------------------

    @Test
    fun `nextId is one for an empty list and one past the largest id otherwise`() {
        assertEquals(1L, MacroLibrary.nextId(emptyList()))
        assertEquals(
            6L,
            MacroLibrary.nextId(
                listOf(macro(5L, "A", OverlayAction.PILL), macro(2L, "B", OverlayAction.HUD)),
            ),
        )
    }

    // --- add --------------------------------------------------------------------------------------

    @Test
    fun `add puts a runnable macro on the end`() {
        val current = listOf(macro(1L, "A", OverlayAction.PILL))
        val out = MacroLibrary.add(current, macro(2L, "B", OverlayAction.HUD))
        assertEquals(listOf(1L, 2L), out.map { it.id })
    }

    @Test
    fun `add stores the normalised macro`() {
        val out = MacroLibrary.add(
            emptyList(),
            macro(1L, "Dirty", OverlayAction.PILL, OverlayAction.PILL, OverlayAction.COLOR),
        )
        assertEquals(listOf(OverlayAction.PILL), out[0].actions)
    }

    @Test
    fun `add is a no-op when the list is full`() {
        val full = (1..MacroLibrary.MAX_MACROS).map { macro(it.toLong(), "M$it", OverlayAction.PILL) }
        assertSame(full, MacroLibrary.add(full, macro(99L, "Extra", OverlayAction.HUD)))
    }

    @Test
    fun `add is a no-op when the macro is not runnable`() {
        val current = listOf(macro(1L, "A", OverlayAction.PILL))
        assertSame(current, MacroLibrary.add(current, macro(2L, "", OverlayAction.HUD)))          // no name
        assertSame(current, MacroLibrary.add(current, macro(3L, "Barred", OverlayAction.COLOR)))  // no action
    }

    @Test
    fun `add is a no-op when the id is already present`() {
        val current = listOf(macro(1L, "A", OverlayAction.PILL))
        assertSame(current, MacroLibrary.add(current, macro(1L, "Clash", OverlayAction.HUD)))
    }

    // --- remove -----------------------------------------------------------------------------------

    @Test
    fun `remove takes the macro with the id out and leaves an absent id alone`() {
        val current = listOf(macro(1L, "A", OverlayAction.PILL), macro(2L, "B", OverlayAction.HUD))
        assertEquals(listOf(2L), MacroLibrary.remove(current, 1L).map { it.id })
        assertEquals(current, MacroLibrary.remove(current, 99L))
    }

    // --- move -------------------------------------------------------------------------------------

    @Test
    fun `move is wall-not-wrap and leaves an absent id alone`() {
        val current = listOf(
            macro(1L, "A", OverlayAction.PILL),
            macro(2L, "B", OverlayAction.HUD),
            macro(3L, "C", OverlayAction.RECORD),
        )
        assertEquals(listOf(2L, 1L, 3L), MacroLibrary.move(current, 1L, 1).map { it.id })   // towards the back
        assertEquals(listOf(1L, 3L, 2L), MacroLibrary.move(current, 3L, -1).map { it.id })  // towards the front
        assertSame(current, MacroLibrary.move(current, 1L, -1)) // off the front is a wall
        assertSame(current, MacroLibrary.move(current, 3L, 1))  // off the back is a wall
        assertSame(current, MacroLibrary.move(current, 99L, 1)) // absent id unchanged
    }

    // --- rename -----------------------------------------------------------------------------------

    @Test
    fun `rename sanitises the new name and leaves an absent id alone`() {
        val current = listOf(macro(1L, "Old", OverlayAction.PILL))
        assertEquals("New Name", MacroLibrary.rename(current, 1L, "  New   Name  ")[0].name)
        assertEquals(current, MacroLibrary.rename(current, 99L, "Ignored"))
    }

    // --- addAction --------------------------------------------------------------------------------

    @Test
    fun `addAction puts a macroable action on the end`() {
        val out = MacroLibrary.addAction(macro(1L, "M", OverlayAction.PILL), OverlayAction.HUD)
        assertEquals(listOf(OverlayAction.PILL, OverlayAction.HUD), out.actions)
    }

    @Test
    fun `addAction is a no-op at the action cap`() {
        val full = Macro(1L, "Full", OverlayAction.entries.filter { it.isMacroable })
        assertEquals(MacroLibrary.MAX_ACTIONS, full.actions.size)
        assertSame(full, MacroLibrary.addAction(full, OverlayAction.PILL))
    }

    @Test
    fun `addAction is a no-op for a non-macroable action`() {
        val m = macro(1L, "M", OverlayAction.PILL)
        assertSame(m, MacroLibrary.addAction(m, OverlayAction.COLOR))
    }

    @Test
    fun `addAction is a no-op when the action is already present`() {
        val m = macro(1L, "M", OverlayAction.PILL)
        assertSame(m, MacroLibrary.addAction(m, OverlayAction.PILL))
    }

    // --- removeAction -----------------------------------------------------------------------------

    @Test
    fun `removeAction takes an action out and leaves an absent one alone`() {
        val m = macro(1L, "M", OverlayAction.PILL, OverlayAction.HUD)
        assertEquals(listOf(OverlayAction.PILL), MacroLibrary.removeAction(m, OverlayAction.HUD).actions)
        assertEquals(m, MacroLibrary.removeAction(m, OverlayAction.RECORD))
    }

    // --- moveAction -------------------------------------------------------------------------------

    @Test
    fun `moveAction is wall-not-wrap and leaves an absent action alone`() {
        val m = macro(1L, "M", OverlayAction.PILL, OverlayAction.HUD, OverlayAction.RECORD)
        assertEquals(
            listOf(OverlayAction.HUD, OverlayAction.PILL, OverlayAction.RECORD),
            MacroLibrary.moveAction(m, OverlayAction.PILL, 1).actions,
        )
        assertEquals(
            listOf(OverlayAction.PILL, OverlayAction.RECORD, OverlayAction.HUD),
            MacroLibrary.moveAction(m, OverlayAction.RECORD, -1).actions,
        )
        assertEquals(m, MacroLibrary.moveAction(m, OverlayAction.PILL, -1))     // off the front is a wall
        assertEquals(m, MacroLibrary.moveAction(m, OverlayAction.RECORD, 1))    // off the back is a wall
        assertEquals(m, MacroLibrary.moveAction(m, OverlayAction.FLASHLIGHT, 1)) // absent action unchanged
    }

    // --- addableActions ---------------------------------------------------------------------------

    @Test
    fun `addableActions is the macroable actions a macro does not already hold`() {
        val m = macro(1L, "M", OverlayAction.PILL, OverlayAction.HUD)
        val addable = MacroLibrary.addableActions(m)
        assertEquals(MacroLibrary.MAX_ACTIONS - 2, addable.size)
        assertTrue(addable.all { it.isMacroable })
        assertFalse(addable.contains(OverlayAction.PILL))
        assertFalse(addable.contains(OverlayAction.HUD))
        assertFalse(addable.contains(OverlayAction.COLOR)) // a barred action is never offered
    }

    @Test
    fun `addableActions offers every macroable action for an empty macro`() {
        assertEquals(MacroLibrary.MAX_ACTIONS, MacroLibrary.addableActions(macro(1L, "M")).size)
    }
}
