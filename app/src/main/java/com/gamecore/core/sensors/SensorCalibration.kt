package com.gamecore.core.sensors

import java.util.Locale
import kotlin.math.sqrt

/**
 * One raw sensor sample, with the accelerometer and gyroscope readings taken together.
 *
 * Values are exactly what the platform delivered: acceleration in m/s² including gravity, angular
 * velocity in rad/s. [millis] is the sensor event's own timestamp in milliseconds since boot, so the
 * spacing is the sensor's real delivery interval rather than the time this app happened to observe it.
 */
data class TraceSample(
    val millis: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
)

/**
 * What a trace says about the quality of the device's gyroscope and accelerometer.
 *
 * [UNKNOWN] is not a fourth grade of quality — it means there were too few samples to make a claim.
 * That distinction is the point: a calibration screen that printed "GOOD" after three samples would
 * be guessing, and guessing is the one thing a sensor report must not do.
 */
enum class SensorQuality {
    GOOD,
    FAIR,
    POOR,
    UNKNOWN,
}

/**
 * The calibration figures derived from a trace.
 *
 * [gyroBiasRadPerSec] is the mean magnitude of the gyroscope vector while the device was still, so it
 * is a positive quantity: a sensor biased in either direction has the same size of problem. [gyroNoise]
 * is the standard deviation of that same magnitude, and [driftPerMinuteDegrees] is what the bias would
 * accumulate over a minute of integration, in degrees.
 */
data class CalibrationResult(
    val gyroBiasRadPerSec: Float,
    val gyroNoise: Float,
    val accelNoise: Float,
    val driftPerMinuteDegrees: Float,
    val quality: SensorQuality,
    val summary: String,
)

/**
 * Pure analysis of a raw sensor trace.
 *
 * No platform types and no filtering: every figure is arithmetic over the samples the caller supplied, so
 * the same list produces the same result on a phone and in a JVM unit test. The thresholds are the ones
 * the calibration screen explains to the user, and they live here rather than in the UI so the verdict
 * and the numbers it describes cannot drift apart.
 */
object SensorCalibration {

    /** Below this many samples the analysis reports [SensorQuality.UNKNOWN] rather than a verdict. */
    const val MIN_SAMPLES = 20

    private const val GOOD_MAX_BIAS = 0.01f
    private const val GOOD_MAX_NOISE = 0.02f
    private const val FAIR_MAX_BIAS = 0.05f
    private const val FAIR_MAX_NOISE = 0.05f
    private const val SECONDS_PER_MINUTE = 60.0

    /**
     * Analyses [samples] and returns the bias, noise, drift estimate and verdict.
     *
     * [expectedIntervalMillis] is used only to describe how long the captured window represents in the
     * summary. It deliberately does not scale the figures themselves: bias and noise are properties of
     * the readings, not of the rate at which they were delivered.
     */
    fun analyse(
        samples: List<TraceSample>,
        expectedIntervalMillis: Long = 20L,
    ): CalibrationResult {
        val gyroMagnitudes = samples.map { it.gyroMagnitude() }
        val accelMagnitudes = samples.map { it.accelMagnitude() }

        val bias = gyroMagnitudes.mean()
        val noise = gyroMagnitudes.standardDeviation()
        val accelNoise = accelMagnitudes.standardDeviation()
        val biasRadPerSec = bias.toFloat()
        val gyroNoise = noise.toFloat()
        val accelNoiseValue = accelNoise.toFloat()
        val driftPerMinuteDegrees = Math.toDegrees(bias * SECONDS_PER_MINUTE)

        // Compare the values the result actually reports, so a boundary cannot read as one grade in
        // the summary and another in the numbers beside it.
        val quality = when {
            samples.size < MIN_SAMPLES -> SensorQuality.UNKNOWN
            biasRadPerSec < GOOD_MAX_BIAS && gyroNoise < GOOD_MAX_NOISE -> SensorQuality.GOOD
            biasRadPerSec < FAIR_MAX_BIAS && gyroNoise < FAIR_MAX_NOISE -> SensorQuality.FAIR
            else -> SensorQuality.POOR
        }

        return CalibrationResult(
            gyroBiasRadPerSec = biasRadPerSec,
            gyroNoise = gyroNoise,
            accelNoise = accelNoiseValue,
            driftPerMinuteDegrees = driftPerMinuteDegrees.toFloat(),
            quality = quality,
            summary = summaryOf(
                sampleCount = samples.size,
                expectedIntervalMillis = expectedIntervalMillis,
                bias = bias,
                noise = noise,
                accelNoise = accelNoise,
                driftPerMinuteDegrees = driftPerMinuteDegrees,
                quality = quality,
            ),
        )
    }

    private fun summaryOf(
        sampleCount: Int,
        expectedIntervalMillis: Long,
        bias: Double,
        noise: Double,
        accelNoise: Double,
        driftPerMinuteDegrees: Double,
        quality: SensorQuality,
    ): String {
        val windowSeconds = sampleCount.toLong() * expectedIntervalMillis.coerceAtLeast(0L) / 1_000.0
        val opening = when {
            sampleCount == 0 -> "No samples captured. "
            sampleCount < MIN_SAMPLES -> "Only $sampleCount of the $MIN_SAMPLES needed samples captured. "
            else -> ""
        }
        return opening + buildString {
            append("Gyro bias ")
            append(format(bias, 4))
            append(" rad/s, noise ")
            append(format(noise, 4))
            append(" rad/s, accel noise ")
            append(format(accelNoise, 3))
            append(" m/s², drift ")
            append(format(driftPerMinuteDegrees, 1))
            append("°/min over ")
            append(format(windowSeconds, 1))
            append(" s. Quality: ")
            append(quality.name)
            append('.')
        }
    }

    private fun TraceSample.gyroMagnitude(): Double {
        val x = gx.toDouble()
        val y = gy.toDouble()
        val z = gz.toDouble()
        return sqrt(x * x + y * y + z * z)
    }

    private fun TraceSample.accelMagnitude(): Double {
        val x = ax.toDouble()
        val y = ay.toDouble()
        val z = az.toDouble()
        return sqrt(x * x + y * y + z * z)
    }

    private fun List<Double>.mean(): Double =
        if (isEmpty()) 0.0 else sumOf { it } / size

    /**
     * Population standard deviation, not the sample estimate.
     *
     * These are all the readings there are for the window, not a sample drawn from a larger population,
     * so dividing by n is the horizon the screen is actually describing.
     */
    private fun List<Double>.standardDeviation(): Double {
        if (size < 2) return 0.0
        val mean = mean()
        val variance = sumOf { value ->
            val delta = value - mean
            delta * delta
        } / size
        return sqrt(variance)
    }

    private fun format(value: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value)
}
