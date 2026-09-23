package com.gamecore.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.Meter
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.startIntentSafely
import com.gamecore.ui.theme.Spacing

/**
 * The first-run wizard (spec §A2), as one destination with seven states.
 *
 * The layout is a fixed header, a scrolling middle and a fixed button bar, rather than the app's usual
 * single [LazyColumn]. That is the one place this screen departs from the house pattern and it is the
 * point of the screen: §A2 asks for a progress indicator and a working Back on every step, and both stop
 * being reachable the moment they scroll off. A step whose content is three lines and a step whose content
 * is eight permission rows then still present the same controls in the same place.
 *
 * Window insets are not applied here. `GameCoreNav` already wraps the whole graph in `systemBarsPadding()`
 * and `displayCutoutPadding()`, so a second application would double the gap — and because the wizard is
 * not a [com.gamecore.ui.Destination.Top] route, the floating navigation bar is absent and this screen's
 * own bar is the only thing at the bottom of the window.
 *
 * @param onFinished called once the wizard is over — completed, or skipped outright. The caller navigates;
 *   this screen never touches the back stack itself, so the same composable works from a fresh install and
 *   from the "Run setup again" entry on Setup health.
 */
@Composable
fun SetupWizardScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SetupWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmExit by remember { mutableStateOf(false) }

    // Steps 3 and 4 send the user to a system page, and Android reports nothing when they come back.
    // Re-asking on resume is the only way the wizard can show a grant that happened outside it (§A2).
    OnResume { viewModel.onResume() }

    // Navigation happens on the state, not in the click handler, because finishing is asynchronous — the
    // profile has to be written first — and a navigate fired beside the call would leave before the save.
    LaunchedEffect(state.isFinished) {
        if (state.isFinished) onFinished()
    }

    // System back is the same movement as the Back button, so the two cannot disagree — except while the
    // app picker is up, where it closes the picker first, because that is the thing the user last opened.
    // On the first step with no picker there is nowhere to go back to, and the handler disables itself so
    // the gesture leaves the wizard exactly as a back gesture on any other screen would.
    BackHandler(enabled = state.isPickerOpen || state.canGoBack) {
        if (state.isPickerOpen) viewModel.closePicker() else viewModel.back()
    }

    val actions = remember(viewModel, context) {
        WizardActions(
            onFeature = viewModel::setFeature,
            onOpenSettings = { row ->
                val intent = viewModel.settingsIntentFor(row)
                if (intent == null || !context.startIntentSafely(intent)) viewModel.onIntentFailed()
            },
            onOpenShizuku = {
                val intent = viewModel.shizukuLaunchIntent()
                if (intent == null || !context.startIntentSafely(intent)) viewModel.onIntentFailed()
            },
            onRequestShizuku = viewModel::requestShizukuPermission,
            onRecheck = viewModel::recheck,
            onOpenPicker = viewModel::openPicker,
            onClosePicker = viewModel::closePicker,
            onChooseGame = viewModel::choose,
            onClearGame = viewModel::clearGame,
            onChoosePreset = viewModel::choosePreset,
            onSmartThermal = viewModel::setSmartThermal,
            onSmartNetwork = viewModel::setSmartNetwork,
            onSmartFullPerformance = viewModel::setSmartFullPerformance,
        )
    }

    // The picker replaces the wizard rather than covering it — see [WizardAppPicker] for why. Returning
    // early rather than wrapping the wizard in an `if` keeps the step content out of composition entirely
    // while several hundred app rows are on screen.
    if (state.isPickerOpen) {
        WizardAppPicker(
            state = state,
            onChoose = viewModel::choose,
            onClose = viewModel::closePicker,
            modifier = modifier,
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        WizardProgressHeader(
            progress = state.progress,
            title = stepTitle(state.step),
            onExit = { confirmExit = true },
        )

        state.message?.let { message ->
            NoteBanner(
                text = message,
                tone = Tone.Accent,
                icon = Icons.Filled.Info,
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = Spacing.sm),
                action = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                top = Spacing.sm,
                bottom = Spacing.lg,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            wizardStepContent(state = state, actions = actions)
        }

        WizardButtonBar(
            state = state,
            onBack = viewModel::back,
            onSkip = viewModel::skip,
            onNext = viewModel::next,
        )
    }

    if (confirmExit) {
        ConfirmDialog(
            title = "Leave setup?",
            message = EXIT_MESSAGE,
            confirmLabel = "Leave",
            onConfirm = {
                confirmExit = false
                // Finishing rather than abandoning: the user has answered the question the wizard asks, so
                // it should not take over the next launch. Anything still missing is reported by Setup
                // health, which is where the dialog says to look.
                viewModel.finish()
            },
            onDismiss = { confirmExit = false },
            isDestructive = false,
        )
    }
}

/**
 * The progress indicator and the step's title (§A2's "progress indicator").
 *
 * Two representations of the same fact, which is §A5's "never communicate state by colour alone" applied
 * to progress: the bar is the glanceable one and the "Step 3 of 7" line is the one a screen reader and a
 * user at 200% text actually get. The bar is then excluded from the accessibility tree with
 * [clearAndSetSemantics] carrying that same sentence, so the two are announced once rather than twice.
 *
 * The title lives here rather than inside each step so every step is guaranteed to have one, and so the
 * heading stays on screen while the step's content scrolls under it.
 */
@Composable
private fun WizardProgressHeader(
    progress: WizardProgress,
    title: String,
    onExit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ScreenPadding, end = Spacing.sm, top = Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = progress.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onExit, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Skip setup")
            }
        }
        Meter(
            fraction = progress.fraction,
            tone = Tone.Accent,
            modifier = Modifier
                .padding(end = Spacing.sm, top = Spacing.xs)
                .clearAndSetSemantics { contentDescription = progress.label },
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .padding(top = Spacing.md, end = Spacing.sm)
                .semantics { heading() },
        )
    }
}

/**
 * Back, Skip and Next, pinned under the content.
 *
 * Every button is at least 48dp tall (§A5) and none of them has a fixed width, so the bar reflows rather
 * than clipping when the text scales to 200% or the window is narrow. Back is a text button and Next is
 * filled because the forward action is the one the user is being invited to take — the difference is
 * weight and position, not colour, so it survives a monochrome or high-contrast rendering.
 *
 * Skip is hidden on the last step, where there is nothing left to skip past and "Skip" beside "Finish"
 * would read as a second way out with an unclear difference.
 */
@Composable
private fun WizardButtonBar(
    state: SetupWizardUiState,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.canGoBack) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Back")
            }
        }
        if (canSkip(state.step)) {
            TextButton(
                onClick = onSkip,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) {
                Text("Skip this step")
            }
        } else {
            // Keeps Next anchored to the right on the Done step, where there is no Skip to push it there.
            Text(text = "", modifier = Modifier.weight(1f))
        }
        Button(
            onClick = onNext,
            enabled = state.canAdvanceNow && !state.isSaving,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(forwardLabel(state.step))
        }
    }
}

private const val EXIT_MESSAGE: String =
    "Nothing you have already granted is undone. You can pick this up again any time from " +
        "Settings › Setup health, which also shows what is still missing."
