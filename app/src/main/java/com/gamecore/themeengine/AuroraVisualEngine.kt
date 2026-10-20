package com.gamecore.themeengine

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The live metrics the engine paints from.
 *
 * All three are the *effective* readings: live values from `PerformanceMonitor`, or the calibration
 * sliders while the user is testing a threshold. Values are normalised on the way in so a bad reading
 * (a temperature sensor that reports -40, a processor that briefly reports over 100) cannot become a
 * negative radius or a velocity outside what the renderer clamps.
 */
data class ThemeEngineMetrics(
    val cpuUsage: Float,
    val ramAllocation: Float,
    val coreTemperatureC: Float,
) {

    fun normalised(): ThemeEngineMetrics = ThemeEngineMetrics(
        cpuUsage = cpuUsage.coerceIn(0f, AuroraBounds.MAX_PERCENT_METRIC),
        ramAllocation = ramAllocation.coerceIn(0f, AuroraBounds.MAX_PERCENT_METRIC),
        coreTemperatureC = coreTemperatureC.coerceIn(0f, AuroraBounds.MAX_TEMP_METRIC),
    )

    companion object {
        val EMPTY = ThemeEngineMetrics(0f, 0f, 0f)
    }
}

/** One paintable vector: a colour and the speed the wave should travel at. */
data class AuroraVector(
    val rgb: Triple<Int, Int, Int>,
    val velocity: Float,
)

/**
 * What the engine decided, before any smoothing.
 *
 * [vector] is the **target** — the one the reference names exactly — and [severity] is the continuous
 * reading the custom vector's velocity is derived from. The plugin keeps a second, smoothed vector for
 * the canvas; this one is what the calibration table prints, because a table showing a transitional
 * value would be showing an animation frame rather than the calibration.
 */
data class AuroraResolution(
    val mode: ColorVectorMode,
    val vector: AuroraVector,
    val severity: Float,
)

/**
 * The rules that turn three readings into a colour vector.
 *
 * Deliberately pure and Android-free: this is the part of the engine whose answer a user can check
 * against the reference (63 % CPU → violet at 1.92x, 98 °C → molten), and a rule that can be tested is
 * a rule that can be trusted. The canvas does the drawing; this file decides what is drawn.
 *
 * Mode selection reads each threshold in the preset as the *onset* of the next vector:
 *
 *  - at or above [CalibrationPreset.tempThreshold], or at extreme CPU/RAM, the vector is molten;
 *  - at or above [CalibrationPreset.cpuThreshold] / [CalibrationPreset.ramThreshold], or at
 *    three-quarters of the temperature threshold, it is Aurora Violet;
 *  - otherwise it is ice.
 *
 * The reference examples fall out of the defaults: 63 % CPU crosses the 55 % default, and 98 °C
 * crosses the 90 °C default. A user who moves those thresholds moves the mode edges with them, which
 * is what the sliders on the calibration screen exist to demonstrate.
 */
object AuroraVisualEngine {

    /**
     * Above this CPU reading the vector goes molten regardless of the thresholds: the device is at its
     * ceiling whether or not someone has calibrated a lower edge.
     */
    private const val EXTREME_CPU = 95f

    /** The same for RAM. Everything allocated is a condition of its own. */
    private const val EXTREME_RAM = 97f

    /** The temperature, as a fraction of its threshold, at which the vector turns violet rather than ice. */
    private const val WARM_FRACTION = 0.75f

