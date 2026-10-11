package com.gamecore.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gamecore.core.common.Formatters
import com.gamecore.core.model.BuiltinDashboardComponent
import com.gamecore.core.model.MetricHistory
import com.gamecore.core.model.SessionStatistics
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.LegendKey
import com.gamecore.ui.components.LineGraph
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.Tone

/**
 * The host-drawn content of the four [BuiltinDashboardComponent]s, as bare column children.
 *
 * Each function emits rows (or a graph) with no card of its own: the composer draws one card per placed
 * item and drops this inside it, so a built-in, a plugin widget and an unavailable item all sit in the
 * same chrome. Nothing here recomputes a figure. The game half reads straight off [SessionStatistics]
 * — the same aggregate, formatted with the same [Formatters] calls and the same labels the Sessions
 * screen uses (`SessionsViewModel.statisticRowsOf`) — so a number shown here is the number shown there,
 * not a second measurement that could drift. The Aim Lab half reads the already-filtered
 * [DashboardChartData] the composer built from 3D runs; it never re-filters a run list of its own.
 *
 * Every component carries an honest empty state: no game sessions, or too few scored 3D runs to plot,
 * says so plainly rather than drawing a flat line or a fabricated zero.
 */

/** The placeholder a measured-but-absent figure shows, matching the rest of the app. */
private const val ABSENT = "—"

/** Dispatches a placed built-in to its renderer. Bare content — the composer supplies the card. */
@Composable
fun BuiltinComponentContent(component: BuiltinDashboardComponent, data: DashboardChartData) {
    when (component) {
        BuiltinDashboardComponent.GAME_PLAYTIME -> GamePlaytimeContent(data.gameStats)
        BuiltinDashboardComponent.GAME_STATS -> GameStatsContent(data.gameStats)
        BuiltinDashboardComponent.AIMLAB_TREND -> AimTrendContent(data)
        BuiltinDashboardComponent.AIMLAB_SUMMARY -> AimSummaryContent(data)
    }
}

/** Headline play figures: the same total, count and longest-session the Sessions summary leads with. */
@Composable
private fun GamePlaytimeContent(stats: SessionStatistics) {
    if (!stats.hasData) {
        NoteBanner(text = "No game sessions recorded yet.", tone = Tone.Muted)
        return
    }
    KeyValueRow(label = "Total play time", value = Formatters.durationTotal(stats.totalPlayTimeMillis))
    KeyValueRow(label = "Sessions", value = stats.sessionCount.toString())
    KeyValueRow(label = "Longest session", value = Formatters.durationCoarse(stats.longestSessionMillis))
}

/**
 * The broader session readout: most-played, battery rate and hottest reading. Battery use is formatted
 * exactly as the Sessions screen formats it (`percentValue(.., 1) + " / hour"`), and absent when no
 * session was long enough or on battery throughout to measure a rate — the honest absence, not 0.
 */
@Composable
private fun GameStatsContent(stats: SessionStatistics) {
    if (!stats.hasData) {
        NoteBanner(text = "No game sessions recorded yet.", tone = Tone.Muted)
        return
    }
    stats.mostPlayedLabel?.let { KeyValueRow(label = "Most played", value = it) }
    KeyValueRow(
        label = "Battery use",
        value = stats.averageBatteryDrainPercentPerHour
            ?.let { Formatters.percentValue(it, decimals = 1) + " / hour" } ?: ABSENT,
    )
    stats.peakTemperatureDeciCelsius?.let {
        KeyValueRow(label = "Hottest reading", value = Formatters.temperature(it))
    }
}

/**
 * The 3D-accuracy trend as a bare line over 0–100%. Below [MetricHistory.MIN_PLOTTABLE] points there is
 * nothing honest to draw — the same floor [LineGraph] itself refuses to plot under — so it says how many
 * runs are needed instead of showing an empty grid.
 */
@Composable
private fun AimTrendContent(data: DashboardChartData) {
    if (data.aimAccuracySeries.size < MetricHistory.MIN_PLOTTABLE) {
        NoteBanner(
            text = "Play at least ${MetricHistory.MIN_PLOTTABLE} scored 3D runs to see your accuracy trend.",
            tone = Tone.Muted,
        )
        return
    }
    LineGraph(series = data.aimAccuracySeries, valueRange = 0f..100f)
    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendKey(label = "Accuracy", colour = MaterialTheme.colorScheme.primary)
    }
}

/** The Aim Lab figures as rows: run count, best and average accuracy over scored 3D runs. */
@Composable
private fun AimSummaryContent(data: DashboardChartData) {
    if (data.aimRunCount == 0) {
        NoteBanner(text = "No scored 3D runs yet.", tone = Tone.Muted)
        return
    }
    KeyValueRow(label = "3D runs", value = data.aimRunCount.toString())
    KeyValueRow(label = "Best accuracy", value = data.aimBestAccuracyPercent?.let { "$it%" } ?: ABSENT)
    KeyValueRow(
        label = "Average accuracy",
        value = data.aimAverageAccuracyPercent?.let { Formatters.percentValue(it, decimals = 1) } ?: ABSENT,
    )
}
