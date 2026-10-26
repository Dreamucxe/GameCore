package com.gamecore.core.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerTestResultValidatorTest {

    private fun result() = ControllerTestResult(
        deviceSignature = "c1:abc",
        deviceName = "Test pad",
        stick = StickKind.LEFT,
        startedAtMillis = 1_700_000_000_000L,
        durationMillis = 10_000L,
        sampleCount = 300,
        offsetX = 0.03f,
        offsetY = 0.04f,
        offsetMagnitude = 0.05f,
        jitter = 0.01f,
        stabilityPercent = 96f,
        peakMagnitude = 0.08f,
        verdict = DriftVerdict.STABLE,
        offsetThreshold = 0.08f,
        jitterThreshold = 0.02f,
    )

    @Test
    fun `a consistent result is valid`() {
        assertTrue(ControllerTestResultValidator.validate(result()) is TestResultValidation.Valid)
    }

    @Test
    fun `a magnitude that disagrees with its own components is rejected`() {
        val outcome = ControllerTestResultValidator.validate(result().copy(offsetMagnitude = 0.5f))

        assertTrue(outcome is TestResultValidation.Invalid)
        assertTrue((outcome as TestResultValidation.Invalid).errors.any { it.contains("magnitude") })
    }

    @Test
    fun `non-finite and negative measurements are rejected`() {
        assertTrue(ControllerTestResultValidator.validate(result().copy(jitter = Float.NaN)) is TestResultValidation.Invalid)
        assertTrue(ControllerTestResultValidator.validate(result().copy(offsetX = Float.NEGATIVE_INFINITY)) is TestResultValidation.Invalid)
        assertTrue(ControllerTestResultValidator.validate(result().copy(jitter = -0.01f)) is TestResultValidation.Invalid)
    }

    @Test
    fun `stability must be a percentage`() {
        assertTrue(ControllerTestResultValidator.validate(result().copy(stabilityPercent = 101f)) is TestResultValidation.Invalid)
        assertTrue(ControllerTestResultValidator.validate(result().copy(stabilityPercent = -1f)) is TestResultValidation.Invalid)
    }

    @Test
    fun `a result needs a device, a duration and a positive id rule`() {
        assertTrue(ControllerTestResultValidator.validate(result().copy(deviceSignature = "")) is TestResultValidation.Invalid)
        assertTrue(ControllerTestResultValidator.validate(result().copy(durationMillis = 0L)) is TestResultValidation.Invalid)
        assertTrue(ControllerTestResultValidator.validate(result().copy(id = -1L)) is TestResultValidation.Invalid)
        assertTrue(ControllerTestResultValidator.validate(result().copy(startedAtMillis = -1L)) is TestResultValidation.Invalid)
    }

    @Test
    fun `a blank device name is labelled rather than stored blank`() {
        val valid = ControllerTestResultValidator.validate(result().copy(deviceName = " ")) as TestResultValidation.Valid

        assertEquals("Unknown controller", valid.result.deviceName)
    }

    @Test
    fun `a drift result maps to a storable summary with the applied tolerance`() {
        val drift = StickDriftAnalyzer.analyze(
            samples = (0 until 300).map { DriftSample(it * 20L, 0.12f, 0f) },
            requestedDurationMillis = 6_000L,
            thresholds = DriftThresholds(offset = 0.08f, jitter = 0.02f, minSamples = 20),
            declaredFlat = 0.02f,
        )

        val stored = ControllerTestResult.from(
            result = drift,
            deviceSignature = "c1:abc",
            deviceName = "Test pad",
            stick = StickKind.LEFT,
            startedAtMillis = 123L,
            durationMillis = 6_000L,
        )

        assertEquals(DriftVerdict.DRIFT, stored.verdict)
        assertEquals(0.12f, stored.offsetMagnitude, 0.001f)
        assertEquals(0.08f, stored.offsetThreshold, 0.0001f)
        assertEquals(300, stored.sampleCount)
        assertTrue(ControllerTestResultValidator.validate(stored) is TestResultValidation.Valid)
    }
}
