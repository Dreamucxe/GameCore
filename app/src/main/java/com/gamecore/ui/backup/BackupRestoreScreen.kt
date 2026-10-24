package com.gamecore.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.common.Formatters
import com.gamecore.data.repository.BackupPolicy
import com.gamecore.data.repository.BackupPreview
import com.gamecore.data.repository.RetentionStatus
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.NavRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.components.startIntentSafely
import com.gamecore.ui.settings.RETENTION_DAY_OPTIONS

/**
 * The §20 backup screen: write every configuration store and the recorded history to one file to keep or
 * move to another device, restore such a file back — carefully, and only after saying what is in it — and
 * decide how long recorded sessions are kept.
 *
 * A sibling of `MacroEditorScreen` in its skeleton (a `LazyColumn` with the shared header, no `Scaffold`)
 * but not in its nature: there is no list to render and no live store to mirror, because a backup is an
 * action rather than a document. So the screen is three cards — back up, restore, retention — over a
 * plain-language account of exactly what a backup does and does not carry, and both destructive paths (the
 * replace restore and the explicit purge) are kept behind an explicit confirmation. A picked file is decoded
 * and counted *before* the confirmation, so the user confirms what is actually in the file rather than a
 * promise about it. The file itself is picked with the Storage Access Framework, so no storage permission is
 * asked for and GameCore only ever sees the one file the user points it at.
 */
@Composable
fun BackupRestoreScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupRestoreViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val padded = Modifier.padding(horizontal = ScreenPadding)
    var confirmReplace by remember { mutableStateOf(false) }
    var pendingPolicy by remember { mutableStateOf(BackupPolicy.MERGE) }

    // The user picks the file after choosing what a restore should do; the chosen policy rides in [pendingPolicy]
    // until the picker returns a document. The file is then decoded and counted, and the restore happens only
    // from the confirmation that follows — a cancelled pick returns null and simply does nothing.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.preview(uri, pendingPolicy)
    }
    val openFile: (BackupPolicy) -> Unit = { policy ->
        pendingPolicy = policy
        picker.launch(arrayOf(MIME_JSON))
    }

    // A written backup stages a one-shot share intent; launch it once and let the ViewModel clear it.
    LaunchedEffect(state.share?.id) {
        state.share?.let { request ->
            context.startIntentSafely(request.intent)
            viewModel.onShareLaunched()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ScreenHeader(
                title = "Backup & restore",
                subtitle = "Save your setup and history to a file, or bring them back",
                onBack = onBack,
            )
        }
        item {
            PlainCard(modifier = padded) {
                Text(text = WHAT_A_BACKUP_IS, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            BackUpCard(canAct = state.canAct, isBusy = state.isBusy, onExport = viewModel::export, modifier = padded)
        }
        item {
            RestoreCard(
                canAct = state.canAct,
                onMerge = { openFile(BackupPolicy.MERGE) },
                onReplace = { confirmReplace = true },
                modifier = padded,
            )
        }
        item {
            RetentionCard(
                retention = state.retention,
                canAct = state.canAct,
                onSetDays = viewModel::setRetentionDays,
                onPurge = viewModel::purgeNow,
                modifier = padded,
            )
        }
        state.message?.let { message ->
            item {
                NoteBanner(
                    text = message,
                    tone = state.messageTone,
                    icon = if (state.messageTone == Tone.Danger) Icons.Filled.Warning else Icons.Filled.Info,
                    modifier = padded,
                )
            }
        }
    }

    // The replace warning comes first, before the picker, because it is about the policy; the preview below
    // comes after the pick, because it is about the file.
    if (confirmReplace) {
        ConfirmDialog(
            title = "Let the file win?",
            message = REPLACE_WARNING,
            confirmLabel = "Choose a file",
            onConfirm = {
                confirmReplace = false
                openFile(BackupPolicy.REPLACE)
            },
            onDismiss = { confirmReplace = false },
        )
    }

    val preview = state.preview
    if (preview != null) {
        ConfirmDialog(
            title = "This file contains",
            message = previewMessage(preview, pendingPolicy),
            confirmLabel = "Restore",
            onConfirm = viewModel::confirmRestore,
            onDismiss = viewModel::dismissPreview,
            isDestructive = pendingPolicy == BackupPolicy.REPLACE,
        )
    }
}

/**
 * The "back up" half: one primary action that writes the file and hands it to the share sheet.
 *
 * A filled [Button] rather than the restore card's text buttons, because writing a backup is the screen's
 * headline action and the one a first-time visitor is looking for. It is disabled while any action runs
 * ([canAct]) and reads back its own progress ([isBusy]) so a slow write never looks like a dead tap.
 */
@Composable
private fun BackUpCard(
    canAct: Boolean,
    isBusy: Boolean,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Back up", modifier = modifier) {
        Text(
            text = "Writes one file with everything above, then opens the share sheet so you can keep it in " +
                "Files, a cloud drive or send it to another device.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ActionRow {
            Button(onClick = onExport, enabled = canAct) {
                Text(if (isBusy) "Writing the file…" else "Back up now")
            }
        }
    }
}

