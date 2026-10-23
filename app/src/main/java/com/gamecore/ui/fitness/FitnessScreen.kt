package com.gamecore.ui.fitness

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.fitness.FitnessCheck
import com.gamecore.core.fitness.FitnessReport
import com.gamecore.core.fitness.FitnessVerdict
import com.gamecore.ui.components.ClickableCard
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.components.startIntentSafely

/**
 * Device fitness check screen.
 *
 * Sections: Sensors, Permissions, Display, Thermal/Power, GPU.
 * Each check shows a verdict chip: READY=green, LIMITED=amber, ACTION_NEEDED=red.
 * "Fix" buttons open Settings intents via [FitnessViewModel.settingsIntentFor].
 */
@Composable
fun FitnessScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FitnessViewModel = hiltViewModel(),
) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Device fitness",
                subtitle = "What this device needs to run GameCore well",
                onBack = onBack,
                action = {
                    if (report.isLoading) {
                        StatusChip("Scanning", Tone.Accent)
                    } else {
                        TextButton(onClick = viewModel::refresh) { Text("Refresh") }
                    }
                },
            )
        }

        if (report.isLoading) {
            item {
                NoteBanner(
                    text = SCANNING,
                    tone = Tone.Muted,
                    icon = Icons.Filled.Refresh,
                    modifier = padded,
                )
            }
            return@LazyColumn
        }

        // Overall verdict banner
        item {
            OverallVerdictBanner(report = report, modifier = padded)
        }

        // Sections
        item { SensorsSection(checks = report.checks.filter { it.id in setOf("gyroscope", "accelerometer") }, modifier = padded) }
        item { PermissionsSection(checks = report.checks.filter { it.id in setOf("overlay_permission", "usage_access", "battery_exemption", "notifications") }, viewModel = viewModel, context = context, modifier = padded) }
        item { DisplaySection(checks = report.checks.filter { it.id == "refresh_rate" }, modifier = padded) }
        item { ThermalPowerSection(checks = report.checks.filter { it.id in setOf("thermal_status", "power_saver") }, modifier = padded) }
        item { GpuSection(checks = report.checks.filter { it.id == "gpu_renderer" }, modifier = padded) }

        item {
            NoteBanner(
                text = HONESTY_NOTE,
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
                modifier = padded,
            )
        }
    }
}

@Composable
private fun OverallVerdictBanner(report: FitnessReport, modifier: Modifier = Modifier) {
    val (bgColor, textColor, icon) = when (report.overall) {
        FitnessVerdict.READY ->
            Triple(Tone.Good.colour().copy(alpha = 0.14f), Tone.Good.colour(), Icons.Filled.Security)
        FitnessVerdict.LIMITED ->
            Triple(Tone.Warning.colour().copy(alpha = 0.14f), Tone.Warning.colour(), Icons.Filled.Build)
        FitnessVerdict.ACTION_NEEDED ->
            Triple(Tone.Danger.colour().copy(alpha = 0.14f), Tone.Danger.colour(), Icons.Filled.Info)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = bgColor,
        contentColor = textColor,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Overall: ${report.overall.label}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = report.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                )
            }
        }
    }
}

@Composable
private fun SensorsSection(checks: List<FitnessCheck>, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Sensors",
        modifier = modifier,
        subtitle = "Motion sensors required for aim assist and calibration",
        icon = Icons.Filled.SensorDoor,
    ) {
        checks.forEach { check ->
            CheckRow(check = check, onFixClick = null)
        }
    }
}

@Composable
private fun PermissionsSection(
    checks: List<FitnessCheck>,
    viewModel: FitnessViewModel,
    context: Context,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Permissions",
        modifier = modifier,
        subtitle = "Special accesses GameCore needs for overlays, game detection and background work",
        icon = Icons.Filled.PhoneAndroid,
    ) {
        checks.forEach { check ->
            CheckRow(
                check = check,
                onFixClick = check.fixRoute?.let { perm ->
                    { launchIntent(viewModel.settingsIntentFor(perm), context) }
                },
            )
        }
    }
}

@Composable
private fun DisplaySection(checks: List<FitnessCheck>, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Display",
        modifier = modifier,
        subtitle = "Panel running at its peak refresh rate",
        icon = Icons.Filled.Visibility,
    ) {
        checks.forEach { check ->
            CheckRow(check = check, onFixClick = null)
        }
    }
}

@Composable
private fun ThermalPowerSection(checks: List<FitnessCheck>, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Thermal & Power",
        modifier = modifier,
        subtitle = "Device temperature state and battery saver impact",
        icon = Icons.Filled.Thermostat,
    ) {
        checks.forEach { check ->
            CheckRow(check = check, onFixClick = null)
        }
    }
}

@Composable
private fun GpuSection(checks: List<FitnessCheck>, modifier: Modifier = Modifier) {
    SectionCard(
        title = "GPU",
        modifier = modifier,
        subtitle = "Graphics stack and Vulkan availability (info only)",
        icon = Icons.Filled.Memory,
    ) {
        checks.forEach { check ->
            CheckRow(check = check, onFixClick = null)
        }
    }
}

@Composable
private fun CheckRow(
    check: FitnessCheck,
    onFixClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val (verdictTone, verdictText) = when (check.verdict) {
        FitnessVerdict.READY -> Tone.Good to "READY"
        FitnessVerdict.LIMITED -> Tone.Warning to "LIMITED"
        FitnessVerdict.ACTION_NEEDED -> Tone.Danger to "ACTION NEEDED"
    }

    ClickableCard(modifier = modifier.fillMaxWidth(), onClick = {}) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = check.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = check.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            StatusChip(text = verdictText, tone = verdictTone)
            onFixClick?.let { fixAction ->
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = fixAction) {
                    Text(check.fixAction ?: "Fix")
                }
            }
        }
    }
}

private fun launchIntent(intent: Intent?, context: Context) {
    intent?.let { context.startIntentSafely(it) }
}

private const val SCANNING =
    "Checking sensors, permissions, display modes, thermal state and graphics stack. " +
    "This reads cached capability reports and live permission states."

private const val HONESTY_NOTE =
    "Every check above is a fact read from this device: sensor presence from SensorManager, " +
    "permissions from the platform's current state, display modes from DisplayManager, thermal " +
    "status from PowerManager, and GPU strings from a real EGL context. Where Android does not " +
    "expose a value, the check says so rather than guessing from the model name."