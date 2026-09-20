package com.gamecore.ui.touch

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.common.Formatters
import com.gamecore.core.input.TouchAction
import com.gamecore.core.input.TouchPoint
import com.gamecore.core.input.TouchSnapshot
import com.gamecore.core.input.TouchStroke
import com.gamecore.core.input.TouchViewMode
import com.gamecore.core.input.intensityAt
import com.gamecore.core.input.pathPercent
import com.gamecore.data.repository.DiagnosticsExport
import com.gamecore.data.repository.DiagnosticsFormat
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
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
import com.gamecore.ui.components.screenAspectRatio
import com.gamecore.ui.components.startIntentSafely
import java.util.Locale

/**
 * Touch heatmap: where fingers land on GameCore's own capture pad.
 *
 * The pad is the honest part of this screen. Android hands an app the touch events dispatched to its own
 * windows and nothing else — not what another app received, not what is on screen, not a keystroke — so a
 * "touch heatmap" that claimed to cover a whole gaming session would be inventing most of it. What this
 * builds instead is a real recording of a real surface, at the size and shape of this device's screen,
 * which is a thing a player can actually use to check thumb reach and swipe consistency.
 */
@Composable
fun TouchScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TouchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Touch heatmap",
                subtitle = "Where your fingers land, recorded on this device only",
                onBack = onBack,
                action = {
                    if (state.isCapturing) StatusChip("Capturing", Tone.Danger, Icons.Filled.TouchApp)
                },
            )
        }

        state.message?.let { message ->
            item {
                NoteBanner(
                    text = message,
                    tone = Tone.Accent,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                    action = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
                )
            }
        }

        item {
            CaptureCard(
                state = state,
                onTouch = viewModel::onTouch,
                onCapturing = viewModel::setCapturing,
                onMode = viewModel::setMode,
                onClear = viewModel::clear,
                modifier = padded,
            )
        }

        if (state.mode == TouchViewMode.RAW) {
            item { RawEventsCard(state = state, modifier = padded) }
        }

        item { StatisticsCard(state = state, modifier = padded) }

        item {
            ExportCard(
                state = state,
                onExport = viewModel::export,
                onShare = {
                    val intent = viewModel.shareIntent()
                    if (intent == null || !context.startIntentSafely(Intent.createChooser(intent, "Share"))) {
                        viewModel.onIntentFailed()
                    }
                },
                modifier = padded,
            )
        }

        item { PrivacyCard(modifier = padded) }
    }
}

/**
 * The pad, its four ways of being drawn, and the switch that decides whether it is listening.
 *
 * The pad only takes the gesture while capture is on. That is a usability decision and a truthful one at
 * the same time: with capture off the pad declines the touch outright, the list scrolls under the finger
 * as any other list would, and nothing is recorded because nothing was delivered.
 */
@Composable
private fun CaptureCard(
    state: TouchUiState,
    onTouch: (Long, TouchAction, Float, Float, Float?, Int, Long) -> Unit,
    onCapturing: (Boolean) -> Unit,
    onMode: (TouchViewMode) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Capture pad",
        modifier = modifier,
        subtitle = if (state.isCapturing) CAPTURE_ON else CAPTURE_OFF,
        icon = Icons.Filled.TouchApp,
        action = {
            if (state.activePointers > 0) {
                StatusChip("${state.activePointers} down", Tone.Accent)
            }
        },
    ) {
        ChoiceRow(
            options = TouchViewMode.entries.toList(),
            selected = state.mode,
            onSelect = onMode,
            label = { it.label },
            perRow = 4,
        )
        Spacer(modifier = Modifier.height(10.dp))

        TouchPad(
            snapshot = state.snapshot,
            mode = state.mode,
            isCapturing = state.isCapturing,
            onTouch = onTouch,
        )

        Spacer(modifier = Modifier.height(10.dp))
        ActionRow {
            if (state.isCapturing) {
                Button(onClick = { onCapturing(false) }) { Text("Stop capture") }
            } else {
                Button(onClick = { onCapturing(true) }) { Text("Start capture") }
            }
            if (state.hasData) {
                OutlinedButton(onClick = onClear) { Text("Clear") }
            }
        }

        if (state.mode == TouchViewMode.HEATMAP && state.hasData) {
            Spacer(modifier = Modifier.height(10.dp))
            HeatLegend(peak = state.snapshot.heatPeak)
        }
    }
}

