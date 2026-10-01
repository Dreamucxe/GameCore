package com.gamecore.domain.trigger

import com.gamecore.core.model.VolumeTriggerButton
import com.gamecore.core.model.VolumeTriggerPressMode

/**
 * The phase of a single volume-key event, mapped straight from Android's `KeyEvent`: [DOWN] is
 * `ACTION_DOWN`, [UP] is `ACTION_UP`. Key *repeats* (`repeatCount > 0`) are not phases — the caller drops
 * them exactly as `QuickTriggerCoordinator.onKeyEvent` already does and lets [VolumePressClassifier.tick]
 * grow a hold instead, because a hold is a fact about elapsed time, not about how many repeat events the
 * platform chose to synthesise.
 */
enum class KeyPhase { DOWN, UP }

/**
 * Thresholds for [VolumePressClassifier]. Time is milliseconds throughout; there is no clock in here.
 */
data class VolumePressConfig(
    /**
     * How long after the *first* press a second press may begin and still count as a double. This is also,
     * unavoidably, the delay a lone tap must wait out before it can be called a [PressDecision.Single] —
     * see the KDoc on [VolumePressClassifier].
     */
    val doublePressWindowMs: Long = DEFAULT_DOUBLE_WINDOW_MS,
    /** How long the button must stay down, uninterrupted, before the press is a hold rather than a tap. */
    val longPressThresholdMs: Long = DEFAULT_LONG_PRESS_MS,
) {
    companion object {
        /** The order of the platform double-tap timeout; sibling to `QuickTriggerSettings.DEFAULT_WINDOW`. */
        const val DEFAULT_DOUBLE_WINDOW_MS = 320L

        /** A deliberate press-and-hold, comfortably past an accidental slow tap. */
        const val DEFAULT_LONG_PRESS_MS = 500L
    }
}

/**
 * What one call to [VolumePressClassifier.evaluate] or [VolumePressClassifier.tick] concluded for one
 * button. Every gesture surfaces exactly once:
 *  - [Single] — one isolated tap, confirmed only after the double-press window has elapsed with no second
 *    press (see the [VolumePressClassifier] KDoc for why this is inherently delayed).
 *  - [Double] — a second press began within the window; fired on that second `DOWN`. The first tap of a
 *    double is never also reported as a [Single].
 *  - [LongPressStart] / [LongPressEnd] — a press-and-hold: [LongPressStart] the instant the hold crosses
 *    [VolumePressConfig.longPressThresholdMs] (surfaced by [VolumePressClassifier.tick]), [LongPressEnd]
 *    on release, carrying the full held [durationMs].
 *  - [None] — nothing was concluded on this call.
 */
sealed interface PressDecision {
    data object None : PressDecision
    data object Single : PressDecision
    data object Double : PressDecision
    data object LongPressStart : PressDecision
    data class LongPressEnd(val durationMs: Long) : PressDecision

    /**
     * The [VolumeTriggerPressMode] this decision satisfies, or null for [None]. Both edges of a hold
     * ([LongPressStart] and [LongPressEnd]) map to [VolumeTriggerPressMode.HOLD], so the caller can ask
     * "which binding does this belong to?" and use the decision type itself for the start/end distinction.
     */
    val pressMode: VolumeTriggerPressMode?
        get() = when (this) {
            None -> null
            Single -> VolumeTriggerPressMode.SINGLE_TAP
            Double -> VolumeTriggerPressMode.DOUBLE_TAP
            LongPressStart, is LongPressEnd -> VolumeTriggerPressMode.HOLD
        }
}

/**
 * Where one button's press machine is between events. Exposed so tests and diagnostics can read it; do
 * not set it by hand — drive it through [VolumePressClassifier].
 */
enum class VolumePressPhase {
    /** No press in progress and nothing pending. */
    IDLE,

    /** The button is down and has not yet been held long enough to be a hold. */
    PRESSING,

    /** The press crossed [VolumePressConfig.longPressThresholdMs]; it is a hold and still held down. */
    HOLDING,

    /** One quick tap finished; the double-press window is being waited out for a possible second press. */
    AWAITING_SECOND,

    /** A double was recognised on the second `DOWN`; its matching `UP` is being swallowed. */
    AFTER_DOUBLE,
}

/**
 * The immutable per-button state the caller threads through — one instance per [VolumeTriggerButton].
 * Create it with [initial]; never mutate it. [VolumePressClassifier.evaluate] and
 * [VolumePressClassifier.tick] return the next state to carry forward, exactly as
 * [com.gamecore.domain.thermal.ThermalDownshiftState] is threaded through its machine.
 */
