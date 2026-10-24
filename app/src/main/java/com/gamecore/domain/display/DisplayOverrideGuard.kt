package com.gamecore.domain.display

import com.gamecore.core.model.DisplaySize
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The dead-man's switch, as a question with three answers.
 *
 * Everything here is pure: a pending row, a clock reading, and a decision. The wiring — who calls the
 * controller and who shows the countdown — lives at the call sites, so the rules that decide whether a
 * display goes back can be tested without a device, a window, or a shell.
 *
 * The rules, and why each one exists:
 *
 *  - **The row is written before the change.** A previous size read *after* `wm size` has run is
 *    GameCore's own number, so reverting it would put back the thing it is trying to undo.
 *  - **The countdown is short.** [PendingDisplayOverride.CONFIRM_WINDOW_MILLIS] is fifteen seconds:
 *    enough to read the screen and reach the button, short enough that a phone left alone is not left
 *    alone for long.
 *  - **Silence reverts.** An expired countdown, and a process that died before the user answered, both
 *    revert. That is the whole point: the failure this feature exists for is a user who *cannot*
 *    answer, and a default of "leave it" would protect nothing.
 *  - **A long-lived override is offered, not reverted.** A row older than
 *    [PendingDisplayOverride.MAX_PENDING_AGE_MILLIS] was applied and never confirmed, but the user has
 *    been living with it since. Reverting a display they have got used to, without asking, is a worse
 *    surprise than the one it fixes, so the launch path presents the choice instead of taking it.
 *  - **Nothing is faked.** A row with no recorded previous size is still restorable — null means the
 *    display had nothing set, and the way back is `wm size reset` — but the copy never claims a size
 *    that was never written down, and a size that cannot be parsed is left alone rather than guessed.
 */
@Singleton
class DisplayOverrideGuard @Inject constructor() {

    /**
     * Decides what to do with [pending], now that [nowMillis] has passed.
     *
     * [userConfirmed] is the only "keep" that counts: the user answered the question this row's own
     * [PendingDisplayOverride.token] was minted for. A row the user has taken over by hand since is
     * not a confirmation, and neither is a countdown belonging to a different application.
     */
    fun decide(
        pending: PendingDisplayOverride?,
        nowMillis: Long,
        userConfirmed: Boolean = false,
    ): DisplayOverrideDecision = when {
        pending == null -> DisplayOverrideDecision.NONE
        userConfirmed -> DisplayOverrideDecision.CLEAR
        isTooOldToActOn(pending, nowMillis) -> DisplayOverrideDecision.CLEAR
        else -> DisplayOverrideDecision.REVERT
    }

    /** Whether the countdown has run out for [pending] as of [nowMillis]. */
    fun hasExpired(pending: PendingDisplayOverride, nowMillis: Long): Boolean =
        nowMillis - pending.appliedAtMillis >= PendingDisplayOverride.CONFIRM_WINDOW_MILLIS

    /** Milliseconds left on the countdown, floored at zero. */
    fun remainingMillis(pending: PendingDisplayOverride, nowMillis: Long): Long =
        (pending.appliedAtMillis + PendingDisplayOverride.CONFIRM_WINDOW_MILLIS - nowMillis)
            .coerceAtLeast(0L)

    /** Whole seconds left, rounded up, so the number on screen never reaches zero while time remains. */
    fun remainingSeconds(pending: PendingDisplayOverride, nowMillis: Long): Long =
        (remainingMillis(pending, nowMillis) + 999L) / 1000L

    /**
     * Whether the row is old enough that the launch path must ask rather than act.
     *
     * True past [PendingDisplayOverride.MAX_PENDING_AGE_MILLIS]. Until then the confirmation never
     * happened — the user rebooted, or the process was killed, or they could not read the screen — and
     * the answer they would have given is the one the countdown was going to give them anyway.
     */
    fun isTooOldToActOn(pending: PendingDisplayOverride, nowMillis: Long): Boolean =
        nowMillis - pending.appliedAtMillis >= PendingDisplayOverride.MAX_PENDING_AGE_MILLIS

