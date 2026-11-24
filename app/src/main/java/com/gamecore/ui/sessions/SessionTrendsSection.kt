package com.gamecore.ui.sessions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gamecore.core.common.Formatters
import com.gamecore.core.model.SessionTrendAggregate
import com.gamecore.core.model.SessionTrendMetric
import com.gamecore.core.model.SessionTrendPoint
import com.gamecore.core.model.SessionTrendRange
import com.gamecore.core.model.SessionTrendReport
import com.gamecore.core.model.SessionTrendSession
import com.gamecore.core.model.SessionTrendSummary
import com.gamecore.core.model.SessionTrends
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceChip
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SectionLabel
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.theme.GameCoreTheme
import com.gamecore.ui.theme.Spacing
import com.gamecore.ui.theme.StatLabelStyle
import com.gamecore.ui.theme.StatValueCompactStyle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * §5.10's Session Trends, below the all-time totals and above the history.
 *
 * Three deliberate absences shape this section:
 *
 *  - **1% low and frame-time jank are not cards.** A session stores one window-average frame rate; it
 *    does not store per-frame times. Drawing either figure would mean inventing it, so the section says
 *    why they are missing instead of showing a plausible-looking number.
 *  - **No colour is the only carrier of a meaning.** A delta is an arrow, a signed amount and the word
 *    "better" or "worse"; the chart has a table with the same numbers and a spoken summary.
 *  - **The range is calendar days, not a rolling pair of periods.** The card says "compared with the
 *    previous 7 days" because that is exactly what the bounded queries asked for, and today's partial
 *    total is called out in the section subtitle.
 */
@Composable
fun SessionTrendsSection(
    state: SessionsUiState,
    onRange: (SessionTrendRange) -> Unit,
    onPackage: (String?) -> Unit,
    onMetric: (SessionTrendMetric) -> Unit,
    onShowTable: (Boolean) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionLabel(
            text = "Trends",
            subtitle = "Game sessions only. Each range is compared with the same number of days before " +
                "it; today is included as far as it has been recorded.",
        )
        Spacer(modifier = Modifier.height(10.dp))
        TrendControls(state = state, onRange = onRange, onPackage = onPackage)
        Spacer(modifier = Modifier.height(Spacing.md))

        val report = state.trends
        state.trendsError?.let { error ->
            NoteBanner(
                text = error,
                tone = Tone.Warning,
                icon = Icons.Filled.Info,
                action = { TextButton(onClick = onRetry) { Text("Retry") } },
            )
            if (report != null) Spacer(modifier = Modifier.height(Spacing.md))
        }
        when {
            report == null && state.trendsLoading -> TrendLoadingCard()
            report == null -> Unit
            !report.hasData -> TrendEmptyCard(
                report = report,
                label = state.trendFilterLabel,
                onRange = onRange,
                onPackage = onPackage,
            )
            else -> {
                if (report.conditionChanges.isNotEmpty()) {
                    ConditionsBanner(report = report)
                    Spacer(modifier = Modifier.height(Spacing.md))
                }
                TrendMetricGrid(report = report)
                Spacer(modifier = Modifier.height(Spacing.md))
                FrameConsistencyNote()
                Spacer(modifier = Modifier.height(Spacing.md))
                BestWorstCard(report = report)
                Spacer(modifier = Modifier.height(Spacing.md))
                DailyTrendCard(
                    report = report,
                    metric = state.trendMetric,
                    showTable = state.trendShowTable,
                    onMetric = onMetric,
                    onShowTable = onShowTable,
                )
            }
        }
    }
}

// ----------------------------------------------------------------------------------- controls

/** Range and game, in one card so the section has one control surface rather than two rails. */
@Composable
private fun TrendControls(
    state: SessionsUiState,
    onRange: (SessionTrendRange) -> Unit,
    onPackage: (String?) -> Unit,
) {
    PlainCard(contentPadding = Spacing.md) {
        Text(
            text = "Range",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        ChoiceRow(
            options = SessionTrendRange.entries,
            selected = state.trendRange,
            onSelect = onRange,
            label = { it.label },
            perRow = 3,
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Game",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            TrendGameMenu(
                state = state,
                onPackage = onPackage,
                modifier = Modifier.widthIn(max = 190.dp),
            )
        }
    }
}

