package com.gamecore.ui.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamecore.core.plugin.AutomationHistoryRecord
import com.gamecore.ui.components.ChoiceChip
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The stateless automation-history screen: a pure function of [PluginAutomationHistoryUiState] and the
 * callbacks that change it. It holds no ViewModel and no business rules — [PluginAutomationHistoryRoute]
 * binds it to one — so a Compose preview or a screenshot test can drive every panel below with literals.
 *
 * It renders three load phases as three different panels, which is the screen's one load-bearing honesty
 * decision made visible:
 *
 *  - [LoadPhase.Loading]: a spinner.
 *  - [LoadPhase.Denied]: the "not allowed" panel. The plugin does not hold the automation-history scope,
 *    so GameCore refused the read. This is deliberately *not* the empty panel — telling a user who
 *    revoked a scope that "nothing has run" would be a lie.
 *  - [LoadPhase.Ready]: the trail. With no records it is the honest "nothing has run yet" empty panel;
 *    with records it is the search box, the outcome-filter chips, the sort toggle and the rows.
 *
 * Everything shown is a real field on [AutomationHistoryRecord]: the operation, the outcome (worded by
 * [outcomeLabel] so an outcome from a newer build still reads sensibly rather than being dropped), the
 * already-sanitised detail, the owning plugin id, the timestamp and — only when the store recorded one —
 * the duration. There is no invented trigger type, rule name or running/cancelled status, because the
 * record carries none. Per section 11, no control signals by colour alone: the outcome tone sits behind a
 * worded [StatusChip], the filter chips are labelled, and the sort button carries its own text.
 *
 * Clearing the trail is a destructive user action, so the Clear control opens a [ConfirmDialog] and only
 * the confirm button calls [onConfirmClear]; a plugin can never drive the clear, and a stray tap cannot.
 */
@Composable
fun PluginAutomationHistoryScreen(
    state: PluginAutomationHistoryUiState,
    onBack: () -> Unit,
    onSearchChange: (String) -> Unit,
    onOutcomeFilterChange: (String?) -> Unit,
    onToggleSort: () -> Unit,
    onConfirmClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showClearDialog by remember { mutableStateOf(false) }
    val visible = remember(state) { visibleRecords(state) }
    val formatter = remember { SimpleDateFormat("MMM d, yyyy  HH:mm:ss", Locale.getDefault()) }
    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Automation history",
                subtitle = "GameCore's record of automations run on your device",
                onBack = onBack,
                // Offered only once the read has succeeded and there is a trail to erase. A Clear shown
                // over the "not allowed" panel, or one that would delete nothing, is worse than none.
                action = if (state.phase == LoadPhase.Ready && state.hasRecords) {
                    { TextButton(onClick = { showClearDialog = true }) { Text("Clear") } }
                } else {
                    null
                },
            )
        }

        when (state.phase) {
            LoadPhase.Loading -> item { LoadingBody() }
            LoadPhase.Denied -> item { DeniedPanel() }
            LoadPhase.Ready -> if (!state.hasRecords) {
                item { EmptyPanel() }
            } else {
                readyBody(
                    state = state,
                    visible = visible,
                    formatter = formatter,
                    padded = padded,
                    onSearchChange = onSearchChange,
                    onOutcomeFilterChange = onOutcomeFilterChange,
                    onToggleSort = onToggleSort,
                )
            }
        }
    }
    if (showClearDialog) {
        ConfirmDialog(
            title = "Clear automation history?",
            message = "This erases every line of GameCore's automation history, for every plugin. It " +
                "cannot be undone and nothing keeps a copy. The automations themselves keep running; " +
                "only the record of what they have done is removed.",
            confirmLabel = "Clear history",
            onConfirm = {
                showClearDialog = false
                onConfirmClear()
            },
            onDismiss = { showClearDialog = false },
        )
    }
}

/**
 * The [LoadPhase.Ready]-with-records body: the view controls over the loaded trail, then the rows. Split
 * into a [LazyListScope] extension so each piece is its own lazy item and the sort/filter row does not
 * scroll with hundreds of records off the top of the list.
 */
