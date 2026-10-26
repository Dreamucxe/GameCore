package com.gamecore.domain.diagnostics

import com.gamecore.core.model.SessionEvent
import com.gamecore.core.model.SessionEventKind
import com.gamecore.core.model.SessionSample
import kotlin.math.abs

/**
 * What a frame-time spike can be attributed to, and how sure we are.
 *
 * Every entry here is a statement with evidence attached, and the unexplained case is first-class
 * rather than a failure: a spike with no recorded cause is the honest answer, and a report that
 * invents one would send a user chasing a problem the device never had.
 */
enum class StutterCauseKind(val label: String) {
    THERMAL("Thermal throttling"),
    REFRESH_CAP("Refresh rate capped"),
    POWER_SAVER("Battery saver"),
    PROFILE_FAILED("Profile not fully applied"),
    NETWORK("Network"),
    CPU_LOAD("Device load"),
    TEMPERATURE("Device temperature"),
}

/** How much the evidence supports the cause. HIGH is still correlation, not proof. */
enum class StutterConfidence(val label: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
}

/**
 * One explanation for one frame outlier.
 *
 * [evidence] is the sentence a user reads: it names the event or the reading and how close it was to
 * the spike. Anything that cannot fill that sentence honestly does not belong in this list.
 */
data class StutterCause(
    val kind: StutterCauseKind,
    val confidence: StutterConfidence,
    val evidence: String,
)

/** One frame outlier and whatever the record says about it. */
data class StutterFinding(
    val elapsedMillis: Long,
    /** The p99 frame time in milliseconds when the session recorded one, else null. */
    val frameDurationMillis: Float?,
    val causes: List<StutterCause>,
    val detail: String?,
) {
    val isUnexplained: Boolean get() = causes.isEmpty()
}

/**
 * The attribution for a whole session.
 *
 * [hasFrameTiming] is false when this device cannot see frame timing at all, which is the common case
 * — a reliable per-frame signal is not available on most devices without root. The UI must present
 * that as "this device cannot answer" rather than as "no stutter".
 */
data class StutterReport(
    val findings: List<StutterFinding> = emptyList(),
    val hasFrameTiming: Boolean = false,
    /** The number of outliers the session recorded, which can be more than [findings] are shown. */
    val outlierCount: Int = findings.size,
) {
    val hasOutliers: Boolean get() = findings.isNotEmpty()
    val unexplainedCount: Int get() = findings.count { it.isUnexplained }
}

/**
 * Correlates frame outliers with what was happening around them.
 *
 * Pure and deterministic: the same samples and events produce the same report, on a JVM, with no
 * clock and no Android. That is what makes every rule below testable, and the rules *are* the product
 * here — a wrong window is a wrong explanation, not a rendering detail.
 *
 * The rules:
 *
 *  - **A cause must still have been true.** A refresh cap restored before the spike is not a cause;
 *    the state between the last on/off pair and the spike is what counts. [lastActiveBefore] is that
 *    question, answered by walking back rather than scanning forward.
 *  - **A cause must be near the spike in time.** Every kind has a window ([THERMAL_WINDOW_MILLIS],
 *    [PROFILE_WINDOW_MILLIS], [NETWORK_WINDOW_MILLIS]). A condition that started three minutes later
 *    did not cause it, and the report does not claim it did. The stateful kinds (thermal, refresh,
 *    power saver) say how long they had been in force; the momentary kinds say how close they were.
 *  - **Correlation is never called causation.** Even MEDIUM confidence only asserts that a recorded
 *    condition overlapped the spike, and the evidence sentence says so in the user's own terms.
 *  - **No evidence, no cause.** An empty [StutterFinding.causes] means no recorded condition explains
 *    the outlier, which is a valid and common result.
 *
 * There is no learning and no heuristic beyond these windows. This object would rather say "unknown"
 * than be impressive: a user told "thermal throttling" by a guess is a user who will not believe the
 * next real one.
 */
object StutterAttribution {

    /** How many outliers the report explains. The worst ones are the ones worth reading. */
    const val MAX_FINDINGS = 5

