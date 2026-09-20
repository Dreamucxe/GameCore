package com.gamecore.aimlab.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.aimlab.AimLabRepository
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.Stats
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.core.common.DefaultDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Aggregates the stored session history into the statistics screen's state.
 *
 * All of the arithmetic lives here, and it runs on the injected [DefaultDispatcher] — [flowOn] covers the
 * whole upstream, so the grouping, summing and sorting of a history that grows without bound never happens
 * on the main thread, and the composable receives a finished value it only has to draw (§21).
 *
 * The one rule the whole file is written around: **nothing is invented**. There is no synthetic path, no
 * sample series and no default figure standing in for a missing one. With no stored sessions this emits a
 * state whose [StatisticsState.overall] is null and the screen shows its empty state; a mode with no
 * sessions never becomes a row of zeroes; and every mean here goes through [Stats] rather than a second
 * division written locally, so the screen and the engine cannot drift apart.
 *
 * [SharingStarted.WhileSubscribed] with the section's usual grace keeps the database flows observed across a
 * rotation and a hop to a mode screen and back, then releases them.
 */
@HiltViewModel
class StatisticsViewModel @Inject constructor(
    repository: AimLabRepository,
    @DefaultDispatcher private val computation: CoroutineDispatcher,
) : ViewModel() {

    /** Which figure the chart plots. The only thing on this screen the user can change. */
    private val metric = MutableStateFlow(TrendMetric.ACCURACY)

    val state: StateFlow<StatisticsState> = combine(
        repository.sessions,
        repository.sessionCount,
        metric,
    ) { sessions, count, chosen ->
        aggregate(sessions = sessions, count = count, metric = chosen)
    }
        // Everything above this line — including the fold itself — runs off the main thread.
        .flowOn(computation)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
            // loading = true until the first real emission, so the empty state cannot appear for a frame
            // before the database has actually said the history is empty.
            initialValue = StatisticsState(),
        )

    fun onMetricSelected(chosen: TrendMetric) = metric.update { chosen }

    // --------------------------------------------------------------------------------- aggregation

    /**
     * Folds the stored sessions into the screen's state. Pure: same input, same output, no clock read.
     *
     * An empty list short-circuits to a loaded-but-empty state. [count] is the repository's authoritative
     * row count and can briefly exceed the observed list, so the displayed session total takes the larger of
     * the two — but it is never used to manufacture totals the list cannot support, because time, hits and
     * shots can only be summed from sessions actually in hand.
     */
    private fun aggregate(
        sessions: List<SessionSummary>,
        count: Int,
        metric: TrendMetric,
    ): StatisticsState {
        if (sessions.isEmpty()) return StatisticsState(loading = false, metric = metric)

        val durations = sessions.map { it.durationMillis }
        val overall = OverallTotals(
            sessions = count.coerceAtLeast(sessions.size),
            trainingMillis = durations.sum(),
            hits = sessions.sumOf { it.hits },
            shots = sessions.sumOf { it.shots },
            // Stats.meanLong, not a local division: one mean in the app, and it is the tested one.
            averageSessionMillis = Stats.meanLong(durations),
            longestSessionMillis = durations.max(),
        )

        val grouped = sessions.groupBy { it.mode }
        val modes = grouped
            .map { (mode, ofMode) -> breakdown(mode, ofMode) }
            .sortedWith(
                compareByDescending<ModeBreakdown> { it.sessions }
                    .thenByDescending { it.lastTrainedAtMillis },
            )

        return StatisticsState(
            loading = false,
            metric = metric,
            overall = overall,
            modes = modes,
            // Modes with no sessions are named as untrained rather than listed at zero.
            untrainedModes = TrainingMode.entries.filterNot { grouped.containsKey(it) },
            trend = trend(sessions, metric),
        )
    }

    /** One mode's totals, from its own sessions only. */
    private fun breakdown(mode: TrainingMode, ofMode: List<SessionSummary>): ModeBreakdown =
        ModeBreakdown(
            mode = mode,
            sessions = ofMode.size,
            trainingMillis = ofMode.sumOf { it.durationMillis },
            hits = ofMode.sumOf { it.hits },
            shots = ofMode.sumOf { it.shots },
            // An unscored mode's sessions all carry the 0 the engine returns for them. That 0 is an
            // absence, not a best, so it is dropped rather than reported.
            bestScore = if (mode.scored) ofMode.maxOf { it.score } else null,
            lastTrainedAtMillis = ofMode.maxOf { it.startedAtMillis },
        )

    /**
     * The progress series: the most recent sessions that actually measured [metric], oldest first.
     *
     * Sessions that cannot produce the value are filtered out before the window is taken, so the chart is
     * up to [TREND_WINDOW] real points rather than a window of sessions with gaps in it — and how many were
     * left out is carried in [TrendSeries.omitted] so the screen can say so.
     *
     * The vertical scale differs by metric on purpose. Accuracy uses the full 0–1 plate, so a point's height
     * is an absolute reading. A score has no natural ceiling, so the scale fits the data (with a one-unit
     * spread forced when every score is identical, which would otherwise divide by zero); the screen
     * discloses that the axis does not start at zero.
     */
    private fun trend(sessions: List<SessionSummary>, metric: TrendMetric): TrendSeries {
        val measured = sessions
            .sortedBy { it.startedAtMillis }
            .mapNotNull { session ->
                val value = when (metric) {
                    // No shots is no accuracy. Stats.accuracy would return 0 here, and 0% would read as
                    // "missed everything" for a session in which nothing was attempted.
                    TrendMetric.ACCURACY ->
                        if (session.shots > 0) Stats.accuracy(session.hits, session.shots) else null
                    TrendMetric.SCORE ->
                        if (session.mode.scored) session.score.toFloat() else null
                }
                value?.let { TrendPoint(startedAtMillis = session.startedAtMillis, value = it) }
            }

        val points = measured.takeLast(TREND_WINDOW)
        if (points.isEmpty()) {
            return TrendSeries(metric = metric, omitted = sessions.size)
        }

        val values = points.map { it.value }
        val low = values.min()
        val high = values.max()
        val axis = when (metric) {
            TrendMetric.ACCURACY -> 0f to 1f
            TrendMetric.SCORE ->
                if (high - low < 1f) (low - 1f).coerceAtLeast(0f) to (high + 1f) else low to high
        }

        return TrendSeries(
            metric = metric,
            points = points,
            axisMin = axis.first,
            axisMax = axis.second,
            average = Stats.mean(values),
            omitted = sessions.size - measured.size,
        )
    }

    private companion object {
        /** The same grace the section's other dashboards use, so this screen feels no different. */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L

        /**
         * How many sessions the chart plots.
         *
         * Enough to show a direction, few enough that each point is still a distinguishable session on a
         * phone-width canvas. Older sessions are not averaged into the edge of the plot — they are simply
         * outside the window, and the card says which dates it covers.
         */
        const val TREND_WINDOW = 20
    }
}