data class VolumePressState(
    val button: VolumeTriggerButton,
    val phase: VolumePressPhase,
    /** `DOWN` time of the press in progress (PRESSING / HOLDING), else null. */
    val pressDownMillis: Long?,
    /** `DOWN` time of the first tap while AWAITING_SECOND, else null. The double window is measured from here. */
    val firstTapDownMillis: Long?,
) {
    companion object {
        fun initial(button: VolumeTriggerButton): VolumePressState = VolumePressState(
            button = button,
            phase = VolumePressPhase.IDLE,
            pressDownMillis = null,
            firstTapDownMillis = null,
        )
    }

    /**
     * When a pending [PressDecision.Single] becomes confirmable, or null if no tap is waiting. The caller
     * schedules a [VolumePressClassifier.tick] at (or after) this instant; that tick is what turns the
     * deliberately delayed single into a decision.
     */
    fun pendingSingleDeadlineMillis(config: VolumePressConfig): Long? =
        firstTapDownMillis?.takeIf { phase == VolumePressPhase.AWAITING_SECOND }
            ?.plus(config.doublePressWindowMs)

    /**
     * When a press in progress will become a [PressDecision.LongPressStart], or null if none is in
     * progress. The caller schedules a [VolumePressClassifier.tick] at (or after) this instant to have the
     * hold recognised in real time rather than only on release.
     */
    fun pendingLongPressDeadlineMillis(config: VolumePressConfig): Long? =
        pressDownMillis?.takeIf { phase == VolumePressPhase.PRESSING }
            ?.plus(config.longPressThresholdMs)
}

/**
 * Classifies a stream of volume-button phases into single / double / long presses, one button at a time.
 *
 * A pure state machine in the mould of [com.gamecore.domain.thermal.ThermalDownshiftMachine]: no clock, no
 * Android, no sensors. The caller keeps one immutable [VolumePressState] per button, feeds each [KeyPhase]
 * with the event's timestamp through [evaluate], advances time through [tick], and threads the returned
 * state forward. Time is a plain `Long`, so the whole thing runs — and is tested — on the JVM.
 *
 * ## The honest tradeoff: a single tap is confirmed *late*, on purpose
 * At the moment a tap happens it is indistinguishable from the first half of a double tap — the only thing
 * that tells them apart is whether a second press follows. So [PressDecision.Single] is deliberately
 * withheld until the double-press window ([VolumePressConfig.doublePressWindowMs]) has fully elapsed with
 * no second press. There is no way around this that still supports double tap: firing Single immediately
 * would make every double tap emit a spurious Single first. We choose correctness over immediacy and state
 * the cost plainly here rather than hide it — **single-tap actions feel one window (~320 ms) slower than
 * double-tap and hold actions, which fire the instant they become unambiguous.**
 *
 * Because there is no internal clock, that delayed Single is surfaced by [tick]: the caller schedules a
 * tick at [VolumePressState.pendingSingleDeadlineMillis]. As a convenience it is also surfaced by
 * [evaluate] when the *next* `DOWN` for the button arrives only after the window has passed — the earlier
 * tap is then reported as [PressDecision.Single] and that `DOWN` begins a fresh press.
 *
 * ## Holds
 * A hold is likewise a statement about elapsed time, so it too is grown by [tick]: while the button is
 * down, a tick at [VolumePressState.pendingLongPressDeadlineMillis] emits [PressDecision.LongPressStart],
 * and the release then emits [PressDecision.LongPressEnd] with the full duration. If the caller feeds only
 * key phases and never ticks, a release that was in fact held past the threshold is still classified
 * correctly as a hold ([PressDecision.LongPressEnd] on the `UP`), only without the interim start edge.
 *
 * ## Independence
 * Buttons are independent by construction: nothing is shared between two [VolumePressState]s, so the caller
 * simply keeps one per button (see [VolumePressState.button]) and routes each key event to its own.
 */
object VolumePressClassifier {

    /** Feeds one [KeyPhase] for the button that [state] tracks, observed at [nowMillis]. */
    fun evaluate(
        state: VolumePressState,
        config: VolumePressConfig,
        nowMillis: Long,
        phase: KeyPhase,
    ): Pair<VolumePressState, PressDecision> = when (phase) {
        KeyPhase.DOWN -> onDown(state, config, nowMillis)
        KeyPhase.UP -> onUp(state, config, nowMillis)
    }

