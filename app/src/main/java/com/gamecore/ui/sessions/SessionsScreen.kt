package com.gamecore.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.model.SessionSort
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceChip
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.ClickableCard
import com.gamecore.ui.components.CompactStat
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.ReadoutRow
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour

/**
 * §23's history list: what was recorded, with the totals above it.
 *
 * The live session, when there is one, sits at the top and is visibly a different kind of thing from the
 * rows below it — it has no report to open and no figures that have settled. Everything else on the screen
 * is a record, and the only destructive things here ([SessionsViewModel.askDelete] and
 * [SessionsViewModel.askClear]) go through a dialog.
 */
@Composable
fun SessionsScreen(
    onOpenReport: (Long) -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // statistics() is a set of aggregate queries rather than a flow, so resuming re-asks.
    OnResume { viewModel.onResume() }

    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Sessions",
                subtitle = subtitleFor(state),
                action = if (state.rows.isNotEmpty()) {
                    { TextButton(onClick = viewModel::askClear) { Text("Clear") } }
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

        if (!state.trackingEnabled) {
            item {
                NoteBanner(
                    text = TRACKING_OFF,
                    tone = Tone.Warning,
                    icon = Icons.Filled.Visibility,
                    modifier = padded,
                    action = {
                        TextButton(onClick = { onNavigate(Destination.Settings) }) { Text("Settings") }
                    },
                )
            }
        }

        state.live?.let { live ->
            item { LiveCard(live = live, onStop = viewModel::stopLiveSession, modifier = padded) }
        }

        if (state.statistics.isNotEmpty()) {
            item { StatisticsCard(state = state, modifier = padded) }
        }

        if (state.isEmpty && !state.isLoading) {
            item {
                EmptyState(
                    icon = Icons.Filled.History,
                    title = "Nothing recorded yet",
                    message = EMPTY_MESSAGE,
                    actionLabel = "Set up a game",
                    onAction = { onNavigate(Destination.Games) },
                )
            }
        }

        if (!state.isEmpty) {
            item { FilterRow(state = state, onFilter = viewModel::setFilter) }
            item { SortCard(state = state, onSort = viewModel::setSort, modifier = padded) }
        }

        items(state.rows, key = { it.id }) { row ->
            SessionCard(
                row = row,
                onOpen = { onOpenReport(row.id) },
                onDelete = { viewModel.askDelete(row) },
                modifier = padded,
            )
        }

        if (!state.isEmpty && state.rows.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Insights,
                    title = "No sessions for ${state.filterLabel}",
                    message = "The filter above is hiding everything else that was recorded.",
                    actionLabel = "Show all games",
                    onAction = { viewModel.setFilter(null) },
                )
            }
        }

        item { RetentionCard(modifier = padded) }
    }

    state.pendingDelete?.let { row ->
        ConfirmDialog(
            title = "Delete this session?",
            message = "${row.label} · ${row.started} · ${row.duration}. The session and its samples are " +
                "removed from this device, and nothing keeps a copy.",
            confirmLabel = "Delete",
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }

    if (state.pendingClear) {
        ConfirmDialog(
            title = "Clear all history?",
            message = "Every recorded session and all of its samples are deleted. The totals go back to " +
                "zero. This cannot be undone.",
            confirmLabel = "Delete everything",
            onConfirm = viewModel::confirmClear,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

private fun subtitleFor(state: SessionsUiState): String = when {
    state.isLoading -> "Reading the history"
    state.live != null -> "Recording ${state.live.label} now"
    state.isEmpty -> "Nothing recorded yet"
    state.hasFilter -> "${state.rows.size} of ${state.filters.firstOrNull()?.count ?: state.rows.size} " +
        "sessions · ${state.filterLabel}"
    else -> "${state.rows.size} recorded on this device"
}

/**
 * The session being recorded right now.
 *
 * Given its own card, above the list and outside it, because it is not a record yet: the duration is still
 * moving, the figures are as of the last flush, and there is no report to open. Ending it from here is a
 * clean stop — the row it leaves behind carries no "at least".
 */
@Composable
private fun LiveCard(live: LiveSession, onStop: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Recording now",
        icon = Icons.Filled.Insights,
        modifier = modifier,
        action = { StatusChip(text = "Live", tone = live.tone) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = live.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = live.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = live.duration, style = MaterialTheme.typography.headlineSmall)
        }
        ActionRow {
            TextButton(onClick = onStop) { Text("Stop recording") }
        }
    }
}

/** The totals, across everything recorded. The card says so, because the chips below filter the list. */
@Composable
private fun StatisticsCard(state: SessionsUiState, modifier: Modifier = Modifier) {
    SectionCard(
        title = "All time",
        subtitle = "Across every session recorded, whatever the filter below says",
        icon = Icons.Filled.History,
        modifier = modifier,
    ) {
        state.statistics.forEach { ReadoutRow(it) }
    }
}

/**
 * One chip per game, each with its own count.
 *
 * A [LazyRow] rather than a wrapping layout: the number of games a user has recorded is unbounded, and
 * this is the one place in the app where horizontal scrolling is the honest answer to that.
 */
@Composable
private fun FilterRow(state: SessionsUiState, onFilter: (String?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.filters, key = { it.packageName ?: ALL_GAMES_KEY }) { filter ->
            ChoiceChip(
                text = "${filter.label} · ${filter.count}",
                isSelected = filter.packageName == state.filterPackage,
                onClick = { onFilter(filter.packageName) },
            )
        }
    }
}

