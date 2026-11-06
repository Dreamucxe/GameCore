package com.gamecore.ui.intelligence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.common.Formatters
import com.gamecore.core.model.BenchmarkComparison
import com.gamecore.core.model.BenchmarkPoint
import com.gamecore.core.model.BenchmarkProfile
import com.gamecore.core.model.BenchmarkRun
import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.ComparisonVerdict
import com.gamecore.core.model.DeviceFinding
import com.gamecore.core.model.DeviceIntelligenceReport
import com.gamecore.core.model.FindingKind
import com.gamecore.core.model.GameSession
import com.gamecore.core.model.IntelligenceAnalysisFrequency
import com.gamecore.core.model.LearningDecision
import com.gamecore.core.model.LearningOutcome
import com.gamecore.core.model.LearningRecord
import com.gamecore.core.model.LearningSummary
import com.gamecore.core.model.OptimizationGoal
import com.gamecore.core.model.RecommendationClass
import com.gamecore.core.model.RecommendationTarget
import com.gamecore.core.model.RefreshRateMechanism
import com.gamecore.core.model.SmartRecommendation
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.LineGraph
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SectionGap
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone
import java.util.Locale

/**
 * Offline device intelligence, real benchmark capture and the local learning history.
 *
 * This screen never calculates a synthetic score. Every figure is either a field from the device
 * scan, a persisted benchmark summary, or explicitly shown as unavailable. The benchmark button
 * observes whatever is already running; the copy beside it says so before the user starts it.
 */
@Composable
fun IntelligenceScreen(
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenAccess: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: IntelligenceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var clearBenchmarks by remember { mutableStateOf(false) }
    var clearLearning by remember { mutableStateOf(false) }
    var clearAll by remember { mutableStateOf(false) }

    LaunchedEffect(state.editorRequest) {
        state.editorRequest?.let { packageName ->
            viewModel.consumeEditorRequest()
            onOpenProfile(packageName)
        }
    }

    if (clearBenchmarks) {
        ConfirmDialog(
            title = "Clear benchmark history?",
            message = "Every completed Intelligence benchmark and comparison will be deleted. " +
                "Profiles, sessions and learning outcomes are not changed.",
            confirmLabel = "Clear benchmarks",
            onConfirm = {
                clearBenchmarks = false
                viewModel.clearBenchmarks()
            },
            onDismiss = { clearBenchmarks = false },
            isDestructive = true,
        )
    }
    if (clearLearning) {
        ConfirmDialog(
            title = "Reset learning history?",
            message = "Applications, dismissals and measured outcomes used to adjust confidence " +
                "will be deleted. Benchmarks and game profiles stay in place.",
            confirmLabel = "Reset learning",
            onConfirm = {
                clearLearning = false
                viewModel.clearLearning()
            },
            onDismiss = { clearLearning = false },
            isDestructive = true,
        )
    }
    if (clearAll) {
        ConfirmDialog(
            title = "Reset all Intelligence data?",
            message = "Every Intelligence benchmark, comparison, generated-recommendation event, " +
                "application, dismissal and measured outcome will be deleted. Game profiles and " +
                "ordinary session history are not changed.",
            confirmLabel = "Reset Intelligence",
            onConfirm = {
                clearAll = false
                viewModel.clearAllIntelligenceData()
            },
            onDismiss = { clearAll = false },
            isDestructive = true,
        )
    }

    val padded = Modifier.padding(horizontal = ScreenPadding)
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Performance Intelligence",
                subtitle = "Offline evidence from this device",
                onBack = onBack,
                action = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.isScanning) {
                        if (state.isScanning) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Scan again")
                        }
                    }
                },
            )
        }

        state.message?.let { message ->
            item {
                NoteBanner(
                    text = message,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                    action = {
                        IconButton(onClick = viewModel::consumeMessage, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss message")
                        }
                    },
                )
            }
        }

        item { GoalCard(state, viewModel::setGoal, modifier = padded) }
        item {
            SmartSettingsCard(
                state = state,
                onFrequency = viewModel::setAnalysisFrequency,
                onNotifications = viewModel::setNotificationsEnabled,
                onAutoApply = viewModel::setAutoApplyProfiles,
                modifier = padded,
            )
        }
        item {
            GameCard(
                state = state,
                onSelect = viewModel::selectGame,
                onOpenProfile = viewModel::openProfileEditor,
                modifier = padded,
            )
        }

        if (state.isLoading) {
            item { LoadingCard(modifier = padded) }
        } else {
            state.report?.let { report ->
                item { DeviceOverview(report, modifier = padded) }
                item { CapabilityCard(report, modifier = padded) }
                item { FindingsCard(report, modifier = padded) }
            }
            item {
                RecommendationsCard(
                    recommendations = state.recommendations,
                    busyKey = state.busyKey,
                    onApply = viewModel::applyRecommendation,
                    onDismiss = viewModel::dismissRecommendation,
                    modifier = padded,
                )
            }
            state.selectedPackage?.let { packageName ->
                item {
                    GameHistoryCard(
                        gameLabel = state.selectedLabel ?: packageName,
                        sessions = state.gameSessions,
                        modifier = padded,
                    )
                }
            }
        }

        item {
            BenchmarkCentre(
                state = state,
                onSelectProfile = viewModel::selectBenchmarkProfile,
                onStart = viewModel::startBenchmark,
                onStop = viewModel::stopBenchmark,
                modifier = padded,
            )
        }

        state.comparison?.let { comparison ->
            item {
                ComparisonCard(
                    comparison = comparison,
                    learningEnabled = state.learningEnabled,
                    outcomeRecorded = state.outcomeRecorded,
                    onRecord = viewModel::recordOutcome,
                    modifier = padded,
                )
            }
        }

        item {
            BenchmarkHistory(
                runs = state.benchmarks,
                onCompare = viewModel::showComparison,
                onClear = { clearBenchmarks = true },
                modifier = padded,
            )
        }
        item {
            LearningCard(
                enabled = state.learningEnabled,
                summaries = state.learning.values.sortedBy { it.actionKey },
                onToggle = viewModel::setLearningEnabled,
                onReset = { clearLearning = true },
                modifier = padded,
            )
        }
        if (state.learningRecords.isNotEmpty()) {
            item { LearningHistoryCard(state.learningRecords, modifier = padded) }
        }
        item {
            PrivacyCard(
                onOpenAccess = onOpenAccess,
                onResetAll = { clearAll = true },
                modifier = padded,
            )
        }
    }
}

