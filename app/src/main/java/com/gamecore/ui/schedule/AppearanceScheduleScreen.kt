package com.gamecore.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.schedule.AppearanceWindow
import com.gamecore.core.schedule.DashboardTarget
import com.gamecore.core.schedule.GameAppearanceOverride
import com.gamecore.core.schedule.ThemeTarget
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.theme.Spacing

@Composable
fun AppearanceScheduleRoute(
    onBack: () -> Unit,
    viewModel: AppearanceScheduleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AppearanceScheduleScreen(
        state = state,
        onBack = onBack,
        onEnabledChange = viewModel::setEnabled,
        onAddWindow = viewModel::addWindow,
        onRemoveWindow = viewModel::removeWindow,
        onPutGameOverride = viewModel::putGameOverride,
        onRemoveGameOverride = viewModel::removeGameOverride,
        onClear = viewModel::clearSchedule,
    )
}

@Composable
private fun AppearanceScheduleScreen(
    state: AppearanceScheduleUiState,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onAddWindow: (String, Int, Int, ThemeTarget?, DashboardTarget?) -> Unit,
    onRemoveWindow: (String) -> Unit,
    onPutGameOverride: (String, String, ThemeTarget?, DashboardTarget?) -> Unit,
    onRemoveGameOverride: (String) -> Unit,
    onClear: () -> Unit,
) {
    var windowDialog by remember { mutableStateOf(false) }
    var gameDialog by remember { mutableStateOf(false) }

    val themeOptions = remember(state.themes) {
        buildList<Pair<ThemeTarget?, String>> {
            add(null to "No change")
            add(ThemeTarget.Default to "GameCore default")
            state.themes.forEach { add(ThemeTarget.Plugin(it.pluginId) to it.label) }
        }
    }
    val dashboardOptions = listOf<Pair<DashboardTarget?, String>>(
        null to "No change",
        DashboardTarget.BuiltIn to "GameCore stats",
        DashboardTarget.PluginDashboard to "Plugin dashboard",
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item {
            ScreenHeader(
                title = "Theme & dashboard schedule",
                subtitle = "Change the theme and Home dashboard on a clock, or while a game is running.",
                onBack = onBack,
            )
        }
        item {
            SectionCard(title = "Now", icon = Icons.Filled.DateRange) {
                Text(
                    text = state.resolved.source.label,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "Theme: ${state.resolved.theme.targetLabel(state.themes)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Dashboard: ${state.resolved.dashboard.targetLabel()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                RowDivider()
                Text(
                    text = "Manual fallback — theme: the one selected on the Plugins screen; " +
                        "dashboard: ${if (state.manualShowBuiltInStats) "GameCore stats" else "plugin dashboard"}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionCard(title = "Schedule", icon = Icons.Filled.DateRange) {
                SwitchRow(
                    title = "Use the schedule",
                    checked = state.schedule.enabled,
                    onCheckedChange = onEnabledChange,
                    description = "When off, the schedule is kept but the manual theme and dashboard are used.",
                )
                NoteBanner(
                    text = "Game overrides win over time windows. A window that does not name a surface " +
                        "leaves that surface on its manual value. Start after end crosses midnight; equal " +
                        "times mean all day.",
                    tone = Tone.Muted,
                )
                if (state.schedule.windows.isEmpty() && state.schedule.gameOverrides.isEmpty()) {
                    // Nothing to clear yet.
                } else {
                    TextButton(onClick = onClear) {
                        Text("Clear schedule")
                    }
                }
            }
        }
        item {
            SectionCard(
                title = "Time windows",
                icon = Icons.Filled.DateRange,
                action = {
                    TextButton(onClick = { windowDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(Spacing.xs))
                        Text("Add")
                    }
                },
            ) {
                if (state.schedule.windows.isEmpty()) {
                    NoteBanner(
                        text = "No windows yet. Add one for a night theme, a weekend dashboard, or any " +
                            "other daily block.",
                    )
                } else {
                    state.schedule.windows.forEachIndexed { index, window ->
                        if (index > 0) RowDivider()
                        WindowRow(
                            window = window,
                            themes = state.themes,
                            onRemove = { onRemoveWindow(window.id) },
                        )
                    }
                }
            }
        }
        item {
            SectionCard(
                title = "Game overrides",
                icon = Icons.Filled.DateRange,
                action = {
                    TextButton(onClick = { gameDialog = true }, enabled = state.games.isNotEmpty()) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(Spacing.xs))
                        Text("Add")
                    }
                },
            ) {
                if (state.games.isEmpty()) {
                    NoteBanner(
                        text = "Add a game profile first. A game override needs a tracked package to " +
                            "match while it is running.",
                    )
                } else if (state.schedule.gameOverrides.isEmpty()) {
                    NoteBanner(
                        text = "No game overrides. Add one to use a different theme or dashboard while a " +
                            "particular game is running.",
                    )
                } else {
                    state.schedule.gameOverrides.forEachIndexed { index, override ->
                        if (index > 0) RowDivider()
                        GameRow(
                            override = override,
                            themes = state.themes,
                            onRemove = { onRemoveGameOverride(override.packageName) },
                        )
                    }
                }
            }
        }
    }

    if (windowDialog) {
        AddWindowDialog(
            themeOptions = themeOptions,
            dashboardOptions = dashboardOptions,
            onDismiss = { windowDialog = false },
            onAdd = { label, start, end, theme, dashboard ->
                onAddWindow(label, start, end, theme, dashboard)
                windowDialog = false
            },
        )
    }
    if (gameDialog) {
        AddGameDialog(
            games = state.games,
            themeOptions = themeOptions,
            dashboardOptions = dashboardOptions,
            onDismiss = { gameDialog = false },
            onAdd = { game, theme, dashboard ->
                onPutGameOverride(game.packageName, game.label, theme, dashboard)
                gameDialog = false
            },
        )
    }
}

