package com.gamecore.core.model

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.common.Precision
import com.gamecore.core.common.RestrictionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentence a user reads after pressing Clear, and the figures the row shows before they do.
 *
 * Every branch is pinned here because the whole feature is a claim about a deletion the user cannot
 * inspect. Three of the branches exist for the same reason they do in [MemoryReclaimReportTest]: a
 * delete whose effect could not be measured says so, a delete that freed nothing says that instead of
 * reporting the size of the directory it removed, and a cache the sandbox keeps out of reach is named
 * rather than quietly subtracted. An app that prints only its successful measurements is choosing which
 * of its own readings to believe.
 */
class CacheClearReportTest {

    @Test
    fun `a clear that freed space leads with the measured figure`() {
        val report = cleared(freedBytes = measured(220L * MB), remainingCacheBytes = measured(0L))
        assertEquals("Freed 220 MB.", report.message)
        assertEquals(220L * MB, report.freed)
        assertTrue(report.isCleared)
        assertFalse(report.hasUnreachableRemainder)
    }

    @Test
    fun `an effect that could not be measured is admitted rather than filled in`() {
        // Usage access withdrawn between the two readings. The delete still ran, and saying "freed 0"
        // here would be a claim about a device GameCore stopped being able to see.
        val report = cleared(
            freedBytes = Observed.needsPermission("Usage access was withdrawn while GameCore was reading."),
            remainingCacheBytes = Observed.needsPermission("Usage access was withdrawn."),
        )
        assertEquals(
            "Cleared the cache GameCore can reach, and could not measure what that freed.",
            report.message,
        )
        assertNull(report.freed)
        assertTrue(report.isCleared)
    }

    @Test
    fun `a clear that freed nothing says so, and a negative reading is not dressed up`() {
        val empty = cleared(freedBytes = measured(0L), remainingCacheBytes = measured(0L))
        assertEquals(
            "Nothing was freed: the part GameCore can reach was empty, or the game refilled it at once.",
            empty.message,
        )
        // A game writing to its cache between the two readings can make the second one larger. The
        // honest reading of that is "nothing was freed", not the absolute value of the difference.
        val refilled = cleared(freedBytes = measured(-8L * MB), remainingCacheBytes = measured(0L))
        assertEquals(empty.message, refilled.message)
        assertFalse(refilled.message.contains("8"))
    }

    @Test
    fun `cache the sandbox keeps out of reach is named, with who can clear it`() {
        val report = cleared(freedBytes = measured(220L * MB), remainingCacheBytes = measured(512L * MB))
        assertEquals(
            "Freed 220 MB. 512 MB of cache is inside the app's own storage, " +
                "which only Android can clear.",
            report.message,
        )
        assertTrue(report.hasUnreachableRemainder)
    }

    @Test
    fun `an unmeasured remainder is not turned into a claim either way`() {
        // Not "0 B of cache is inside the app's own storage", and not a second sentence saying the
        // remainder is unknown: the figure is missing, so the sentence about it is missing.
        val report = cleared(
            freedBytes = measured(40L * MB),
            remainingCacheBytes = Observed.Failed("StorageStatsManager stopped answering"),
        )
        assertEquals("Freed 40.0 MB.", report.message)
        assertFalse(report.hasUnreachableRemainder)
        assertNull(report.remaining)
    }

    @Test
    fun `the two reports that are not a clear carry their own reason and are not cleared`() {
        val notAttempted = CacheClearReport.NotAttempted(
            packageName = PACKAGE,
            label = LABEL,
            reason = "Clearing another app's cache needs Shizuku.",
            needsShizuku = true,
        )
        assertEquals("Clearing another app's cache needs Shizuku.", notAttempted.message)
        assertFalse(notAttempted.isCleared)
        assertTrue(notAttempted.needsShizuku)

        val failed = CacheClearReport.Failed(PACKAGE, LABEL, "The shell refused: permission denied")
        assertEquals("The shell refused: permission denied", failed.message)
        assertFalse(failed.isCleared)
    }

    @Test
    fun `a row invites clearing only once there is something to gain`() {
        // The threshold exists so a list of games is not eight buttons that each free four kilobytes.
        assertFalse(row(measured(64L * 1024L)).isWorthClearing)
        assertTrue(row(measured(512L * 1024L)).isWorthClearing)
        assertTrue(row(measured(900L * MB)).isWorthClearing)
        // An unmeasured cache is not a small one. The row says so by having no figure at all, and the
        // screen keeps the button offered because the delete does not need the measurement.
        val unmeasured = row(Observed.needsPermission("Needs usage access."))
        assertFalse(unmeasured.isWorthClearing)
        assertNull(unmeasured.measuredCache)
    }

    @Test
    fun `one refusal stands for all three figures, and survives being turned into a row`() {
        val reason = Observed.needsPermission("Measuring what a game is using needs usage access.")
        val reading = AppStorageReading.unavailable(reason)
        assertEquals(reason, reading.cacheBytes)
        assertEquals(reason, reading.clearableCacheBytes)
        assertEquals(reason, reading.untouchedBytes)

        val storage = reading.describing(
            packageName = PACKAGE,
            label = LABEL,
            hasProfile = true,
            isDeclaredGame = false,
        )
        assertEquals(PACKAGE, storage.packageName)
        assertEquals(LABEL, storage.label)
        assertTrue(storage.hasProfile)
        assertFalse(storage.isDeclaredGame)
        val restricted = storage.cacheBytes as Observed.Restricted
        assertEquals(RestrictionReason.PERMISSION_REQUIRED, restricted.reason)
    }

    @Test
    fun `a device that measures the cache but not its clearable share keeps the figure it has`() {
        // Below Android 12 there is no externalCacheBytes, so the row shows a total it cannot break
        // down. Dropping the total as well would hide a real reading behind a missing one.
        val reading = AppStorageReading(
            cacheBytes = measured(300L * MB),
            clearableCacheBytes = Observed.needsNewerApi("Android 11 does not say."),
            untouchedBytes = measured(2L * 1024L * MB),
        )
        val storage = reading.describing(PACKAGE, LABEL, hasProfile = false, isDeclaredGame = true)
        assertEquals(300L * MB, storage.measuredCache)
        assertTrue(storage.isWorthClearing)
        assertNull((storage.clearableCacheBytes as Observed.Restricted).unlockedBy)
    }

    private fun cleared(
        freedBytes: Observed<Long>,
        remainingCacheBytes: Observed<Long>,
    ) = CacheClearReport.Cleared(
        packageName = PACKAGE,
        label = LABEL,
        freedBytes = freedBytes,
        remainingCacheBytes = remainingCacheBytes,
    )

    private fun row(cacheBytes: Observed<Long>) = GameStorage(
        packageName = PACKAGE,
        label = LABEL,
        hasProfile = false,
        isDeclaredGame = true,
        cacheBytes = cacheBytes,
        clearableCacheBytes = cacheBytes,
        untouchedBytes = measured(0L),
    )

    private fun measured(value: Long): Observed<Long> =
        Observed.of(value, DataSource.STORAGE_STATS, Precision.SAMPLED)

    private companion object {
        const val PACKAGE = "com.example.game"
        const val LABEL = "Example Game"
        const val MB = 1024L * 1024L
    }
}
