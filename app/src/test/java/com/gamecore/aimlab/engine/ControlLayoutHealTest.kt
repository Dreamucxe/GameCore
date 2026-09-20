package com.gamecore.aimlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The self-heal and orientation behaviour of [ControlLayout] (§3, §4).
 *
 * These are the pure half of the persistence-bug fix: a layout that lost its controls rebuilds from its
 * finger-count preset without losing its name, a layout that already has controls is left alone, and the
 * landscape set is filled from the preset when missing. The repository's Room half is exercised by
 * [AimLabLayoutStoreTest] against an in-memory fake.
 */
class ControlLayoutHealTest {

    @Test
    fun `an empty layout heals to the three-finger preset keeping its name and id`() {
        val broken = ControlLayout(id = 7L, name = "3 finger", controls = emptyList())
        val healed = broken.healed()
        assertEquals("3 finger", healed.name)
        assertEquals(7L, healed.id)
        assertTrue("portrait was not rebuilt", healed.controls.isNotEmpty())
        assertTrue("landscape was not rebuilt", healed.landscapeControls.isNotEmpty())
        assertTrue(healed.controls.any { it.role == ControlRole.SHOOT })
    }

    @Test
    fun `a layout with controls keeps every one and only fills the missing landscape set`() {
        val portrait = ControlLayoutPresets.build(LayoutPreset.FOUR_FINGER).controls
        val layout = ControlLayout(id = 3L, name = "mine", controls = portrait, landscapeControls = emptyList())
        val healed = layout.healed()
        // Portrait is untouched — same controls, same order.
        assertEquals(portrait, healed.controls)
        // Landscape filled from the preset that matches the portrait count (4-finger → 7 controls).
        assertTrue(healed.landscapeControls.isNotEmpty())
        assertEquals("mine", healed.name)
    }

    @Test
    fun `a fully populated layout is returned unchanged`() {
        val full = ControlLayout.preset(LayoutPreset.FIVE_FINGER).copy(id = 1L, name = "keep")
        val healed = full.healed()
        assertEquals(full.controls, healed.controls)
        assertEquals(full.landscapeControls, healed.landscapeControls)
    }

    @Test
    fun `isEmpty is true only when both orientations are empty`() {
        assertTrue(ControlLayout(name = "x").isEmpty)
        assertFalse(ControlLayout.preset(LayoutPreset.TWO_FINGER).isEmpty)
    }

    @Test
    fun `control count maps to the right preset for heal`() {
        assertEquals(LayoutPreset.THREE_FINGER, LayoutPreset.forControlCount(0))
        assertEquals(LayoutPreset.TWO_FINGER, LayoutPreset.forControlCount(3))
        assertEquals(LayoutPreset.THREE_FINGER, LayoutPreset.forControlCount(5))
        assertEquals(LayoutPreset.FOUR_FINGER, LayoutPreset.forControlCount(7))
        assertEquals(LayoutPreset.FIVE_FINGER, LayoutPreset.forControlCount(9))
    }

    @Test
    fun `preset builds both orientations and every landscape set has a shoot and a stick`() {
        for (preset in listOf(
            LayoutPreset.TWO_FINGER,
            LayoutPreset.THREE_FINGER,
            LayoutPreset.FOUR_FINGER,
            LayoutPreset.FIVE_FINGER,
        )) {
            val layout = ControlLayout.preset(preset)
            assertTrue("$preset portrait empty", layout.controls.isNotEmpty())
            assertTrue("$preset landscape empty", layout.landscapeControls.isNotEmpty())
            assertTrue("$preset landscape has no shoot", layout.landscapeControls.any { it.role == ControlRole.SHOOT })
            assertTrue("$preset landscape has no stick", layout.landscapeControls.any { it.role == ControlRole.MOVE_STICK })
        }
    }

    @Test
    fun `enabledControlsFor returns the right orientation set`() {
        val layout = ControlLayout.preset(LayoutPreset.THREE_FINGER)
        assertEquals(
            layout.controls.filter { it.enabled },
            layout.enabledControlsFor(ControlOrientation.PORTRAIT),
        )
        assertEquals(
            layout.landscapeControls.filter { it.enabled },
            layout.enabledControlsFor(ControlOrientation.LANDSCAPE),
        )
    }
}
