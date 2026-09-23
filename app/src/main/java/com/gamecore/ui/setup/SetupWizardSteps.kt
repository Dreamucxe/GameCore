package com.gamecore.ui.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamecore.core.common.Formatters
import com.gamecore.core.model.ShizukuState
import com.gamecore.domain.setup.PresetLine
import com.gamecore.domain.setup.SetupPreset
import com.gamecore.domain.setup.WizardStep
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ClickableCard
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.games.AppOption
import com.gamecore.ui.theme.Spacing

/**
 * The wizard's seven steps (spec §A2), as content for the screen's one list.
 *
 * Split out of [SetupWizardScreen] because that file is the frame — progress, buttons, back — and this one
 * is everything inside it. They are the same destination and the same package; the division is purely so
 * that a change to "what step 3 says" never touches the file that owns "how you get from step 3 to step 4".
 *
 * Every step is [LazyListScope] content rather than a composable of its own, so the whole wizard scrolls as
 * one list: the permission step can be eight cards tall on a device where nothing is granted, and a nested
 * scrolling container inside a fixed-height slot is the thing that makes that unusable at 200% text.
 */

/**
 * Everything a step can ask the wizard to do.
 *
 * Passed as one object rather than fourteen parameters, and held in a `remember` by the screen so the steps
 * are not handed a fresh set of lambdas on every recomposition. The steps get no [SetupWizardViewModel] and
 * no `Context` — a step that could reach either would be a step that could start an Activity without the
 * screen's failure handling, and §A2's Grant buttons all have to report a page that would not open.
 */
data class WizardActions(
    val onFeature: (WizardFeature, Boolean) -> Unit,
    val onOpenSettings: (PermissionRow) -> Unit,
    val onOpenShizuku: () -> Unit,
    val onRequestShizuku: () -> Unit,
    val onRecheck: () -> Unit,
    val onOpenPicker: () -> Unit,
    val onClosePicker: () -> Unit,
    val onChooseGame: (AppOption) -> Unit,
    val onClearGame: () -> Unit,
    val onChoosePreset: (SetupPreset) -> Unit,
    val onSmartThermal: (Boolean) -> Unit,
    val onSmartNetwork: (Boolean) -> Unit,
    val onSmartFullPerformance: (Boolean) -> Unit,
)

