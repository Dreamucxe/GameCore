package com.gamecore.ui.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The one-time notice shown the first time a user turns Instant Replay on (§3.6), before any capture
 * starts, so continuous background recording is never a surprise.
 *
 * Stateless by design: it does not decide whether it should appear or remember that it has. Whoever calls
 * it owns the "has the user seen this" flag (a single boolean in preferences is enough) and shows the
 * dialog only until [onConfirm] fires. That keeps the "one-time" rule testable and out of the drawing
 * code, matching how the editor's other notices ([PreLaunchWarningDialog], the resolution notice) are
 * held by the screen and merely drawn here.
 *
 * Non-blocking and honest about the three things the audit says a user must know before opting in:
 * recording runs the whole time the game is open, everything stays on this device, and this version is
 * video only. Built on [AlertDialog] with the copy inline, exactly like the editor's other dialogs.
 *
 * @param onConfirm the user accepted; the caller should record that the notice was seen and let the
 *   opt-in stand.
 * @param onCancel the user backed out (button or scrim); the caller should leave Instant Replay off.
 */
@Composable
fun InstantReplayDisclaimerDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(text = "Before you turn on Instant Replay", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "While you play, GameCore keeps recording the screen into a short rolling " +
                        "buffer so the last few seconds are always ready to save. Recording runs the " +
                        "whole time the game is open.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "The buffer and any clip you save stay in GameCore's private storage on this " +
                        "device — they are never uploaded. The rolling buffer is cleared when you stop " +
                        "playing or turn Instant Replay off.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "This version records video only, without sound.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Turn it on") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Not now") } },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    )
}
