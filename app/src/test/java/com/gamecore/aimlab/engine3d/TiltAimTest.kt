package com.gamecore.aimlab.engine3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The accelerometer-to-tilt convention: which way the axes point, and which samples are refused.
 *
 * A sign flip here would steer the view the wrong way, and a missing refusal would turn a swing or a
 * flat-lying phone into a violent aim jump. Both are pinned directly rather than inferred from a model.
 */
class TiltAimTest {

    private val eps = 1e-4f

    @Test
    fun `an upright portrait phone reads as the zero pose`() {
        val pose = TiltAim.read(0f, TiltAim.GRAVITY, 0f)
        assertNotNull(pose)
        val p = pose!!
        assertEquals(0f, p.steer, eps)
        assertEquals(0f, p.tip, eps)
    }

    @Test
    fun `banking the right edge down raises steer`() {
        val angle = 0.2f
        val pose = TiltAim.read(
            TiltAim.GRAVITY * sin(angle),
            TiltAim.GRAVITY * cos(angle),
            0f,
        )
        assertNotNull(pose)
        val p = pose!!
        assertEquals(angle, p.steer, eps)
        // Steering must not bleed into the tip axis.
        assertEquals(0f, p.tip, eps)
    }

    @Test
    fun `banking the left edge down lowers steer`() {
        // The same rotation with the opposite sign must produce the opposite steer, not the same one.
        val angle = 0.2f
        val pose = TiltAim.read(
            -TiltAim.GRAVITY * sin(angle),
            TiltAim.GRAVITY * cos(angle),
            0f,
        )
        assertNotNull(pose)
        val p = pose!!
        assertEquals(-angle, p.steer, eps)
        assertEquals(0f, p.tip, eps)
    }

    @Test
    fun `leaning the top edge away raises tip without steering`() {
        val angle = 0.3f
        val pose = TiltAim.read(
            0f,
            TiltAim.GRAVITY * cos(angle),
            TiltAim.GRAVITY * sin(angle),
        )
        assertNotNull(pose)
        val p = pose!!
        assertEquals(angle, p.tip, eps)
        // Tipping must not bleed into the steer axis.
        assertEquals(0f, p.steer, eps)
    }

    @Test
    fun `a swing far from gravity reads as nothing`() {
        assertNull(TiltAim.read(0f, 25f, 0f))
    }

    @Test
    fun `a screen that faces the sky has no in-plane direction`() {
        assertNull(TiltAim.read(0f, 0f, TiltAim.GRAVITY))
    }

    @Test
    fun `a screen-plane projection below the floor is rejected`() {
        // Down is (0, -0.19, -0.9817): the in-plane part is 0.19, just under MIN_SCREEN_PROJECTION.
        assertNull(
            TiltAim.read(
                0f,
                0.19f * TiltAim.GRAVITY,
                0.9817f * TiltAim.GRAVITY,
            ),
        )
    }

    @Test
    fun `a screen-plane projection above the floor is accepted`() {
        val inPlane = 0.21f
        val normal = sqrt(1f - inPlane * inPlane)
        val pose = TiltAim.read(
            0f,
            inPlane * TiltAim.GRAVITY,
            normal * TiltAim.GRAVITY,
        )
        assertNotNull(pose)
        assertEquals(0f, pose!!.steer, eps)
    }

    @Test
    fun `a non-finite or zero sample reads as nothing`() {
        assertNull(TiltAim.read(Float.NaN, TiltAim.GRAVITY, 0f))
        assertNull(TiltAim.read(Float.POSITIVE_INFINITY, TiltAim.GRAVITY, 0f))
        assertNull(TiltAim.read(0f, 0f, 0f))
    }

    @Test
    fun `delta wraps a steer jump across the seam into the short way round`() {
        val (dx, dy) = TiltAim.delta(TiltPose(3f, 0f), TiltPose(-3f, 0f))
        assertEquals(-6f + 2f * PI.toFloat(), dx, eps)
        assertEquals(0f, dy, eps)
    }

    @Test
    fun `delta leaves a small step untouched`() {
        val (dx, dy) = TiltAim.delta(TiltPose(0.1f, -0.4f), TiltPose(0.35f, -0.1f))
        assertEquals(0.25f, dx, eps)
        assertEquals(0.3f, dy, eps)
    }

    @Test
    fun `wrap folds a full turn in either direction`() {
        assertEquals(0.5f, TiltAim.wrap(0.5f + 2f * PI.toFloat()), eps)
        assertEquals(-0.5f, TiltAim.wrap(-0.5f - 2f * PI.toFloat()), eps)
    }
}
