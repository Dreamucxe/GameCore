package com.gamecore.core.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Pins [SensorCalibration.analyse] with synthetic traces.
 *
 * Every case here is arithmetic on plain values — no sensors, no Android, no coroutines. The three grades
 * each get a case that fails for one reason only, so a threshold that moves shows up as a specific broken
 * test rather than a general sense that calibration got worse.
 */
class SensorCalibrationTest {

    private fun sample(
        index: Int,
        ax: Float = 0f,
        ay: Float = 0f,
        az: Float = GRAVITY,
        gx: Float = 0f,
        gy: Float = 0f,
        gz: Float = 0f,
    ): TraceSample = TraceSample(
        millis = index * 20L,
        ax = ax,
        ay = ay,
        az = az,
        gx = gx,
        gy = gy,
        gz = gz,
    )

    // --- empty and too-short traces ----------------------------------------------------------------

    @Test
    fun `empty trace is unknown with zeroed figures`() {
        val result = SensorCalibration.analyse(emptyList())

        assertEquals(0f, result.gyroBiasRadPerSec, 0f)
        assertEquals(0f, result.gyroNoise, 0f)
        assertEquals(0f, result.accelNoise, 0f)
        assertEquals(0f, result.driftPerMinuteDegrees, 0f)
        assertEquals(SensorQuality.UNKNOWN, result.quality)
        assertTrue(result.summary.contains("No samples captured"))
        assertTrue(result.summary.contains("UNKNOWN"))
    }

    @Test
    fun `nineteen samples are unknown even when they are perfect`() {
        val result = SensorCalibration.analyse(List(19) { sample(it) })

        assertEquals(SensorQuality.UNKNOWN, result.quality)
        assertTrue(result.summary.contains("19"))
        assertTrue(result.summary.contains("20"))
    }

    @Test
    fun `twenty samples are enough for a verdict`() {
        val result = SensorCalibration.analyse(List(20) { sample(it) })

        assertEquals(SensorQuality.GOOD, result.quality)
    }

    // --- GOOD --------------------------------------------------------------------------------------

    @Test
    fun `perfectly still trace is good`() {
        val result = SensorCalibration.analyse(List(200) { sample(it) })

        assertEquals(0f, result.gyroBiasRadPerSec, 1e-6f)
        assertEquals(0f, result.gyroNoise, 1e-6f)
        assertEquals(0f, result.accelNoise, 1e-6f)
        assertEquals(0f, result.driftPerMinuteDegrees, 1e-6f)
        assertEquals(SensorQuality.GOOD, result.quality)
    }

    @Test
    fun `small constant bias is good`() {
        val result = SensorCalibration.analyse(List(200) { sample(it, gx = 0.005f) })

        assertEquals(0.005f, result.gyroBiasRadPerSec, 1e-6f)
        assertEquals(0f, result.gyroNoise, 1e-6f)
        assertEquals(SensorQuality.GOOD, result.quality)
    }

    @Test
    fun `small gyro noise is good`() {
        // Magnitudes alternate between 0 and 0.01: mean 0.005, population std dev 0.005.
        val result = SensorCalibration.analyse(
            List(200) { sample(it, gx = if (it % 2 == 0) 0f else 0.01f) },
        )

        assertEquals(0.005f, result.gyroBiasRadPerSec, 1e-6f)
        assertEquals(0.005f, result.gyroNoise, 1e-6f)
        assertEquals(SensorQuality.GOOD, result.quality)
    }

    // --- FAIR --------------------------------------------------------------------------------------

    @Test
    fun `bias between the good and fair thresholds is fair`() {
        val result = SensorCalibration.analyse(List(200) { sample(it, gx = 0.03f) })

        assertEquals(0.03f, result.gyroBiasRadPerSec, 1e-6f)
        assertEquals(SensorQuality.FAIR, result.quality)
    }

    @Test
    fun `bias exactly at the good threshold is only fair`() {
        // The thresholds are strict: "good" is below 0.01, not at it.
        val result = SensorCalibration.analyse(List(200) { sample(it, gx = 0.01f) })

        assertEquals(0.01f, result.gyroBiasRadPerSec, 1e-7f)
        assertEquals(SensorQuality.FAIR, result.quality)
    }

    @Test
    fun `noise between the good and fair thresholds is fair`() {
        // Magnitudes alternate between 0.01 and 0.07: mean 0.04, population std dev 0.03.
        val result = SensorCalibration.analyse(
            List(200) { sample(it, gx = if (it % 2 == 0) 0.01f else 0.07f) },
        )

        assertEquals(0.04f, result.gyroBiasRadPerSec, 1e-6f)
        assertEquals(0.03f, result.gyroNoise, 1e-6f)
        assertEquals(SensorQuality.FAIR, result.quality)
    }

