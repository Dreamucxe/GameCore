package com.gamecore.domain.power

import com.gamecore.core.model.ThermalClass

/**
 * "Keep full performance" (spec §D), as pure arithmetic on state passed in rather than a running loop.
 *
 * The feature is one sentence with a great many edge cases: when a game session with this profile
 * setting starts, turn Android's own battery saver off so it stops capping the refresh rate, and put it
 * back exactly as it was when the session ends. Every complication is a promise the spec makes about a
 * device that does not cooperate — a saver the system re-enables at low battery, a write that does not
 * take, a device too hot to be pushed — and each of those is a statement about *state*, not about a
 * callback. So this models the decision the same way [com.gamecore.core.overlay.HoldToConfirm] models a
 * hold: the caller (the service) owns the mutable [BatterySaverOverrideState], asks this what to do at
 * each moment, and performs the answer through `SettingsWriter(low_power)` and the restore journal.
 *
 * What this file must never do is claim success it cannot see. Turning the saver off is a settings write
 * that some builds accept and ignore (extreme/adaptive saver on top of `low_power`), so the service reads
 * the value back a couple of seconds later and hands the result to [onReadback]; a saver still on after a
 * disable is [OverrideStatus.READBACK_MISMATCH], reported plainly, not hidden.
 *
 * Two rules override everything else, in this order:
 *  1. **Thermal wins.** A [ThermalClass.CRITICAL] device is never pushed, toggle or no toggle — the whole
 *     point of the override is more sustained performance, and there is none to be had from a phone that
 *     is throttling to protect itself. [OverrideStatus.BLOCKED_THERMAL].
 *  2. **Do not fight the system.** If the platform re-enables the saver mid-session (its own low-battery
 *     threshold), that is a decision with a reason this app cannot see, so the override latches paused for
 *     the rest of the session rather than turning it straight back off in a loop. Only the user
 *     re-enabling the toggle clears the latch, and even then a re-apply waits out
 *     [BatterySaverOverrideConfig.minReapplyIntervalMillis] so a saver oscillating around a battery
 *     threshold cannot become a write storm.
 */
data class BatterySaverOverrideConfig(
    /** Whether this profile opted in. Off is the default and the common case. */
    val enabled: Boolean = false,
    /**
     * The shortest gap between two disable writes, so a system that re-enables the saver and a user who
     * re-enables the toggle cannot together produce a fight loop. 60 s by default.
     */
    val minReapplyIntervalMillis: Long = DEFAULT_MIN_REAPPLY_MILLIS,
) {
    companion object {
        const val DEFAULT_MIN_REAPPLY_MILLIS = 60_000L
    }
}

/** The chip the pill and panel show, and its human line. */
enum class OverrideStatus(val label: String) {
    /** The feature is off, or there was nothing to override. */
    INACTIVE("Full performance off"),

    /** GameCore turned the saver off and it stayed off. */
    OVERRIDDEN("Full performance: battery saver overridden"),

    /** The system turned the saver back on (low battery); the override is paused for the session. */
    PAUSED_SYSTEM_REENABLED("Paused: system re-enabled battery saver"),

    /** The device is at [ThermalClass.CRITICAL]; thermal protection outranks the override. */
    BLOCKED_THERMAL("Paused: device too hot for full performance"),

    /** The feature needs Shizuku, which is not connected. */
    UNAVAILABLE("Unavailable: needs Shizuku"),

    /** The disable write was accepted and the saver is still on. */
    READBACK_MISMATCH("Full performance requested, battery saver still on"),
}

/**
 * What the caller should do to the device. The service performs these; this file only chooses them.
 *
 * [Restore]'s [toOn] is the captured original — restoring is "put it back where the user had it", which
 * is `on` only when the user's saver was on before GameCore touched it.
 */
sealed interface BatterySaverAction {
    data object DoNothing : BatterySaverAction
    data class CaptureAndDisable(val reason: String) : BatterySaverAction
    data class Restore(val toOn: Boolean, val reason: String) : BatterySaverAction
}

