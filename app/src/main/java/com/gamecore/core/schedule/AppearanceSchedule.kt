package com.gamecore.core.schedule

import com.gamecore.core.common.TextSanitizer
import java.util.Locale

/**
 * A target for the app's theme while a schedule window or game override is in force.
 *
 * [Default] means GameCore's own theme, not "leave whatever is there". The distinction matters:
 * a window that says "GameCore default" must be able to pull the app out of a plugin theme, while
 * a window that says nothing about the theme should leave the user's manual choice alone. The
 * nullable field on [AppearanceWindow] carries the second state.
 */
sealed interface ThemeTarget {
    data object Default : ThemeTarget
    data class Plugin(val pluginId: String) : ThemeTarget
}

/** A target for Home's dashboard mode: the host's own stats, or the plugin dashboard. */
sealed interface DashboardTarget {
    data object BuiltIn : DashboardTarget
    data object PluginDashboard : DashboardTarget
}

/**
 * One daily time window.
 *
 * Minutes are local wall-clock minutes since midnight. [startMinute] == [endMinute] is a 24-hour
 * window; [startMinute] > [endMinute] crosses midnight. The model deliberately has no timezone:
 * "22:00" means the phone's own clock, which is what a user means when they set a night theme.
 */
data class AppearanceWindow(
    val id: String,
    val label: String,
    val startMinute: Int,
    val endMinute: Int,
    val theme: ThemeTarget? = null,
    val dashboard: DashboardTarget? = null,
) {
    fun contains(minuteOfDay: Int): Boolean {
        val minute = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        return when {
            startMinute == endMinute -> true
            startMinute < endMinute -> minute >= startMinute && minute < endMinute
            else -> minute >= startMinute || minute < endMinute
        }
    }

    val timeLabel: String
        get() = "${formatMinute(startMinute)}–${formatMinute(endMinute)}"

    fun normalised(index: Int): AppearanceWindow = copy(
        id = id.trim().take(64).ifBlank { "window-$index" },
        label = TextSanitizer.sanitizeName(label, MAX_LABEL_LENGTH).ifBlank { "Window ${index + 1}" },
        startMinute = startMinute.coerceIn(0, MINUTES_PER_DAY - 1),
        endMinute = endMinute.coerceIn(0, MINUTES_PER_DAY - 1),
        theme = theme?.normalised(),
        dashboard = dashboard,
    )

    companion object {
        const val MAX_LABEL_LENGTH = 40
        const val MINUTES_PER_DAY = 24 * 60

        fun formatMinute(minute: Int): String {
            val safe = minute.coerceIn(0, MINUTES_PER_DAY - 1)
            return String.format(Locale.US, "%02d:%02d", safe / 60, safe % 60)
        }

        fun parseMinute(raw: String): Int? {
            val parts = raw.trim().split(':')
            if (parts.size != 2) return null
            val hour = parts[0].toIntOrNull() ?: return null
            val minute = parts[1].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return hour * 60 + minute
        }
    }
}

/** A per-game override. It outranks every time window while [packageName] is the tracked game. */
data class GameAppearanceOverride(
    val packageName: String,
    val label: String,
    val theme: ThemeTarget? = null,
    val dashboard: DashboardTarget? = null,
) {
    fun normalised(): GameAppearanceOverride {
        val safePackage = TextSanitizer.validatePackageName(packageName).orEmpty()
        return copy(
            packageName = safePackage,
            label = TextSanitizer.sanitizeName(label, AppearanceWindow.MAX_LABEL_LENGTH)
                .ifBlank { safePackage },
            theme = theme?.normalised(),
            dashboard = dashboard,
        )
    }
}

/**
 * The user's schedule. Empty and disabled by default: a scheduler that starts changing the app
 * without being asked would be exactly the kind of silent behaviour the rest of this app avoids.
 *
 * Precedence is resolved per surface, not per row: a game override's theme wins over a window's
 * theme, which wins over the user's manual selection. If a game override names only a dashboard,
 * its missing theme does not block the window's theme; that is what makes the two surfaces
 * independently schedulable without a second "no change" sentinel on the override itself.
 */