@Composable
private fun WindowRow(
    window: AppearanceWindow,
    themes: List<ThemeOption>,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = window.label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = window.timeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Theme: ${window.theme.targetLabel(themes)} · " +
                    "Dashboard: ${window.dashboard.targetLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove ${window.label}")
        }
    }
}

@Composable
private fun GameRow(
    override: GameAppearanceOverride,
    themes: List<ThemeOption>,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = override.label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = override.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Theme: ${override.theme.targetLabel(themes)} · " +
                    "Dashboard: ${override.dashboard.targetLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove ${override.label}")
        }
    }
}

@Composable
private fun AddWindowDialog(
    themeOptions: List<Pair<ThemeTarget?, String>>,
    dashboardOptions: List<Pair<DashboardTarget?, String>>,
    onDismiss: () -> Unit,
    onAdd: (String, Int, Int, ThemeTarget?, DashboardTarget?) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var startText by remember { mutableStateOf("22:00") }
    var endText by remember { mutableStateOf("07:00") }
    var theme by remember { mutableStateOf<ThemeTarget?>(null) }
    var dashboard by remember { mutableStateOf<DashboardTarget?>(null) }
    val start = AppearanceWindow.parseMinute(startText)
    val end = AppearanceWindow.parseMinute(endText)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add time window") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        label = { Text("Start (HH:mm)") },
                        isError = start == null,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it },
                        label = { Text("End (HH:mm)") },
                        isError = end == null,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = "Start after end crosses midnight. Equal times mean all day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TargetPicker(
                    label = "Theme",
                    options = themeOptions,
                    selected = theme,
                    onSelected = { theme = it },
                )
                TargetPicker(
                    label = "Dashboard",
                    options = dashboardOptions,
                    selected = dashboard,
                    onSelected = { dashboard = it },
                )
            }
        },
        confirmButton = {
            Button(
                enabled = start != null && end != null,
                onClick = {
                    if (start != null && end != null) {
                        onAdd(label, start, end, theme, dashboard)
                    }
                },
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun AddGameDialog(
    games: List<GameOption>,
    themeOptions: List<Pair<ThemeTarget?, String>>,
    dashboardOptions: List<Pair<DashboardTarget?, String>>,
    onDismiss: () -> Unit,
    onAdd: (GameOption, ThemeTarget?, DashboardTarget?) -> Unit,
) {
    var selected by remember(games) { mutableStateOf<GameOption?>(games.firstOrNull()) }
    var theme by remember { mutableStateOf<ThemeTarget?>(null) }
    var dashboard by remember { mutableStateOf<DashboardTarget?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add game override") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                TargetPicker(
                    label = "Game",
                    options = games.map { game ->
                        game to "${game.label} (${game.packageName})"
                    },
                    selected = selected,
                    onSelected = { selected = it },
                )
                TargetPicker(
                    label = "Theme",
                    options = themeOptions,
                    selected = theme,
                    onSelected = { theme = it },
                )
                TargetPicker(
                    label = "Dashboard",
                    options = dashboardOptions,
                    selected = dashboard,
                    onSelected = { dashboard = it },
                )
                Text(
                    text = "This override applies while the game is the one GameCore is tracking. A " +
                        "\"No change\" surface falls through to the current time window, then to manual.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = selected != null,
                onClick = { selected?.let { onAdd(it, theme, dashboard) } },
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun <T> TargetPicker(
    label: String,
    options: List<Pair<T?, String>>,
    selected: T?,
    onSelected: (T?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: "No change"
    Column {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(selectedLabel)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            onSelected(value)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

private fun ThemeTarget?.targetLabel(themes: List<ThemeOption>): String = when (this) {
    null -> "No change"
    ThemeTarget.Default -> "GameCore default"
    is ThemeTarget.Plugin -> themes.firstOrNull { it.pluginId == pluginId }?.label ?: pluginId
}

private fun DashboardTarget?.targetLabel(): String = when (this) {
    null -> "No change"
    DashboardTarget.BuiltIn -> "GameCore stats"
    DashboardTarget.PluginDashboard -> "Plugin dashboard"
}


