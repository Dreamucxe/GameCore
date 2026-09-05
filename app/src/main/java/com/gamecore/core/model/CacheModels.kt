package com.gamecore.core.model

import com.gamecore.core.common.Formatters
import com.gamecore.core.common.Observed
import com.gamecore.core.common.valueOrNull

/**
 * A game's storage, and the one part of it GameCore is willing to delete.
 *
 * Every "cache cleaner" on a phone store shows one number and one button. The number is usually the
 * app's whole data directory, and the button usually runs `pm clear`, which is why the reviews of
 * those apps are full of people who lost a save. So the figures here are three rather than one, and
 * the split between them is the feature:
 *
 *  - [cacheBytes] is what the platform calls cache for this package across the volume: the
 *    directory inside the app's private storage plus the one in shared storage. It is the number the
 *    user recognises from the app's own storage page.
 *  - [clearableCacheBytes] is the part of that GameCore can actually delete — the shared-storage
 *    cache directory, and nothing else. It is a separate reading rather than a share of the first,
 *    because on Android 11 and below the platform will not break the total down and a guess at the
 *    split would be a guess about how much a button is about to free.
 *  - [untouchedBytes] is the app's data with its cache taken out: saves, logins, settings, and the
 *    downloaded assets of a game that streams them. Nothing GameCore does touches this, and it is on
 *    screen so that "cleared 900 MB" can never be mistaken for having come out of it.
 *
 * Each is an [Observed] because each can be unavailable for a different reason — usage access not
 * granted, an Android version that does not expose the breakdown, a package the platform declined to
 * measure — and a storage screen that turned any of those into a zero would be inviting the user to
 * press a button on the strength of a number that was never read.
 */
data class GameStorage(
    val packageName: String,

    /** Sanitised at the boundary that read it; an app's label is another developer's text. */
    val label: String,

    /** True when the user has a GameCore profile for this app, which is why it is in the list. */
    val hasProfile: Boolean,

    /** True when the app declares itself a game. Either this or [hasProfile] puts a row here. */
    val isDeclaredGame: Boolean,

    val cacheBytes: Observed<Long>,
    val clearableCacheBytes: Observed<Long>,
    val untouchedBytes: Observed<Long>,
) {

    /** The figure rows are ordered by, and null when the platform would not give one. */
    val measuredCache: Long? get() = cacheBytes.valueOrNull

    /**
     * True when there is a measured cache and it is large enough to be worth a tap.
     *
     * The bound is deliberately low. It exists to keep a list of games from being a list of
     * "Clear" buttons that free four kilobytes each, not to hide anything: a row below it still
     * shows its size, it simply does not invite an action that would achieve nothing.
     */
    val isWorthClearing: Boolean get() = (measuredCache ?: 0L) >= WORTH_CLEARING_BYTES

    private companion object {
        const val WORTH_CLEARING_BYTES = 512L * 1024L
    }
}

/**
 * The three figures as the platform boundary read them, before anything knows whose game it is.
 *
 * Split from [GameStorage] so that the class holding a `StorageStatsManager` does not also have to
 * know what a profile is, and so that the mapping from a reading to a row — the only place the two
 * meet — is a function in this file that a test can call with no Android present.
 */
data class AppStorageReading(
    val cacheBytes: Observed<Long>,
    val clearableCacheBytes: Observed<Long>,
    val untouchedBytes: Observed<Long>,
) {

    fun describing(
        packageName: String,
        label: String,
        hasProfile: Boolean,
        isDeclaredGame: Boolean,
    ): GameStorage = GameStorage(
        packageName = packageName,
        label = label,
        hasProfile = hasProfile,
        isDeclaredGame = isDeclaredGame,
        cacheBytes = cacheBytes,
        clearableCacheBytes = clearableCacheBytes,
        untouchedBytes = untouchedBytes,
    )

    companion object {

        /**
         * One reason standing for all three figures.
         *
         * Usage access not granted, no storage-stats service, a package the platform will not
         * measure: none of those fail one figure and leave the others readable, so the boundary says
         * so once rather than repeating itself three times and inviting a caller to believe that a
         * partial reading is possible where it is not.
         */
        fun unavailable(reason: Observed<Nothing>): AppStorageReading =
            AppStorageReading(reason, reason, reason)
    }
}

