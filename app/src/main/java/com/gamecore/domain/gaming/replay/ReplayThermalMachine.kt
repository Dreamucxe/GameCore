package com.gamecore.domain.gaming.replay

import com.gamecore.core.model.ThermalClass

/**
 * Auto pause/resume of the Instant Replay buffer on heat (audit A7), as a pure state machine mirroring
 * [com.gamecore.domain.thermal.ThermalDownshiftMachine]: the caller owns the immutable [ReplayThermalState],
 * calls [ReplayThermalMachine.evaluate] each monitoring tick, performs any [ReplayThermalDecision], and
 * threads the returned state back.
 *
 * Unlike the downshift machine this one never turns the feature *off* — the user's setting is kept while the
 * buffer is paused, and it resumes on its own once the device cools. Two rules protect the user from a
 * machine that misreads a device on the edge of throttling:
 *  - A pause needs [ThermalClass.CRITICAL] sustained for [ReplayThermalConfig.sustainCriticalMillis]; a
 *    resume needs a below-critical run of [ReplayThermalConfig.sustainCoolMillis]. A brief spike or dip
 *    across the line does nothing.
 *  - No two transitions happen closer together than [ReplayThermalConfig.minIntervalMillis], so a device
 *    flapping in and out of CRITICAL cannot pause-resume-pause on every tick.
 *
 * It keys strictly on `== CRITICAL` (the audit's "actively critical" gate; the platform SEVERE maps to
 * [ThermalClass.HOT], which is intentionally *not* enough to pause a capture). Everything below CRITICAL —
 * including [ThermalClass.UNAVAILABLE] — counts as the cool side, matching the fixed binary contract.
 */
data class ReplayThermalConfig(
    /** How long CRITICAL must hold before the buffer pauses — 5 s by default. */
    val sustainCriticalMillis: Long = DEFAULT_SUSTAIN_CRITICAL_MILLIS,
    /** How long below-CRITICAL must hold before the buffer resumes — 15 s by default. */
    val sustainCoolMillis: Long = DEFAULT_SUSTAIN_COOL_MILLIS,
    /** The shortest gap between a pause and a resume (or vice versa) — 30 s by default. */
    val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
) {
    companion object {
        const val DEFAULT_SUSTAIN_CRITICAL_MILLIS = 5_000L
        const val DEFAULT_SUSTAIN_COOL_MILLIS = 15_000L
        const val DEFAULT_MIN_INTERVAL_MILLIS = 30_000L
    }
}

/**
 * The state the caller threads through a session. Immutable; [ReplayThermalMachine.evaluate] returns a copy.
 *
 * @param paused whether the buffer is currently paused for heat.
 * @param lastChangeMillis when the last pause/resume happened, for the min-interval anti-flap; null before
 *   the first transition.
 * @param criticalSinceMillis when the current unbroken run of CRITICAL began; null while not critical.
 * @param coolSinceMillis when the current unbroken below-CRITICAL run began; null while critical.
 */
data class ReplayThermalState(
    val paused: Boolean = false,
    val lastChangeMillis: Long? = null,
    val criticalSinceMillis: Long? = null,
    val coolSinceMillis: Long? = null,
)

/** What the caller should do this tick. The service performs it; this machine only chooses it. */
sealed interface ReplayThermalDecision {
    /** Nothing to do; the buffer's paused/running state is unchanged. */
    data object NoChange : ReplayThermalDecision

    /** Pause the buffer (stop feeding the encoder) — the device is sustained-critical. */
    data class Pause(val reason: String) : ReplayThermalDecision

    /** Resume the buffer — the device has cooled and stayed cool. */
    data class Resume(val reason: String) : ReplayThermalDecision
}

/**
 * The pure form, matching [com.gamecore.domain.thermal.ThermalDownshiftMachine]: an `object` whose
 * [evaluate] takes `nowMillis` directly. Prefer [ClockedReplayThermalMachine] when a caller already holds a
 * [ReplayClock].
 */
object ReplayThermalMachine {

    private const val PAUSE_REASON =
        "Pausing the replay buffer while the device stays critically hot."
    private const val RESUME_REASON =
        "Resuming the replay buffer now the device is no longer critical."

    /**
     * One tick. Returns the state to carry forward and what to do to the buffer.
     *
     * @param thermalClass the classification [com.gamecore.core.model.ThermalClassifier] already produced.
     * @param nowMillis this tick's time.
     */
    fun evaluate(
        state: ReplayThermalState,
        config: ReplayThermalConfig,
        thermalClass: ThermalClass,
        nowMillis: Long,
    ): Pair<ReplayThermalState, ReplayThermalDecision> {
        val intervalPassed = state.lastChangeMillis == null ||
            nowMillis - state.lastChangeMillis >= config.minIntervalMillis

        return if (thermalClass == ThermalClass.CRITICAL) {
            // A critical tick starts (or continues) the critical timer and kills any cool timer.
            val criticalSince = state.criticalSinceMillis ?: nowMillis
            val s = state.copy(criticalSinceMillis = criticalSince, coolSinceMillis = null)
            val sustained = nowMillis - criticalSince >= config.sustainCriticalMillis
            if (!s.paused && sustained && intervalPassed) {
                s.copy(paused = true, lastChangeMillis = nowMillis) to
                    ReplayThermalDecision.Pause(PAUSE_REASON)
            } else {
                s to ReplayThermalDecision.NoChange
            }
        } else {
            // Below critical: start (or continue) the cool timer and kill any critical timer.
            val coolSince = state.coolSinceMillis ?: nowMillis
            val s = state.copy(coolSinceMillis = coolSince, criticalSinceMillis = null)
            val sustained = nowMillis - coolSince >= config.sustainCoolMillis
            if (s.paused && sustained && intervalPassed) {
                s.copy(paused = false, lastChangeMillis = nowMillis) to
                    ReplayThermalDecision.Resume(RESUME_REASON)
            } else {
                s to ReplayThermalDecision.NoChange
            }
        }
    }
}

/**
 * The thin wrapper that literally satisfies §0's "injected [ReplayClock]" wording: it holds the clock and a
 * config, and each [evaluate] reads the time from the clock rather than taking a `nowMillis` argument.
 *
 * Named apart from the [ReplayThermalMachine] object (which owns the pure logic) only because a Kotlin file
 * cannot have an object and a class of the same name; the behaviour is identical, delegated tick-for-tick.
 */
class ClockedReplayThermalMachine(
    private val clock: ReplayClock,
    private val config: ReplayThermalConfig = ReplayThermalConfig(),
) {
    fun evaluate(
        state: ReplayThermalState,
        thermalClass: ThermalClass,
    ): Pair<ReplayThermalState, ReplayThermalDecision> =
        ReplayThermalMachine.evaluate(state, config, thermalClass, clock.nowMillis())
}
