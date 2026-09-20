package com.gamecore.aimlab.engine

/**
 * Clamping that survives `NaN`.
 *
 * Every `normalised()` in the engine exists to make one promise: whatever was handed in, what comes out
 * is inside the documented range. `coerceIn` alone cannot keep it. Clamping is comparisons, every
 * comparison against `NaN` is false, so `Float.NaN.coerceIn(0f, 1f)` returns `Float.NaN` — the one input
 * that most needs clamping is the one input that passes through untouched. A `NaN` then spreads: it is
 * not equal to itself, so it defeats the `contains` test on a target, the distance sort that picks the
 * nearest one, and every average a session's statistics are folded from, and it does all that several
 * layers away from wherever it entered.
 *
 * The infinities need no special case — `+∞ > max` and `-∞ < min` are ordinary comparisons and clamp to
 * the bounds — so this differs from [coerceIn] for exactly one input.
 *
 * [fallback] defaults to [min] rather than to zero because zero is not inside every range in this
 * package: a sensitivity multiplier bottoms out at 0.05 and a control's opacity at 15, and substituting
 * a zero there would be a value the type says cannot exist. The bottom of the range is always legal, and
 * a value that arrived as `NaN` carries no information to preserve — there is no "closest valid number"
 * to a thing that is not a number, so the choice is arbitrary and what matters is that it is in range,
 * documented, and the same every time.
 *
 * Boundary validation is still the caller's job where the input comes from outside the app:
 * [ConfigCodec] rejects a `NaN` in an imported file outright rather than quietly clamping it, because a
 * file containing one is corrupt and the user is better told so. This is the layer beneath that, for
 * objects assembled anywhere else.
 */
internal fun Float.clampFinite(min: Float, max: Float, fallback: Float = min): Float =
    if (isNaN()) fallback else coerceIn(min, max)
