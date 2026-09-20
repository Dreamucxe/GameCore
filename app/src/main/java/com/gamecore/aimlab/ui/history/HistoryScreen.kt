package com.gamecore.aimlab.ui.history

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.Stats
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.core.common.Formatters
import com.gamecore.data.repository.DiagnosticsFormat
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.ClickableCard
import com.gamecore.ui.components.ConfirmDialog
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
import com.gamecore.ui.components.colour
import com.gamecore.ui.components.startIntentSafely

/**
 * Every session the user has run, newest first.
 *
 * A row is a summary, not a report: what it was, how hard, when, how long, and the two figures that mean
 * anything at a glance. Tapping it opens the full result. The row's score is shown only for the modes that
 * produce one — free practice is unscored and the engine stores a 0 for it, so a "Score 0" on that row would
 * be an absence dressed as a result — and its accuracy only when shots were actually fired, which tracking
 * and gyro runs (hold-to-track, no discrete shot) never do.
 *
 * The filters are built from the history rather than from the enums, so every chip offered leads somewhere.
 * Both destructive actions go through the shared confirmation, because nothing in GameCore keeps a second
 * copy of a session: there is no undo behind either of them.
 *
 * @param onOpenSession open the results screen for one stored session id.
 * @param onBack leave the screen.
 */
@Composable
fun HistoryScreen(
    onOpenSession: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
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
                title = "Session history",
                subtitle = if (state.sessions.isEmpty()) {
                    "Every finished session is kept here."
                } else {
                    Formatters.count(state.sessions.size, "session") + " recorded, newest first."
                },
                onBack = onBack,
            )
        }

        if (state.message != null) {
            item {
                NoteBanner(
                    text = state.message.orEmpty(),
                    tone = Tone.Muted,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                    action = { TextButton(onClick = viewModel::dismissMessage) { Text("Dismiss") } },
                )
            }
        }

        if (state.isEmpty) {
            item {
                EmptyState(
                    icon = Icons.Filled.History,
                    title = "No sessions yet",
                    message = "Finish a run in any training mode and it is stored here, with the figures " +
                        "that mode measured.",
                )
            }
            return@LazyColumn
        }

        if (state.hasAnyFilter) {
            item {
                FilterCard(
                    state = state,
                    onMode = viewModel::onModeSelected,
                    onDifficulty = viewModel::onDifficultySelected,
                    modifier = padded,
                )
            }
        }

        if (state.filteredToNothing) {
            item {
                NoteBanner(
                    text = "No sessions match that filter.",
                    tone = Tone.Muted,
                    icon = Icons.Filled.FilterList,
                    modifier = padded,
                    action = { TextButton(onClick = viewModel::clearFilters) { Text("Show all") } },
                )
            }
        }

        items(state.visible, key = { it.id }) { session ->
            SessionRow(
                session = session,
                onOpen = { onOpenSession(session.id) },
                onDelete = { viewModel.askDelete(session) },
                modifier = padded,
            )
        }

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

        item {
            Box(modifier = padded) {
                ActionRow {
                    OutlinedButton(
                        onClick = viewModel::askClearAll,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Tone.Danger.colour()),
                    ) {
                        Icon(imageVector = Icons.Filled.DeleteSweep, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Clear history")
                    }
                }
            }
        }
    }

    val pending = state.pendingDelete
    if (pending != null) {
        ConfirmDialog(
            title = "Delete this session?",
            message = "${pending.mode.label} on ${pending.difficulty.label}, " +
                "${Formatters.dateTime(pending.startedAtMillis)}. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissConfirmation,
            isDestructive = true,
        )
    } else if (state.confirmClear) {
        ConfirmDialog(
            title = "Clear all history?",
            message = "All ${state.sessions.size} sessions are deleted, along with the personal records " +
                "they set. Nothing is kept anywhere else, so this cannot be undone.",
            confirmLabel = "Clear everything",
            onConfirm = viewModel::confirmClearAll,
            onDismiss = viewModel::dismissConfirmation,
            isDestructive = true,
        )
    }
}

// ------------------------------------------------------------------------------------------ rows

/**
 * One stored session.
 *
 * The card is the tap target and opens the full result; the bin is a separate one inside it, because
 * deleting is not something a mis-aimed tap on a list should be able to start — and it only *starts* it,
 * since the confirmation stands between the icon and the row going away.
 */