@Composable
private fun GameHistoryCard(
    gameLabel: String,
    sessions: List<GameSession>,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "$gameLabel intelligence",
        subtitle = "Real measurements from recorded gaming sessions",
        icon = Icons.Filled.SportsEsports,
        modifier = modifier,
    ) {
        if (sessions.isEmpty()) {
            Text(
                "No completed GameCore session has been recorded for this game. Benchmark results " +
                    "remain separate and appear below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        val meaningful = sessions.filter { it.hasMeaningfulAggregates }
        val fps = meaningful.mapNotNull { it.averageFrameRate }.asReversed()
        val temperatures = meaningful.mapNotNull {
            it.averageTemperatureDeciCelsius?.div(10f)
        }.asReversed()
        val pressured = meaningful.count { (it.peakMemoryPercent ?: 0f) >= 92f }
        val warm = meaningful.count { (it.peakTemperatureDeciCelsius ?: 0) >= 550 }
        val drains = sessions.mapNotNull { it.drain()?.percentPerHour }

        KeyValueRow("Completed sessions", sessions.size.toString())
        KeyValueRow(
            "Recorded play time",
            formatDuration(sessions.sumOf { it.durationMillis(it.endedAtMillis ?: it.startedAtMillis) }),
        )
        if (warm > 0) KeyValueRow("Warm sessions", "$warm of ${meaningful.size}", Tone.Warning)
        if (pressured > 0) KeyValueRow("Memory-pressure sessions", "$pressured of ${meaningful.size}", Tone.Warning)
        if (drains.isNotEmpty()) {
            KeyValueRow(
                "Average reliable battery drain",
                String.format(Locale.US, "%.1f%%/h", drains.average()),
            )
        }

        if (fps.size >= 3) {
            SectionGap(8)
            Text("Average FPS by session", style = MaterialTheme.typography.labelLarge)
            val ceiling = (fps.maxOrNull() ?: 60f).coerceAtLeast(30f) * 1.1f
            LineGraph(series = fps, valueRange = 0f..ceiling, graphHeight = 82)
            Text(
                "Oldest → newest · Android gfxinfo frame timing only",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (temperatures.size >= 3) {
            SectionGap(8)
            Text("Average temperature by session", style = MaterialTheme.typography.labelLarge)
            LineGraph(series = temperatures, valueRange = 0f..100f, graphHeight = 82)
            Text(
                "Oldest → newest · CPU-class or battery sensor as recorded by the session",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionGap(8)
        Text("Recent sessions", style = MaterialTheme.typography.labelLarge)
        sessions.take(5).forEachIndexed { index, session ->
            if (index > 0) RowDivider()
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(
                    Formatters.dateTime(session.startedAtMillis),
                    style = MaterialTheme.typography.titleSmall,
                )
                KeyValueRow("Duration", formatDuration(session.durationMillis(session.endedAtMillis ?: session.startedAtMillis)))
                session.averageFrameRate?.let { KeyValueRow("Average FPS", formatFps(it)) }
                session.averageTemperatureDeciCelsius?.let {
                    KeyValueRow("Average temperature", formatTemperature(it))
                }
                session.peakMemoryPercent?.let { KeyValueRow("Peak memory", formatPercent(it)) }
            }
        }
    }
}

@Composable
private fun SmartSettingsCard(
    state: IntelligenceUiState,
    onFrequency: (IntelligenceAnalysisFrequency) -> Unit,
    onNotifications: (Boolean) -> Unit,
    onAutoApply: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(
        title = "Smart settings",
        subtitle = "Automatic work is opt-in, local and reversible",
        icon = Icons.Filled.Tune,
        modifier = modifier,
    ) {
        Text(
            "Automatic analysis frequency",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(state.analysisFrequency.label, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.MoreVert, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                IntelligenceAnalysisFrequency.entries.forEach { frequency ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(frequency.label)
                                Text(
                                    frequency.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onFrequency(frequency)
                        },
                    )
                }
            }
        }
        SectionGap(4)
        Text(
            state.analysisFrequency.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowDivider()
        SwitchRow(
            title = "Recommendation notifications",
            checked = state.notificationsEnabled,
            onCheckedChange = onNotifications,
            enabled = state.analysisFrequency != IntelligenceAnalysisFrequency.MANUAL,
            description = if (state.analysisFrequency == IntelligenceAnalysisFrequency.MANUAL) {
                "Choose an automatic frequency first. Manual scans report only inside this screen."
            } else {
                "Notify only after an automatic local scan finds at least one compatible action. " +
                    "Android's notification permission must also be allowed in system settings."
            },
        )
        RowDivider()
        SwitchRow(
            title = "Apply approved profiles automatically",
            checked = state.autoApplyProfiles,
            onCheckedChange = onAutoApply,
            description = "Uses the existing game detector and restore system. Intelligence never " +
                "adds an unapproved recommendation to a profile automatically.",
        )
    }
}

@Composable
private fun GoalCard(
    state: IntelligenceUiState,
    onGoal: (OptimizationGoal) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Optimization goal",
        subtitle = state.goal.summary,
        icon = Icons.Filled.Bolt,
        modifier = modifier,
    ) {
        ChoiceRow(
            options = OptimizationGoal.entries,
            selected = state.goal,
            onSelect = onGoal,
            label = { it.label },
            perRow = 2,
            enabled = !state.isCapturing,
        )
    }
}

@Composable
private fun GameCard(
    state: IntelligenceUiState,
    onSelect: (String?, String?) -> Unit,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(
        title = "Target",
        subtitle = "Choose a game for per-game advice and frame-rate capture",
        icon = Icons.Filled.SportsEsports,
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = !state.isCapturing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = state.selectedLabel ?: "Whole device (no game)",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.MoreVert, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Whole device (no game)") },
                    onClick = {
                        expanded = false
                        onSelect(null, null)
                    },
                )
                state.games.forEach { game ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(game.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    game.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(game.packageName, game.label)
                        },
                    )
                }
            }
        }
        if (state.selectedPackage == null) {
            SectionGap(8)
            NoteBanner(
                text = "Device actions can still be recommended. Profile actions are hidden until " +
                    "a game is selected, and FPS stays unavailable without a game package.",
                tone = Tone.Muted,
            )
        } else {
            SectionGap(4)
            TextButton(onClick = { onOpenProfile(state.selectedPackage) }) {
                Text("Review game profile")
            }
        }
    }
}

