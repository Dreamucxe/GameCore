package com.gamecore.ui.performance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.unavailabilityText
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.OptimizationAction
import com.gamecore.core.model.OptimizationResult
import com.gamecore.core.model.PerformanceMode
import com.gamecore.core.model.RefreshRateMechanism
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.GraphCard
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.Readout
import com.gamecore.ui.components.ReadoutRow
import com.gamecore.ui.components.ReadoutTile
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SliderRow
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.TileRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.capabilityTone
import com.gamecore.ui.components.readoutOf
import kotlin.math.abs

/**
 * The whole device, measured, and the small set of changes a user can make to it without a game profile.
 *
 * The two collections at the top have different lifetimes on purpose. [PerformanceViewModel.live] is
 * cold: collecting it here is what starts the sampling loop, and leaving this screen is what stops it.
 * That matters more here than anywhere else in the app — this is the page about heat and battery, and a
 * page about battery that keeps a sensor loop running behind a backgrounded app would be the joke that
 * writes itself.
 *
 * Order: anything that needs an answer first, then the figures, then the controls. A user who opened this
 * screen because their phone is hot reads the thermal banner before they scroll.
 */
@Composable
fun PerformanceScreen(
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PerformanceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle(PerformanceLive.AWAITING)

    // The user may have come back from granting WRITE_SETTINGS or starting Shizuku.
    OnResume { viewModel.onResume() }

    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Performance",
                subtitle = subtitleFor(state),
                action = {
                    TextButton(onClick = viewModel::recheck) { Text("Re-check") }
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

        live.thermalNote?.let { note ->
            item {
                NoteBanner(
                    text = note,
                    tone = Tone.Warning,
                    icon = Icons.Filled.Thermostat,
                    modifier = padded,
                )
            }
        }

        if (state.results.isNotEmpty()) {
            item {
                ResultsCard(
                    results = state.results,
                    onDismiss = viewModel::dismissResults,
                    onNavigate = onNavigate,
                    modifier = padded,
                )
            }
        }

        live.tiles.chunked(TILES_PER_ROW).forEach { row ->
            item {
                TileRow(modifier = padded) {
                    row.forEach { readout ->
                        ReadoutTile(
                            readout = readout,
                            modifier = Modifier.weight(1f),
                            icon = tileIcon(readout.label),
                        )
                    }
                    repeat(TILES_PER_ROW - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }

        item {
            GraphCard(
                title = "Load",
                series = live.cpuSeries,
                modifier = padded,
                seriesLabel = "CPU",
                secondary = live.memorySeries,
                secondaryLabel = "RAM",
                format = { "${it.toInt()}%" },
            )
        }

        item {
            GraphCard(
                title = "Temperature",
                series = live.temperatureSeries,
                modifier = padded,
                valueRange = GRAPH_MIN_CELSIUS..GRAPH_MAX_CELSIUS,
                format = { "${it.toInt()}°C" },
                emptyMessage = "Collecting samples. A device that exposes no temperature sensor " +
                    "shows nothing here at all — the cards below say which it is.",
            )
        }

        item {
            FrameRateCard(
                state = state,
                live = live,
                onNavigate = onNavigate,
                modifier = padded,
            )
        }

        // Only when something real was measured. An empty graph here would be a promise the platform
        // does not keep on most devices.
        if (live.frameRateRows.isNotEmpty()) {
            item {
                GraphCard(
                    title = "Frames per second",
                    series = live.frameRateSeries,
                    modifier = padded,
                    valueRange = 0f..FRAME_GRAPH_MAX_FPS,
                    format = { "${it.toInt()}" },
                )
            }
        }

        item {
            RowsCard(
                title = "Processor",
                icon = Icons.Filled.Memory,
                rows = live.cpuRows,
                extraRows = live.coreRows,
                extraTitle = "Per core",
                modifier = padded,
            )
        }

        item {
            RowsCard(
                title = "Battery",
                icon = Icons.Filled.BatteryFull,
                rows = live.batteryRows,
                modifier = padded,
            )
        }

        item {
            RowsCard(
                title = "Thermal",
                icon = Icons.Filled.Thermostat,
                rows = live.thermalRows,
                subtitle = "GameCore reads these. Nothing here can be written — the thermal " +
                    "protection keeping the device from damaging itself is not ours to switch off.",
                modifier = padded,
            )
        }

        item {
            NetworkCard(
                state = state,
                live = live,
                onMeasure = viewModel::measureStability,
                modifier = padded,
            )
        }

        item {
            RefreshRateCard(
                state = state,
                live = live,
                onPin = viewModel::pinRefreshRate,
                onRelease = viewModel::releaseRefreshRate,
                onNavigate = onNavigate,
                modifier = padded,
            )
        }

        item {
            ModeCard(
                state = state,
                onApplyMode = viewModel::applyMode,
                modifier = padded,
            )
        }

        item {
            OptimizationsCard(
                state = state,
                onApply = viewModel::apply,
                onRestore = viewModel::restore,
                onNavigate = onNavigate,
                modifier = padded,
            )
        }

        item {
            SamplingCard(
                state = state,
                onInterval = viewModel::setSampleIntervalSeconds,
                onLatency = viewModel::setMeasureLatency,
                onBackground = viewModel::setBackgroundMonitoring,
                onClearGraphs = viewModel::clearGraphs,
                modifier = padded,
            )
        }
    }
}

private fun subtitleFor(state: PerformanceUiState): String = when {
    state.watchedGameLabel.isNotEmpty() -> "Watching ${state.watchedGameLabel}"
    else -> "Sampled every ${Formatters.duration(state.sampleIntervalMillis)} while this screen is open"
}

/** A glyph per tile. Decoration, so an unrecognised label simply gets none. */
private fun tileIcon(label: String): ImageVector? = when (label) {
    PerformanceLabels.CPU -> Icons.Filled.Memory
    PerformanceLabels.MEMORY -> Icons.Filled.DataUsage
    PerformanceLabels.TEMPERATURE -> Icons.Filled.Thermostat
    PerformanceLabels.BATTERY -> Icons.Filled.BatteryFull
    else -> null
}

private const val TILES_PER_ROW = 2
private const val GRAPH_MIN_CELSIUS = 20f
private const val GRAPH_MAX_CELSIUS = 60f

/**
 * A card of [ReadoutRow]s, which is most of this screen.
 *
 * Every row arrives already formatted, with its own absence reason where there is no figure, so this
 * composable has no branch in it about whether a reading exists — §24A.2 in the shape it takes in
 * practice. [extraRows] is the per-core subsection, which is the one list whose length depends on the
 * device.
 */
@Composable
private fun RowsCard(
    title: String,
    icon: ImageVector,
    rows: List<Readout>,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    extraRows: List<Readout> = emptyList(),
    extraTitle: String? = null,
) {
    if (rows.isEmpty()) return
    SectionCard(title = title, subtitle = subtitle, icon = icon, modifier = modifier) {
        rows.forEach { ReadoutRow(it) }
        if (extraRows.isNotEmpty() && extraTitle != null) {
            RowDivider()
            Text(
                text = extraTitle,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )
            extraRows.forEach { ReadoutRow(it) }
        }
    }
}

/**
 * The one card in this app that most often has no number in it, by design.
 *
 * §24B: reliable per-frame timing is not available to an ordinary Android app. GameCore reads it from the
 * platform's own profiler through Shizuku, for games that draw through the UI pipeline, and where that is
 * not possible this card prints the reason instead of a figure. There is deliberately no path here from
 * "no frame data" to a number — not the display's refresh rate, not the overlay's own composition rate,
 * not an estimate from CPU load. Those are the three things every fake FPS meter shows.
 */
@Composable
private fun FrameRateCard(
    state: PerformanceUiState,
    live: PerformanceLive,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measuring = live.frameRateRows.isNotEmpty()
    SectionCard(
        title = "Frame rate",
        subtitle = state.watchedGameLabel.ifEmpty { null },
        icon = Icons.Filled.Speed,
        modifier = modifier,
        action = {
            StatusChip(
                text = if (measuring) "Measured" else "Not available",
                tone = if (measuring) Tone.Good else Tone.Muted,
            )
        },
    ) {
        Text(
            text = state.frameRate.explanation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (measuring) {
            live.frameRateRows.forEach { ReadoutRow(it) }
        } else {
            live.frameRateNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (!state.capabilities.hasElevatedAccess) {
                ActionRow {
                    TextButton(onClick = { onNavigate(Destination.Shizuku) }) {
                        Text("What Shizuku would add")
                    }
                }
            }
        }
    }
}

/**
 * The network figures, plus the burst that produces the jitter reading.
 *
 * "Measure again" is a button rather than a loop because a stability window is five real TCP handshakes
 * 200 ms apart: worth sending when the user asks, and not worth sending every two seconds behind a screen
 * they left open. The warning sentence comes from [com.gamecore.core.model.ConnectionStability] itself,
 * which is careful to call a failed probe a failed probe rather than packet loss.
 */
@Composable
private fun NetworkCard(
    state: PerformanceUiState,
    live: PerformanceLive,
    onMeasure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Network",
        icon = Icons.Filled.Insights,
        modifier = modifier,
    ) {
        live.networkRows.forEach { ReadoutRow(it) }
        RowDivider()
        val report = state.stability.valueOrNull
        val stability = report?.stability
        if (stability == null) {
            Text(
                text = state.stability.unavailabilityText()
                    ?: "No stability measurement has been taken yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ReadoutRow(
                readoutOf(
                    label = "Jitter",
                    value = stability.jitterMillis?.let { Formatters.millis(it) } ?: ABSENT,
                    detail = "Mean variation between consecutive probes — the honest substitute for " +
                        "a packet-loss figure, which needs ICMP and so root.",
                    tone = if (stability.isUnstable) Tone.Warning else Tone.Good,
                ),
            )
            ReadoutRow(
                readoutOf(
                    label = "Probes",
                    value = "${stability.completedProbes} completed",
                    detail = if (stability.failedProbes > 0) {
                        "${stability.failedProbes} did not complete"
                    } else {
                        "Range ${Formatters.millis(stability.minMillis ?: -1)} – " +
                            Formatters.millis(stability.maxMillis ?: -1)
                    },
                ),
            )
            report.warning?.let { warning ->
                NoteBanner(
                    text = warning,
                    tone = Tone.Warning,
                    icon = Icons.Filled.Bolt,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        ActionRow {
            TextButton(
                onClick = onMeasure,
                enabled = !state.isMeasuringStability && live.isConnected,
            ) {
                if (state.isMeasuringStability) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (state.isMeasuringStability) "Measuring" else "Measure stability")
            }
        }
    }
}

/**
 * The rates this panel reports, and which of the three mechanisms can write one.
 *
 * §31's 60 Hz-only device and §24B's MediaTek case are both handled here rather than by hiding the card.
 * A single-rate panel gets its figure and a sentence saying there is nothing to choose. A chipset known
 * to accept `peak_refresh_rate` and carry on at the old rate gets a warning *before* the user presses a
 * chip, with the path that does work offered next to it. The chips are enabled only when a mechanism
 * exists that changes the rate device-wide — [PerformanceUiState.canChangeRefreshRate] deliberately
 * excludes the per-window preference, which would appear to work on this screen and change nothing in a
 * game.
 */
@Composable
private fun RefreshRateCard(
    state: PerformanceUiState,
    live: PerformanceLive,
    onPin: (Float) -> Unit,
    onRelease: () -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Refresh rate",
        subtitle = state.refreshMechanism.label,
        icon = Icons.Filled.Refresh,
        modifier = modifier,
        action = {
            StatusChip(
                text = if (state.canChangeRefreshRate) "Changeable" else "Read only",
                tone = if (state.canChangeRefreshRate) Tone.Good else Tone.Muted,
            )
        },
    ) {
        live.displayRows.forEach { ReadoutRow(it) }
        RowDivider()
        Text(
            text = state.refreshMechanism.explanation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.warnsAboutChipset) {
            NoteBanner(
                text = "This device's display driver is one of the ones that takes a refresh-rate " +
                    "write and stays where it was. GameCore reads the rate back afterwards and will " +
                    "say so if that happens rather than reporting a change it cannot see.",
                tone = Tone.Warning,
                icon = Icons.Filled.Bolt,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (state.canChangeRefreshRate) {
            ChoiceRow(
                options = state.supportedRates,
                selected = selectedRateOf(state.supportedRates, live.currentRefreshRate),
                onSelect = onPin,
                label = { Formatters.hertz(it) },
                modifier = Modifier.padding(top = 10.dp),
                enabled = !state.isApplying,
            )
            ActionRow {
                TextButton(onClick = onRelease, enabled = !state.isApplying) {
                    Text("Let the system choose")
                }
            }
        } else {
            Text(
                text = refreshReasonFor(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (state.supportedRates.size > 1 && !state.capabilities.hasElevatedAccess) {
                ActionRow {
                    TextButton(onClick = { onNavigate(Destination.Shizuku) }) {
                        Text("Set up Shizuku")
                    }
                }
            }
        }
    }
}

/**
 * Which chip to fill in, given a measured rate that will not be exactly 120.0.
 *
 * A panel running at 119.998 Hz is running at its 120 Hz mode, and an equality test on two floats would
 * leave every chip unselected while the readout above says 120 Hz. Nearest within a hertz, or none.
 */
private fun selectedRateOf(rates: List<Float>, current: Float?): Float? {
    if (current == null) return null
    return rates.minByOrNull { abs(it - current) }?.takeIf { abs(it - current) < RATE_MATCH_TOLERANCE }
}

/** Why the chips are not there, which is a different sentence for each of the three reasons. */
private fun refreshReasonFor(state: PerformanceUiState): String = when {
    state.supportedRates.size <= 1 ->
        "This panel reports a single rate, so there is nothing to choose between. That is a property " +
            "of the display, not a limitation GameCore can work around."
    state.refreshMechanism == RefreshRateMechanism.WINDOW_PREFERENCE ->
        "Android will only let GameCore ask for a rate for its own window. A game draws in its own " +
            "window, so pinning ours would change the number on this screen and nothing in the game."
    else ->
        "No mechanism on this device writes the display's rate bounds for other apps. The elevated " +
            "shell is what usually unlocks it."
}

/**
 * The four named modes, each with what it actually writes.
 *
 * There is no "current mode" highlighted, because the device does not report one. GameCore knows what it
 * last applied within this process; it does not know whether the user changed the rate in Settings
 * afterwards, and a filled chip claiming "Performance" over a device that has since been rebooted would
 * be a small lie told confidently. So these are actions, not a selection.
 *
 * Custom gets an explanation and no button. It means "the switches in a game profile", which is a screen
 * away and not something this card can apply — and a button whose only effect is to print why it did
 * nothing is the kind of button §32 rules out.
 */
@Composable
private fun ModeCard(
    state: PerformanceUiState,
    onApplyMode: (PerformanceMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = state.isApplying || state.isRestoring
    SectionCard(
        title = "Modes",
        subtitle = "A named set of the changes below, applied to the device now",
        icon = Icons.Filled.Tune,
        modifier = modifier,
    ) {
        PerformanceMode.entries.forEachIndexed { index, mode ->
            if (index > 0) RowDivider()
            Text(
                text = mode.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = mode.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (mode == PerformanceMode.CUSTOM) {
                Text(
                    text = "Set per game, in that game's profile.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                ActionRow {
                    TextButton(onClick = { onApplyMode(mode) }, enabled = !busy) {
                        Text(if (mode == PerformanceMode.BALANCED) "Put everything back" else "Apply")
                    }
                }
            }
        }
    }
}

/**
 * The individual changes, each with its own status and its own reason for not being available.
 *
 * §17's four states in the form they take on screen: [CapabilityStatus.AVAILABLE] gets an Apply button,
 * the two actionable refusals get a button that goes where the user can fix it, and
 * [CapabilityStatus.UNSUPPORTED] gets the chip and nothing else — offering an action for something the
 * device cannot do implies it is the user's fault.
 *
 * The restore line at the bottom is not decoration. Every applied change writes its previous value down
 * first, and a count here means the device is still holding something GameCore put there.
 */
@Composable
private fun OptimizationsCard(
    state: PerformanceUiState,
    onApply: (OptimizationAction) -> Unit,
    onRestore: () -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Changes",
        subtitle = "Each one writes a setting you could write yourself, and remembers the old value",
        icon = Icons.Filled.Settings,
        modifier = modifier,
    ) {
        state.deviceActions.forEachIndexed { index, action ->
            if (index > 0) RowDivider()
            OptimizationRow(
                action = action,
                status = state.statusOf(action),
                isBusy = state.isApplying || state.isRestoring,
                onApply = { onApply(action) },
                onNavigate = onNavigate,
            )
        }
        if (state.pendingRestores > 0) {
            RowDivider()
            Text(
                text = "GameCore is holding ${Formatters.count(state.pendingRestores, "setting")} " +
                    "changed from what it found. Putting them back writes the recorded value, not a " +
                    "guess at a default.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActionRow {
                TextButton(onClick = onRestore, enabled = !state.isRestoring && !state.isApplying) {
                    Text(if (state.isRestoring) "Putting back" else "Put everything back")
                }
            }
        }
    }
}

/**
 * One change: what it is, what it does, whether it can be done here, and the one button that follows.
 *
 * The explanation is the enum's own [OptimizationAction.explanation], not a shorter version written for
 * this card. §17 requires every optimization to explain itself, and the place that explanation cannot
 * drift from the code that performs it is the enum.
 */
@Composable
private fun OptimizationRow(
    action: OptimizationAction,
    status: CapabilityStatus,
    isBusy: Boolean,
    onApply: () -> Unit,
    onNavigate: (Destination) -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = action.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = action.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        StatusChip(text = status.label, tone = capabilityTone(status))
    }
    if (status.isUsable) {
        ActionRow {
            TextButton(onClick = onApply, enabled = !isBusy) { Text("Apply") }
        }
    } else if (status.isActionable) {
        ActionRow {
            TextButton(
                onClick = {
                    onNavigate(
                        if (status == CapabilityStatus.REQUIRES_SHIZUKU) {
                            Destination.Shizuku
                        } else {
                            Destination.Permissions
                        },
                    )
                },
            ) {
                Text(if (status == CapabilityStatus.REQUIRES_SHIZUKU) "Set up Shizuku" else "Fix this")
            }
        }
    }
}

/**
 * How often this screen reads the device, and whether it keeps reading after the screen is gone.
 *
 * The interval is a real cost, not a preference with no consequences: every tick reads `/proc/stat`, the
 * thermal zones, the byte counters and — when latency is on — opens a socket. The description says so,
 * because the honest answer to "why is a monitoring app using battery" is "because you asked it to
 * measure something every second".
 *
 * Background monitoring is the one switch here that outlives the screen, so it is the one with a §24B
 * consequence: the ViewModel raises the Doze note when it goes on, rather than letting samples stop and
 * look like a bug.
 */
@Composable
private fun SamplingCard(
    state: PerformanceUiState,
    onInterval: (Int) -> Unit,
    onLatency: (Boolean) -> Unit,
    onBackground: (Boolean) -> Unit,
    onClearGraphs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Sampling",
        icon = Icons.Filled.Terminal,
        modifier = modifier,
    ) {
        SliderRow(
            title = "Interval",
            value = (state.sampleIntervalMillis / MILLIS_PER_SECOND).toInt(),
            range = MIN_INTERVAL_SECONDS..MAX_INTERVAL_SECONDS,
            onValueChange = onInterval,
            valueLabel = Formatters.duration(state.sampleIntervalMillis),
            description = "Every tick reads the processor's counters, the thermal zones and the " +
                "network's byte totals. Shorter is a smoother graph and more work for the device.",
        )
        RowDivider()
        SwitchRow(
            title = "Measure latency",
            checked = state.measureLatency,
            onCheckedChange = onLatency,
            description = "Opens a TCP connection to a public resolver once per sample to time the " +
                "round trip. Off means the ping figure is absent rather than estimated.",
        )
        SwitchRow(
            title = "Keep sampling in the background",
            checked = state.backgroundMonitoring,
            onCheckedChange = onBackground,
            description = "Runs a foreground service with a notification so the graphs continue " +
                "while GameCore is closed. Off is the default, and costs nothing when closed.",
        )
        ActionRow {
            TextButton(onClick = onClearGraphs) { Text("Clear graphs") }
        }
    }
}

/**
 * What the last thing the user pressed actually did, one line per change.
 *
 * This card is the reason the whole engine returns a sealed result rather than a boolean. "Applied" here
 * means the value was written *and read back*; a write that could not be verified says "Requested" and a
 * device that took the write and stayed where it was says "Ignored". Those three are visibly different,
 * because collapsing them into a green tick is precisely the fake-optimizer behaviour §24 rules out — and
 * the MediaTek refresh-rate case makes the middle one common enough to matter.
 */
@Composable
private fun ResultsCard(
    results: List<OptimizationResult>,
    onDismiss: () -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocked = results.filterIsInstance<OptimizationResult.Blocked>()
        .firstOrNull { it.status.isActionable }
    SectionCard(
        title = "Last change",
        icon = Icons.Filled.Restore,
        modifier = modifier,
        action = { TextButton(onClick = onDismiss) { Text("Dismiss") } },
    ) {
        results.forEach { result ->
            ReadoutRow(
                readoutOf(
                    label = result.action.label,
                    value = resultWord(result),
                    detail = result.message,
                    tone = resultTone(result),
                ),
            )
        }
        if (blocked != null) {
            ActionRow {
                TextButton(
                    onClick = {
                        onNavigate(
                            if (blocked.status == CapabilityStatus.REQUIRES_SHIZUKU) {
                                Destination.Shizuku
                            } else {
                                Destination.Permissions
                            },
                        )
                    },
                ) {
                    Text("What would make this work")
                }
            }
        }
    }
}

/**
 * One word for each outcome, chosen so the three that are not "it worked" cannot be mistaken for it.
 *
 * "Requested" for [OptimizationResult.Unverified] rather than a hedged version of Applied: the write went
 * out and GameCore could not confirm it landed, which is a genuinely different thing to tell a user than
 * either success or failure.
 */
private fun resultWord(result: OptimizationResult): String = when (result) {
    is OptimizationResult.Applied -> "Applied"
    is OptimizationResult.Unverified -> "Requested"
    is OptimizationResult.NotHonoured -> "Ignored by the device"
    is OptimizationResult.Skipped -> "Skipped"
    is OptimizationResult.Blocked -> result.status.label
    is OptimizationResult.Failed -> "Failed"
}

private fun resultTone(result: OptimizationResult): Tone = when (result) {
    is OptimizationResult.Applied -> Tone.Good
    is OptimizationResult.Unverified -> Tone.Warning
    is OptimizationResult.NotHonoured -> Tone.Danger
    is OptimizationResult.Skipped -> Tone.Muted
    is OptimizationResult.Blocked -> Tone.Muted
    is OptimizationResult.Failed -> Tone.Danger
}

/** How near a measured rate has to be to a mode's nominal rate to count as that mode, in hertz. */
private const val RATE_MATCH_TOLERANCE = 1.5f

/**
 * The ceiling on the frames-per-second graph.
 *
 * Fixed rather than auto-ranged so the line's height means the same thing between visits, and set above
 * the fastest panel GameCore is likely to meet so a 144 Hz device's frames are not drawn off the top.
 */
private const val FRAME_GRAPH_MAX_FPS = 165f

private const val MILLIS_PER_SECOND = 1_000L
private const val MIN_INTERVAL_SECONDS = (AppSettings.MIN_SAMPLE_INTERVAL / MILLIS_PER_SECOND).toInt()
private const val MAX_INTERVAL_SECONDS = (AppSettings.MAX_SAMPLE_INTERVAL / MILLIS_PER_SECOND).toInt()
