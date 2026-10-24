package com.gamecore.domain.display

import com.gamecore.core.model.DisplaySize
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A display override that was written and has not been confirmed yet.
 *
 * This row is why the dead-man's switch works. `wm size` survives a reboot and a process death, so a
 * display GameCore made unreadable stays unreadable until something puts it back — and the undo cannot
 * depend on the user navigating back to the screen that applied it, because they cannot see that
 * screen. Writing what the display was doing *before* the write, and keeping it until the user says
 * "keep", is the only thing that makes the recovery possible at all.
 *
 * [previousValue] is deliberately the same string the restore point holds — `RestorePointRepository`'s
 * `display_size` row — rather than a second copy of the size in a second place. One value, one format,
 * one thing to keep right: the revert replays that row through the ordinary restore path, so a
 * previous size of null means "the display had no override, and `wm size reset` is the way back", which
 * is the common case and the one that has to be right.
 *
 * [token] is minted per application. A row left over from three sessions ago must not be confirmable
 * by a countdown that belongs to this one, and a notification action that arrives after the user has
 * already answered must not be able to answer again.
 */
data class PendingDisplayOverride(
    /**
     * The `WxH` the display was running before GameCore changed it, or null when it had no override.
     *
     * Null is not a missing recording. It is the ordinary case — a display with nothing set on it —
     * and [hasRecordedPrevious] deliberately reports it as one rather than treating it as an error,
     * because the restore path handles it properly: null means `wm size reset`.
     */
    val previousValue: String?,

    /** What was asked for, in the display's own orientation. For the copy, never for the restore. */
    val requestedWidth: Int,
    val requestedHeight: Int,

    /** Wall-clock millis the override was written. The countdown is derived from this, not counted. */
    val appliedAtMillis: Long,

    /** Unique per application, so only the confirmation this row was written for can answer it. */
    val token: String,
) {

    /** "720 × 1600". The size the user is being asked to keep. */
    val requestedLabel: String get() = sizeString(requestedWidth, requestedHeight)

    /** "1080 × 2400", or null when there was no override to record. */
    val previousLabel: String? get() = previousValue?.let { asLabel(it) }

    /** True when a size was actually recorded and can be put back. */
    val hasRecordedPrevious: Boolean get() = previousValue != null

    companion object {

        /**
         * How long the user gets before the switch fires.
         *
         * Fifteen seconds is a deliberate compromise: long enough to read what the screen is asking and
         * reach the button, short enough that a phone left on a display nobody can read is a phone the
         * owner has already picked up and is trying to fix by hand.
         */
        const val CONFIRM_WINDOW_MILLIS = 15_000L

        /**
         * The longest a pending row is acted on unasked after the process died.
         *
         * A row older than this was applied and never confirmed, but the user has since been living
         * with it: they rebooted, or they got used to it, or they never noticed. Reverting a display
         * somebody has been using for a week without asking is a worse surprise than the one the
         * switch exists to prevent, so the launch path *offers* past this age instead of acting. The
         * notification says which size is set and what the two answers are.
         */
        const val MAX_PENDING_AGE_MILLIS = 24L * 60 * 60 * 1000

        fun sizeString(width: Int, height: Int): String = "$width × $height"

        /** "1080x2400" as the copy spells it: "1080 × 2400". */
        private fun asLabel(value: String): String {
            val size = DisplaySize.parse(value) ?: return value
            return sizeString(size.widthPixels, size.heightPixels)
        }
    }
}

/** What the guard decided should happen next, given a pending row and a clock. */
enum class DisplayOverrideDecision {
    /**
     * Keep it. Either the user said keep, or the row is old enough that acting without asking would
     * be the bigger surprise.
     */
    CLEAR,

    /** Silence: the countdown expired, or the process died before the confirmation happened. */
    REVERT,

    /** No row at all. Nothing to decide. */
    NONE,
}

/**
 * The four fields of a pending row as they are stored, before they are a [PendingDisplayOverride].
 *
 * A separate type so the storage layer can be a dumb key-value mapping while the parsing — which is
 * the part that can be wrong and is worth testing — stays in [DisplayOverrideStore]. Nothing here
 * knows about Android, which is what lets a JVM test exercise a corrupt row.
 */
