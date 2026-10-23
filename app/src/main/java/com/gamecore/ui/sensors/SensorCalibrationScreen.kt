package com.gamecore.ui.sensors

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.common.Formatters
import com.gamecore.core.sensors.CalibrationResult
import com.gamecore.core.sensors.SensorQuality
import com.gamecore.core.sensors.TraceSample
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.Meter
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Sensor calibration: a ten-second still capture, the live trace while it runs, then what the trace says.
 *
 * The screen is deliberately one scroll rather than three separate pages. The instruction, the button,
 * the trace and the verdict are the same task at different moments, and a user who has just been told
 * their gyroscope is poor needs the trace that produced that verdict within the same thumb movement.
 *
 * The trace is drawn from [CalibrationUiState.samples] on a plain [Canvas] with two independent vertical
 * scales. Accelerometer magnitude sits around gravity (9.8 m/s²); gyroscope magnitude sits around zero.
 * Sharing one scale would press the gyro line flat against the axis and hide exactly the bias the screen
 * exists to show.
 */
@Composable
fun SensorCalibrationScreen(
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: SensorCalibrationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val result = state.result
    val padded = Modifier.padding(horizontal = ScreenPadding)

    val headerChip = when {
        state.isCapturing -> "Capturing" to Tone.Accent
        result != null -> result.quality.name to qualityTone(result.quality)
        else -> "Ready" to Tone.Muted
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Sensor calibration",
                subtitle = "Ten seconds still, then the bias, noise and drift the sensor actually has",
                onBack = onBack,
                action = { StatusChip(text = headerChip.first, tone = headerChip.second) },
            )
        }

        item {
            NoteBanner(
                text = "Place the device on a flat, stable surface and leave it untouched until the " +
                    "capture stops. Movement is noise: it will be measured as part of the sensor's " +
                    "quality, not corrected away.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
                modifier = padded,
            )
        }

        item {
            CaptureCard(
                state = state,
                onStart = viewModel::startCapture,
                onStop = viewModel::stopCapture,
                modifier = padded,
            )
        }

        item {
            TraceCard(state = state, modifier = padded)
        }

        if (result != null) {
            item {
                ResultCard(result = result, modifier = padded)
            }
        }
    }
}

/**
 * The progress bar and the one button that changes meaning while a capture runs.
 *
 * The meter is driven by elapsed time rather than sample count, so a device delivering fewer samples per
 * second still shows a real ten-second window rather than a bar that reaches the end early.
 */
@Composable
private fun CaptureCard(
    state: CalibrationUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val result = state.result
    val meterTone = when {
        state.isCapturing -> Tone.Accent
        result != null -> qualityTone(result.quality)
        else -> Tone.Muted
    }
    val statusText = when {
        state.isCapturing -> "Capturing — ${Formatters.count(state.samples.size, "sample")}"
        result != null -> "Capture complete"
        else -> "Not started"
    }

    SectionCard(
        title = "Capture",
        subtitle = "Ten seconds at the sensor's game rate",
        icon = Icons.Filled.Sensors,
        modifier = modifier,
    ) {
        Meter(fraction = state.progress, tone = meterTone)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = Formatters.percent(state.progress),
                style = MaterialTheme.typography.bodyMedium,
                color = meterTone.colour(),
            )
        }
        ActionRow {
            Button(
                onClick = if (state.isCapturing) onStop else onStart,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = if (state.isCapturing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    when {
                        state.isCapturing -> "Stop capture"
                        result == null -> "Start capture"
                        else -> "Calibrate again"
                    },
                )
            }
        }
    }
}

/**
 * The live trace and its legend.
 *
 * Fewer than two points is not a flat line, it is no line: the Canvas draws nothing and the card says it
 * is waiting, rather than inventing a shape from a single reading.
 */
