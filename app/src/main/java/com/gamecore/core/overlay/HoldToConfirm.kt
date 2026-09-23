package com.gamecore.core.overlay

/**
 * The Full panel's "press and hold to end session" gesture (spec §5), as pure arithmetic on an injected
 * clock rather than a running timer.
 *
 * The footer shows a progress ring that fills while the user holds the End-session button and, once it has
 * been held long enough, ends the session exactly once. There are three promises the spec makes about it —
 * "press and hold ~2s", "early release cancels", "completes once, no double fire" — and each of them is a
 * statement about *time* and *state*, not about a callback. So this models the gesture the same way
 * [IdleDimmer] models the idle fade: the caller owns the state (the timestamp the press began, or null when
 * the finger is up) and asks this object what things look like *now*.
 *
 * Why arithmetic on a clock instead of a `postDelayed`/`ValueAnimator`:
 *  - It is unit-testable with a fake clock (spec §10 asks for exactly that). Every question below is a pure
 *    function of `(pressStartMillis, nowMillis)`, so a test advances a plain `Long` by hand and reads the
 *    answer — no `Thread.sleep`, no main-looper, no Android.
 *  - It cannot leak. There is no posted callback to forget to cancel on hide or on service destroy, which is
 *    the whole point of spec §9. The view schedules at most one repaint (see [nextChangeAfterMillis]).
 *
 * Why early release cancels for free: a release is simply the caller setting its press-start back to null.
 * Every function treats a null start as "not pressing" and reports zero progress / not confirmed, so letting
 * go *is* the cancel — there is no half-finished state to unwind and nothing to reset. A fresh press starts a
 * brand-new window from its own timestamp.
 *
 * Why completion is an *edge*, not a *level*: [isConfirmedAt] stays true for as long as the finger keeps
 * holding past the threshold, so firing on the level would end the session on every frame while held — a
 * double (triple, hundredfold) fire. The single firing moment is the *rising edge*, the frame where confirm
 * first flips false→true. [firesAt] expresses that edge purely; the tiny "have I fired yet" latch lives in
 * the caller (a `Boolean` it updates from [isConfirmedAt]), so this object stays immutable and there is one
 * obvious place — the caller — that decides the action runs once.
 *
 * @param holdMillis how long the button must be held before the gesture confirms. Defaults to
 *   [DEFAULT_HOLD_MILLIS] (~2 s per the spec); the value is a named default, never hard-coded at a call site.
 */
data class HoldToConfirm(val holdMillis: Long = DEFAULT_HOLD_MILLIS) {

    /**
     * How full the ring is at [nowMillis], in `0f..1f`, given the press began at [pressStartMillis]
     * (null when the finger is up).
     *
     * Zero when not pressing, so releasing empties the ring instantly. Clamped at both ends: a clock that
     * appears to run backwards reads 0f rather than a negative fill, and holding past the threshold reads
     * exactly 1f rather than overshooting. Allocates nothing beyond the returned float.
     */
    fun progressAt(pressStartMillis: Long?, nowMillis: Long): Float {
        if (pressStartMillis == null || holdMillis <= 0L) return if (pressStartMillis == null) 0f else 1f
        val elapsed = nowMillis - pressStartMillis
        return when {
            elapsed <= 0L -> 0f
            elapsed >= holdMillis -> 1f
            else -> elapsed.toFloat() / holdMillis.toFloat()
        }
    }

    /**
     * Whether the hold has lasted the full [holdMillis] at [nowMillis]. False while the finger is up.
     *
     * This is the *level*: it is true at the instant the window elapses and stays true for as long as the
     * hold continues. Confirming exactly at `holdMillis` (`elapsed >= holdMillis`) mirrors [IdleDimmer], where
     * the state changes at the boundary, not one millisecond after it. Fire the end-session action on the
     * *edge* of this value (see [firesAt]), not on the value itself.
     */
    fun isConfirmedAt(pressStartMillis: Long?, nowMillis: Long): Boolean {
        if (pressStartMillis == null) return false
        return nowMillis - pressStartMillis >= holdMillis
    }

    /**
     * Milliseconds of hold still to go at [nowMillis], for a "keep holding" readout: null when not pressing,
     * clamped to 0 (never negative) once the hold is complete but the finger is still down.
     *
     * This differs from [nextChangeAfterMillis] on purpose. Here, a completed-but-still-held gesture reports
     * `0` (there is nothing left to wait for, and the ring is full); there, it reports `null` (there is
     * nothing left to *schedule*). The view uses this to show time, and that one to post a repaint.
     */
    fun remainingMillis(pressStartMillis: Long?, nowMillis: Long): Long? {
        if (pressStartMillis == null) return null
        val remaining = pressStartMillis + holdMillis - nowMillis
        return if (remaining <= 0L) 0L else remaining
    }

    /**
     * Milliseconds until the ring completes, so the view can post exactly one repaint at the moment of
     * confirmation; null when there is nothing to schedule — the finger is up, or the hold is already done.
     * Never negative.
     *
     * The progress ring animates while held, but the *state change* the view must not miss is the single
     * completion instant. Scheduling one callback at that delay (and none once complete) is the direct analogue
     * of [IdleDimmer.nextChangeAfterMillis], and keeps this gesture as callback-light as the idle fade.
     */
    fun nextChangeAfterMillis(pressStartMillis: Long?, nowMillis: Long): Long? {
        if (pressStartMillis == null) return null
        val doneAt = pressStartMillis + holdMillis
        return if (nowMillis >= doneAt) null else doneAt - nowMillis
    }

    /**
     * The single firing edge: true only on the transition where the gesture *first* becomes confirmed —
     * i.e. it is confirmed now at [nowMillis] but was not on the caller's previous evaluation.
     *
     * The caller keeps one `Boolean` latch and, each time it evaluates the gesture, does:
     *
     * ```
     * val now = clock()
     * if (holdToConfirm.firesAt(wasConfirmed, pressStart, now)) endSession()   // runs exactly once
     * wasConfirmed = holdToConfirm.isConfirmedAt(pressStart, now)              // update the latch
     * val progress = holdToConfirm.progressAt(pressStart, now)                 // paint the ring
     * ```
     *
     * Because the latch tracks [isConfirmedAt], holding past the threshold gives `wasConfirmed == true` and no
     * second fire (no double fire); releasing sets `pressStart` to null, so [isConfirmedAt] drops the latch
     * back to false, and a fresh press can confirm — and fire — again. All the mutation is the caller's single
     * `Boolean`; this function is pure edge detection: `!previouslyConfirmed && isConfirmedAt(now)`.
     */
    fun firesAt(previouslyConfirmed: Boolean, pressStartMillis: Long?, nowMillis: Long): Boolean =
        !previouslyConfirmed && isConfirmedAt(pressStartMillis, nowMillis)

    companion object {
        /** ~2 seconds, the spec's suggested hold for confirming end-of-session. */
        const val DEFAULT_HOLD_MILLIS = 2_000L
    }
}
