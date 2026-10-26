package com.gamecore.core.controller

import kotlin.math.abs

/**
 * One axis exactly as `InputDevice.MotionRange` described it.
 *
 * Deliberately a plain Kotlin type rather than the Android one: every rule below is arithmetic on the
 * driver's declared range, and keeping it here means the dead-zone, the asymmetric-range handling and
 * the trigger fallback are all proven on the JVM instead of on a controller somebody has to hold.
 */
data class AxisRange(
    val minimum: Float,
    val maximum: Float,
    val flat: Float = 0f,
    val fuzz: Float = 0f,
) {
    val span: Float get() = maximum - minimum
    val isUsable: Boolean get() = span > 0f && minimum.isFinite() && maximum.isFinite()
}

/**
 * Raw controller values to the range the rest of the lab reasons about.
 *
 * Android does not promise a shape for an axis. A stick is usually `-1f..1f`, but a driver may report
 * `-128f..127f` or an asymmetric pair; a trigger is usually `0f..1f`, but some controllers expose it on
 * the alternate `AXIS_BRAKE`/`AXIS_GAS` pair instead of `AXIS_LTRIGGER`/`AXIS_RTRIGGER`. Two rules keep
 * this honest:
 *
 *  - **The declared range is the truth, never the axis name.** [signed] divides by the side of centre the
 *    value is on, so `-0.6` from a `-1f..1f` axis and `-77` from a `-128f..127f` axis both mean the same
 *    fraction of travel.
 *  - **`flat` is a dead zone, not noise to subtract.** A value inside the driver's declared flat radius is
 *    centre. It is not rescaled and it does not become a small drift figure; reporting a value there as
 *    offset would diagnose a controller against the dead zone its own driver already applies.
 *
 * `fuzz` is retained on [AxisRange] because the device reports it, but it is not applied here: fuzz is the
 * driver's error margin on a reading, not a second dead zone, and folding the two together would hide the
 * difference between a controller at rest and one whose driver rounds.
 */
object AxisNormalizer {

    /**
     * A stick or hat axis, normalised to `-1f..1f` around the declared centre.
     *
     * Zero is treated as the centre when it lies inside the range, which is what a signed Android axis
     * means. A range that does not straddle zero (`0f..1f`) cannot be a signed stick; it is normalised
     * against its midpoint so a caller that passes one still gets a bounded answer rather than a lie.
     */
    fun signed(raw: Float, range: AxisRange): Float {
        if (!range.isUsable || !raw.isFinite()) return 0f
        val centre = if (range.minimum < 0f && range.maximum > 0f) 0f else range.minimum + range.span / 2f
        if (abs(raw - centre) <= range.flat) return 0f
        val half = if (raw >= centre) {
            (range.maximum - centre).takeIf { it > 0f }
        } else {
            (centre - range.minimum).takeIf { it > 0f }
        } ?: return 0f
        return ((raw - centre) / half).coerceIn(-1f, 1f)
    }

    /**
     * A trigger axis, normalised to `0f..1f`, with the driver's flat radius treated as fully released.
     *
     * The flat radius is subtracted from the start of travel rather than from both ends, because a trigger
     * that declares a dead zone is declaring where "not pressed" ends. The remaining travel is stretched
     * back to the full `0f..1f` so the reported pull is still a fraction of usable travel.
     */
    fun trigger(raw: Float, range: AxisRange): Float {
        if (!range.isUsable || !raw.isFinite()) return 0f
        val start = (range.minimum + range.flat).coerceAtMost(range.maximum)
        val usable = range.maximum - start
        if (usable <= 0f) return 0f
        return ((raw - start) / usable).coerceIn(0f, 1f)
    }

    /**
     * The signed dead zone as a fraction of the axis's travel, for comparing against a configurable
     * threshold.
     *
     * The shorter side of centre is the divisor. On an asymmetric range `flat` is a raw radius, so the
     * same raw value is a larger fraction of the shorter side; using it here means the floor is never
     * underestimated and a stick resting inside the driver's dead zone is never called drifting.
     */
    fun signedFlat(range: AxisRange): Float = signedFraction(range.flat, range)

    /** The driver's error margin as a fraction of travel, using the same conservative divisor as [signedFlat]. */
    fun signedFuzz(range: AxisRange): Float = signedFraction(range.fuzz, range)

    private fun signedFraction(value: Float, range: AxisRange): Float {
        if (!range.isUsable) return 0f
        val centre = if (range.minimum < 0f && range.maximum > 0f) {
            0f
        } else {
            range.minimum + range.span / 2f
        }
        val half = listOf(abs(range.minimum - centre), abs(range.maximum - centre))
            .filter { it > 0f }
            .minOrNull()
            ?: return 0f
        return (value / half).coerceIn(0f, 1f)
    }

    /** The trigger dead zone as a fraction of the usable travel, or zero when the range is unusable. */
    fun triggerFlat(range: AxisRange): Float {
        if (!range.isUsable) return 0f
        return (range.flat / range.span).coerceIn(0f, 1f)
    }
}
