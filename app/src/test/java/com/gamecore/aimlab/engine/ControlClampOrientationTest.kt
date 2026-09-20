package com.gamecore.aimlab.engine

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every control of both orientations stays fully inside the safe area after a clamp, including a cutout
 * inset (§4/§7). The clamp must keep a control's whole body in — not just its centre — in portrait and
 * landscape alike, so a rotation or a notch can never strand a button off the usable screen.
 */
class ControlClampOrientationTest {

    /** A safe area with a chunky left inset, standing in for a landscape display cutout. */
    private val cutoutSafeArea = SafeArea(left = 0.08f, top = 0.05f, right = 0.96f, bottom = 0.95f)

    private fun assertInside(layout: ControlLayout, safe: SafeArea) {
        (layout.controls + layout.landscapeControls).forEach { c ->
            val halfW = c.widthFraction / 2f
            val halfH = c.heightFraction / 2f
            assertTrue("${c.role} left edge outside", c.xFraction - halfW >= safe.left - 1e-4f)
            assertTrue("${c.role} right edge outside", c.xFraction + halfW <= safe.right + 1e-4f)
            assertTrue("${c.role} top edge outside", c.yFraction - halfH >= safe.top - 1e-4f)
            assertTrue("${c.role} bottom edge outside", c.yFraction + halfH <= safe.bottom + 1e-4f)
        }
    }

    @Test
    fun `every preset clamps fully inside a cutout safe area in both orientations`() {
        for (preset in listOf(
            LayoutPreset.TWO_FINGER,
            LayoutPreset.THREE_FINGER,
            LayoutPreset.FOUR_FINGER,
            LayoutPreset.FIVE_FINGER,
        )) {
            val clamped = ControlLayout.preset(preset).clampedTo(cutoutSafeArea)
            assertInside(clamped, cutoutSafeArea)
        }
    }

    @Test
    fun `clampedTo clamps the landscape set as well as the portrait set`() {
        // A control planted hard in the corner of both sets must be pulled inside both.
        val corner = ControlWidget(ControlRole.SHOOT, xFraction = 1f, yFraction = 1f, widthFraction = 0.2f, heightFraction = 0.2f)
        val layout = ControlLayout(
            name = "corner",
            controls = listOf(corner),
            landscapeControls = listOf(corner),
        ).clampedTo(cutoutSafeArea)
        assertInside(layout, cutoutSafeArea)
    }
}
