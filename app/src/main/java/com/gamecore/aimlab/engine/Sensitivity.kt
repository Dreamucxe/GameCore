package com.gamecore.aimlab.engine

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/**
 * One sensitivity profile: how raw pointer or gyro input becomes camera movement.
 *
 * These are training parameters, not a claim about any real game's sensitivity — GameCore cannot see a
 * game's input pipeline. The profile is applied by [SensitivityMath] and every field is documented there.
 * Enum-by-name and clamped in [normalised] so a hand-edited import cannot produce a curve the math does
 * not define.
 *
 * @param cameraSensitivity base multiplier for hip-fire look input.
 * @param adsMultiplier multiplier applied on top of [cameraSensitivity] while aiming down sights (≤1 slows).
 * @param gyroSensitivity base multiplier for gyroscope input.
 * @param gyroAdsMultiplier ADS multiplier for gyro input.
 * @param horizontalScale extra scale on the X axis only.
 * @param verticalScale extra scale on the Y axis only.
 * @param deadzonePercent input magnitude below this fraction of full-scale is treated as zero (0..100).
 * @param smoothingPercent exponential smoothing strength, 0 (none) to 100 (heavy).
 * @param responseExponent response-curve exponent: 1 linear, >1 eases small movements, <1 sharpens them.
 * @param invertX flip horizontal sign.
 * @param invertY flip vertical sign.
 */
data class SensitivityProfile(
    val id: Long = 0L,
    val name: String,
    val preset: SensitivityPreset = SensitivityPreset.CUSTOM,
    val cameraSensitivity: Float = 1.0f,
    val adsMultiplier: Float = 1.0f,
    val gyroSensitivity: Float = 1.0f,
    val gyroAdsMultiplier: Float = 1.0f,
    val horizontalScale: Float = 1.0f,
    val verticalScale: Float = 1.0f,
    val deadzonePercent: Int = 0,
    val smoothingPercent: Int = 0,
    val responseExponent: Float = 1.0f,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
) {
    fun normalised(): SensitivityProfile = copy(
        cameraSensitivity = cameraSensitivity.clampFinite(MIN_SENS, MAX_SENS, fallback = 1f),
        adsMultiplier = adsMultiplier.clampFinite(MIN_MULT, MAX_MULT, fallback = 1f),
        gyroSensitivity = gyroSensitivity.clampFinite(MIN_SENS, MAX_SENS, fallback = 1f),
        gyroAdsMultiplier = gyroAdsMultiplier.clampFinite(MIN_MULT, MAX_MULT, fallback = 1f),
        horizontalScale = horizontalScale.clampFinite(MIN_MULT, MAX_MULT, fallback = 1f),
        verticalScale = verticalScale.clampFinite(MIN_MULT, MAX_MULT, fallback = 1f),
        deadzonePercent = deadzonePercent.coerceIn(0, MAX_DEADZONE),
        smoothingPercent = smoothingPercent.coerceIn(0, 100),
        responseExponent = responseExponent.clampFinite(MIN_EXPONENT, MAX_EXPONENT, fallback = 1f),
    )

    companion object {
        const val MIN_SENS = 0.05f
        const val MAX_SENS = 10f
        const val MIN_MULT = 0.05f
        const val MAX_MULT = 5f
        const val MIN_EXPONENT = 0.4f
        const val MAX_EXPONENT = 3.0f

        /** A deadzone above this would swallow most real input; capped so a profile stays usable. */
        const val MAX_DEADZONE = 40
    }
}

/** The named starting points §10 asks for. CUSTOM means "the values are the user's own". */
enum class SensitivityPreset(val label: String, val camera: Float, val gyro: Float) {
    LOW("Low", camera = 0.6f, gyro = 0.6f),
    MEDIUM("Medium", camera = 1.0f, gyro = 1.0f),
    HIGH("High", camera = 1.6f, gyro = 1.6f),
    CUSTOM("Custom", camera = 1.0f, gyro = 1.0f),
    ;