    private const val CPU_LOAD_PERCENT = 85f
    private const val HOT_DECI_CELSIUS = 450

    /** How far before a thermal warning it may still explain a spike. */
    private const val THERMAL_WINDOW_MILLIS = 60_000L

    /** How long after a failed profile apply it may still explain a spike. */
    private const val PROFILE_WINDOW_MILLIS = 300_000L

    /** How near a network probe a spike may fall for the probe to be considered. */
    private const val NETWORK_WINDOW_MILLIS = 15_000L

    /**
     * Builds the report for one session's rows.
     *
     * [samples] supplies the correlates (load, temperature) and [events] the transitions. A session
     * with no `FRAME_OUTLIER` events returns an empty report; [explainsAbsence] turns that into the
     * sentence the screen shows, because there are three different reasons for it.
     */
    fun attribute(samples: List<SessionSample>, events: List<SessionEvent>): StutterReport {
        val outliers = events
            .filter { it.kind == SessionEventKind.FRAME_OUTLIER }
            .sortedBy { it.elapsedMillis }
        if (outliers.isEmpty()) {
            return StutterReport(
                findings = emptyList(),
                hasFrameTiming = samples.any { it.frameRate != null },
            )
        }
        val worst = outliers.drop(outliers.size - MAX_FINDINGS.coerceAtMost(outliers.size))
        return StutterReport(
            findings = worst.map { outlier ->
                StutterFinding(
                    elapsedMillis = outlier.elapsedMillis,
                    frameDurationMillis = outlier.value?.toFloat(),
                    causes = causesFor(outlier, samples, events),
                    detail = outlier.detail,
                )
            },
            hasFrameTiming = true,
            outlierCount = outliers.size,
        )
    }

    /**
     * The sentence that says why there is no attribution, or null when there is something to show.
     *
     * Three different absences, and conflating them is what makes a diagnostic screen untrustworthy:
     * this device cannot see frame timing at all; it can, and nothing stuttered; or the session has no
     * recorded events because it predates the timeline.
     */
    fun explainsAbsence(samples: List<SessionSample>, events: List<SessionEvent>): String? {
        if (events.any { it.kind == SessionEventKind.FRAME_OUTLIER }) return null
        if (samples.none { it.frameRate != null }) {
            return "This device exposes no reliable per-frame timing, so no frame-time spikes were " +
                "recorded for this session. GameCore does not estimate frame rate from the refresh " +
                "rate, so there is nothing to attribute."
        }
        if (events.isEmpty()) {
            return "No events were recorded for this session — it was recorded before GameCore kept " +
                "a session timeline."
        }
        return "Frame timing was recorded for this session and no frame-time spikes were detected."
    }

    // ---------------------------------------------------------------------------- per outlier

