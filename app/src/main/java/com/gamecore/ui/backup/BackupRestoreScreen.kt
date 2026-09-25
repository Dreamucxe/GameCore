package com.gamecore.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
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
import com.gamecore.data.repository.BackupPolicy
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.components.startIntentSafely

/**
 * The §20 backup screen: write every configuration store to one file to keep or move to another device, and
 * restore such a file back — carefully, and never over the user's session history.
 *
 * A sibling of `MacroEditorScreen` in its skeleton (a `LazyColumn` with the shared header, no `Scaffold`)
 * but not in its nature: there is no list to render and no live store to mirror, because a backup is an
 * action rather than a document. So the screen is two cards — back up, restore — over a plain-language
 * account of exactly what a backup does and does not carry, and the restore's destructive path is kept
 * behind an explicit confirmation. The file itself is picked with the Storage Access Framework, so no
 * storage permission is asked for and GameCore only ever sees the one file the user points it at.
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
    // until the picker returns a document. A cancelled pick returns null and simply does nothing.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.restore(uri, pendingPolicy)
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
                subtitle = "Save your setup to a file, or bring it back",
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
 * toggle would hide that. [onMerge] launches the file picker straight away; [onReplace] only asks for the
 * file — the screen shows a confirmation first, so the destructive path is never one tap.
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

/** The MIME type the picker filters to and the backup is written as; a plain JSON document. */
private const val MIME_JSON = "application/json"

private const val WHAT_A_BACKUP_IS =
    "A backup saves your setup — appearance and settings, your game profiles with the crosshair and colour " +
        "presets they use, any other presets and HUD layouts you have made, and your macros. It does not " +
        "include your session history or Aim Lab runs, and it does not carry which layout, crosshair or " +
        "colour is currently active — those stay as they are on this device. Restoring never touches the " +
        "games you have recorded."

private const val RESTORE_EXPLANATION =
    "Pick a backup file to bring your setup back. Merge adds what is missing and leaves everything you " +
        "already have in place, matching layouts and presets by content so running it twice changes nothing " +
        "the second time. Replace does the same, but where you both have a profile for the same game the " +
        "file's version wins, and your macros are swapped for the file's. Appearance and settings are applied " +
        "either way, and neither one deletes anything the file leaves out or touches your session history."

private const val REPLACE_WARNING =
    "Replace lets the file win where something already exists: a profile for a game you both have is " +
        "overwritten with the file's, and your macros are swapped for the file's. It still only adds the " +
        "layouts and presets that are missing — nothing unrelated is deleted, and your session history is " +
        "not affected — but the profiles and macros it overwrites cannot be brought back. Merge instead to " +
        "only add what the file has, without changing what you already keep."

