package com.gamecore.ui.display

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.theme.GameCoreTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * "Keep this display size?" as a full screen.
 *
 * An Activity rather than a dialog, a toast or a bottom sheet, for one reason: the display override it
 * is asking about may have made the app's own windows unreadable, and a full-screen takeover with a
 * fifteen-second countdown and one unmistakable pair of buttons is the surface that survives that. A
 * dialog on top of a screen nobody can see does not.
 *
 * Started from wherever the override was applied — the Settings render-resolution control, a game
 * profile applying while the user is in a game, the overlay panel's aspect tile — and it finishes itself
 * on every path. When it cannot be started at all, [AndroidDisplayOverrideConfirmer] falls back to the
 * notification carrying the same two answers, so the question is always asked somewhere.
 *
 * Configuration changes are handled two ways, on purpose:
 *
 *  - The manifest declares `configChanges`, so a rotation does not recreate this activity at all.
 *  - The countdown is *derived* from the row's own timestamp on every tick rather than counted, so even
 *    a process death and a fresh creation continues from the right number of seconds. The row, not the
 *    screen, is the source of truth about how long is left.
 */
@AndroidEntryPoint
class DisplaySizeConfirmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GameCoreTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    DisplaySizeConfirmScreen(onDone = ::finish)
                }
            }
        }
    }

    companion object {

        /**
         * The intent that asks the question for [token].
         *
         * The token is carried for traceability, and deliberately not *trusted*: the screen reads the
         * store for the row that is actually waiting and answers that one. An intent extra is data from
         * outside the process, and a confirmation that believed it would be a confirmation that could be
         * pointed at a row that no longer exists. `NEW_TASK` because the caller is usually a service or a
         * controller with no activity of its own.
         *
         * `excludeFromRecents` and the empty `taskAffinity` in the manifest are what keep it out of the
         * recents list and out of GameCore's own task — a confirmation nobody answered should not be
         * something the user later finds and reopens.
         */
        fun intentFor(context: Context, token: String): Intent =
            Intent(context, DisplaySizeConfirmActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** The row this screen was asked about. Not read back: the store is the source of truth. */
        private const val EXTRA_TOKEN = "com.gamecore.extra.DISPLAY_OVERRIDE_TOKEN"
    }
}

/**
 * The screen itself.
 *
 * Every sentence here describes what the device did or is about to do, never what the user should have
 * done — and the one claim it never makes is that this widens the field of view. `wm size` changes the
 * logical display size the system reports; a game handed a different shape draws the same view it always
 * drew, spread across more of the panel.
 */
@Composable
private fun DisplaySizeConfirmScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DisplaySizeConfirmViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending = state.pending

    // Read the row, and let the ViewModel start its own counter from what it found. Runs once, and again
    // on a recreation, because both need the same thing: the row, not the state composition started with.
    LaunchedEffect(Unit) { viewModel.start() }

    // Answered, or never asked: close. Done by an effect rather than inside the click handler so it also
    // fires when the row turns out to have been answered somewhere else.
    LaunchedEffect(state.done, state.outcome) {
        if (state.done && state.outcome == null) onDone()
    }

    if (pending == null && state.outcome == null) return

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Keep this display size?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 24.dp),
        )

        state.confirmSentence?.let { sentence ->
            PlainCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = sentence, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "This changes the logical display size GameCore hands to the system. It " +
                            "is not a wider field of view: a game draws the same view it always drew, " +
                            "spread across the whole panel.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }

        if (pending != null && state.remainingSeconds > 0L) {
            Text(
                text = "Reverting in ${state.remainingSeconds} s unless you say keep.",
                style = MaterialTheme.typography.titleLarge,
                color = Tone.Warning.colour(),
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (pending != null && state.expired) {
            Text(
                text = "The countdown ran out, so the size recorded before this change is being put back.",
                style = MaterialTheme.typography.titleMedium,
                color = Tone.Warning.colour(),
            )
        }

        state.outcome?.let { outcome ->
            NoteBanner(text = outcome, tone = Tone.Accent, icon = Icons.Filled.Info)
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }

        if (state.isBusy) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Putting the previous size back…", style = MaterialTheme.typography.bodyLarge)
            }
        } else if (pending != null && state.outcome == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = viewModel::keep, modifier = Modifier.weight(1f)) { Text("Keep it") }
                OutlinedButton(onClick = viewModel::revert, modifier = Modifier.weight(1f)) {
                    Text("Revert now")
                }
            }
            RowDivider(modifier = Modifier.padding(vertical = 4.dp))
            state.revertSentence?.let { sentence ->
                Text(
                    text = sentence,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f, fill = true))

        if (!state.shellAvailable && pending != null) {
            NoteBanner(
                text = "GameCore cannot reach the elevated shell, so it could not put the previous size " +
                    "back even if the countdown runs out. Connect Shizuku, then open Recovery.",
                tone = Tone.Warning,
                icon = Icons.Filled.Info,
            )
        }
    }
}
