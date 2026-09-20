package com.gamecore.aimlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-screen controls a finger presses, and the promise they stay reachable.
 *
 * §12 asks that a control can never be dragged (or imported) to a position where it is partly or fully off
 * the usable screen, and that a control cannot be faded to invisibility and lost. Those are the load-
 * bearing cases here: [ControlWidget.normalised] floors size and opacity, [ControlWidget.clampTo] keeps
 * the whole body inside the safe area (centring when it is too big to fit), and role uniqueness stops a
 * layout from growing two Shoot buttons. A degenerate safe area is rejected outright so the clamp math
 * never divides a negative range.
 */
class ControlLayoutTest {

    private val eps = 1e-4f

    @Test
    fun `normalising clamps size, floors opacity and keeps the centre in bounds`() {
        val tiny = ControlWidget(ControlRole.SHOOT, xFraction = 1.5f, yFraction = -0.2f, widthFraction = 0.001f, heightFraction = 0.99f, opacityPercent = 0).normalised()
        assertEquals(ControlWidget.MIN_SIZE, tiny.widthFraction, eps)
        assertEquals(ControlWidget.MAX_SIZE, tiny.heightFraction, eps)
        assertEquals(ControlWidget.MIN_OPACITY, tiny.opacityPercent)
        assertEquals(1f, tiny.xFraction, eps)
        assertEquals(0f, tiny.yFraction, eps)
    }

    @Test
    fun `a control near an edge is pushed in so its half-width stays inside the safe area`() {
        val safe = SafeArea(0.03f, 0.03f, 0.97f, 0.97f)
        val edge = ControlWidget(ControlRole.SHOOT, xFraction = 0.99f, yFraction = 0.5f, widthFraction = 0.14f, heightFraction = 0.14f)
        val clamped = edge.clampTo(safe)
        val halfW = clamped.widthFraction / 2f
        assertTrue("right edge off screen", clamped.xFraction + halfW <= safe.right + eps)
        assertTrue("pushed in from 0.99", clamped.xFraction < 0.99f)
    }

    @Test
    fun `a control wider than the safe area is centred on that axis`() {
        val safe = SafeArea(0.4f, 0.03f, 0.6f, 0.97f) // only 0.2 wide
        val wide = ControlWidget(ControlRole.MOVE_STICK, xFraction = 0.1f, yFraction = 0.5f, widthFraction = 0.40f, heightFraction = 0.14f)
        val clamped = wide.clampTo(safe)
        assertEquals((safe.left + safe.right) / 2f, clamped.xFraction, eps)
    }

    @Test
    fun `adding a control of an existing role replaces it rather than duplicating the role`() {
        val original = ControlWidget(ControlRole.SHOOT, xFraction = 0.8f, yFraction = 0.8f)
        val moved = ControlWidget(ControlRole.SHOOT, xFraction = 0.2f, yFraction = 0.2f)
        val layout = ControlLayout(name = "test", controls = listOf(original)).withControl(moved)
        assertEquals(1, layout.controls.count { it.role == ControlRole.SHOOT })
        assertEquals(0.2f, layout.controls.first { it.role == ControlRole.SHOOT }.xFraction, eps)
    }

    @Test
    fun `a preset yields a non-empty in-bounds layout with a shoot and a move stick`() {
        val layout = ControlLayout.preset(LayoutPreset.THREE_FINGER)
        assertTrue(layout.controls.isNotEmpty())
        assertNotNull(layout.controls.firstOrNull { it.role == ControlRole.SHOOT })
        assertNotNull(layout.controls.firstOrNull { it.role == ControlRole.MOVE_STICK })
        for (c in layout.controls) {
            assertTrue("size out of range: ${c.widthFraction}", c.widthFraction in ControlWidget.MIN_SIZE..ControlWidget.MAX_SIZE)
            assertTrue("size out of range: ${c.heightFraction}", c.heightFraction in ControlWidget.MIN_SIZE..ControlWidget.MAX_SIZE)
            assertTrue("x out of bounds: ${c.xFraction}", c.xFraction in 0f..1f)
            assertTrue("y out of bounds: ${c.yFraction}", c.yFraction in 0f..1f)
            assertTrue("opacity too low: ${c.opacityPercent}", c.opacityPercent >= ControlWidget.MIN_OPACITY)
        }
    }

    @Test
    fun `a degenerate safe area is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { SafeArea(left = 0.6f, top = 0.03f, right = 0.4f, bottom = 0.97f) }
        assertThrows(IllegalArgumentException::class.java) { SafeArea(left = 0.03f, top = 0.9f, right = 0.97f, bottom = 0.1f) }
    }
}
