package com.gamecore.domain.schedule

import com.gamecore.core.common.ApplicationScope
import com.gamecore.core.plugin.PluginPermission
import com.gamecore.core.plugin.PluginRegistry
import com.gamecore.core.plugin.PluginSection
import com.gamecore.core.plugin.ScheduledTheme
import com.gamecore.core.schedule.DashboardTarget
import com.gamecore.core.schedule.ResolvedAppearance
import com.gamecore.core.schedule.ThemeTarget
import com.gamecore.core.schedule.resolveAppearance
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.domain.gaming.GamingCoordinator
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Applies the user's theme/dashboard schedule.
 *
 * The scheduler is deliberately an *override layer*, not a writer of the user's settings. A theme
 * target goes through [PluginRegistry.setScheduledTheme], which the registry resolves above the
 * manual selection; a dashboard target becomes [dashboardOverride], which Home reads above
 * `showBuiltInHomeStats`. Ending a window therefore reveals the manual choice unchanged, and a user
 * who switches a theme off on the Plugins screen still has that choice waiting for them.
 *
 * The schedule is re-resolved every half minute and on every game/profile/plugin change. That is
 * enough for a wall-clock window, and it costs one combine over already-hot flows. An unchanged
 * answer is cheap: the registry ignores a repeated scheduled theme, and Home's state flow does not
 * emit when the dashboard override is already in force.
 */
@Singleton
class AppearanceScheduler @Inject constructor(
    private val preferences: SecurePreferenceStore,
    private val plugins: PluginRegistry,
    private val gaming: GamingCoordinator,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val dashboardOverrideState = MutableStateFlow<Boolean?>(null)

    /**
     * `true` means show GameCore's own Home stats, `false` means the plugin dashboard, and null
     * means no schedule is overriding the manual switch. Home reads this as a layer above
     * `AppSettings.showBuiltInHomeStats`.
     */
    val dashboardOverride: StateFlow<Boolean?> = dashboardOverrideState.asStateFlow()

    private val resolvedState = MutableStateFlow(ResolvedAppearance())
    val resolved: StateFlow<ResolvedAppearance> = resolvedState.asStateFlow()

    private val started = MutableStateFlow(false)
    private val applyMutex = Mutex()

    /** Idempotent: the root may ask on every launch, and only one collector is ever needed. */
    fun start() {
        if (started.value) return
        started.value = true
        scope.launch {
            combine(
                preferences.appearanceSchedule,
                plugins.installed,
                plugins.isLoaded,
                gaming.gaming.map { it.playing }.distinctUntilChanged(),
                minuteTicker(),
            ) { schedule, installed, loaded, playing, minute ->
                if (!loaded) return@combine ResolvedAppearance()
                val availableThemes = installed
                    .asSequence()
                    .filter { it.isActive }
                    .filter { PluginSection.THEME in it.manifest.declares }
                    .filter { it.grant.has(PluginPermission.CHANGE_UI) }
                    .map { it.id }
                    .toSet()
                resolveAppearance(schedule, minute, playing, availableThemes)
            }
                .collect { apply(it) }
        }
    }

    /**
     * Applies one resolved value. Serialised so a quick schedule change and a tick cannot interleave
     * two registry writes; the last value wins, which is the correct one for a state override.
     */
    private suspend fun apply(value: ResolvedAppearance) = applyMutex.withLock {
        val scheduledTheme = when (val theme = value.theme) {
            null -> null
            ThemeTarget.Default -> ScheduledTheme.Default
            is ThemeTarget.Plugin -> ScheduledTheme.Plugin(theme.pluginId)
        }
        plugins.setScheduledTheme(scheduledTheme)
        dashboardOverrideState.value = when (value.dashboard) {
            null -> null
            DashboardTarget.BuiltIn -> true
            DashboardTarget.PluginDashboard -> false
        }
        resolvedState.value = value
    }

    /** The phone's wall clock, not UTC: a 22:00 window means 22:00 where the user is. */
    private fun minuteTicker(): Flow<Int> = flow {
        while (true) {
            val now = LocalTime.now()
            emit(now.hour * 60 + now.minute)
            // Half a minute keeps a minute-boundary transition from being up to a minute late
            // without turning the scheduler into a per-second wakeup.
            delay(TICK_MILLIS)
        }
    }

    private companion object {
        const val TICK_MILLIS = 30_000L
    }
}