@Composable
private fun TrendGameMenu(
    state: SessionsUiState,
    onPackage: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = state.trendFilterLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("All games") },
                onClick = {
                    expanded = false
                    onPackage(null)
                },
            )
            state.games.filter { it.packageName != null }.forEach { game ->
                DropdownMenuItem(
                    text = { Text("${game.label} · ${game.count}") },
                    onClick = {
                        expanded = false
                        onPackage(game.packageName)
                    },
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------- states

@Composable
private fun TrendLoadingCard() {
    PlainCard {
        Text(
            text = "Reading session history…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Nothing was recorded in this range.
 *
 * The previous period is named when it has sessions, because "the last 7 days were quiet" and "you have
 * never played" are different answers and only one of them is fixed by a wider range.
 */
@Composable
private fun TrendEmptyCard(
    report: SessionTrendReport,
    label: String,
    onRange: (SessionTrendRange) -> Unit,
    onPackage: (String?) -> Unit,
) {
    val nextRange = when (report.range) {
        SessionTrendRange.WEEK -> SessionTrendRange.MONTH
        SessionTrendRange.MONTH -> SessionTrendRange.QUARTER
        SessionTrendRange.QUARTER -> null
    }
    SectionCard(
        title = "No sessions in this range",
        subtitle = "Nothing was recorded for $label in the last ${report.range.days} days.",
        icon = Icons.Filled.History,
    ) {
        Text(
            text = if (report.previous.sessionCount > 0) {
                "The previous ${report.range.days} days hold " +
                    Formatters.count(report.previous.sessionCount, "session") +
                    ". A wider range includes them."
            } else {
                "The previous ${report.range.days} days are empty too. Play a tracked game and the " +
                    "figures will appear here."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (nextRange != null || label != "All games") {
            Spacer(modifier = Modifier.height(Spacing.sm))
            ActionRow {
                if (nextRange != null) {
                    Button(onClick = { onRange(nextRange) }) {
                        Text("Show ${nextRange.label}")
                    }
                }
                if (label != "All games") {
                    TextButton(onClick = { onPackage(null) }) { Text("All games") }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------------- conditions

/**
 * The competing explanation for every delta under it.
 *
 * It names the changes rather than saying "conditions changed", because a banner that will not say what
 * moved is impossible to check. The last sentence stops the section implying that the game caused a
 * difference that the device or the user's play pattern could explain.
 */
@Composable
private fun ConditionsBanner(report: SessionTrendReport) {
    NoteBanner(
        text = "Conditions changed since the previous ${report.range.days} days: " +
            report.conditionChanges.joinToString(", ") +
            ". The deltas below may reflect the device or how you played, not the game.",
        tone = Tone.Warning,
        icon = Icons.Filled.Info,
    )
}

// --------------------------------------------------------------------------------------- cards

@Composable
private fun TrendMetricGrid(report: SessionTrendReport) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SessionTrendMetric.entries.chunked(2).forEach { rowMetrics ->
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                rowMetrics.forEach { metric ->
                    TrendMetricTile(
                        summary = report.summary(metric),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowMetrics.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * One figure, its delta and the basis for both.
 *
 * The value is tinted by improvement only after the delta line has already said "better" or "worse"; the
 * colour is a second cue, never the first. Play time has no judgement word because more time is not
 * inherently good, so its tone stays neutral.
 */
@Composable
private fun TrendMetricTile(
    summary: SessionTrendSummary,
    modifier: Modifier = Modifier,
) {
    val tone = when {
        summary.isImprovement == true -> Tone.Good
        summary.isImprovement == false -> Tone.Warning
        else -> Tone.Neutral
    }
    PlainCard(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = summary.spoken
        },
        contentPadding = Spacing.md,
        fillHeight = true,
    ) {
        Text(
            text = summary.metric.label.uppercase(Locale.US),
            style = StatLabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = summary.valueText,
            style = StatValueCompactStyle,
            color = if (summary.metric == SessionTrendMetric.PLAY_TIME) {
                MaterialTheme.colorScheme.onSurface
            } else {
                tone.colour()
            },
            maxLines = 1,
        )
        if (summary.deltaText != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = summary.deltaText,
                style = MaterialTheme.typography.labelLarge,
                color = tone.colour(),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = summary.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The one honest answer to the 1% low and jank cards the specification asks for.
 *
 * GameCore's session table has an average frame rate and no distribution. A 1% low or a jank percentage
 * computed from a single average would be a number with no measurement behind it, so the section omits
 * both and says what would have to change to show them.
 */
@Composable
private fun FrameConsistencyNote() {
    NoteBanner(
        text = "1% lows and frame-time jank are not shown. A session stores a window-average frame rate, " +
            "not per-frame times, so neither figure can be calculated from this history without changing " +
            "what the recorder collects.",
        tone = Tone.Muted,
        icon = Icons.Filled.Info,
    )
}

/**
 * Best and worst by average frame rate.
 *
 * That is the only session-level metric with enough range to rank honestly; temperature and battery are
 * device readings whose extremes say more about the day than about the session. When only one session
 * measured a frame rate, it is labelled as the only one rather than as both best and worst.
 */
@Composable
private fun BestWorstCard(report: SessionTrendReport) {
    SectionCard(
        title = "Best and worst session",
        subtitle = "By average frame rate in this range, among sessions that recorded one.",
        icon = Icons.Filled.TrackChanges,
    ) {
        val best = report.best
        val worst = report.worst
        when {
            best == null || worst == null -> NoteBanner(
                text = "No session in this range recorded a frame rate, so there is nothing to rank.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
            best.id == worst.id -> ExtremeRow(label = "Only session", session = best, tone = Tone.Neutral)
            else -> {
                ExtremeRow(label = "Best", session = best, tone = Tone.Good)
                RowDivider()
                ExtremeRow(label = "Worst", session = worst, tone = Tone.Neutral)
            }
        }
    }
}

@Composable
private fun ExtremeRow(
    label: String,
    session: SessionTrendSession,
    tone: Tone,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip(text = label, tone = tone)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = String.format(Locale.US, "%.1f fps", session.averageFrameRate),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = session.gameLabel + " · " +
                    Formatters.relativeDay(session.startedAtMillis) + " · " +
                    Formatters.durationCoarse(session.durationMillis),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ----------------------------------------------------------------------------------- chart/table

@Composable
private fun DailyTrendCard(
    report: SessionTrendReport,
    metric: SessionTrendMetric,
    showTable: Boolean,
    onMetric: (SessionTrendMetric) -> Unit,
    onShowTable: (Boolean) -> Unit,
) {
    SectionCard(
        title = "Daily trend",
        subtitle = "One entry per local day, oldest on the left.",
        icon = Icons.Filled.Insights,
    ) {
        ChoiceRow(
            options = SessionTrendMetric.entries,
            selected = metric,
            onSelect = onMetric,
            label = { it.shortLabel },
            perRow = 4,
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ChoiceChip(
                text = "Chart",
                isSelected = !showTable,
                onClick = { onShowTable(false) },
                modifier = Modifier.weight(1f),
            )
            ChoiceChip(
                text = "Table",
                isSelected = showTable,
                onClick = { onShowTable(true) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))

        if (showTable) {
            TrendTable(report = report, metric = metric)
        } else {
            TrendChartArea(report = report, metric = metric)
        }
    }
}

@Composable
private fun TrendChartArea(
    report: SessionTrendReport,
    metric: SessionTrendMetric,
) {
    val points = report.series(metric)
    val known = points.mapNotNull { it.value }
    if (known.isEmpty()) {
        NoteBanner(
            text = report.absenceReason(metric),
            tone = Tone.Muted,
            icon = Icons.Filled.Info,
        )
        return
    }

    val rawMin = known.min()
    val rawMax = known.max()
    val axisMin = if (metric == SessionTrendMetric.TEMPERATURE) rawMin - 0.5 else 0.0
    val axisMax = when (metric) {
        SessionTrendMetric.TEMPERATURE -> (rawMax + 0.5).coerceAtLeast(axisMin + 1.0)
        SessionTrendMetric.PLAY_TIME -> (rawMax * 1.15).coerceAtLeast(1.0)
        SessionTrendMetric.FRAME_RATE -> (rawMax * 1.15).coerceAtLeast(1.0)
        SessionTrendMetric.BATTERY_DRAIN -> (rawMax * 1.15).coerceAtLeast(0.1)
    }
    val summary = report.chartSummary(metric)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = summary },
    ) {
        Column(
            modifier = Modifier
                .height(ChartHeight.dp)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            AxisLabel(SessionTrends.axisText(metric, axisMax))
            AxisLabel(SessionTrends.axisText(metric, axisMin))
        }
        TrendPlot(
            points = points,
            metric = metric,
            axisMin = axisMin,
            axisMax = axisMax,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AxisLabel(chartDate(points.first().date))
        AxisLabel(chartDate(points.last().date))
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = if (metric == SessionTrendMetric.PLAY_TIME) {
            "Bars show total play time on each local day. A day with no session is a zero-height bar, " +
                "not a missing reading."
        } else if (metric == SessionTrendMetric.TEMPERATURE) {
            "The vertical scale starts near the recorded readings rather than at zero, so the shape " +
                "shows the movement between them. Days without a reading are gaps, not zeroes."
        } else {
            "Days without a reading are gaps, not zeroes. The table has the same figures day by day."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private const val ChartHeight = 132

/**
 * The plot itself.
 *
 * Bars for play time because a day's total is a quantity, lines for the three measured rates because
 * their shape is what a trend is for. A null point breaks the line rather than falling to the floor: a
 * day the device did not measure is not a day at zero.
 */
@Composable
private fun TrendPlot(
    points: List<SessionTrendPoint>,
    metric: SessionTrendMetric,
    axisMin: Double,
    axisMax: Double,
    modifier: Modifier = Modifier,
) {
    val lineColour = MaterialTheme.colorScheme.primary
    val gridColour = MaterialTheme.colorScheme.outlineVariant
    val bars = metric == SessionTrendMetric.PLAY_TIME
    val span = (axisMax - axisMin).takeIf { it > 0.0 } ?: 1.0

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(ChartHeight.dp),
    ) {
        drawTrendGrid(gridColour)
        if (bars) {
            drawTrendBars(points, axisMin, span, lineColour)
        } else {
            drawTrendLine(points, axisMin, span, lineColour)
        }
    }
}

private fun DrawScope.drawTrendGrid(colour: Color) {
    val lines = 3
    repeat(lines) { index ->
        val y = size.height * index / (lines - 1).toFloat()
        drawLine(
            color = colour,
            start = Offset(0f, y.coerceIn(0.5f, size.height - 0.5f)),
            end = Offset(size.width, y.coerceIn(0.5f, size.height - 0.5f)),
            strokeWidth = 1f,
        )
    }
}

private fun DrawScope.drawTrendLine(
    points: List<SessionTrendPoint>,
    axisMin: Double,
    span: Double,
    colour: Color,
) {
    val stroke = 2.2f * density
    val top = stroke / 2f
    val usable = (size.height - stroke).coerceAtLeast(1f)
    val stepX = if (points.size > 1) size.width / (points.size - 1) else size.width

    fun yFor(value: Double): Float {
        val fraction = ((value - axisMin) / span).coerceIn(0.0, 1.0)
        return top + usable * (1f - fraction.toFloat())
    }

    val segment = Path()
    var open = false
    points.forEachIndexed { index, point ->
        val value = point.value
        if (value == null) {
            open = false
            return@forEachIndexed
        }
        val x = stepX * index
        val y = yFor(value)
        if (open) segment.lineTo(x, y) else segment.moveTo(x, y)
        drawCircle(color = colour, radius = stroke, center = Offset(x, y))
        open = true
    }
    drawPath(
        path = segment,
        color = colour,
        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun DrawScope.drawTrendBars(
    points: List<SessionTrendPoint>,
    axisMin: Double,
    span: Double,
    colour: Color,
) {
    if (points.isEmpty()) return
    val stepX = size.width / points.size
    val barWidth = (stepX * 0.58f).coerceAtLeast(1f)
    val usable = (size.height - 4f).coerceAtLeast(1f)
    points.forEachIndexed { index, point ->
        val value = point.value ?: return@forEachIndexed
        val fraction = ((value - axisMin) / span).coerceIn(0.0, 1.0).toFloat()
        val height = usable * fraction
        if (height <= 0f) return@forEachIndexed
        val x = stepX * index + (stepX - barWidth) / 2f
        drawRect(
            color = colour,
            topLeft = Offset(x, size.height - height),
            size = Size(barWidth, height),
        )
    }
}

@Composable
private fun TrendTable(
    report: SessionTrendReport,
    metric: SessionTrendMetric,
) {
    val points = report.series(metric)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TableHeader("Day", Modifier.weight(1.4f))
            TableHeader("Sessions", Modifier.weight(0.8f), TextAlign.Center)
            TableHeader(metric.shortLabel, Modifier.weight(1f), TextAlign.End)
        }
        RowDivider()
        points.forEach { point ->
            val reading = SessionTrends.pointText(metric, point) ?: "Not measured"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics {
                        contentDescription =
                            "${tableDate(point.date)}, ${Formatters.count(point.sessionCount, "session")}, " +
                                "${metric.label}: $reading"
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tableDate(point.date),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1.4f),
                )
                Text(
                    text = point.sessionCount.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(0.8f),
                )
                Text(
                    text = SessionTrends.pointText(metric, point) ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Days with no reading are shown as —, not zero. Play time is a real total, so an " +
                "empty day is 0m.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TableHeader(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = textAlign,
        modifier = modifier,
    )
}

@Composable
private fun AxisLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val chartFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
private val tableFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

private fun chartDate(date: LocalDate): String = date.format(chartFormatter)
private fun tableDate(date: LocalDate): String = date.format(tableFormatter)

/**
 * Static evidence for compact-width layout review when no device is attached.
 *
 * A preview is not runtime verification; it exists so Android Studio or a screenshot harness can render
 * every populated state — conditions banner, four metric cards, extremes and chart — without a database.
 */
@Preview(showBackground = true, widthDp = 400, heightDp = 1800)
@Composable
private fun SessionTrendsSectionPreview() {
    val range = SessionTrendRange.WEEK
    val now = Instant.parse("2026-11-20T12:00:00Z").toEpochMilli()
    val window = SessionTrends.window(range, now, ZoneId.of("UTC"))
    val report = SessionTrends.build(
        range = range,
        window = window,
        currentRows = listOf(
            previewAggregate(window.lastDay.minusDays(4), sessions = 2, fps = 54.0, temp = 405.0),
            previewAggregate(window.lastDay.minusDays(2), sessions = 2, fps = 58.0, temp = 425.0),
            previewAggregate(window.lastDay, sessions = 2, fps = 61.0, temp = 438.0),
        ),
        previousRows = listOf(
            previewAggregate(window.firstDay.minusDays(3), sessions = 2, fps = 53.0, temp = 390.0),
            previewAggregate(window.firstDay.minusDays(1), sessions = 2, fps = 55.0, temp = 395.0),
        ),
        best = SessionTrendSession(
            id = 4,
            packageName = "com.example.game",
            gameLabel = "Example Game",
            startedAtMillis = now - 2 * 24 * 60 * 60 * 1_000L,
            durationMillis = 52 * 60 * 1_000L,
            averageFrameRate = 63.4f,
        ),
        worst = SessionTrendSession(
            id = 1,
            packageName = "com.example.game",
            gameLabel = "Example Game",
            startedAtMillis = now - 6 * 24 * 60 * 60 * 1_000L,
            durationMillis = 28 * 60 * 1_000L,
            averageFrameRate = 49.8f,
        ),
    )
    GameCoreTheme {
        SessionTrendsSection(
            state = SessionsUiState(
                trends = report,
                trendsLoading = false,
                trendRange = range,
                trendPackage = "com.example.game",
                games = listOf(
                    SessionFilter(null, "All games", 12),
                    SessionFilter("com.example.game", "Example Game", 8),
                    SessionFilter("com.example.other", "Other Game", 4),
                ),
            ),
            onRange = {},
            onPackage = {},
            onMetric = {},
            onShowTable = {},
            onRetry = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

private fun previewAggregate(
    day: LocalDate,
    sessions: Int,
    fps: Double,
    temp: Double,
): SessionTrendAggregate {
    val playMillis = sessions * 40 * 60 * 1_000L
    return SessionTrendAggregate(
        day = day,
        packageName = "com.example.game",
        sessionCount = sessions,
        playTimeMillis = playMillis,
        frameRateWeightedSum = fps * playMillis,
        frameRateWeightMillis = playMillis,
        frameRateSessionCount = sessions,
        temperatureWeightedSum = temp * playMillis,
        temperatureWeightMillis = playMillis,
        temperatureSessionCount = sessions,
        drainRateSum = 6.2 * sessions,
        drainSessionCount = sessions,
        chargingSessionCount = if (day.dayOfMonth % 2 == 0) 1 else 0,
    )
}