    fun resolve(metrics: ThemeEngineMetrics, preset: CalibrationPreset): AuroraResolution {
        val readings = metrics.normalised()
        val calibration = preset.normalised()

        val severity = maxOf(
            readings.cpuUsage / AuroraBounds.MAX_PERCENT_METRIC,
            readings.ramAllocation / AuroraBounds.MAX_PERCENT_METRIC,
            readings.coreTemperatureC / AuroraBounds.MAX_TEMP_METRIC,
        ).coerceIn(0f, 1f)

        val molten = readings.coreTemperatureC >= calibration.tempThreshold ||
            readings.cpuUsage >= EXTREME_CPU ||
            readings.ramAllocation >= EXTREME_RAM

        val violet = !molten && (
            readings.cpuUsage >= calibration.cpuThreshold ||
                readings.ramAllocation >= calibration.ramThreshold ||
                readings.coreTemperatureC >= calibration.tempThreshold * WARM_FRACTION
            )

        val derived = when {
            molten -> ColorVectorMode.MOLTEN_LAVA
            violet -> ColorVectorMode.AURORA_VIOLET
            else -> ColorVectorMode.ICE_GLACIER
        }

        // CUSTOM is the user's explicit override: the colour they chose is painted as chosen, and only
        // the velocity keeps following load. A CUSTOM preset with no colour falls back to the blend the
        // metrics imply, so the mode can never paint from a null.
        if (calibration.vectorMode == ColorVectorMode.CUSTOM) {
            val colour = calibration.customColorRgb ?: blendedColour(severity)
            val velocity = lerp(AuroraPalette.ICE_VELOCITY, AuroraPalette.LAVA_VELOCITY, severity)
            return AuroraResolution(
                mode = ColorVectorMode.CUSTOM,
                vector = AuroraVector(colour, (velocity * calibration.fluidVelocity).boundedVelocity()),
                severity = severity,
            )
        }

        return AuroraResolution(
            mode = derived,
            vector = AuroraVector(
                rgb = AuroraPalette.colourOf(derived),
                velocity = (AuroraPalette.velocityOf(derived) * calibration.fluidVelocity).boundedVelocity(),
            ),
            severity = severity,
        )
    }

    /**
     * The continuous ice → violet → lava ramp used by a CUSTOM preset with no colour of its own.
     *
     * Violet sits at 0.6 of the ramp so the reference's own mid-load reading lands on it rather than
     * somewhere between the two anchors.
     */
    fun blendedColour(severity: Float): Triple<Int, Int, Int> {
        val t = severity.coerceIn(0f, 1f)
        return if (t <= CUSTOM_VIOLET_AT) {
            lerpColour(AuroraPalette.ICE, AuroraPalette.VIOLET, t / CUSTOM_VIOLET_AT)
        } else {
            lerpColour(AuroraPalette.VIOLET, AuroraPalette.LAVA, (t - CUSTOM_VIOLET_AT) / (1f - CUSTOM_VIOLET_AT))
        }
    }

    /** Where the violet anchor sits on the custom ramp. */
    private const val CUSTOM_VIOLET_AT = 0.6f
}

/**
 * The engine's paint state at full precision.
 *
 * The canvas paints bytes, but the transition between two colours must not be computed in bytes. An
 * exponential blend rounded into integers stalls once its per-step change drops below half a unit —
 * in practice about a fifth of the way from one colour to the next — and a per-step round also biases
 * the speed of the whole transition toward whichever way the channel is travelling. Both are avoided
 * by keeping the running values as floats from start to finish and rounding once, in [painted], on the
 * way to the screen.
 */
internal data class AuroraRenderVector(
    val red: Float,
    val green: Float,
    val blue: Float,
    val velocity: Float,
) {

    /** What the canvas actually draws this frame. */
    fun painted(): AuroraVector = AuroraVector(
        rgb = Triple(
            red.roundToInt().coerceIn(0, 255),
            green.roundToInt().coerceIn(0, 255),
            blue.roundToInt().coerceIn(0, 255),
        ),
        velocity = velocity.boundedVelocity(),
    )

    companion object {
        fun of(vector: AuroraVector): AuroraRenderVector = AuroraRenderVector(
            red = vector.rgb.first.toFloat(),
            green = vector.rgb.second.toFloat(),
            blue = vector.rgb.third.toFloat(),
            velocity = vector.velocity.boundedVelocity(),
        )
    }
}

