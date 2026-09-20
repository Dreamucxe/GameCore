package com.gamecore.ui.permissions

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.permissions.GamePermission
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.components.startIntentSafely

/**
 * §21's permissions centre: every access GameCore can ask for, what it is for, and what breaks without it.
 *
 * One card per access rather than a list of switches, because the interesting part of a permission is not
 * its state but its consequence — "the HUD cannot be drawn" is the sentence that lets someone decide, and
 * `whatBreaks` is written for exactly that. Nothing here is granted by GameCore: each button opens the page
 * or raises the dialog that Android owns, and the answer is read back on the next resume.
 */
@Composable
fun PermissionsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val padded = Modifier.padding(horizontal = ScreenPadding)

    // §24B: POST_NOTIFICATIONS is the one access here with a system dialog, and a dialog can only be
    // raised from a launcher registered in a composable — a ViewModel has no Activity to raise it from.
    val notifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted -> viewModel.onNotificationResult(isGranted) }

    // Everything else is granted in another app's UI, which never calls back. This is the only signal.
    OnResume { viewModel.refresh() }

    val launch: (Intent?) -> Unit = { intent ->
        if (intent == null || !context.startIntentSafely(intent)) viewModel.onIntentFailed()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(title = "Permissions", subtitle = state.summary, onBack = onBack)
        }

        state.message?.let { message ->
            item {
                NoteBanner(
                    text = message,
                    tone = Tone.Warning,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                    action = {
                        TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
                    },
                )
            }
        }

        item {
            NoteBanner(
                text = INTRODUCTION,
                tone = Tone.Muted,
                icon = Icons.Filled.Shield,
                modifier = padded,
            )
        }

        if (state.needsBatteryExemption) {
            item {
                NoteBanner(
                    text = DOZE_EXPLANATION,
                    tone = Tone.Warning,
                    icon = Icons.Filled.BatterySaver,
                    modifier = padded,
                    action = {
                        TextButton(onClick = { launch(viewModel.batteryExemptionIntent()) }) {
                            Text("Allow")
                        }
                    },
                )
            }
        }

        items(state.rows, key = { it.permission.name }) { row ->
            PermissionCard(
                row = row,
                onOpen = { launch(viewModel.settingsIntentFor(row.permission)) },
                onRequestDialog = {
                    notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                },
                dialogRefused = state.notificationDialogRefused,
                modifier = padded,
            )
        }

        item {
            NoteBanner(
                text = CLOSING_NOTE,
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
                modifier = padded,
            )
        }
    }
}

/**
 * One access, with the two sentences that matter and at most one button.
 *
 * [PermissionRow.isActionable] decides whether there is a button at all, so a card for something this
 * version of Android has no page for is a card with no button rather than one that opens nothing. The
 * notification row is the exception in *how* it asks: a dialog until Android stops showing it, the Settings
 * page after that, which is what [dialogRefused] switches over.
 */
@Composable
private fun PermissionCard(
    row: PermissionRow,
    onOpen: () -> Unit,
    onRequestDialog: () -> Unit,
    dialogRefused: Boolean,
    modifier: Modifier = Modifier,
) {
    val permission = row.permission
    SectionCard(
        title = permission.title,
        modifier = modifier,
        subtitle = permission.kind.label,
        icon = iconFor(permission),
        action = {
            when {
                row.unavailableReason != null -> StatusChip("Not applicable", Tone.Muted)
                row.isGranted -> StatusChip("Granted", Tone.Good, Icons.Filled.Check)
                else -> StatusChip("Not granted", Tone.Warning)
            }
        },
    ) {
        Text(
            text = permission.why,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowDivider()
        Text(
            text = row.unavailableReason ?: permission.whatBreaks,
            style = MaterialTheme.typography.bodySmall,
            color = if (row.isGranted || row.unavailableReason != null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                Tone.Warning.colour()
            },
        )
        if (row.isActionable) {
            ActionRow {
                if (row.canRequestDialog && !dialogRefused) {
                    Button(onClick = onRequestDialog) { Text("Allow") }
                } else {
                    Button(onClick = onOpen) { Text("Open the setting") }
                }
            }
        }
    }
}

private fun iconFor(permission: GamePermission): ImageVector = when (permission) {
    GamePermission.OVERLAY -> Icons.Filled.Layers
    GamePermission.USAGE_ACCESS -> Icons.Filled.Timeline
    GamePermission.WRITE_SETTINGS -> Icons.Filled.Tune
    GamePermission.NOTIFICATION_POLICY -> Icons.Filled.DoNotDisturbOn
    GamePermission.POST_NOTIFICATIONS -> Icons.Filled.Notifications
    GamePermission.BATTERY_OPTIMISATION_EXEMPTION -> Icons.Filled.BatterySaver
    GamePermission.PACKAGE_VISIBILITY -> Icons.Filled.Visibility
    // A note rather than a bell, because the row is about what the access is for and not about
    // the switch's name. A second bell next to POST_NOTIFICATIONS would read as two entries for
    // one thing, which is exactly the confusion the catalogue wording is trying to avoid.
    GamePermission.NOTIFICATION_LISTENER -> Icons.Filled.MusicNote
}

private const val INTRODUCTION =
    "GameCore asks for nothing it does not use, and every one of these is granted by Android rather than " +
        "by this screen — the buttons open the page that owns the decision. Refusing any of them leaves the " +
        "rest of the app working; the card says what stops working."

private const val DOZE_EXPLANATION =
    "You have switched on something that keeps running while the screen is off. Android's battery " +
        "optimisation stops background work after a few minutes, which ends a recording mid-session and " +
        "leaves the graph short rather than telling you it was cut off. Exempting GameCore prevents that. " +
        "It is the one permission here that costs battery by itself, and nothing else on this screen needs it."

private const val CLOSING_NOTE =
    "GameCore does not root the device and does not need it to. Two things on this list have no permission " +
        "at all: reading per-app CPU on newer Android and setting a refresh rate the display driver refuses. " +
        "Those need the elevated shell on the Shizuku screen, and where even that fails the screen for it " +
        "says so rather than reporting a change that did not happen."
