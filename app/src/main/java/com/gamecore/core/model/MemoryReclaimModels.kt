package com.gamecore.core.model

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.Observed
import com.gamecore.core.common.valueOrNull

/**
 * Closing background apps so a game can have the memory they were holding, and the accounting that
 * makes the claim checkable.
 *
 * This is the one thing GameCore does that "RAM booster" apps pretend to do, so the difference is
 * worth stating, because the difference lives entirely in this file and in
 * [com.gamecore.domain.memory.BackgroundAppReclaimer].
 *
 * A booster shows a dial, kills whatever it can reach by package name, and reports a figure it
 * computed rather than measured. What is here instead:
 *
 *  - Nothing is closed that GameCore has not first seen running, in a state it could read. An app
 *    whose state could not be determined is [ProtectionReason.FOREGROUND_STATE_UNKNOWN] — left
 *    alone, and reported as left alone — because the only safe reading of "I could not tell" is
 *    "do not touch it".
 *  - The freed figure is the difference between two `ActivityManager.MemoryInfo` readings taken
 *    either side of the close. It is an [Observed] so a reading that could not be taken is absent
 *    rather than zero, and it is allowed to come out *negative*: a game loading claims memory
 *    faster than a few cached apps release it, and [MemoryReclaimReport.Completed.summary] says so
 *    plainly rather than reporting the absolute value or hiding the sign.
 *  - Every app that survived is in the report with the reason it survived.
 *
 * None of these types name a platform object. A `RunningAppProcessInfo`, an `ApplicationInfo` or a
 * `ServiceRecord` line from a dump is turned into the flat records below at the boundary that read
 * it, which is §24A.2 and also what makes the filter testable on a machine with no Android on it.
 */
data class ReclaimCandidate(
    val packageName: String,
    /** Sanitised at the boundary; an app's label is another developer's text. */
    val label: String,
    val state: AppProcessState,
)

/**
 * What an app's processes were doing when GameCore looked.
 *
 * [protection] is the whole point of the enum: it says whether this state, on its own, is a reason
 * to leave the app alone. Null means the state itself is no objection — the app may still be
 * protected for one of the reasons that has nothing to do with what it is doing, such as being the
 * keyboard or being on the user's list.
 *
 * The states are coarser than the platform's own adjustment types deliberately. `dumpsys` prints a
 * dozen labels, they differ between versions and OEMs, and the decision here needs only to know
 * which of these six buckets a process is in.
 */
enum class AppProcessState(val label: String, val protection: ProtectionReason?) {
    TOP("On screen", ProtectionReason.FOREGROUND_APP),
    FOREGROUND_SERVICE("Running a foreground service", ProtectionReason.FOREGROUND_SERVICE),
    PERCEPTIBLE("Doing something you can see or hear", ProtectionReason.IN_USE),
    HOME("The home screen", ProtectionReason.DEFAULT_LAUNCHER),

    /** A plain background service. What the platform itself reclaims first under pressure. */
    BACKGROUND_SERVICE("Running a background service", null),

    /** Held in memory in case it is opened again, and doing nothing. The whole target of this. */
    CACHED("Cached, doing nothing", null),

    /**
     * Running, and its state could not be read.
     *
     * Reached when the process dump parsed but this line did not, which is exactly the case a
     * booster would treat as "safe to kill". It is treated as protected.
     */
    UNKNOWN("GameCore could not tell what it was doing", ProtectionReason.FOREGROUND_STATE_UNKNOWN),
}

/**
 * Why one app was left running.
 *
 * Not a log level and not an internal code: every one of these is shown to the user next to the app
 * it applied to, because "why is this app still open" is the first question anyone asks of a feature
 * like this, and the answer is always one of these ten.
 *
 * The order is the order the filter checks them in, which is also roughly the order of how
 * dangerous getting it wrong would be. [SELF] is first because GameCore closing itself mid-launch
 * would take the overlay, the session recording and the restore ledger with it.
 */
enum class ProtectionReason(val label: String) {
    /** Closing GameCore would abandon the restore points for everything it just changed. */
    SELF("GameCore itself"),

    /** The whole point of the exercise. Named separately from [FOREGROUND_APP] because at the
     *  moment this runs the game may not be on screen yet. */
    LAUNCHING_GAME("The game you are starting"),

    /** Closing the launcher means a blank screen when the user leaves the game. */
    DEFAULT_LAUNCHER("Your home screen"),

    /** Closing the keyboard means no keyboard in the game's own text fields. */
    CURRENT_IME("The keyboard you are using"),

    /** Music, a call, a download, a workout tracker. The user asked for it and can see it. */
    FOREGROUND_SERVICE("Running a foreground service"),

    FOREGROUND_APP("On screen"),

    /** Visible, perceptible, or holding a provider something else is reading. */
    IN_USE("Doing something you can see or hear"),

