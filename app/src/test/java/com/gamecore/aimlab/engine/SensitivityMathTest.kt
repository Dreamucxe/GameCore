package com.gamecore.aimlab.engine

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pipeline that turns a raw swipe into camera movement.
 *
 * §31 pins each stage in order, and every one has a way to go quietly wrong: a deadzone that clips a
 * diagonal to a cardinal, a response curve that stops being monotonic, an ADS multiplier that leaks into
 * hip-fire, an inversion that flips the wrong axis, or a smoother that never lets the first sample
 * through. Each test isolates one stage by neutralising the others (exponent 1, no deadzone, no smoothing)
 * so a failure names the stage that broke.
 */
class SensitivityMathTest {

    private val eps = 1e-4f

    private fun profile(
        cameraSensitivity: Float = 1f,
        adsMultiplier: Float = 1f,
        gyroSensitivity: Float = 1f,
        gyroAdsMultiplier: Float = 1f,
        deadzonePercent: Int = 0,
        smoothingPercent: Int = 0,
        responseExponent: Float = 1f,
        invertX: Boolean = false,
        invertY: Boolean = false,
    ) = SensitivityProfile(
        name = "test",
        cameraSensitivity = cameraSensitivity,
        adsMultiplier = adsMultiplier,
        gyroSensitivity = gyroSensitivity,
        gyroAdsMultiplier = gyroAdsMultiplier,
        deadzonePercent = deadzonePercent,
        smoothingPercent = smoothingPercent,
        responseExponent = responseExponent,
        invertX = invertX,
        invertY = invertY,
    )

    @Test
    fun `input below the deadzone produces exactly no movement`() {
        val math = SensitivityMath(profile(deadzonePercent = 20))
        val out = math.apply(rawX = 0.1f, rawY = 0.0f, aiming = false)
        assertEquals(0f, out.x, eps)
        assertEquals(0f, out.y, eps)
    }

    @Test
    fun `the response curve is monotonic in magnitude for linear, eased and sharpened exponents`() {
        for (exponent in listOf(1f, 2f, 0.5f)) {
            val math = SensitivityMath(profile(responseExponent = exponent))
            val small = math.apply(0.2f, 0f, aiming = false).x
            math.reset()
            val large = math.apply(0.6f, 0f, aiming = false).x
            assertTrue("exponent $exponent not monotonic: small=$small large=$large", abs(large) > abs(small))
            // Sign is preserved for a negative input too.
            math.reset()
            val negative = math.apply(-0.6f, 0f, aiming = false).x
            assertTrue("exponent $exponent lost the sign", negative < 0f)
        }
    }

    @Test
    fun `doubling camera sensitivity doubles the output`() {
        val single = SensitivityMath(profile(cameraSensitivity = 1f)).apply(0.4f, 0.3f, aiming = false)
        val doubled = SensitivityMath(profile(cameraSensitivity = 2f)).apply(0.4f, 0.3f, aiming = false)
        assertEquals(single.x * 2f, doubled.x, eps)
        assertEquals(single.y * 2f, doubled.y, eps)
    }

    @Test
    fun `the ADS multiplier applies only while aiming`() {
        val p = profile(adsMultiplier = 0.5f)
        val hip = SensitivityMath(p).apply(0.4f, 0f, aiming = false).x
        val ads = SensitivityMath(p).apply(0.4f, 0f, aiming = true).x
        assertEquals(hip * 0.5f, ads, eps)
    }

    @Test
    fun `the gyro flag selects the gyro sensitivity and gyro ADS pair`() {
        val p = profile(cameraSensitivity = 1f, gyroSensitivity = 3f, gyroAdsMultiplier = 0.5f)
        val gyroHip = SensitivityMath(p).apply(0.4f, 0f, aiming = false, gyro = true).x
        val cameraHip = SensitivityMath(p).apply(0.4f, 0f, aiming = false, gyro = false).x
        assertEquals(cameraHip * 3f, gyroHip, eps)

        val gyroAds = SensitivityMath(p).apply(0.4f, 0f, aiming = true, gyro = true).x
        assertEquals(gyroHip * 0.5f, gyroAds, eps)
    }

    @Test
    fun `inversion flips the sign of each axis independently`() {
        val base = SensitivityMath(profile()).apply(0.4f, 0.3f, aiming = false)
        val invX = SensitivityMath(profile(invertX = true)).apply(0.4f, 0.3f, aiming = false)
        val invY = SensitivityMath(profile(invertY = true)).apply(0.4f, 0.3f, aiming = false)
        assertEquals(-base.x, invX.x, eps)
        assertEquals(base.y, invX.y, eps)
        assertEquals(base.x, invY.x, eps)
        assertEquals(-base.y, invY.y, eps)
    }

    @Test
    fun `smoothing lets the first sample pass through and lags the second toward it`() {
        val math = SensitivityMath(profile(smoothingPercent = 50))
        val first = math.apply(0.6f, 0f, aiming = false)
        // With no history the first output passes through unsmoothed.
        val expectedRaw = SensitivityMath(profile()).apply(0.6f, 0f, aiming = false)
        assertEquals(expectedRaw.x, first.x, eps)

        // A second, smaller input is dragged between the previous output and the new raw value.
        val second = math.apply(0.2f, 0f, aiming = false)
        val newRaw = SensitivityMath(profile()).apply(0.2f, 0f, aiming = false).x
        assertTrue("second=${second.x} not between $newRaw and ${first.x}", second.x > newRaw && second.x < first.x)
    }

    @Test
    fun `reset clears the smoothing memory so the next sample passes through again`() {
        val math = SensitivityMath(profile(smoothingPercent = 50))
        math.apply(0.6f, 0f, aiming = false)
        math.reset()
        val afterReset = math.apply(0.2f, 0f, aiming = false).x
        val fresh = SensitivityMath(profile()).apply(0.2f, 0f, aiming = false).x
        assertEquals(fresh, afterReset, eps)
    }
}