/**
 * One smoothing step of the paint state toward the target.
 *
 * Frame-rate independent by construction: the fraction of the remaining distance covered depends on
 * elapsed time, not on how many times this was called, so a dropped frame slows the transition rather
 * than stopping it. The constant is a *time to settle*, not a per-frame alpha — at 900 ms the canvas
 * is visibly fluid at sixty frames a second and still converges in about a second on a screen that
 * only produces four.
 *
 * It snaps onto the target once it is close. An exponential approach is asymptotic, so without the
 * snap the flow would keep emitting imperceptibly small changes forever and a canvas that had
 * visually finished would never stop redrawing.
 *
 * [dtMillis] is clamped: a `System.nanoTime` gap measured across a process suspension is not a frame
 * delta, and letting it through would teleport the colour on the first frame after resume.
 */
internal fun blendVector(
    current: AuroraRenderVector,
    target: AuroraVector,
    dtMillis: Long,
    tauMillis: Float = SETTLE_TAU_MILLIS,
): AuroraRenderVector {
    val dt = dtMillis.coerceIn(0L, 1000L)
    if (dt == 0L) return current
    val alpha = (1f - exp(-dt / tauMillis)).coerceIn(0f, 1f)
    val targetRed = target.rgb.first.toFloat()
    val targetGreen = target.rgb.second.toFloat()
    val targetBlue = target.rgb.third.toFloat()

    val next = AuroraRenderVector(
        red = lerp(current.red, targetRed, alpha),
        green = lerp(current.green, targetGreen, alpha),
        blue = lerp(current.blue, targetBlue, alpha),
        velocity = lerp(current.velocity, target.velocity, alpha).boundedVelocity(),
    )

    val settled = kotlin.math.abs(next.red - targetRed) <= SETTLE_CHANNEL &&
        kotlin.math.abs(next.green - targetGreen) <= SETTLE_CHANNEL &&
        kotlin.math.abs(next.blue - targetBlue) <= SETTLE_CHANNEL &&
        kotlin.math.abs(next.velocity - target.velocity) <= SETTLE_VELOCITY

    return if (settled) {
        AuroraRenderVector(targetRed, targetGreen, targetBlue, target.velocity)
    } else {
        next
    }
}

/** How long a transition takes to settle, in milliseconds. */
private const val SETTLE_TAU_MILLIS = 900f

/** A channel this close to the target is the target: within half a painted byte. */
private const val SETTLE_CHANNEL = 0.5f

/** Velocities this close to the target are the target. */
private const val SETTLE_VELOCITY = 0.002f

/** How the calibration table writes a vector: `rgb(148, 90, 180)`. */
fun rgbText(rgb: Triple<Int, Int, Int>): String = "rgb(${rgb.first}, ${rgb.second}, ${rgb.third})"

/** How the calibration table writes a speed: `1.92x`. */
fun velocityText(velocity: Float): String = "%.2fx".format(velocity)

/** `62.7%`, the figure the CPU and RAM cards print. */
fun percentText(value: Float): String = "%.1f%%".format(value.coerceIn(0f, AuroraBounds.MAX_PERCENT_METRIC))

/** `98°C`, the figure the temperature card prints. */
fun temperatureText(value: Float): String =
    "${value.coerceIn(0f, AuroraBounds.MAX_TEMP_METRIC).roundToInt()}°C"

private fun lerp(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction.coerceIn(0f, 1f)

private fun lerpChannel(start: Int, end: Int, fraction: Float): Int =
    lerp(start.toFloat(), end.toFloat(), fraction).roundToInt().coerceIn(0, 255)

private fun lerpColour(
    start: Triple<Int, Int, Int>,
    end: Triple<Int, Int, Int>,
    fraction: Float,
): Triple<Int, Int, Int> = Triple(
    lerpChannel(start.first, end.first, fraction),
    lerpChannel(start.second, end.second, fraction),
    lerpChannel(start.third, end.third, fraction),
)

/** One place where a velocity becomes renderable, so no other path can hand the canvas a wild value. */
private fun Float.boundedVelocity(): Float =
    coerceIn(AuroraBounds.MIN_VELOCITY, AuroraBounds.MAX_VELOCITY)
