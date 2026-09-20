package com.gamecore.ui.motion

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.Observed
import com.gamecore.core.common.unavailabilityText
import com.gamecore.core.sensors.Axis3
import com.gamecore.core.sensors.MotionFrame
import com.gamecore.core.sensors.MotionSensorKind
import com.gamecore.core.sensors.MotionSummary
import com.gamecore.core.sensors.MotionTrace
import com.gamecore.core.sensors.SensorDescriptor
import com.gamecore.data.repository.DiagnosticsExport
import com.gamecore.data.repository.DiagnosticsFormat
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.LineGraph
import com.gamecore.ui.components.Meter
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatEntry
import com.gamecore.ui.components.StatStrip
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.components.startIntentSafely
import java.util.Locale
import kotlin.math.max

/**
 * Gyro analytics: what the motion sensors on this device are actually reporting, right now.
 *
 * Three things this screen is careful about, all of them the same care in different places:
 *
 *  - **A missing sensor is a sentence, not a zero.** Every card is fed by an [Observed], so a phone
 *    without a gyroscope shows the reason it has no angular velocity rather than three flat lines.
 *  - **The requested rate is not the delivered rate.** Android treats `samplingPeriodUs` as a hint, so
 *    the observed frequency is printed beside the choice and the two are allowed to disagree on screen.
 *  - **Motion is not aim.** The analysis card measures how the phone moved, which is a real measurement
 *    and a different thing from how well the player was aiming. The card says that out loud.
 */
@Composable
fun MotionScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MotionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Gyro analytics",
                subtitle = "Angular velocity, acceleration and orientation, from this device's sensors",
                onBack = onBack,
                action = {
                    if (state.isStreaming) {
                        StatusChip("Live", Tone.Good, Icons.Filled.FiberManualRecord)
                    }
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

        if (state.isLoaded && !state.hasAnySensor) {
            item {
                EmptyState(
                    icon = Icons.Filled.Sensors,
                    title = if (state.hasSensorService) "No motion sensors" else "No sensor service",
                    message = if (state.hasSensorService) NO_SENSORS else NO_SERVICE,
                )
            }
            return@LazyColumn
        }

        item {
            AxisCard(
                title = "Angular velocity",
                subtitle = "Gyroscope · rad/s",
                icon = Icons.Filled.Speed,
                sensor = state.gyroscope,
                reading = state.frame.gyro,
                series = state.gyroSeries,
                accuracy = MotionFrame.accuracyLabel(state.frame.gyroAccuracy),
                floor = GYRO_FLOOR,
                modifier = padded,
            )
        }

        item {
            AxisCard(
                title = "Acceleration",
                subtitle = "Accelerometer · m/s², gravity included",
                icon = Icons.Filled.Timeline,
                sensor = state.accelerometer,
                reading = state.frame.acceleration,
                series = state.accelerationSeries,
                accuracy = MotionFrame.accuracyLabel(state.frame.accelerationAccuracy),
                floor = ACCELERATION_FLOOR,
                modifier = padded,
            )
        }

        item { OrientationCard(state = state, modifier = padded) }

        item { SamplingCard(state = state, onRate = viewModel::setRate, modifier = padded) }

        item { AnalysisCard(state = state, modifier = padded) }

        item {
            RecordingCard(
                state = state,
                onStart = viewModel::startRecording,
                onPause = viewModel::pauseRecording,
                onResume = viewModel::resumeRecording,
                onStop = viewModel::stopRecording,
                onClear = viewModel::clearRecording,
                onExport = viewModel::export,
                onShare = {
                    val intent = viewModel.shareIntent()
                    if (intent == null || !context.startIntentSafely(Intent.createChooser(intent, "Share"))) {
                        viewModel.onIntentFailed()
                    }
                },
                modifier = padded,
            )
        }

        item { SensorsCard(state = state, modifier = padded) }
    }
}

