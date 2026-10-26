package com.gamecore.ui.controller

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gamecore.core.controller.DriftResult
import com.gamecore.core.controller.DriftVerdict
import com.gamecore.core.input.ControllerDevice
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.Meter
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatEntry
import com.gamecore.ui.components.StatStrip
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import java.util.Locale

/**
 * Stick drift, measured from the event stream while the stick is left alone.
 *
 * Nothing on this card calibrates anything. It reports the resting mean and the RMS movement of the readings
 * the controller actually sent, against the driver's own flat/fuzz floors and the chosen tolerance. A run
 * that was disturbed is [DriftVerdict.MOVED]; one that did not receive enough fresh events is
 * [DriftVerdict.NOT_ENOUGH_DATA]. Neither can be saved as a result.
 */
@Composable
fun ControllerDriftCard(
    device: ControllerDevice,
    state: DriftUiState,
    hasAxes: Boolean,
    onStick: (StickSlot) -> Unit,
    onDuration: (Int) -> Unit,
    onOffsetThreshold: (Float) -> Unit,
    onJitterThreshold: (Float) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val floors = device.stickFloors(state.stick)
    val flatFloor = floors?.first ?: 0f
    val fuzzFloor = floors?.second ?: 0f
    val appliedOffset = maxOf(state.offsetThreshold, flatFloor)
    val appliedJitter = maxOf(state.jitterThreshold, fuzzFloor)
    val result = state.result?.takeIf { state.deviceId == device.deviceId }

    SectionCard(
        title = "Stick drift",
        modifier = modifier,
        subtitle = "Resting offset and jitter from fresh motion events",
        icon = Icons.Filled.Tune,
        action = {
            when {
                state.running -> StatusChip("Running", Tone.Accent)
                result != null -> StatusChip(result.verdict.label, result.verdict.tone())
            }
        },
    ) {
        NoteBanner(
            text = "This measures the controller; it does not recalibrate it or change its dead zone. " +
                "Leave the stick untouched for the whole run.",
            tone = Tone.Muted,
            icon = Icons.Filled.Info,
        )

        if (!hasAxes) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "${device.name.ifBlank { "This controller" }} does not declare both axes for the " +
                    "${state.stick.label.lowercase()}. Android cannot measure an axis the device does not report.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("Stick", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(6.dp))
        ChoiceRow(
            options = listOf(StickSlot.LEFT, StickSlot.RIGHT),
            selected = state.stick,
            onSelect = onStick,
            label = { it.label },
            perRow = 2,
            enabled = !state.running,
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text("Test duration", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(6.dp))
        ChoiceRow(
            options = listOf(5, 10, 20, 30),
            selected = state.durationSeconds,
            onSelect = onDuration,
            label = { "$it s" },
            perRow = 4,
            enabled = !state.running,
        )

        Spacer(modifier = Modifier.height(10.dp))
        LabSlider(
            label = "Offset threshold",
            valueText = formatPercent(appliedOffset),
            value = state.offsetThreshold,
            range = 0f..0.30f,
            onChange = onOffsetThreshold,
            enabled = !state.running,
        )
        LabSlider(
            label = "Jitter threshold",
            valueText = formatPercent(appliedJitter),
            value = state.jitterThreshold,
            range = 0f..0.10f,
            onChange = onJitterThreshold,
            enabled = !state.running,
        )
        if (flatFloor > 0f || fuzzFloor > 0f) {
            Text(
                text = buildString {
                    append("The driver declares a ")
                    append(formatPercent(flatFloor))
                    append(" flat radius and ")
                    append(formatPercent(fuzzFloor))
                    append(" fuzz. Neither threshold can be set below those floors, so a stick inside the " +
                        "driver's own dead zone is not reported as drifting.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        if (state.running) {
            Meter(fraction = state.progress)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${state.sampleCount} fresh " +
                    (if (state.sampleCount == 1) "reading" else "readings"),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = String.format(
                        Locale.US,
                        "%.1f s of %d s",
                        state.elapsedMillis / 1000f,
                        state.durationSeconds,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Keep still",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            LiveDriftFigures(state.progressResult)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel run")
            }
        } else {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Start ${state.durationSeconds} s test")
            }
        }

        result?.let { finished ->
            Spacer(modifier = Modifier.height(14.dp))
            ResultBlock(result = finished, saved = state.saved, onSave = onSave, onStart = onStart)
        }
    }
}

@Composable
private fun LiveDriftFigures(result: DriftResult?) {
    if (result == null || result.sampleCount == 0) return
    Spacer(modifier = Modifier.height(8.dp))
    KeyValueRow(label = "Offset so far", value = formatPercent(result.offsetMagnitude))
    KeyValueRow(label = "Jitter so far", value = formatPercent(result.jitter))
    KeyValueRow(label = "Inside tolerance", value = String.format(Locale.US, "%.0f%%", result.stabilityPercent))
}

@Composable
private fun ResultBlock(
    result: DriftResult,
    saved: Boolean,
    onSave: () -> Unit,
    onStart: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Result",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            StatusChip(result.verdict.label, result.verdict.tone())
            if (saved) {
                Spacer(modifier = Modifier.width(6.dp))
                StatusChip("Saved", Tone.Good)
            }
        }
        StatStrip(
            entries = listOf(
                StatEntry("Offset", formatPercent(result.offsetMagnitude), result.verdict.tone()),
                StatEntry("Jitter", formatPercent(result.jitter), Tone.Accent),
                StatEntry("Stable", String.format(Locale.US, "%.0f%%", result.stabilityPercent), Tone.Muted),
            ),
        )
        KeyValueRow(label = "Readings", value = "${result.sampleCount} over ${seconds(result.spanMillis)} s")
        KeyValueRow(label = "Peak", value = formatPercent(result.peakMagnitude))
        KeyValueRow(
            label = "Applied offset",
            value = formatPercent(result.effectiveOffset) +
                if (result.declaredFlat > 0f) " (flat floor)" else "",
        )
        KeyValueRow(
            label = "Applied jitter",
            value = formatPercent(result.effectiveJitter) +
                if (result.declaredFuzz > 0f) " (fuzz floor)" else "",
        )
        KeyValueRow(
            label = "Offset x / y",
            value = "${formatSigned(result.offsetX)} / ${formatSigned(result.offsetY)}",
        )
        Text(
            text = result.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (result.isConclusive) {
            Spacer(modifier = Modifier.height(2.dp))
            ActionRow {
                Button(onClick = onSave, enabled = !saved) {
                    Text(if (saved) "Saved" else "Save result")
                }
                OutlinedButton(onClick = onStart) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Run again")
                }
            }
        } else {
            NoteBanner(
                text = "This run has no verdict to save. A moved stick or too few fresh readings is not a " +
                    "measurement of rest.",
                tone = Tone.Warning,
                icon = Icons.Filled.Info,
            )
        }
    }
}

private fun seconds(millis: Long): String = String.format(Locale.US, "%.1f", millis / 1000f)