    /**
     * Whether a launch-time recovery should just do it rather than ask.
     *
     * The mirror of [decide] without the keep case: a row that is still young never got its
     * confirmation, so [DisplayOverrideDecision.REVERT] is what the countdown would have decided too.
     */
    fun shouldRevertOnLaunch(pending: PendingDisplayOverride, nowMillis: Long): Boolean =
        !isTooOldToActOn(pending, nowMillis)

    /**
     * The sentence the confirmation shows, describing exactly what is about to happen.
     *
     * Says "logical display size" rather than anything about the picture, because that is what
     * `wm size` changes: the size the window manager reports to apps. It is not a wider field of view,
     * and a copy that implies otherwise is a promise no app can keep.
     */
    fun confirmCopy(pending: PendingDisplayOverride): String = buildString {
        append("GameCore set this display's logical size to ")
        append(pending.requestedLabel)
        append(". A display size change survives a reboot, and one small enough can be hard to undo ")
        append("from the screen in front of you.")
    }

    /**
     * The sentence beside the Revert button, naming what would go back.
     *
     * Null previous value is the ordinary case — the display had no override before — so it is spelled
     * out as clearing the override rather than pretending a size was recorded.
     */
    fun revertCopy(pending: PendingDisplayOverride): String {
        val previous = pending.previousLabel
            ?: return "The display had no size override before this change, so putting it back clears " +
                "the override — which is `wm size reset`, and is what the restore replays."
        return "Putting back $previous, the size recorded before GameCore changed the display."
    }

    /**
     * The sentence for the launch notice when the recorded size actually went back.
     *
     * Says which size is on screen now, because "restored" without a number is a claim the user cannot
     * check. A display that had no override beforehand clears it, and the copy says that too — that is
     * the ordinary case, not a failure.
     */
    fun revertedCopy(pending: PendingDisplayOverride): String = buildString {
        val previous = pending.previousLabel
        if (previous == null) {
            append("GameCore changed this display's size and never got an answer, so it cleared ")
            append("the override. The display is back to the size it had before GameCore touched it.")
        } else {
            append("GameCore changed this display's logical size to ")
            append(pending.requestedLabel)
            append(" and never got an answer, so it was put back to ")
            append(previous)
            append(", the size recorded before the change.")
        }
    }

    /**
     * The sentence for a notice about a change that was *not* put back, with [reason] for why.
     *
     * The instruction is always attached: a user told only that their display is still stretched has
     * been told about a problem with no way through it, and this screen exists precisely because they
     * may not be able to see the app to work one out.
     */
    fun stillSetCopy(pending: PendingDisplayOverride, reason: String): String = buildString {
        append("GameCore changed this display's logical size to ")
        append(pending.requestedLabel)
        append(" and the confirmation never happened. ")
        append(reason)
        append(" ")
        if (pending.previousLabel == null) {
            // The display had no override before, so "putting it back" means clearing it — which is
            // exactly what `wm size reset` does, and what the restore path already replays for a null
            // previous value. It is not a missing recording and must not read as one.
            append("The display had no size override before this change, so putting it back clears the " +
                "override, which the recovery screen can do.")
        } else {
            append("Use the recovery screen to put ")
            append(pending.previousLabel)
            append(" back.")
        }
    }

    /**
     * The sentence for the launch notice when the row is too old to act on unasked.
     *
     * It is the same override and the same two answers, with the difference that nothing has been done
     * yet — the user gets to say, rather than finding out.
     */
    fun offeredCopy(pending: PendingDisplayOverride): String = buildString {
        append("This display's logical size is ")
        append(pending.requestedLabel)
        append(", set by GameCore and never confirmed. Keep it, or put it back — ")
        if (pending.previousLabel == null) {
            append("the display had no size override beforehand, so putting it back clears the override.")
        } else {
            append(pending.previousLabel)
            append(" is the size recorded beforehand.")
        }
    }

    /**
     * The size a revert should write, or null when nothing usable was recorded.
     *
     * Returned as a [DisplaySize] because that is what the controller takes, and null rather than a
     * panel guess: a revert to a size GameCore invented is the same failure the feature exists to
     * prevent, committed by the recovery path instead of the game.
     */
    fun previousSize(pending: PendingDisplayOverride): DisplaySize? =
        pending.previousValue?.let { DisplaySize.parse(it) }
}
