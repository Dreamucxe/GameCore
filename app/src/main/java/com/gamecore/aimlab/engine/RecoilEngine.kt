package com.gamecore.aimlab.engine

import kotlin.math.abs

/**
 * Generates a weapon's recoil pattern and scores how well the player compensated for it.
 *
 * The pattern is the sequence of cumulative aim offsets the weapon imposes as it fires. It is generated
 * from the weapon's [RecoilSpec] and the injected [Rng] so that — for a given seed — the same weapon
 * always produces the same pattern (§B2.6: same seed → same pattern; different seed → different). The
 * randomised fraction of each kick is `randomness`; at 0 the pattern is fixed and learnable, at 1 it is
 * fully random each shot.
 *
 * Compensation is scored as how close the player's counter-movement came to cancelling the recoil: for
 * each shot, the residual is the recoil offset plus the player's applied counter-offset, and the score is
 * derived from the mean residual magnitude over the burst. Recovery, when not firing, decays the
 * accumulated offset back toward zero at `recoveryPerSecond`.
 */
class RecoilEngine(private val rng: Rng) {

    /**
     * Builds the cumulative recoil offsets for a burst of [shots] from [spec].
     *
     * Each shot adds a vertical kick (always upward, expressed as negative Y so "up" is consistent with a
     * screen whose origin is top-left) and a horizontal kick whose base sign alternates left/right so a
     * pattern drifts rather than walks off one side. The randomised part of each component is a gaussian
     * scaled by `randomness`; the fixed part is `(1 - randomness)`. Offsets accumulate, so element i is
     * the total displacement after i+1 shots — which is what the player is actually fighting.
     */
    fun pattern(spec: RecoilSpec, shots: Int): List<Vec2> {
        if (shots <= 0) return emptyList()
        val out = ArrayList<Vec2>(shots)
        var cx = 0f
        var cy = 0f
        for (i in 0 until shots) {
            val vFixed = spec.verticalPerShot * (1f - spec.randomness)
            val vRand = spec.verticalPerShot * spec.randomness * abs(rng.nextGaussian())
            cy -= vFixed + vRand // upward = negative Y

            val sign = if (i % 2 == 0) 1f else -1f
            val hFixed = spec.horizontalPerShot * (1f - spec.randomness) * sign
            val hRand = spec.horizontalPerShot * spec.randomness * rng.nextGaussian()
            cx += hFixed + hRand

            out.add(Vec2(cx, cy))
        }
        return out
    }

    /**
     * Decays an accumulated recoil [offset] toward zero over [elapsedSeconds] at [recoveryPerSecond].
     *
     * Exponential recovery: each second retains `e^(-recoveryPerSecond)` of the offset, so a higher rate
     * recovers faster and the offset approaches — but never overshoots — zero. The tests assert the
     * magnitude strictly decreases and heads toward the origin.
     */
    fun recover(offset: Vec2, elapsedSeconds: Float, recoveryPerSecond: Float): Vec2 {
        if (elapsedSeconds <= 0f || recoveryPerSecond <= 0f) return offset
        val retain = Math.exp(-(recoveryPerSecond * elapsedSeconds).toDouble()).toFloat()
        return Vec2(offset.x * retain, offset.y * retain)
    }

    /**
     * Scores compensation over a burst, in [0,1] where 1 is perfect cancellation.
     *
     * For each shot the residual is `recoilOffset + playerCounter` (the player's counter should be the
     * negative of the recoil to cancel it). The score is `1 - meanResidual / referenceError` clamped to
     * [0,1], where the reference is the mean recoil magnitude the player was fighting — so leaving the aim
     * uncompensated scores ~0 and cancelling it exactly scores 1. Mismatched list lengths score 0.
     */
    fun compensationScore(recoil: List<Vec2>, playerCounter: List<Vec2>): Float {
        if (recoil.isEmpty() || recoil.size != playerCounter.size) return 0f
        var residualSum = 0.0
        var recoilSum = 0.0
        for (i in recoil.indices) {
            val residual = recoil[i] + playerCounter[i]
            residualSum += residual.length
            recoilSum += recoil[i].length
        }
        if (recoilSum <= 0.0) return 1f // nothing to compensate for
        val ratio = residualSum / recoilSum
        return (1.0 - ratio).coerceIn(0.0, 1.0).toFloat()
    }
}
