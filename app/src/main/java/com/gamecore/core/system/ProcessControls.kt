package com.gamecore.core.system

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.model.AppProcessState
import com.gamecore.core.permissions.PermissionChecker
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The platform boundary for closing background applications, and for everything that has to be
 * read before one is closed.
 *
 * Every method here asks a question about *other* applications, which is the part of Android that
 * has been closing steadily since API 28: `getRunningAppProcesses` returns only the caller's own
 * process, `getRunningServices` is deprecated and answers only for the caller, and package
 * visibility on API 30 hid most of what was left. So there are two tiers here and nothing between
 * them.
 *
 * With an elevated shell, [runningApps] is the platform's own process list carrying the platform's
 * own word for what each process is doing, [close] is `am kill`, and the list can be read again
 * afterwards. That second read is the only reason a count of apps closed can be called confirmed.
 *
 * Without one, [recentlyUsedPackages] is the honest substitute — a usage event says the user had an
 * app open, which is evidence that it holds a process and not proof — and [requestClose] is
 * `ActivityManager.killBackgroundProcesses`, which returns nothing. Callers report what that path
 * did as asked for, never as done.
 *
 * Nothing here decides anything. Which of these packages may be closed is
 * [com.gamecore.domain.memory.ReclaimFilter]'s question, kept out of this file so that it can be
 * answered in a test with no device present.
 */