    /** Includes anything with no launcher entry: not an app the user opens, so not one to close. */
    SYSTEM_APP("Part of the system"),

    ALLOWLISTED("On your never-close list"),

    /** Running, state unreadable. The honest default, and the one that keeps this feature safe. */
    FOREGROUND_STATE_UNKNOWN("GameCore could not tell what it was doing"),
}

/**
 * What became of one app.
 *
 * [Closed] and [Requested] are separate cases rather than one case with a boolean, because they are
 * different claims. [Closed] means GameCore saw the app running, asked the elevated shell to close
 * it, looked again, and it was gone. [Requested] means GameCore called
 * `ActivityManager.killBackgroundProcesses` and cannot check: the method returns nothing, and
 * without the shell there is no second look to be had. A report that called both of those "closed"
 * would be inventing the half that was never verified, which is the whole of what this app refuses
 * to do.
 */
sealed interface ReclaimOutcome {

    val packageName: String

    /** The app's own label, or its package name when there is nothing better to show. */
    val label: String

    /** Seen running, asked to close, and confirmed gone by a second read of the process list. */
    data class Closed(
        override val packageName: String,
        override val label: String,
    ) : ReclaimOutcome

    /** Handed to the platform, which does not say what it did. Never counted as closed. */
    data class Requested(
        override val packageName: String,
        override val label: String,
    ) : ReclaimOutcome

    /** Left running on purpose. [reason] is shown to the user as it is written. */
    data class Protected(
        override val packageName: String,
        override val label: String,
        val reason: ProtectionReason,
    ) : ReclaimOutcome

    /** Asked, and it is still there. [detail] is what went wrong, not a stack trace. */
    data class Failed(
        override val packageName: String,
        override val label: String,
        val detail: String,
    ) : ReclaimOutcome
}

/**
 * What one pass did, as one object the overlay banner renders and the session state holds.
 *
 * Sealed rather than a data class with a nullable everything, because "GameCore did not try" and
 * "GameCore tried and closed nothing" are different facts and the user acts on them differently: the
 * first is fixed by starting Shizuku, the second means there was nothing worth closing.
 */
sealed interface MemoryReclaimReport {

    /**
     * The profile asked for it and GameCore did not attempt it. [reason] is a full sentence.
     *
     * Not a failure and not silent. A user who ticked the box and got nothing is owed the reason,
     * and the reason is nearly always that the elevated shell is not running — which the profile
     * editor already warns about before the box is ticked.
     */
    data class Skipped(val reason: String) : MemoryReclaimReport

    /**
     * GameCore looked at what was running and acted on it.
     *
     * [via] is how the closing was done — the elevated shell, or the platform's own
     * `ActivityManager` — and it is kept because it is what decides whether [closedCount] is a
     * confirmed number or [requestedCount] is the honest one.
     */
    data class Completed(
        val outcomes: List<ReclaimOutcome>,
        val freedBytes: Observed<Long>,
        val via: DataSource,
    ) : MemoryReclaimReport {

        val closedCount: Int get() = outcomes.count { it is ReclaimOutcome.Closed }

        val requestedCount: Int get() = outcomes.count { it is ReclaimOutcome.Requested }

        val failedCount: Int get() = outcomes.count { it is ReclaimOutcome.Failed }

        val protectedApps: List<ReclaimOutcome.Protected>
            get() = outcomes.filterIsInstance<ReclaimOutcome.Protected>()

        /** True when nothing was asked to close, whatever the reason. */
        val touchedNothing: Boolean get() = closedCount == 0 && requestedCount == 0

        /**
         * The one line the banner shows.
         *
         * Three things about it are deliberate. It leads with what was *done*, because that is the
         * part GameCore is sure of. It says "asked Android to close" on the unverified path rather
         * than borrowing the confirmed wording. And a freed figure that came out zero or negative is
         * reported as what it is — a game claiming memory while cached apps release it is the normal
         * case on a device that was not short of memory to begin with, and an app that only ever
         * reports a positive number is choosing which measurements to believe.
         *
         * The tilde is not decoration. Both readings are exact, but the interval between them
         * belongs to the whole device, so the difference is a measurement of that interval and not
         * of GameCore's own work.
         */
        fun summary(): String {
            val what = when {
                closedCount > 0 -> "Closed ${Formatters.count(closedCount, "app")}"
                requestedCount > 0 ->
                    "Asked Android to close ${Formatters.count(requestedCount, "app")}"
                failedCount > 0 -> "Nothing could be closed"
                else -> "Nothing to close — everything running was in use"
            }
            val freed = freedBytes.valueOrNull
            return when {
                touchedNothing -> what
                freed == null -> "$what · GameCore could not measure what that freed"
                freed <= 0L -> "$what · available memory did not rise while the game was starting"
                else -> "$what · freed ~${Formatters.bytes(freed)}"
            }
        }
    }
}