/**
 * The "restore" half: the safe merge on the left, the destructive replace on the right.
 *
 * Two text buttons rather than one, because merge and replace are genuinely different acts and a mode
 * toggle would hide that. [onMerge] launches the file picker straight away; [onReplace] shows the warning
 * first. Either way the picked file is decoded and counted before anything is written — the confirmation
 * that follows is the screen's, and it names what the file actually holds.
 */
@Composable
private fun RestoreCard(
    canAct: Boolean,
    onMerge: () -> Unit,
    onReplace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Restore", modifier = modifier) {
        Text(text = RESTORE_EXPLANATION, style = MaterialTheme.typography.bodyMedium)
        ActionRow {
            TextButton(onClick = onMerge, enabled = canAct) { Text("Merge from a file") }
            TextButton(onClick = onReplace, enabled = canAct) {
                Text(text = "Replace from a file", color = Tone.Danger.colour())
            }
        }
    }
}

/**
 * The retention half: the window dropdown, what it currently puts out of scope, and an explicit purge.
 *
 * The count is read from the manager rather than computed here, and the button is live only when there is
 * something for it to delete — a disabled destructive action beside a truthful "nothing to delete" is the
 * only honest way to show a purge that would otherwise be a no-op. The confirmation names the count and the
 * window, and the copy says plainly that personal records survive.
 */