data class AppearanceSchedule(
    val enabled: Boolean = false,
    val windows: List<AppearanceWindow> = emptyList(),
    val gameOverrides: List<GameAppearanceOverride> = emptyList(),
) {
    fun normalised(): AppearanceSchedule {
        val seenWindowIds = HashSet<String>()
        val windows = windows
            .take(MAX_WINDOWS)
            .mapIndexed { index, window -> window.normalised(index) }
            .filter { seenWindowIds.add(it.id) }
        val seenPackages = HashSet<String>()
        val overrides = gameOverrides
            .take(MAX_GAME_OVERRIDES)
            .map { it.normalised() }
            .filter { it.packageName.isNotEmpty() && seenPackages.add(it.packageName) }
        return copy(windows = windows, gameOverrides = overrides)
    }

    fun windowAt(minuteOfDay: Int): AppearanceWindow? =
        windows.firstOrNull { it.contains(minuteOfDay) }

    fun overrideFor(packageName: String?): GameAppearanceOverride? =
        packageName?.let { pkg -> gameOverrides.firstOrNull { it.packageName == pkg } }

    companion object {
        const val MAX_WINDOWS = 8
        const val MAX_GAME_OVERRIDES = 32
    }
}

private fun ThemeTarget.normalised(): ThemeTarget = when (this) {
    ThemeTarget.Default -> ThemeTarget.Default
    is ThemeTarget.Plugin -> {
        val id = pluginId.trim()
        if (id.isEmpty() || id.length > 200) ThemeTarget.Default else ThemeTarget.Plugin(id)
    }
}

/**
 * The resolved override for one moment, already stripped of targets the host cannot honour.
 *
 * Null theme/dashboard means "no override on this surface", so the caller falls back to the manual
 * selection. [source] is for the schedule screen's "Now" card; it is a host-authored label, never
 * plugin text.
 */
data class ResolvedAppearance(
    val theme: ThemeTarget? = null,
    val dashboard: DashboardTarget? = null,
    val source: AppearanceSource = AppearanceSource.MANUAL,
)

enum class AppearanceSource(val label: String) {
    GAME("Game override"),
    WINDOW("Time window"),
    MANUAL("Manual"),
}

/**
 * Pure precedence rule: game-specific, then the first matching time window, then manual.
 *
 * [availableThemeIds] is the set of plugins that can actually supply a theme right now — installed,
 * enabled, compatible and holding `change_ui`. A target naming anything else is skipped rather than
 * applied and silently rejected by the registry, so the next layer (the window, or manual) gets its
 * chance. That is what keeps a disabled plugin from leaving the app stuck on a theme it no longer
 * has.
 */
fun resolveAppearance(
    schedule: AppearanceSchedule,
    minuteOfDay: Int,
    gamePackage: String?,
    availableThemeIds: Set<String>,
): ResolvedAppearance {
    if (!schedule.enabled) return ResolvedAppearance()

    val game = schedule.overrideFor(gamePackage)
    val window = schedule.windowAt(minuteOfDay)

    val gameTheme = game?.theme?.takeIf { it.isAvailable(availableThemeIds) }
    val windowTheme = window?.theme?.takeIf { it.isAvailable(availableThemeIds) }
    val gameDashboard = game?.dashboard
    val windowDashboard = window?.dashboard

    val theme = gameTheme ?: windowTheme
    val dashboard = gameDashboard ?: windowDashboard

    // The source describes where the value that actually won came from, not merely whether a
    // higher-priority row existed. A game override whose theme names a disabled plugin must not
    // make the card say "Game override" while the visible theme came from the window underneath.
    val source = when {
        gameTheme != null || gameDashboard != null -> AppearanceSource.GAME
        windowTheme != null || windowDashboard != null -> AppearanceSource.WINDOW
        else -> AppearanceSource.MANUAL
    }
    return ResolvedAppearance(theme = theme, dashboard = dashboard, source = source)
}

private fun ThemeTarget.isAvailable(availableThemeIds: Set<String>): Boolean = when (this) {
    ThemeTarget.Default -> true
    is ThemeTarget.Plugin -> pluginId in availableThemeIds
}