@Composable
private fun TraceCard(
    state: CalibrationUiState,
    modifier: Modifier = Modifier,
) {
    val samples = state.samples
    SectionCard(
        title = "Live trace",
        subtitle = "Accelerometer and gyroscope magnitude, one point per sample",
        icon = Icons.Filled.Timeline,
        modifier = modifier,
    ) {
        TraceLegend()
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TraceHeight)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = MaterialTheme.shapes.medium,
                )
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            SensorTraceCanvas(
                samples = samples,
                modifier = Modifier.fillMaxSize(),
            )
            if (samples.size < 2) {
                Text(
                    text = "Waiting for samples",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (samples.isNotEmpty()) {
            val last = samples.last()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Accel ${format(last.accelMagnitude(), 2)} m/s² · " +
                    "Gyro ${format(last.gyroMagnitude(), 3)} rad/s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TraceLegend() {
    val accelerometer = MaterialTheme.colorScheme.primary
    val gyroscope = Tone.Warning.colour()
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem(colour = accelerometer, label = "Accel |a| (m/s²)")
        LegendItem(colour = gyroscope, label = "Gyro |ω| (rad/s)")
    }
}

@Composable
private fun LegendItem(colour: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(width = 18.dp, height = 3.dp)) {
            drawRect(color = colour)
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Two polylines over one shared time axis.
 *
 * Each series is scaled to its own maximum so both shapes stay readable, with a floor on the range so a
 * perfectly still gyro is drawn as a line near zero rather than amplified into a mountain range by
 * autoscaling a few ten-thousandths of a radian.
 */
@Composable
private fun SensorTraceCanvas(
    samples: List<TraceSample>,
    modifier: Modifier = Modifier,
) {
    val accelColour = MaterialTheme.colorScheme.primary
    val gyroColour = Tone.Warning.colour()
    val gridColour = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    Canvas(modifier = modifier) {
        if (samples.size < 2) return@Canvas

        val topInset = 4.dp.toPx()
        val bottomInset = 4.dp.toPx()
        val usableHeight = (size.height - topInset - bottomInset).coerceAtLeast(1f)
        val stepX = size.width / (samples.size - 1).toFloat()
        val strokeWidth = 2.dp.toPx()

        val accelMax = max(MIN_ACCEL_SCALE, samples.maxOf { it.accelMagnitude() } * 1.1f)
        val gyroMax = max(MIN_GYRO_SCALE, samples.maxOf { it.gyroMagnitude() } * 1.1f)

        for (line in 0..GRID_LINES) {
            val y = topInset + usableHeight * line / GRID_LINES
            drawLine(
                color = gridColour,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
            )
        }

        fun pathFor(maxValue: Float, magnitude: (TraceSample) -> Float): Path {
            val path = Path()
            samples.forEachIndexed { index, sample ->
                val x = stepX * index
                val fraction = (magnitude(sample) / maxValue).coerceIn(0f, 1f)
                val y = topInset + usableHeight * (1f - fraction)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            return path
        }

        drawPath(
            path = pathFor(accelMax) { it.accelMagnitude() },
            color = accelColour,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            path = pathFor(gyroMax) { it.gyroMagnitude() },
            color = gyroColour,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * The verdict, its colour and the figures behind it.
 *
 * The summary is the engine's own sentence rather than one assembled here: the thresholds, the figures
 * and the words that describe them then have exactly one home. The chip is the colour-coded verdict and
 * always carries the word too, so the meaning survives for a user who cannot distinguish the tints.
 */
@Composable
private fun ResultCard(
    result: CalibrationResult,
    modifier: Modifier = Modifier,
) {
    val tone = qualityTone(result.quality)
    SectionCard(
        title = "Calibration result",
        icon = qualityIcon(result.quality),
        modifier = modifier,
        action = { StatusChip(text = result.quality.name, tone = tone) },
    ) {
        NoteBanner(
            text = qualityDescription(result.quality),
            tone = tone,
            icon = qualityIcon(result.quality),
        )
        Spacer(modifier = Modifier.height(10.dp))
        KeyValueRow(
            label = "Gyro bias",
            value = "${format(result.gyroBiasRadPerSec, 4)} rad/s",
        )
        KeyValueRow(
            label = "Gyro noise",
            value = "${format(result.gyroNoise, 4)} rad/s",
        )
        KeyValueRow(
            label = "Accel noise",
            value = "${format(result.accelNoise, 3)} m/s²",
        )
        KeyValueRow(
            label = "Drift",
            value = "${format(result.driftPerMinuteDegrees, 1)} °/min",
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = result.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun qualityTone(quality: SensorQuality): Tone = when (quality) {
    SensorQuality.GOOD -> Tone.Good
    SensorQuality.FAIR -> Tone.Warning
    SensorQuality.POOR -> Tone.Danger
    SensorQuality.UNKNOWN -> Tone.Muted
}

private fun qualityIcon(quality: SensorQuality): ImageVector = when (quality) {
    SensorQuality.GOOD -> Icons.Filled.Sensors
    SensorQuality.FAIR -> Icons.Filled.Info
    SensorQuality.POOR -> Icons.Filled.Warning
    SensorQuality.UNKNOWN -> Icons.Filled.HelpOutline
}

private fun qualityDescription(quality: SensorQuality): String = when (quality) {
    SensorQuality.GOOD ->
        "Good: gyro bias and noise are low. This sensor is usable as it is."
    SensorQuality.FAIR ->
        "Fair: noticeable bias or noise. Playable, but long sessions may drift."
    SensorQuality.POOR ->
        "Poor: high bias or noise. Treat the readings as approximate and recalibrate " +
            "the device if it offers a tool for it."
    SensorQuality.UNKNOWN ->
        "Unknown: not enough samples to judge. Capture again and keep the device still."
}

private fun TraceSample.accelMagnitude(): Float = sqrt(ax * ax + ay * ay + az * az)

private fun TraceSample.gyroMagnitude(): Float = sqrt(gx * gx + gy * gy + gz * gz)

private fun format(value: Float, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

private val TraceHeight = 180.dp
private const val GRID_LINES = 4
private const val MIN_ACCEL_SCALE = 12f
private const val MIN_GYRO_SCALE = 0.1f
