package com.gamecore.ui.config

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamecore.ui.theme.Spacing

/**
 * The §27/§28 config editor: the whole of one file's text, in a monospaced field the user can change, with
 * the way to save it, hand it back to its original, or mark a checkpoint of where it is now.
 *
 * Deliberately stateless — the sibling of [ConfigDiffDialog] and [ConfigBrowserScreen] in that respect. It
 * holds no store, no ViewModel and no navigation of its own; it is handed the [fileName] to name the file,
 * the [text] to show, and the callbacks for the four things a user can do here, and draws exactly that. The
 * only thing it remembers is where the field is scrolled to, which is a fact about this view and not about
 * the document — the [text] itself is the caller's, changed only through [onTextChange], so an edit and the
 * saved copy can never drift out from under whoever owns them.
 *
 * When [readOnly] is set the field is shown but cannot be typed into — a binary file, or one the shell
 * cannot write — and Save goes dark, because there is nothing to write back. Save is otherwise enabled only
 * when the buffer is [isDirty]: a save that would write the file exactly as it already is is not offered.
 * Restore and Checkpoint stay live regardless; restoring a file the user has not touched is still how they
 * undo an earlier edit, and a checkpoint is a mark on the current bytes whether or not they are unsaved.
 *
 * When [showDriftWarning] is set a banner sits above the field: the game was updated since this file's
 * original was backed up (§A9), so a config saved against the old build may no longer fit. It is a warning,
 * not a lock — every action stays available — and [onDismissDriftWarning] clears it once acknowledged.
 *
 * The three states a document can be in are drawn as themselves, not as a blank field: while [isLoading]
 * the body is a centred spinner and the action bar is withheld, because there is nothing yet to act on; an
 * [error] is the whole of what the screen can show — a missing file, no elevated shell — and replaces the
 * field entirely; and a [message] is the transient outcome of an action (saved, restored, a read-only
 * reason) shown as a snackbar and cleared through [onMessageShown] once the host has surfaced it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditorScreen(
    fileName: String,
    text: String,
    onTextChange: (String) -> Unit,
    isDirty: Boolean,
    readOnly: Boolean = false,
    isLoading: Boolean = false,
    showDriftWarning: Boolean = false,
    message: String? = null,
    error: String? = null,
    onSave: () -> Unit,
    onRestore: () -> Unit,
    onCheckpoint: () -> Unit,
    onDismissDriftWarning: () -> Unit = {},
    onMessageShown: () -> Unit = {},
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val snackbarHostState = remember { SnackbarHostState() }
    // A message is a one-shot outcome, not a piece of the layout: show it, then tell the host to clear it so
    // the same "Saved." cannot re-appear on the next recomposition.
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }
    // No document means no actions: the bar of Save/Restore/Checkpoint only makes sense once there is a file
    // loaded and no blocking error, so it is withheld in the loading and error states rather than shown dead.
    val hasDocument = !isLoading && error == null
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (hasDocument) {
                Surface(color = scheme.surface) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onRestore) { Text("Restore") }
                        TextButton(onClick = onCheckpoint) { Text("Checkpoint") }
                        Spacer(modifier = Modifier.weight(1f))
                        Button(onClick = onSave, enabled = isDirty && !readOnly) { Text("Save") }
                    }
                }
            }
        },
    ) { innerPadding ->
        when {
            isLoading -> Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            error != null -> Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Spacing.xl),
                )
            }

            else -> Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
            ) {
                if (showDriftWarning) {
                    DriftWarningBanner(onDismiss = onDismissDriftWarning)
                }
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = scheme.surfaceVariant,
                    border = BorderStroke(1.dp, scheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(Spacing.lg),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        readOnly = readOnly,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = scheme.onSurface,
                        ),
                        cursorBrush = SolidColor(scheme.primary),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(Spacing.md),
                    )
                }
            }
        }
    }
}

/**
 * The update-drift banner (§A9): a full-width strip above the editor saying the game was updated after this
 * file's original was backed up, so a saved config may no longer fit. It carries a [onDismiss] action rather
 * than a timer because it is a caution the user should choose to clear, not one that vanishes on its own; it
 * never disables the field, because a stale config is still the user's to read and to edit.
 */
@Composable
private fun DriftWarningBanner(onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "This game was updated after this file was backed up, so a saved config may not fit the " +
                    "new version.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
