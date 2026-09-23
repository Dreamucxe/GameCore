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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.model.DetectionRemedy
import com.gamecore.core.model.DELETE_PROFILE_TITLE
import com.gamecore.core.model.GameCardState
import com.gamecore.core.model.chipsSentence
import com.gamecore.core.model.deleteProfileMessage
import com.gamecore.core.model.profileClaimNote
import com.gamecore.core.model.shownChips
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.PreLaunchWarningDialog
import com.gamecore.ui.components.ProfileChipRow
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.theme.Density
import com.gamecore.ui.theme.Spacing

/**
 * The user's game profiles, and whether GameCore can see a game start.
 *
 * The detection state is given a card of its own at the top rather than a line at the bottom. A profile
 * only does anything if something notices the game launching, and on a device where usage access has
 * never been granted the honest thing to lead with is that nothing is watching — not the list.
 *
 * §5's redesign turns each profile's one truncated summary sentence into a scannable card: the game's real
 * icon, its effects as a wrapping row of chips (from the same generator the Home hero uses, capped with a
 * "+N more" so one heavy profile cannot push the next game off the screen), and — the honesty rule §10
 * asks for — the profile's state as a *word* beside the dimming, never dimming alone. Delete moved into an
 * overflow menu behind a confirm dialog whose wording is held to what the audit says actually happens.
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
    // §3 compact density tightens the gaps *between* cards while leaving the padding inside them alone.
    val cardGap = if (state.isCompact) Spacing.md * Density.COMPACT_FACTOR else Spacing.md

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = Spacing.xs, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(cardGap),
    ) {
        item {
            ScreenHeader(
                title = "Games",
                subtitle = state.subtitle,
                action = {
                    // Kept in every state, including the empty one — the empty state's button and this
                    // are two doors to the same place, and hiding this on an empty list would be the one
                    // time a user looking for "add" cannot find it in the corner it always lives in.
                    TextButton(onClick = { onOpenProfile(Destination.NEW_PROFILE) }) { Text("Add") }
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
                    action = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
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
                    title = "No games yet",
                    message = "A profile is what GameCore does when one game starts: a refresh rate, the " +
                        "overlays you want, a session recording. Add the games you play.",
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
            title = DELETE_PROFILE_TITLE,
            message = deleteProfileMessage(doomed.label),
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.delete(doomed.packageName)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    // §C4. Shown when a launch measured a poor connection on a profile that asked to be warned. Every
    // answer leads somewhere — the check holds a launch, it never refuses one.
    state.pendingLaunch?.let { pending ->
        PreLaunchWarningDialog(
            reason = pending.reason,
            onLaunchAnyway = viewModel::confirmPendingLaunch,
            onDontWarn = viewModel::dontWarnPendingLaunch,
            onCancel = viewModel::dismissPendingLaunch,
        )
    }
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
            Spacer(modifier = Modifier.height(Spacing.sm))
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
 * One profile: its icon, what it will do as chips, whether it is on, and what the user can do to it.
 *
 * Buttons rather than a tappable card. A card that is itself a click target *and* contains a switch and
 * several actions gives a screen reader overlapping targets and gives a thumb an ambiguous one.
 *
 * A disabled or uninstalled profile is drawn dimmed — but the dimming is applied only to the informational
 * half (icon, name, chips) and never to the controls, so the card stays visibly interactive, and the state
 * is always spelled out in a [StatusChip] word beside it because §10 forbids conveying state by alpha alone.
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
    // "Playing" and "On" need no dimming; "Off" and "Not installed" do. Dimming the identity half only —
    // the controls stay at full opacity so the card never looks inert.
    val dimmed = row.state == GameCardState.OFF || row.state == GameCardState.NOT_INSTALLED
    val identityAlpha = if (dimmed) DIMMED_ALPHA else 1f

    PlainCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GameIcon(
                packageName = row.packageName,
                label = row.label,
                modifier = Modifier.alpha(identityAlpha),
            )
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(identityAlpha),
                )
                Text(
                    text = row.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(identityAlpha),
                )
            }
            Spacer(modifier = Modifier.width(Spacing.sm))
            StateChip(row.state)
            Spacer(modifier = Modifier.width(Spacing.sm))
            // Uninstalled: the switch cannot change what an absent game does, so it is disabled — but the
            // state chip and the note below say why, and Edit and Delete stay available.
            Switch(
                checked = row.isEnabled,
                onCheckedChange = onSetEnabled,
                enabled = row.isInstalled,
            )
        }

        // The chips, capped, with the remainder spoken as a plain "+N more" rather than a chip that
        // pretends to be an effect. The whole row is collapsed into one screen-reader stop that reads the
        // *full* effect list (via chipsSentence), so the visible cap never hides an effect from someone who
        // cannot see the chips — the "+N more" is a sighted-only convenience, not a place data hides.
        val chips = shownChips(row.chips)
        if (chips.shown.isNotEmpty()) {
            val sentence = chipsSentence(row.chips)
            Spacer(modifier = Modifier.height(Spacing.sm))
            Column(
                modifier = Modifier
                    .alpha(identityAlpha)
                    .then(
                        if (sentence != null) {
                            Modifier.clearAndSetSemantics { contentDescription = sentence }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                ProfileChipRow(chips = chips.shown)
                if (chips.overflow > 0) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = "+${chips.overflow} more",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // The honest caveat under the chips — only for the two states where the chips do not say it all
        // (nothing configured yet, or effects that write no device setting).
        profileClaimNote(row.claim)?.let { note ->
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!row.isInstalled) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            NoteBanner(
                text = "This game is not installed at the moment. The profile is kept, and will start " +
                    "working again if you reinstall it.",
                tone = Tone.Muted,
                icon = Icons.Filled.VisibilityOff,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.xs))
        ActionRow {
            IconButton(onClick = onPlay, enabled = row.isInstalled && !anyBusy) {
                Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = "Start ${row.label}")
            }
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onApply, enabled = row.isInstalled && !anyBusy) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(Spacing.sm))
                }
                Text(if (isBusy) "Applying" else "Apply now")
            }
            Spacer(modifier = Modifier.weight(1f))
            ProfileOverflowMenu(label = row.label, enabled = !anyBusy, onDelete = onDelete)
        }
    }
}

/** The card's state word, tinted but never colour-alone — the text is always the signal. */
@Composable
private fun StateChip(state: GameCardState) {
    val tone = when (state) {
        GameCardState.PLAYING -> Tone.Good
        GameCardState.ON -> Tone.Muted
        GameCardState.OFF -> Tone.Muted
        GameCardState.NOT_INSTALLED -> Tone.Warning
    }
    StatusChip(text = state.label, tone = tone)
}

