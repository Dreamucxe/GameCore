package com.gamecore.ui.games

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.model.DetectionRemedy
import com.gamecore.core.model.DELETE_PROFILE_TITLE
import com.gamecore.core.model.GameCardState
import com.gamecore.core.model.chipsSentence
import com.gamecore.core.model.deleteProfileMessage
import com.gamecore.core.model.profileClaimNote
import com.gamecore.core.model.shownChips
import com.gamecore.data.repository.ProfileCollisionPolicy
import com.gamecore.domain.gaming.ApplyDiagnostic
import com.gamecore.domain.gaming.ApplyResult
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.NavRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.PreLaunchWarningDialog
import com.gamecore.ui.components.ProfileChipRow
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SectionLabel
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.components.startIntentSafely
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
    val transferState by viewModel.transferState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<GameRow?>(null) }

    // The remedy for "no detection" is a system settings page, so the answer can change while this
    // screen is still on top of the stack.
    OnResume { viewModel.refreshDetection() }

    // SAF hands back a URI readable for this call only; [ProfileTransfer] copies what it needs out of it and
    // never keeps the grant. A cancelled pick returns null and simply does nothing (§5, mirroring the
    // crosshair image picker and the backup restore picker).
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFrom(uri)
    }

    // A written export stages a one-shot share intent; fire it once and let the ViewModel clear it, telling
    // it back whether anything on the device actually accepted the file.
    LaunchedEffect(transferState.share?.id) {
        transferState.share?.let { request ->
            viewModel.shareHandled(shared = context.startIntentSafely(request.intent))
        }
    }

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
                    // Import and Add share the corner that is present in every state, the empty one
                    // included — bringing a profile in from a file is exactly what a user with an empty
                    // list reaches for, so hiding it there would be the one time it cannot be found.
                    Row {
                        TextButton(onClick = { importPicker.launch(arrayOf(MIME_JSON)) }) { Text("Import") }
                        TextButton(onClick = { onOpenProfile(Destination.NEW_PROFILE) }) { Text("Add") }
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
                    action = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
                )
            }
        }

        // The library leads. A user opening Games is looking for a game, not for a diagnostic, so the
        // profiles are a two-column grid of covers directly under the header and detection — which is
        // reference material about *how* they apply — sits underneath them. The grid is built from
        // rows of two rather than a LazyVerticalGrid because the screen already scrolls vertically;
        // nesting a second vertical scroller would give the list two scroll positions.
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
        } else if (state.profiles.isNotEmpty()) {
            item {
                SectionLabel(
                    text = "Library",
                    subtitle = Formatters.count(state.profiles.size, "profile") + " saved on this device.",
                    modifier = padded,
                )
            }
            items(state.profiles.chunked(2), key = { chunk -> chunk.first().packageName }) { chunk ->
                Row(
                    modifier = padded
                        // Rows fade and slide into their new place when a profile is added, removed
                        // or re-sorted, so the library never snaps between two arrangements.
                        .animateItem()
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    chunk.forEach { row ->
                        ProfileCard(
                            row = row,
                            isBusy = state.busyPackage == row.packageName,
                            anyBusy = state.busyPackage != null,
                            onEdit = { onOpenProfile(row.packageName) },
                            onSetEnabled = { viewModel.setEnabled(row.packageName, it) },
                            onPlay = { viewModel.play(row.packageName) },
                            onApply = { viewModel.applyNow(row.packageName) },
                            onExport = { viewModel.exportProfile(row.packageName) },
                            onDelete = { pendingDelete = row },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    if (chunk.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        item {
            DetectionCard(
                state = state,
                onSetAutoApply = viewModel::setAutoApply,
                onSetPinned = viewModel::setPinnedPackage,
                onRemoveExcluded = viewModel::removeExcludedPackage,
                onAddExcluded = viewModel::addExcludedPackage,
                onNavigate = onNavigate,
                modifier = padded,
            )
        }

        // The emulator frontend is experimental and deliberately adjacent to game detection rather than
        // in the bottom bar: it is a launcher for apps the user installed, not part of GameCore's own
        // performance path. The label is the first thing the row says.
        item {
            PlainCard(modifier = padded) {
                NavRow(
                    title = "Emulators · experimental",
                    description = "Add ROMs and hand them to an emulator already installed on this device",
                    icon = Icons.Filled.SportsEsports,
                    onClick = { onNavigate(Destination.Emulators) },
                )
            }
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

    // §5. An import found a package that already has a saved profile. Nothing has been written yet — the
    // user decides how each clash is resolved, and Cancel writes nothing at all.
    transferState.collision?.let { prompt ->
        ImportCollisionDialog(
            prompt = prompt,
            onReplace = { viewModel.resolveImport(ProfileCollisionPolicy.REPLACE) },
            onKeepBoth = { viewModel.resolveImport(ProfileCollisionPolicy.KEEP_BOTH) },
            onCancel = viewModel::cancelImport,
        )
    }
}

/**
 * Whether anything is watching for a game, and what the last detection and apply actually did.
 *
 * The switch is offered even when detection is unavailable, rather than hidden or forced off. The user
 * may be about to grant the access, and a switch that silently turned itself off would be a second
 * mystery on top of the first.
 *
 * The diagnostics below it are readings, not advice. "No decision yet" and "No apply yet" are the
 * truthful states before the detector has run; nothing here guesses that a game probably started, and
 * the pin only reorders and badges the list rather than claiming a session.
 */
@Composable
private fun DetectionCard(
    state: GamesUiState,
    onSetAutoApply: (Boolean) -> Unit,
    onSetPinned: (String?) -> Unit,
    onRemoveExcluded: (String) -> Unit,
    onAddExcluded: (String) -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    var addingApp by remember { mutableStateOf(false) }

    SectionCard(
        title = "Detection & apply",
        icon = Icons.Filled.Visibility,
        modifier = modifier,
        action = {
            StatusChip(
                text = if (state.detectionAvailable) "Watching" else "Not watching",
                tone = if (state.detectionAvailable) Tone.Good else Tone.Muted,
            )
        },
    ) {
        NoteBanner(
            text = state.detectionNote
                ?: "GameCore checks the foreground app and acts only on the profiles you have enabled.",
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

        Spacer(modifier = Modifier.height(Spacing.sm))
        SwitchRow(
            title = "Apply a profile when its game starts",
            checked = state.autoApply,
            onCheckedChange = onSetAutoApply,
            description = "And put the settings back when it stops.",
        )

        Spacer(modifier = Modifier.height(Spacing.xs))
        RowDivider()
        LastDecision(state.lastDecision)
        RowDivider()
        LastApply(state.lastApply)
        RowDivider()
        PinnedProfileControl(
            pinnedPackage = state.pinnedPackage,
            profiles = state.profiles,
            onSetPinned = onSetPinned,
        )
        RowDivider()
        ExcludedApps(apps = state.excludedApps, onRemove = onRemoveExcluded)
        NavRow(
            title = "Add app",
            description = "Exclude an app from detection by package name.",
            onClick = { addingApp = true },
        )
    }

    if (addingApp) {
        AddExcludedAppDialog(
            existing = state.excludedApps.map { it.packageName }.toSet(),
            onAdd = {
                onAddExcluded(it)
                addingApp = false
            },
            onDismiss = { addingApp = false },
        )
    }
}

/** The last foreground package the detector judged, with the policy's own reason and the time. */
@Composable
private fun LastDecision(decision: DetectionDecisionView?) {
    Text(
        text = "Last decision",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (decision == null) {
        Text(
            text = "No decision yet",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Spacer(modifier = Modifier.height(Spacing.xs))
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = decision.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = decision.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = if (decision.shouldTrack) {
                    Tone.Good.colour()
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = Formatters.clockTime(decision.atMillis),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The last real application, never a guess that one happened because a game is in front. */
@Composable
private fun LastApply(application: ApplyDiagnostic?) {
    Text(
        text = "Last apply",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (application == null) {
        Text(
            text = "No apply yet",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Spacer(modifier = Modifier.height(Spacing.xs))
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = application.profileName.ifBlank { application.packageName },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${application.result.label} · ${application.detail.ifBlank { "No detail." }}",
                style = MaterialTheme.typography.bodyMedium,
                color = application.result.tone.colour(),
            )
        }
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = Formatters.clockTime(application.atMillis),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A pin is a view preference: it badges and sorts a profile, and explicitly starts nothing. */
@Composable
private fun PinnedProfileControl(
    pinnedPackage: String?,
    profiles: List<GameRow>,
    onSetPinned: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = profiles.filter { it.isEnabled }
    val selected = enabled.firstOrNull { it.packageName == pinnedPackage }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Text(
            text = "Pinned profile",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Shown first with a badge. A pin does not start the game or a session.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(
                    text = selected?.label ?: "None",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("None") },
                    onClick = {
                        expanded = false
                        onSetPinned(null)
                    },
                )
                enabled.forEach { row ->
                    DropdownMenuItem(
                        text = { Text(row.label) },
                        onClick = {
                            expanded = false
                            onSetPinned(row.packageName)
                        },
                    )
                }
            }
        }
    }
}

/** The allowlist, with the same remove affordance the app uses on its other protection lists. */
@Composable
private fun ExcludedApps(apps: List<ExcludedAppRow>, onRemove: (String) -> Unit) {
    Text(
        text = "Excluded apps",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (apps.isEmpty()) {
        Text(
            text = "Nothing excluded. Every enabled profile is watched.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Text(
        text = "These apps are never watched, even if they have an enabled profile.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    apps.forEach { app ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { onRemove(app.packageName) }) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Stop excluding ${app.label}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The allowlist's one text field, validated against Android's package grammar before anything is saved.
 *
 * The dialog stays open on a rejection, with the reason under the field, because closing it would look
 * like the app had been excluded and leave the user to discover otherwise later. A duplicate is treated
 * as an error for the same reason: the entry is already doing what the user asked, so saying so is more
 * honest than closing on a no-op.
 */
@Composable
private fun AddExcludedAppDialog(
    existing: Set<String>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    val validated = TextSanitizer.validatePackageName(input)
    val error = when {
        !submitted -> null
        validated == null -> "That is not a package name. Use letters, digits, underscores and dots, " +
            "with at least one dot."
        validated in existing -> "That app is already excluded."
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Exclude an app", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text(
                    text = "Enter the package name Android reports for the app, for example " +
                        "com.example.game.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it.take(MAX_PACKAGE_INPUT)
                        submitted = false
                    },
                    label = { Text("Package name") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = if (error != null) {
                        { Text(text = error) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    submitted = true
                    val packageName = TextSanitizer.validatePackageName(input)
                    if (packageName != null && packageName !in existing) onAdd(packageName)
                },
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    )
}

/** The word an apply result is reported with; the enum names are not user-facing. */
private val ApplyResult.label: String
    get() = when (this) {
        ApplyResult.APPLIED -> "Applied"
        ApplyResult.SKIPPED -> "Skipped"
        ApplyResult.FAILED -> "Failed"
        ApplyResult.NOT_NEEDED -> "Not needed"
    }

private val ApplyResult.tone: Tone
    get() = when (this) {
        ApplyResult.APPLIED -> Tone.Good
        ApplyResult.SKIPPED -> Tone.Muted
        ApplyResult.FAILED -> Tone.Warning
        ApplyResult.NOT_NEEDED -> Tone.Muted
    }

/** Android caps a package name at 255 characters; the field stops at the same point. */
private const val MAX_PACKAGE_INPUT = 255

/**
 * One profile as a library tile: the game's own icon, its state word, its effects, and the controls.
 *
 * The tile is not one big click target. Its *identity half* is — the icon, name and package open the
 * editor — while the switch and the action row are siblings outside that clickable area, so a screen
 * reader never meets overlapping targets and a thumb never has to guess whether a tap edits or toggles.
 * That is the same rule the old full-width row followed; only the shape changed.
 *
 * A disabled or uninstalled profile is drawn dimmed — but the dimming is applied only to the informational
 * half (icon, name, chips) and never to the controls, so the tile stays visibly interactive, and the state
 * is always spelled out in a [StatusChip] word beside it because §10 forbids conveying state by alpha alone.
 *
 * The chips are capped at [GRID_CHIPS_SHOWN] (two rows of two) because a grid cell is half a screen wide;
 * the "+N more" is a sighted-only convenience, and the whole row is collapsed into one screen-reader stop
 * that reads the *full* effect list, so no effect is hidden from someone who cannot see the chips.
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
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // "Playing" and "On" need no dimming; "Off" and "Not installed" do. Dimming the identity half only —
    // the controls stay at full opacity so the tile never looks inert.
    val dimmed = row.state == GameCardState.OFF || row.state == GameCardState.NOT_INSTALLED
    val identityAlpha = if (dimmed) DIMMED_ALPHA else 1f

    PlainCard(modifier = modifier, contentPadding = 12.dp, fillHeight = true) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onEdit)
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(
                        if (dimmed) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                GameIcon(
                    packageName = row.packageName,
                    label = row.label,
                    size = 42.dp,
                    modifier = Modifier.alpha(identityAlpha),
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(identityAlpha),
                )
                Text(
                    text = row.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(identityAlpha),
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = Spacing.sm),
        ) {
            StateChip(row.state)
            if (row.isPinned) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Pinned",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // A switch on its own line, not beside the state chip: half a screen is not wide enough for
        // "Not installed" and a switch to share a row without clipping one of them. The word beside it
        // means the control is not left to colour alone, and the switch stays the only toggle target.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = Spacing.xs),
        ) {
            Text(
                text = if (row.isEnabled) "Profile on" else "Profile off",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = row.isEnabled,
                onCheckedChange = onSetEnabled,
                enabled = row.isInstalled,
            )
        }

        val chips = shownChips(row.chips)
        if (chips.shown.isNotEmpty()) {
            val visible = chips.shown.take(GRID_CHIPS_SHOWN)
            val hidden = chips.overflow + (chips.shown.size - visible.size)
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
                ProfileChipRow(chips = visible, perRow = 2)
                if (hidden > 0) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = "+$hidden more",
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (!row.isInstalled) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = "Not installed. The profile is kept and will work again if you reinstall it.",
                style = MaterialTheme.typography.bodySmall,
                color = Tone.Warning.colour(),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Weighted, so every tile in a row puts its controls on the same baseline even when one has
        // more chips or a longer claim note than the other.
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPlay, enabled = row.isInstalled && !anyBusy) {
                Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = "Start ${row.label}")
            }
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(16.dp)
                        .semantics { contentDescription = "Applying ${row.label}" },
                    strokeWidth = 2.dp,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            ProfileOverflowMenu(
                label = row.label,
                enabled = !anyBusy,
                isBusy = isBusy,
                canApply = row.isInstalled,
                onApply = onApply,
                onEdit = onEdit,
                onExport = onExport,
                onDelete = onDelete,
            )
        }
    }
}

/** How many effect chips a half-width library tile shows before it summarises the rest. */
private const val GRID_CHIPS_SHOWN = 4

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
 * The per-profile overflow, holding Edit, Apply now, Export / Share and Delete.
 *
 * A half-width tile has room for one primary action (Play) and one menu. Everything else moves in here:
 * Apply is a rare manual override, Export is rare and non-destructive, and Delete is the one irreversible
 * action on the tile — a destructive button sitting a thumb-width from Play is how it gets pressed by
 * accident, so it keeps its confirm dialog and its place at the bottom of the menu.
 *
 * The menu keeps its own open state locally; nothing about which tile's menu is showing needs to survive a
 * recomposition of the list.
 */
@Composable
private fun ProfileOverflowMenu(
    label: String,
    enabled: Boolean,
    isBusy: Boolean,
    canApply: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }, enabled = enabled) {
        Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "More options for $label")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Edit profile") },
            onClick = {
                expanded = false
                onEdit()
            },
        )
        DropdownMenuItem(
            text = { Text(if (isBusy) "Applying…" else "Apply now") },
            enabled = canApply && !isBusy,
            onClick = {
                expanded = false
                onApply()
            },
        )
        DropdownMenuItem(
            text = { Text("Export / Share") },
            onClick = {
                expanded = false
                onExport()
            },
        )
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

/**
 * A §5 import found packages already saved on this device, and asks how to resolve them before writing
 * anything. Three choices, stacked as [PreLaunchWarningDialog] stacks its own, because the labels are too
 * long for the AlertDialog's one-line button row:
 *
 *  - **Keep both** imports each clashing profile under a new name and leaves the saved one untouched.
 *  - **Replace** overwrites the saved profile with the incoming one.
 *  - **Cancel** writes nothing — not the clashing profiles, and not the ones that did not clash.
 *
 * Cancel dropping even the non-colliding profiles is deliberate: an import is one action the user chose as a
 * whole, and a Cancel that quietly kept half of it would be a surprise. They can pick the file again.
 */
@Composable
private fun ImportCollisionDialog(
    prompt: ImportCollisionPrompt,
    onReplace: () -> Unit,
    onKeepBoth: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = "Some profiles already exist", style = MaterialTheme.typography.titleLarge) },
        text = { Text(text = collisionMessage(prompt), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                TextButton(onClick = onKeepBoth) { Text("Keep both") }
                TextButton(onClick = onReplace) { Text("Replace") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    )
}

/** Names the clashing profiles (capped) and what each choice will do, for the collision dialog's body. */
private fun collisionMessage(prompt: ImportCollisionPrompt): String {
    val shown = prompt.collidingLabels.take(COLLISION_LABELS_SHOWN)
    val extra = prompt.collidingLabels.size - shown.size
    val names = shown.joinToString(", ") + if (extra > 0) " and $extra more" else ""
    val count = prompt.collidingLabels.size
    val head = if (count == prompt.total) {
        "All $count of the profiles in this file are already saved here: $names."
    } else {
        "$count of the ${prompt.total} profiles in this file are already saved here: $names."
    }
    return "$head\n\nReplace overwrites what is saved. Keep both imports the incoming ones under a new " +
        "name and leaves yours untouched. Cancel imports nothing."
}

/** The MIME the import picker filters on, and the type the export share sheet offers — a profile file is JSON. */
private const val MIME_JSON = "application/json"

/** How many clashing profile names the collision dialog lists before it summarises the rest as "and N more". */
private const val COLLISION_LABELS_SHOWN = 5