private fun LazyListScope.readyBody(
    state: PluginAutomationHistoryUiState,
    visible: List<AutomationHistoryRecord>,
    formatter: SimpleDateFormat,
    padded: Modifier,
    onSearchChange: (String) -> Unit,
    onOutcomeFilterChange: (String?) -> Unit,
    onToggleSort: () -> Unit,
) {
    item {
        SearchField(query = state.query, onSearchChange = onSearchChange, modifier = padded)
    }
    item {
        OutcomeFilterRow(
            outcomes = outcomesPresent(state.records),
            selected = state.outcomeFilter,
            onSelect = onOutcomeFilterChange,
        )
    }
    item {
        ListControls(
            shownCount = visible.size,
            totalCount = state.totalCount,
            newestFirst = state.newestFirst,
            onToggleSort = onToggleSort,
            modifier = padded,
        )
    }
    if (visible.isEmpty()) {
        item {
            NoteBanner(
                text = "No automations match your search or filter.",
                tone = Tone.Muted,
                modifier = padded,
            )
        }
    } else {
        items(visible, key = { it.id }) { record ->
            HistoryRow(record = record, formatter = formatter, modifier = padded)
        }
    }
}

@Composable
private fun LoadingBody() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun DeniedPanel() {
    EmptyState(
        icon = Icons.Filled.Block,
        title = "Not allowed to show this",
        message = "This plugin isn't allowed to read your automation history, so GameCore has nothing " +
            "to show. Grant it the \"Automation history\" data access on its permissions screen to let " +
            "it open this record. This is not the same as an empty history: GameCore is not saying " +
            "nothing has run, only that it cannot show you.",
    )
}

@Composable
private fun EmptyPanel() {
    EmptyState(
        icon = Icons.Filled.History,
        title = "Nothing has run yet",
        message = "No automations have run on your device, so there is nothing to show. Lines appear " +
            "here as GameCore runs the automations your plugins have set up.",
    )
}
@Composable
private fun SearchField(
    query: String,
    onSearchChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        // Collapse newlines so a pasted multi-line fragment stays a single-line needle.
        onValueChange = { onSearchChange(it.replace('\n', ' ')) },
        label = { Text("Search") },
        placeholder = { Text("Operation, detail or plugin") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onSearchChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun OutcomeFilterRow(
    outcomes: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ChoiceChip(text = "All", isSelected = selected == null, onClick = { onSelect(null) })
        }
        items(outcomes, key = { it }) { outcome ->
            ChoiceChip(
                text = outcomeLabel(outcome),
                isSelected = selected == outcome,
                onClick = { onSelect(outcome) },
            )
        }
    }
}
@Composable
private fun ListControls(
    shownCount: Int,
    totalCount: Int,
    newestFirst: Boolean,
    onToggleSort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (shownCount == totalCount) {
                "$totalCount ${lines(totalCount)}"
            } else {
                "Showing $shownCount of $totalCount ${lines(totalCount)}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.Muted.colour(),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onToggleSort) {
            Icon(
                imageVector = if (newestFirst) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (newestFirst) "Newest first" else "Oldest first")
        }
    }
}
@Composable
private fun HistoryRow(
    record: AutomationHistoryRecord,
    formatter: SimpleDateFormat,
    modifier: Modifier = Modifier,
) {
    PlainCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.operation,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatter.format(Date(record.timestampMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Tone.Muted.colour(),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            StatusChip(text = outcomeLabel(record.outcome), tone = outcomeTone(record.outcome))
        }
        // The detail is already capped and sanitised at the audit boundary, so it is shown as plain
        // text — never parsed, linkified or run.
        if (record.detail.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = record.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = record.pluginId,
                style = MaterialTheme.typography.bodySmall,
                color = Tone.Muted.colour(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Shown only when the store actually measured a duration; a null is left blank, not "0".
            record.durationMillis?.let { duration ->
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatDuration(duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = Tone.Muted.colour(),
                )
            }
        }
    }
}
/**
 * A tone for an outcome, used only to colour the worded [StatusChip] beside it — never as the sole signal,
 * so a user who cannot tell the tones apart still reads the label. The names are the stable strings
 * [AuditEntry.Outcome] stores; an outcome from a newer build falls to the neutral [Tone.Muted] rather
 * than being forced into a wrong colour.
 */
private fun outcomeTone(outcome: String): Tone = when (outcome) {
    "ALLOWED" -> Tone.Good
    "CLAMPED" -> Tone.Accent
    "RATE_LIMITED" -> Tone.Warning
    "DENIED" -> Tone.Warning
    "ERROR" -> Tone.Danger
    else -> Tone.Muted
}

private fun lines(count: Int): String = if (count == 1) "line" else "lines"

/** A duration in its smallest honest unit: whole milliseconds under a second, one decimal of a second above. */
private fun formatDuration(millis: Long): String =
    if (millis < 1000) "$millis ms" else "%.1f s".format(Locale.getDefault(), millis / 1000.0)
