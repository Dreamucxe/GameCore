package com.gamecore.themeengine

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SectionGap
import com.gamecore.ui.components.SliderRow
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.StatTile
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import kotlin.math.roundToInt

/**
 * The Theme Engine's calibration dashboard.
 *
 * Top to bottom it answers, in the order a user asks them: is it running, what does it look like, what
 * is it reading, what did it decide, and how do I change what it decides. The preview is above the
 * numbers because the colour is the feature; the calibration table is above the sliders because it is
 * the thing a user is checking the sliders against.
 *
 * Nothing here animates by itself. The colour on the canvas is the engine's smoothed vector, collected
 * straight from the plugin, and the table is the engine's *target* resolved from the same readings —
 * so "rgb(148, 90, 180)" is printed only when the rules really select violet, not while the canvas is
 * still travelling toward it.
 */
@Composable
fun CalibrationDashboard(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ThemeEngineViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val renderVector by viewModel.renderVector.collectAsStateWithLifecycle()
    val overridesPluginThemes by viewModel.overridesPluginThemes.collectAsStateWithLifecycle()
    var saveName by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ScreenPadding),
    ) {
        item {
            ScreenHeader(
                title = "Theme Engine",
                subtitle = "A live backdrop that follows this device's own readings",
                onBack = onBack,
            )
        }
        item { SectionGap() }
        item { PreviewCard(vector = renderVector, active = state.isActive) }
        item { SectionGap() }
        item { MetricRow(state) }
        if (!state.availability.temperature) {
            item {
                Spacer(Modifier.height(8.dp))
                NoteBanner(
                    text = "This device does not report a core temperature. The backdrop treats it as " +
                        "cold rather than inventing heat, and the card above says so.",
                    tone = Tone.Muted,
                    icon = Icons.Filled.Thermostat,
                )
            }
        }
        item { SectionGap() }
        item { CalibrationCard(state) }
        item { SectionGap() }
        item {
            ManualOverrideCard(
                state = state,
                onCpu = viewModel::setCpuOverride,
                onRam = viewModel::setRamOverride,
                onTemperature = viewModel::setCoreTemperatureOverride,
                onFollowLive = viewModel::followLive,
            )
        }
        item { SectionGap() }
        item {
            ThresholdCard(
                state = state,
                onCpu = viewModel::setCpuThreshold,
                onRam = viewModel::setRamThreshold,
                onTemperature = viewModel::setTemperatureThreshold,
            )
        }
        item { SectionGap() }
        item {
            SavePointsCard(
                state = state,
                name = saveName,
                onNameChange = { saveName = it },
                onSave = {
                    viewModel.savePreset(saveName)
                    saveName = ""
                },
                onSelect = viewModel::selectPreset,
                onDelete = viewModel::deleteActivePreset,
            )
        }
        item { SectionGap() }
        item {
            SectionCard(
                title = "App-wide colour",
                subtitle = "Whether this engine or a plugin decides what the app looks like",
                icon = Icons.Filled.Palette,
            ) {
                SwitchRow(
                    title = "Override plugin themes",
                    checked = overridesPluginThemes,
                    onCheckedChange = viewModel::setOverridesPluginThemes,
                    description = "Off, a plugin that supplies colours wins, exactly as it always has. " +
                        "On, the vector above is the app's accent on every screen and a plugin keeps " +
                        "everything else it does but stops choosing the palette.",
                )
                RowDivider()
                KeyValueRow(
                    label = "App accent right now",
                    value = rgbText(state.resolution.vector.rgb),
                    tone = if (overridesPluginThemes) Tone.Accent else Tone.Muted,
                )
                KeyValueRow(
                    label = "Chosen by",
                    value = when {
                        !overridesPluginThemes -> "The settings above"
                        !state.isActive -> "Nothing yet — the engine is not running"
                        else -> "Theme Engine, from this device's readings"
                    },
                    tone = Tone.Muted,
                )
            }
        }
        item { SectionGap() }
        item {
            NoteBanner(
                text = "The Theme Engine is built into GameCore and lives in its own package. Plugins are " +
                    "data the app reads and never code it runs, so an engine that has to draw cannot be one " +
                    "of them — this screen is where its calibration lives, not a plugin installer.",
                tone = Tone.Muted,
                icon = Icons.Filled.Palette,
            )
        }
        item { Spacer(Modifier.height(ScreenBottomPadding)) }
    }
}

