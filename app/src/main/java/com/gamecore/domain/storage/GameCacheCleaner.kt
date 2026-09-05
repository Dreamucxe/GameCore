package com.gamecore.domain.storage

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.common.Precision
import com.gamecore.core.model.CacheClearReport
import com.gamecore.core.model.GameStorage
import com.gamecore.core.model.InstalledApp
import com.gamecore.core.system.AppStorageControls
import com.gamecore.core.system.ControlOutcome
import com.gamecore.core.system.InstalledAppLister
import com.gamecore.data.repository.GameProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Measures what each of the user's games is holding in cache, and clears the part that is safe to
 * clear.
 *
 * The order of operations is the whole feature, so it is written out before the code:
 *
 *  1. Take the list of installed apps and keep the ones that are the user's games — a declared game,
 *     or an app with a GameCore profile. Everything else on the device is somebody else's business.
 *  2. Measure each one. Three figures, each separately obtainable or not: the cache, the part of it in
 *     shared storage, and the data that is not cache and will not be touched.
 *  3. On a tap, read the cache figure, delete `Android/data/<pkg>/cache`, wait for the platform's
 *     accounting to catch up, and read it again.
 *  4. Report the difference between the two readings — not the figure that was there before, and not
 *     the exit code of the delete.
 *
 * Step 4 is the difference between this and the cache cleaners on the store, and step 1 is the reason
 * it cannot become one of them: there is no "clean everything" button here, because there is no
 * command in [com.gamecore.core.shizuku.ShellCommand] that would implement one.
 *
 * What is out of reach is stated rather than worked around. The copy of a game's cache inside its
 * private data directory cannot be opened by a shell running as the shell user, and GameCore holds no
 * root path to anything, so that part is reported as remaining, with Android's own storage page for
 * the app offered next to it. `pm clear` would reach it and would take the user's saves with it, which
 * is why it is absent from the command set and not merely unused.
 *
 * Nothing here decides which apps belong in the list or in what order. That is [CacheSelection], kept
 * separate for the reason [com.gamecore.domain.memory.ReclaimFilter] is: so it can be checked on a
 * machine with no device attached.
 */