/**
 * The per-profile overflow, holding Delete.
 *
 * Delete lives behind a menu and a confirm dialog rather than as a fifth button on the row: it is the one
 * irreversible action on the card, and a destructive button sitting a thumb-width from Play and Apply is
 * how it gets pressed by accident. The menu keeps its own open state locally — nothing about which card's
 * menu is showing needs to survive a recomposition of the list.
 */
@Composable
private fun ProfileOverflowMenu(label: String, enabled: Boolean, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }, enabled = enabled) {
        Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "More options for $label")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Delete", color = Tone.Danger.colour()) },
            onClick = {
                expanded = false
                onDelete()
            },
        )
    }
}

/**
 * Undoes a manual apply.
 *
 * Separate from the profiles because it is not one profile's business: whatever GameCore last changed
 * gets put back, whether that was this game's profile or the one above it. Less prominent than the old
 * standalone button — a quiet card at the foot of the list, where an occasional recovery action belongs.
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
            TextButton(onClick = onRestore, enabled = !isBusy) { Text("Restore settings") }
        }
    }
}

/** How far a disabled or uninstalled profile's identity half fades — dim enough to read as inactive, not so
 * dim it cannot be read at all (it stays above the 4.5:1 body-text floor on this app's surfaces). */
private const val DIMMED_ALPHA = 0.6f