@Composable
private fun RetentionCard(
    retention: RetentionStatus?,
    canAct: Boolean,
    onSetDays: (Int) -> Unit,
    onPurge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmPurge by remember { mutableStateOf(false) }
    SectionCard(title = "History retention", modifier = modifier) {
        Text(text = RETENTION_EXPLANATION, style = MaterialTheme.typography.bodyMedium)
        val loaded = retention
        if (loaded == null) {
            Text(
                text = "Reading your history…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val days = loaded.retentionDays
            val count = loaded.purgeableSessions
            val canPurge = canAct && days > 0 && count > 0
            DropdownRow(
                title = "Delete sessions older than",
                value = retentionWindowLabel(days),
                options = RETENTION_DAY_OPTIONS,
                label = ::retentionWindowLabel,
                onSelect = onSetDays,
                description = "Applied when GameCore starts and after a restore. Keep forever stores everything.",
            )
            ActionRow {
                TextButton(onClick = { confirmPurge = true }, enabled = canPurge) {
                    Text(
                        text = if (count > 0) "Delete now (${Formatters.count(count, "session")})" else "Delete now",
                        color = if (canPurge) Tone.Danger.colour() else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = purgeableCaption(days, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val loaded = retention
    if (confirmPurge && loaded != null) {
        ConfirmDialog(
            title = "Delete old sessions?",
            message = "Permanently deletes ${Formatters.count(loaded.purgeableSessions, "recorded session")} " +
                "older than ${retentionWindowLabel(loaded.retentionDays)}. Personal records are never " +
                "deleted — a best run outlives the session that produced it.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmPurge = false
                onPurge()
            },
            onDismiss = { confirmPurge = false },
        )
    }
}

/**
 * A row whose value opens a Material dropdown, built from the screen's existing row and menu parts.
 *
 * Deliberately a copy of Settings' identical row rather than a shared component: the two controls that want
 * it are both "pick one of a short fixed list", and the shared list itself ([RETENTION_DAY_OPTIONS]) already
 * travels between the two screens, which is the part that must not diverge.
 */
@Composable
private fun <T> DropdownRow(
    title: String,
    value: String,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        NavRow(
            title = title,
            onClick = { expanded = true },
            description = description,
            trailing = value,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** The preview's one paragraph: what the file holds, what the chosen policy will do, and what it will not. */
private fun previewMessage(preview: BackupPreview.Ready, policy: BackupPolicy): String = buildString {
    append("Profiles: ${preview.profiles}. Game sessions: ${preview.gameSessions}. ")
    append("Aim Lab runs: ${preview.aimLabSessions}. Personal records: ${preview.personalRecords}. ")
    append(
        "Also ${Formatters.count(preview.layouts, "HUD layout")}, ${preview.crosshairs} crosshair preset(s), " +
            "${preview.colours} colour preset(s) and ${preview.macros} macro(s). "
    )
    if (preview.version < 2) {
        append("It was written before backups carried history, so there are no sessions in it. ")
    }
    append(
        if (policy == BackupPolicy.MERGE) {
            "Merge adds what is missing and leaves everything you already have in place."
        } else {
            "Replace lets the file win for profiles for the same game and for macros."
        }
    )
    append(
        " Nothing is written until you confirm, and a restore only ever adds sessions — anything removed " +
            "afterwards comes solely from your retention window."
    )

    // §20. Where the file came from, and what may not mean the same thing here. A v1/v2 file has no
    // snapshot at all, and that is said as "unknown origin" rather than dressed up as compatibility:
    // an absent comparison is not a reassuring one.
    val device = preview.device
    if (device == null) {
        append("\n\nOrigin: not recorded. This file predates device snapshots, so it cannot be " +
            "checked against this device. Display sizes and refresh rates in it may not exist here.")
    } else {
        append("\n\nOrigin: ${device.label}.")
        val checks = preview.migration
        if (checks.isEmpty()) {
            append(" No display or refresh-rate differences were found against this device.")
        } else {
            checks.forEach { check ->
                append("\n• ${check.title}: ${check.detail}")
            }
        }
    }
}

/** A retention window in the words the dropdown uses. Zero is "keep forever"; odd values still read true. */
private fun retentionWindowLabel(days: Int): String = when (days) {
    0 -> "Keep forever"
    30, 90, 180, 365 -> "$days days"
    else -> Formatters.count(days, "day")
}

/** The line under the button: why it is disabled, or what it would remove right now. */
private fun purgeableCaption(days: Int, count: Int): String = when {
    days <= 0 -> "Nothing is deleted automatically while the window is off."
    count == 0 -> "Nothing recorded so far is older than the window."
    else -> "${Formatters.count(count, "recorded session")} would be deleted right now."
}

/** The MIME type the picker filters to and the backup is written as; a plain JSON document. */
private const val MIME_JSON = "application/json"

private const val WHAT_A_BACKUP_IS =
    "A backup saves your setup — appearance and settings, your game profiles with the crosshair and colour " +
        "presets they use, any other presets and HUD layouts you have made, and your macros — and the history " +
        "this device has recorded: finished game sessions, Aim Lab runs and your personal records. It does not " +
        "carry plugins or the GameCore app itself, and it does not carry which layout, crosshair or colour is " +
        "currently active — those stay as they are on this device."

private const val RESTORE_EXPLANATION =
    "Pick a backup file to bring your setup back. Merge adds what is missing and leaves everything you " +
        "already have in place, matching layouts and presets by content so running it twice changes nothing " +
        "the second time. Replace does the same, but where you both have a profile for the same game the " +
        "file's version wins, and your macros are swapped for the file's. Appearance and settings are applied " +
        "either way. History from the file is added under both modes: a session you already have is skipped, " +
        "a personal record only moves up, and neither mode deletes a session the file leaves out."

private const val RETENTION_EXPLANATION =
    "The window is GameCore's own promise about your history: sessions that ended before it are removed when " +
        "the app starts and after a restore, and Delete now does it immediately. Personal records are kept — " +
        "a deleted session may still have produced one, and a best run outlives the session that made it."

private const val REPLACE_WARNING =
    "Replace lets the file win where something already exists: a profile for a game you both have is " +
        "overwritten with the file's, and your macros are swapped for the file's. It still only adds the " +
        "layouts and presets that are missing — nothing unrelated is deleted, and history is only ever added, " +
        "never removed — but the profiles and macros it overwrites cannot be brought back. Merge instead to " +
        "only add what the file has, without changing what you already keep."