/**
 * One three-axis sensor: its current reading, its three traces and its own scale.
 *
 * The three plots share a range so the axes can be compared against each other — a Y wobble that is twice
 * the size of the X one should look twice the size. The floor keeps a still device from being scaled up
 * until sensor noise fills the card, which would look like the phone was shaking.
 */
@Composable
private fun AxisCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    sensor: Observed<SensorDescriptor>?,
    reading: Axis3?,
    series: AxisSeries,
    accuracy: String,
    floor: Float,
    modifier: Modifier = Modifier,
) {
    val unavailable = sensor?.unavailabilityText()
    SectionCard(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        icon = icon,
        action = { if (unavailable == null) StatusChip(accuracy, accuracyTone(accuracy)) },
    ) {
        if (unavailable != null) {
            Text(
                text = unavailable,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        StatStrip(
            entries = listOf(
                StatEntry("X", axisValue(reading?.x), Tone.Accent),
                StatEntry("Y", axisValue(reading?.y), Tone.Accent),
                StatEntry("Z", axisValue(reading?.z), Tone.Accent),
            ),
        )
        Spacer(modifier = Modifier.height(10.dp))

        val bound = max(series.extreme, floor)
        val range = -bound..bound
        AxisPlot(label = "X", values = series.x, range = range)
        AxisPlot(label = "Y", values = series.y, range = range)
        AxisPlot(label = "Z", values = series.z, range = range)

        Row(
            modifier = Modifier.padding(start = AXIS_LABEL_WIDTH.dp, top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "±${decimal(bound)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${series.size} of ${AxisSeries.CAPACITY} samples shown",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (reading == null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = AWAITING_SAMPLE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One axis of one sensor: a letter, then its line, drawn against the card's shared range. */
@Composable
private fun AxisPlot(
    label: String,
    values: List<Float>,
    range: ClosedFloatingPointRange<Float>,
) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(AXIS_LABEL_WIDTH.dp),
        )
        LineGraph(
            series = values,
            valueRange = range,
            graphHeight = AXIS_PLOT_HEIGHT,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Azimuth, pitch and roll, from the rotation vector.
 *
 * Not computed from the accelerometer: a phone being moved has acceleration that is not gravity, and the
 * tilt derived from it during a flick is wrong in exactly the moment this screen is being watched. Devices
 * without a rotation vector sensor get the reason instead of a derived number.
 */
@Composable
private fun OrientationCard(state: MotionUiState, modifier: Modifier = Modifier) {
    val unavailable = state.rotationVector?.unavailabilityText()
    SectionCard(
        title = "Orientation",
        modifier = modifier,
        subtitle = "Rotation vector · degrees",
        icon = Icons.Filled.Explore,
    ) {
        if (unavailable != null) {
            Text(
                text = unavailable,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        val orientation = state.frame.orientation
        StatStrip(
            entries = listOf(
                StatEntry("Azimuth", degrees(orientation?.azimuthDegrees)),
                StatEntry("Pitch", degrees(orientation?.pitchDegrees)),
                StatEntry("Roll", degrees(orientation?.rollDegrees)),
            ),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = ORIENTATION_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The requested rate, and the rate the platform is actually delivering against it. */
@Composable
private fun SamplingCard(
    state: MotionUiState,
    onRate: (SampleRate) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Sampling",
        modifier = modifier,
        subtitle = state.rate.detail,
        icon = Icons.Filled.Sensors,
    ) {
        ChoiceRow(
            options = SampleRate.entries.toList(),
            selected = state.rate,
            onSelect = onRate,
            label = { it.label },
            perRow = 4,
        )
        Spacer(modifier = Modifier.height(10.dp))
        StatStrip(
            entries = listOf(
                StatEntry(
                    label = "Delivered",
                    value = state.frame.deliveredHz?.let { Formatters.hertz(it) } ?: ABSENT,
                    tone = Tone.Accent,
                ),
                StatEntry("Samples", plain(state.frame.sampleCount)),
                StatEntry("Event clock", eventClock(state.frame.eventTimestampNanos)),
            ),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = SAMPLING_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The movement figures, with the caveat they need.
 *
 * Each of these is arithmetic over real samples: the degrees are integrated angular velocity, the intensity
 * is the magnitude of acceleration less one gravity, and the direction changes are sign changes in yaw
 * outside a dead zone. None of them knows where the player was aiming or whether they hit anything.
 */
@Composable
private fun AnalysisCard(state: MotionUiState, modifier: Modifier = Modifier) {
    val summary = state.summary
    SectionCard(
        title = "Motion analysis",
        modifier = modifier,
        subtitle = if (state.isRecording) "This recording" else "Since sampling started",
        icon = Icons.Filled.Analytics,
    ) {
        if (summary.isEmpty) {
            Text(
                text = ANALYSIS_EMPTY,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        StatStrip(
            entries = listOf(
                StatEntry("Horizontal", "${decimal(summary.horizontalDegrees)}°"),
                StatEntry("Vertical", "${decimal(summary.verticalDegrees)}°"),
                StatEntry("Turns", summary.directionChanges.toString()),
            ),
        )
        Spacer(modifier = Modifier.height(10.dp))
        StatStrip(
            entries = listOf(
                StatEntry("Avg rotation", "${decimal(summary.averageRotationDegreesPerSecond)}°/s"),
                StatEntry("Peak rotation", "${decimal(summary.peakRotationDegreesPerSecond)}°/s", Tone.Accent),
            ),
        )
        Spacer(modifier = Modifier.height(10.dp))
        StatStrip(
            entries = listOf(
                StatEntry("Avg intensity", "${decimal(summary.movementIntensity)} m/s²"),
                StatEntry("Peak intensity", "${decimal(summary.peakMovementIntensity)} m/s²", Tone.Accent),
            ),
        )
        Spacer(modifier = Modifier.height(10.dp))
        KeyValueRow(
            label = "Held still",
            value = Formatters.percent(summary.stillFraction),
            tone = Tone.Muted,
        )
        KeyValueRow(label = "Samples measured", value = plain(summary.samples), tone = Tone.Muted)
        Spacer(modifier = Modifier.height(8.dp))
        NoteBanner(text = NOT_ACCURACY, tone = Tone.Muted, icon = Icons.Filled.Info)
    }
}

/**
 * Start, pause, stop, clear — and the export that only exists once there is something to export.
 *
 * The buffer meter is not decoration. The trace is bounded, and a long recording that quietly stopped
 * keeping samples would produce an export that looks complete and is not, so the fill is shown while it
 * fills and the dropped count is shown if the ceiling is ever reached.
 */
@Composable
private fun RecordingCard(
    state: MotionUiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onExport: (DiagnosticsFormat) -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Recording",
        modifier = modifier,
        subtitle = "Raw telemetry, kept on this device",
        icon = Icons.Filled.FiberManualRecord,
        action = {
            when {
                state.isRecording -> StatusChip("Recording", Tone.Danger)
                state.isPaused -> StatusChip("Paused", Tone.Warning)
                state.isStopped -> StatusChip("Stopped", Tone.Accent)
                else -> Unit
            }
        },
    ) {
        ActionRow {
            when {
                state.isRecording -> {
                    Button(onClick = onPause) { Text("Pause") }
                    OutlinedButton(onClick = onStop) { Text("Stop") }
                }
                state.isPaused -> {
                    Button(onClick = onResume) { Text("Resume") }
                    OutlinedButton(onClick = onStop) { Text("Stop") }
                }
                else -> {
                    Button(onClick = onStart) { Text(if (state.isStopped) "Record again" else "Record") }
                    if (state.hasTrace) OutlinedButton(onClick = onClear) { Text("Clear") }
                }
            }
        }

        if (state.hasTrace || state.isRecording) {
            Spacer(modifier = Modifier.height(10.dp))
            KeyValueRow(
                label = "Raw samples kept",
                value = "${plain(state.recordedSamples.toLong())} of " +
                    plain(MotionTrace.DEFAULT_CAPACITY.toLong()),
                tone = if (state.isTraceFull) Tone.Warning else Tone.Neutral,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Meter(
                fraction = state.traceFraction,
                tone = if (state.isTraceFull) Tone.Warning else Tone.Accent,
            )
            if (state.isTraceFull) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = TRACE_FULL,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tone.Warning.colour(),
                )
            }
        }

        if (state.isStopped && !state.summary.isEmpty) {
            Spacer(modifier = Modifier.height(10.dp))
            RowDivider()
            Spacer(modifier = Modifier.height(10.dp))
            SessionSummary(summary = state.summary)
        }

        if (state.hasTrace) {
            Spacer(modifier = Modifier.height(10.dp))
            RowDivider()
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "EXPORT",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            ActionRow {
                OutlinedButton(onClick = { onExport(DiagnosticsFormat.JSON) }, enabled = !state.isExporting) {
                    Text("JSON")
                }
                OutlinedButton(onClick = { onExport(DiagnosticsFormat.CSV) }, enabled = !state.isExporting) {
                    Text("CSV")
                }
                OutlinedButton(onClick = { onExport(DiagnosticsFormat.TEXT) }, enabled = !state.isExporting) {
                    Text("Text")
                }
            }
            state.lastExport?.let { written -> ExportResult(written = written, onShare = onShare) }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = EXPORT_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What one finished recording came to. The same figures as the live card, over the session only. */
@Composable
private fun SessionSummary(summary: MotionSummary) {
    Text(
        text = "SESSION",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(6.dp))
    StatStrip(
        entries = listOf(
            StatEntry("Length", Formatters.duration(summary.durationMillis)),
            StatEntry("Samples", plain(summary.samples)),
            StatEntry("Turns", summary.directionChanges.toString()),
        ),
    )
    if (summary.droppedSamples > 0L) {
        Spacer(modifier = Modifier.height(6.dp))
        KeyValueRow(
            label = "Samples dropped",
            value = plain(summary.droppedSamples),
            tone = Tone.Warning,
        )
    }
}

/** The file that was just written, and the one way it leaves this device: the user's own share sheet. */
@Composable
private fun ExportResult(written: DiagnosticsExport.Written, onShare: () -> Unit) {
    Spacer(modifier = Modifier.height(10.dp))
    KeyValueRow(label = written.fileName, value = Formatters.bytes(written.sizeBytes), tone = Tone.Good)
    KeyValueRow(
        label = "Rows written",
        value = plain(written.recordCount.toLong()),
        tone = Tone.Muted,
    )
    if (written.uri != null) {
        Spacer(modifier = Modifier.height(6.dp))
        ActionRow {
            OutlinedButton(onClick = onShare) { Text("Share") }
        }
    }
}

/** Every motion sensor this device declares, exactly as `Sensor` describes it. */
@Composable
private fun SensorsCard(state: MotionUiState, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Sensors",
        modifier = modifier,
        subtitle = "As reported by SensorManager",
        icon = Icons.Filled.Memory,
    ) {
        state.sensors.forEachIndexed { index, (kind, observed) ->
            if (index > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                RowDivider()
                Spacer(modifier = Modifier.height(10.dp))
            }
            Text(
                text = kind.label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            when (observed) {
                is Observed.Value -> SensorDetail(observed.value, kind.unit)
                else -> Text(
                    text = observed.unavailabilityText() ?: ABSENT,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SensorDetail(descriptor: SensorDescriptor, unit: String) {
    Column {
        KeyValueRow(label = "Name", value = descriptor.name)
        KeyValueRow(label = "Vendor", value = descriptor.vendor)
        KeyValueRow(label = "Version", value = descriptor.version.toString())
        KeyValueRow(label = "Maximum range", value = "${decimal(descriptor.maximumRange)} $unit")
        KeyValueRow(label = "Resolution", value = "${fine(descriptor.resolution)} $unit")
        KeyValueRow(
            label = "Fastest rate",
            value = descriptor.maximumHz?.let { Formatters.hertz(it) } ?: ABSENT,
        )
        KeyValueRow(label = "Minimum delay", value = micros(descriptor.minDelayMicros))
        KeyValueRow(label = "Maximum delay", value = micros(descriptor.maxDelayMicros))
        KeyValueRow(label = "Power", value = "${decimal(descriptor.powerMilliAmp)} mA")
        KeyValueRow(label = "Reporting", value = descriptor.reportingMode)
        KeyValueRow(label = "Wake-up sensor", value = if (descriptor.isWakeUp) "Yes" else "No")
    }
}

// ------------------------------------------------------------------------------------ formatting

private fun accuracyTone(accuracy: String): Tone = when (accuracy) {
    "High" -> Tone.Good
    "Medium" -> Tone.Neutral
    "Low" -> Tone.Warning
    "Unreliable" -> Tone.Danger
    else -> Tone.Muted
}

private fun axisValue(reading: Float?): String =
    if (reading == null) ABSENT else String.format(Locale.US, "%+.3f", reading)

private fun degrees(reading: Float?): String =
    if (reading == null) ABSENT else String.format(Locale.US, "%.1f°", reading)

private fun decimal(value: Float): String = String.format(Locale.US, "%.2f", value)

private fun fine(value: Float): String = String.format(Locale.US, "%.5f", value)

/** A plain grouped count. `Formatters.count` wants a noun; these are bare sample totals. */
private fun plain(value: Long): String = String.format(Locale.US, "%,d", value)

private fun micros(value: Int): String = when {
    value <= 0 -> ABSENT
    value >= 1_000 -> "${value / 1_000} ms"
    else -> "$value µs"
}

/** The sensor's own event clock, in seconds since whatever it counts from. Not a wall clock. */
private fun eventClock(nanos: Long): String =
    if (nanos <= 0L) ABSENT else String.format(Locale.US, "%.1f s", nanos / 1_000_000_000.0)

private const val AXIS_LABEL_WIDTH = 18

private const val AXIS_PLOT_HEIGHT = 54

/** A still gyroscope reads in the thousandths; without a floor the plot would scale up to its noise. */
private const val GYRO_FLOOR = 0.5f

/** A phone at rest reads one gravity on one axis, so the acceleration plot starts at ±10 m/s². */
private const val ACCELERATION_FLOOR = 10f

private const val NO_SENSORS =
    "This device reports no gyroscope, accelerometer or rotation vector to Android, so there is nothing " +
        "for this screen to read. GameCore cannot add a sensor that is not there."

private const val NO_SERVICE =
    "Android's sensor service is not available to GameCore on this device. Nothing here can be read " +
        "without it."

private const val AWAITING_SAMPLE =
    "The sensor is registered and has not delivered a sample yet."

private const val ORIENTATION_NOTE =
    "Azimuth is compass heading and needs a magnetometer to mean anything absolute; pitch and roll are " +
        "relative to the ground. These come from the rotation vector sensor rather than being derived " +
        "from acceleration, which is wrong exactly while the device is being moved."

private const val SAMPLING_NOTE =
    "The rate is a request. Android may deliver slower to save power, or faster because another app asked " +
        "for more, so the delivered figure is measured here rather than copied from the request. The " +
        "event clock is the sensor's own timestamp and does not start at any particular moment."

private const val ANALYSIS_EMPTY =
    "Nothing measured yet. Move the device and the figures fill in."

private const val NOT_ACCURACY =
    "These are sensor measurements of how the device moved, and nothing more. They are not a measure of " +
        "aim, accuracy or skill — Android gives an app no access to what is on screen or to where a shot " +
        "landed, and a number claiming otherwise would be invented."

private const val TRACE_FULL =
    "The raw buffer is full. Later samples are still measured in the figures above but are no longer kept " +
        "for export; the dropped count is in the session summary."

private const val EXPORT_NOTE =
    "The file is written inside GameCore's own storage. Nothing is uploaded, and no part of this recording " +
        "leaves the device unless you share it yourself."
