package com.gamecore.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * A launch the pre-launch network check (§C4) flagged, held until the user says what to do with it.
 *
 * [packageName] is the game the check measured for; [reason] is the already-user-facing line the check
 * produced — the specific worst axis it found ("Latency is high, around 180 ms."), not a generic label.
 * The check never blocks a launch, so this is only ever a held decision: every one of the dialog's three
 * choices leads somewhere, and "Launch anyway" is always among them.
 */
data class PendingLaunch(val packageName: String, val reason: String)

/**
 * The §C4 warning sheet, shown before a game whose profile asked to be warned launches on a connection a
 * real probe burst just rated poor. Non-blocking by construction — it offers three ways forward and no
 * way to be stuck:
 *
 *  - **Launch anyway** starts the game this once and leaves the profile's setting alone.
 *  - **Don't warn for this game** starts it and turns this profile's pre-launch warning off, for the user
 *    who plays on a connection GameCore will always rate poor and does not want asking each time.
 *  - **Cancel** does neither: nothing launches and nothing changes.
 *
 * The three actions stack vertically rather than crowd the AlertDialog's one-line button row, which has
 * no room for three labels this long. The copy lives here beside the composable, as the app's other
 * screen copy does, rather than in strings.xml.
 */
@Composable
fun PreLaunchWarningDialog(
    reason: String,
    onLaunchAnyway: () -> Unit,
    onDontWarn: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = "Network looks poor", style = MaterialTheme.typography.titleLarge) },
        text = { Text(text = reason, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                TextButton(onClick = onLaunchAnyway) { Text("Launch anyway") }
                TextButton(onClick = onDontWarn) { Text("Don't warn for this game") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    )
}