@Singleton
class ProcessControls @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionChecker,
    private val shell: ElevatedShell,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    // ------------------------------------------------------------------- enumeration

    /**
     * Every package that has a running process, against what its processes are doing.
     *
     * Needs the shell, and has no standard-Android equivalent — this is precisely the read API 28
     * closed — so without one the answer is [Observed.needsElevation] and the caller drops to
     * [recentlyUsedPackages]. What it must not drop to is the installed-app list: that is a list of
     * apps that *might* be running, and treating it as this one is how a booster arrives at a
     * number of apps closed that it never saw open.
     *
     * A dump that arrives but does not parse is [Observed.Failed] rather than an empty map, because
     * the filter reads an empty map as "nothing is running" and would then have nothing to protect.
     */
    suspend fun runningApps(): Observed<Map<String, AppProcessState>> = withContext(io) {
        if (!shell.isAvailable()) {
            return@withContext Observed.needsElevation(
                "Reading which apps are running needs Shizuku.",
            )
        }
        val result = shell.execute(ShellCommand.RunningProcesses, ElevatedShell.LONG_TIMEOUT)
        if (!result.isSuccess) {
            return@withContext Observed.Failed(
                "The list of running apps could not be read",
                result.failureReason(),
            )
        }
        DumpsysParsers.parseRunningProcesses(result.stdout)
    }

    /**
     * The pid of one package's main process.
     *
     * Deliberately here, beside [runningApps], and deliberately the *only* way anything in this app
     * turns a package name into a pid. CPU affinity is the one feature that needs one, and the
     * tempting alternative was a second read that answered just that question — which would have been
     * a second thing to keep in step with the platform's process-dump format, and a second place for
     * "which process is the game" to be answered differently. It is the same command, the same
     * timeout and the same dump [runningApps] reads; only the parse differs.
     *
     * Main process only, and [DumpsysParsers.parseMainProcessPid] says why at length: a game's render
     * thread is in the process named for the package, and `com.game:audio` is left where the scheduler
     * put it.
     */
    suspend fun mainProcessPid(packageName: String): Observed<Int> = withContext(io) {
        if (!shell.isAvailable()) {
            return@withContext Observed.needsElevation(
                "Finding a game's process needs Shizuku.",
            )
        }
        val result = shell.execute(ShellCommand.RunningProcesses, ElevatedShell.LONG_TIMEOUT)
        if (!result.isSuccess) {
            return@withContext Observed.Failed(
                "The list of running processes could not be read",
                result.failureReason(),
            )
        }
        DumpsysParsers.parseMainProcessPid(result.stdout, packageName)
    }

    /**
     * Packages the user had on screen inside the trailing window, most recent first.
     *
     * The standard tier's substitute for [runningApps], and weaker in a way worth being exact
     * about: an `ACTIVITY_RESUMED` event says an app was opened at a time, not that it still holds
     * a process — the platform may have reclaimed it since. So this is a list of packages worth
     * *offering* to [requestClose], which is itself a request and not an instruction, and nothing
     * that comes out of this method is ever counted as an app closed.
     *
     * An empty list is a legitimate answer and not a failure: on a device the user has not touched
     * for a while there is genuinely nothing here, and the pass reports that it found nothing to
     * close rather than reporting that it could not look.
     *
     * The query [ForegroundAppWatcher] uses, over a longer window. The foreground app is the newest
     * of these events; the rest are the apps behind it.
     */
    suspend fun recentlyUsedPackages(
        withinMillis: Long = RECENT_WINDOW_MILLIS,
    ): Observed<List<String>> = withContext(io) {
        if (!permissions.hasUsageAccess()) {
            return@withContext Observed.needsPermission(
                "Choosing which apps to close without Shizuku needs usage access.",
            )
        }
        val manager = systemService<UsageStatsManager>(Context.USAGE_STATS_SERVICE)
            ?: return@withContext Observed.notPresent("This device has no usage-stats service")
        try {
            val now = System.currentTimeMillis()
            val events = manager.queryEvents(now - withinMillis, now)
            val oldestFirst = ArrayList<String>()
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                oldestFirst += TextSanitizer.validatePackageName(event.packageName) ?: continue
            }
            Observed.of(oldestFirst.asReversed().distinct(), DataSource.USAGE_STATS)
        } catch (error: SecurityException) {
            Observed.needsPermission("Usage access was withdrawn while GameCore was reading.")
        } catch (error: Throwable) {
            Observed.Failed("Recently used apps could not be read", error::class.simpleName)
        }
    }

    // ----------------------------------------------------------------------- memory

    /**
     * What the platform says is available to allocate, right now.
     *
     * `ActivityManager.MemoryInfo.availMem`, read once before the closing and once after, because
     * the difference between two of these is the only figure for "memory freed" that is measured
     * rather than assembled out of guesses about how large each closed app was.
     *
     * Deliberately not the composite reading the metrics layer builds: that one carries the
     * `/proc/meminfo` fields this has no use for, and its available figure is a plain `Long` where
     * zero cannot be told apart from a service that was not there. Here an unobtainable reading is
     * absent, and the report says it could not measure what it freed instead of claiming nothing
     * was freed.
     */
    suspend fun availableMemoryBytes(): Observed<Long> = withContext(io) {
        val manager = systemService<ActivityManager>(Context.ACTIVITY_SERVICE)
            ?: return@withContext Observed.notPresent("This device has no activity manager")
        try {
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            if (info.availMem > 0L) {
                Observed.of(info.availMem, DataSource.ACTIVITY_MANAGER)
            } else {
                Observed.Failed("The activity manager reported no available memory")
            }
        } catch (error: Throwable) {
            Observed.Failed("Available memory could not be read", error::class.simpleName)
        }
    }

    // ------------------------------------------------------------------ protections

    /**
     * Every package that can draw the home screen.
     *
     * Every one, rather than whichever is currently default, for two reasons. The set is what the
     * question is really about — closing any app that is acting as the home screen ends with a
     * black screen when the user leaves the game, and which of two installed launchers is default
     * this minute does not change that. And `resolveActivity` collapses to a chooser stub when the
     * user has not picked a default, which is a package name that protects nothing.
     *
     * An empty set is not "this device has no launcher". It means the query was refused, and the
     * caller treats it as a reason to leave the whole pass alone: a protection that cannot be
     * established cannot be honoured, and the feature is off by default precisely so that it is
     * allowed to decline.
     */
    suspend fun homeScreenPackages(): Set<String> = withContext(io) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val manager = context.packageManager
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            resolved.mapNotNullTo(LinkedHashSet()) {
                TextSanitizer.validatePackageName(it.activityInfo?.packageName)
            }
        } catch (error: Throwable) {
            emptySet()
        }
    }

    /**
     * The package supplying the keyboard, or null if it could not be determined.
     *
     * `Settings.Secure.DEFAULT_INPUT_METHOD` holds a flattened component name, and only the package
     * half of it is a thing that can be closed. Reading a Secure key needs no permission; writing
     * one does, and nothing here writes.
     *
     * Null means GameCore could not find out, not that there is no keyboard, and is treated the way
     * an unreadable process state is treated. Closing the keyboard leaves the user unable to type in
     * the game's own text fields until the platform restarts it, which is exactly the sort of damage
     * this feature exists to avoid doing.
     */
    suspend fun currentImePackage(): String? = withContext(io) {
        try {
            val component = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
            )
            TextSanitizer.validatePackageName(component?.substringBefore('/'))
        } catch (error: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------------- closing

    /**
     * Closes one package's background processes through the shell.
     *
     * [ControlOutcome.Applied] means the command ran and exited zero. It does not mean the app is
     * gone: `am kill` prints nothing, exits zero whether it ended a process or declined to, and has
     * no return value to read. The caller re-reads [runningApps] once, after the whole pass, and
     * takes its count from that — confirming here instead would cost one process dump per app.
     *
     * A malformed package name is refused before a command exists, and the refusal does not repeat
     * the name back: it arrived from a parsed dump or a list the user typed, and neither is text this
     * app puts on screen unsanitised.
     */
    suspend fun close(packageName: String): ControlOutcome = withContext(io) {
        val command = ShellCommand.killBackgroundApp(packageName)
            ?: return@withContext ControlOutcome.Failed("That is not a usable package name.")
        if (!shell.isAvailable()) {
            return@withContext ControlOutcome.RequiresAccess(
                "Closing background apps needs Shizuku.",
                needsShizuku = true,
            )
        }
        val result = shell.execute(command)
        if (result.isSuccess) ControlOutcome.Applied() else ControlOutcome.Failed(result.failureReason())
    }

    /**
     * Asks Android to close one package's background processes.
     *
     * The path with no Shizuku, and the reason a report ever says "asked Android to close" instead of
     * "closed". `killBackgroundProcesses` returns `void`: it may end several processes, one, or none,
     * and the caller is not told which. What it will not do is documented, and is the enforcement
     * this tier leans on — it spares the foreground app and everything the platform ranks above a
     * plain background service, which includes anything holding a foreground service. That is the
     * platform's own bound and not a check GameCore made, which is why the filter still runs first.
     *
     * `KILL_BACKGROUND_PROCESSES` is a normal permission granted at install, so a `SecurityException`
     * here means a vendor build withheld it. Reported as needing access, because that is what it is.
     */
    suspend fun requestClose(packageName: String): ControlOutcome = withContext(io) {
        val valid = TextSanitizer.validatePackageName(packageName)
            ?: return@withContext ControlOutcome.Failed("That is not a usable package name.")
        val manager = systemService<ActivityManager>(Context.ACTIVITY_SERVICE)
            ?: return@withContext ControlOutcome.Unsupported("This device has no activity manager.")
        try {
            manager.killBackgroundProcesses(valid)
            ControlOutcome.Applied()
        } catch (error: SecurityException) {
            ControlOutcome.RequiresAccess("This build does not let apps close background apps.")
        } catch (error: Throwable) {
            ControlOutcome.Failed("Android refused to close that app.")
        }
    }

    // ------------------------------------------------------------------------ utils

    @Suppress("UNCHECKED_CAST")
    private fun <T> systemService(name: String): T? = try {
        context.getSystemService(name) as? T
    } catch (error: Throwable) {
        null
    }

    companion object {

        /**
         * How far back [recentlyUsedPackages] looks.
         *
         * Half an hour, which is a guess at how long an app the user opened is likely to still be
         * holding a process, and is the reason that path reports what it asked for rather than what
         * it did. Nothing is decided from the window alone: the filter runs over whatever it returns.
         */
        const val RECENT_WINDOW_MILLIS = 30L * 60L * 1_000L
    }
}
