package com.gamecore.ui.logs

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.diagnostics.LogLevel
import com.gamecore.core.diagnostics.LogRecord
import com.gamecore.ui.components.ChoiceChip
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone

/**
 * The durable history the app keeps for itself and for the games it watched.
 *
 * Two promises shape the screen:
 *
 *  - **Off means off.** The switch stops new writes, and the banner says so instead of pretending the
 *    history is still live. Existing records survive until Clear, because turning off a recorder is
 *    not a request to erase what it already saw.
 *  - **Every row is copyable as it was written.** The detail dialog shows the same text the share
 *    file contains. A log viewer that reformats the stack trace is a second source of truth, and the
 *    one thing a bug report cannot afford is two versions of the same crash.
 */
@Composable
fun LogsScreen(
    onBack: () -> Unit,
    viewModel: LogsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val shareRequest by viewModel.shareRequest.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var selected by remember { mutableStateOf<LogRecord?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(shareRequest) {
        val intent = shareRequest ?: return@LaunchedEffect
        context.startActivity(Intent.createChooser(intent, "Share logs"))
        viewModel.consumeShare()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = ScreenBottomPadding),
    ) {
        item {
            ScreenHeader(
                title = "Logs & crashes",
                subtitle = "What GameCore and the games it watched did, kept on this device",
                onBack = onBack,
                icon = Icons.Filled.BugReport,
            )
        }

        item {
            PlainCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenPadding, vertical = 8.dp),
            ) {
                SwitchRow(
                    title = "Record logs",
                    checked = state.enabled,
                    onCheckedChange = viewModel::setEnabled,
                    description = "Crash reports and game-session boundaries. Off stops new records; " +
                        "nothing already written is deleted.",
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (state.enabled) {
                        "GameCore records its own crashes directly. A game's crash is read from the " +
                            "system crash buffer when Shizuku is available; without it, the session " +
                            "boundary is still recorded, but the game's stack trace is not."
                    } else {
                        "New crashes will not be recorded. This includes GameCore's own crash — the " +
                            "copy in Downloads is written by the same switch."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!state.enabled) {
            item {
                NoteBanner(
                    text = "Logging is off.",
                    tone = Tone.Warning,
                    icon = Icons.Filled.Warning,
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                )
            }
        }

        if (state.message != null) {
            item {
                NoteBanner(
                    text = state.message.orEmpty(),
                    tone = Tone.Muted,
                    icon = Icons.Filled.Info,
                    modifier = Modifier
                        .padding(horizontal = ScreenPadding)
                        .clickable { viewModel.dismissMessage() },
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenPadding, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { clipboard.setText(AnnotatedString(viewModel.copyText(state.visibleRecords))) },
                    enabled = state.visibleRecords.isNotEmpty(),
                ) { Text("Copy") }
                OutlinedButton(
                    onClick = { viewModel.share(state.visibleRecords) },
                    enabled = state.visibleRecords.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(modifier = Modifier.height(0.dp))
                    Text("Share")
                }
                TextButton(
                    onClick = { confirmClear = true },
                    enabled = state.records.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text("Clear")
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogFilter.entries.forEach { filter ->
                    ChoiceChip(
                        text = filter.label,
                        isSelected = state.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                    )
                }
            }
        }

        when {
            state.isLoading -> item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(ScreenPadding),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }

            state.visibleRecords.isEmpty() -> item {
                PlainCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenPadding, vertical = 12.dp),
                ) {
                    Text(
                        text = if (state.records.isEmpty()) {
                            "No records yet. GameCore writes here when it crashes, when a game " +
                                "session starts or ends, and when Shizuku lets it read a game's crash."
                        } else {
                            "No records match this filter."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> items(state.visibleRecords, key = { it.id }) { record ->
                LogRow(
                    record = record,
                    onClick = { selected = record },
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp),
                )
            }
        }
    }

    selected?.let { record ->
        LogDetailDialog(
            record = record,
            onCopy = {
                clipboard.setText(AnnotatedString(viewModel.copyText(listOf(record))))
                selected = null
            },
            onDismiss = { selected = null },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear log history?") },
            text = {
                Text(
                    "Every record on this screen will be deleted. The record switch is not changed, " +
                        "and the copy of GameCore's last crash in Downloads is not touched.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        viewModel.clear()
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun LogRow(
    record: LogRecord,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlainCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChip(
                text = record.kind.label,
                tone = when (record.level) {
                    LogLevel.ERROR -> Tone.Danger
                    LogLevel.WARNING -> Tone.Warning
                    LogLevel.INFO -> Tone.Muted
                },
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = record.shortTime,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = record.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!record.packageName.isNullOrBlank()) {
            Text(
                text = record.packageName.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val firstLine = record.detail.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        if (firstLine.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = firstLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LogDetailDialog(
    record: LogRecord,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(record.title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "${record.kind.label} · ${record.level.label} · ${record.shortTime}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!record.packageName.isNullOrBlank()) {
                    Text(
                        text = record.packageName.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = record.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${record.appVersion} · ${record.device}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onCopy) { Text("Copy") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