@Singleton
class GameCacheCleaner @Inject constructor(
    private val storage: AppStorageControls,
    private val apps: InstalledAppLister,
    private val profiles: GameProfileRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Whether the shell the clearing needs is running, so the screen can say so before a tap. */
    suspend fun canClear(): Boolean = storage.canClearSharedCache()

    /**
     * Every game worth listing, measured, largest cache first.
     *
     * System apps are included in the enumeration and then filtered by the same rule as everything
     * else, because a game preloaded by the manufacturer is still a game and still fills a cache. What
     * keeps the list short is the rule, not the flag.
     *
     * A measurement that could not be taken leaves its row in place with the reason on it. Dropping
     * those rows would turn "usage access is not granted" into "you have no games", which is a
     * different and much more confusing statement.
     */
    suspend fun list(): List<GameStorage> = withContext(io) {
        val profiled = profiles.profiles.first().mapTo(HashSet()) { it.packageName }
        val installed = apps.list(includeSystemApps = true)
        val measured = CacheSelection.candidates(installed, profiled).map { app ->
            storage.read(app.packageName).describing(
                packageName = app.packageName,
                label = app.label,
                hasProfile = app.packageName in profiled,
                isDeclaredGame = app.isLikelyGame,
            )
        }
        CacheSelection.ordered(measured)
    }

    /**
     * Clears one game's shared-storage cache and reports what that achieved.
     *
     * Never throws: this is a button on a list, and a list that dies because one row's measurement
     * failed is worse than a row that says it could not be measured.
     *
     * The two readings bracket the delete as tightly as they can, and the wait between the command and
     * the second one is not tuning. The platform's storage figures come from the same accounting the
     * app's own storage page reads, which is updated behind the delete rather than by it, so a reading
     * taken immediately would measure the cache that was there a moment ago and report that nothing
     * was freed.
     *
     * Measurement is not a precondition for the delete. Usage access being absent costs the figure,
     * not the action — the report then says the cache was cleared and that GameCore could not measure
     * what that freed, which is exactly what happened.
     */
    suspend fun clear(packageName: String): CacheClearReport = withContext(io) {
        val label = apps.describe(packageName)?.label ?: packageName
        val before = storage.read(packageName).cacheBytes
        when (val outcome = storage.clearSharedCache(packageName)) {
            is ControlOutcome.Applied -> Unit
            is ControlOutcome.RequiresAccess -> return@withContext CacheClearReport.NotAttempted(
                packageName = packageName,
                label = label,
                reason = outcome.detail,
                needsShizuku = outcome.needsShizuku,
            )
            is ControlOutcome.Unsupported -> return@withContext CacheClearReport.NotAttempted(
                packageName = packageName,
                label = label,
                reason = outcome.detail,
            )
            is ControlOutcome.Failed -> return@withContext CacheClearReport.Failed(
                packageName = packageName,
                label = label,
                detail = outcome.detail,
            )
        }
        delay(SETTLE_MILLIS)
        val after = storage.read(packageName).cacheBytes
        CacheClearReport.Cleared(
            packageName = packageName,
            label = label,
            freedBytes = freedBetween(before, after),
            remainingCacheBytes = after,
        )
    }

    /**
     * How much smaller the cache got, or the reason there is no such figure.
     *
     * Before minus after, so a positive number means bytes went away. [Precision.SAMPLED] because
     * both readings are exact and their difference is not a reading at all: the game may write to its
     * cache in the interval, and on a device where it does, the honest figure is the smaller one this
     * produces rather than the size of the directory that was deleted.
     *
     * A missing reading on either side is the answer, propagated as it arrived. Reporting it as zero
     * would say nothing was freed, which is a claim, and a different one from not knowing.
     */
    private fun freedBetween(before: Observed<Long>, after: Observed<Long>): Observed<Long> = when {
        before is Observed.Value && after is Observed.Value ->
            Observed.of(before.value - after.value, DataSource.STORAGE_STATS, Precision.SAMPLED)
        before !is Observed.Value -> before
        else -> after
    }

    private companion object {

        /**
         * How long to wait after the delete before measuring again.
         *
         * The same interval the memory reclaim waits for process teardown, for the same kind of
         * reason: the platform's own bookkeeping runs behind the operation, not with it. Short enough
         * that a row does not appear stuck, long enough that the second reading is of the directory
         * that is now gone rather than of the one that was there when the command was issued.
         */
        const val SETTLE_MILLIS = 700L
    }
}

/**
 * Which apps belong on the storage screen, and in what order.
 *
 * Pure, and with no Android type in any signature, so every rule below is checkable without a device.
 * [InstalledApp] and [GameStorage] are flat records the boundary produced; nothing here can reach a
 * `PackageManager` even if a later change wanted it to.
 */
internal object CacheSelection {

    /**
     * The user's games: an app that declares itself one, or an app they made a profile for.
     *
     * The second half matters more than the first. `CATEGORY_GAME` is the developer's own declaration
     * and plenty of large games leave it unset, so a list built on the flag alone would be missing the
     * one the user actually wants to clear. A profile is the user having said this is a game, which is
     * better evidence than the manifest, and it is kept even when the profile is switched off — a
     * disabled profile is still a statement about what the app is.
     *
     * GameCore itself is already absent: the lister drops its own package, and an app that offers to
     * clear its own cache to make a number move is doing the thing this feature exists not to do.
     */
    fun candidates(installed: List<InstalledApp>, profiledPackages: Set<String>): List<InstalledApp> =
        installed.filter { it.isLikelyGame || it.packageName in profiledPackages }

    /**
     * Largest cache first, then unmeasured, then alphabetical.
     *
     * The order answers the question the screen is opened with — what is taking up the space — and the
     * unmeasured rows go last rather than being treated as zero, because a row whose size could not be
     * read is not a row with nothing in it. Within either group the tiebreak is the label, so the list
     * does not reshuffle between visits when two games are holding the same amount.
     */
    fun ordered(rows: List<GameStorage>): List<GameStorage> = rows.sortedWith(
        compareByDescending<GameStorage> { it.measuredCache != null }
            .thenByDescending { it.measuredCache ?: 0L }
            .thenBy { it.label.lowercase() },
    )
}
