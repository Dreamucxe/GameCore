package com.gamecore.core.overlay

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure-Kotlin mapping between a slider's continuous drag fraction (0f..1f) and the
 * discrete value it represents, plus the human-readable label shown next to the thumb.
 *
 * This object deliberately contains **no** `android.*` or Compose references so it can be
 * exercised by fast JVM unit tests. All UI concerns (drawing, gestures, colours) live in
 * [OverlayComponents]; this layer only answers three questions:
 *
 *  1. Where along the track does a value sit? -> [valueToFraction]
 *  2. Given a drag position, what value did the user land on? -> [fractionToValue]
 *  3. How should that value read to a person? -> [label]
 *
 * The value-space is treated as `[min, max]` with a quantisation [step]. Fractions are always
 * clamped into `0f..1f` and values into `min..max`, so callers never have to guard the edges.
 */
object OverlaySliderMapping {

    /**
     * Projects [value] onto its position along the `[min, max]` track as a fraction in `0f..1f`.
     *
     * Values at or below [min] map to `0f`; values at or above [max] map to `1f`. A degenerate
     * range (`max <= min`) yields `0f` rather than dividing by zero.
     *
     * @param value the value to locate on the track.
     * @param min the inclusive lower bound of the value-space.
     * @param max the inclusive upper bound of the value-space.
     * @return the clamped drag fraction in `0f..1f`.
     */
    fun valueToFraction(value: Float, min: Float, max: Float): Float {
        if (max <= min) return 0f
        val fraction = (value - min) / (max - min)
        return fraction.coerceIn(0f, 1f)
    }

    /**
     * Resolves a drag [fraction] back to a concrete value, quantised to the nearest [step].
     *
     * The fraction is first clamped into `0f..1f`, mapped linearly onto `[min, max]`, then snapped
     * to the closest multiple of [step] anchored at [min]. The result is finally clamped into
     * `min..max` so rounding can never push it past a bound. A non-positive [step] disables
     * snapping and returns the clamped continuous value.
     *
     * @param fraction the drag position in `0f..1f` (values outside are clamped).
     * @param min the inclusive lower bound of the value-space.
     * @param max the inclusive upper bound of the value-space.
     * @param step the quantisation granularity, anchored at [min].
     * @return the snapped value in `min..max`.
     */
    fun fractionToValue(fraction: Float, min: Float, max: Float, step: Float): Float {
        val clamped = fraction.coerceIn(0f, 1f)
        val raw = min + clamped * (max - min)
        if (step <= 0f) return raw.coerceIn(min, max)
        val snapped = min + (((raw - min) / step).roundToInt()) * step
        return snapped.coerceIn(min, max)
    }

    /**
     * Formats [value] as a display string, e.g. `"52%"`, `"+4%"`, `"0°"`, `"60 Hz"`.
     *
     * The magnitude is rounded to the nearest whole number. Symbol units (`"%"`, `"°"`) and the
     * empty unit hug the number with no gap; word units (e.g. `"Hz"`) are separated by a space.
     * When [signed] is `true`, non-negative values are prefixed with `"+"`; negative values always
     * carry their natural `"-"` regardless of [signed].
     *
     * @param value the value to render.
     * @param unit the unit suffix (`"%"`, `"°"`, `"Hz"`, `""`, ...).
     * @param signed whether to show an explicit `"+"` on non-negative values.
     * @return the formatted label.
     */
    fun label(value: Float, unit: String, signed: Boolean = false): String {
        val rounded = value.roundToInt()
        val magnitude = abs(rounded)
        val sign = when {
            rounded < 0 -> "-"
            // `rounded > 0`, not just `signed`: zero is the neutral value for the signed fields (a hue of
            // 0°, a saturation of 0%), and the colour editor writes it "0°"/"0%" with no plus. A "+0" here
            // would be a second formatter disagreeing with `ColorField.format` about the most-shown value
            // of the three — exactly the split `OverlayLevel.format`'s KDoc forbids.
            signed && rounded > 0 -> "+"
            else -> ""
        }
        val separator = if (unit.isEmpty() || unit == "%" || unit == "°") "" else " "
        return "$sign$magnitude$separator$unit"
    }

    /**
     * The number of discrete stops a slider of this range and [step] has, as `ProgressBarRangeInfo` counts
     * them: the intervals between the endpoints, minus one — the endpoints themselves are not "stops". A
     * continuous or degenerate slider reports `0`.
     *
     * Here rather than in [OverlayComponents] because it is the fourth question this layer answers about a
     * slider — how many stops a screen reader announces — and it is pure `Int` arithmetic like the other
     * three, so it belongs where they are tested rather than hidden as a private helper beside the drawing
     * code.
     */
    fun steps(min: Float, max: Float, step: Float): Int {
        if (step <= 0f || max <= min) return 0
        val intervals = ((max - min) / step).roundToInt()
        return (intervals - 1).coerceAtLeast(0)
    }
}