@Composable
private fun LoadingCard(modifier: Modifier = Modifier) {
    PlainCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Reading this device", style = MaterialTheme.typography.titleMedium)
                Text(
                    "CPU, memory, display, battery, thermal state and supported controls",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeviceOverview(report: DeviceIntelligenceReport, modifier: Modifier = Modifier) {
    val scan = report.scan
    SectionCard(
        title = "Device report",
        subtitle = "Read ${Formatters.dateTime(scan.scannedAtMillis)}",
        icon = Icons.Filled.PhoneAndroid,
        modifier = modifier,
    ) {
        KeyValueRow("Device", listOf(scan.manufacturer, scan.model).filter { it.isNotBlank() }.joinToString(" "))
        KeyValueRow("Processor", scan.socModel ?: "Not reported")
        KeyValueRow("GPU", scan.gpuRenderer ?: "Model not exposed by Android")
        KeyValueRow("CPU", "${scan.coreCount} cores · ${scan.cpuArchitecture}")
        KeyValueRow("RAM available", "${Formatters.bytes(scan.availableRamBytes)} / ${Formatters.bytes(scan.totalRamBytes)}")
        KeyValueRow("Storage available", "${Formatters.bytes(scan.storageAvailableBytes)} / ${Formatters.bytes(scan.storageTotalBytes)}")
        KeyValueRow("Display", "${scan.displayWidthPixels} × ${scan.displayHeightPixels}")
        KeyValueRow("Refresh now", scan.displayRefreshRateHz?.let(::formatHz) ?: "Not reported")
        KeyValueRow(
            "Battery",
            if (scan.batteryLevelPercent in 0..100) {
                "${scan.batteryLevelPercent}%${if (scan.batteryCharging) " · charging" else ""}"
            } else {
                "Not reported${if (scan.batteryCharging) " · charging" else ""}"
            },
        )
        KeyValueRow(
            "Thermal state",
            scan.thermalClass.label,
            tone = when (scan.thermalClass.level.name) {
                "CRITICAL", "EMERGENCY", "SHUTDOWN" -> Tone.Danger
                "HOT", "WARM" -> Tone.Warning
                else -> Tone.Good
            },
        )
        if (scan.unavailableReadings.isNotEmpty()) {
            SectionGap(6)
            NoteBanner(
                text = "Unavailable: ${scan.unavailableReadings.joinToString("; ")}",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
        }
    }
}

@Composable
private fun CapabilityCard(report: DeviceIntelligenceReport, modifier: Modifier = Modifier) {
    val capabilities = report.scan.capabilities
    SectionCard(
        title = "Operations and compatibility",
        subtitle = "Detected hardware is separate from controls GameCore verified",
        icon = Icons.Filled.CheckCircle,
        modifier = modifier,
    ) {
        KeyValueRow(
            "Shizuku",
            capabilities.shizuku.label,
            if (capabilities.shizuku.isUsable) Tone.Good else Tone.Muted,
        )
        KeyValueRow(
            "Refresh-rate control",
            capabilities.refreshRateMechanism.label,
            if (capabilities.refreshRateMechanism == RefreshRateMechanism.SYSTEM_SETTINGS ||
                capabilities.refreshRateMechanism == RefreshRateMechanism.SHIZUKU_SETTINGS
            ) {
                Tone.Good
            } else {
                Tone.Muted
            },
        )
        CapabilityRow("Brightness", capabilities.brightnessControl)
        CapabilityRow("Rotation", capabilities.rotationControl)
        CapabilityRow("Screen timeout", capabilities.screenTimeoutControl)
        CapabilityRow("Battery saver", capabilities.batterySaverControl)
        CapabilityRow("Animation scales", capabilities.animationScaleControl)
        CapabilityRow("Game detection", capabilities.gameDetection)
        CapabilityRow("Overlay", capabilities.overlay)
        CapabilityRow("Do Not Disturb", capabilities.doNotDisturbControl)
        CapabilityRow("Screen capture", capabilities.screenCapture)
        KeyValueRow(
            "CPU utilisation",
            if (capabilities.cpuUsageReadable) "Readable" else "Not readable",
            if (capabilities.cpuUsageReadable) Tone.Good else Tone.Muted,
        )
        KeyValueRow(
            "Per-core frequencies",
            if (capabilities.perCoreFrequenciesReadable) "Readable" else "Not readable",
            if (capabilities.perCoreFrequenciesReadable) Tone.Good else Tone.Muted,
        )
        KeyValueRow(
            "Platform thermal status",
            if (capabilities.thermalStatusSupported) "Supported" else "Unavailable",
            if (capabilities.thermalStatusSupported) Tone.Good else Tone.Muted,
        )
        KeyValueRow(
            "Game FPS source",
            if (capabilities.frameRate.canMeasureGame) "Available for selected package" else "Unavailable",
            if (capabilities.frameRate.canMeasureGame) Tone.Good else Tone.Muted,
        )
        if (!capabilities.frameRate.canMeasureGame) {
            Text(
                capabilities.frameRate.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (capabilities.isKnownUnreliableRefreshChipset) {
            SectionGap(6)
            NoteBanner(
                text = "This chipset family is known to accept refresh-rate writes without always " +
                    "honouring them. GameCore treats a change as unverified until read-back confirms it.",
                tone = Tone.Warning,
            )
        }
    }
}

@Composable
private fun CapabilityRow(label: String, status: CapabilityStatus) {
    KeyValueRow(
        label,
        status.label,
        when (status) {
            CapabilityStatus.AVAILABLE -> Tone.Good
            CapabilityStatus.REQUIRES_PERMISSION,
            CapabilityStatus.REQUIRES_SHIZUKU,
            -> Tone.Warning
            CapabilityStatus.UNSUPPORTED -> Tone.Muted
        },
    )
}

@Composable
private fun FindingsCard(report: DeviceIntelligenceReport, modifier: Modifier = Modifier) {
    SectionCard(
        title = "What the readings mean",
        subtitle = "Strengths, limits and opportunities backed by named evidence",
        icon = Icons.Filled.Science,
        modifier = modifier,
    ) {
        if (report.findings.isEmpty()) {
            Text(
                "The scan did not expose enough evidence for a finding.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            report.findings.forEachIndexed { index, finding ->
                FindingRow(finding)
                if (index != report.findings.lastIndex) RowDivider()
            }
        }
    }
}

@Composable
private fun FindingRow(finding: DeviceFinding) {
    Column(modifier = Modifier.padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(
                text = when (finding.kind) {
                    FindingKind.STRENGTH -> "Strength"
                    FindingKind.LIMITATION -> "Limit"
                    FindingKind.OPPORTUNITY -> "Opportunity"
                },
                tone = when (finding.kind) {
                    FindingKind.STRENGTH -> Tone.Good
                    FindingKind.LIMITATION -> Tone.Warning
                    FindingKind.OPPORTUNITY -> Tone.Accent
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(finding.title, style = MaterialTheme.typography.titleSmall)
        }
        SectionGap(4)
        Text(finding.detail, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Evidence: ${finding.evidence}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecommendationsCard(
    recommendations: List<SmartRecommendation>,
    busyKey: String?,
    onApply: (SmartRecommendation) -> Unit,
    onDismiss: (SmartRecommendation) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Recommendations", style = MaterialTheme.typography.titleLarge)
        Text(
            "Rules run locally over the scan, stored benchmarks and—when enabled—measured outcomes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (recommendations.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.CheckCircle,
                title = "Nothing justified right now",
                message = "The current readings and goal do not support a compatible change. " +
                    "Select a game or capture a benchmark to add evidence.",
            )
        } else {
            recommendations.forEach { recommendation ->
                RecommendationCard(
                    recommendation = recommendation,
                    busy = busyKey == recommendation.key,
                    anyBusy = busyKey != null,
                    onApply = { onApply(recommendation) },
                    onDismiss = { onDismiss(recommendation) },
                )
            }
        }
    }
}

@Composable
private fun RecommendationCard(
    recommendation: SmartRecommendation,
    busy: Boolean,
    anyBusy: Boolean,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    PlainCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(recommendation.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    recommendation.compatibility.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusChip(
                text = recommendation.classification.label,
                tone = recommendationTone(recommendation.classification),
            )
        }
        SectionGap(8)
        KeyValueRow("Confidence", "${recommendation.confidencePercent}%", recommendationTone(recommendation.classification))
        Text("Current", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(recommendation.current, style = MaterialTheme.typography.bodyMedium)
        SectionGap(5)
        Text("Proposed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(recommendation.proposed, style = MaterialTheme.typography.bodyMedium)
        SectionGap(8)
        Text(recommendation.reason, style = MaterialTheme.typography.bodyMedium)
        SectionGap(6)
        NoteBanner(text = "Trade-off: ${recommendation.tradeoff}", tone = Tone.Warning)
        SectionGap(6)
        recommendation.evidence.forEach { evidence ->
            Text(
                "• ${evidence.source}: ${evidence.detail}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        recommendation.history?.let {
            SectionGap(6)
            NoteBanner(text = it, tone = Tone.Accent, icon = Icons.Filled.History)
        }
        SectionGap(10)
        ActionRow {
            Button(
                onClick = onApply,
                enabled = !anyBusy && recommendation.classification.isUsable,
                modifier = Modifier.weight(1f),
            ) {
                if (busy) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                }
                Text(actionLabel(recommendation))
            }
            TextButton(onClick = onDismiss, enabled = !anyBusy) { Text("Dismiss") }
        }
    }
}

@Composable
private fun BenchmarkCentre(
    state: IntelligenceUiState,
    onSelectProfile: (BenchmarkProfile) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Benchmark centre",
        subtitle = "Observe a real game or device workload already in progress",
        icon = Icons.Filled.PlayArrow,
        modifier = modifier,
    ) {
        BenchmarkProfileMenu(
            selected = state.benchmarkProfile,
            enabled = !state.isCapturing,
            onSelect = onSelectProfile,
        )
        SectionGap(8)
        Text(
            state.benchmarkProfile.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KeyValueRow("Capture length", formatDuration(state.benchmarkProfile.defaultDurationMillis))
        if (state.selectedPackage == null) {
            NoteBanner(
                text = "No game is selected. CPU, memory, battery, display and thermal readings can " +
                    "still be captured, but FPS cannot be attributed or measured.",
                tone = Tone.Warning,
            )
        } else {
            NoteBanner(
                text = "Start ${state.selectedLabel} yourself and keep playing during the capture. " +
                    "GameCore does not launch a synthetic workload or invent missing FPS.",
                tone = Tone.Accent,
            )
        }

        state.progress?.let { progress ->
            SectionGap(10)
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
            KeyValueRow(
                "Progress",
                "${formatDuration(progress.elapsedMillis)} / ${formatDuration(progress.totalMillis)}",
            )
            KeyValueRow("Readings", progress.sampleCount.toString())
            progress.latestPoint?.let { point -> BenchmarkPointRows(point) }
        }
        SectionGap(10)
        if (state.isCapturing) {
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text("Stop without saving")
            }
        } else {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Start real capture")
            }
        }
    }
}

@Composable
private fun BenchmarkProfileMenu(
    selected: BenchmarkProfile,
    enabled: Boolean,
    onSelect: (BenchmarkProfile) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(selected.label, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.MoreVert, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            BenchmarkProfile.entries.forEach { profile ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(profile.label)
                            Text(
                                formatDuration(profile.defaultDurationMillis),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(profile)
                    },
                )
            }
        }
    }
}

@Composable
private fun BenchmarkPointRows(point: BenchmarkPoint) {
    val temperature = point.temperatureDeciCelsius?.let(::formatTemperature) ?: "Unavailable"
    val fps = point.frameRate?.takeIf { it.isReliable }?.averageFps?.let(::formatFps) ?: "Unavailable"
    KeyValueRow("Latest CPU", metricWithSource(point.cpuPercent?.let(::formatPercent), point.cpuSource))
    KeyValueRow("Latest GPU", metricWithSource(point.gpuPercent?.let(::formatPercent), point.gpuSource))
    KeyValueRow("Latest memory", metricWithSource(point.memoryPercent?.let(::formatPercent), point.memorySource))
    KeyValueRow("Latest temperature", metricWithSource(temperature.takeUnless { it == "Unavailable" }, point.temperatureSource))
    KeyValueRow("Latest FPS window", fps)
}

@Composable
private fun ComparisonCard(
    comparison: BenchmarkComparison,
    learningEnabled: Boolean,
    outcomeRecorded: Boolean,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Before and after",
        subtitle = comparison.verdict.label,
        icon = Icons.Filled.Science,
        action = {
            StatusChip(
                text = comparison.confidencePercent?.let { "$it%" } ?: "No verdict confidence",
                tone = comparisonTone(comparison.verdict),
            )
        },
        modifier = modifier,
    ) {
        Text(comparison.explanation, style = MaterialTheme.typography.bodyMedium)
        comparison.deltas.forEach { delta ->
            KeyValueRow(
                delta.label,
                "${formatMetric(delta.before, delta.unit)} → ${formatMetric(delta.after, delta.unit)}",
                tone = when {
                    delta.delta == 0f -> Tone.Muted
                    delta.isImprovement -> Tone.Good
                    else -> Tone.Warning
                },
            )
        }
        if (comparison.conditions.isNotEmpty()) {
            SectionGap(6)
            comparison.conditions.forEach { condition ->
                Text(
                    "• $condition",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SectionGap(10)
        when {
            outcomeRecorded -> NoteBanner("This measured outcome is in local learning history.", Tone.Good)
            !learningEnabled -> NoteBanner(
                "Learning is off, so this comparison is shown but does not change confidence.",
                Tone.Muted,
            )
            comparison.verdict == ComparisonVerdict.NOT_COMPARABLE -> Unit
            else -> OutlinedButton(onClick = onRecord, modifier = Modifier.fillMaxWidth()) {
                Text("Use this measured result for learning")
            }
        }
    }
}

@Composable
private fun BenchmarkHistory(
    runs: List<BenchmarkRun>,
    onCompare: (Long) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Benchmark history",
        subtitle = if (runs.isEmpty()) "No completed captures" else "${runs.size} most recent captures",
        icon = Icons.Filled.History,
        action = {
            if (runs.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear benchmark history")
                }
            }
        },
        modifier = modifier,
    ) {
        if (runs.isEmpty()) {
            Text(
                "A completed capture appears here. Stopped and failed captures are never stored.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            runs.forEachIndexed { index, run ->
                BenchmarkRunRow(run, onCompare)
                if (index != runs.lastIndex) RowDivider()
            }
        }
    }
}

@Composable
private fun BenchmarkRunRow(run: BenchmarkRun, onCompare: (Long) -> Unit) {
    var expanded by remember(run.id) { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(run.gameLabel ?: "Whole device", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${run.profile.label} · ${Formatters.dateTime(run.startedAtMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            run.summary.averageFps?.let { StatusChip(formatFps(it), Tone.Accent) }
        }
        KeyValueRow("Duration", formatDuration(run.summary.durationMillis))
        KeyValueRow("Readings", run.summary.sampleCount.toString())
        run.summary.averageTemperatureDeciCelsius?.let {
            KeyValueRow("Average temperature", formatTemperature(it))
        }
        if (run.summary.averageFps == null) {
            Text(
                "FPS unavailable${run.summary.fpsSource?.let { ": $it" } ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (run.summary.unavailable.isNotEmpty()) {
            Text(
                run.summary.unavailable.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide measured details" else "Show measured details")
        }
        if (expanded) {
            val summary = run.summary
            summary.averageFps?.let { KeyValueRow("Average FPS", formatFps(it)) }
            summary.lowestWindowFps?.let { KeyValueRow("Lowest FPS window", formatFps(it)) }
            summary.onePercentLowFps?.let { KeyValueRow("1% low FPS", formatFps(it)) }
            summary.frameVariationPercent?.let { KeyValueRow("FPS window variation", formatPercent(it)) }
            summary.p95FrameMillis?.let { KeyValueRow("P95 frame time", formatMillis(it)) }
            summary.p99FrameMillis?.let { KeyValueRow("P99 frame time", formatMillis(it)) }
            summary.jankPercent?.let { KeyValueRow("Jank", formatPercent(it)) }
            summary.averageCpuPercent?.let {
                KeyValueRow("CPU average / peak", "${formatPercent(it)} / ${summary.peakCpuPercent?.let(::formatPercent) ?: "—"}")
            }
            summary.averageGpuPercent?.let {
                KeyValueRow("GPU average / peak", "${formatPercent(it)} / ${summary.peakGpuPercent?.let(::formatPercent) ?: "—"}")
            }
            summary.averageMemoryPercent?.let {
                KeyValueRow("Memory average / peak", "${formatPercent(it)} / ${summary.peakMemoryPercent?.let(::formatPercent) ?: "—"}")
            }
            summary.averageTemperatureDeciCelsius?.let {
                KeyValueRow(
                    "Temperature average / peak",
                    "${formatTemperature(it)} / ${summary.peakTemperatureDeciCelsius?.let(::formatTemperature) ?: "—"}",
                )
            }
            if (summary.startBatteryPercent != null && summary.endBatteryPercent != null) {
                KeyValueRow("Battery", "${summary.startBatteryPercent}% → ${summary.endBatteryPercent}%")
            }
            summary.batteryDrainPerHour?.let {
                KeyValueRow("Battery drain", String.format(Locale.US, "%.1f%%/h", it))
            }
            summary.averageRefreshRateHz?.let { KeyValueRow("Display refresh average", formatHz(it)) }
            val sources = listOfNotNull(
                summary.fpsSource?.let { "FPS — $it" },
                summary.cpuSource?.let { "CPU — $it" },
                summary.gpuSource?.let { "GPU — $it" },
                summary.memorySource?.let { "Memory — $it" },
                summary.temperatureSource?.let { "Temperature — $it" },
                summary.batterySource?.let { "Battery — $it" },
                summary.refreshRateSource?.let { "Refresh rate — $it" },
            )
            if (sources.isNotEmpty()) {
                SectionGap(5)
                Text("Measurement sources", style = MaterialTheme.typography.labelLarge)
                sources.forEach { source ->
                    Text(
                        "• $source",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (run.baselineRunId != null) {
            TextButton(onClick = { onCompare(run.id) }) { Text("Compare with baseline") }
        }
    }
}

@Composable
private fun LearningCard(
    enabled: Boolean,
    summaries: List<LearningSummary>,
    onToggle: (Boolean) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Local learning",
        subtitle = "Why confidence changed on this device",
        icon = Icons.Filled.Memory,
        modifier = modifier,
    ) {
        SwitchRow(
            title = "Use measured outcomes",
            checked = enabled,
            onCheckedChange = onToggle,
            description = "When off, stored history is preserved but ignored and no new comparison " +
                "outcome is recorded.",
        )
        if (summaries.isEmpty()) {
            SectionGap(6)
            Text(
                "No applications, dismissals or measured outcomes have been recorded yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            summaries.forEach { summary ->
                RowDivider()
                Column(Modifier.padding(vertical = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            summary.actionKey.replace('_', ' ').replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        StatusChip(
                            text = when {
                                summary.blocked -> "Blocked by results"
                                summary.confidenceAdjustment > 0 -> "+${summary.confidenceAdjustment}%"
                                else -> "${summary.confidenceAdjustment}%"
                            },
                            tone = when {
                                summary.blocked || summary.confidenceAdjustment < 0 -> Tone.Warning
                                summary.confidenceAdjustment > 0 -> Tone.Good
                                else -> Tone.Muted
                            },
                        )
                    }
                    Text(
                        summary.explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SectionGap(8)
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Reset learning history")
            }
        }
    }
}

@Composable
private fun LearningHistoryCard(records: List<LearningRecord>, modifier: Modifier = Modifier) {
    val shown = records.take(20)
    SectionCard(
        title = "Learning history",
        subtitle = "Latest ${shown.size} local events",
        icon = Icons.Filled.History,
        modifier = modifier,
    ) {
        shown.forEachIndexed { index, record ->
            Column(Modifier.padding(vertical = 7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        record.actionKey.replace('_', ' ').replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(
                        text = when (record.decision) {
                            LearningDecision.GENERATED -> "Generated"
                            LearningDecision.APPLIED -> "Applied"
                            LearningDecision.ATTEMPTED -> "Unconfirmed"
                            LearningDecision.IGNORED -> "Dismissed"
                            LearningDecision.TESTED -> "Tested"
                            LearningDecision.UNKNOWN -> "Unrecognised"
                        },
                        tone = when (record.decision) {
                            LearningDecision.GENERATED -> Tone.Accent
                            LearningDecision.APPLIED -> Tone.Good
                            LearningDecision.ATTEMPTED -> Tone.Warning
                            LearningDecision.IGNORED -> Tone.Muted
                            LearningDecision.TESTED -> outcomeTone(record.outcome)
                            LearningDecision.UNKNOWN -> Tone.Muted
                        },
                    )
                }
                Text(
                    "${record.goal.label} · ${Formatters.dateTime(record.createdAtMillis)}" +
                        (record.gamePackage?.let { " · $it" } ?: " · whole device"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (record.outcome != LearningOutcome.UNKNOWN) {
                    Text(
                        buildString {
                            append("Outcome: ${record.outcome.name.lowercase().replace('_', ' ')}")
                            if (record.metricName != null && record.beforeValue != null && record.afterValue != null) {
                                append(" · ${record.metricName}: ${record.beforeValue} → ${record.afterValue}")
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = outcomeTone(record.outcome).let { tone ->
                            // Use the shared tone mapping without exposing its internal colour helper.
                            when (tone) {
                                Tone.Good -> MaterialTheme.colorScheme.primary
                                Tone.Danger -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        },
                    )
                }
                record.note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (index != shown.lastIndex) RowDivider()
        }
    }
}

@Composable
private fun PrivacyCard(
    onOpenAccess: () -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Private by design",
        icon = Icons.Filled.Info,
        modifier = modifier,
    ) {
        Text(
            "Scanning, recommendations, benchmark summaries and learning all stay on this device. " +
                "No cloud API, online model, downloaded model or telemetry is used. GameCore reads " +
                "only the device sources and permissions already shown in its capability screens.",
            style = MaterialTheme.typography.bodyMedium,
        )
        SectionGap(10)
        OutlinedButton(onClick = onOpenAccess, modifier = Modifier.fillMaxWidth()) {
            Text("Review access and privacy")
        }
        SectionGap(6)
        OutlinedButton(onClick = onResetAll, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.DeleteSweep, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Reset all Intelligence data")
        }
    }
}

private fun actionLabel(recommendation: SmartRecommendation): String = when {
    recommendation.requiresTesting -> "Review test"
    recommendation.target is RecommendationTarget.GameProfile -> "Save to profile"
    recommendation.target is RecommendationTarget.DeviceAction -> "Apply now"
    else -> "Acknowledge"
}

private fun recommendationTone(classification: RecommendationClass): Tone = when (classification) {
    RecommendationClass.STRONGLY_RECOMMENDED -> Tone.Good
    RecommendationClass.RECOMMENDED -> Tone.Accent
    RecommendationClass.OPTIONAL -> Tone.Neutral
    RecommendationClass.EXPERIMENTAL -> Tone.Warning
    RecommendationClass.NOT_RECOMMENDED,
    RecommendationClass.UNSUPPORTED,
    -> Tone.Danger
}

private fun comparisonTone(verdict: ComparisonVerdict): Tone = when (verdict) {
    ComparisonVerdict.IMPROVED -> Tone.Good
    ComparisonVerdict.REGRESSED -> Tone.Danger
    ComparisonVerdict.NEUTRAL -> Tone.Neutral
    ComparisonVerdict.INCONCLUSIVE,
    ComparisonVerdict.NOT_COMPARABLE,
    -> Tone.Warning
}

private fun outcomeTone(outcome: LearningOutcome): Tone = when (outcome) {
    LearningOutcome.IMPROVED -> Tone.Good
    LearningOutcome.REGRESSED -> Tone.Danger
    LearningOutcome.NEUTRAL -> Tone.Neutral
    LearningOutcome.INCONCLUSIVE -> Tone.Warning
    LearningOutcome.UNKNOWN -> Tone.Muted
}

private fun formatDuration(millis: Long): String {
    val seconds = millis.coerceAtLeast(0L) / 1_000L
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    val remaining = seconds % 60L
    return when {
        hours > 0 -> "${hours}h ${minutes}m ${remaining}s"
        minutes > 0 -> "${minutes}m ${remaining}s"
        else -> "${remaining}s"
    }
}

private fun formatHz(value: Float): String =
    if (kotlin.math.abs(value - value.toInt()) < 0.05f) "${value.toInt()} Hz"
    else String.format(Locale.US, "%.1f Hz", value)

private fun formatFps(value: Float): String = String.format(Locale.US, "%.1f FPS", value)

private fun formatPercent(value: Float): String = String.format(Locale.US, "%.1f%%", value)

private fun formatMillis(value: Float): String = String.format(Locale.US, "%.1f ms", value)

private fun metricWithSource(value: String?, source: String?): String = when {
    value == null -> "Unavailable"
    source == null -> value
    else -> "$value · $source"
}

private fun formatTemperature(deciCelsius: Int): String =
    String.format(Locale.US, "%.1f °C", deciCelsius / 10f)

private fun formatMetric(value: Float, unit: String): String = when (unit) {
    "°C/10" -> String.format(Locale.US, "%.1f °C", value / 10f)
    else -> String.format(Locale.US, "%.1f %s", value, unit)
}
