package com.gamecore.aimlab.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.core.common.Formatters
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatEntry
import com.gamecore.ui.components.StatStrip
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import kotlin.math.roundToLong

/**
 * Statistics: what the stored sessions add up to, and nothing else.
 *
 * Three absences define this screen, and each of them is deliberate.
 *
 *  - **With no sessions there are no statistics.** The screen shows its empty state, not a grid of zeroes.
 *    A "0 sessions · 0% accuracy" summary is a result-shaped report of an absence, which §1/§30 rule out.
 *  - **A mode never played shows "not trained yet".** It gets no card, no 0% and no empty bar — those would
 *    say "you tried this and failed at it", which is a different and untrue claim.
 *  - **A trend needs two points.** One session is a reading, not a direction, so the card prints what that
 *    one session measured and says a trend needs another rather than drawing a flat line across the width.
 *
 * Nothing here computes anything: [StatisticsViewModel] does the grouping and summing off the main thread
 * and hands down a finished [StatisticsState]. This file decides only how a figure — and a missing figure —
 * is drawn.
 *
 * @param onBack leave the screen.
 */
@Composable
fun StatisticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatisticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val padded = Modifier.padding(horizontal = ScreenPadding)
    val overall = state.overall

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Statistics",
                subtitle = "Counted from the sessions you have actually run.",
                onBack = onBack,
            )
        }

        when {
            // The first frame, before the database has answered. Showing the empty state here would make
            // a user with a long history see "no sessions yet" flash past on every entry.
            state.loading -> Unit

            overall == null -> item {
                EmptyState(
                    icon = Icons.Filled.Insights,
                    title = "No sessions yet",
                    message = "Statistics are worked out from sessions you have run. Train in any mode and " +
                        "your totals, your per-mode breakdown and your progress over time will appear here.",
                    actionLabel = "Back to Aim Lab",
                    onAction = onBack,
                )
            }

            else -> statistics(
                state = state,
                totals = overall,
                padded = padded,
                onMetric = viewModel::onMetricSelected,
            )
        }
    }
}

/**
 * The screen proper, for the case where there is a history to report.
 *
 * A [LazyListScope] extension taking a non-null [totals] rather than a branch inside the list builder: the
 * "there are statistics" case is only reachable with real totals in hand, and expressing that in the
 * signature is what stops a zeroed placeholder ever being constructed to satisfy a nullable parameter.
 */
private fun LazyListScope.statistics(
    state: StatisticsState,
    totals: OverallTotals,
    padded: Modifier,
    onMetric: (TrendMetric) -> Unit,
) {
    item { OverallCard(totals = totals, modifier = padded) }

    item {
        TrendCard(
            trend = state.trend,
            onMetric = onMetric,
            modifier = padded,
        )
    }

    item {
        Text(
            text = "By mode",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = padded,
        )
    }

    items(items = state.modes, key = { it.mode.name }) { breakdown ->
        ModeCard(breakdown = breakdown, modifier = padded)
    }

    if (state.untrainedModes.isNotEmpty()) {
        item { UntrainedCard(modes = state.untrainedModes, modifier = padded) }
    }
}

// ------------------------------------------------------------------------------------------ totals

/**
 * The whole history in one card.
 *
 * Sessions, time and the shot counts are sums of real rows. Accuracy is a ratio, so it is only shown when
 * something was fired — a user whose history is all reaction tests sees the reason rather than "0%".
 */
@Composable
private fun OverallCard(totals: OverallTotals, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Overall",
        subtitle = "Every session you have stored.",
        icon = Icons.Filled.Adjust,
        modifier = modifier,
    ) {
        StatStrip(
            entries = listOf(
                StatEntry(label = "Sessions", value = totals.sessions.toString()),
                StatEntry(label = "Trained", value = Formatters.durationTotal(totals.trainingMillis)),
                accuracyEntry(totals.accuracyFraction),
            ),
        )
        RowDivider()
        if (totals.shots > 0) {
            KeyValueRow(label = "Hits", value = totals.hits.toString(), tone = Tone.Good)
            KeyValueRow(label = "Shots", value = totals.shots.toString())
        } else {
            KeyValueRow(label = "Shots", value = "None recorded", tone = Tone.Muted)
        }
        KeyValueRow(
            label = "Average session",
            value = Formatters.durationCoarse(totals.averageSessionMillis.roundToLong()),
        )
        KeyValueRow(
            label = "Longest session",
            value = Formatters.durationCoarse(totals.longestSessionMillis),
        )
    }
}

