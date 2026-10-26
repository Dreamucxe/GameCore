package com.gamecore.core.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerProfileValidatorTest {

    @Test
    fun `a complete profile is valid and trimmed`() {
        val outcome = ControllerProfileValidator.validate(
            profile().copy(
                name = "  Aim preset  ",
                deviceName = "  Xbox Wireless Controller ",
                notes = "  measured at rest\r\n",
            ),
        )

        val valid = outcome as ProfileValidation.Valid
        assertEquals("Aim preset", valid.profile.name)
        assertEquals("Xbox Wireless Controller", valid.profile.deviceName)
        assertEquals("measured at rest", valid.profile.notes)
    }

    @Test
    fun `a blank name is rejected rather than filled with a placeholder`() {
        val outcome = ControllerProfileValidator.validate(profile().copy(name = "   "))

        assertTrue(outcome is ProfileValidation.Invalid)
        assertTrue((outcome as ProfileValidation.Invalid).errors.any { it.contains("name") })
    }

    @Test
    fun `over-long text is rejected, not truncated`() {
        val longName = ControllerProfileValidator.validate(profile().copy(name = "x".repeat(41)))
        val longNotes = ControllerProfileValidator.validate(profile().copy(notes = "x".repeat(281)))

        assertTrue(longName is ProfileValidation.Invalid)
        assertTrue(longNotes is ProfileValidation.Invalid)
    }

    @Test
    fun `a control character in the name is rejected`() {
        val outcome = ControllerProfileValidator.validate(profile().copy(name = "bad\nname"))

        assertTrue(outcome is ProfileValidation.Invalid)
    }

    @Test
    fun `dead zone, exponent and thresholds must be inside their model ranges`() {
        assertTrue(ControllerProfileValidator.validate(profile().copy(deadzone = 0.51f)) is ProfileValidation.Invalid)
        assertTrue(ControllerProfileValidator.validate(profile().copy(deadzone = -0.01f)) is ProfileValidation.Invalid)
        assertTrue(ControllerProfileValidator.validate(profile().copy(deadzone = Float.NaN)) is ProfileValidation.Invalid)
        assertTrue(ControllerProfileValidator.validate(profile().copy(exponent = 0.9f)) is ProfileValidation.Invalid)
        assertTrue(ControllerProfileValidator.validate(profile().copy(exponent = 4.1f)) is ProfileValidation.Invalid)
        assertTrue(
            ControllerProfileValidator.validate(profile().copy(driftOffsetThreshold = 0.31f)) is
                ProfileValidation.Invalid,
        )
        assertTrue(
            ControllerProfileValidator.validate(profile().copy(driftJitterThreshold = 0.11f)) is
                ProfileValidation.Invalid,
        )
    }

    @Test
    fun `a missing device signature is invalid`() {
        assertTrue(ControllerProfileValidator.validate(profile().copy(deviceSignature = "")) is ProfileValidation.Invalid)
    }

    @Test
    fun `an unknown device name is labelled rather than left blank`() {
        val valid = ControllerProfileValidator.validate(profile().copy(deviceName = " ")) as ProfileValidation.Valid

        assertEquals("Unknown controller", valid.profile.deviceName)
    }

    @Test
    fun `timestamps are only ordered, never invented`() {
        val valid = ControllerProfileValidator.validate(
            profile().copy(createdAtMillis = 500L, updatedAtMillis = 100L),
        ) as ProfileValidation.Valid

        assertEquals(500L, valid.profile.updatedAtMillis)
        assertFalse(ControllerProfileValidator.validate(profile().copy(createdAtMillis = -1L)) is ProfileValidation.Valid)
    }

    @Test
    fun `comparison flags changed settings and ignores names`() {
        val left = profile().copy(name = "One", deadzone = 0.08f)
        val right = profile().copy(name = "Two", deadzone = 0.12f)

        val differences = ControllerProfileComparator.compare(left, right)

        assertEquals(5, differences.size)
        assertTrue(differences.first { it.label == "Dead zone" }.differs)
        assertFalse(differences.first { it.label == "Curve" }.differs)
        assertTrue(differences.none { it.label == "Name" })
    }

    @Test
    fun `comparing a profile with itself reports no differences`() {
        val one = profile()

        assertTrue(ControllerProfileComparator.compare(one, one).none { it.differs })
    }

    @Test
    fun `a signature is a capability fingerprint, not a name`() {
        val first = ControllerSignature.of(1, 2, listOf(0, 1, 11), 14)
        val reordered = ControllerSignature.of(1, 2, listOf(11, 1, 0), 14)
        val otherAxes = ControllerSignature.of(1, 2, listOf(0, 1, 12), 14)
        val otherPad = ControllerSignature.of(1, 3, listOf(0, 1, 11), 14)

        assertEquals(first, reordered)
        assertFalse(first == otherAxes)
        assertFalse(first == otherPad)
        assertTrue(first.startsWith("c1:"))
    }

    fun profile() = ControllerProfile(
        name = "Reference",
        deviceSignature = ControllerSignature.of(1, 2, listOf(0, 1, 11, 14), 14),
        deviceName = "Test controller",
        deadzone = 0.08f,
        curve = ResponseCurveKind.EXPONENTIAL,
        exponent = 2f,
        driftOffsetThreshold = 0.08f,
        driftJitterThreshold = 0.02f,
        notes = "reference",
        createdAtMillis = 1_000L,
        updatedAtMillis = 2_000L,
    )
}