data class StoredPendingOverride(
    /** Null when the display had no override before GameCore changed it. */
    val previousValue: String?,
    /** The requested size as an argument string, `WxH`. Parsed on the way out, not on the way in. */
    val requestedArgument: String,
    val appliedAtMillis: Long,
    val token: String,
)

/**
 * Where a pending row is kept, with the platform left out of it.
 *
 * `SharedPreferences` rather than Room on purpose: this is read by the first thing
 * `Application.onCreate` schedules, before the encrypted database has been keyed and opened, and the
 * one write that matters happens *before* the display is changed. A row that needs a database to be
 * readable is a row that cannot save a display nobody can see.
 *
 * The interface is what makes the store testable — a JVM test can hand it a map and a clock, and
 * needs no emulator, no Robolectric and no instrumented run to prove the format round-trips.
 */
interface PendingOverridePersistence {

    /** The stored row, or null when nothing is waiting. No interpretation: exactly what is on disk. */
    fun read(): StoredPendingOverride?

    fun write(row: StoredPendingOverride)

    fun clear()
}

/**
 * The pending row, and the rules for reading it back.
 *
 * Pure Kotlin by design: the wiring — who calls [record] and who shows the countdown — lives at the
 * call sites, so every rule that decides whether a display goes back can be tested without a device,
 * a window, a shell or a `Context`.
 */
@Singleton
class DisplayOverrideStore @Inject constructor(
    private val persistence: PendingOverridePersistence,
) {

    /**
     * Writes the row before the override, and returns it.
     *
     * [previousValue] must be taken from the restore point's own record, not re-read afterwards: once
     * `wm size` has run the display reports GameCore's number as its own, and a previous size captured
     * then is the thing the revert is trying to undo.
     */
    fun record(
        previousValue: String?,
        requested: DisplaySize,
        appliedAtMillis: Long = System.currentTimeMillis(),
    ): PendingDisplayOverride {
        val row = PendingDisplayOverride(
            previousValue = previousValue,
            requestedWidth = requested.widthPixels,
            requestedHeight = requested.heightPixels,
            appliedAtMillis = appliedAtMillis,
            token = java.util.UUID.randomUUID().toString(),
        )
        persistence.write(
            StoredPendingOverride(
                previousValue = row.previousValue,
                requestedArgument = "${row.requestedWidth}x${row.requestedHeight}",
                appliedAtMillis = row.appliedAtMillis,
                token = row.token,
            ),
        )
        return row
    }

    /**
     * The unconfirmed override, or null when there is nothing waiting.
     *
     * Null for a row that cannot be understood as well as for no row at all: a stored size that will
     * not parse, or a timestamp of zero, is not something GameCore should count down from or put back.
     * [hasUnreadableRow] is how a caller tells that case from an empty store, because the two deserve
     * different sentences.
     */
    fun pending(): PendingDisplayOverride? {
        val stored = persistence.read() ?: return null
        val requested = DisplaySize.parse(stored.requestedArgument) ?: return null
        if (stored.token.isBlank() || stored.appliedAtMillis <= 0L) return null
        return PendingDisplayOverride(
            previousValue = stored.previousValue,
            requestedWidth = requested.widthPixels,
            requestedHeight = requested.heightPixels,
            appliedAtMillis = stored.appliedAtMillis,
            token = stored.token,
        )
    }

    /**
     * Whether a row exists that [pending] refuses to interpret.
     *
     * The honest failure of a write that was interrupted halfway: the restore point still exists, so
     * the device can still be put back, but this record cannot tell anyone what was asked for. The
     * launch path says that rather than reporting a clean slate.
     */
    fun hasUnreadableRow(): Boolean = persistence.read() != null && pending() == null

    /**
     * Removes the row, whatever is in it.
     *
     * Called by the confirming half, and by the recovery that has already done its job. It is not
     * called by the reverting half until the restore has actually run: a row cleared before a restore
     * that could not be completed is a row that can no longer be retried, and a failed restore on a
     * display nobody can read is the one failure this feature must not make permanent.
     */
    fun clear() = persistence.clear()

    /** Whether [token] is the one waiting now, so a stale action cannot answer a newer question. */
    fun matches(token: String?): Boolean = token != null && pending()?.token == token
}
