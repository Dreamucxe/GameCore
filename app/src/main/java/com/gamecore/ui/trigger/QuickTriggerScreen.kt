package com.gamecore.ui.trigger

import android.app.StatusBarManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.model.QuickTriggerAction
import com.gamecore.core.model.QuickTriggerMethod
import com.gamecore.core.model.QuickTriggerSettings
import com.gamecore.core.model.TriggerAvailability
import com.gamecore.service.QuickTriggerTileService
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SliderRow
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.startIntentSafely

/**
 * The Quick Trigger settings: how to open GameCore quickly, and an honest account of what each way costs.
 *
 * The one fact the whole screen is built around is that an ordinary Android app cannot intercept hardware
 * buttons from outside its own window. So the chooser never offers a "double-tap from anywhere" that does
 * not exist — it offers the real mechanisms, each labelled with its real requirement, and the availability
 * line under the chooser is read from the live device on every resume.
 *
 * A method the device cannot run at all is not shown. The sliders and the extra rows appear only for the
 * method they belong to, so nothing on screen is a control that does nothing.
 */
@Composable
fun QuickTriggerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QuickTriggerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val padded = Modifier.padding(horizontal = ScreenPadding)

    // The overlay permission, the accessibility service and the tile are all granted in Android's own UI,
    // which never calls back. Re-reading availability on resume is the only signal that one has changed.
    OnResume { viewModel.refresh() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Quick trigger",
                subtitle = "A shortcut that opens GameCore",
                onBack = onBack,
                action = {
                    StatusChip(
                        text = if (state.settings.enabled) "Armed" else "Off",
                        tone = if (state.settings.enabled) Tone.Good else Tone.Muted,
                        icon = Icons.Filled.Bolt,
                    )
                },
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

        item {
            NoteBanner(text = LIMITS_NOTE, tone = Tone.Muted, icon = Icons.Filled.Info, modifier = padded)
        }

        item { EnableCard(state = state, onSetEnabled = viewModel::setEnabled, modifier = padded) }

        if (state.settings.enabled) {
            item {
                MethodCard(
                    state = state,
                    onSelectMethod = viewModel::setMethod,
                    modifier = padded,
                )
            }
            item {
                RequirementCard(
                    state = state,
                    onGrant = { intent ->
                        if (!context.startIntentSafely(intent)) viewModel.onAccessibilityIntentFailed()
                    },
                    accessibilityIntent = viewModel::accessibilityIntent,
                    onRequestTile = {
                        val requested = QuickTriggerTileService.requestAdd(context) { result ->
                            viewModel.onTileResult(
                                result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED,
                            )
                        }
                        if (!requested) viewModel.onTilePromptUnavailable()
                    },
                    modifier = padded,
                )
            }
            item {
                TuningCard(
                    state = state,
                    onSetWindow = viewModel::setWindow,
                    onCommitWindow = viewModel::commitWindow,
                    onSetShake = viewModel::setShake,
                    onCommitShake = viewModel::commitShake,
                    onSetPassThrough = viewModel::setPassThroughKeys,
                    modifier = padded,
                )
            }
            item {
                ActionCard(
                    state = state,
                    onSelectAction = viewModel::setAction,
                    onTest = viewModel::testFire,
                    modifier = padded,
                )
            }
        }
    }
}

/** The master switch, and what turning it off leaves behind (nothing). */
@Composable
private fun EnableCard(
    state: QuickTriggerUiState,
    onSetEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "GameCore / Quick trigger", icon = Icons.Filled.Bolt, modifier = modifier) {
        SwitchRow(
            title = "Enable the quick trigger",
            checked = state.settings.enabled,
            onCheckedChange = onSetEnabled,
            description = "A shortcut that opens the GameCore panel or the app. Off means no shortcut and " +
                "no background cost of any kind.",
        )
    }
}

/**
 * The method chooser.
 *
 * Only the methods the current device can actually run are offered — a device with no accelerometer never
 * shows "Shake". The explanation under the chooser is the selected method's own, so a user reads what they
 * are agreeing to before the requirement card below tells them whether it is met.
 */