/** The canvas in a rounded frame, with nothing else in it: the colour is the point. */
@Composable
private fun PreviewCard(vector: AuroraVector, active: Boolean) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp),
        shape = MaterialTheme.shapes.large,
        color = Color(0xFF05040A),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    ) {
        AuroraLiveCanvas(
            vector = vector,
            active = active,
            modifier = Modifier.fillMaxSize(),
            description = "Live theme engine preview",
        )
    }
}

/** The three readings, as compact cards. */
@Composable
private fun MetricRow(state: CalibrationUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatTile(
            label = "CPU CORE",
            value = if (state.availability.cpu) percentText(state.metrics.cpuUsage) else "—",
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.Speed,
            tone = Tone.Accent,
        )
        StatTile(
            label = "RAM ALLOCATION",
            value = if (state.availability.ram) percentText(state.metrics.ramAllocation) else "—",
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.Memory,
            tone = Tone.Neutral,
        )
        StatTile(
            label = "CORE TEMP",
            value = if (state.availability.temperature) {
                temperatureText(state.metrics.coreTemperatureC)
            } else {
                "—"
            },
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.Thermostat,
            tone = when (state.resolution.mode) {
                ColorVectorMode.MOLTEN_LAVA -> Tone.Danger
                ColorVectorMode.AURORA_VIOLET -> Tone.Warning
                else -> Tone.Neutral
            },
        )
    }
}

/**
 * What the rules decided, printed as the figures the reference names.
 *
 * [KeyValueRow] rather than a read-only text field: these are readings, not settings, and a field
 * implies the user can type into it.
 */
