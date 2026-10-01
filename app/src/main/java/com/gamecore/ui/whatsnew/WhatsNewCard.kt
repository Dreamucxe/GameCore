package com.gamecore.ui.whatsnew

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gamecore.core.whatsnew.ReleaseNote

/**
 * The one-time surface shown after the app is updated to a version that added features (spec surface A).
 *
 * A modal [AlertDialog] rather than a Home card, so it reads as "here is what changed" on the launch that
 * follows an update and then is gone — the same dialog shape [com.gamecore.ui.components.ConfirmDialog]
 * uses, so it looks like the rest of the app. It is fully stateless: it draws [entries] — the releases
 * the caller worked out are unseen — and reports the two taps back out. Whether it appears at all, and
 * the persisted "seen" write that stops it returning, belong to the caller, not here.
 *
 * [onDismiss] is the acknowledgement ("Got it", and a back-press or scrim tap), [onViewAll] opens the
 * full What's New screen. Typically [entries] is the single release just installed; when an existing
 * user upgrades across several at once it holds all of them, newest first, so each is labelled.
 */
@Composable
fun WhatsNewCard(
    entries: List<ReleaseNote>,
    onDismiss: () -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Defensive: the caller only shows this when there is something unseen, but a dialog with nothing in
    // it is never worth drawing.
    if (entries.isEmpty()) return

    val labelReleases = entries.size > 1

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(text = DIALOG_TITLE, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = if (labelReleases) INTRO_MANY else INTRO_ONE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entries.forEach { release ->
                    if (labelReleases) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Version ${release.versionName}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    release.features.forEach { feature ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = feature.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = feature.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(BUTTON_DISMISS) } },
        dismissButton = { TextButton(onClick = onViewAll) { Text(BUTTON_VIEW_ALL) } },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    )
}

private const val DIALOG_TITLE = "What's new"
private const val INTRO_ONE = "GameCore has been updated. Here's what's new:"
private const val INTRO_MANY = "Here's everything added since you were last here:"
private const val BUTTON_DISMISS = "Got it"
private const val BUTTON_VIEW_ALL = "See all"