    // --- POOR --------------------------------------------------------------------------------------

    @Test
    fun `bias above the fair threshold is poor`() {
        val result = SensorCalibration.analyse(List(200) { sample(it, gx = 0.06f) })

        assertEquals(0.06f, result.gyroBiasRadPerSec, 1e-6f)
        assertEquals(SensorQuality.POOR, result.quality)
    }

    @Test
    fun `noise above the fair threshold is poor even when bias is not`() {
        // One in five readings is 0.2 rad/s: bias 0.04, population std dev 0.08.
        val result = SensorCalibration.analyse(
            List(400) { sample(it, gx = if (it % 5 == 0) 0.2f else 0f) },
        )

        assertEquals(0.04f, result.gyroBiasRadPerSec, 1e-6f)
        assertEquals(0.08f, result.gyroNoise, 1e-5f)
        assertEquals(SensorQuality.POOR, result.quality)
    }

    // --- bias magnitude and drift ------------------------------------------------------------------

    @Test
    fun `negative readings produce a positive bias magnitude`() {
        // Bias is the mean magnitude, so the direction of the error is not part of the figure.
        val result = SensorCalibration.analyse(List(200) { sample(it, gx = -0.02f) })

        assertEquals(0.02f, result.gyroBiasRadPerSec, 1e-6f)
        assertEquals(0f, result.gyroNoise, 1e-6f)
        assertTrue(result.driftPerMinuteDegrees > 0f)
    }

    @Test
    fun `drift is bias integrated over one minute in degrees`() {
        val result = SensorCalibration.analyse(List(200) { sample(it, gx = 0.01f) })

        val expected = Math.toDegrees(0.01 * 60.0).toFloat()
        assertEquals(expected, result.driftPerMinuteDegrees, 0.01f)
    }

    @Test
    fun `multi-axis bias uses the vector magnitude`() {
        val result = SensorCalibration.analyse(
            List(200) { sample(it, gx = 0.003f, gy = 0.004f) },
        )

        val expectedBias = sqrt(0.003f * 0.003f + 0.004f * 0.004f)
        val expectedDrift = Math.toDegrees(expectedBias.toDouble() * 60.0).toFloat()

        assertEquals(0.005f, result.gyroBiasRadPerSec, 1e-6f)
        assertEquals(expectedDrift, result.driftPerMinuteDegrees, 0.01f)
        assertEquals(0f, result.gyroNoise, 1e-6f)
    }

    // --- accelerometer noise -----------------------------------------------------------------------

    @Test
    fun `accel noise is the spread of accel magnitude`() {
        // Magnitudes alternate between 9.31 and 10.31 m/s², so the population std dev is 0.5.
        val result = SensorCalibration.analyse(
            List(200) { sample(it, az = if (it % 2 == 0) 9.31f else 10.31f) },
        )

        assertEquals(0.5f, result.accelNoise, 1e-5f)
    }

    // --- interval and summary ----------------------------------------------------------------------

    @Test
    fun `expected interval describes the window without changing the figures`() {
        val samples = List(100) { sample(it, gx = 0.02f) }

        val twentyMillis = SensorCalibration.analyse(samples, expectedIntervalMillis = 20L)
        val fortyMillis = SensorCalibration.analyse(samples, expectedIntervalMillis = 40L)

        assertEquals(twentyMillis.gyroBiasRadPerSec, fortyMillis.gyroBiasRadPerSec, 0f)
        assertEquals(twentyMillis.gyroNoise, fortyMillis.gyroNoise, 0f)
        assertEquals(twentyMillis.accelNoise, fortyMillis.accelNoise, 0f)
        assertEquals(twentyMillis.driftPerMinuteDegrees, fortyMillis.driftPerMinuteDegrees, 0f)
        assertTrue(twentyMillis.summary.contains("2.0 s"))
        assertTrue(fortyMillis.summary.contains("4.0 s"))
    }

    @Test
    fun `summary states the figures and the verdict`() {
        val result = SensorCalibration.analyse(List(200) { sample(it, gx = 0.005f) })

        assertTrue(result.summary.contains("Gyro bias"))
        assertTrue(result.summary.contains("noise"))
        assertTrue(result.summary.contains("accel noise"))
        assertTrue(result.summary.contains("drift"))
        assertTrue(result.summary.contains("GOOD"))
    }

    private companion object {
        const val GRAVITY = 9.81f
    }
}
