package com.gamecore.core.system

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.model.AppStorageReading
import com.gamecore.core.permissions.PermissionChecker
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The platform boundary for how much storage another app is using, and for deleting the one
 * directory of it GameCore is willing to delete.
 *
 * Both halves are here together because the second is only defensible in the presence of the first.
 * A clear button with no measurement either side of it can report success from an exit code, and an
 * exit code from `rm -rf` says nothing about whether there was anything there — `-f` is what makes an
 * absent directory succeed. So the reading is not a nicety attached to the feature; it is the only
 * evidence the feature ever produces.
 *
 * Nothing here decides which apps to show or in what order. That is
 * [com.gamecore.domain.storage.CacheSelection]'s question, kept out of this file for the reason
 * [com.gamecore.domain.memory.ReclaimFilter] is kept out of [ProcessControls]: so it can be answered
 * in a test with no device present.
 */
@Singleton
class AppStorageControls @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionChecker,
    private val shell: ElevatedShell,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    // ---------------------------------------------------------------------- reading

    /**
     * What one package is using, as three separate readings.
     *
     * `queryStatsForPackage` is the only API that will answer this for another app, and it wants
     * usage access — the same grant GameCore already asks for to know which game is in the
     * foreground, so a user who has set the app up at all has already given it. Without it the answer
     * is [Observed.needsPermission] for all three figures and the screen offers the grant, rather
     * than a list of zeroes with a button under each.
     *
     * The breakdown is where the API levels diverge. `cacheBytes` and `dataBytes` are there from 26,
     * and `dataBytes` includes the cache, so the untouched figure is their difference. `externalCacheBytes`
     * — the part in shared storage, which is the part GameCore can reach — arrived in Android 12. On
     * anything older the clearable figure is [Observed.needsNewerApi]: the total is known, the share
     * of it a delete would reach is not, and inventing a fraction of a number the user is about to
     * act on would be worse than saying so.
     */
    suspend fun read(packageName: String): AppStorageReading = withContext(io) {
        val valid = TextSanitizer.validatePackageName(packageName)
            ?: return@withContext AppStorageReading.unavailable(
                Observed.Failed("That is not a usable package name"),
            )
        if (!permissions.hasUsageAccess()) {
            return@withContext AppStorageReading.unavailable(
                Observed.needsPermission("Measuring what a game is using needs usage access."),
            )
        }
        val manager = systemService<StorageStatsManager>(Context.STORAGE_STATS_SERVICE)
            ?: return@withContext AppStorageReading.unavailable(
                Observed.notPresent("This device has no storage-stats service"),
            )
        try {
            val stats = manager.queryStatsForPackage(
                StorageManager.UUID_DEFAULT,
                valid,
                Process.myUserHandle(),
            )
            val cache = stats.cacheBytes.coerceAtLeast(0L)
            val data = stats.dataBytes.coerceAtLeast(0L)
            AppStorageReading(
                cacheBytes = Observed.of(cache, DataSource.STORAGE_STATS),
                clearableCacheBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Observed.of(
                        stats.externalCacheBytes.coerceIn(0L, cache),
                        DataSource.STORAGE_STATS,
                    )
                } else {
                    Observed.needsNewerApi(
                        "Android ${Build.VERSION.RELEASE} does not say how much of the cache is " +
                            "in shared storage.",
                    )
                },
                untouchedBytes = Observed.of((data - cache).coerceAtLeast(0L), DataSource.STORAGE_STATS),
            )
        } catch (error: PackageManager.NameNotFoundException) {
            AppStorageReading.unavailable(Observed.notPresent("That app is not installed"))
        } catch (error: SecurityException) {
            AppStorageReading.unavailable(
                Observed.needsPermission("Usage access was withdrawn while GameCore was reading."),
            )
        } catch (error: Throwable) {
            AppStorageReading.unavailable(
                Observed.Failed("That app's storage could not be measured", error::class.simpleName),
            )
        }
    }

    // --------------------------------------------------------------------- deleting

    /** Whether the shell that does the deleting is running, so a screen can say so up front. */
    suspend fun canClearSharedCache(): Boolean = shell.isAvailable()

    /**
     * Deletes one package's shared-storage cache directory.
     *
     * [ControlOutcome.Applied] means the command exited zero, and that is all it means: the caller
     * reads the cache figure again afterwards and reports the difference, because `rm -rf` on a
     * directory that was already empty and `rm -rf` on one holding two gigabytes are the same exit
     * code. Nothing here converts that into a claim.
     *
     * The user id is derived from GameCore's own uid rather than assumed to be zero, so an install in
     * a secondary user or a work profile builds a path to that user's shared storage instead of
     * silently pointing at the owner's — where the directory either does not exist, which would be
     * reported as nothing freed, or belongs to a different copy of the game, which would be worse.
     *
     * A shell that is not running is [ControlOutcome.RequiresAccess] with the flag that makes the
     * screen offer Shizuku. It is not a failure, and the screen keeps offering Android's own storage
     * page for the app either way — that route needs nothing granted and reaches more than this does.
     */
    suspend fun clearSharedCache(packageName: String): ControlOutcome = withContext(io) {
        val command = ShellCommand.clearSharedCache(packageName, currentUserId())
            ?: return@withContext ControlOutcome.Failed("That is not a usable package name.")
        if (!shell.isAvailable()) {
            return@withContext ControlOutcome.RequiresAccess(
                "Clearing another app's cache needs Shizuku.",
                needsShizuku = true,
            )
        }
        val result = shell.execute(command, ElevatedShell.LONG_TIMEOUT)
        if (result.isSuccess) {
            ControlOutcome.Applied()
        } else {
            ControlOutcome.Failed(result.failureReason())
        }
    }

    // ------------------------------------------------------------------------ utils

    /**
     * The Android user GameCore is installed in.
     *
     * `uid / 100000` is the platform's own arithmetic for this — `UserHandle.getUserId` is hidden
     * API and `myUserHandle().hashCode()` happens to return the id but is not documented to — and it
     * is read from GameCore's own uid, which is a fact about this process rather than a guess.
     */
    private fun currentUserId(): Int = Process.myUid() / PER_USER_RANGE

    @Suppress("UNCHECKED_CAST")
    private fun <T> systemService(name: String): T? = try {
        context.getSystemService(name) as? T
    } catch (error: Throwable) {
        null
    }

    private companion object {
        /** `UserHandle.PER_USER_RANGE`, which is public in AOSP and hidden from the SDK. */
        const val PER_USER_RANGE = 100_000
    }
}