@Composable
private fun MethodCard(
    state: QuickTriggerUiState,
    onSelectMethod: (QuickTriggerMethod) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Trigger method", icon = Icons.Filled.Tune, modifier = modifier) {
        val methods = state.offeredMethods
        ChoiceRow(
            options = methods,
            selected = state.settings.method,
            onSelect = onSelectMethod,
            label = { it.label },
            perRow = 2,
        )
        RowDivider()
        Text(
            text = state.settings.method.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Whether the chosen method can fire right now, and the one button that fixes it if it cannot.
 *
 * The status chip is the coordinator's own reading — AVAILABLE, REQUIRES PERMISSION, NEEDS SETUP — and the
 * button changes to match: accessibility opens the system service screen, the tile asks Android to offer
 * itself, the overlay one is left to the permissions screen the rest of the app already routes to.
 */
@Composable
private fun RequirementCard(
    state: QuickTriggerUiState,
    onGrant: (android.content.Intent) -> Unit,
    accessibilityIntent: () -> android.content.Intent,
    onRequestTile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val availability = state.selected ?: return
    SectionCard(title = "Requirement", icon = Icons.Filled.Info, modifier = modifier) {
        KeyValueRow(
            label = availability.method.requirement.label,
            value = availability.status.label,
            tone = statusTone(availability.status),
        )
        Text(
            text = availability.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // The volume methods work on GameCore's own screens with no grant; the accessibility service lifts
        // that to anywhere. Offer it as the optional upgrade it is, not as a requirement.
        if (availability.method.isKeyBased) {
            RowDivider()
            KeyValueRow(
                label = "Accessibility service",
                value = if (state.accessibilityEnabled) "ON" else "OFF",
                tone = if (state.accessibilityEnabled) Tone.Good else Tone.Muted,
            )
            Text(
                text = ACCESSIBILITY_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActionRow {
                TextButton(onClick = { onGrant(accessibilityIntent()) }) {
                    Text(if (state.accessibilityEnabled) "Accessibility settings" else "Turn on")
                }
            }
        }

        if (availability.method == QuickTriggerMethod.QUICK_TILE) {
            RowDivider()
            ActionRow {
                OutlinedButton(onClick = onRequestTile) {
                    Text(if (state.canRequestTile) "Add the tile" else "How to add the tile")
                }
            }
        }
    }
}

/**
 * The two dials, each shown only for the method it belongs to.
 *
 * The double-tap window is for the three key methods; the shake sensitivity is for shake. Pass-through
 * defaults on for the key methods, because a volume key that stops changing the volume is a bug from the
 * user's side of the screen even when it was set deliberately.
 */
@Composable
private fun TuningCard(
    state: QuickTriggerUiState,
    onSetWindow: (Int) -> Unit,
    onCommitWindow: () -> Unit,
    onSetShake: (Int) -> Unit,
    onCommitShake: () -> Unit,
    onSetPassThrough: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val method = state.settings.method
    val isKey = method.isKeyBased
    val isShake = method == QuickTriggerMethod.SHAKE
    if (!isKey && !isShake) return

    SectionCard(title = "Tuning", icon = Icons.Filled.Tune, modifier = modifier) {
        if (isKey) {
            SliderRow(
                title = "Second press within",
                value = state.windowValue,
                range = QuickTriggerSettings.MIN_WINDOW.toInt()..QuickTriggerSettings.MAX_WINDOW.toInt(),
                onValueChange = onSetWindow,
                valueLabel = "${state.windowValue} ms",
                description = "How long the second press may arrive after the first. Shorter misses a slow " +
                    "double tap; longer turns two ordinary volume changes into a trigger.",
                onValueChangeFinished = onCommitWindow,
            )
            SwitchRow(
                title = "Keep the volume keys working",
                checked = state.settings.passThroughKeys,
                onCheckedChange = onSetPassThrough,
                description = "On, the keys still change the volume as well as firing the trigger. Off, the " +
                    "second press only fires the trigger.",
            )
        }
        if (isShake) {
            SliderRow(
                title = "Shake sensitivity",
                value = state.shakeValue,
                range = 0..100,
                onValueChange = onSetShake,
                valueLabel = "${state.shakeValue}%",
                description = "Higher fires on a gentler shake. Too high and carrying the phone can open the " +
                    "panel, so it takes two sharp jolts either way.",
                onValueChangeFinished = onCommitShake,
            )
        }
    }
}

/** What firing does, and a button to fire it once so the choice can be felt rather than guessed. */
@Composable
private fun ActionCard(
    state: QuickTriggerUiState,
    onSelectAction: (QuickTriggerAction) -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Action", icon = Icons.Filled.Bolt, modifier = modifier) {
        ChoiceRow(
            options = QuickTriggerAction.entries.toList(),
            selected = state.settings.action,
            onSelect = onSelectAction,
            label = { it.label },
            perRow = 3,
        )
        Text(
            text = state.settings.action.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowDivider()
        ActionRow {
            OutlinedButton(onClick = onTest) { Text("Test it now") }
        }
    }
}

private fun statusTone(status: TriggerAvailability.Status): Tone = when (status) {
    TriggerAvailability.Status.AVAILABLE -> Tone.Good
    TriggerAvailability.Status.NEEDS_PERMISSION -> Tone.Warning
    TriggerAvailability.Status.NEEDS_SETUP -> Tone.Warning
    TriggerAvailability.Status.UNSUPPORTED -> Tone.Danger
}

private const val LIMITS_NOTE =
    "An ordinary app cannot capture hardware buttons from outside its own window — Android sends keys to " +
        "whatever is in front. So each method below either works on GameCore's own screens, or needs one " +
        "specific thing, named next to it. None of them uses a hidden feature or root."

private const val ACCESSIBILITY_NOTE =
    "Without it, the volume double-tap works while GameCore is the app on screen. Turning on GameCore's " +
        "accessibility service lets it work from inside other apps too. The service reads only key events, " +
        "and only to detect the trigger."