/**
 * The state the caller threads through a session. Immutable; every function returns a new copy.
 *
 * [originalSaverOn] is the load-bearing field: it is null until the session start captures it, and it is
 * what [onSessionEnd] restores to. `false` (saver was already off) and `true` (we turned a real one off)
 * are both real captures and are treated differently at restore; null means "we never got far enough to
 * have an obligation", so restore is a no-op.
 */
data class BatterySaverOverrideState(
    val originalSaverOn: Boolean? = null,
    val overrideApplied: Boolean = false,
    val lastApplyMillis: Long? = null,
    val systemReenabled: Boolean = false,
    val status: OverrideStatus = OverrideStatus.INACTIVE,
) {
    companion object {
        fun initial(): BatterySaverOverrideState = BatterySaverOverrideState()
    }
}

object BatterySaverOverrideMachine {

    /**
     * Session start: capture the current saver state and disable it, unless something forbids that.
     *
     * The order of the guards is the order of authority. A disabled profile does nothing. Missing Shizuku
     * is [OverrideStatus.UNAVAILABLE] — the toggle can be on and still have no way to act. A critical
     * device is blocked outright. Only then, if the saver is actually on, is it captured and disabled; a
     * saver already off is captured as `false` so the session-end restore is a correct no-op, and nothing
     * is written.
     */
    fun onSessionStart(
        state: BatterySaverOverrideState,
        config: BatterySaverOverrideConfig,
        nowMillis: Long,
        saverCurrentlyOn: Boolean,
        shizukuAvailable: Boolean,
        thermalClass: ThermalClass,
    ): Pair<BatterySaverOverrideState, BatterySaverAction> {
        if (!config.enabled) {
            return state.copy(status = OverrideStatus.INACTIVE) to BatterySaverAction.DoNothing
        }
        if (!shizukuAvailable) {
            return state.copy(status = OverrideStatus.UNAVAILABLE) to BatterySaverAction.DoNothing
        }
        if (thermalClass == ThermalClass.CRITICAL) {
            // Capture nothing and change nothing: the poll will disable once the device cools, and there
            // is no obligation to restore something we never touched.
            return state.copy(status = OverrideStatus.BLOCKED_THERMAL) to BatterySaverAction.DoNothing
        }
        if (!saverCurrentlyOn) {
            // Record that the user's saver was off, so a later poll that overrides (should the system turn
            // it on) still restores to off. Nothing to do now.
            return state.copy(
                originalSaverOn = false,
                status = OverrideStatus.INACTIVE,
            ) to BatterySaverAction.DoNothing
        }
        return state.copy(
            originalSaverOn = true,
            overrideApplied = true,
            lastApplyMillis = nowMillis,
            status = OverrideStatus.OVERRIDDEN,
        ) to BatterySaverAction.CaptureAndDisable(
            "Turning Android's battery saver off so it stops capping the refresh rate.",
        )
    }

    /**
     * The read-back a couple of seconds after a disable write.
     *
     * The honest half of the feature: a saver still on after we asked it off is reported as a mismatch,
     * never as success. A saver confirmed off leaves the status where the disable left it.
     */
    fun onReadback(
        state: BatterySaverOverrideState,
        saverStillOn: Boolean,
    ): BatterySaverOverrideState = when {
        !state.overrideApplied -> state
        saverStillOn -> state.copy(status = OverrideStatus.READBACK_MISMATCH)
        else -> state.copy(status = OverrideStatus.OVERRIDDEN)
    }

