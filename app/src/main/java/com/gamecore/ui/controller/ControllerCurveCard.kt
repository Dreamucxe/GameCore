package com.gamecore.ui.controller

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gamecore.core.controller.AxisNormalizer
import com.gamecore.core.controller.AxisRange
import com.gamecore.core.controller.ResponseCurve
import com.gamecore.core.controller.ResponseCurveKind
import com.gamecore.core.controller.StickReading
import com.gamecore.core.input.ControllerDevice
import com.gamecore.core.input.ControllerInputSnapshot
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import java.util.Locale

/**
 * A radial dead zone and response curve, simulated against the live stick.
 *
 * This card changes nothing outside itself. The game the user came from keeps reading the controller exactly
 * as Android hands it the events; GameCore has no supported hook to alter another app's input. The preview
 * answers "what would a game using this curve do with the stick where it is now", and the note above the
 * graph says so in those words.
 */
@Composable
fun ControllerCurveCard(
    device: ControllerDevice,
    snapshot: ControllerInputSnapshot,
    state: CurveUiState,
    onStick: (StickSlot) -> Unit,
    onDeadzone: (Float) -> Unit,
    onKind: (ResponseCurveKind) -> Unit,
    onExponent: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val slot = state.stick.takeIf { it != StickSlot.HAT } ?: StickSlot.LEFT
    val xAxis = device.axisFor(slot.xAxis)
    val yAxis = device.axisFor(slot.yAxis)
    val settings = state.settings
    val raw = if (xAxis != null && yAxis != null &&
        snapshot.reported(slot.xAxis) && snapshot.reported(slot.yAxis)
    ) {
        StickReading(
            x = AxisNormalizer.signed(
                snapshot.axis(slot.xAxis),
                AxisRange(xAxis.minimum, xAxis.maximum, xAxis.flat, xAxis.fuzz),
            ),
            y = AxisNormalizer.signed(
                snapshot.axis(slot.yAxis),
                AxisRange(yAxis.minimum, yAxis.maximum, yAxis.flat, yAxis.fuzz),
            ),
        )
    } else {
        StickReading.CENTRE
    }
    val shaped = ResponseCurve.apply(raw, settings)
    val rawRadius = raw.magnitude.coerceIn(0f, 1f)
    val shapedRadius = shaped.magnitude.coerceIn(0f, 1f)
    val description = "Response curve simulation. Input ${formatPercent(rawRadius)}, output " +
        "${formatPercent(shapedRadius)}. Dead zone ${formatPercent(settings.deadzone)}, " +
        "${settings.kind.label} curve."

    SectionCard(
        title = "Response curve lab",
        modifier = modifier,
        subtitle = "A reference simulation, not a games setting",
        icon = Icons.Filled.Tune,
        action = { StatusChip("Simulation only", Tone.Accent) },
    ) {
        NoteBanner(
            text = "No setting on this card is applied to a game, a controller or the system. Android gives " +
                "GameCore no supported way to change how another app reads a stick; this only previews a " +
                "curve against the live position.",
            tone = Tone.Warning,
            icon = Icons.Filled.Info,
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text("Stick", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(6.dp))
        ChoiceRow(
            options = listOf(StickSlot.LEFT, StickSlot.RIGHT),
            selected = slot,
            onSelect = onStick,
            label = { it.label },
            perRow = 2,
        )
        if (xAxis == null || yAxis == null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${device.name.ifBlank { "This controller" }} does not declare both axes for " +
                    "${slot.label.lowercase()}, so the preview cannot follow it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        TransferGraph(
            settings = settings,
            rawRadius = rawRadius,
            shapedRadius = shapedRadius,
            description = description,
        )
        Spacer(modifier = Modifier.height(10.dp))

        KeyValueRow(label = "Raw radius", value = formatPercent(rawRadius))
        KeyValueRow(label = "Shaped radius", value = formatPercent(shapedRadius))
        KeyValueRow(
            label = "Raw x / y",
            value = "${formatSigned(raw.x)} / ${formatSigned(raw.y)}",
        )
        KeyValueRow(
            label = "Shaped x / y",
            value = "${formatSigned(shaped.x)} / ${formatSigned(shaped.y)}",
        )
        KeyValueRow(label = "Direction", value = if (rawRadius < 0.001f) "Centred" else "Preserved")
        KeyValueRow(label = "Dead zone", value = formatPercent(settings.deadzone))
        KeyValueRow(
            label = "Curve",
            value = if (settings.kind == ResponseCurveKind.SMOOTH) {
                "Smooth (exponent not used)"
            } else {
                "${settings.kind.label} · ${formatExponent(settings.exponent)}"
            },
        )

        Spacer(modifier = Modifier.height(10.dp))
        LabSlider(
            label = "Radial dead zone",
            valueText = formatPercent(state.deadzone),
            value = state.deadzone,
            range = 0f..0.50f,
            onChange = onDeadzone,
        )
        Text("Curve shape", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(6.dp))
        ChoiceRow(
            options = ResponseCurveKind.entries.toList(),
            selected = state.kind,
            onSelect = onKind,
            label = { it.label },
            perRow = 3,
        )
        LabSlider(
            label = "Exponent",
            valueText = formatExponent(state.exponent),
            value = state.exponent,
            range = 1f..4f,
            onChange = onExponent,
            enabled = state.kind == ResponseCurveKind.EXPONENTIAL,
        )
        Text(
            text = "Exponent applies to the exponential curve. A smooth curve uses a fixed ease-in/ease-out " +
                "shape; a linear curve ignores both.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        TextButton(onClick = onReset) {
            Text("Reset curve settings")
        }
    }
}

@Composable
private fun TransferGraph(
    settings: com.gamecore.core.controller.ResponseCurveSettings,
    rawRadius: Float,
    shapedRadius: Float,
    description: String,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val curveColour = MaterialTheme.colorScheme.primary
    val dotColour = MaterialTheme.colorScheme.tertiary
    val deadzoneColour = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Output ↑  ·  input radius →",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.35f)
                .semantics { contentDescription = description },
        ) {
            val pad = 10.dp.toPx()
            val width = size.width - pad * 2f
            val height = size.height - pad * 2f

            fun x(value: Float): Float = pad + value.coerceIn(0f, 1f) * width
            fun y(value: Float): Float = size.height - pad - value.coerceIn(0f, 1f) * height

            for (step in 1..3) {
                val fraction = step / 4f
                drawLine(grid, Offset(x(fraction), y(0f)), Offset(x(fraction), y(1f)), strokeWidth = 1f)
                drawLine(grid, Offset(x(0f), y(fraction)), Offset(x(1f), y(fraction)), strokeWidth = 1f)
            }
            drawLine(
                color = grid,
                start = Offset(x(0f), y(0f)),
                end = Offset(x(1f), y(1f)),
                strokeWidth = 1.dp.toPx(),
            )
            if (settings.deadzone > 0f) {
                drawLine(
                    color = deadzoneColour,
                    start = Offset(x(settings.deadzone), y(0f)),
                    end = Offset(x(settings.deadzone), y(1f)),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            val path = Path()
            path.moveTo(x(0f), y(0f))
            if (settings.deadzone > 0f) {
                path.lineTo(x(settings.deadzone), y(0f))
            }
            val start = settings.deadzone.coerceIn(0f, 1f)
            for (index in 0..80) {
                val radius = start + (1f - start) * index / 80f
                path.lineTo(x(radius), y(ResponseCurve.magnitude(radius, settings)))
            }
            drawPath(path, color = curveColour, style = Stroke(width = 2.dp.toPx()))
            drawCircle(dotColour, radius = 4.dp.toPx(), center = Offset(x(rawRadius), y(shapedRadius)))
        }
    }
}

private fun formatExponent(value: Float): String = String.format(Locale.US, "%.2f", value)
