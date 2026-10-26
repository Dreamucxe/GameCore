package com.gamecore.ui.controller

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gamecore.core.controller.DriftVerdict
import com.gamecore.ui.components.Tone
import java.util.Locale

/**
 * A labelled slider whose value is always visible.
 *
 * The numeric readout is part of the control rather than a tooltip: a dead-zone percentage that only appears
 * while a thumb is held is unreadable for someone checking a setting after the fact, and a screen reader gets
 * a label and a current value from the semantics, not an unlabelled seek bar.
 */
@Composable
fun LabSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "$label: $valueText" }
                .padding(vertical = 0.dp),
        )
    }
}

/** One decimal place is enough for a percentage of stick travel; more implies precision the stick lacks. */
fun formatPercent(value: Float): String = String.format(Locale.US, "%.1f%%", value * 100f)

/** A signed normalized figure, for an offset component. */
fun formatSigned(value: Float): String = String.format(Locale.US, "%+.3f", value)

/** What a verdict should look like when it is rendered as a chip. */
fun DriftVerdict.tone(): Tone = when (this) {
    DriftVerdict.STABLE -> Tone.Good
    DriftVerdict.DRIFT -> Tone.Danger
    DriftVerdict.UNSTABLE -> Tone.Warning
    DriftVerdict.MOVED -> Tone.Warning
    DriftVerdict.NOT_ENOUGH_DATA -> Tone.Muted
}
