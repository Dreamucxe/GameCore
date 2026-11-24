package com.gamecore.ui.market

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.plugin.market.MarketEntry
import com.gamecore.core.plugin.market.MarketEntryState
import com.gamecore.core.plugin.market.MarketEntryStatus
import com.gamecore.ui.components.ChoiceChip
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone

/**
 * Discover: the curated index, the updates the user has not taken yet, and the hash check that makes
 * either one safe.
 *
 * The screen never says "trusted" on its own. A verified entry carries the curator's mark, the card
 * names the hash-verified download path, and the confirmation for an update says exactly what will
 * change — the plugin arrives switched off with its grant cleared. A marketplace that hides that step
 * is a permission bypass with prettier typography.
 */
@Composable
fun MarketScreen(
    onBack: () -> Unit,
    onOpenPlugins: () -> Unit,
    viewModel: MarketViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<MarketEntryStatus?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = ScreenBottomPadding),
    ) {
        item {
            ScreenHeader(
                title = "Discover",
                subtitle = "Curated plugins, hash-checked before install",
                onBack = onBack,
                action = {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(12.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Read the plugin index again",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
            )
        }

        state.error?.let { error ->
            item {
                NoteBanner(
                    text = error,
                    tone = Tone.Warning,
                    icon = Icons.Filled.Warning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenPadding, vertical = 4.dp),
                    action = {
                        TextButton(onClick = viewModel::dismissMessage) { Text("Dismiss") }
                    },
                )
            }
        }

        state.message?.let { message ->
            item {
                NoteBanner(
                    text = message,
                    tone = Tone.Accent,
                    icon = Icons.Filled.Info,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenPadding, vertical = 4.dp),
                    action = {
                        TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
                    },
                )
            }
        }

        if (state.statuses.isNotEmpty()) {
            item {
                PlainCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenPadding, vertical = 4.dp),
                    contentPadding = 12.dp,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (state.updateCount > 0) {
                                    "${state.updateCount} " +
                                        (if (state.updateCount == 1) "update" else "updates") +
                                        " available"
                                } else {
                                    "Everything installed is up to date"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "${state.statuses.size} curated " +
                                    (if (state.statuses.size == 1) "plugin" else "plugins") +
                                    " · packages are checked against the index hash before install",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onOpenPlugins) { Text("My plugins") }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenPadding, vertical = 4.dp),
                    placeholder = { Text("Search by name, developer or package") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = ScreenPadding, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MarketFilter.entries.forEach { filter ->
                        if (filter == MarketFilter.UPDATES && state.updateCount == 0) return@forEach
                        ChoiceChip(
                            text = if (filter == MarketFilter.UPDATES) {
                                "Updates (${state.updateCount})"
                            } else {
                                filter.label
                            },
                            isSelected = state.filter == filter,
                            onClick = { viewModel.setFilter(filter) },
                        )
                    }
                }
            }
        }

        when {
            state.isLoading && state.statuses.isEmpty() -> item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(ScreenPadding),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }

            state.statuses.isEmpty() -> item {
                PlainCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenPadding, vertical = 8.dp),
                ) {
                    Text(
                        text = "The curated index could not be read. Check the connection and try again; " +
                            "nothing is installed from a screen that has no index.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = viewModel::refresh) { Text("Try again") }
                }
            }

            state.visible.isEmpty() -> item {
                PlainCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenPadding, vertical = 8.dp),
                ) {
                    Text(
                        text = if (state.filter == MarketFilter.UPDATES) {
                            "No updates right now. Installed plugins are at the version the index lists."
                        } else {
                            "Nothing in the index matches this search."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> items(state.visible, key = { it.entry.id }) { status ->
                MarketEntryCard(
                    status = status,
                    isWorking = state.workingId == status.entry.id,
                    anyWorking = state.workingId != null,
                    onAction = { pending = status },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenPadding, vertical = 4.dp),
                )
            }
        }

        item {
            Text(
                text = "The index is a curated file in GameCore's repository. The package itself is " +
                    "always installed through the same loader and consent screen as a file you picked " +
                    "yourself.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
            )
        }
    }

    pending?.let { status ->
        InstallConfirmDialog(
            status = status,
            onConfirm = {
                pending = null
                viewModel.install(status.entry)
            },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun MarketEntryCard(
    status: MarketEntryStatus,
    isWorking: Boolean,
    anyWorking: Boolean,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = status.entry
    PlainCard(modifier = modifier, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.verified) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Reviewed for the index",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Text(
                    text = "${entry.developer} · ${entry.category.label} · ${entry.versionName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            StatusChip(text = stateLabel(status), tone = stateTone(status.state))
        }

        if (entry.summary.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "${formatSize(entry.sizeBytes)} · ${entry.id}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        entry.versionName.takeIf { it.isNotBlank() }?.let { version ->
            status.installedVersion?.let { installed ->
                if (status.state == MarketEntryState.UPDATE_AVAILABLE) {
                    Text(
                        text = "Installed: $installed → index: $version",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        status.state.actionLabel?.let { label ->
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onAction,
                enabled = !anyWorking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Verifying and installing…")
                } else {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun InstallConfirmDialog(
    status: MarketEntryStatus,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val entry = status.entry
    val isUpdate = status.state == MarketEntryState.UPDATE_AVAILABLE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (isUpdate) "Update ${entry.name}?" else "Install ${entry.name}?")
        },
        text = {
            Column {
                Text(
                    text = "GameCore will download ${formatSize(entry.sizeBytes)} from the curated " +
                        "repository, check it against the index hash, and only then hand it to the " +
                        "plugin loader.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isUpdate) {
                        "An update replaces the installed package, clears its granted permissions and " +
                            "leaves it switched off. You will review the new version before it can run."
                    } else {
                        "A new plugin arrives switched off. You will review its permissions before it " +
                            "can run, and nothing is granted by this download."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (status.installed != null && !isUpdate) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "A copy is already installed at ${status.installedVersion ?: "an unknown version"}.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(if (isUpdate) "Update" else "Install")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    )
}

private fun stateLabel(status: MarketEntryStatus): String = when (status.state) {
    MarketEntryState.NOT_INSTALLED -> "Not installed"
    MarketEntryState.UPDATE_AVAILABLE -> "Update"
    MarketEntryState.UP_TO_DATE -> "Up to date"
    MarketEntryState.BUNDLED -> "Bundled"
    MarketEntryState.INCOMPATIBLE -> "Unsupported"
}

private fun stateTone(state: MarketEntryState): Tone = when (state) {
    MarketEntryState.NOT_INSTALLED -> Tone.Accent
    MarketEntryState.UPDATE_AVAILABLE -> Tone.Warning
    MarketEntryState.UP_TO_DATE -> Tone.Good
    MarketEntryState.BUNDLED -> Tone.Muted
    MarketEntryState.INCOMPATIBLE -> Tone.Danger
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "unknown size"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
