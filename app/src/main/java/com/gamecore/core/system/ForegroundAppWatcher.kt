package com.gamecore.core.system

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.DetectionAvailability
import com.gamecore.core.model.DetectionRemedy
import com.gamecore.core.model.ForegroundApp
import com.gamecore.core.model.ForegroundSource
import com.gamecore.core.permissions.PermissionChecker
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which app is in the foreground.
 *
 * From API 28 `ActivityManager.getRunningAppProcesses()` returns only the caller's own
 * process, so for a non-privileged app there are exactly two routes and no third:
 *
 *  1. `UsageStatsManager.queryEvents()`, gated behind the usage-access appop. Accurate to
 *     the activity-resumed event, and the route used whenever the user has granted it.
 *  2. `dumpsys activity activities` through Shizuku, for a user who runs Shizuku but has
 *     not granted usage access.
 *
 * When neither is open, [availability] says so and names the remedy. Automatic detection
 * is then reported as unavailable and profiles are applied by hand — the app does not fall
 * back to inferring a game from CPU load or from a window title, which would be a guess
 * presented as detection.
 *
 * The event query, not `queryUsageStats`. Usage *stats* are aggregated into buckets whose
 * granularity the platform chooses; the most recently used package in an interval bucket
 * is not the package on screen now, and a game that has been open for an hour behind a
 * paused GameCore would be missed. Events carry `ACTIVITY_RESUMED` with a timestamp, which
 * is the actual question.
 */
@Singleton
class ForegroundAppWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionChecker,
    private val shell: ElevatedShell,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Whether detection can work right now, and what the user would have to do.
     *
     * Usage access is preferred when both are open: it is a platform event stream rather
     * than a text dump, so it costs no shell round trip and cannot be broken by a vendor
     * changing a dump format.
     */
    suspend fun availability(): DetectionAvailability = withContext(io) {
        when {
            permissions.hasUsageAccess() ->
                DetectionAvailability.Available(ForegroundSource.USAGE_EVENTS)
            shell.isAvailable() ->
                DetectionAvailability.Available(ForegroundSource.SHELL_DUMP)
            else -> DetectionAvailability.NO_ACCESS
        }
    }

    /**
     * The foreground package, or the reason it could not be determined.
     *
     * Both routes are tried in preference order rather than the first being trusted: a
     * usage-access grant can be revoked between the availability check and the query, and
     * an empty event window is a normal outcome on a device that has been idle.
     */
    suspend fun current(): Observed<ForegroundApp> = withContext(io) {
        if (permissions.hasUsageAccess()) {
            val fromEvents = fromUsageEvents()
            if (fromEvents is Observed.Value) return@withContext fromEvents
        }
        if (shell.isAvailable()) {
            val fromShell = fromShellDump()
            if (fromShell is Observed.Value) return@withContext fromShell
        }
        if (!permissions.hasUsageAccess() && !shell.isAvailable()) {
            return@withContext Observed.needsPermission(
                "Seeing which app is in the foreground needs usage access, or Shizuku.",
            )
        }
        Observed.Failed("The foreground app could not be determined")
    }

    /**
     * Convenience for the detection service's poll loop: the package name only, or null.
     *
     * Deliberately narrower than [current] because the service compares it against saved
     * profiles and has no use for the source or the timestamp. The distinction between
     * "nothing is in the foreground" and "we cannot see the foreground" belongs to
     * [availability], which the service checks before it starts polling at all.
     */
    suspend fun currentPackage(): String? = current().valueOrNull?.packageName

    // ------------------------------------------------------------------ usage events

    /**
     * The most recent `ACTIVITY_RESUMED` in a short trailing window.
     *
     * The window has to be wider than the poll interval — a resume that happened between
     * two polls must still be found — but not so wide that a game the user left five
     * minutes ago is reported as foreground. [LOOKBACK_MILLIS] is a minute, which covers
     * any sane interval and is short enough that a stale answer cannot outlive one.
     *
     * Every resume in the window is walked and the last one kept, rather than breaking at
     * the first match: `queryEvents` returns events in ascending time order, so the first
     * match is the oldest.
     */
    private fun fromUsageEvents(): Observed<ForegroundApp> {
        val manager = try {
            context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        } catch (error: Throwable) {
            null
        } ?: return Observed.notPresent("This device has no usage-stats service")

        val now = System.currentTimeMillis()
        return try {
            val events = manager.queryEvents(now - LOOKBACK_MILLIS, now)
            val event = UsageEvents.Event()
            var latestPackage: String? = null
            var latestTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                val pkg = event.packageName ?: continue
                if (event.timeStamp >= latestTime) {
                    latestTime = event.timeStamp
                    latestPackage = pkg
                }
            }
            val resolved = latestPackage
                ?: return Observed.Failed("No app came to the foreground in the last minute")
            Observed.of(
                ForegroundApp(
                    packageName = resolved,
                    detectedAtMillis = latestTime,
                    source = ForegroundSource.USAGE_EVENTS,
                ),
                com.gamecore.core.common.DataSource.USAGE_STATS,
            )
        } catch (error: SecurityException) {
            Observed.needsPermission("Usage access was revoked")
        } catch (error: Throwable) {
            Observed.Failed("Usage events could not be read", error.message)
        }
    }

    // -------------------------------------------------------------------- shell dump

    private suspend fun fromShellDump(): Observed<ForegroundApp> {
        val result = shell.execute(ShellCommand.TopActivity)
        if (!result.isSuccess) {
            return Observed.Failed("The activity dump could not be read", result.failureReason())
        }
        return when (val parsed = DumpsysParsers.parseForegroundPackage(result.stdout)) {
            is Observed.Value -> Observed.of(
                ForegroundApp(
                    packageName = parsed.value,
                    detectedAtMillis = System.currentTimeMillis(),
                    source = ForegroundSource.SHELL_DUMP,
                ),
                parsed.source,
            )
            is Observed.Restricted -> parsed
            is Observed.Failed -> parsed
        }
    }

    /**
     * The remedy the user is closest to having, for the button on the Games screen.
     *
     * Shizuku already running but its permission not granted is one tap away; usage access
     * is three Settings screens away but needs nothing installed. So an installed-and-idle
     * Shizuku wins, and otherwise the answer is usage access.
     */
    suspend fun nearestRemedy(): DetectionRemedy = withContext(io) {
        if (shell.isAvailable()) DetectionRemedy.START_SHIZUKU else DetectionRemedy.GRANT_USAGE_ACCESS
    }

    private companion object {
        /** Wide enough to survive any poll interval, short enough not to go stale. */
        const val LOOKBACK_MILLIS = 60_000L
    }
}