    /**
     * Advances time with no key event, resolving the two purely time-based outcomes: a pending single once
     * the double-press window has elapsed, and the start of a hold once the long-press threshold has
     * elapsed. Safe to call as often as the caller likes — it only ever acts at or after a deadline, and
     * returns [PressDecision.None] otherwise.
     */
    fun tick(
        state: VolumePressState,
        config: VolumePressConfig,
        nowMillis: Long,
    ): Pair<VolumePressState, PressDecision> = when (state.phase) {
        VolumePressPhase.PRESSING -> {
            val down = state.pressDownMillis
            if (down != null && nowMillis - down >= config.longPressThresholdMs) {
                state.copy(phase = VolumePressPhase.HOLDING) to PressDecision.LongPressStart
            } else {
                state to PressDecision.None
            }
        }

        VolumePressPhase.AWAITING_SECOND -> {
            val first = state.firstTapDownMillis
            if (first != null && nowMillis - first >= config.doublePressWindowMs) {
                VolumePressState.initial(state.button) to PressDecision.Single
            } else {
                state to PressDecision.None
            }
        }

        else -> state to PressDecision.None
    }

    private fun onDown(
        state: VolumePressState,
        config: VolumePressConfig,
        nowMillis: Long,
    ): Pair<VolumePressState, PressDecision> = when (state.phase) {
        VolumePressPhase.IDLE, VolumePressPhase.AFTER_DOUBLE ->
            state.copy(
                phase = VolumePressPhase.PRESSING,
                pressDownMillis = nowMillis,
                firstTapDownMillis = null,
            ) to PressDecision.None

        VolumePressPhase.AWAITING_SECOND -> {
            val first = state.firstTapDownMillis
            // Strictly inside the window: at exactly the deadline `tick` has already promised a Single, so
            // a DOWN landing on that same instant must start a fresh press, not complete a double.
            val withinWindow = first != null && nowMillis - first < config.doublePressWindowMs
            if (withinWindow) {
                // A second press begins inside the window → a double, fired now on the DOWN. Its matching
                // UP is swallowed, and the first tap is never separately reported as a Single.
                state.copy(
                    phase = VolumePressPhase.AFTER_DOUBLE,
                    pressDownMillis = nowMillis,
                    firstTapDownMillis = null,
                ) to PressDecision.Double
            } else {
                // The window already lapsed before this press, so the earlier tap resolves as a Single now
                // (if a tick had not already surfaced it) and this DOWN starts a fresh press.
                state.copy(
                    phase = VolumePressPhase.PRESSING,
                    pressDownMillis = nowMillis,
                    firstTapDownMillis = null,
                ) to PressDecision.Single
            }
        }

        // Already down (e.g. a key repeat that slipped past the caller's filter). Nothing new to conclude.
        VolumePressPhase.PRESSING, VolumePressPhase.HOLDING -> state to PressDecision.None
    }

    private fun onUp(
        state: VolumePressState,
        config: VolumePressConfig,
        nowMillis: Long,
    ): Pair<VolumePressState, PressDecision> = when (state.phase) {
        VolumePressPhase.PRESSING -> {
            val down = state.pressDownMillis
            val duration = if (down != null) nowMillis - down else 0L
            if (down != null && duration >= config.longPressThresholdMs) {
                // Held past the threshold, but no tick recognised it in real time. Report the completed
                // hold on release; there was no separate LongPressStart because no tick advanced time.
                VolumePressState.initial(state.button) to PressDecision.LongPressEnd(duration)
            } else {
                // A quick tap. Withhold the verdict until the double-press window elapses (see class KDoc);
                // the window is measured from this tap's DOWN.
                state.copy(
                    phase = VolumePressPhase.AWAITING_SECOND,
                    pressDownMillis = null,
                    firstTapDownMillis = down,
                ) to PressDecision.None
            }
        }

        VolumePressPhase.HOLDING -> {
            val down = state.pressDownMillis
            val duration = if (down != null) nowMillis - down else 0L
            VolumePressState.initial(state.button) to PressDecision.LongPressEnd(duration)
        }

        // The release of the second tap of a double: already reported on its DOWN, so just consume it.
        VolumePressPhase.AFTER_DOUBLE -> VolumePressState.initial(state.button) to PressDecision.None

        // A stray UP with no press in progress. Ignore it.
        VolumePressPhase.IDLE, VolumePressPhase.AWAITING_SECOND -> state to PressDecision.None
    }
}