/**
 * What one press of Clear did, for one game.
 *
 * Three cases rather than a success flag and a message, because they are three different claims and
 * the screen does three different things with them. [NotAttempted] means no command was built, and
 * the user is owed the reason and a way forward — usually Shizuku, sometimes the app's own storage
 * page. [Failed] means the delete ran and the shell said it did not work. [Cleared] means the delete
 * ran and exited cleanly, which is not the same as having freed anything, and is why that case
 * carries a measurement rather than a claim.
 *
 * Nothing here reports bytes freed as a difference between what the cache was and zero. The figure is
 * the difference between two readings of the same platform counter taken either side of the delete,
 * so it is a measurement of the delete, and it is allowed to come out at nothing.
 */
sealed interface CacheClearReport {

    val packageName: String

    val label: String

    /** The sentence shown against the row. A full sentence, never a code and never a stack trace. */
    val message: String

    val isCleared: Boolean get() = this is Cleared

    /**
     * GameCore did not run anything, and this is why.
     *
     * [needsShizuku] picks the button the screen offers next to the sentence: starting Shizuku, or
     * opening the app's own storage page, which is the standard-Android way to do this and is offered
     * on every row whatever this says.
     */
    data class NotAttempted(
        override val packageName: String,
        override val label: String,
        val reason: String,
        val needsShizuku: Boolean = false,
    ) : CacheClearReport {
        override val message: String get() = reason
    }

    /** The command ran and did not succeed. [detail] is what the shell reported, cleaned up. */
    data class Failed(
        override val packageName: String,
        override val label: String,
        val detail: String,
    ) : CacheClearReport {
        override val message: String get() = detail
    }

    /**
     * The delete ran and exited cleanly.
     *
     * [freedBytes] is the drop in the platform's cache figure across the delete, and every one of its
     * three possible shapes is a different sentence. A number is what was measured. An absent reading
     * means the delete ran and GameCore cannot say what it achieved, which happens when usage access
     * is withdrawn between the two reads. Nothing, or less than nothing, means the figure did not fall
     * — the reachable part was already empty, or the game wrote to its cache again in the second it
     * took to look, and both are ordinary.
     *
     * [remainingCacheBytes] is the cache still there afterwards, and on almost every device it will
     * not be zero: what is left is the copy inside the app's private storage, which a shell running as
     * the shell user cannot open and which GameCore holds no other route to. That is a fact about the
     * Android sandbox and it is reported as one, next to the button that does reach it — Android's own
     * storage page for the app.
     */
    data class Cleared(
        override val packageName: String,
        override val label: String,
        val freedBytes: Observed<Long>,
        val remainingCacheBytes: Observed<Long>,
    ) : CacheClearReport {

        val freed: Long? get() = freedBytes.valueOrNull

        val remaining: Long? get() = remainingCacheBytes.valueOrNull

        /** True when there is cache left that only Android's own storage page can clear. */
        val hasUnreachableRemainder: Boolean get() = (remaining ?: 0L) > 0L

        override val message: String
            get() {
                val measured = freed
                val what = when {
                    measured == null ->
                        "Cleared the cache GameCore can reach, and could not measure what that freed"
                    measured <= 0L ->
                        "Nothing was freed: the part GameCore can reach was empty, " +
                            "or the game refilled it at once"
                    else -> "Freed ${Formatters.bytes(measured)}"
                }
                val rest = remaining
                return if (rest == null || rest <= 0L) {
                    "$what."
                } else {
                    "$what. ${Formatters.bytes(rest)} of cache is inside the app's own storage, " +
                        "which only Android can clear."
                }
            }
    }
}
