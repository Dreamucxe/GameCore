package com.gamecore.aimlab.engine3d

import com.gamecore.aimlab.engine.Difficulty
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Easy→Extreme progression must be a real difficulty curve, not a relabelling (§3, §16).
 *
 * Every spatial field is asserted to move monotonically harder across the four fixed levels: the sphere
 * shrinks, spawns further away, over a wider arc, moving faster, and the on-target window tightens. CUSTOM
 * is excluded — it resolves to the NORMAL baseline for the caller to overlay.
 */
class Difficulty3DTest {

    private val levels = listOf(Difficulty.EASY, Difficulty.NORMAL, Difficulty.HARD, Difficulty.EXTREME)
        .map { Difficulty3DParameters.forLevel(it) }

    private fun <T : Comparable<T>> assertStrictlyDecreasing(name: String, values: List<T>) {
        for (i in 1 until values.size) {
            assertTrue("$name not decreasing: ${values[i - 1]} -> ${values[i]}", values[i] < values[i - 1])
        }
    }

    private fun <T : Comparable<T>> assertStrictlyIncreasing(name: String, values: List<T>) {
        for (i in 1 until values.size) {
            assertTrue("$name not increasing: ${values[i - 1]} -> ${values[i]}", values[i] > values[i - 1])
        }
    }

    @Test
    fun `radius shrinks as difficulty rises`() =
        assertStrictlyDecreasing("targetRadius", levels.map { it.targetRadius })

    @Test
    fun `targets spawn further away as difficulty rises`() {
        assertStrictlyIncreasing("minDistance", levels.map { it.minDistance })
        assertStrictlyIncreasing("maxDistance", levels.map { it.maxDistance })
    }

    @Test
    fun `the spawn cone widens as difficulty rises`() {
        assertStrictlyIncreasing("maxYawDegrees", levels.map { it.maxYawDegrees })
        assertStrictlyIncreasing("maxPitchDegrees", levels.map { it.maxPitchDegrees })
    }

    @Test
    fun `tracked targets move faster as difficulty rises`() =
        assertStrictlyIncreasing("trackingSpeed", levels.map { it.trackingSpeed })

    @Test
    fun `the on-target window tightens as difficulty rises`() =
        assertStrictlyDecreasing("onTargetAngleDegrees", levels.map { it.onTargetAngleDegrees })

    @Test
    fun `custom resolves to the normal baseline`() {
        val custom = Difficulty3DParameters.forLevel(Difficulty.CUSTOM)
        val normal = Difficulty3DParameters.forLevel(Difficulty.NORMAL)
        assertTrue(custom == normal)
    }
}
