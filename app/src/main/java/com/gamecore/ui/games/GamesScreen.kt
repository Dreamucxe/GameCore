package com.gamecore.ui.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import com.gamecore.core.common.Formatters
import com.gamecore.core.model.DetectionRemedy
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour

/**
 * The user's game profiles, and whether GameCore can see a game start.
 *
 * The detection state is given a card of its own at the top rather than a line at the bottom. A profile
 * only does anything if something notices the game launching, and on a device where usage access has
 * never been granted the honest thing to lead with is that nothing is watching — not the list.
 */
@Composable
fun GamesScreen(
    onOpenProfile: (String) -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GamesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<GameRow?>(null) }

    // The remedy for "no detection" is a system settings page, so the answer can change while this
    // screen is still on top of the stack.
    OnResume { viewModel.refreshDetection() }

    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Games",
                subtitle = subtitleFor(state),
                action = {
                    TextButton(onClick = { onOpenProfile(Destination.NEW_PROFILE) }) {
                        Text("Add")
                    }
                },
            )
        }

        if (state.message != null) {
            item {
                NoteBanner(
                    text = state.message.orEmpty(),
                    tone = Tone.Accent,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                    action = {
                        TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
                    },
                )
            }
        }

        item {
            DetectionCard(
                state = state,
                onSetAutoApply = viewModel::setAutoApply,
                onNavigate = onNavigate,
                modifier = padded,
            )
        }

        if (state.isEmpty) {
            item {
                EmptyState(
                    icon = Icons.Filled.SportsEsports,
                    title = "No profiles yet",
                    message = "A profile is what GameCore does when one game starts: a refresh rate, " +
                        "the overlays you want, a session recording. Add the games you play.",
                    actionLabel = "Add a game",
                    onAction = { onOpenProfile(Destination.NEW_PROFILE) },
                )
            }
        }

        items(state.profiles, key = { it.packageName }) { row ->
            ProfileCard(
                row = row,
                isBusy = state.busyPackage == row.packageName,
                anyBusy = state.busyPackage != null,
                onEdit = { onOpenProfile(row.packageName) },
                onSetEnabled = { viewModel.setEnabled(row.packageName, it) },
                onPlay = { viewModel.play(row.packageName) },
                onApply = { viewModel.applyNow(row.packageName) },
                onDelete = { pendingDelete = row },
                modifier = padded,
            )
        }

        if (state.profiles.isNotEmpty()) {
            item {
                RestoreRow(
                    isBusy = state.busyPackage != null,
                    onRestore = viewModel::restoreNow,
                    modifier = padded,
                )
            }
        }
    }

    val doomed = pendingDelete
    if (doomed != null) {
        ConfirmDialog(
            title = "Delete this profile?",
            message = "${doomed.label}'s settings will be forgotten. Any session already recorded " +
                "for it is kept.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.delete(doomed.packageName)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

private fun subtitleFor(state: GamesUiState): String = when {
    !state.isLoaded -> "Loading"
    state.profiles.isEmpty() -> "Nothing configured yet"
    state.enabledCount == state.profiles.size -> Formatters.count(state.profiles.size, "profile")
    else -> "${Formatters.count(state.profiles.size, "profile")} · ${state.enabledCount} on"
}

/**
 * Whether anything is watching for a game, and whether profiles are applied automatically.
 *
 * The switch is offered even when detection is unavailable, rather than hidden or forced off. The user
 * may be about to grant the access, and a switch that silently turned itself off would be a second
 * mystery on top of the first.
 */
@Composable
private fun DetectionCard(
    state: GamesUiState,
    onSetAutoApply: (Boolean) -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Automatic",
        icon = Icons.Filled.Visibility,
        modifier = modifier,
        action = {
            StatusChip(
                text = if (state.detectionAvailable) "Watching" else "Not watching",
                tone = if (state.detectionAvailable) Tone.Good else Tone.Muted,
            )
        },
    ) {
        SwitchRow(
            title = "Apply a profile when its game starts",
            checked = state.autoApply,
            onCheckedChange = onSetAutoApply,
            description = "And put the settings back when it stops.",
        )
        val note = state.detectionNote
        if (note != null) {
            Spacer(modifier = Modifier.height(6.dp))
            NoteBanner(
                text = note,
                tone = if (state.isConfiguredButBlind) Tone.Warning else Tone.Muted,
                icon = Icons.Filled.Info,
                action = {
                    val remedy = state.detectionRemedy
                    if (remedy != null) {
                        TextButton(
                            onClick = {
                                onNavigate(
                                    when (remedy) {
                                        DetectionRemedy.GRANT_USAGE_ACCESS -> Destination.Permissions
                                        DetectionRemedy.START_SHIZUKU -> Destination.Shizuku
                                    },
                                )
                            },
                        ) {
                            Text(remedy.label)
                        }
                    }
                },
            )
        }
    }
}

/**
 * One profile: what it will do, whether it is on, and the four things the user can do to it.
 *
 * Buttons rather than a tappable card. A card that is itself a click target *and* contains a switch and
 * three actions gives a screen reader five overlapping targets and gives a thumb an ambiguous one.
 */
@Composable
private fun ProfileCard(
    row: GameRow,
    isBusy: Boolean,
    anyBusy: Boolean,
    onEdit: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onPlay: () -> Unit,
    onApply: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlainCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.isPlaying) {
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusChip(text = "Playing", tone = Tone.Good)
                    }
                }
                Text(
                    text = row.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = row.isEnabled,
                onCheckedChange = onSetEnabled,
                enabled = row.isInstalled,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = row.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = if (row.isEnabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (!row.isInstalled) {
            Spacer(modifier = Modifier.height(8.dp))
            NoteBanner(
                text = "This game is not installed at the moment. The profile is kept, and will " +
                    "start working again if you reinstall it.",
                tone = Tone.Muted,
                icon = Icons.Filled.VisibilityOff,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        ActionRow {
            // An icon rather than a fifth text button: four labels and a Delete do not fit the card's
            // width at the larger UI scales the settings screen offers, and a clipped Delete is worse
            // than a glyph everyone already reads as "start this".
            IconButton(onClick = onPlay, enabled = row.isInstalled && !anyBusy) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Start ${row.label}",
                )
            }
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onApply, enabled = row.isInstalled && !anyBusy) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isBusy) "Applying" else "Apply now")
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDelete, enabled = !anyBusy) {
                Text(text = "Delete", color = Tone.Danger.colour())
            }
        }
    }
}

/**
 * Undoes a manual apply.
 *
 * Separate from the profiles because it is not one profile's business: whatever GameCore last changed
 * gets put back, whether that was this game's profile or the one above it.
 */
@Composable
private fun RestoreRow(isBusy: Boolean, onRestore: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Applied one by hand?",
        subtitle = "Puts back every setting GameCore has changed and not yet restored.",
        icon = Icons.Filled.Restore,
        modifier = modifier,
    ) {
        ActionRow {
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onRestore, enabled = !isBusy) { Text("Put settings back") }
        }
    }
}