// ------------------------------------------------------------------------------------------- trend

/**
 * Progress over time, with the two cases where there is no line to draw handled before the chart is.
 *
 * The metric chooser stays visible in all three cases: "no score anywhere in your history" is itself an
 * answer the user asked for by tapping Score, and hiding the control would leave them unable to get back.
 */
@Composable
private fun TrendCard(
    trend: TrendSeries,
    onMetric: (TrendMetric) -> Unit,
    modifier: Modifier = Modifier,
) {
    val change = trend.change
    // Read once into locals: both are computed properties, and the branches below have to be able to use
    // the value they just tested rather than asking again.
    val lone = trend.loneValue
    val average = trend.average
    SectionCard(
        title = "Progress over time",
        subtitle = "One point per session, oldest on the left.",
        icon = Icons.Filled.Insights,
        modifier = modifier,
        action = {
            if (change != null) {
                StatusChip(
                    text = trend.metric.formatChange(change),
                    tone = when {
                        change > 0f -> Tone.Good
                        change < 0f -> Tone.Warning
                        else -> Tone.Muted
                    },
                )
            }
        },
    ) {
        ChoiceRow(
            options = TrendMetric.entries,
            selected = trend.metric,
            onSelect = onMetric,
            label = { it.label },
            perRow = 2,
        )
        Spacer(modifier = Modifier.height(10.dp))

        when {
            // Nothing in the history measures this at all.
            trend.points.isEmpty() -> NoteBanner(
                text = "None of your sessions carry a ${trend.metric.label.lowercase()}. " +
                    trend.metric.absenceReason,
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )

            // Exactly one. A line through a single point is a claim about change that no data supports,
            // so the reading is printed and the shortfall stated.
            lone != null -> NoteBanner(
                text = "One session so far measured ${trend.metric.label.lowercase()}: " +
                    "${trend.metric.format(lone)}, on " +
                    "${Formatters.relativeDay(trend.points[0].startedAtMillis)}. A trend needs at least " +
                    "${TrendSeries.MIN_POINTS} sessions, so there is nothing to plot yet.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )

            else -> {
                TrendChart(series = trend)
                RowDivider()
                KeyValueRow(label = "Sessions plotted", value = trend.points.size.toString())
                if (average != null) {
                    KeyValueRow(
                        label = "Average across them",
                        value = trend.metric.format(average),
                    )
                }
                if (change != null) {
                    KeyValueRow(
                        label = "First to latest",
                        value = trend.metric.formatChange(change),
                        tone = when {
                            change > 0f -> Tone.Good
                            change < 0f -> Tone.Warning
                            else -> Tone.Muted
                        },
                    )
                }
                if (!trend.zeroBased) {
                    ChartNote(
                        "The scale starts at ${trend.metric.format(trend.axisMin)} rather than zero, so " +
                            "the shape shows the movement between these sessions.",
                    )
                }
                if (trend.omitted > 0) {
                    ChartNote(
                        "${Formatters.count(trend.omitted, "stored session")} " +
                            "${if (trend.omitted == 1) "is" else "are"} not on this chart. " +
                            trend.metric.absenceReason,
                    )
                }
            }
        }
    }
}

/**
 * The line itself: a path through one point per session, with both axes labelled.
 *
 * Hand-drawn on a `Canvas` for the same reason the rest of the app's plots are — a charting dependency to
 * draw at most twenty points would be larger than the feature. The vertical scale is printed beside the
 * plot and the dates of the first and last session beneath it, because a line without a scale is a shape
 * rather than a measurement.
 *
 * Points are spaced evenly across the width rather than by date. These are sessions, not samples of a
 * continuous signal: spacing them by timestamp would squash a fortnight of daily practice against a gap
 * from a month the user was away, and the dot per point is there to keep it clear that each one is a
 * session that happened rather than a reading off a curve.
 *
 * Colours are read here and captured by the draw lambda — a draw scope cannot ask the theme anything.
 */
@Composable
private fun TrendChart(series: TrendSeries, modifier: Modifier = Modifier) {
    val lineColour = MaterialTheme.colorScheme.primary
    val gridColour = MaterialTheme.colorScheme.outlineVariant
    val points = series.points
    val axisMin = series.axisMin
    // Guarded against a zero-width range: identical values must draw a line, not divide by zero.
    val span = (series.axisMax - axisMin).takeIf { it > 0f } ?: 1f

    Row(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .height(ChartHeight)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            AxisLabel(series.metric.format(series.axisMax))
            AxisLabel(series.metric.format(axisMin))
        }
        Column(modifier = Modifier.weight(1f)) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ChartHeight),
            ) {
                val stroke = 2.2f * density
                val top = stroke / 2f
                val usable = (size.height - stroke).coerceAtLeast(1f)
                val stepX = if (points.size > 1) size.width / (points.size - 1) else size.width

                repeat(GRID_LINES) { index ->
                    val y = (size.height * index / (GRID_LINES - 1).toFloat())
                        .coerceIn(0.5f, size.height - 0.5f)
                    drawLine(
                        color = gridColour,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f,
                    )
                }

                fun yFor(value: Float): Float {
                    val fraction = ((value - axisMin) / span).coerceIn(0f, 1f)
                    return top + usable * (1f - fraction)
                }

                val line = Path()
                points.forEachIndexed { index, point ->
                    val x = stepX * index
                    val y = yFor(point.value)
                    if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
                }
                drawPath(
                    path = line,
                    color = lineColour,
                    style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                points.forEachIndexed { index, point ->
                    drawCircle(
                        color = lineColour,
                        radius = stroke,
                        center = Offset(stepX * index, yFor(point.value)),
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AxisLabel(Formatters.relativeDay(points.first().startedAtMillis))
                AxisLabel(Formatters.relativeDay(points.last().startedAtMillis))
            }
        }
    }
}

