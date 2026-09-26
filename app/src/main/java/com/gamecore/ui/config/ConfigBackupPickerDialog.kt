package com.gamecore.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gamecore.core.common.Formatters
import com.gamecore.core.model.ConfigBackup
import com.gamecore.ui.theme.Spacing

/**
 * The "which copy do you want back?" step behind the config editor's Restore (spec §A6/§A7).
 *
 * A restore is only meaningful against a specific copy, so this lists every backup the editor found for
 * the file — the untouched [original][ConfigBackup.isOriginal] first, then the user's checkpoints, newest
 * first as the repository already orders them — and a tap on a row is the choice. Stateless, like the
 * app's other config dialogs: it holds nothing, takes the finished [backups] list and two callbacks, and
 * leaves it to the caller to have not shown it at all when the list is empty (there is nothing to restore
 * before the first save secures an original).
 *
 * The original is named for what it is — the file *before* the first edit — because that is the floor the
 * user is reaching for; a checkpoint shows the label they gave it, or a plain "Checkpoint" when they gave
 * none, and every row carries the time it was taken so two checkpoints of the same file are told apart by
 * when rather than by an opaque id. Tapping a row is destructive to the *live* file, not to the backup, so
 * there is no extra confirm here: the diff the user already saw on the way in, and the originals that are
 * never overwritten, are the safety net — a second modal would only be a wall.
 */
@Composable
fun ConfigBackupPickerDialog(
    backups: List<ConfigBackup>,
    onPick: (ConfigBackup) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Restore a copy", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for (backup in backups) {
                    BackupRow(backup = backup, onPick = onPick)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    )
}

/**
 * One tappable backup: what it is on the first line, and when it was taken plus its size on the second.
 *
 * A transparent [Surface] so the whole row is one ripple and one target, matching the browser's rows. The
 * title distinguishes the one original from the many checkpoints; the subtitle is always the capture time
 * ([Formatters.dateTime]) and byte size, so nothing about the choice is hidden behind a tap.
 */
@Composable
private fun BackupRow(
    backup: ConfigBackup,
    onPick: (ConfigBackup) -> Unit,
) {
    val title = when {
        backup.isOriginal -> "Original — before your first edit"
        !backup.label.isNullOrBlank() -> backup.label
        else -> "Checkpoint"
    }
    Surface(
        onClick = { onPick(backup) },
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = Spacing.md)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${Formatters.dateTime(backup.createdAtMillis)} · ${Formatters.bytes(backup.sizeBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
