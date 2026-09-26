package com.gamecore.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gamecore.core.config.DiffResult
import com.gamecore.core.config.DiffType
import com.gamecore.ui.theme.StatusGood

/**
 * The "does this look right?" step between editing a config and writing it back (spec §28).
 *
 * The dialog is deliberately stateless: it is handed a finished [DiffResult] — the diff is computed by
 * [com.gamecore.core.config.LineDiff], not here — and two callbacks, and it holds nothing of its own. That
 * is what lets it sit under a caller that already owns the edit's state (the config editor) without a
 * ViewModel, Hilt, or navigation of its own; [onConfirm] is the user committing the write and [onDismiss]
 * is backing out, and which of those happened is the caller's to record.
 *
 * Each changed line is marked twice over, never by colour alone (the §2 status rule): an added line gets a
 * `+` gutter and the app's "good" green, a removed line a `-` gutter and the theme's error red, and an
 * unchanged line a blank gutter in the muted on-surface colour so the edits stand out from the context
 * around them. The line text is monospaced so a config's alignment survives the preview, and the whole diff
 * scrolls within a bounded height so a large file cannot push the confirm and cancel buttons off-screen.
 */
@Composable
fun ConfigDiffDialog(
    result: DiffResult,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Resolved once: StatusGood is the accent-free "good" colour (Color.kt), error is the theme's red, and
    // the muted on-surface colour keeps unchanged context from competing with the marked lines.
    val addedColour: Color = StatusGood
    val removedColour: Color = MaterialTheme.colorScheme.error
    val unchangedColour: Color = MaterialTheme.colorScheme.onSurfaceVariant
    val lineStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Confirm changes", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${result.added} added, ${result.removed} removed",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    for (line in result.lines) {
                        val gutter: String
                        val colour: Color
                        when (line.type) {
                            DiffType.ADDED -> {
                                gutter = "+"
                                colour = addedColour
                            }
                            DiffType.REMOVED -> {
                                gutter = "-"
                                colour = removedColour
                            }
                            DiffType.UNCHANGED -> {
                                gutter = " "
                                colour = unchangedColour
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = gutter,
                                style = lineStyle,
                                color = colour,
                                modifier = Modifier.width(16.dp),
                            )
                            Text(text = line.text, style = lineStyle, color = colour)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    )
}