@Composable
private fun AxisLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A caveat the line cannot carry, under the plot where the reader meets it. */
@Composable
private fun ChartNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

// -------------------------------------------------------------------------------------- per mode

/**
 * One mode the user has trained in, from that mode's sessions alone.
 *
 * "Best score" appears only where the mode is scored. In the unscored sandbox the engine returns 0 for
 * every session, and a "Best score: 0" line would present that placeholder as an achievement.
 */
@Composable
private fun ModeCard(breakdown: ModeBreakdown, modifier: Modifier = Modifier) {
    val bestScore = breakdown.bestScore
    SectionCard(
        title = breakdown.mode.label,
        subtitle = "${Formatters.count(breakdown.sessions, "session")} · last trained " +
            Formatters.relativeDay(breakdown.lastTrainedAtMillis),
        icon = Icons.Filled.SportsEsports,
        modifier = modifier,
    ) {
        StatStrip(
            entries = listOf(
                StatEntry(label = "Trained", value = Formatters.durationTotal(breakdown.trainingMillis)),
                StatEntry(label = "Hits", value = breakdown.hits.toString(), tone = Tone.Good),
                accuracyEntry(breakdown.accuracyFraction),
            ),
        )
        RowDivider()
        if (breakdown.shots > 0) {
            KeyValueRow(label = "Shots", value = breakdown.shots.toString())
        } else {
            KeyValueRow(label = "Shots", value = "None recorded", tone = Tone.Muted)
        }
        if (bestScore != null) {
            KeyValueRow(label = "Best score", value = bestScore.toString(), tone = Tone.Accent)
        } else {
            KeyValueRow(label = "Score", value = "Not scored in this mode", tone = Tone.Muted)
        }
    }
}

/**
 * The modes with no history, named rather than drawn at zero.
 *
 * This card is the positive form of the rule: the user can see that flick training is missing from their
 * statistics because they have never run it, which is information — as opposed to a flick card reading 0%,
 * which would be a fabrication.
 */
@Composable
private fun UntrainedCard(modes: List<TrainingMode>, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Not trained yet",
        subtitle = "No sessions stored for these, so there is nothing to report on them.",
        icon = Icons.Filled.Info,
        modifier = modifier,
    ) {
        modes.forEach { mode ->
            KeyValueRow(label = mode.label, value = "Not trained yet", tone = Tone.Muted)
        }
    }
}

// --------------------------------------------------------------------------------------- shared

/**
 * Accuracy as a stat cell, including the case where there is none.
 *
 * A null fraction is the absence marker in the muted tone, never "0%": 0% means every shot missed, and a
 * history with no shots in it has not missed anything.
 */
private fun accuracyEntry(fraction: Float?): StatEntry = StatEntry(
    label = "Accuracy",
    value = fraction?.let { Formatters.percent(it, 0) } ?: ABSENT,
    tone = when {
        fraction == null -> Tone.Muted
        fraction >= 0.75f -> Tone.Good
        fraction >= 0.45f -> Tone.Neutral
        else -> Tone.Warning
    },
)

/** The height the trend chart is drawn at, matching the app's other plots. */
private val ChartHeight = 132.dp

/** Floor, middle and ceiling of the printed range. */
private const val GRID_LINES = 3
