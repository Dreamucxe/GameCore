package com.gamecore.aimlab.ui.stats

import com.gamecore.aimlab.engine.Stats
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.core.common.Formatters
import kotlin.math.roundToInt

/**
 * The figure the progress chart plots over time.
 *
 * Two metrics, because they are absent for different reasons and the screen has to say which. A session
 * with no shots has no [ACCURACY] — hits ÷ nothing is not 0%, it is nothing — and a session in an unscored
 * mode has no [SCORE], because `TrainingMode.scored` is false there and the engine returns a 0 it never
 * meant as a result. Either way the session is left out of the series rather than plotted on the floor.
 */
enum class TrendMetric(val label: String) {
    ACCURACY("Accuracy"),
    SCORE("Score"),
    ;

    /** One value as the user reads it — through [Formatters], so this matches every other screen. */
    fun format(value: Float): String = when (this) {
        ACCURACY -> Formatters.percent(value, 0)
        SCORE -> value.roundToInt().toString()
    }

    /**
     * A difference between two values, signed.
     *
     * Accuracy moves in *percentage points*, not percent: from 40% to 50% is ten points, and calling that
     * "+10%" would be a different (and wrong) claim about a 25% relative rise.
     */
    fun formatChange(delta: Float): String = when (this) {
        ACCURACY -> {
            val points = (delta * 100f).roundToInt()
            if (points > 0) "+$points pts" else "$points pts"
        }
        SCORE -> {
            val points = delta.roundToInt()
            if (points > 0) "+$points" else "$points"
        }
    }

    /** Why a session carries no value for this metric. The sentence the screen shows in place of a chart. */
    val absenceReason: String
        get() = when (this) {
            ACCURACY -> "Only sessions in which a shot was fired have an accuracy."
            SCORE -> "Only sessions in a scored mode have a score."
        }
}

/**
 * The whole history, added up.
 *
 * This value exists only when at least one session has been stored — [StatisticsState.overall] is null
 * otherwise — so every field in it is a count of something that really happened. That is why the fields are
 * non-null and [accuracyFraction] is not: sessions, time and shots are facts even when small, but accuracy
 * is a ratio, and with no shots there is no ratio to report (§30).
 *
 * [averageSessionMillis] comes from [Stats.meanLong] rather than a division written here, so the one mean
 * in the app is the tested one.
 */
data class OverallTotals(
    val sessions: Int,
    val trainingMillis: Long,
    val hits: Int,
    val shots: Int,
    val averageSessionMillis: Float,
    val longestSessionMillis: Long,
) {
    /** Hits ÷ shots across the whole history, or null when nothing has been fired. Never 0% by default. */
    val accuracyFraction: Float?
        get() = if (shots > 0) Stats.accuracy(hits, shots) else null
}

/**
 * One mode the user has actually trained in.
 *
 * A mode with no sessions does not get one of these at all — it appears in [StatisticsState.untrainedModes]
 * and is drawn as "not trained yet". A row of zeroes under a mode name reads as "you tried it and scored
 * nothing", which is a different and untrue statement.
 *
 * [bestScore] is null for an unscored mode rather than the 0 the engine returns for it, for the same reason.
 */
data class ModeBreakdown(
    val mode: TrainingMode,
    val sessions: Int,
    val trainingMillis: Long,
    val hits: Int,
    val shots: Int,
    val bestScore: Int?,
    val lastTrainedAtMillis: Long,
) {
    val accuracyFraction: Float?
        get() = if (shots > 0) Stats.accuracy(hits, shots) else null
}

/** One plotted session: when it was and what it measured. Never interpolated, never filled in. */
data class TrendPoint(val startedAtMillis: Long, val value: Float)

/**
 * Progress over time, as a series of real sessions.
 *
 * The rule this type exists to enforce is [MIN_POINTS]: **two points are the minimum trend**. One session
 * drawn on a chart is a horizontal line implying a stable figure over time, when all that is known is a
 * single measurement — so [isPlottable] is false and the screen says what the one session measured instead
 * of drawing it.
 *
 * [omitted] is how many stored sessions carry no value for this metric at all. Counting them out loud is
 * what stops the chart implying it covers the whole history when it does not.
 *
 * [axisMin]/[axisMax] are the printed vertical scale. Accuracy plots against its full 0–100% plate so the
 * height of a point means something absolute; a score auto-scales to the data, because a run of scores in
 * the four thousands plotted from zero is a flat line that hides the very movement the chart is for — and
 * when that happens [zeroBased] is false and the screen says so under the plot.
 */
data class TrendSeries(
    val metric: TrendMetric = TrendMetric.ACCURACY,
    val points: List<TrendPoint> = emptyList(),
    val axisMin: Float = 0f,
    val axisMax: Float = 1f,
    val average: Float? = null,
    val omitted: Int = 0,
) {
    /** Whether there is enough here to draw a line: two sessions, not one. */
    val isPlottable: Boolean get() = points.size >= MIN_POINTS

    /** The single measurement, when that is all there is. Null as soon as there are two or more. */
    val loneValue: Float? get() = if (points.size == 1) points[0].value else null

    /** Last minus first, across the plotted window. Null unless there is a window to speak of. */
    val change: Float?
        get() = if (isPlottable) points.last().value - points.first().value else null

    /** Whether the vertical scale starts at zero. When it does not, the screen has to say so. */
    val zeroBased: Boolean get() = axisMin <= 0f

    companion object {
        /** A trend needs at least two points. One is a reading; two is the first thing worth a line. */
        const val MIN_POINTS = 2
    }
}

/**
 * Everything the statistics screen draws, already reduced from stored sessions by the ViewModel.
 *
 * The screen renders this and calls back; it computes nothing. [loading] is kept distinct from "loaded and
 * empty" so the empty state — which is a considered, deliberate screen here, not a fallback — never flashes
 * up in the frame before the database answers.
 *
 * [overall] being null *is* the empty case: no sessions, so no totals, so no screen full of zeroes. Every
 * other field follows from real rows, and each one is absent rather than zeroed when the rows do not support
 * it (§1/§30).
 */
data class StatisticsState(
    val loading: Boolean = true,
    val metric: TrendMetric = TrendMetric.ACCURACY,
    val overall: OverallTotals? = null,
    val modes: List<ModeBreakdown> = emptyList(),
    val untrainedModes: List<TrainingMode> = emptyList(),
    val trend: TrendSeries = TrendSeries(),
) {
    /** The one switch between the statistics and the empty state. */
    val hasHistory: Boolean get() = overall != null
}
