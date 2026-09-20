package com.gamecore.ui.controller

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.unavailabilityText
import com.gamecore.core.input.ControllerAxis
import com.gamecore.core.input.ControllerDevice
import com.gamecore.core.input.ControllerEventKind
import com.gamecore.core.input.ControllerInputSnapshot
import com.gamecore.core.input.ControllerReader
import com.gamecore.data.repository.DiagnosticsExport
import com.gamecore.data.repository.DiagnosticsFormat
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceChip
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.KeyValueRow
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
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.components.startIntentSafely
import java.util.Locale

/**
 * Controller lab: what is attached, and what it is doing.
 *
 * Everything on this screen is either `InputDevice` describing the hardware or an event Android dispatched
 * to this activity. There is no polling of a controller behind Android's back, no global interception, and
 * no latency figure — the platform timestamps an event when the framework receives it, which already
 * contains the radio and the driver, so a millisecond number here would describe this process and not the
 * controller. The lab says that rather than printing one.
 *
 * Input is claimed only while this screen is being collected, and never for the navigation keys, so a
 * controller behaves exactly as it always did everywhere else in GameCore.
 */
@Composable
fun ControllerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ControllerViewModel = hiltViewModel(),
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
                title = "Controller lab",
                subtitle = "Attached controllers, their axes, and the input they are sending",
                onBack = onBack,
                action = {
                    if (state.hasControllers) {
                        StatusChip(
                            text = if (state.hasInput) "Listening" else "Connected",
                            tone = if (state.hasInput) Tone.Good else Tone.Accent,
                            icon = Icons.Filled.SportsEsports,
                        )
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

        item {
            AttachedCard(
                state = state,
                onRefresh = viewModel::refresh,
                onShowAll = viewModel::setShowAll,
                onSelect = viewModel::select,
                modifier = padded,
            )
        }

        val device = state.selected
        if (device == null) {
            item {
                EmptyState(
                    icon = Icons.Filled.SportsEsports,
                    title = if (state.hasInputService) "No controller attached" else "No input service",
                    message = when {
                        !state.hasInputService -> NO_SERVICE
                        state.readFailed -> state.devices?.unavailabilityText() ?: NO_CONTROLLERS
                        else -> NO_CONTROLLERS
                    },
                )
            }
            item { NoteBanner(text = SCOPE_NOTE, tone = Tone.Muted, icon = Icons.Filled.Info, modifier = padded) }
            return@LazyColumn
        }

        item { DeviceCard(device = device, modifier = padded) }

        item {
            LiveCard(
                device = device,
                snapshot = state.snapshot,
                tool = state.tool,
                onTool = viewModel::setTool,
                onClear = viewModel::clearInput,
                modifier = padded,
            )
        }

        item {
            VibrationCard(
                device = device,
                onTest = { viewModel.testVibration(device.deviceId) },
                modifier = padded,
            )
        }

        item {
            ExportCard(
                state = state,
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

        item { NoteBanner(text = NO_LATENCY, tone = Tone.Muted, icon = Icons.Filled.Info, modifier = padded) }
    }
}

/** Everything attached, and which one the rest of the screen is about. */
@Composable
private fun AttachedCard(
    state: ControllerUiState,
    onRefresh: () -> Unit,
    onShowAll: (Boolean) -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Attached",
        modifier = modifier,
        subtitle = "From InputDevice, updated when Android reports a change",
        icon = Icons.Filled.Memory,
        action = { TextButton(onClick = onRefresh) { Text("Refresh") } },
    ) {
        val unavailable = state.devices?.unavailabilityText()
        if (unavailable != null) {
            Text(
                text = unavailable,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
        } else {
            KeyValueRow(
                label = if (state.showAll) "Input devices" else "Controllers",
                value = state.controllers.size.toString(),
                tone = if (state.hasControllers) Tone.Good else Tone.Muted,
            )
        }

        if (state.controllers.size > 1) {
            Spacer(modifier = Modifier.height(10.dp))
            ChoiceRow(
                options = state.controllers,
                selected = state.selected,
                onSelect = { onSelect(it.deviceId) },
                label = { it.name.ifBlank { "Device ${it.deviceId}" } },
                perRow = 2,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        SwitchRow(
            title = "Show every input device",
            checked = state.showAll,
            onCheckedChange = onShowAll,
            description = SHOW_ALL_NOTE,
        )
    }
}

/** The hardware, exactly as `InputDevice` described it. Nothing here is inferred from the name. */
@Composable
private fun DeviceCard(device: ControllerDevice, modifier: Modifier = Modifier) {
    SectionCard(
        title = device.name.ifBlank { "Device ${device.deviceId}" },
        modifier = modifier,
        subtitle = device.classification,
        icon = Icons.Filled.SportsEsports,
        action = { if (device.isVirtual) StatusChip("Virtual", Tone.Warning) },
    ) {
        StatStrip(
            entries = listOf(
                StatEntry("Axes", device.axes.size.toString(), Tone.Accent),
                StatEntry("Buttons", device.buttons.size.toString(), Tone.Accent),
                StatEntry("Player", if (device.controllerNumber > 0) device.controllerNumber.toString() else ABSENT),
            ),
        )
        Spacer(modifier = Modifier.height(10.dp))
        KeyValueRow(label = "Device ID", value = device.deviceId.toString())
        KeyValueRow(label = "Vendor ID", value = device.vendorHex)
        KeyValueRow(label = "Product ID", value = device.productHex)
        KeyValueRow(
            label = "Sources",
            value = device.sources.joinToString(", ").ifBlank { ABSENT },
        )
        KeyValueRow(label = "Keyboard", value = device.keyboardType)
        KeyValueRow(
            label = "Vibration",
            value = if (device.vibration.available) "Available" else "Not reported",
            tone = if (device.vibration.available) Tone.Good else Tone.Muted,
        )
        if (device.descriptor.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Descriptor ${device.descriptor}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (device.buttons.size < ControllerReader.GAMEPAD_KEYS.size) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = BUTTON_LIST_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The live half: one tester at a time, over the events the activity actually received. */
@Composable
private fun LiveCard(
    device: ControllerDevice,
    snapshot: ControllerInputSnapshot,
    tool: ControllerTool,
    onTool: (ControllerTool) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Live input",
        modifier = modifier,
        subtitle = "Events dispatched to GameCore while this screen is open",
        icon = Icons.Filled.Tune,
        action = { if (snapshot.hasActivity) TextButton(onClick = onClear) { Text("Clear") } },
    ) {
        StatStrip(
            entries = listOf(
                StatEntry("Buttons", plain(snapshot.keyEventCount)),
                StatEntry("Motion", plain(snapshot.motionEventCount)),
                StatEntry("Held", snapshot.heldKeys.size.toString(), Tone.Accent),
            ),
        )
        Spacer(modifier = Modifier.height(10.dp))
        ChoiceRow(
            options = ControllerTool.entries.toList(),
            selected = tool,
            onSelect = onTool,
            label = { it.label },
            perRow = 3,
        )
        Spacer(modifier = Modifier.height(12.dp))

        when (tool) {
            ControllerTool.STICKS -> StickTester(device = device, snapshot = snapshot)
            ControllerTool.TRIGGERS -> TriggerTester(device = device, snapshot = snapshot)
            ControllerTool.BUTTONS -> ButtonTester(device = device, snapshot = snapshot)
            ControllerTool.AXES -> AxisTester(device = device, snapshot = snapshot)
            ControllerTool.EVENTS -> EventLog(snapshot = snapshot)
        }

        if (!snapshot.hasActivity) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = AWAITING_INPUT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The sticks, drawn on their own axes with the driver's declared dead zone around centre.
 *
 * The dead-zone ring is `MotionRange.getFlat()` — the controller's own statement about how far from centre
 * a reading can be while still meaning centre. It is drawn rather than described because a stick that
 * rests outside its own flat zone is the thing a user is looking for, and a number does not show that.
 */
@Composable
private fun StickTester(device: ControllerDevice, snapshot: ControllerInputSnapshot) {
    var drawn = 0
    StickSlot.entries.forEach { slot ->
        val xAxis = device.axisFor(slot.xAxis)
        val yAxis = device.axisFor(slot.yAxis)
        if (xAxis == null || yAxis == null) return@forEach
        if (drawn > 0) {
            Spacer(modifier = Modifier.height(12.dp))
            RowDivider()
            Spacer(modifier = Modifier.height(12.dp))
        }
        drawn++

        val xValue = snapshot.axis(slot.xAxis)
        val yValue = snapshot.axis(slot.yAxis)
        Text(
            text = slot.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StickPad(
                x = centred(xAxis, xValue),
                y = centred(yAxis, yValue),
                deadZone = deadZoneFraction(xAxis),
                reported = snapshot.reported(slot.xAxis) || snapshot.reported(slot.yAxis),
                modifier = Modifier.width(STICK_SIZE.dp),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                KeyValueRow(label = "X", value = signed(xValue), tone = Tone.Accent)
                KeyValueRow(label = "Y", value = signed(yValue), tone = Tone.Accent)
                KeyValueRow(label = "Peak", value = signed(maxOf(snapshot.peak(slot.xAxis), snapshot.peak(slot.yAxis))))
                KeyValueRow(label = "Range", value = xAxis.range, tone = Tone.Muted)
                KeyValueRow(label = "Dead zone", value = decimal(xAxis.flat), tone = Tone.Muted)
            }
        }
    }

    if (drawn == 0) {
        Text(
            text = NO_STICKS,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One stick: its own square, its dead-zone ring, and where it is sitting. */
@Composable
private fun StickPad(
    x: Float,
    y: Float,
    deadZone: Float,
    reported: Boolean,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(surface),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f - 6f
            drawLine(outline, Offset(0f, center.y), Offset(size.width, center.y), strokeWidth = 1f)
            drawLine(outline, Offset(center.x, 0f), Offset(center.x, size.height), strokeWidth = 1f)
            drawCircle(color = outline, radius = radius, style = Stroke(width = 2f))
            if (deadZone > 0f) {
                drawCircle(
                    color = muted.copy(alpha = 0.45f),
                    radius = radius * deadZone.coerceIn(0f, 1f),
                    style = Stroke(width = 1f),
                )
            }
            if (reported) {
                drawCircle(
                    color = accent,
                    radius = 6.dp.toPx(),
                    center = Offset(center.x + x * radius, center.y + y * radius),
                )
            }
        }
        if (!reported) {
            Text(
                text = "no data",
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * The analog triggers, on whichever axis this controller reports them.
 *
 * Both conventional pairs are checked, and the one that was found is named. A controller with neither is
 * shown as having neither rather than as two triggers resting at zero.
 */
@Composable
private fun TriggerTester(device: ControllerDevice, snapshot: ControllerInputSnapshot) {
    TriggerSlot.entries.forEachIndexed { index, slot ->
        if (index > 0) Spacer(modifier = Modifier.height(14.dp))
        val axis = device.axisFor(slot.axis) ?: device.axisFor(slot.alternate)
        Text(
            text = slot.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (axis == null) {
            Text(
                text = NO_TRIGGER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@forEachIndexed
        }
        val value = snapshot.axis(axis.axis)
        Text(
            text = decimal(value),
            style = MaterialTheme.typography.headlineSmall,
            color = Tone.Accent.colour(),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Meter(fraction = axis.fraction(value), tone = Tone.Accent)
        Spacer(modifier = Modifier.height(6.dp))
        KeyValueRow(label = "Axis", value = axis.label, tone = Tone.Muted)
        KeyValueRow(label = "Range", value = axis.range, tone = Tone.Muted)
        KeyValueRow(label = "Peak", value = decimal(snapshot.peak(axis.axis)), tone = Tone.Muted)
    }
}

/**
 * The button tester: every key this controller declares, lit while it is held.
 *
 * The list comes from `InputDevice.hasKeys`, so a pad without a Mode button simply has no Mode chip. Back,
 * Home, the volume keys and the recents key are never consumed by the lab even when a controller sends
 * them, so they are not shown here — a chip that could never light would be a lie about the hardware.
 */
@Composable
private fun ButtonTester(device: ControllerDevice, snapshot: ControllerInputSnapshot) {
    if (device.buttons.isEmpty()) {
        Text(
            text = NO_BUTTONS,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    device.buttons.chunked(BUTTONS_PER_ROW).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.forEach { code ->
                ChoiceChip(
                    text = buttonLabel(code),
                    isSelected = snapshot.isPressed(code),
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(BUTTONS_PER_ROW - row.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }

    val pressed = device.buttons.filter { snapshot.presses(it) > 0 }
    if (pressed.isEmpty()) return
    Spacer(modifier = Modifier.height(4.dp))
    RowDivider()
    Spacer(modifier = Modifier.height(10.dp))
    Text(
        text = "PRESSES",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(4.dp))
    pressed.forEach { code ->
        KeyValueRow(
            label = buttonLabel(code),
            value = snapshot.presses(code).toString(),
            tone = if (snapshot.isPressed(code)) Tone.Good else Tone.Neutral,
        )
    }
}

/** Every axis the controller declares, with its live value against its own declared range. */
@Composable
private fun AxisTester(device: ControllerDevice, snapshot: ControllerInputSnapshot) {
    if (device.axes.isEmpty()) {
        Text(
            text = NO_AXES,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    device.axes.forEachIndexed { index, axis ->
        if (index > 0) Spacer(modifier = Modifier.height(10.dp))
        val seen = snapshot.reported(axis.axis)
        val value = snapshot.axis(axis.axis)
        KeyValueRow(
            label = axis.label,
            value = if (seen) signed(value) else "not reported yet",
            tone = if (seen) Tone.Accent else Tone.Muted,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Meter(fraction = axis.fraction(value), tone = if (seen) Tone.Accent else Tone.Muted)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "range ${axis.range} · flat ${decimal(axis.flat)} · fuzz ${decimal(axis.fuzz)} · " +
                "peak ${decimal(snapshot.peak(axis.axis))}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The last few button events, newest first, with the platform's own key names. */
@Composable
private fun EventLog(snapshot: ControllerInputSnapshot) {
    if (snapshot.recentEvents.isEmpty()) {
        Text(
            text = NO_EVENTS,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    snapshot.recentEvents.take(EVENT_LIMIT).forEach { event ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = event.kind.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (event.kind == ControllerEventKind.DOWN) Tone.Good.colour() else Tone.Muted.colour(),
                modifier = Modifier.width(44.dp),
            )
            Text(
                text = event.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "#${event.keyCode}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Rumble, using the controller's own motor or nothing at all. */
@Composable
private fun VibrationCard(
    device: ControllerDevice,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Vibration",
        modifier = modifier,
        subtitle = "The controller's motor, not the phone's",
        icon = Icons.Filled.Bolt,
        action = {
            StatusChip(
                text = if (device.vibration.available) "AVAILABLE" else "NOT SUPPORTED",
                tone = if (device.vibration.available) Tone.Good else Tone.Muted,
            )
        },
    ) {
        if (!device.vibration.available) {
            Text(
                text = NO_VIBRATION,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        KeyValueRow(label = "Motors reported", value = device.vibration.vibratorCount.toString())
        device.vibration.detail?.let { detail ->
            KeyValueRow(label = "Source", value = detail, tone = Tone.Muted)
        }
        Spacer(modifier = Modifier.height(10.dp))
        ActionRow {
            OutlinedButton(onClick = onTest) { Text("Test rumble") }
        }
    }
}

/** The capability report, written into GameCore's own storage and shared only if the user shares it. */
@Composable
private fun ExportCard(
    state: ControllerUiState,
    onExport: (DiagnosticsFormat) -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Export",
        modifier = modifier,
        subtitle = "Every attached controller and its declared capabilities",
        icon = Icons.Filled.GridView,
    ) {
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
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = EXPORT_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExportResult(written: DiagnosticsExport.Written, onShare: () -> Unit) {
    Spacer(modifier = Modifier.height(10.dp))
    KeyValueRow(label = written.fileName, value = Formatters.bytes(written.sizeBytes), tone = Tone.Good)
    KeyValueRow(label = "Devices written", value = written.recordCount.toString(), tone = Tone.Muted)
    if (written.uri != null) {
        Spacer(modifier = Modifier.height(6.dp))
        ActionRow {
            OutlinedButton(onClick = onShare) { Text("Share") }
        }
    }
}

// ------------------------------------------------------------------------------------ formatting

/** Where a reading sits in its own axis range, mapped to -1..1 so the pad can draw it. */
private fun centred(axis: ControllerAxis, value: Float): Float = axis.fraction(value) * 2f - 1f

/** The declared dead zone as a share of half the axis range, which is what the ring is drawn against. */
private fun deadZoneFraction(axis: ControllerAxis): Float {
    val half = (axis.maximum - axis.minimum) / 2f
    if (half <= 0f) return 0f
    return (axis.flat / half).coerceIn(0f, 1f)
}

private fun signed(value: Float): String = String.format(Locale.US, "%+.2f", value)

private fun decimal(value: Float): String = String.format(Locale.US, "%.2f", value)

private fun plain(value: Long): String = String.format(Locale.US, "%,d", value)

/** `BUTTON_A` reads better as `A` on a chip; anything else keeps the platform's own name. */
private fun buttonLabel(keyCode: Int): String =
    ControllerReader.keyLabel(keyCode).removePrefix("BUTTON_").removePrefix("DPAD_")

private const val STICK_SIZE = 108

private const val BUTTONS_PER_ROW = 3

private const val EVENT_LIMIT = 24

private const val NO_SERVICE =
    "Android's input service is not available to GameCore on this device, so no controller can be read."

private const val NO_CONTROLLERS =
    "Android reports no gamepad, joystick or D-pad attached. Connect a controller over USB or Bluetooth " +
        "and it appears here — GameCore is told about a controller only once the system has paired it."

private const val SCOPE_NOTE =
    "A controller's input reaches an app only through the focused window. GameCore can read the controller " +
        "while this screen is open, and cannot see what a controller does inside another game — Android " +
        "does not offer that to any normal app."

private const val SHOW_ALL_NOTE =
    "Includes keyboards, touchscreens and virtual devices, which are input devices but not controllers."

private const val BUTTON_LIST_NOTE =
    "Buttons come from InputDevice.hasKeys, which answers for a fixed list of gamepad keycodes. A button " +
        "this controller maps to something outside that list is not shown as missing — it was not asked about."

private const val AWAITING_INPUT =
    "No controller events yet. Press a button or move a stick with this screen open."

private const val NO_STICKS =
    "This controller declares no stick axes to Android. A D-pad-only pad reports its directions as buttons " +
        "instead — they are in the Buttons tester."

private const val NO_TRIGGER =
    "Not reported by this controller. Android exposes triggers as LTRIGGER/RTRIGGER or BRAKE/GAS; this " +
        "device declares neither, so there is no analog value to read."

private const val NO_BUTTONS =
    "This device declares none of the gamepad keycodes Android can be asked about."

private const val NO_AXES =
    "This device declares no motion axes."

private const val NO_EVENTS =
    "No button events recorded yet."

private const val NO_VIBRATION =
    "This controller reports no vibrator to Android. Some pads drive their motors over a vendor protocol " +
        "that Android does not expose, and GameCore will not buzz the phone instead and call it a rumble test."

private const val EXPORT_NOTE =
    "The file is written inside GameCore's own storage. Nothing is uploaded, and the report leaves this " +
        "device only if you share it yourself."

private const val NO_LATENCY =
    "GameCore does not show controller latency. Android timestamps an input event when the framework " +
        "receives it, which already includes the radio, the driver and the input pipeline, and gives no " +
        "access to the moment the button was physically pressed. A millisecond figure derived from those " +
        "timestamps would describe queueing inside this app, so none is shown."