    /**
     * The ~2s session poll.
     *
     * Thermal is checked first for the reason [onSessionStart] states — a critical device is never pushed.
     * Then the "do not fight the system" rule: if we overrode and the saver is on again, that was the
     * platform's doing, and the override latches paused for the session. Once latched it stays paused
     * (returning [BatterySaverAction.DoNothing]) until [userReenabled] clears it. A device where nothing
     * has drifted holds [OverrideStatus.OVERRIDDEN] with no write.
     */
    fun onPoll(
        state: BatterySaverOverrideState,
        config: BatterySaverOverrideConfig,
        nowMillis: Long,
        saverCurrentlyOn: Boolean,
        thermalClass: ThermalClass,
    ): Pair<BatterySaverOverrideState, BatterySaverAction> {
        if (!config.enabled || !state.overrideApplied) {
            return state to BatterySaverAction.DoNothing
        }
        if (thermalClass == ThermalClass.CRITICAL) {
            return state.copy(status = OverrideStatus.BLOCKED_THERMAL) to BatterySaverAction.DoNothing
        }
        if (state.systemReenabled) {
            // Already paused by the system this session; hold, whatever the saver reads now.
            return state.copy(status = OverrideStatus.PAUSED_SYSTEM_REENABLED) to
                BatterySaverAction.DoNothing
        }
        if (saverCurrentlyOn) {
            // The system turned it back on. Latch paused; do NOT reverse it.
            return state.copy(
                systemReenabled = true,
                status = OverrideStatus.PAUSED_SYSTEM_REENABLED,
            ) to BatterySaverAction.DoNothing
        }
        return state.copy(status = OverrideStatus.OVERRIDDEN) to BatterySaverAction.DoNothing
    }

    /**
     * The user re-enabling the toggle mid-session, which is the only thing that clears a system pause.
     *
     * Even then the re-apply is gated: a disable write is emitted only if [BatterySaverOverrideConfig
     * .minReapplyIntervalMillis] has passed since the last one, so a saver flapping around a battery
     * threshold cannot turn a user's re-enable into a write storm. Too soon → the latch clears but no
     * write goes out yet, and the next poll (after the interval) picks it up.
     */
    fun userReenabled(
        state: BatterySaverOverrideState,
        config: BatterySaverOverrideConfig,
        nowMillis: Long,
        saverCurrentlyOn: Boolean,
        thermalClass: ThermalClass,
    ): Pair<BatterySaverOverrideState, BatterySaverAction> {
        if (thermalClass == ThermalClass.CRITICAL) {
            return state.copy(status = OverrideStatus.BLOCKED_THERMAL) to BatterySaverAction.DoNothing
        }
        val cleared = state.copy(systemReenabled = false)
        if (!saverCurrentlyOn) {
            // Nothing to turn off; we are already at full performance.
            return cleared.copy(status = OverrideStatus.OVERRIDDEN) to BatterySaverAction.DoNothing
        }
        val since = state.lastApplyMillis
        val due = since == null || nowMillis - since >= config.minReapplyIntervalMillis
        return if (due) {
            cleared.copy(
                overrideApplied = true,
                lastApplyMillis = nowMillis,
                status = OverrideStatus.OVERRIDDEN,
            ) to BatterySaverAction.CaptureAndDisable(
                "Re-applying full performance at your request.",
            )
        } else {
            // Latch cleared but the interval gate holds the write back.
            cleared.copy(status = OverrideStatus.PAUSED_SYSTEM_REENABLED) to BatterySaverAction.DoNothing
        }
    }

    /**
     * Session end, process-death recovery and "Put settings back" all call this.
     *
     * Restores only what GameCore changed: an override that actually disabled a real saver restores it to
     * on; a session where the saver was already off (or was never captured) has no obligation and does
     * nothing. Idempotent — calling it twice restores once and then no-ops.
     */
    fun onSessionEnd(state: BatterySaverOverrideState): Pair<BatterySaverOverrideState, BatterySaverAction> {
        val ended = BatterySaverOverrideState.initial()
        return if (state.overrideApplied && state.originalSaverOn == true) {
            ended to BatterySaverAction.Restore(
                toOn = true,
                reason = "Putting Android's battery saver back on, where you had it.",
            )
        } else {
            ended to BatterySaverAction.DoNothing
        }
    }
}
