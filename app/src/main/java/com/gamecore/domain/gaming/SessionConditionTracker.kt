package com.gamecore.domain.gaming

import com.gamecore.core.model.FrameRateSample
import com.gamecore.core.model.SessionEvent
import com.gamecore.core.model.SessionEventKind
import com.gamecore.core.model.ThermalStatus
import kotlin.math.roundToInt

/**
 * Turns a stream of samples into the handful of events that explain a session.
 *
 * This is deliberately separate from [SessionRecorder] and from the sampler: it is a pure state
 * machine over readings, so every rule below can be tested with four fake snapshots instead of a
 * device, a database and a game. The recorder owns the clock and the row; this owns the judgement
 * about when a reading is an event.
 *
 * Four rules define it:
 *
 *  - **Transitions, not readings.** A device that had battery saver on for the whole session should
 *    not produce an event every two seconds, and a report does not need to be told the same thing
 *    four hundred times. The first observation only seeds the state; later observations emit when the
 *    value changes. The one exception is thermal warning at the opening sample, because a session
 *    that began already throttled is a fact worth placing at its start.
 *  - **Frame outliers need a tail, not a mean.** A session can average 58 fps and still stutter: the
 *    99th percentile or the jank count is where that lives. The tracker uses those, requires a
 *    reliable sample ([FrameRateSample.isReliable]), and rate-limits itself so one bad minute does not
 *    fill the table with hundreds of rows.
 *  - **No cause is claimed.** A thermal transition is recorded as a transition. Whether it caused a
 *    particular frame-time spike is a question for `StutterAttribution`, which can see the whole
 *    timeline and is allowed to answer "unknown".
 *  - **Nothing is invented.** A device that does not expose thermal status or frame timing produces
 *    no events of those kinds. The absence is honest and the report says why, rather than treating
 *    an unreadable sensor as a normal reading.
 *
 * The state is mutable and not thread-safe. [SessionRecorder] calls it under its own mutex, one
 * sample at a time, which is the only caller.
 */
class SessionConditionTracker {

    private var thermal: ThermalStatus? = null
    private var refreshCapped: Boolean? = null
    private var powerSaver: Boolean? = null
    private var lastOutlierAtMillis = -OUTLIER_COOLDOWN_MILLIS

    /**
     * Folds one sample in and returns the events it produced, oldest first.
     *
     * [elapsedMillis] is the sample's offset from the session start, and [sessionId] is stamped onto
     * every event so the caller can write the list without a second mapping step.
     */
    fun eventsFor(
        sessionId: Long,
        elapsedMillis: Long,
        thermalStatus: ThermalStatus?,
        refreshCapped: Boolean?,
        powerSaver: Boolean,
        frameRate: FrameRateSample?,
    ): List<SessionEvent> = buildList {
        thermalStatus?.let { current ->
            val wasWarning = thermal?.warrantsAlert == true
            if (thermal == null) {
                if (current.warrantsAlert) {
                    add(event(sessionId, elapsedMillis, SessionEventKind.THERMAL_WARNING, current))
                }
            } else if (current.warrantsAlert != wasWarning) {
                val kind = if (current.warrantsAlert) {
                    SessionEventKind.THERMAL_WARNING
                } else {
                    SessionEventKind.THERMAL_RECOVERED
                }
                add(event(sessionId, elapsedMillis, kind, current))
            }
            thermal = current
        }

        refreshCapped?.let { current ->
            val previous = this@SessionConditionTracker.refreshCapped
            if (previous != null && previous != current) {
                add(
                    SessionEvent(
                        sessionId = sessionId,
                        elapsedMillis = elapsedMillis,
                        kind = if (current) {
                            SessionEventKind.REFRESH_CAPPED
                        } else {
                            SessionEventKind.REFRESH_RESTORED
                        },
                        detail = if (current) "Below the panel's peak rate" else "At the panel's peak rate",
                    ),
                )
            }
            this@SessionConditionTracker.refreshCapped = current
        }

        val saverWasOn = this@SessionConditionTracker.powerSaver
        if (saverWasOn != null && saverWasOn != powerSaver) {
            add(
                SessionEvent(
                    sessionId = sessionId,
                    elapsedMillis = elapsedMillis,
                    kind = if (powerSaver) {
                        SessionEventKind.POWER_SAVER_ON
                    } else {
                        SessionEventKind.POWER_SAVER_OFF
                    },
                    detail = "The system's power-save setting changed during the session.",
                ),
            )
        }
        this@SessionConditionTracker.powerSaver = powerSaver

        if (frameRate != null && frameRate.isReliable) {
            val p99 = frameRate.p99FrameMillis
            val jank = frameRate.jankPercent
            val spike = (p99 != null && p99 >= OUTLIER_P99_MILLIS) || jank >= OUTLIER_JANK_PERCENT
            if (spike && elapsedMillis - lastOutlierAtMillis >= OUTLIER_COOLDOWN_MILLIS) {
                lastOutlierAtMillis = elapsedMillis
                add(
                    SessionEvent(
                        sessionId = sessionId,
                        elapsedMillis = elapsedMillis,
                        kind = SessionEventKind.FRAME_OUTLIER,
                        detail = frameDetail(frameRate, p99, jank),
                        // Milliseconds when the p99 exists. A jank-only outlier has no single
                        // duration that means the same thing, so its value stays null rather than
                        // putting an fps figure in a field another reader assumes is milliseconds.
                        value = p99?.toDouble(),
                    ),
                )
            }
        }
    }

    private fun event(
        sessionId: Long,
        elapsedMillis: Long,
        kind: SessionEventKind,
        status: ThermalStatus,
    ): SessionEvent = SessionEvent(
        sessionId = sessionId,
        elapsedMillis = elapsedMillis,
        kind = kind,
        detail = status.label,
        value = status.level.toDouble(),
    )

    /**
     * The short line under a frame outlier.
     *
     * Each clause is only present when the platform produced it. "p99 68 ms" and "22% jank" are
     * different measurements and both are useful; joining them with a separator keeps one row
     * readable without turning it into a paragraph.
     */
    private fun frameDetail(
        frameRate: FrameRateSample,
        p99: Float?,
        jank: Float?,
    ): String = buildList {
        p99?.let { add("p99 ${it.roundToInt()} ms") }
        if (jank != null && jank >= OUTLIER_JANK_PERCENT) add("${jank.roundToInt()}% jank")
        frameRate.onePercentLowFps?.let { add("${it.roundToInt()} fps 1% low") }
    }.joinToString(" · ")

    private companion object {
        /**
         * A 99th percentile at or above 50 ms is a tail slower than 20 fps. On a 60 Hz display the
         * budget is 16.7 ms, so this is three frames in the worst hundred — visible, and not the
         * normal shape of a stable session.
         */
        const val OUTLIER_P99_MILLIS = 50f

        /** One frame in six missing its deadline is a stutter by any reasonable reading. */
        const val OUTLIER_JANK_PERCENT = 15f

        /** At most one row every ten seconds: a bad minute is one event, not thirty. */
        const val OUTLIER_COOLDOWN_MILLIS = 10_000L
    }
}
