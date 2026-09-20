package com.gamecore.aimlab.engine3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first-person camera's look clamp, yaw wrap, movement bounds and the aim ray it produces.
 *
 * The clamp is the property that matters most: no sequence of look inputs may drive pitch past vertical
 * or let the player walk through a wall, because either would break the render and the aim model at once.
 */
class Camera3DTest {

    private val eps = 1e-3f

    @Test
    fun `pitch clamps just short of vertical however far the drag pushes`() {
        val cam = Camera3D()
        cam.applyLook(0f, 1_000f)
        assertEquals(Camera3D.PITCH_LIMIT, cam.pitchDegrees, eps)
        cam.applyLook(0f, -10_000f)
        assertEquals(-Camera3D.PITCH_LIMIT, cam.pitchDegrees, eps)
    }

    @Test
    fun `applyLook returns the actually-applied pitch delta after clamping`() {
        val cam = Camera3D(pitchDegrees = 88f)
        // Only 1 degree of headroom remains before the 89 limit.
        val applied = cam.applyLook(0f, 30f)
        assertEquals(1f, applied, eps)
        assertEquals(Camera3D.PITCH_LIMIT, cam.pitchDegrees, eps)
    }

    @Test
    fun `yaw wraps into a bounded range rather than drifting unbounded`() {
        val cam = Camera3D()
        cam.applyLook(350f, 0f)
        cam.applyLook(350f, 0f) // 700 total → wraps to -20
        assertTrue("yaw should stay bounded", cam.yawDegrees in -180f..180f)
        assertEquals(-20f, cam.yawDegrees, eps)
    }

    @Test
    fun `the aim ray starts at the eye and points along the look direction`() {
        val cam = Camera3D(position = Vec3(0f, 1.6f, 0f))
        val ray = cam.aimRay()
        assertEquals(Vec3(0f, 1.6f, 0f), ray.origin)
        assertEquals(-1f, ray.direction.z, eps)
    }

    @Test
    fun `movement is clamped inside the room and never escapes the box`() {
        val cam = Camera3D()
        val half = 6f
        // Try to walk far past the wall.
        cam.move(Vec3(100f, 0f, 0f), roomHalfExtent = half)
        assertTrue("x escaped the room", cam.position.x <= half - Camera3D.MOVE_MARGIN + eps)
        cam.move(Vec3(-1000f, 0f, -1000f), roomHalfExtent = half)
        assertTrue("x escaped the room", cam.position.x >= -(half - Camera3D.MOVE_MARGIN) - eps)
        assertTrue("z escaped the room", cam.position.z >= -(half - Camera3D.MOVE_MARGIN) - eps)
    }

    @Test
    fun `eye height changes only Y`() {
        val cam = Camera3D(position = Vec3(1f, 1.6f, 2f))
        cam.setEyeHeight(0.9f)
        assertEquals(1f, cam.position.x, eps)
        assertEquals(0.9f, cam.position.y, eps)
        assertEquals(2f, cam.position.z, eps)
    }

    @Test
    fun `reset returns the camera to the default standing pose`() {
        val cam = Camera3D()
        cam.applyLook(45f, 30f)
        cam.move(Vec3(2f, 0f, 2f), roomHalfExtent = 6f)
        cam.setEyeHeight(0.8f)
        cam.reset()
        assertEquals(0f, cam.yawDegrees, eps)
        assertEquals(0f, cam.pitchDegrees, eps)
        assertEquals(Camera3D.DEFAULT_EYE_HEIGHT, cam.position.y, eps)
        assertEquals(0f, cam.position.x, eps)
    }
}
