package com.gamecore.core.model

/**
 * The application in the foreground, and how GameCore found out.
 *
 * [source] is not decoration. From API 28 `ActivityManager.getRunningAppProcesses()`
 * returns only the calling app's own process, so there are exactly two routes to the
 * foreground package for a non-privileged app, and they differ in ways that matter to
 * the user:
 *
 *  * [ForegroundSource.USAGE_EVENTS] needs the usage-access appop, is accurate to the
 *    activity-resumed event, and is what the app uses when the user has granted it.
 *  * [ForegroundSource.SHELL_DUMP] needs Shizuku, and is available to a user who has
 *    Shizuku running but has not granted usage access.
 *
 * When neither is available, automatic game detection is reported as unavailable and
 * profiles are applied by hand. There is no third route, and the app does not pretend
 * there is: polling for a window title or inferring from CPU load would be a guess
 * dressed as detection.
 */
data class ForegroundApp(
    val packageName: String,
    val detectedAtMillis: Long,
    val source: ForegroundSource,
)

enum class ForegroundSource(val label: String) {
    USAGE_EVENTS("Usage access"),
    SHELL_DUMP("Shizuku"),
}

/**
 * Whether foreground detection can work at all right now, and what would fix it.
 *
 * Held separately from [ForegroundApp] because the answer "we cannot see the
 * foreground app" has to be presentable on the Games screen before any game runs —
 * a switch that silently does nothing is worse than one that explains itself.
 */
sealed interface DetectionAvailability {

    data class Available(val source: ForegroundSource) : DetectionAvailability

    /** Neither route is open. [remedy] names the one the user is closest to having. */
    data class Unavailable(val reason: String, val remedy: DetectionRemedy) : DetectionAvailability

    val isAvailable: Boolean get() = this is Available

    companion object {
        val NO_ACCESS = Unavailable(
            reason = "Android does not let an app see which other app is in the foreground " +
                "without usage access. Grant it and GameCore can apply a profile the moment a " +
                "game starts.",
            remedy = DetectionRemedy.GRANT_USAGE_ACCESS,
        )
    }
}

enum class DetectionRemedy(val label: String) {
    GRANT_USAGE_ACCESS("Grant usage access"),
    START_SHIZUKU("Start Shizuku"),
}

/**
 * One installed application, as the game picker shows it.
 *
 * Carries only what that screen draws — §24A.2: a DTO the UI consumes must not be a
 * passthrough of a platform object. An `ApplicationInfo` holds the app's data
 * directory, its uid, its native library path and its full flag set, none of which a
 * list row needs and all of which would then be one field access away from any
 * Composable.
 *
 * [isLikelyGame] is a heuristic and named as one. It is true when the package declares
 * `ApplicationInfo.CATEGORY_GAME` — which is the developer's own declaration, honest
 * but frequently unset — or when the launcher intent carries the game category. The
 * picker uses it to sort, never to filter: an app wrongly judged not-a-game would
 * otherwise be impossible to configure, and the user knows better than the heuristic.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val isLikelyGame: Boolean,
    val isSystemApp: Boolean,
    val versionName: String?,
    /**
     * The package's last-update time in epoch milliseconds, taken from
     * `PackageInfo.lastUpdateTime`. Null when it could not be read.
     */
    val lastUpdateTime: Long? = null,
)
