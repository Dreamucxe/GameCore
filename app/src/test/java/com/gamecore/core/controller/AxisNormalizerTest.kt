package com.gamecore.core.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisNormalizerTest {

    private val standard = AxisRange(minimum = -1f, maximum = 1f)
    private val driver = AxisRange(minimum = -128f, maximum = 127f)
    private val asymmetric = AxisRange(minimum = -0.8f, maximum = 1f)
    private val trigger = AxisRange(minimum = 0f, maximum = 1f)

    @Test
    fun `signed axis maps both sides of centre using the declared range`() {
        assertEquals(0f, AxisNormalizer.signed(0f, standard), 0.0001f)
        assertEquals(0.5f, AxisNormalizer.signed(0.5f, standard), 0.0001f)
        assertEquals(-0.5f, AxisNormalizer.signed(-0.5f, standard), 0.0001f)
        assertEquals(1f, AxisNormalizer.signed(1f, standard), 0.0001f)
        assertEquals(-1f, AxisNormalizer.signed(-1f, standard), 0.0001f)
    }

    @Test
    fun `a raw driver range is normalised by the half it is on, not by its span`() {
        assertEquals(0.5f, AxisNormalizer.signed(63.5f, driver), 0.001f)
        assertEquals(-0.5f, AxisNormalizer.signed(-64f, driver), 0.001f)
        assertEquals(1f, AxisNormalizer.signed(127f, driver), 0.001f)
        assertEquals(-1f, AxisNormalizer.signed(-128f, driver), 0.001f)
    }

    @Test
    fun `an asymmetric stick range keeps full travel on both sides`() {
        assertEquals(1f, AxisNormalizer.signed(1f, asymmetric), 0.0001f)
        assertEquals(-1f, AxisNormalizer.signed(-0.8f, asymmetric), 0.0001f)
        assertEquals(0.4f, AxisNormalizer.signed(0.4f, asymmetric), 0.0001f)
    }

    @Test
    fun `values beyond the declared range clamp instead of producing an impossible reading`() {
        assertEquals(1f, AxisNormalizer.signed(4f, standard), 0.0001f)
        assertEquals(-1f, AxisNormalizer.signed(-4f, standard), 0.0001f)
    }

    @Test
    fun `the driver flat radius is centre, and a value outside it is not rescaled`() {
        val flat = AxisRange(minimum = -1f, maximum = 1f, flat = 0.1f)

        assertEquals("inside the declared dead zone", 0f, AxisNormalizer.signed(0.05f, flat), 0f)
        assertEquals("at the edge", 0f, AxisNormalizer.signed(0.1f, flat), 0f)
        assertEquals("outside it the reading is unchanged", 0.25f, AxisNormalizer.signed(0.25f, flat), 0.0001f)
    }

    @Test
    fun `an unusable range or a non-finite reading is centre, never a guess`() {
        assertEquals(0f, AxisNormalizer.signed(1f, AxisRange(1f, 1f)), 0f)
        assertEquals(0f, AxisNormalizer.signed(Float.NaN, standard), 0f)
        assertEquals(0f, AxisNormalizer.signed(Float.POSITIVE_INFINITY, standard), 0f)
        assertEquals(0f, AxisNormalizer.trigger(1f, AxisRange(2f, 1f)), 0f)
    }

    @Test
    fun `a trigger maps its own range to zero through one`() {
        assertEquals(0f, AxisNormalizer.trigger(0f, trigger), 0.0001f)
        assertEquals(0.25f, AxisNormalizer.trigger(0.25f, trigger), 0.0001f)
        assertEquals(1f, AxisNormalizer.trigger(1f, trigger), 0.0001f)
        assertEquals(1f, AxisNormalizer.trigger(3f, trigger), 0.0001f)
    }

    @Test
    fun `a trigger flat radius is removed from the start and the rest is stretched`() {
        val halfPressed = AxisRange(minimum = 0f, maximum = 1f, flat = 0.2f)

        assertEquals(0f, AxisNormalizer.trigger(0.1f, halfPressed), 0f)
        assertEquals(0f, AxisNormalizer.trigger(0.2f, halfPressed), 0f)
        assertEquals("half of the usable travel", 0.5f, AxisNormalizer.trigger(0.6f, halfPressed), 0.0001f)
        assertEquals(1f, AxisNormalizer.trigger(1f, halfPressed), 0.0001f)
    }

    @Test
    fun `the flat figure used as a threshold is a fraction of the shorter side`() {
        assertEquals(0.1f, AxisNormalizer.signedFlat(AxisRange(-1f, 1f, flat = 0.1f)), 0.0001f)
        assertEquals(10f / 127f, AxisNormalizer.signedFlat(AxisRange(-128f, 127f, flat = 10f)), 0.0001f)
        assertEquals(
            "an asymmetric range uses its shorter half",
            0.125f,
            AxisNormalizer.signedFlat(AxisRange(-0.8f, 1f, flat = 0.1f)),
            0.0001f,
        )
        assertEquals(0.1f, AxisNormalizer.triggerFlat(AxisRange(0f, 1f, flat = 0.1f)), 0.0001f)
        assertEquals(
            "a range around a non-zero centre uses its shorter half",
            0.2f,
            AxisNormalizer.signedFlat(AxisRange(0f, 1f, flat = 0.1f)),
            0.0001f,
        )
        assertEquals(0f, AxisNormalizer.signedFlat(AxisRange(-1f, 1f, flat = 0f)), 0f)
    }

    @Test
    fun `the fuzz figure is normalised the same conservative way`() {
        assertEquals(0.05f, AxisNormalizer.signedFuzz(AxisRange(-1f, 1f, fuzz = 0.05f)), 0.0001f)
        assertEquals(5f / 127f, AxisNormalizer.signedFuzz(AxisRange(-128f, 127f, fuzz = 5f)), 0.0001f)
        assertEquals(
            0.0625f,
            AxisNormalizer.signedFuzz(AxisRange(-0.8f, 1f, fuzz = 0.05f)),
            0.0001f,
        )
    }

    @Test
    fun `the flat figure never exceeds the axis`() {
        assertEquals(1f, AxisNormalizer.signedFlat(AxisRange(-1f, 1f, flat = 5f)), 1f)
        assertFalse(AxisNormalizer.signedFlat(AxisRange(-1f, 1f, flat = 5f)) > 1f)
        assertTrue(AxisNormalizer.triggerFlat(AxisRange(0f, 1f, flat = 2f)) <= 1f)
    }
}
