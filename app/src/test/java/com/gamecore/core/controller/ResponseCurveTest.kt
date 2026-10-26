package com.gamecore.core.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseCurveTest {

    private val linear = ResponseCurveSettings(deadzone = 0f, kind = ResponseCurveKind.LINEAR, exponent = 2f)

    @Test
    fun `a reading inside the dead zone is centre`() {
        val settings = ResponseCurveSettings(deadzone = 0.15f, kind = ResponseCurveKind.LINEAR, exponent = 2f)
        val out = ResponseCurve.apply(StickReading(0.1f, 0.1f), settings)

        assertEquals(0f, out.x, 0.0001f)
        assertEquals(0f, out.y, 0.0001f)
        assertEquals(0f, ResponseCurve.magnitude(0.1f, settings), 0.0001f)
    }

    @Test
    fun `a linear curve without a dead zone returns the input`() {
        val out = ResponseCurve.apply(StickReading(0.6f, 0.8f), linear)

        assertEquals(0.6f, out.x, 0.0001f)
        assertEquals(0.8f, out.y, 0.0001f)
    }

    @Test
    fun `a radial dead zone preserves direction and removes only radius`() {
        val settings = ResponseCurveSettings(deadzone = 0.2f, kind = ResponseCurveKind.LINEAR, exponent = 2f)
        val out = ResponseCurve.apply(StickReading(0.36f, 0.48f), settings)
        val inputAngle = kotlin.math.atan2(0.48f, 0.36f)
        val outputAngle = kotlin.math.atan2(out.y, out.x)

        assertEquals(inputAngle, outputAngle, 0.0001f)
        assertEquals("the full vector is scaled, not each axis", 0.5f, out.magnitude, 0.0001f)
    }

    @Test
    fun `an exponential curve squares the travel after the dead zone`() {
        val settings = ResponseCurveSettings(deadzone = 0f, kind = ResponseCurveKind.EXPONENTIAL, exponent = 2f)
        val out = ResponseCurve.apply(StickReading(0.3f, 0.4f), settings)

        assertEquals("magnitude 0.5 becomes 0.25", 0.25f, out.magnitude, 0.0001f)
        assertEquals(0.15f, out.x, 0.0001f)
        assertEquals(0.2f, out.y, 0.0001f)
    }

    @Test
    fun `a curve still reaches full deflection`() {
        val settings = ResponseCurveSettings(deadzone = 0.2f, kind = ResponseCurveKind.EXPONENTIAL, exponent = 3f)
        val out = ResponseCurve.apply(StickReading(1f, 0f), settings)

        assertEquals(1f, out.magnitude, 0.0001f)
        assertEquals(1f, ResponseCurve.magnitude(1f, settings), 0.0001f)
    }

    @Test
    fun `a smoothstep curve is symmetric around half travel`() {
        assertEquals(0f, ResponseCurve.transfer(0f, ResponseCurveKind.SMOOTH, 4f), 0.0001f)
        assertEquals(0.5f, ResponseCurve.transfer(0.5f, ResponseCurveKind.SMOOTH, 4f), 0.0001f)
        assertEquals(1f, ResponseCurve.transfer(1f, ResponseCurveKind.SMOOTH, 4f), 0.0001f)
        assertTrue(
            "the first half is slower than linear",
            ResponseCurve.transfer(0.25f, ResponseCurveKind.SMOOTH, 4f) < 0.25f,
        )
    }

    @Test
    fun `an exponent of one is linear for both exponent curves`() {
        val t = 0.37f
        assertEquals(t, ResponseCurve.transfer(t, ResponseCurveKind.EXPONENTIAL, 1f), 0.0001f)
        assertEquals(t, ResponseCurve.transfer(t, ResponseCurveKind.LINEAR, 3f), 0.0001f)
    }

    @Test
    fun `settings are bounded rather than allowed to swallow the stick`() {
        val wild = ResponseCurveSettings(deadzone = 0.9f, kind = ResponseCurveKind.LINEAR, exponent = 12f)
        val safe = wild.sanitized()

        assertEquals(ResponseCurveSettings.MAX_DEADZONE, safe.deadzone, 0f)
        assertEquals(ResponseCurveSettings.MAX_EXPONENT, safe.exponent, 0f)
        assertEquals(0f, ResponseCurve.magnitude(0.5f, wild), 0f)
    }

    @Test
    fun `non-finite input is centre and out-of-range input is clamped`() {
        assertEquals(StickReading.CENTRE, ResponseCurve.apply(StickReading(Float.NaN, 0f), linear))
        val out = ResponseCurve.apply(StickReading(4f, 0f), linear)
        assertEquals(1f, out.x, 0.0001f)
        assertEquals(0f, ResponseCurve.magnitude(Float.NaN, linear), 0f)
    }
}
