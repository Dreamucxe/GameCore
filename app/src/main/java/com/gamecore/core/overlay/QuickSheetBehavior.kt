package com.gamecore.core.overlay

/**
 * The quick sheet's auto-close state machine (spec §4), computed from timestamps rather than run on a timer.
 *
 * The sheet is the tap-and-back surface: a user flicks it open, hits a toggle, and drops back into the game.
 * Left open it dims the game for no reason, so §4 closes it after a period without a touch (a setting,
 * default 8 s). This models that period exactly the way [IdleDimmer] models the button's idle fade, and for
 * the same reason: it is arithmetic on an injected clock, not a `postDelayed`.
 *
 * Why arithmetic and not a timer:
 *  - **It survives a rebind.** A repeating `Handler` callback is state that lives on the service; when the
 *    overlay is torn down and rebuilt (rotation, a theme change read live, the service being recreated) that
 *    callback either leaks or is silently lost. A `lastInteractionMillis` timestamp is a single `Long` the
 *    view already knows, so the sheet re-derives "should I be closed by now?" from scratch on every frame it
 *    is asked to draw, and there is nothing to leak.
 *  - **It is one posted callback, not a heartbeat.** The view asks [shouldCloseAt] what the state is *now*
 *    and posts exactly one repaint at [nextChangeAfterMillis]; every touch cancels that one callback and the
 *    next draw posts a fresh one. There is no repeating tick eating a frame budget over a game (spec §9:
 *    nothing runs for a component that is idle beyond one scheduled wake).
 *  - **It is unit-testable with a fake clock.** No `Handler`, no `Looper`, no `android.*` — §10 lists the
 *    auto-close machine among the fake-clock tests, so the whole of the decision has to be plain Kotlin the
 *    way it is here.
 *
 * The gate — *whether* auto-close applies at all — is deliberately not here. Spec §4 makes it a setting the
 * user can turn off, and a sheet the user is actively dragging a slider on should not close mid-gesture; both
 * of those are the caller's to decide (it simply stops feeding this machine, or keeps `lastInteractionMillis`
 * fresh). This object answers one question: given the last touch and the time now, is the sheet past its
 * welcome. That single job is what keeps it testable.
 *
 * @param idleAfterMillis how long without a touch before the sheet closes. Defaults to
 *   [DEFAULT_AUTO_CLOSE_MILLIS] (8 s), the spec §4 default; the caller passes the user's setting when it
 *   differs.
 */
data class QuickSheetAutoClose(val idleAfterMillis: Long = DEFAULT_AUTO_CLOSE_MILLIS) {

    /**
     * Whether the sheet should be closed at [nowMillis], given the last touch was at [lastInteractionMillis].
     *
     * `>=`, not `>`, so the instant the window elapses counts as closed — the same boundary [IdleDimmer]
     * uses for the fade, kept identical so the two idle machines never disagree by a millisecond about what
     * "elapsed" means.
     */
    fun shouldCloseAt(lastInteractionMillis: Long, nowMillis: Long): Boolean {
        val elapsed = nowMillis - lastInteractionMillis
        return elapsed >= idleAfterMillis
    }

    /**
     * Milliseconds until the sheet is due to close, so the view can post exactly one repaint, or null if the
     * close time is already at or behind [nowMillis] and there is nothing left to schedule — the caller has
     * already been told (or should be, via [shouldCloseAt]) to close now.
     *
     * There is only ever one scheduled close: a single instant, [lastInteractionMillis] + [idleAfterMillis].
     * Any touch moves [lastInteractionMillis] forward, which is the whole of "interaction resets the timer" —
     * the previously posted callback is cancelled by the caller and the next call here hands back a fresh,
     * longer delay. Never negative.
     */
    fun nextChangeAfterMillis(lastInteractionMillis: Long, nowMillis: Long): Long? {
        val closeAt = lastInteractionMillis + idleAfterMillis
        return if (nowMillis >= closeAt) null else closeAt - nowMillis
    }

    companion object {
        /** The spec §4 default: the sheet closes 8 s after the last touch when auto-close is on. */
        const val DEFAULT_AUTO_CLOSE_MILLIS = 8_000L
    }
}