/**
 * The surface itself, at this device's aspect ratio so a thumb-reach reading means something.
 *
 * Every event that reaches [onTouch] arrived because Android dispatched it to this composable. The pointer
 * block consumes what it receives so the surrounding list does not steal a drag halfway through a swipe and
 * leave the recording with a stroke that stops in the middle for no reason the data can explain.
 */
@Composable
private fun TouchPad(
    snapshot: TouchSnapshot,
    mode: TouchViewMode,
    isCapturing: Boolean,
    onTouch: (Long, TouchAction, Float, Float, Float?, Int, Long) -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val ratio = screenAspectRatio()

    val listening = if (!isCapturing) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) {
            val width = size.width.toFloat().coerceAtLeast(1f)
            val height = size.height.toFloat().coerceAtLeast(1f)
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val active = event.changes.count { it.pressed }
                    for (change in event.changes) {
                        val action = when {
                            change.changedToDownIgnoreConsumed() -> TouchAction.DOWN
                            change.changedToUpIgnoreConsumed() -> TouchAction.UP
                            change.positionChangedIgnoreConsumed() -> TouchAction.MOVE
                            else -> null
                        }
                        if (action != null) {
                            onTouch(
                                change.id.value,
                                action,
                                (change.position.x / width).coerceIn(0f, 1f),
                                (change.position.y / height).coerceIn(0f, 1f),
                                change.pressure.takeIf { it > 0f },
                                active,
                                change.uptimeMillis,
                            )
                        }
                        change.consume()
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(14.dp))
            .then(listening),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = surface)

            when (mode) {
                TouchViewMode.HEATMAP -> drawHeat(snapshot, accent)
                TouchViewMode.POINTS -> drawPoints(snapshot, accent)
                TouchViewMode.TRAILS -> drawTrails(snapshot, accent)
                TouchViewMode.RAW -> drawPoints(snapshot, accent)
            }

            // The frame last, so a cell in the corner does not paint over it.
            drawRect(color = outline, size = size, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
        }

        if (snapshot.isEmpty) {
            Text(
                text = if (isCapturing) PAD_WAITING else PAD_IDLE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
            )
        }
    }
}

