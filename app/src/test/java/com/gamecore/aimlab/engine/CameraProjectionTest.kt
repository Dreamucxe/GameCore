package com.gamecore.aimlab.engine

import com.gamecore.aimlab.engine3d.CameraProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FOV derivation across the aspect ratios a phone actually presents (§2, §7).
 *
 * The property that matters: fixing the horizontal FOV and deriving the vertical from the aspect keeps
 * the horizontal turn constant, so aim feel is the same in portrait and landscape. Checked for 9:16,
 * 9:20, 16:9, 20:9 and 21:9, plus the round-trip and the spawn-visibility gate.
 */
class CameraProjectionTest {

    private val eps = 0.5f

    // width / height
    private val aspects = mapOf(
        "9:16 portrait" to 9f / 16f,
        "9:20 portrait" to 9f / 20f,
        "16:9 landscape" to 16f / 9f,
        "20:9 landscape" to 20f / 9f,
        "21:9 landscape" to 21f / 9f,
    )

    @Test
    fun `vertical fov is larger than horizontal in portrait and smaller in landscape`() {
        val h = 90f
        val portrait = CameraProjection.verticalFovDegrees(h, 9f / 16f)
        val landscape = CameraProjection.verticalFovDegrees(h, 16f / 9f)
        assertTrue("portrait vFOV should exceed hFOV", portrait > h)
        assertTrue("landscape vFOV should be below hFOV", landscape < h)
    }

    @Test
    fun `horizontal fov round-trips through vertical for every aspect`() {
        val h = 90f
        for ((name, aspect) in aspects) {
            val v = CameraProjection.verticalFovDegrees(h, aspect)
            val back = CameraProjection.horizontalFovDegrees(v, aspect)
            assertEquals("round-trip failed for $name", h, back, eps)
        }
    }

    @Test
    fun `a degenerate aspect does not produce NaN`() {
        val v = CameraProjection.verticalFovDegrees(90f, 0f)
        assertFalse("vFOV was NaN for a zero aspect", v.isNaN())
        assertTrue(v > 0f)
    }

    @Test
    fun `a target dead ahead is always within view`() {
        for ((name, aspect) in aspects) {
            assertTrue(
                "dead-ahead target rejected for $name",
                CameraProjection.isWithinView(0f, 0f, 90f, aspect),
            )
        }
    }

    @Test
    fun `a target past the vertical edge of a narrow portrait view is rejected`() {
        // Portrait 9:20 has a small vertical FOV; a pitch offset near the horizontal half-FOV is off-screen.
        val aspect = 9f / 20f
        val vHalf = CameraProjection.verticalFovDegrees(90f, aspect) / 2f
        assertFalse(
            "a target past the vertical edge should be rejected",
            CameraProjection.isWithinView(0f, vHalf + 5f, 90f, aspect),
        )
        assertTrue(
            "a target well inside the vertical range should be accepted",
            CameraProjection.isWithinView(0f, vHalf - 5f, 90f, aspect),
        )
    }
}