@Composable
private fun SessionRow(
    session: SessionSummary,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ClickableCard(onClick = onOpen, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = session.mode.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = Formatters.relativeDay(session.startedAtMillis) + " · " +
                        Formatters.clockTime(session.startedAtMillis),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusChip(text = session.difficulty.label, tone = Tone.Muted)
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete this session",
                    tint = Tone.Danger.colour(),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        StatStrip(entries = rowEntries(session))
    }
}

/**
 * The figures on a row: how long it lasted, what it scored if it scores at all, and how accurate it was.
 *
 * Score is omitted entirely for an unscored mode rather than shown as the 0 the engine stores, and accuracy
 * is the absence marker when no shot was fired — tracking and gyro runs have no shots to be accurate with.
 */
private fun rowEntries(session: SessionSummary): List<StatEntry> = buildList {
    add(StatEntry(label = "Duration", value = Formatters.duration(session.durationMillis)))
    if (session.mode.scored) {
        add(StatEntry(label = "Score", value = session.score.toString(), tone = Tone.Accent))
    }
    add(accuracyEntry(Stats.accuracy(session.hits, session.shots), session.shots))
}

/** Accuracy as a stat cell. Before a shot is fired there is no accuracy — that is an absence, not 0 %. */
private fun accuracyEntry(fraction: Float, shots: Int): StatEntry = StatEntry(
    label = "Accuracy",
    value = if (shots <= 0) ABSENT else Formatters.percent(fraction, 0),
    tone = when {
        shots <= 0 -> Tone.Muted
        fraction >= 0.75f -> Tone.Good
        fraction >= 0.45f -> Tone.Neutral
        else -> Tone.Warning
    },
)

// --------------------------------------------------------------------------------------- filters

@Composable
private fun FilterCard(
    state: HistoryState,
    onMode: (TrainingMode?) -> Unit,
    onDifficulty: (Difficulty?) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Filter",
        subtitle = "Only the modes and difficulties you have actually run are offered.",
        icon = Icons.Filled.FilterList,
        modifier = modifier,
        action = {
            if (state.isFiltered) {
                StatusChip(text = Formatters.count(state.visible.size, "match", "matches"), tone = Tone.Accent)
            }
        },
    ) {
        if (state.hasModeFilter) {
            FilterLabel("Mode")
            ChoiceRow(
                options = state.modeOptions,
                selected = state.mode,
                onSelect = onMode,
                label = { it?.label ?: "All modes" },
                perRow = 2,
            )
        }
        if (state.hasDifficultyFilter) {
            if (state.hasModeFilter) RowDivider()
            FilterLabel("Difficulty")
            ChoiceRow(
                options = state.difficultyOptions,
                selected = state.difficulty,
                onSelect = onDifficulty,
                label = { it?.label ?: "All" },
                perRow = 3,
            )
        }
    }
}

@Composable
private fun FilterLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

// ---------------------------------------------------------------------------------------- export

/**
 * The two files this screen can write, each labelled with what is actually in it.
 *
 * They are not the same data in two encodings and the card does not pretend otherwise: CSV is the session
 * table as filtered on screen, JSON is the configuration bundle the app can read back in. The file is
 * written inside GameCore's own storage; sharing it is a separate, deliberate second action.
 */
@Composable
private fun ExportCard(
    state: HistoryState,
    onExport: (DiagnosticsFormat) -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Export",
        subtitle = "CSV writes the sessions listed above. JSON writes your weapons, sensitivity profiles " +
            "and control layouts — the form this app can import again.",
        icon = Icons.Filled.Share,
        modifier = modifier,
    ) {
        ActionRow {
            OutlinedButton(
                onClick = { onExport(DiagnosticsFormat.CSV) },
                enabled = state.visible.isNotEmpty() && !state.exporting,
            ) { Text("CSV") }
            OutlinedButton(
                onClick = { onExport(DiagnosticsFormat.JSON) },
                enabled = !state.exporting,
            ) { Text("JSON") }
        }
        val written = state.lastExport
        if (written != null) {
            RowDivider()
            KeyValueRow(
                label = written.fileName,
                value = Formatters.bytes(written.sizeBytes),
                tone = Tone.Good,
            )
            KeyValueRow(label = "Entries written", value = written.recordCount.toString())
            if (written.uri != null) {
                Spacer(modifier = Modifier.height(6.dp))
                ActionRow { OutlinedButton(onClick = onShare) { Text("Share") } }
            }
        }
    }
}
