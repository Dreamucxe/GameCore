package com.gamecore.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Names a checkpoint before it is taken (spec §A6): one optional label field over the current bytes.
 *
 * A checkpoint is a mark on the file as it stands now, kept alongside the untouched original, and the only
 * thing that tells two of them apart in the restore picker is when they were taken and what the user called
 * them. This is where that name is given. It is optional on purpose — a checkpoint with no label is still a
 * useful "here is where it was", shown as a plain "Checkpoint" in the picker — so the confirm button is
 * always live, and an empty or blank field becomes `null` rather than an empty string, which is what
 * [ConfigEditorViewModel.checkpoint] and [ConfigBackupPickerDialog]'s "Checkpoint" fallback both expect.
 *
 * Stateless but for the field's own text, like the app's other config dialogs (title style titleLarge,
 * container the surface colour, large shape): it holds the half-typed name only, hands the trimmed result to
 * [onConfirm], and leaves everything about the file and the buffer to the caller.
 */
@Composable
fun ConfigCheckpointDialog(
    onConfirm: (label: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Save a checkpoint", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Marks the file as it is now, kept beside the untouched original so you can come " +
                        "back to this point later. A name is optional.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(label.trim().ifEmpty { null }) }) { Text("Save checkpoint") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    )
}
