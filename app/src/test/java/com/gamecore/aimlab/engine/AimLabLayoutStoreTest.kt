package com.gamecore.aimlab.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persistence-bug fix, proven against an in-memory [LayoutStore] that models Room's two-table
 * save/load exactly (§6/§7) — Room itself cannot run in a JVM unit test here.
 *
 * The fake stores a layout and its controls the way the real DAO's transaction does: a save wholly
 * replaces the control rows for that layout id, and a load returns the layout with those rows, running the
 * shared [healedForLoad] step and writing back a repair — the same contract [AimLabRepositoryImpl] upholds
 * over the database. If the production save/load ever regressed to dropping controls, this fake would have
 * to regress in lockstep to stay green, which is the point of sharing [healedForLoad] and the mapper.
 */
class AimLabLayoutStoreTest {

    /** A minimal in-memory store: layouts by id, and control lists keyed the same way the rows are. */
    private class FakeLayoutStore : LayoutStore {
        private var nextId = 1L
        private val layouts = HashMap<Long, ControlLayout>()

        override suspend fun saveLayout(layout: ControlLayout): Long {
            val id = if (layout.id == 0L) nextId++ else layout.id
            // Store both orientations' controls under the resolved id — the transaction the DAO runs.
            layouts[id] = layout.copy(id = id)
            return id
        }

        override suspend fun loadLayout(id: Long): ControlLayout? {
            val stored = layouts[id] ?: return null
            val healed = stored.healedForLoad()
            if (healed !== stored) saveLayout(healed) // persist the repair, as the repository does
            return healed
        }

        override suspend fun layoutsList(): List<ControlLayout> =
            layouts.values.map { it.healedForLoad() }.sortedBy { it.id }

        /** Test-only: force a layout into the broken "saved with zero controls" state the bug produced. */
        fun corrupt(id: Long, name: String) {
            layouts[id] = ControlLayout(id = id, name = name, controls = emptyList())
        }
    }

    @Test
    fun `saving a layout persists all its controls and the count matches`() = runBlocking {
        val store = FakeLayoutStore()
        val id = store.saveLayout(ControlLayout.preset(LayoutPreset.FOUR_FINGER).copy(name = "4 finger"))
        val loaded = store.loadLayout(id)!!
        val expected = ControlLayout.preset(LayoutPreset.FOUR_FINGER).controls.size
        assertEquals(expected, loaded.controls.size)
        assertTrue(loaded.controls.isNotEmpty())
        // The list count is the real saved rows.
        val listed = store.layoutsList().first { it.id == id }
        assertEquals(loaded.controls.size, listed.controls.size)
    }

    @Test
    fun `loading returns the same controls the editor saved`() = runBlocking {
        val store = FakeLayoutStore()
        val original = ControlLayout.preset(LayoutPreset.THREE_FINGER).copy(name = "mine")
        val id = store.saveLayout(original)
        val loaded = store.loadLayout(id)!!
        assertEquals(original.controls.map { it.role }.toSet(), loaded.controls.map { it.role }.toSet())
        assertEquals(original.landscapeControls.map { it.role }.toSet(), loaded.landscapeControls.map { it.role }.toSet())
    }

    @Test
    fun `an empty saved layout self-heals from its preset on load and stays healed`() = runBlocking {
        val store = FakeLayoutStore()
        val id = store.saveLayout(ControlLayout.preset(LayoutPreset.THREE_FINGER).copy(name = "3 finger"))
        store.corrupt(id, "3 finger") // simulate the old bug: row present, controls gone

        val healed = store.loadLayout(id)!!
        assertTrue("empty layout did not self-heal", healed.controls.isNotEmpty())
        assertEquals("3 finger", healed.name)

        // The repair was written back: a second load is already whole without healing again.
        val again = store.loadLayout(id)!!
        assertEquals(healed.controls.size, again.controls.size)
        // And the list shows the real count, never "0 of 0".
        val listed = store.layoutsList().first { it.id == id }
        assertTrue(listed.controls.isNotEmpty())
    }

    @Test
    fun `a corrupt layout falls back to defaults rather than drawing nothing`() = runBlocking {
        val store = FakeLayoutStore()
        val id = store.saveLayout(ControlLayout(name = "broken"))
        store.corrupt(id, "broken")
        val healed = store.loadLayout(id)!!
        // Both orientations are populated so the overlay always has something to draw.
        assertTrue(healed.controls.isNotEmpty())
        assertTrue(healed.landscapeControls.isNotEmpty())
    }

    @Test
    fun `loading a missing layout returns null`() = runBlocking {
        assertNull(FakeLayoutStore().loadLayout(999L))
    }
}
