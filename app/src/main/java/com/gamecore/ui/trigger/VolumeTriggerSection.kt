package com.gamecore.ui.trigger

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gamecore.core.model.FractionPoint
import com.gamecore.core.model.VolumeTriggerButton
import com.gamecore.core.model.VolumeTriggerConfig
import com.gamecore.core.model.VolumeTriggerPressMode
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SectionGap
import com.gamecore.ui.components.SliderRow
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone
import kotlin.math.roundToInt

/**
 * The per-game volume-button point trigger (feature 4), as one card the profile editor embeds.
 *
 * The flow is: switch it on, pick which volume button, point at where the tap should land, choose whether a
 * single press, a double press or a long press fires it (a long press injects a hold, whose length the
 * slider sets), and test it. Every piece is stateless — the value comes in through [config], the changes go
 * out through the lambdas — so the profile row this edits stays the one copy of the truth, the way the
 * crosshair and Scout editors are built.
 *
 * The honesty this feature turns on lives in [availability]: the trigger needs BOTH GameCore's accessibility
 * service (to read a volume key while a *game* holds focus — an ordinary app cannot) AND Shizuku (to inject
 * the tap). Miss either and the card says exactly what is missing and offers the button that fixes it, and
 * "Test trigger" is disabled rather than left as a control that does nothing (§24/§32). Which button is being
 * edited is hoisted to the caller as [selectedButton]; the point, press mode and hold time shown are that
 * button's own binding.
 *
 * [onCommitHoldMs] is optional: like the crosshair's sliders, the hold slider reports every value through
 * [onHoldMsChange] and fires the commit once on release, so a caller holding a draft can store once per
 * gesture. A caller that persists on every change can leave it defaulted.
 */