@Composable
private fun CalibrationCard(state: CalibrationUiState) {
    SectionCard(
        title = "Calibration",
        subtitle = "Resolved from the readings above",
        icon = Icons.Filled.Tune,
        action = {
            StatusChip(
                text = if (state.isActive) "● THEME ENGINE: ACTIVE" else "● THEME ENGINE: PAUSED",
                tone = if (state.isActive) Tone.Good else Tone.Muted,
            )
        },
    ) {
        KeyValueRow(
            label = "Theme mode status",
            value = state.resolution.mode.label,
            tone = Tone.Accent,
        )
        KeyValueRow(
            label = "Color vector value",
            value = rgbText(state.resolution.vector.rgb),
            tone = Tone.Neutral,
        )
        KeyValueRow(
            label = "Fluid velocity",
            value = velocityText(state.resolution.vector.velocity),
            tone = Tone.Neutral,
        )
        RowDivider()
        Text(
            text = "A save point remembers the mode and the thresholds in force. It does not freeze the " +
                "colour: the vector always follows the readings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The three manual overrides.
 *
 * Moving any of them switches the whole feed from the device's readings to these numbers — all three
 * at once, seeded from whatever was on screen — so the preview never mixes a live temperature with a
 * guess at the CPU.
 */
@Composable
private fun ManualOverrideCard(
    state: CalibrationUiState,
    onCpu: (Int) -> Unit,
    onRam: (Int) -> Unit,
    onTemperature: (Int) -> Unit,
    onFollowLive: () -> Unit,
) {
    SectionCard(
        title = "Manual override",
        subtitle = "Drive the preview with numbers of your own",
        icon = Icons.Filled.Speed,
    ) {
        if (state.isFollowingLive) {
            NoteBanner(
                text = "Following this device's live readings.",
                tone = Tone.Good,
            )
        } else {
            NoteBanner(
                text = "Overriding the device's readings. The canvas is painting these values, not the " +
                    "sensors'. Nothing is written to storage until you save a point.",
                tone = Tone.Warning,
                action = {
                    TextButton(onClick = onFollowLive) { Text("Follow live") }
                },
            )
        }
        SliderRow(
            title = "CPU load",
            value = state.metrics.cpuUsage.roundToInt(),
            range = 0..AuroraBounds.MAX_PERCENT_METRIC.toInt(),
            onValueChange = onCpu,
            valueLabel = percentText(state.metrics.cpuUsage),
            description = "The overall core utilisation the engine reads as cpuUsage.",
        )
        SliderRow(
            title = "RAM allocation",
            value = state.metrics.ramAllocation.roundToInt(),
            range = 0..AuroraBounds.MAX_PERCENT_METRIC.toInt(),
            onValueChange = onRam,
            valueLabel = percentText(state.metrics.ramAllocation),
            description = "The share of physical memory in use, as ramAllocation.",
        )
        SliderRow(
            title = "Core temperature",
            value = state.metrics.coreTemperatureC.roundToInt(),
            range = 0..AuroraBounds.MAX_TEMP_METRIC.toInt(),
            onValueChange = onTemperature,
            valueLabel = temperatureText(state.metrics.coreTemperatureC),
            description = "The hottest CPU zone, in Celsius, as coreTemperature.",
        )
    }
}

/**
 * The edges the automatic rules step at.
 *
 * These edit the preset in force in memory. Leaving without saving is therefore not destructive, and
 * the note on the calibration card says as much; selecting a save point replaces them in one step.
 */
@Composable
private fun ThresholdCard(
    state: CalibrationUiState,
    onCpu: (Int) -> Unit,
    onRam: (Int) -> Unit,
    onTemperature: (Int) -> Unit,
) {
    SectionCard(
        title = "Trigger thresholds",
        subtitle = "Where the vector steps up",
        icon = Icons.Filled.Tune,
    ) {
        SliderRow(
            title = "CPU threshold",
            value = state.preset.cpuThreshold.roundToInt(),
            range = 0..AuroraBounds.MAX_PERCENT_METRIC.toInt(),
            onValueChange = onCpu,
            valueLabel = percentText(state.preset.cpuThreshold),
            description = "At or above this, the vector becomes Aurora Violet.",
        )
        SliderRow(
            title = "RAM threshold",
            value = state.preset.ramThreshold.roundToInt(),
            range = 0..AuroraBounds.MAX_PERCENT_METRIC.toInt(),
            onValueChange = onRam,
            valueLabel = percentText(state.preset.ramThreshold),
            description = "Memory pressure alone is enough to raise the vector.",
        )
        SliderRow(
            title = "Temperature threshold",
            value = state.preset.tempThreshold.roundToInt(),
            range = 0..AuroraBounds.MAX_TEMP_METRIC.toInt(),
            onValueChange = onTemperature,
            valueLabel = temperatureText(state.preset.tempThreshold),
            description = "At or above this, the vector goes molten rather than violet.",
        )
    }
}

/** Named calibrations, stored in this device's preferences. */
@Composable
private fun SavePointsCard(
    state: CalibrationUiState,
    name: String,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: () -> Unit,
) {
    SectionCard(
        title = "Save points",
        subtitle = "Your calibrations, kept on this device",
        icon = Icons.Filled.Palette,
    ) {
        ChoiceRow(
            options = state.presets,
            selected = state.presets.firstOrNull { it.id == state.preset.id },
            onSelect = { preset -> onSelect(preset.id) },
            label = { it.name },
            perRow = 2,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("Save point name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSave) { Text("Save") }
        }
        if (state.canDeletePreset) {
            TextButton(onClick = onDelete) {
                Text("Delete “${state.preset.name}”")
            }
        }
    }
}
