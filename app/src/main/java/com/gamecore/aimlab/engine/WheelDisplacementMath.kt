package com.gamecore.aimlab.engine

import com.gamecore.core.model.AppSettings

/**
 * The honest half of the high-sensitivity wheels feature (§Wheels): the input re-scale the guide ring only
 * hints at.
 *
 * The guide overlay [com.gamecore.core.overlay.WheelOverlay] draws is a sticker on the glass — a ring in a
 * `FLAG_NOT_TOUCHABLE` window that reads nothing and takes no touch — because GameCore cannot reach into
 * another game and re-scale its virtual joystick: that would need touch injection into a process it does not
 * own, which the platform does not offer and anti-cheat rightly treats as tampering. So the ring shows the
 * throw a player is working with, and *this* is where a faster wheel is actually delivered — inside
 * GameCore's own Aim Lab training surface, where there is a real on-screen stick to re-scale and no other
 * app to touch. [com.gamecore.aimlab.ui.input.TrainingSurface] feeds its stick displacement through
 * [apply] before it moves the training camera, exactly as it already runs look input through
 * [SensitivityMath].
 *
 * This is a plain gain on the raw displacement, which is the whole design: the wheels feature is "the same
 * stick, scaled", not a curve or a smoother, so it reuses the sensitivity model's clamping and its [Vec2]
 * output type but none of the response-curve or deadzone stages [SensitivityMath] adds. The one guarantee it
 * makes is [SensitivityMath]'s guarantee — a percent that arrived out of range, from a hand-edited settings
 * file or an older build's bounds, is clamped into [AppSettings.WHEEL_SENSITIVITY_MIN]..MAX before it can
 * scale anything, the same `coerceIn` the profile applies to its own percent fields in
 * [SensitivityProfile.normalised]. Stateless, unlike [SensitivityMath]'s smoothing: a gain has no memory.
 */
object WheelDisplacementMath {

    /**
     * The multiplier a given [sensitivityPercent] applies, exactly 1.0 at 100%.
     *
     * The percent is clamped into [AppSettings.WHEEL_SENSITIVITY_MIN]..MAX first — a value below the floor or
     * above the ceiling is not trusted just because it reached here — and divided by 100, so the neutral 100
     * lands on 1.0f and the range maps to x0.50–x3.00, the multiplier the screen shows the hand.
     */
    fun multiplier(sensitivityPercent: Int): Float {
        val clamped = sensitivityPercent
            .coerceIn(AppSettings.WHEEL_SENSITIVITY_MIN, AppSettings.WHEEL_SENSITIVITY_MAX)
        return clamped / 100f
    }

    /**
     * Scales one raw stick displacement by [multiplier], sign and direction preserved.
     *
     * Both axes take the same gain — a wheel is uniform, not per-axis, which is what makes it a wheel rather
     * than the axis scales [SensitivityProfile] carries — so a displacement keeps its angle and only its
     * length changes. Returns a [Vec2] in the same units it was handed, for the training surface to add to
     * the camera the way it adds a [SensitivityMath] delta.
     *
     * @param rawX raw horizontal stick displacement, in the surface's own units.
     * @param rawY raw vertical stick displacement.
     * @param sensitivityPercent the remap gain in percent, clamped by [multiplier] before it is applied.
     */
    fun apply(rawX: Float, rawY: Float, sensitivityPercent: Int): Vec2 {
        val gain = multiplier(sensitivityPercent)
        return Vec2(rawX * gain, rawY * gain)
    }
}
