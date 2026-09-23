package com.gamecore.core.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [OverlaySliderMapping]: value<->fraction round-tripping, edge clamping,
 * step quantisation, and the four label formats used by the overlay sliders.
 */
class OverlaySliderMappingTest {

    private val delta = 0.0001f

    // ---- value <-> fraction round-trip ----

    @Test
    fun valueToFraction_midpoint() {
        assertEquals(0.52f, OverlaySliderMapping.valueToFraction(52f, 0f, 100f), delta)
    }

    @Test
    fun roundTrip_valueToFractionToValue() {
        val value = 55f
        val fraction = OverlaySliderMapping.valueToFraction(value, 0f, 100f)
        val recovered = OverlaySliderMapping.fractionToValue(fraction, 0f, 100f, 5f)
        assertEquals(value, recovered, delta)
    }

    @Test
    fun roundTrip_offsetRange() {
        // Range that does not start at zero.
        val value = 90f
        val fraction = OverlaySliderMapping.valueToFraction(value, 30f, 144f)
        val recovered = OverlaySliderMapping.fractionToValue(fraction, 30f, 144f, 1f)
        assertEquals(value, recovered, delta)
    }

    // ---- clamping ----

    @Test
    fun valueToFraction_clampsBelowMin() {
        assertEquals(0f, OverlaySliderMapping.valueToFraction(-10f, 0f, 100f), delta)
    }

    @Test
    fun valueToFraction_clampsAboveMax() {
        assertEquals(1f, OverlaySliderMapping.valueToFraction(150f, 0f, 100f), delta)
    }

    @Test
    fun valueToFraction_degenerateRangeReturnsZero() {
        assertEquals(0f, OverlaySliderMapping.valueToFraction(50f, 100f, 100f), delta)
    }

    @Test
    fun fractionToValue_clampsBelowZero() {
        assertEquals(0f, OverlaySliderMapping.fractionToValue(-0.5f, 0f, 100f, 1f), delta)
    }

    @Test
    fun fractionToValue_clampsAboveOne() {
        assertEquals(100f, OverlaySliderMapping.fractionToValue(1.5f, 0f, 100f, 1f), delta)
    }

    // ---- step rounding to nearest ----

    @Test
    fun fractionToValue_snapsUpToNearestStep() {
        // raw = 53 -> nearest multiple of 5 is 55.
        assertEquals(55f, OverlaySliderMapping.fractionToValue(0.53f, 0f, 100f, 5f), delta)
    }

    @Test
    fun fractionToValue_snapsDownToNearestStep() {
        // raw = 52 -> nearest multiple of 5 is 50.
        assertEquals(50f, OverlaySliderMapping.fractionToValue(0.52f, 0f, 100f, 5f), delta)
    }

    @Test
    fun fractionToValue_nonPositiveStepDisablesSnapping() {
        assertEquals(52f, OverlaySliderMapping.fractionToValue(0.52f, 0f, 100f, 0f), delta)
    }

    // ---- label formats ----

    @Test
    fun label_percent() {
        assertEquals("52%", OverlaySliderMapping.label(52f, "%"))
    }

    @Test
    fun label_signedPercent() {
        assertEquals("+4%", OverlaySliderMapping.label(4f, "%", signed = true))
    }

    @Test
    fun label_signedNegativeKeepsMinus() {
        assertEquals("-4%", OverlaySliderMapping.label(-4f, "%", signed = true))
    }

    @Test
    fun label_degreesZero() {
        assertEquals("0°", OverlaySliderMapping.label(0f, "°"))
    }

    @Test
    fun label_hertzHasSpace() {
        assertEquals("60 Hz", OverlaySliderMapping.label(60f, "Hz"))
    }

    @Test
    fun label_signedZeroHasNoPlus() {
        // Zero is the neutral for the signed colour fields, and the colour editor writes it "0°"/"0%"
        // with no plus. A "+0" here would be a second formatter disagreeing with ColorField.format about
        // the most-shown value of the three — the split OverlayLevel.format's KDoc forbids.
        assertEquals("0%", OverlaySliderMapping.label(0f, "%", signed = true))
        assertEquals("0°", OverlaySliderMapping.label(0f, "°", signed = true))
    }

    // ---- the shared formatter agrees with OverlayLevel.format on the signed colour fields ----

    @Test
    fun sharedLabelAgreesWithLevelFormatForColourValues() {
        // The gate for the panel's colour sliders: the slider's own label and the app-wide
        // OverlayLevel.format (which the colour editor and the value readout use) must render the same
        // number the same way, or one control shows "+0%" while another shows "0%" for the same field.
        for (value in listOf(-100, -40, -4, 0, 4, 40, 100)) {
            assertEquals(
                "saturation $value",
                OverlayLevel.SATURATION.format(value),
                OverlaySliderMapping.label(value.toFloat(), "%", signed = true),
            )
        }
        for (degrees in listOf(-180, -90, 0, 90, 180)) {
            assertEquals(
                "hue $degrees",
                OverlayLevel.HUE.format(degrees),
                OverlaySliderMapping.label(degrees.toFloat(), "°", signed = true),
            )
        }
    }

    // ---- steps: the number of stops a screen reader announces ----

    @Test
    fun steps_wholePercent() {
        // 0..100 in steps of 1 has 100 intervals, so 99 stops between the endpoints.
        assertEquals(99, OverlaySliderMapping.steps(0f, 100f, 1f))
    }

    @Test
    fun steps_coarserStep() {
        assertEquals(19, OverlaySliderMapping.steps(0f, 100f, 5f))
    }

    @Test
    fun steps_continuousIsZero() {
        assertEquals(0, OverlaySliderMapping.steps(0f, 100f, 0f))
    }

    @Test
    fun steps_degenerateRangeIsZero() {
        assertEquals(0, OverlaySliderMapping.steps(100f, 100f, 1f))
    }

    @Test
    fun steps_stepWiderThanRangeIsZeroNotNegative() {
        assertEquals(0, OverlaySliderMapping.steps(0f, 10f, 25f))
    }
}