/** Dispatches to the current step. The order of the `when` is the order of [WizardStep], not of the flow. */
fun LazyListScope.wizardStepContent(state: SetupWizardUiState, actions: WizardActions) {
    item(key = "lead") {
        Text(
            text = stepLead(state.step),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
    when (state.step) {
        WizardStep.WELCOME -> welcomeStep()
        WizardStep.FEATURES -> featuresStep(state, actions)
        WizardStep.PERMISSIONS -> permissionsStep(state, actions)
        WizardStep.SHIZUKU -> shizukuStep(state, actions)
        WizardStep.FIRST_GAME -> firstGameStep(state, actions)
        WizardStep.SMART_FEATURES -> smartFeaturesStep(state, actions)
        WizardStep.DONE -> doneStep(state)
    }
}

// ---------------------------------------------------------------------------------- 1 · Welcome

/**
 * Step 1 (§A2): what the app is, and an accurate privacy note.
 *
 * "Accurate" is the whole requirement and it is why this is two cards rather than one reassuring sentence.
 * The first says what stays here, which is everything the app records. The second says the one thing that
 * connects out — the optional latency probe — names its default host, and says where to switch it off.
 * Claiming nothing leaves the device would be simpler and would be false the moment the user turns the
 * network check on, and a privacy note the user can catch out is worse than none.
 */
private fun LazyListScope.welcomeStep() {
    item(key = "welcome-stays") {
        SectionCard(
            title = "Everything stays on this device",
            icon = Icons.Filled.Lock,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        ) {
            Text(
                text = PRIVACY_NOTE_STAYS_HERE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item(key = "welcome-out") {
        SectionCard(
            title = "The one thing that connects out",
            icon = Icons.Filled.Shield,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        ) {
            Text(
                text = PRIVACY_NOTE_CONNECTS_OUT,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --------------------------------------------------------------------------------- 2 · Features

/**
 * Step 2 (§A2): the eight things GameCore can do, all off.
 *
 * Switches rather than a multi-select chip row, because each one needs a sentence beside it and a chip has
 * nowhere to put one. Nothing here grants anything — a tick is a statement of interest that decides which
 * later steps exist, which is why un-ticking on this step can shorten the flow.
 */
private fun LazyListScope.featuresStep(state: SetupWizardUiState, actions: WizardActions) {
    items(WizardFeature.entries.toList(), key = { it.name }) { feature ->
        SwitchRow(
            title = feature.title,
            checked = feature.isPicked(state.choices),
            onCheckedChange = { picked -> actions.onFeature(feature, picked) },
            description = feature.description,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
}

// ------------------------------------------------------------------------------ 3 · Permissions

/**
 * Step 3 (§A2): one card per permission a ticked feature needs.
 *
 * Nothing on this step is worded here. The plain "why" and the "what stops working" are
 * [com.gamecore.core.permissions.GamePermission.why] and `.whatBreaks` verbatim — the same strings the
 * Permissions screen shows — because a user who reads one explanation in the wizard and a different one
 * in Settings has to work out which is current. Re-wording is how the two drift.
 *
 * The state chip is never the only signal: the chip's own text says "Granted" or "Not granted", and the
 * icon beside it differs in shape as well as colour (§A5).
 */
private fun LazyListScope.permissionsStep(state: SetupWizardUiState, actions: WizardActions) {
    if (state.permissionRows.isEmpty()) {
        item(key = "perm-none") {
            NoteBanner(
                text = "Nothing to grant. None of the features you picked needs a permission, so this " +
                    "step has nothing to ask for.",
                tone = Tone.Good,
                icon = Icons.Filled.CheckCircle,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        }
        return
    }
    items(state.permissionRows, key = { it.need.name }) { row ->
        PermissionCard(
            row = row,
            onGrant = { actions.onOpenSettings(row) },
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
    item(key = "perm-recheck") {
        ActionRow {
            TextButton(onClick = actions.onRecheck, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Check again")
            }
        }
    }
    // §A2 puts this on the permission step and nowhere else. It is the answer to a specific, visible
    // symptom — a toggle Android will not let the user move — and a user who is not looking at one would
    // read it as an instruction they are supposed to follow.
    item(key = "perm-restricted") {
        RestrictedSettingsExpander(modifier = Modifier.padding(horizontal = ScreenPadding))
    }
}

@Composable
private fun PermissionCard(
    row: PermissionRow,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = row.title,
        modifier = modifier,
        action = {
            StatusChip(
                text = row.stateLabel,
                tone = if (row.isGranted) Tone.Good else Tone.Warning,
                icon = if (row.isGranted) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            )
        },
    ) {
        row.catalogue?.let { permission ->
            Text(
                text = permission.why,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "If you skip it: ${permission.whatBreaks}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!row.isGranted) {
            ActionRow {
                Button(onClick = onGrant, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Grant")
                }
            }
        }
    }
}

/**
 * "Toggle greyed out?" — the restricted-settings explanation, collapsed by default.
 *
 * Collapsed because it is not advice most users need: it applies to the specific case where Android has
 * flagged the install source and disables the special-access toggle outright. Expanded it is three steps
 * inside Android's own Settings, described by what the user does rather than by a screenshot, since the
 * wording of that menu differs between vendors and a quoted label that does not match is worse than none.
 */
@Composable
private fun RestrictedSettingsExpander(modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("Toggle greyed out?")
            Spacer(modifier = Modifier.width(Spacing.xs))
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Hide the explanation" else "Show the explanation",
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                text = RESTRICTED_SETTINGS_HELP,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sm),
            )
        }
    }
}

// --------------------------------------------------------------------------------- 4 · Shizuku

/**
 * Step 4 (§A2): Shizuku, optional and honest about being optional.
 *
 * The state, the explanation and the button's label all come from [ShizukuState] itself, which is the same
 * source the Shizuku screen reads — four distinct states, each with its own sentence, rather than a
 * connected/not-connected boolean that would tell a user with the app installed but not started the same
 * thing it tells a user who has never heard of it.
 *
 * The N-of-M line is a real count. It is [com.gamecore.core.model.DeviceCapabilities.usableControlCount]
 * against its own `controlCount`, computed from what this device answered — not a constant, and not a
 * promise of what Shizuku would unlock.
 *
 * No download URL appears anywhere on this step. The Open button uses the launch intent the manager
 * already resolves, and is simply absent when Shizuku is not installed; a hard-coded link would be a URL
 * this project has to keep correct forever.
 */
private fun LazyListScope.shizukuStep(state: SetupWizardUiState, actions: WizardActions) {
    item(key = "shizuku-state") {
        SectionCard(
            title = state.shizuku.state.label,
            icon = Icons.Filled.Terminal,
            modifier = Modifier.padding(horizontal = ScreenPadding),
            action = {
                StatusChip(
                    text = if (state.shizuku.isConnected) "Connected" else "Not connected",
                    tone = if (state.shizuku.isConnected) Tone.Good else Tone.Muted,
                    icon = if (state.shizuku.isConnected) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Filled.RadioButtonUnchecked
                    },
                )
            },
        ) {
            Text(
                text = state.shizuku.state.explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = state.shizuku.controlSummary,
                style = MaterialTheme.typography.bodyMedium,
            )
            ActionRow {
                if (state.shizuku.isInstalled) {
                    Button(onClick = actions.onOpenShizuku, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Open Shizuku")
                    }
                }
                if (state.shizuku.state == ShizukuState.RUNNING_PERMISSION_DENIED ||
                    state.shizuku.state == ShizukuState.RUNNING_PERMISSION_UNKNOWN
                ) {
                    OutlinedButton(
                        onClick = actions.onRequestShizuku,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Ask for access")
                    }
                }
                TextButton(onClick = actions.onRecheck, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Check again")
                }
            }
        }
    }
    if (!state.shizuku.isConnected) {
        item(key = "shizuku-help") {
            SectionCard(
                title = "Starting it",
                modifier = Modifier.padding(horizontal = ScreenPadding),
            ) {
                Text(
                    text = SHIZUKU_START_HELP,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------ 5 · First game

/**
 * Step 5 (§A2): pick a game, then pick what the profile should do.
 *
 * The preset chooser lives here, inside this step, rather than as an eighth step of its own — see
 * INTEGRATION.md for why. Briefly: a preset is a set of values *for a profile*, so it has nothing to apply
 * to until an app is picked, and the step engine has no `PRESET` value to give it. Making it a sub-section
 * that appears once a game is chosen needs no change to the engine and keeps the two decisions on the one
 * screen where they are related.
 *
 * Both halves are skippable. Finishing with no game is a complete setup, and the wizard says so on Done
 * rather than treating it as unfinished business.
 */
private fun LazyListScope.firstGameStep(state: SetupWizardUiState, actions: WizardActions) {
    item(key = "game-pick") {
        val picked = state.pickedGame
        SectionCard(
            title = picked?.label ?: "No game chosen yet",
            subtitle = picked?.packageName,
            icon = Icons.Filled.SportsEsports,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        ) {
            ActionRow {
                Button(onClick = actions.onOpenPicker, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (picked == null) "Choose a game" else "Change")
                }
                if (picked != null) {
                    TextButton(onClick = actions.onClearGame, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Clear")
                    }
                }
            }
        }
    }

    if (state.pickedGame == null) return

    item(key = "preset-lead") {
        Text(
            text = "Pick a starting point. Every line below is what it will actually set on this device — " +
                "you can change any of it afterwards in the profile editor.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
    items(state.presetViews, key = { it.preset.name }) { view ->
        PresetCard(
            view = view,
            isSelected = view.preset == state.preset,
            onSelect = { actions.onChoosePreset(view.preset) },
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
}

/**
 * One preset, shown as the exact list of what it will set (§A2 step 5).
 *
 * Both kinds of line are drawn. A [PresetLine.WillSet] is "setting: value"; a [PresetLine.NotAvailable] is
 * "Not available here: reason" and stays on the card rather than being filtered out, because a user
 * comparing two presets needs to see that the difference between them is unavailable on their phone.
 *
 * Selection is shown by a chip that says "Selected" and by the button changing from an outline to a fill —
 * never by the card's colour alone (§A5).
 */
@Composable
private fun PresetCard(
    view: PresetChoiceView,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = view.label,
        icon = Icons.Filled.Tune,
        modifier = modifier,
        action = { if (isSelected) StatusChip(text = "Selected", tone = Tone.Good, icon = Icons.Filled.CheckCircle) },
    ) {
        view.lines.forEach { line ->
            when (line) {
                is PresetLine.WillSet -> Text(
                    text = "${line.setting}: ${line.value}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                is PresetLine.NotAvailable -> Text(
                    text = "${line.setting} — not available here: ${line.reason}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ActionRow {
            if (isSelected) {
                Button(onClick = onSelect, modifier = Modifier.heightIn(min = 48.dp)) { Text("Selected") }
            } else {
                OutlinedButton(onClick = onSelect, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Use this")
                }
            }
        }
    }
}

// --------------------------------------------------------------------------- 6 · Smart features

/**
 * Step 6 (§A2): the three optional behaviours, default off, one line each.
 *
 * A switch this device cannot honour is disabled *and* carries the reason as its description, which is the
 * difference between "off" and "off because Shizuku is not running". A greyed switch with no sentence is
 * the failure mode this step exists to avoid — the user is left unable to tell whether they turned it off
 * or the app did.
 *
 * All three apply to the first profile, and only to it. That is what §A2 asks for and it is also the
 * smaller claim: these are profile settings, not app-wide ones, so a user who later makes a second profile
 * is not surprised by behaviour they never chose for it.
 */
private fun LazyListScope.smartFeaturesStep(state: SetupWizardUiState, actions: WizardActions) {
    item(key = "smart-thermal") {
        SwitchRow(
            title = "Thermal auto-downshift",
            checked = state.smart.thermalDownshift,
            onCheckedChange = actions.onSmartThermal,
            description = state.smart.thermalBlockedReason
                ?: "When the device gets hot, step the refresh rate down instead of letting Android " +
                "throttle it unpredictably.",
            enabled = state.smart.thermalBlockedReason == null,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
    item(key = "smart-network") {
        SwitchRow(
            title = "Network check before launch",
            checked = state.smart.networkCheck,
            onCheckedChange = actions.onSmartNetwork,
            description = "Warn before a session starts if the connection looks bad. It never stops a " +
                "launch — you can always play anyway.",
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
    item(key = "smart-full") {
        SwitchRow(
            title = "Full performance under battery saver",
            checked = state.smart.fullPerformance,
            onCheckedChange = actions.onSmartFullPerformance,
            description = state.smart.fullPerformanceBlockedReason
                ?: "Turn Android's battery saver off while this game is in front, and turn it back on " +
                "when you leave.",
            enabled = state.smart.fullPerformanceBlockedReason == null,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
    item(key = "smart-note") {
        NoteBanner(
            text = "These apply to the profile you just made. Other profiles keep their own settings.",
            tone = Tone.Muted,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
}

// ------------------------------------------------------------------------------------- 7 · Done

/**
 * Step 7 (§A2): what is ready, and what was skipped.
 *
 * Two lists, both from [doneSummary], which is pure and tested. Skipped items are stated without being
 * nagged about — no warning tone, no "you should" — because every one of them was a choice the user made
 * on a step that said it was optional. The closing line points at Setup health, which is the place that
 * will still be able to answer the question tomorrow.
 */
private fun LazyListScope.doneStep(state: SetupWizardUiState) {
    val summary = doneSummary(
        choices = state.choices,
        permissionRows = state.permissionRows,
        shizuku = state.shizuku,
        profileName = state.pickedGame?.label,
    )
    item(key = "done-ready") {
        SectionCard(
            title = "Ready",
            icon = Icons.Filled.CheckCircle,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        ) {
            if (summary.ready.isEmpty()) {
                Text(
                    text = "Nothing set up yet — that is fine. GameCore's readings work without any of it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                summary.ready.forEach {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    if (summary.skipped.isNotEmpty()) {
        item(key = "done-skipped") {
            SectionCard(
                title = "Skipped",
                modifier = Modifier.padding(horizontal = ScreenPadding),
            ) {
                summary.skipped.forEach {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    item(key = "done-where") {
        NoteBanner(
            text = "Settings › Setup health lists all of this again, with a Fix button on anything that " +
                "is not set up. You can run this wizard again from there too.",
            tone = Tone.Muted,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
}

// ------------------------------------------------------------------------------------ the picker

/**
 * The installed-app list, shown in place of the wizard while it is open.
 *
 * Deliberately the same list, the same sort and the same rows as the profile editor's picker (§A2 step 5's
 * "reuse the Add-a-game picker"): games first by the platform's own category flag, everything else
 * alphabetically, and the flag used only to order rather than to filter, because a game whose developer
 * left the category unset is exactly the app the user came here for.
 *
 * It replaces the wizard rather than sitting on top of it because a list of several hundred rows inside a
 * step that already has a progress bar and a button bar leaves almost no height for the list — and because
 * the wizard's Next button would be visible under a screen where "next" means nothing.
 */
@Composable
fun WizardAppPicker(
    state: SetupWizardUiState,
    onChoose: (AppOption) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val padded = Modifier.padding(horizontal = ScreenPadding)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            ScreenHeader(
                title = "Choose a game",
                subtitle = if (state.isLoadingApps) {
                    "Reading the installed list"
                } else {
                    Formatters.count(state.apps.size, "app")
                },
                onBack = onClose,
            )
        }
        if (state.isLoadingApps) {
            item {
                Row(
                    modifier = padded.padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = "Listing apps", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        items(state.apps, key = { it.packageName }) { app ->
            WizardAppRow(app = app, onClick = { onChoose(app) }, modifier = padded)
        }
    }
}

@Composable
private fun WizardAppRow(app: AppOption, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ClickableCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (app.hasProfile) {
                StatusChip(text = "Has a profile", tone = Tone.Accent)
            } else if (app.isLikelyGame) {
                StatusChip(text = "Game", tone = Tone.Good, icon = Icons.Filled.SportsEsports)
            }
        }
    }
}
