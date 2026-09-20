package com.gamecore.aimlab.ui.input

import com.gamecore.aimlab.engine.ControlRole
import com.gamecore.aimlab.engine.ControlWidget
import com.gamecore.aimlab.engine.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boundary where pixels become arena fractions and a finger lands on a control.
 *
 * The engine never sees a pixel, so this conversion is the only place a surface's size enters — a wrong
 * divisor or an unclamped fraction would send the engine a coordinate off the arena. The degenerate
 * width/height branches matter because a surface is measured as zero for a frame during layout, and a
 * divide-by-zero there would crash the very first draw. The overlap rule ([controlAt] picks the smallest
 * box) is what keeps a small button on top of a large stick reachable.
 */
class TouchMathTest {

    private val eps = 1e-4f

    @Test
    fun `a pointer maps to an arena fraction and clamps out-of-range pixels`() {
        val mid = TouchMath.toArena(px = 500f, py = 250f, width = 1000, height = 1000)
        assertEquals(0.5f, mid.x, eps)
        assertEquals(0.25f, mid.y, eps)

        val past = TouchMath.toArena(px = 2000f, py = -50f, width = 1000, height = 1000)
        assertEquals(1f, past.x, eps)
        assertEquals(0f, past.y, eps)
    }

    @Test
    fun `a zero-sized surface maps to the centre rather than dividing by zero`() {
        assertEquals(Vec2.CENTER, TouchMath.toArena(100f, 100f, width = 0, height = 1000))
        assertEquals(Vec2.CENTER, TouchMath.toArena(100f, 100f, width = 1000, height = 0))
    }

    @Test
    fun `a delta scales both axes by the width, and a zero surface yields no look`() {
        val look = TouchMath.deltaToLook(dpx = 100f, dpy = 200f, width = 1000, height = 2000)
        assertEquals(0.1f, look.x, eps)
        assertEquals(0.2f, look.y, eps) // scaled by width, not height
        assertEquals(Vec2(0f, 0f), TouchMath.deltaToLook(10f, 10f, width = 0, height = 100))
    }

    @Test
    fun `a point inside a control's box hits and a point outside does not`() {
        val control = ControlWidget(ControlRole.SHOOT, xFraction = 0.5f, yFraction = 0.5f, widthFraction = 0.2f, heightFraction = 0.2f)
        assertTrue(TouchMath.hitsControl(Vec2(0.55f, 0.45f), control))
        assertFalse(TouchMath.hitsControl(Vec2(0.8f, 0.5f), control))
    }

    @Test
    fun `the smallest enabled control containing the point wins when boxes overlap`() {
        val stick = ControlWidget(ControlRole.MOVE_STICK, xFraction = 0.5f, yFraction = 0.5f, widthFraction = 0.4f, heightFraction = 0.4f)
        val button = ControlWidget(ControlRole.SHOOT, xFraction = 0.5f, yFraction = 0.5f, widthFraction = 0.1f, heightFraction = 0.1f)
        val hit = TouchMath.controlAt(Vec2(0.5f, 0.5f), listOf(stick, button))
        assertSame(button, hit)
    }

    @Test
    fun `a disabled control is ignored and no match returns null`() {
        val disabled = ControlWidget(ControlRole.SHOOT, xFraction = 0.5f, yFraction = 0.5f, widthFraction = 0.2f, heightFraction = 0.2f, enabled = false)
        assertNull(TouchMath.controlAt(Vec2(0.5f, 0.5f), listOf(disabled)))
        assertNull(TouchMath.controlAt(Vec2(0.05f, 0.05f), listOf(disabled.copy(enabled = true))))
    }
}