@Composable
private fun SortCard(state: SessionsUiState, onSort: (SessionSort) -> Unit, modifier: Modifier = Modifier) {
    SectionCard(title = "Order", modifier = modifier) {
        ChoiceRow(
            options = SessionSort.entries.toList(),
            selected = state.sort,
            onSelect = onSort,
            label = { it.label },
            perRow = 2,
        )
    }
}

/**
 * One recorded session, tappable through to its report.
 *
 * The three figures are drawn as a strip of their own rather than as rows, because at this size they are
 * being scanned down a column and not read. [SessionRow.note] is the interrupted case and gets a full line:
 * it is the difference between a length and a lower bound, and it does not fit in a chip.
 */
@Composable
private fun SessionCard(
    row: SessionRow,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ClickableCard(onClick = onOpen, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.started,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = row.duration, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            row.figures.forEach { figure ->
                CompactStat(
                    label = figure.label,
                    value = figure.value,
                    tone = figure.tone,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        row.note?.let { note ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "$note — the duration above is a floor, not a length.",
                style = MaterialTheme.typography.bodySmall,
                color = Tone.Warning.colour(),
            )
        }
        ActionRow {
            TextButton(onClick = onOpen) { Text("Report") }
            TextButton(onClick = onDelete) { Text("Delete", color = Tone.Danger.colour()) }
        }
    }
}

/**
 * Where the history lives, said once on the screen that holds it.
 *
 * §30 and §24A.5 are both user-visible facts here: nothing recorded leaves the device, and the database it
 * sits in is encrypted. A user deciding whether to let an app record what they play for months is entitled
 * to read that without going looking for it.
 */
@Composable
private fun RetentionCard(modifier: Modifier = Modifier) {
    SectionCard(title = "Where this is kept", icon = Icons.Filled.Info, modifier = modifier) {
        Text(
            text = RETENTION,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowDivider()
        Text(
            text = SAMPLES_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val ALL_GAMES_KEY = "__all__"

private const val TRACKING_OFF =
    "Session tracking is off, so nothing new is being recorded. What is already here stays until you " +
        "delete it."

private const val EMPTY_MESSAGE =
    "A session is recorded while a game you have a profile for is in the foreground: how long you played, " +
        "what the battery did, and whatever else this device lets GameCore measure. Add a game and it " +
        "starts on its own."

private const val RETENTION =
    "Every session is stored in GameCore's own encrypted database on this device. Nothing is uploaded, " +
        "there is no account, and the app makes no network requests for any of it. Deleting a session or " +
        "clearing the history removes it for good."

private const val SAMPLES_NOTE =
    "Each session also keeps the individual samples behind its graphs. They go with the session when it is " +
        "deleted, and an export from Settings writes exactly what is here — nothing is reconstructed."

