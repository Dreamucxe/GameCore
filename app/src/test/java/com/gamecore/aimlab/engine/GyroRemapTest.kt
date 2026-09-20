package com.gamecore.aimlab.engine

import com.gamecore.aimlab.engine3d.GyroRemap
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Gyro axis remapping for all four display rotations (§3, §7).
 *
 * A physical turn to the player's right must turn the view right in every orientation. The test drives a
 * pure right-turn delta `(dx>0, dy=0)` and a pure look-up delta `(dx=0, dy>0)` through each rotation and
 * asserts the remapped axes carry the intent — the fix for "gyro feels wrong in landscape".
 */
class GyroRemapTest {

    private val eps = 1e-4f

    @Test
    fun `rotation 0 passes through unchanged`() {
        val (yaw, pitch) = GyroRemap.remap(1f, 0.5f, GyroRemap.ROTATION_0)
        assertEquals(1f, yaw, eps)
        assertEquals(0.5f, pitch, eps)
    }

    @Test
    fun `rotation 90 swaps axes so a physical turn still yaws`() {
        // Landscape (rotated 90° CCW): portrait vertical becomes yaw, portrait horizontal becomes pitch.
        val (yaw, pitch) = GyroRemap.remap(1f, 0f, GyroRemap.ROTATION_90)
        assertEquals(0f, yaw, eps)
        assertEquals(-1f, pitch, eps)
        val (yaw2, pitch2) = GyroRemap.remap(0f, 1f, GyroRemap.ROTATION_90)
        assertEquals(1f, yaw2, eps)
        assertEquals(0f, pitch2, eps)
    }

    @Test
    fun `rotation 180 negates both axes`() {
        val (yaw, pitch) = GyroRemap.remap(1f, 1f, GyroRemap.ROTATION_180)
        assertEquals(-1f, yaw, eps)
        assertEquals(-1f, pitch, eps)
    }

    @Test
    fun `rotation 270 is the mirror of 90`() {
        val (yaw, pitch) = GyroRemap.remap(1f, 0f, GyroRemap.ROTATION_270)
        assertEquals(0f, yaw, eps)
        assertEquals(1f, pitch, eps)
        val (yaw2, pitch2) = GyroRemap.remap(0f, 1f, GyroRemap.ROTATION_270)
        assertEquals(-1f, yaw2, eps)
        assertEquals(0f, pitch2, eps)
    }

    @Test
    fun `90 and 270 are opposites of each other`() {
        val a = GyroRemap.remap(0.7f, 0.3f, GyroRemap.ROTATION_90)
        val b = GyroRemap.remap(0.7f, 0.3f, GyroRemap.ROTATION_270)
        assertEquals(a.first, -b.first, eps)
        assertEquals(a.second, -b.second, eps)
    }

    @Test
    fun `an unknown rotation passes through rather than dropping input`() {
        val (yaw, pitch) = GyroRemap.remap(0.4f, 0.6f, 99)
        assertEquals(0.4f, yaw, eps)
        assertEquals(0.6f, pitch, eps)
    }
}
