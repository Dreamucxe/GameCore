package com.gamecore.aimlab.engine

import kotlin.math.roundToInt

/**
 * Pure statistical helpers shared by every mode and by the results screen.
 *
 * All of it is total: an empty input never divides by zero and never returns NaN — it returns a defined
 * zero-or-null, because a results screen must show "no data" rather than "NaN%", and §30 forbids inventing
 * a number where there is none. Every function documents its own formula.
 */
object Stats {

    /**
     * Accuracy as hits ÷ shots, in [0,1].
     *
     * Zero shots returns 0, not NaN: a session where nothing was fired has no accuracy, and 0 is the
     * honest floor to show. Hits above shots is clamped to 1 defensively — it should never happen, but a
     * corrupt import must not produce 140% accuracy.
     */
    fun accuracy(hits: Int, shots: Int): Float {
        if (shots <= 0) return 0f
        return (hits.toFloat() / shots).coerceIn(0f, 1f)
    }

    /** Accuracy as a whole-percent integer for display. Zero shots → 0. */
    fun accuracyPercent(hits: Int, shots: Int): Int = (accuracy(hits, shots) * 100).roundToInt()

    /**
     * The arithmetic mean of [values], or 0 for an empty list (never NaN).
     */
    fun mean(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        var sum = 0.0
        for (v in values) sum += v
        return (sum / values.size).toFloat()
    }

    /** Mean of a list of longs (e.g. reaction times in ms), or 0 for empty. */
    fun meanLong(values: List<Long>): Float {
        if (values.isEmpty()) return 0f
        var sum = 0.0
        for (v in values) sum += v
        return (sum / values.size).toFloat()
    }

    /**
     * The median of [values].
     *
     * Sorts a copy, then for an odd count returns the middle element and for an even count returns the
     * mean of the two middle elements. Empty returns 0. This is the standard median and the unit tests
     * check both parities and the empty case explicitly.
     */
    fun median(values: List<Long>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val n = sorted.size
        val mid = n / 2
        return if (n % 2 == 1) {
            sorted[mid].toFloat()
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2f
        }
    }

    /**
     * The [percentile]-th percentile of [values] by linear interpolation between closest ranks.
     *
     * [percentile] is 0..100. Empty returns 0. The rank is `p/100 * (n-1)`, and a fractional rank
     * interpolates between its neighbours — the same "linear interpolation" method NumPy uses by default,
     * chosen so p50 equals [median] for both parities.
     */
    fun percentile(values: List<Long>, percentile: Float): Float {
        if (values.isEmpty()) return 0f
        val p = percentile.coerceIn(0f, 100f)
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0].toFloat()
        val rank = p / 100f * (sorted.size - 1)
        val low = rank.toInt()
        val high = (low + 1).coerceAtMost(sorted.size - 1)
        val frac = rank - low
        return sorted[low] + (sorted[high] - sorted[low]) * frac
    }
}

/**
 * The reaction-test result set, computed once from a list of reaction times.
 *
 * Reaction times are milliseconds, each measured from target-appearance to the tap that hit it, against
 * the engine [Clock]. A missed or too-early tap is not in this list — only completed reactions — so
 * [attempts] is the number of genuine reactions, and an empty session yields the all-zero [EMPTY] rather
 * than a set of NaNs.
 */
data class ReactionStats(
    val attempts: Int,
    val fastestMillis: Long,
    val slowestMillis: Long,
    val averageMillis: Float,
    val medianMillis: Float,
) {
    companion object {
        val EMPTY = ReactionStats(0, 0L, 0L, 0f, 0f)

        /**
         * Folds a list of reaction times into the summary.
         *
         * Fastest is the min, slowest the max, average the mean, median via [Stats.median]. Empty → [EMPTY].
         */
        fun from(reactionsMillis: List<Long>): ReactionStats {
            if (reactionsMillis.isEmpty()) return EMPTY
            return ReactionStats(
                attempts = reactionsMillis.size,
                fastestMillis = reactionsMillis.min(),
                slowestMillis = reactionsMillis.max(),
                averageMillis = Stats.meanLong(reactionsMillis),
                medianMillis = Stats.median(reactionsMillis),
            )
        }
    }
}
