package com.gamecore.aimlab.engine3d

import com.gamecore.aimlab.engine.SensitivityMath

/**
 * Turns a raw pointer or gyro delta into a yaw/pitch change in **degrees**, through the existing
 * sensitivity pipeline, in a resolution-independent way (§3).
 *
 * The 2D arena fed [SensitivityMath] a delta already normalised to the surface (a full swipe ≈ ±1) and
 * treated the result as arena units. In 3D the same profile has to move the *camera* by an angle, and
 * that angle must be the same whether the phone is 1080p or 1440p — a drag of one physical centimetre
 * should turn the view the same amount on both. So the raw pixel delta is converted to a fraction of the
 * surface first (dividing by width for both axes, so vertical and horizontal share one scale and a 45°
 * drag stays 45°), run through [SensitivityMath] exactly as before — deadzone, response curve,
 * sensitivity, axis scale, ADS, inversion, smoothing — and the unit-scale result is multiplied by
 * [DEGREES_PER_FULL_SWIPE] to become degrees.
 *
 * Gyro deltas already arrive in radians of device rotation from the sensor reader; they are converted to
 * degrees and fed through the same pipeline with `gyro = true`, so one profile governs both input methods
 * and neither invents a number the sensor did not report (§3: real sensor data only).
 *
 * Pure and android-free: it holds a [SensitivityMath] (whose only state is smoothing) and no more.
 */
class LookConversion(private val sensitivity: SensitivityMath) {

    /** Clears smoothing memory between runs, delegating to the wrapped pipeline. */
    fun reset() = sensitivity.reset()

    /**
     * A touch-drag delta in pixels → a (yawDelta, pitchDelta) in degrees.
     *
     * Both axes are divided by [surfaceWidthPx] on purpose: using width for Y too keeps the vertical and
     * horizontal degrees-per-pixel identical, so aim is not squashed on a tall surface. A drag *up*
     * (negative pixel Y, screen origin top-left) should raise the view (positive pitch), so pitch is the
     * negated Y. Returns yaw in `.first`, pitch in `.second`.
     */
    fun fromTouch(
        deltaXpx: Float,
        deltaYpx: Float,
        surfaceWidthPx: Int,
        aiming: Boolean,
    ): Pair<Float, Float> {
        if (surfaceWidthPx <= 0) return 0f to 0f
        val fracX = deltaXpx / surfaceWidthPx
        val fracY = deltaYpx / surfaceWidthPx
        val moved = sensitivity.apply(fracX, fracY, aiming = aiming, gyro = false)
        val yaw = moved.x * DEGREES_PER_FULL_SWIPE
        val pitch = -moved.y * DEGREES_PER_FULL_SWIPE
        return yaw to pitch
    }

    /**
     * A gyro rotation delta in radians → a (yawDelta, pitchDelta) in degrees.
     *
     * The sensor reader hands over integrated angular deltas about the device's yaw and pitch axes. They
     * are already angles, so they convert straight to degrees and go through the gyro branch of the
     * pipeline for the gyro sensitivity/ADS values. The full-swipe scale is *not* applied — a gyro delta
     * is not a fraction of the screen — the profile's gyro sensitivity is the only scale.
     */
    fun fromGyro(
        yawRadians: Float,
        pitchRadians: Float,
        aiming: Boolean,
    ): Pair<Float, Float> {
        val yawDeg = Math.toDegrees(yawRadians.toDouble()).toFloat()
        val pitchDeg = Math.toDegrees(pitchRadians.toDouble()).toFloat()
        // The pipeline's response curve expects small unit-scale inputs, so feed degrees scaled down to
        // that range and scale the result back; this keeps the curve meaningful for gyro too.
        val moved = sensitivity.apply(
            yawDeg / GYRO_CURVE_SCALE,
            pitchDeg / GYRO_CURVE_SCALE,
            aiming = aiming,
            gyro = true,
        )
        return (moved.x * GYRO_CURVE_SCALE) to (moved.y * GYRO_CURVE_SCALE)
    }

    companion object {
        /**
         * Degrees the view turns for a full-width swipe at sensitivity 1.0, linear curve.
         *
         * 180° means one edge-to-edge drag spins the view half a turn at base sensitivity — a middle-of-
         * the-road trainer default the sensitivity multiplier scales from. Not tuned to any real game.
         */
        const val DEGREES_PER_FULL_SWIPE = 180f

        /** The degree range mapped onto the pipeline's unit-scale curve input for gyro. */
        const val GYRO_CURVE_SCALE = 90f
    }
}
