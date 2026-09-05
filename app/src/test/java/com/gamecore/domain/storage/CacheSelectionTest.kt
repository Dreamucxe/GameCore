package com.gamecore.domain.storage

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.model.AppStorageReading
import com.gamecore.core.model.GameStorage
import com.gamecore.core.model.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which apps the storage screen lists, and in what order.
 *
 * The rule is the whole reason this screen is not a general cache cleaner: it lists the user's games and
 * nothing else, so there is never a button offering to empty the cache of their bank or their camera.
 * Both halves of the rule are checked, because the interesting half is the second one — a list built on
 * `CATEGORY_GAME` alone would be missing the game the user came here to clear.
 *
 * The order is asserted for the same reason [com.gamecore.domain.memory.ReclaimFilterTest] asserts the
 * precedence of protections: it answers the question the screen was opened with, and a row the platform
 * would not measure must not be able to sort as though it were empty.
 */
class CacheSelectionTest {

    @Test
    fun `a declared game is listed, and so is any app the user made a profile for`() {
        val installed = listOf(
            app("com.example.shooter", isLikelyGame = true),
            app("com.example.puzzle", isLikelyGame = false),
            app("com.example.bank", isLikelyGame = false),
        )
        val candidates = CacheSelection.candidates(installed, setOf("com.example.puzzle"))
        assertEquals(
            listOf("com.example.shooter", "com.example.puzzle"),
            candidates.map { it.packageName },
        )
    }

    @Test
    fun `an app that is neither a declared game nor profiled is not on this screen at all`() {
        // The line that keeps this from being a cache cleaner for the whole device. A keyboard, a bank
        // and a launcher are all someone else's business, and none of them gets a Clear button here.
        val installed = listOf(
            app("com.android.settings", isLikelyGame = false, isSystemApp = true),
            app("com.example.keyboard", isLikelyGame = false),
        )
        assertEquals(emptyList<InstalledApp>(), CacheSelection.candidates(installed, emptySet()))
    }

    @Test
    fun `a preloaded game is kept, because the rule is the category and not the system flag`() {
        // A game the manufacturer shipped fills a cache exactly like one from the store, and the
        // enumeration includes system apps so that the rule below decides rather than the flag.
        val installed = listOf(app("com.vendor.game", isLikelyGame = true, isSystemApp = true))
        assertEquals(1, CacheSelection.candidates(installed, emptySet()).size)
    }

    @Test
    fun `the largest cache is first and an unmeasured row is last, not empty`() {
        val rows = listOf(
            row("com.example.small", "Small", 12L * MB),
            row("com.example.unknown", "Unknown", cacheBytes = null),
            row("com.example.huge", "Huge", 3L * 1024L * MB),
            row("com.example.empty", "Empty", 0L),
        )
        assertEquals(
            listOf("com.example.huge", "com.example.small", "com.example.empty", "com.example.unknown"),
            CacheSelection.ordered(rows).map { it.packageName },
        )
    }

    @Test
    fun `two games holding the same amount keep a stable order between visits`() {
        // Sorting by size alone would let the platform's iteration order decide, and the list would
        // reshuffle every time the screen was opened on a device with several idle games.
        val rows = listOf(
            row("com.example.zebra", "Zebra", 50L * MB),
            row("com.example.apple", "apple", 50L * MB),
            row("com.example.b", "Banana", cacheBytes = null),
            row("com.example.a", "Avocado", cacheBytes = null),
        )
        assertEquals(
            listOf("apple", "Zebra", "Avocado", "Banana"),
            CacheSelection.ordered(rows).map { it.label },
        )
    }

    private fun app(
        packageName: String,
        isLikelyGame: Boolean,
        isSystemApp: Boolean = false,
    ) = InstalledApp(
        packageName = packageName,
        label = packageName.substringAfterLast('.'),
        isLikelyGame = isLikelyGame,
        isSystemApp = isSystemApp,
        versionName = null,
    )

    private fun row(packageName: String, label: String, cacheBytes: Long?): GameStorage {
        val reading = if (cacheBytes == null) {
            AppStorageReading.unavailable(Observed.needsPermission("Needs usage access."))
        } else {
            val measured = Observed.of(cacheBytes, DataSource.STORAGE_STATS)
            AppStorageReading(measured, measured, measured)
        }
        return reading.describing(
            packageName = packageName,
            label = label,
            hasProfile = false,
            isDeclaredGame = true,
        )
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
