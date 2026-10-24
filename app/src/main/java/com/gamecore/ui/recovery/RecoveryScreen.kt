package com.gamecore.ui.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour

/**
 * The recovery console.
 *
 * Two jobs, one screen:
 *
 *  - **Confirm or revert an override.** When a display override has been written and not confirmed, this
 *    screen holds the countdown and the two answers. Silence is the third answer and it reverts: the
 *    failure this exists for is a user who cannot reach a button, and "leave it" would protect nothing.
 *  - **Fix a display from cold.** With nothing pending it is still where you come when the screen is
 *    wrong: put the display back to its own size, stop overlays and plugins without having to work out
 *    which one is misbehaving, and read what actually happened rather than a success that may not have
 *    landed.
 *
 * Everything here is phrased as what the device did, never as what the user should have done. `wm size`
 * changes the logical display size the system reports; it is not a wider field of view, and nothing on
 * this screen implies otherwise.
 */
@Composable
fun RecoveryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecoveryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The ViewModel reads the row and starts its own counter from what it actually found, so this is a
    // single re-read on entry rather than a loop keyed on a state that has not been read yet.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Recovery",
            subtitle = "Display overrides and safe mode",
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding)
                .padding(bottom = 24.dp),
        ) {
            state.outcome?.let { outcome ->
                NoteBanner(
                    text = outcome,
                    tone = Tone.Accent,
                    icon = Icons.Filled.Info,
                    modifier = Modifier.padding(vertical = 8.dp),
                    action = { TextButton(onClick = viewModel::dismissOutcome) { Text("Dismiss") } },
                )
            }

            if (state.pending != null) {
                ConfirmationCard(state = state, viewModel = viewModel)
            } else {
                NothingPendingCard(viewModel = viewModel)
            }

            // What the device can actually do, said plainly and first. A recovery screen whose buttons
            // sit behind an unavailable feature and says so only after they are tapped is not a recovery
            // screen.
            PlainCard(modifier = Modifier.padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Terminal,
                            contentDescription = null,
                            tint = if (state.shellAvailable) Tone.Good.colour() else Tone.Warning.colour(),
                        )
                        Text(
                            text = if (state.shellAvailable) {
                                "Elevated shell available"
                            } else {
                                "Elevated shell not available"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = state.shellReason
                            ?: "Shizuku is connected, so GameCore can change the display size, read it " +
                                "back, and put it back afterwards.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            PlainCard(modifier = Modifier.padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "What this screen is for",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "When a game starts, GameCore can stretch the display to the game's " +
                            "shape so the picture fills the panel. It does that by changing the display " +
                            "size the system reports to apps, and that change survives a reboot — so if " +
                            "the game's session ends badly, or the phone was restarted, or the change is " +
                            "simply a shape you do not want, you can put it back from here without " +
                            "knowing what the last game asked for.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        text = "It is not a wider field of view. A game handed a different shape draws " +
                            "the same view it always drew; the display just spreads it over the whole " +
                            "panel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            PlainCard(modifier = Modifier.padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Restore the display to its own size",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Clears the display size GameCore set and puts the panel's own " +
                            "resolution back, whatever set it. It reads the change back before saying " +
                            "it worked, and it leaves nothing pending.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    OutlinedButton(
                        onClick = viewModel::restoreDisplayToNative,
                        enabled = !state.isBusy,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        if (state.isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Restore display")
                        }
                    }
                }
            }

            RowDivider(modifier = Modifier.padding(vertical = 4.dp))

            PlainCard(modifier = Modifier.padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "If the phone itself is misbehaving",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Two switches that stop things without deleting anything. Both are " +
                            "undoable from their own screens.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = "Disable plugins",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.Extension, contentDescription = null)
                        Text(
                            text = if (state.pluginsDisabled) "Already off" else "On",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "One switch, checked before every plugin call, so nothing a plugin " +
                            "declares — a theme, a widget, an automation rule — runs. Your plugins and " +
                            "their settings are kept. Turn them back on from the plugins screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Button(
                        onClick = viewModel::disablePlugins,
                        enabled = !state.pluginsDisabled,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("Disable plugins")
                    }
                    Text(
                        text = "Turn off overlays",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.Layers, contentDescription = null)
                        Text(
                            text = if (state.overlaysOff) "Nothing shown" else "Something on screen",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "Hides the floating button, the pill, the crosshair and the HUD — the " +
                            "same thing the control panel's own stop button does. What you have " +
                            "switched on is untouched, so they come back when you turn them on again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Button(
                        onClick = viewModel::turnOffOverlays,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("Turn off overlays")
                    }
                }
            }

            if (!state.shellAvailable) {
                NoteBanner(
                    text = "GameCore still has the record of what to put back, so nothing is lost. " +
                        "Connect Shizuku and this screen can change the display again.",
                    tone = Tone.Warning,
                    icon = Icons.Filled.Build,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }

    if (state.isBusy && state.pending == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

/**
 * The countdown and the two answers.
 *
 * The countdown is the whole point and it is drawn as a number of seconds: a progress bar that silently
 * fills does not tell a user how long they have, and this screen is asking a question with a clock on it.
 */
@Composable
private fun ConfirmationCard(state: RecoveryUiState, viewModel: RecoveryViewModel) {
    val pending = state.pending ?: return

    PlainCard(modifier = Modifier.padding(vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Keep this display size?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.confirmSentence
                    ?: ("GameCore set this display's logical size to ${pending.requestedLabel} when a " +
                        "game started. A display size change survives a reboot, and one small enough can " +
                        "be hard to undo from the screen in front of you."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (state.isConfirming && !state.expired) {
                Text(
                    text = "Reverting in ${state.remainingSeconds} s unless you say keep.",
                    style = MaterialTheme.typography.titleMedium,
                    color = Tone.Warning.colour(),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else if (state.expired) {
                Text(
                    text = "The countdown ran out, so the size recorded before the change is being put " +
                        "back.",
                    style = MaterialTheme.typography.titleMedium,
                    color = Tone.Warning.colour(),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            if (state.isBusy) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
                    Text("Putting the previous size back…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = viewModel::confirm, modifier = Modifier.weight(1f)) {
                        Text("Keep it")
                    }
                    OutlinedButton(onClick = viewModel::revert, modifier = Modifier.weight(1f)) {
                        Text("Revert")
                    }
                }
            }

            RowDivider(modifier = Modifier.padding(vertical = 12.dp))
            Text(
                text = revertSentence(state.revertSentence),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The sentence beside Revert, taken from the guard rather than rebuilt here.
 *
 * Null previous value is the ordinary case — the display had no override — so the guard's sentence says
 * that putting it back clears the override, rather than pretending a size was recorded.
 */
@Composable
private fun revertSentence(sentence: String?): String = sentence
    ?: "Putting this back restores the size recorded before GameCore changed the display."

/** Nothing waiting: still a recovery screen, just without a question on it. */
@Composable
private fun NothingPendingCard(viewModel: RecoveryViewModel) {
    Box(modifier = Modifier.padding(vertical = 16.dp)) {
        EmptyState(
            icon = Icons.Filled.PhoneAndroid,
            title = "Nothing to recover",
            message = "GameCore has no display change waiting for confirmation. When a game starts and " +
                "GameCore stretches the display, it will ask — and if you never answer, it puts the " +
                "display back by itself.",
            actionLabel = "Check again",
            onAction = viewModel::refresh,
        )
    }
}
