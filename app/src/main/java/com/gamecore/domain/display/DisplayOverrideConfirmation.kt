package com.gamecore.domain.display

/**
 * Asks the user the question a pending override was written for.
 *
 * The controller calls this after it has written, never before, and never instead of writing: the
 * dead-man's switch is not a permission prompt. What the answer does — keep, or put the recorded size
 * back — is already decided by the row; this is only the surface it is decided on.
 *
 * One method, and no return value. It cannot fail in a way the caller should care about: a
 * confirmation that could not be shown is a fallback notification's problem, not the display write's,
 * and a confirmation the user never answers is exactly what the countdown is for.
 */
interface DisplayOverrideConfirmer {

    /** Shows [row] to the user, by whatever surface this device can put in front of them. */
    fun confirm(row: PendingDisplayOverride)
}

/**
 * What the launch-time recovery is allowed to say to the user, without knowing how.
 *
 * The recovery's decisions live in [DisplayOverrideRecovery] and are pure; the sentences and the
 * surfaces live in the UI. Splitting them keeps `Application.onCreate`'s deferred work away from
 * `NotificationManager` and away from a `Context`, and it is the same inversion the rest of the app
 * uses — a domain rule that cannot be tested on a machine with no display, behind an interface.
 */
interface DisplayOverrideNotices {

    /** The confirmation asked as a notification, with Keep and Revert actions on it. */
    fun confirmation(row: PendingDisplayOverride)

    /** The recorded size went back and was read back. */
    fun reverted(row: PendingDisplayOverride)

    /** It was not put back, and [reason] is the honest sentence for why not. */
    fun notReverted(row: PendingDisplayOverride, reason: String)

    /** The row is too old to act on unasked, so the user is offered the choice instead. */
    fun offered(row: PendingDisplayOverride)

    /** A row exists that could not be read, so nothing was done and nothing was claimed. */
    fun unreadableRow()
}