/** Heat as opacity over the grid. One colour, so the intensity is the only thing being read. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHeat(
    snapshot: TouchSnapshot,
    colour: Color,
) {
    if (snapshot.heatPeak <= 0) return
    val cellWidth = size.width / snapshot.heatColumns
    val cellHeight = size.height / snapshot.heatRows
    for (row in 0 until snapshot.heatRows) {
        for (column in 0 until snapshot.heatColumns) {
            val intensity = snapshot.intensityAt(column, row)
            if (intensity <= 0f) continue
            drawRect(
                color = colour.copy(alpha = HEAT_FLOOR + (1f - HEAT_FLOOR) * intensity * HEAT_CEILING),
                topLeft = Offset(column * cellWidth, row * cellHeight),
                size = Size(cellWidth, cellHeight),
            )
        }
    }
}

/** Every recorded point as a dot, with the ones that started a gesture drawn solid. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPoints(
    snapshot: TouchSnapshot,
    colour: Color,
) {
    for (point in snapshot.points) {
        val solid = point.action == TouchAction.DOWN
        drawCircle(
            color = colour.copy(alpha = if (solid) 0.9f else 0.25f),
            radius = if (solid) 7f else 3f,
            center = Offset(point.x * size.width, point.y * size.height),
        )
    }
}

/** Each stroke as the path it actually took, with its start marked. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrails(
    snapshot: TouchSnapshot,
    colour: Color,
) {
    for (stroke in snapshot.strokes) {
        val points = stroke.points
        if (points.size < 2) continue
        for (index in 1 until points.size) {
            val from = points[index - 1]
            val to = points[index]
            drawLine(
                color = colour.copy(alpha = if (stroke.isSwipe) 0.75f else 0.35f),
                start = Offset(from.x * size.width, from.y * size.height),
                end = Offset(to.x * size.width, to.y * size.height),
                strokeWidth = 4f,
                cap = StrokeCap.Round,
            )
        }
        val first = points.first()
        drawCircle(
            color = colour,
            radius = 6f,
            center = Offset(first.x * size.width, first.y * size.height),
        )
    }
}

/** What the shading means, in the same colour it is drawn in. */
@Composable
private fun HeatLegend(peak: Int) {
    val accent = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "1",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp)),
        ) {
            val steps = 24
            val stepWidth = size.width / steps
            for (step in 0 until steps) {
                val intensity = step / (steps - 1f)
                drawRect(
                    color = accent.copy(alpha = HEAT_FLOOR + (1f - HEAT_FLOOR) * intensity * HEAT_CEILING),
                    topLeft = Offset(step * stepWidth, 0f),
                    size = Size(stepWidth + 1f, size.height),
                )
            }
        }
        Text(
            text = "$peak",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "Touches recorded in the busiest cell of the grid.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The last events as they were recorded, for reading a gesture back one row at a time. */
@Composable
private fun RawEventsCard(state: TouchUiState, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Raw events",
        modifier = modifier,
        subtitle = "Newest first, as delivered to the pad",
        icon = Icons.Filled.GridView,
    ) {
        if (state.recentEvents.isEmpty()) {
            Text(
                text = "Nothing delivered yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        state.recentEvents.forEach { event -> RawEventRow(event) }
    }
}

@Composable
private fun RawEventRow(event: TouchPoint) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = event.action.label,
            style = MaterialTheme.typography.labelMedium,
            color = when (event.action) {
                TouchAction.DOWN -> MaterialTheme.colorScheme.primary
                TouchAction.CANCEL -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            text = "#${event.pointerId} · ${percent(event.x)}, ${percent(event.y)}" +
                (event.pressure?.let { " · p ${decimal(it)}" } ?: "") +
                (if (event.activePointers > 1) " · ${event.activePointers} down" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Everything the log counted, over everything captured since the last clear. */
@Composable
private fun StatisticsCard(state: TouchUiState, modifier: Modifier = Modifier) {
    val summary = state.summary
    SectionCard(
        title = "Statistics",
        modifier = modifier,
        subtitle = "Since the pad was last cleared",
        icon = Icons.Filled.Insights,
    ) {
        if (summary.isEmpty) {
            Text(
                text = "Nothing captured yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        StatStrip(
            entries = listOf(
                StatEntry("Touches", summary.totalTouches.toString(), Tone.Accent),
                StatEntry("Swipes", summary.totalSwipes.toString()),
                StatEntry("Events", summary.totalEvents.toString()),
            ),
        )
        Spacer(modifier = Modifier.height(10.dp))
        StatStrip(
            entries = listOf(
                StatEntry("Avg touch", Formatters.millis(summary.averageTouchDurationMillis.toInt())),
                StatEntry("Longest swipe", "${decimal(summary.longestSwipe * 100f)}%"),
                StatEntry("Multi-touch", summary.multiTouchStrokes.toString()),
            ),
        )
        Spacer(modifier = Modifier.height(10.dp))
        KeyValueRow(
            label = "Most-used area",
            value = summary.busiestCellLabel?.replaceFirstChar { it.uppercase() } ?: ABSENT,
            tone = Tone.Accent,
        )
        KeyValueRow(
            label = "Share of touches there",
            value = summary.busiestCellShare?.let { Formatters.percent(it) } ?: ABSENT,
        )
        KeyValueRow(
            label = "Touch density",
            value = summary.touchDensityPerSecond?.let { "${decimal(it)} /s" } ?: ABSENT,
        )
        KeyValueRow(
            label = "Most fingers at once",
            value = summary.maximumSimultaneousPointers.toString(),
        )
        KeyValueRow(
            label = "Longest swipe took",
            value = Formatters.millis(summary.longestSwipeDurationMillis.toInt()),
        )
        KeyValueRow(label = "Capture length", value = Formatters.duration(summary.captureMillis))
        if (summary.droppedEvents > 0) {
            KeyValueRow(
                label = "Events dropped",
                value = summary.droppedEvents.toString(),
                tone = Tone.Warning,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = DROPPED_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val longest = state.snapshot.strokes.maxByOrNull { it.pathLength }
        if (longest != null && longest.isSwipe) {
            Spacer(modifier = Modifier.height(10.dp))
            RowDivider()
            Spacer(modifier = Modifier.height(10.dp))
            LongestSwipe(longest)
        }
    }
}

@Composable
private fun LongestSwipe(stroke: TouchStroke) {
    Text(
        text = "LONGEST SWIPE",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(6.dp))
    StatStrip(
        entries = listOf(
            StatEntry("Path", "${decimal(stroke.pathPercent())}%"),
            StatEntry("Straight", "${decimal(stroke.displacement * 100f)}%"),
            StatEntry("Time", Formatters.millis(stroke.durationMillis.toInt())),
        ),
    )
}

/** The file, and the only route off the device: the user's own share sheet. */
@Composable
private fun ExportCard(
    state: TouchUiState,
    onExport: (DiagnosticsFormat) -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Export",
        modifier = modifier,
        subtitle = "Written inside GameCore's own storage",
        icon = Icons.Filled.Insights,
    ) {
        ActionRow {
            OutlinedButton(
                onClick = { onExport(DiagnosticsFormat.JSON) },
                enabled = state.hasData && !state.isExporting,
            ) { Text("JSON") }
            OutlinedButton(
                onClick = { onExport(DiagnosticsFormat.CSV) },
                enabled = state.hasData && !state.isExporting,
            ) { Text("CSV") }
            OutlinedButton(
                onClick = { onExport(DiagnosticsFormat.TEXT) },
                enabled = state.hasData && !state.isExporting,
            ) { Text("Text") }
        }
        state.lastExport?.let { written -> ExportResult(written = written, onShare = onShare) }
    }
}

@Composable
private fun ExportResult(written: DiagnosticsExport.Written, onShare: () -> Unit) {
    Spacer(modifier = Modifier.height(10.dp))
    KeyValueRow(label = written.fileName, value = Formatters.bytes(written.sizeBytes), tone = Tone.Good)
    KeyValueRow(label = "Rows written", value = written.recordCount.toString(), tone = Tone.Muted)
    if (written.uri != null) {
        Spacer(modifier = Modifier.height(6.dp))
        ActionRow { OutlinedButton(onClick = onShare) { Text("Share") } }
    }
}

/** What this screen does not have access to, said plainly rather than left to be assumed. */
@Composable
private fun PrivacyCard(modifier: Modifier = Modifier) {
    SectionCard(
        title = "What this can and cannot see",
        modifier = modifier,
        icon = Icons.Filled.PrivacyTip,
    ) {
        Text(
            text = SCOPE_EXPLANATION,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(10.dp))
        NOT_CAPTURED.forEach { line ->
            Text(
                text = "•  $line",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        NoteBanner(text = LOCAL_ONLY, tone = Tone.Muted, icon = Icons.Filled.PrivacyTip)
    }
}

// ------------------------------------------------------------------------------------ formatting

private fun percent(fraction: Float): String = String.format(Locale.US, "%.0f%%", fraction * 100f)

private fun decimal(value: Float): String = String.format(Locale.US, "%.1f", value)

/** Even one touch in a cell should be visible, hence a floor rather than a pure multiply. */
private const val HEAT_FLOOR = 0.14f

/** The busiest cell stops short of opaque so the grid underneath stays readable. */
private const val HEAT_CEILING = 0.85f

private const val CAPTURE_ON = "Recording touches on the pad below"

private const val CAPTURE_OFF = "Capture is off — the pad passes touches through"

private const val PAD_WAITING = "Draw here"

private const val PAD_IDLE = "Start capture, then draw here"

private const val DROPPED_NOTE =
    "The log holds a fixed number of points and strokes. Older ones were discarded to keep recording; " +
        "the counts above still include them, the drawing does not."

private const val SCOPE_EXPLANATION =
    "This records the touches Android delivers to the pad on this screen. That is the whole of what an " +
        "ordinary app is given: touch events aimed at its own windows. A touch anywhere else on this " +
        "device belongs to whatever app received it, and GameCore is not told about it."

private val NOT_CAPTURED = listOf(
    "Screenshots or screen contents",
    "Keyboard text, passwords or anything typed",
    "Touches in other applications",
    "Touches on the home screen or in system UI",
    "Anything at all while this screen is closed",
)

private const val LOCAL_ONLY =
    "Everything recorded here stays on this device. There is no account, no server and no upload — the " +
        "only way any of it moves is the export above, shared by you."
