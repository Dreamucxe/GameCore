package com.gamecore.ui.setup

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.startIntentSafely

/**
 * Setup health (spec §A3): one row per setup item, with a way to fix each one.
 *
 * Reached from Settings and from the Home card, and it is the same composable both times — the card is a
 * shortcut to this screen rather than a second, smaller version of it, so the two can never report different
 * things about the same device.
 *
 * The house [LazyColumn] pattern rather than the wizard's fixed frame, because this is a report: there is no
 * progress to keep on screen and no forward button to keep reachable, and the rows scroll under a header the
 * user can scroll back to.
 *
 * @param onAddGame opens the new-profile editor. A lambda rather than a [Destination], because the editor's
 *   route carries a package name and the root is what knows how to build it.
 */
@Composable
fun SetupHealthScreen(
    onBack: () -> Unit,
    onNavigate: (Destination) -> Unit,
    onAddGame: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SetupHealthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The whole point of this screen is to be right about grants, and a grant made in the Settings app
    // arrives with no callback. §A3 asks for the re-check explicitly.
    OnResume { viewModel.onResume() }

    val padded = Modifier.padding(horizontal = ScreenPadding)
    val launch: (Intent?) -> Unit = { intent ->
        if (intent == null || !context.startIntentSafely(intent)) viewModel.onIntentFailed()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Setup health",
                // The count is the subtitle rather than a card of its own: it is a summary of the list
                // directly below it, and a user who reads the list does not need it twice.
                subtitle = state.summary.label,
                onBack = onBack,
                action = { TextButton(onClick = viewModel::recheck) { Text("Re-check") } },
            )
        }

        state.message?.let { message ->
            item {
                NoteBanner(
                    text = message,
                    tone = Tone.Accent,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                    action = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
                )
            }
        }

        items(state.rows, key = { it.item.name }) { row ->
            HealthRowCard(
                row = row,
                onFix = {
                    // The three internal fixes are the rows with no permission behind them. Routing them
                    // through the same button keeps one affordance per row, which is what §A3 asks for —
                    // the difference is only where the user lands.
                    when (row.item) {
                        SetupHealthItem.PING_HOST -> onNavigate(Destination.Settings)
                        SetupHealthItem.FIRST_PROFILE -> onAddGame()
                        else -> launch(viewModel.fixIntentFor(row))
                    }
                },
                modifier = padded,
            )
        }

        item {
            SectionCard(title = "Start over", modifier = padded) {
                Text(
                    text = "Runs the setup wizard again from the beginning. Nothing you have already " +
                        "granted or saved is undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ActionRow {
                    OutlinedButton(
                        onClick = {
                            viewModel.restartWizard()
                            onNavigate(Destination.SetupWizard)
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Run setup again")
                    }
                }
            }
        }
    }
}

/**
 * One row: title, state, the sentence that explains it, and a Fix button when there is something to fix.
 *
 * The state appears three ways — the chip's text, the chip's icon shape, and the tone — so it survives a
 * greyscale render, a colour-blind user and a screen reader (§A5). The text is the authoritative one; the
 * other two are there to make it glanceable.
 */
@Composable
private fun HealthRowCard(
    row: SetupHealthRow,
    onFix: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = row.title,
        modifier = modifier,
        action = {
            StatusChip(
                text = row.state.label,
                tone = toneFor(row),
                icon = iconFor(row.state),
            )
        },
    ) {
        Text(
            text = row.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (row.canFix) {
            ActionRow {
                OutlinedButton(onClick = onFix, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(row.item.fixLabel)
                }
            }
        }
    }
}

/**
 * The tone for one row.
 *
 * [HealthState.Skipped] is muted rather than warned, which is §A3's "skipped optional items never nag" made
 * visible: the row is present, states what it is, and draws no attention.
 *
 * The first-profile row is the deliberate exception. It is [HealthState.NotSetUp] on every fresh install and
 * a warning colour there would mean the app greets a new user by telling them something is wrong, when in
 * fact nothing is — they simply have not added a game yet. Accent reads as an invitation.
 */
private fun toneFor(row: SetupHealthRow): Tone = when (row.state) {
    HealthState.Ready -> Tone.Good
    HealthState.NotSetUp ->
        if (row.item == SetupHealthItem.FIRST_PROFILE) Tone.Accent else Tone.Warning
    HealthState.Skipped -> Tone.Muted
    is HealthState.Unavailable -> Tone.Muted
}

/** A different shape per state, so the chip is readable without colour. */
private fun iconFor(state: HealthState): ImageVector = when (state) {
    HealthState.Ready -> Icons.Filled.CheckCircle
    HealthState.NotSetUp -> Icons.Filled.Warning
    HealthState.Skipped -> Icons.Filled.RadioButtonUnchecked
    is HealthState.Unavailable -> Icons.Filled.Block
}
