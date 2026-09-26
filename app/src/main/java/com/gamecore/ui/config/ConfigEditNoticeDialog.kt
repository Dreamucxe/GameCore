package com.gamecore.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The §26 notice shown once before a user first edits a game's config files, so the power they are about
 * to reach for is stated where they reach for it rather than left to be discovered by a broken save.
 *
 * Editing a config is not like changing a setting in this app: it writes into a file the game owns, and a
 * bad value there can corrupt a save or make the game behave in ways GameCore cannot undo for it. What
 * GameCore does guarantee is the one thing that makes editing safe to offer at all — it copies an
 * untouched original of every file before the first change and keeps it, so a restore is always one tap
 * away. The dialog says exactly that, and no more, because a warning the user cannot act on is just a wall
 * between them and their edit.
 *
 * Stateless but for the checkbox it owns: "Don't show this again" is a choice about this dialog, so it is
 * held here and handed back through [onProceed] rather than lifted into a caller that has no other reason
 * to know it. Everything else — whether the flag has already been set, and therefore whether this dialog
 * is shown at all — belongs to the screen that gates the edit, not here. The copy lives beside the
 * composable, as the app's other screen and dialog copy does, rather than in strings.xml.
 *
 * @param onProceed the user accepted the notice; the boolean is the state of "Don't show this again".
 * @param onDismiss the user backed out — via Cancel or a tap outside — and no edit should follow.
 */
@Composable
fun ConfigEditNoticeDialog(
    onProceed: (dontShowAgain: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var dontShowAgain by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Editing config files", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Editing a game's config files is powerful. A wrong value can corrupt a save " +
                        "or make the game misbehave, and those changes happen inside the game's own " +
                        "files — not something GameCore can undo for it.\n\n" +
                        "Before the first change, GameCore secures one untouched original of every file " +
                        "and lets you restore it at any time. Even so, you edit at your own risk.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = dontShowAgain,
                            role = Role.Checkbox,
                            onValueChange = { dontShowAgain = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // The row carries the toggle so the label is a target too; the box itself stays inert
                    // (`onCheckedChange = null`) rather than fire the change a second time.
                    Checkbox(checked = dontShowAgain, onCheckedChange = null)
                    Text(text = "Don't show this again", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onProceed(dontShowAgain) }) { Text("I understand") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    )
}
