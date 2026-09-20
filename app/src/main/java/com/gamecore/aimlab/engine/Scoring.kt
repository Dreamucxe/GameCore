package com.gamecore.aimlab.engine

import kotlin.math.roundToInt

/**
 * The per-mode scoring formulas, gathered in one place so every score is defined and testable.
 *
 * A "score" here is a single integer a personal record can rank, produced from a mode's raw counters.
 * Each formula is documented on its function and pinned by unit tests. None of them invents anything the
 * session did not measure — an empty session scores 0.
 */
object Scoring {

    /**
     * Flick score.
     *
     * Rewards hits, accuracy and speed: `hits * 100`, scaled by accuracy (so spraying misses is punished),
     * plus a speed bonus that falls off with average acquisition time. Zero hits → 0.
     *
     * `speedBonus = max(0, (TARGET_ACQUIRE_MS - avgAcquireMillis)) * hits / 10`, so faster-than-reference
     * acquisition adds points per hit and slower-than-reference adds none (never negative).
     */
    fun flick(hits: Int, shots: Int, avgAcquireMillis: Float): Int {
        if (hits <= 0) return 0
        val base = hits * 100
        val accuracyScaled = base * Stats.accuracy(hits, shots)
        val speedBonus = (TARGET_ACQUIRE_MS - avgAcquireMillis).coerceAtLeast(0f) * hits / 10f
        return (accuracyScaled + speedBonus).roundToInt()
    }

    /**
     * Tracking score, 0..10000.
     *
     * Time on target is the main driver; average error pulls it down. `timeOnTarget * 10000`, then
     * multiplied by an error factor `1 / (1 + averageError * ERROR_WEIGHT)` so tighter tracking scores
     * higher. Zero time on target → 0.
     */
    fun tracking(timeOnTargetFraction: Float, averageError: Float): Int {
        val onTarget = timeOnTargetFraction.coerceIn(0f, 1f)
        val errorFactor = 1f / (1f + averageError.coerceAtLeast(0f) * ERROR_WEIGHT)
        return (onTarget * 10_000f * errorFactor).roundToInt()
    }

    /**
     * Reaction score.
     *
     * Lower average reaction time is better, so the score is `max(0, REACTION_CEILING - averageMillis)`
     * scaled by accuracy (early/wrong taps that lowered accuracy cost points). Zero attempts → 0.
     */
    fun reaction(stats: ReactionStats, hits: Int, shots: Int): Int {
        if (stats.attempts <= 0) return 0
        val speed = (REACTION_CEILING - stats.averageMillis).coerceAtLeast(0f)
        return (speed * Stats.accuracy(hits, shots)).roundToInt()
    }

    /**
     * Recoil score, 0..10000.
     *
     * Directly the compensation score (0..1) times 10000. Compensation is computed by
     * [RecoilEngine.compensationScore].
     */
    fun recoil(compensationScore: Float): Int = (compensationScore.coerceIn(0f, 1f) * 10_000f).roundToInt()

    /**
     * Gyro training score, 0..10000.
     *
     * Combines tracking quality with stability: the tracking score scaled by a stability factor
     * `1 / (1 + correctionStdDev * STABILITY_WEIGHT)`, so smoother correction (lower standard deviation of
     * the per-frame correction magnitude) scores higher for the same time on target.
     */
    fun gyro(timeOnTargetFraction: Float, averageError: Float, correctionStdDev: Float): Int {
        val trackingScore = tracking(timeOnTargetFraction, averageError)
        val stabilityFactor = 1f / (1f + correctionStdDev.coerceAtLeast(0f) * STABILITY_WEIGHT)
        return (trackingScore * stabilityFactor).roundToInt()
    }

    /**
     * Movement training score.
     *
     * Movement mode is aim-while-moving: it scores like flick but scaled by a movement-consistency factor
     * in [0,1] the mode supplies (how steadily the player kept moving). `flick(...) * consistency`.
     */
    fun movement(hits: Int, shots: Int, avgAcquireMillis: Float, consistency: Float): Int =
        (flick(hits, shots, avgAcquireMillis) * consistency.coerceIn(0f, 1f)).roundToInt()

    /** Reference target-acquisition time in ms; faster than this earns the flick speed bonus. */
    const val TARGET_ACQUIRE_MS = 800f

    /** Reaction times below this ceiling (ms) earn a positive reaction score. */
    const val REACTION_CEILING = 600f

    /** How hard average tracking error is penalised in [tracking]. */
    const val ERROR_WEIGHT = 20f

    /** How hard correction jitter is penalised in [gyro]. */
    const val STABILITY_WEIGHT = 8f
}
