package com.gamecore.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.data.repository.SampleExporter
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.GraphCard
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.ReadoutRow
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatEntry
import com.gamecore.ui.components.StatStrip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.startIntentSafely

/**
 * §21's report on one session: the figures, the graphs, and what they rest on.
 *
 * The order is deliberate. The three headline figures are the ones every device can measure, the sample
 * count sits directly under them, and the graphs come after — so a reader reaches the lines already knowing
 * how many points are behind them. A session with four samples shows the same layout with the summary
 * replaced by the reason there isn't one, rather than a different screen.
 */
@Composable
fun SessionReportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionReportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Deleting the session is done from here, and there is nothing left to show afterwards.
    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) onBack()
    }

    // Share is one tap: the card is drawn and then handed straight to the sheet. A PNG left in the app's
    // own storage that the user never asked to keep is not a feature, so nothing here shows it first.
    LaunchedEffect(state.cardReady) {
        if (!state.cardReady) return@LaunchedEffect
        val intent = viewModel.cardIntent()
        viewModel.cardShared(shared = intent != null && context.startIntentSafely(intent))
    }

    // The sample export follows the same one-tap-to-share path as the card: written, then handed straight
    // to the sheet, and the file left in the app's own pruned `exports` directory rather than shown here.
    LaunchedEffect(state.sampleExportReady) {
        if (!state.sampleExportReady) return@LaunchedEffect
        val intent = viewModel.sampleExportIntent()
        viewModel.sampleExportShared(shared = intent != null && context.startIntentSafely(intent))
    }

    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = state.title.ifEmpty { "Session" },
                subtitle = state.subtitle.ifEmpty { null },
                onBack = onBack,
                action = if (!state.isMissing && !state.isLoading) {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SampleExportAction(
                                busy = state.isExportingSamples,
                                onExport = viewModel::exportSamples,
                            )
                            IconButton(
                                onClick = viewModel::shareCard,
                                enabled = !state.isRenderingCard,
                            ) {
                                if (state.isRenderingCard) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.Share,
                                        contentDescription = "Share a card for this session",
                                    )
                                }
                            }
                            TextButton(onClick = viewModel::askDelete) { Text("Delete") }
                        }
                    }
                } else {
                    null
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

        if (state.isMissing) {
            item {
                EmptyState(
                    icon = Icons.Filled.History,
                    title = "Session not found",
                    message = "It has been deleted, or the history was cleared while this screen was open.",
                    actionLabel = "Back to sessions",
                    onAction = onBack,
                )
            }
        }

        if (!state.isMissing && !state.isLoading) {
            item { HeadlineCard(state = state, modifier = padded) }

            state.interruption?.let { note ->
                item {
                    NoteBanner(
                        text = note,
                        tone = Tone.Warning,
                        icon = Icons.Filled.Shield,
                        modifier = padded,
                    )
                }
            }

            item { FiguresCard(state = state, modifier = padded) }

            items(state.graphs.size) { index ->
                val graph = state.graphs[index]
                GraphCard(
                    title = graph.title,
                    series = graph.points,
                    modifier = padded,
                    valueRange = graph.range,
                    seriesLabel = graph.seriesLabel,
                    secondary = graph.secondary,
                    secondaryLabel = graph.secondaryLabel,
                    format = { "${it.toInt()}${graph.unit}" },
                    emptyMessage = graph.emptyMessage,
                    note = graph.note,
                )
            }

            item { ProvenanceCard(state = state, modifier = padded) }
        }
    }

    if (state.pendingDelete) {
        ConfirmDialog(
            title = "Delete this session?",
            message = "${state.title} · ${state.subtitle}. The session and every sample behind these " +
                "graphs are removed from this device.",
            confirmLabel = "Delete",
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

/**
 * The three figures every device can measure, and the sentence that qualifies them.
 *
 * The confidence line sits inside this card rather than at the bottom of the screen on purpose: it applies
 * to the numbers directly above it, and a reader who scrolls past the graphs and never reaches a footnote
 * has been told nothing.
 */
@Composable
private fun HeadlineCard(state: SessionReportUiState, modifier: Modifier = Modifier) {
    PlainCard(modifier = modifier) {
        StatStrip(
            entries = state.headline.map { StatEntry(label = it.label, value = it.value, tone = it.tone) },
        )
        RowDivider()
        Text(
            text = state.confidence,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Every figure, in a fixed order, each absence carrying its own reason. */
@Composable
private fun FiguresCard(state: SessionReportUiState, modifier: Modifier = Modifier) {
    SectionCard(
        title = "What was measured",
        subtitle = "A dash is a reading this device could not take, not a zero",
        icon = Icons.Filled.Insights,
        modifier = modifier,
    ) {
        state.figures.forEach { ReadoutRow(it) }
    }
}

/**
 * What was done to the device during the session, and where the record lives.
 *
 * The profile line is the part a user comes back for: a session that ran with a profile applied and one
 * that did not are not comparable, and six weeks later nothing else on the screen says which this was.
 */
@Composable
private fun ProvenanceCard(state: SessionReportUiState, modifier: Modifier = Modifier) {
    SectionCard(title = "About this record", icon = Icons.Filled.Info, modifier = modifier) {
        state.profileNote?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RowDivider()
        }
        Text(
            text = REPORT_SCOPE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val REPORT_SCOPE =
    "Everything on this page was computed from samples taken while the game was in the foreground, and it " +
        "is stored in GameCore's encrypted database on this device. Nothing was measured inside the game, " +
        "and nothing here has been uploaded anywhere."

/**
 * The header's "export the raw samples" affordance: one icon, a menu of the two formats.
 *
 * A menu rather than two buttons because CSV is the one most people want and JSON is the one a few do, and
 * a header with three icons already carries share and delete. The spinner replaces the icon while a file is
 * being written so the same tap cannot start a second export over the first.
 */
@Composable
private fun SampleExportAction(
    busy: Boolean,
    onExport: (SampleExporter.Format) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = !busy) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = "Export this session's samples",
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Export samples (CSV)") },
                onClick = {
                    expanded = false
                    onExport(SampleExporter.Format.CSV)
                },
            )
            DropdownMenuItem(
                text = { Text("Export samples (JSON)") },
                onClick = {
                    expanded = false
                    onExport(SampleExporter.Format.JSON)
                },
            )
        }
    }
}