    private fun causesFor(
        outlier: SessionEvent,
        samples: List<SessionSample>,
        events: List<SessionEvent>,
    ): List<StutterCause> = buildList {
        val at = outlier.elapsedMillis

        lastActiveBefore(events, at, SessionEventKind.THERMAL_WARNING, SessionEventKind.THERMAL_RECOVERED)
            ?.takeIf { at - it <= THERMAL_WINDOW_MILLIS }
            ?.let { startedAt ->
                val after = at - startedAt
                add(
                    StutterCause(
                        kind = StutterCauseKind.THERMAL,
                        confidence = if (after <= 10_000L) {
                            StutterConfidence.MEDIUM
                        } else {
                            StutterConfidence.LOW
                        },
                        evidence = "The platform started throttling ${seconds(after)} before this " +
                            "spike and had not recovered.",
                    ),
                )
            }

        lastActiveBefore(events, at, SessionEventKind.REFRESH_CAPPED, SessionEventKind.REFRESH_RESTORED)
            ?.let { startedAt ->
                add(
                    StutterCause(
                        kind = StutterCauseKind.REFRESH_CAP,
                        confidence = StutterConfidence.LOW,
                        evidence = "The display was below its peak rate from ${seconds(at - startedAt)} " +
                            "before this spike. A recording, not proof it caused this spike.",
                    ),
                )
            }

        lastActiveBefore(events, at, SessionEventKind.POWER_SAVER_ON, SessionEventKind.POWER_SAVER_OFF)
            ?.let { startedAt ->
                add(
                    StutterCause(
                        kind = StutterCauseKind.POWER_SAVER,
                        confidence = StutterConfidence.LOW,
                        evidence = "Battery saver was on from ${seconds(at - startedAt)} before this " +
                            "spike. A recording, not proof it caused this spike.",
                    ),
                )
            }

        events
            .filter { it.kind == SessionEventKind.PROFILE_APPLY_FAILED }
            .firstOrNull { at - it.elapsedMillis in 0..PROFILE_WINDOW_MILLIS }
            ?.let {
                add(
                    StutterCause(
                        kind = StutterCauseKind.PROFILE_FAILED,
                        confidence = StutterConfidence.LOW,
                        evidence = "The profile at launch was not fully applied, so the settings it " +
                            "could not confirm were unavailable for the whole session.",
                    ),
                )
            }

        val spike = events
            .filter { it.kind == SessionEventKind.NETWORK_SPIKE }
            .minByOrNull { abs(it.elapsedMillis - at) }
        if (spike != null && abs(spike.elapsedMillis - at) <= NETWORK_WINDOW_MILLIS) {
            add(
                StutterCause(
                    kind = StutterCauseKind.NETWORK,
                    confidence = if (abs(spike.elapsedMillis - at) <= 5_000L) {
                        StutterConfidence.MEDIUM
                    } else {
                        StutterConfidence.LOW
                    },
                    evidence = "A network probe completed slowly within " +
                        "${seconds(abs(spike.elapsedMillis - at))} of this spike. The probe is a TCP " +
                        "handshake to the host set in Settings, not the game's server.",
                ),
            )
        }

        nearestSample(samples, at)?.let { sample ->
            val cpu = sample.cpuPercent
            if (cpu != null && cpu >= CPU_LOAD_PERCENT) {
                add(
                    StutterCause(
                        kind = StutterCauseKind.CPU_LOAD,
                        confidence = StutterConfidence.LOW,
                        evidence = "Total processor use was ${"%.0f".format(cpu)}% across every app on " +
                            "the device a moment before this spike.",
                    ),
                )
            }
            val temperature = sample.temperatureDeciCelsius
            if (temperature != null && temperature >= HOT_DECI_CELSIUS) {
                add(
                    StutterCause(
                        kind = StutterCauseKind.TEMPERATURE,
                        confidence = StutterConfidence.LOW,
                        evidence = "The device was already at " +
                            "${"%.1f".format(temperature / 10f)} °C before this spike.",
                    ),
                )
            }
        }
    }

    /**
     * When the last "on" transition before [at] happened, or null if the state was off.
     *
     * [onKind] opens the state and [offKind] closes it; the pair is what makes "still active" a
     * well-defined question. Only the last transition of either kind before [at] matters, so this
     * walks back rather than scanning forward — a session with hundreds of probes around one outlier
     * should not pay for all of them.
     */
    private fun lastActiveBefore(
        events: List<SessionEvent>,
        at: Long,
        onKind: SessionEventKind,
        offKind: SessionEventKind,
    ): Long? {
        val lastOn = events.lastOrNull { it.kind == onKind && it.elapsedMillis <= at } ?: return null
        val lastOff = events.lastOrNull { it.kind == offKind && it.elapsedMillis <= at }
        return if (lastOff == null || lastOn.elapsedMillis > lastOff.elapsedMillis) {
            lastOn.elapsedMillis
        } else {
            null
        }
    }

    /** The sample closest in time to [at], by absolute distance. */
    private fun nearestSample(samples: List<SessionSample>, at: Long): SessionSample? =
        samples.minByOrNull { abs(it.elapsedMillis - at) }

    private fun seconds(millis: Long): String {
        val clamped = millis.coerceAtLeast(0L)
        return if (clamped < 1_000L) "under a second" else "${(clamped + 500L) / 1_000L} s"
    }
}
