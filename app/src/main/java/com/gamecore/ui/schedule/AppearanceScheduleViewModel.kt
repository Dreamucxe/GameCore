package com.gamecore.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.plugin.PluginPermission
import com.gamecore.core.plugin.PluginRegistry
import com.gamecore.core.plugin.PluginSection
import com.gamecore.core.schedule.AppearanceSchedule
import com.gamecore.core.schedule.AppearanceWindow
import com.gamecore.core.schedule.DashboardTarget
import com.gamecore.core.schedule.GameAppearanceOverride
import com.gamecore.core.schedule.ResolvedAppearance
import com.gamecore.core.schedule.ThemeTarget
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.GameProfileRepository
import com.gamecore.domain.schedule.AppearanceScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ThemeOption(val pluginId: String, val label: String)

data class GameOption(val packageName: String, val label: String)

data class AppearanceScheduleUiState(
    val schedule: AppearanceSchedule = AppearanceSchedule(),
    val themes: List<ThemeOption> = emptyList(),
    val games: List<GameOption> = emptyList(),
    val manualShowBuiltInStats: Boolean = true,
    val resolved: ResolvedAppearance = ResolvedAppearance(),
)

/**
 * The schedule editor's state.
 *
 * The list of selectable themes is filtered by exactly the same rules the registry uses to resolve
 * one: active, declaring `theme`, and holding `change_ui`. Offering a plugin the registry would
 * reject would produce a schedule row that looks valid but never takes effect.
 */
@HiltViewModel
class AppearanceScheduleViewModel @Inject constructor(
    private val preferences: SecurePreferenceStore,
    private val registry: PluginRegistry,
    private val profiles: GameProfileRepository,
    scheduler: AppearanceScheduler,
) : ViewModel() {

    val state: StateFlow<AppearanceScheduleUiState> = combine(
        preferences.appearanceSchedule,
        registry.installed,
        profiles.profiles,
        preferences.settings,
        scheduler.resolved,
    ) { schedule, installed, games, settings, resolved ->
        AppearanceScheduleUiState(
            schedule = schedule,
            themes = installed
                .asSequence()
                .filter { it.isActive }
                .filter { PluginSection.THEME in it.manifest.declares }
                .filter { it.grant.has(PluginPermission.CHANGE_UI) }
                .map { ThemeOption(it.id, it.name) }
                .sortedBy { it.label.lowercase() }
                .toList(),
            games = games
                .asSequence()
                .map { GameOption(it.packageName, it.label) }
                .sortedBy { it.label.lowercase() }
                .toList(),
            manualShowBuiltInStats = settings.showBuiltInHomeStats,
            resolved = resolved,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AppearanceScheduleUiState(),
    )

    fun setEnabled(enabled: Boolean) {
        preferences.updateAppearanceSchedule { it.copy(enabled = enabled) }
    }

    fun addWindow(
        label: String,
        startMinute: Int,
        endMinute: Int,
        theme: ThemeTarget?,
        dashboard: DashboardTarget?,
    ) {
        preferences.updateAppearanceSchedule { schedule ->
            schedule.copy(
                windows = schedule.windows + AppearanceWindow(
                    id = "window-${UUID.randomUUID()}",
                    label = label,
                    startMinute = startMinute,
                    endMinute = endMinute,
                    theme = theme,
                    dashboard = dashboard,
                ),
            )
        }
    }

    fun removeWindow(id: String) {
        preferences.updateAppearanceSchedule { schedule ->
            schedule.copy(windows = schedule.windows.filterNot { it.id == id })
        }
    }

    fun putGameOverride(
        packageName: String,
        label: String,
        theme: ThemeTarget?,
        dashboard: DashboardTarget?,
    ) {
        preferences.updateAppearanceSchedule { schedule ->
            val replacement = GameAppearanceOverride(
                packageName = packageName,
                label = label,
                theme = theme,
                dashboard = dashboard,
            )
            schedule.copy(
                gameOverrides = schedule.gameOverrides
                    .filterNot { it.packageName == packageName } + replacement,
            )
        }
    }

    fun removeGameOverride(packageName: String) {
        preferences.updateAppearanceSchedule { schedule ->
            schedule.copy(
                gameOverrides = schedule.gameOverrides.filterNot {
                    it.packageName == packageName
                },
            )
        }
    }

    fun clearSchedule() {
        preferences.updateAppearanceSchedule { AppearanceSchedule() }
    }
}