@Composable
fun VolumeTriggerSection(
    config: VolumeTriggerConfig,
    selectedButton: VolumeTriggerButton,
    availability: VolumeTriggerAvailability,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectButton: (VolumeTriggerButton) -> Unit,
    onPointChange: (FractionPoint) -> Unit,
    onCommitPoint: () -> Unit,
    onPressModeChange: (VolumeTriggerPressMode) -> Unit,
    onHoldMsChange: (Int) -> Unit,
    onTestTrigger: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenShizuku: () -> Unit,
    modifier: Modifier = Modifier,
    onCommitHoldMs: () -> Unit = {},
) {
    val binding = if (selectedButton == VolumeTriggerButton.VOLUME_UP) config.up else config.down
    val pressMode = binding?.pressMode ?: VolumeTriggerPressMode.SINGLE_TAP
    val holdMs = binding?.holdMs ?: DEFAULT_HOLD_MS
    val canTest = availability.isUsable && binding?.point != null

    SectionCard(
        title = "Volume button trigger",
        subtitle = "Fire a tap where you place the marker",
        icon = Icons.Filled.Bolt,
        modifier = modifier,
        action = {
            val (chipText, chipTone) = when {
                !config.enabled -> "Off" to Tone.Muted
                availability.isUsable -> "Ready" to Tone.Good
                else -> "Unavailable" to Tone.Warning
            }
            StatusChip(text = chipText, tone = chipTone)
        },
    ) {
        SwitchRow(
            title = "Map a volume button to a tap",
            checked = config.enabled,
            onCheckedChange = onToggleEnabled,
            description = "While this game is in front, a volume-button press injects a tap at the point " +
                "you place below. Needs GameCore's accessibility service and Shizuku.",
        )

        if (config.enabled) {
            AvailabilityNote(
                availability = availability,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onOpenShizuku = onOpenShizuku,
            )

            RowDivider()
            ChoiceRow(
                options = VOLUME_BUTTONS,
                selected = selectedButton,
                onSelect = onSelectButton,
                label = ::buttonLabel,
                perRow = 2,
            )
            SectionGap(8)
            VolumeTriggerPointPicker(
                point = binding?.point,
                onPointChange = onPointChange,
                onCommit = onCommitPoint,
            )
            SectionGap(8)
            KeyValueRow(
                label = "Point",
                value = binding?.point?.let { "x ${percent(it.x)} · y ${percent(it.y)}" } ?: "Not placed",
                tone = if (binding?.point == null) Tone.Muted else Tone.Neutral,
            )

            RowDivider()
            ChoiceRow(
                options = PRESS_MODES,
                selected = pressMode,
                onSelect = onPressModeChange,
                label = ::pressModeLabel,
                perRow = 3,
            )
            Text(
                text = pressModeExplanation(pressMode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pressMode == VolumeTriggerPressMode.HOLD) {
                SliderRow(
                    title = "Hold for",
                    value = holdMs,
                    range = MIN_HOLD_MS..MAX_HOLD_MS,
                    onValueChange = onHoldMsChange,
                    valueLabel = "$holdMs ms",
                    description = "How long the injected touch stays down before it lifts.",
                    onValueChangeFinished = onCommitHoldMs,
                )
            }

            RowDivider()
            ActionRow {
                Button(onClick = onTestTrigger, enabled = canTest) { Text("Test trigger") }
                when {
                    !availability.isUsable -> StatusChip(text = "Unavailable", tone = Tone.Warning)
                    binding?.point == null -> StatusChip(text = "Place a point first", tone = Tone.Muted)
                }
            }
        }
    }
}

/**
 * The unavailable line and the buttons that fix it, shown only while something is actually missing.
 *
 * One button per missing grant rather than a single "fix it" — the two live in different apps and are
 * granted in different places, so a user who has one already should not be sent to it again.
 */
@Composable
private fun AvailabilityNote(
    availability: VolumeTriggerAvailability,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    val reason = availability.reason ?: return
    SectionGap(8)
    NoteBanner(text = "Unavailable. $reason.", tone = Tone.Warning, icon = Icons.Filled.Info)
    SectionGap(6)
    ActionRow {
        if (!availability.accessibilityEnabled) {
            OutlinedButton(onClick = onOpenAccessibilitySettings) { Text("Turn on accessibility") }
        }
        if (!availability.shizukuGranted) {
            OutlinedButton(onClick = onOpenShizuku) { Text("Open Shizuku") }
        }
    }
}

private val VOLUME_BUTTONS = listOf(VolumeTriggerButton.VOLUME_UP, VolumeTriggerButton.VOLUME_DOWN)

private val PRESS_MODES = listOf(
    VolumeTriggerPressMode.SINGLE_TAP,
    VolumeTriggerPressMode.DOUBLE_TAP,
    VolumeTriggerPressMode.HOLD,
)

private fun buttonLabel(button: VolumeTriggerButton): String = when (button) {
    VolumeTriggerButton.VOLUME_UP -> "Volume Up"
    VolumeTriggerButton.VOLUME_DOWN -> "Volume Down"
}

private fun pressModeLabel(mode: VolumeTriggerPressMode): String = when (mode) {
    VolumeTriggerPressMode.SINGLE_TAP -> "Single"
    VolumeTriggerPressMode.DOUBLE_TAP -> "Double"
    VolumeTriggerPressMode.HOLD -> "Hold"
}

private fun pressModeExplanation(mode: VolumeTriggerPressMode): String = when (mode) {
    VolumeTriggerPressMode.SINGLE_TAP -> "A single press of the volume button injects one tap at the point."
    VolumeTriggerPressMode.DOUBLE_TAP ->
        "A double press injects a tap at the point — harder to fire by accident than a single press."
    VolumeTriggerPressMode.HOLD -> "A long press injects a touch held down for the time set below."
}

private fun percent(fraction: Float): String = "${(fraction * 100).roundToInt()}%"

/** A hold shorter than this reads as a tap; longer than this is a fidget, not a deliberate hold. */
private const val MIN_HOLD_MS = 100
private const val MAX_HOLD_MS = 2000

/** The binding's own default, mirrored so an unplaced button's slider starts where a new binding would. */
private const val DEFAULT_HOLD_MS = 250
