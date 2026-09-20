package com.gamecore.data.database

import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.ControlOrientation
import com.gamecore.aimlab.engine.ControlRole
import com.gamecore.aimlab.engine.LayoutPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout↔row mapping that the persistence-bug fix hinges on (§bug-fix, §4).
 *
 * Room cannot run in a JVM unit test here, so the DAO's transaction is covered by
 * [com.gamecore.aimlab.engine.AimLabLayoutStoreTest] against an in-memory fake of the same contract; this
 * test pins the pure mapper both sides of that transaction call. The bug was a layout persisting with
 * zero control rows and the list counting "0 of 0", so the assertions here are: every control becomes a
 * row, the row count equals the control count, and a round-trip back through [AimLabMappers.toModel]
 * returns the same controls in both orientations — the single load path the list, editor and training
 * screen all share.
 */
class AimLabLayoutMapperTest {

    @Test
    fun `every control of both orientations becomes a row`() {
        val layout = ControlLayout.preset(LayoutPreset.FOUR_FINGER).copy(id = 4L, name = "4 finger")
        val rows = AimLabMappers.controlEntities(layout, layoutId = 4L)
        assertEquals(layout.controls.size + layout.landscapeControls.size, rows.size)
        // Each row is stamped with the layout id and a real orientation.
        assertTrue(rows.all { it.layoutId == 4L })
        assertEquals(layout.controls.size, rows.count { it.orientation == ControlOrientation.PORTRAIT.name })
        assertEquals(layout.landscapeControls.size, rows.count { it.orientation == ControlOrientation.LANDSCAPE.name })
    }

    @Test
    fun `a saved-then-loaded layout returns the same controls — the count matches`() {
        val original = ControlLayout.preset(LayoutPreset.THREE_FINGER).copy(id = 3L, name = "3 finger")
        val entity = AimLabMappers.toEntity(original, nowMillis = 1_000L)
        val rows = AimLabMappers.controlEntities(original, layoutId = entity.id)

        // What the one relation query hands back:
        val loaded = AimLabMappers.toModel(entity, rows)
        assertEquals("3 finger", loaded.name)
        assertEquals(original.controls.size, loaded.controls.size)
        assertEquals(original.landscapeControls.size, loaded.landscapeControls.size)
        assertEquals(original.controls.map { it.role }.toSet(), loaded.controls.map { it.role }.toSet())
        // The list's "N of M controls enabled" count comes from these real rows, not an empty stand-in.
        assertTrue("loaded layout has no enabled controls", loaded.enabledControls.isNotEmpty())
        assertEquals(
            original.enabledControls.size,
            loaded.enabledControls.size,
        )
    }

    @Test
    fun `pre-v9 rows with no orientation column value load as the portrait set`() {
        // Simulate rows written before schema 9: orientation defaulted to PORTRAIT by the migration.
        val layout = ControlLayout.preset(LayoutPreset.TWO_FINGER).copy(id = 2L, name = "old")
        val legacyRows = layout.controls.map {
            AimLabMappers.toEntity(it, 2L, ControlOrientation.PORTRAIT)
        }
        val loaded = AimLabMappers.toModel(AimLabMappers.toEntity(layout, 1L), legacyRows)
        assertEquals(layout.controls.size, loaded.controls.size)
        assertTrue("legacy rows must not populate landscape", loaded.landscapeControls.isEmpty())
    }

    @Test
    fun `an unknown role row is dropped, not guessed`() {
        val entity = AimLabMappers.toEntity(ControlLayout(id = 1L, name = "x"), 1L)
        val goodRow = AimLabMappers.toEntity(
            ControlLayout.preset(LayoutPreset.TWO_FINGER).controls.first { it.role == ControlRole.SHOOT },
            1L,
            ControlOrientation.PORTRAIT,
        )
        val badRow = goodRow.copy(role = "GHOST_ROLE")
        val loaded = AimLabMappers.toModel(entity, listOf(goodRow, badRow))
        assertEquals(1, loaded.controls.size)
        assertEquals(ControlRole.SHOOT, loaded.controls.first().role)
    }
}