    companion object {
        fun fromName(name: String?): SensitivityPreset = entries.firstOrNull { it.name == name } ?: CUSTOM
    }
}

/**
 * Applies a [SensitivityProfile] to raw input deltas. Pure and stateful only for smoothing.
 *
 * The pipeline, in order, per axis (documented because §31/tests pin each step):
 *  1. **Deadzone.** If the input vector's magnitude is below `deadzonePercent%` of full-scale (1.0), the
 *     output is exactly zero. Applied to the magnitude, not per-axis, so a diagonal nudge is not clipped
 *     to a cardinal one.
 *  2. **Response curve.** Each axis is raised to `responseExponent` in magnitude, sign preserved:
 *     `out = sign(in) * |in|^exponent`. Exponent 1 is linear; >1 eases small movements (fine aim); <1
 *     sharpens them. Monotonic in |in| for any positive exponent, which the tests assert.
 *  3. **Sensitivity + axis scale + ADS.** Multiply by base sensitivity, the per-axis scale, and — when
 *     aiming — the ADS multiplier.
 *  4. **Inversion.** Flip the sign of X and/or Y last, so it composes cleanly with everything above.
 *  5. **Smoothing.** Exponential moving average toward the new value; `smoothingPercent` is the weight of
 *     the *previous* output, so 0 is no smoothing and higher lags more. Stateful across calls.
 *
 * A fresh instance per session; [reset] clears the smoothing memory between runs.
 */
class SensitivityMath(private val profile: SensitivityProfile) {

    private var smoothedX = 0f
    private var smoothedY = 0f
    private var hasPrevious = false

    fun reset() {
        smoothedX = 0f
        smoothedY = 0f
        hasPrevious = false
    }

    /**
     * Transforms one raw input delta into a camera-movement delta.
     *
     * @param rawX raw horizontal delta in full-scale units (a full swipe ≈ ±1).
     * @param rawY raw vertical delta.
     * @param aiming whether ADS multipliers apply.
     * @param gyro whether to use the gyro sensitivity/ADS pair instead of the camera pair.
     */
    fun apply(rawX: Float, rawY: Float, aiming: Boolean, gyro: Boolean = false): Vec2 {
        // 1. Deadzone on magnitude.
        val magnitude = kotlin.math.hypot(rawX, rawY)
        val deadzone = profile.deadzonePercent / 100f
        if (magnitude < deadzone) {
            // Still feed the smoother a zero so a held-still finger settles to rest rather than freezing.
            return smooth(0f, 0f)
        }

        // 2. Response curve, sign-preserving.
        val curvedX = curve(rawX)
        val curvedY = curve(rawY)

        // 3. Sensitivity, axis scale, ADS.
        val base = if (gyro) profile.gyroSensitivity else profile.cameraSensitivity
        val ads = when {
            !aiming -> 1f
            gyro -> profile.gyroAdsMultiplier
            else -> profile.adsMultiplier
        }
        var outX = curvedX * base * profile.horizontalScale * ads
        var outY = curvedY * base * profile.verticalScale * ads

        // 4. Inversion.
        if (profile.invertX) outX = -outX
        if (profile.invertY) outY = -outY

        // 5. Smoothing.
        return smooth(outX, outY)
    }

    private fun curve(value: Float): Float {
        if (value == 0f) return 0f
        return value.sign * abs(value).pow(profile.responseExponent)
    }

    private fun smooth(x: Float, y: Float): Vec2 {
        val weight = profile.smoothingPercent / 100f
        if (!hasPrevious || weight <= 0f) {
            smoothedX = x
            smoothedY = y
            hasPrevious = true
            return Vec2(x, y)
        }
        smoothedX = smoothedX * weight + x * (1f - weight)
        smoothedY = smoothedY * weight + y * (1f - weight)
        return Vec2(smoothedX, smoothedY)
    }
}
