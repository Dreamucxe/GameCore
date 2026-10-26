package com.gamecore.domain.diagnostics

import com.gamecore.core.model.SessionEvent
import com.gamecore.core.model.SessionEventKind
import com.gamecore.core.model.SessionSample

/**
 * Which part of the network path a frame-time spike lined up with.
 *
 * The list stops at what an app can actually observe. There is no `GAME_SERVER` value on purpose:
 * GameCore cannot see a game's traffic, cannot open its socket, and has no address for it, so a
 * verdict naming the server would be a guess dressed as a measurement. [UPSTREAM] is the honest
 * ceiling — the path beyond this device answered slowly at that moment, and where the traffic went
 * after that is not something this record can say.
 */
enum class NetworkVerdict(val label: String) {
    /** Latency spiked beyond this device while the local link was quiet. */
    UPSTREAM("Beyond this device"),

    /** Latency spiked between this device and the router, before the wider network was involved. */
    LOCAL_LINK("This device to the router"),

    /** Both lanes spiked at once, so this record cannot say which one caused the frame time. */
    NETWORK_CORRELATED("Network, not localised"),

    /** Nothing in the network record lines up with the spikes — or there is no record to compare. */
    UNKNOWN("Not supported by the network record"),
}

/**
 * The network half of the session's attribution, as counts and one verdict.
 *
 * [stutterCount] is the number of frame-time outliers [StutterAttribution] found, so the two cards
 * cannot disagree about how many spikes a session had. [correlatedStutters] is how many of those were
 * within [NetworkAttribution.CORRELATION_WINDOW_MILLIS] of a recorded network spike.
 */
data class NetworkReport(
    val verdict: NetworkVerdict,
    val stutterCount: Int = 0,
    val correlatedStutters: Int = 0,
    val upstreamSpikes: Int = 0,
    val localSpikes: Int = 0,
    val probeCount: Int = 0,
    val hasLocalLane: Boolean = false,
    val hasUpstreamLane: Boolean = false,
) {
    /** True when there is enough evidence for the card to say anything at all. */
    val hasProbes: Boolean get() = probeCount > 0
    val hasStutters: Boolean get() = stutterCount > 0
    val isCorrelated: Boolean get() = correlatedStutters > 0
}

/**
 * Correlates the session's frame-time outliers with the two network lanes it recorded.
 *
 * Pure, like [StutterAttribution] and for the same reason: the rules are the product, and they are
 * testable inputs-to-outputs on a JVM with no device, no socket and no clock.
 *
 * The rules:
 *
 *  - **Both lanes or nothing.** Upstream alone cannot tell a slow router from a slow internet path,
 *    and local alone cannot tell whether the wider network was also struggling. The verdict says
 *    [NetworkVerdict.NETWORK_CORRELATED] when both spike rather than picking the more dramatic one.
 *  - **A spike is a point, and a frame is a window.** Probes are two seconds apart and a frame-time
 *    outlier is reported at its window's end, so a correlation needs a window wider than one probe
 *    interval. [CORRELATION_WINDOW_MILLIS] is deliberately one interval either side: tighter would
 *    miss the probe that measured the stall, looser would start correlating unrelated minutes.
 *  - **No network spike is not "the network was fine".** A session with stutters and quiet probes
 *    returns [NetworkVerdict.UNKNOWN], and the wording says the network is *not supported* as an
 *    explanation — never that the device is the cause. The device-side explanation is
 *    [StutterAttribution]'s job and this card does not second-guess it.
 *  - **An empty record is first-class.** A session recorded before these events existed, or one where
 *    the user turned probes off, has no lanes to compare. That is [NetworkVerdict.UNKNOWN] with
 *    [probeCount] zero, and the card says so rather than drawing a verdict with no evidence.
 */
object NetworkAttribution {

    /**
     * How close a network spike has to be to a frame outlier to count as the same moment.
     *
     * Three seconds: one probe interval either side of the outlier. The probes are the sampling
     * resolution, so a tighter window would be claiming a precision the record does not have, and a
     * looser one would start pairing unrelated events in a long session.
     */
    const val CORRELATION_WINDOW_MILLIS = 3_000L

    /**
     * The verdict for [samples] and [events].
     *
     * [samples] is used only through [StutterAttribution], so the stutter count on this card is the
     * same number the attribution card shows. [events] supplies the two probe lanes and their spikes.
     */
    fun attribute(
        samples: List<SessionSample> = emptyList(),
        events: List<SessionEvent> = emptyList(),
    ): NetworkReport {
        val stutterReport = StutterAttribution.attribute(samples, events)
        val stutterCount = stutterReport.outlierCount
        val stutterTimes = events
            .filter { it.kind == SessionEventKind.FRAME_OUTLIER }
            .map { it.elapsedMillis }
        val probes = events.filter { it.kind == SessionEventKind.NETWORK_PROBE }
        val spikes = events.filter { it.kind == SessionEventKind.NETWORK_SPIKE }

        val upstreamSpikes = spikes.filter { it.lane() == LANE_UPSTREAM }
        val localSpikes = spikes.filter { it.lane() == LANE_LOCAL }

        val hasUpstream = probes.any { it.lane() == LANE_UPSTREAM }
        val hasLocal = probes.any { it.lane() == LANE_LOCAL }

        if (stutterTimes.isEmpty() || probes.isEmpty()) {
            return NetworkReport(
                verdict = NetworkVerdict.UNKNOWN,
                stutterCount = stutterCount,
                upstreamSpikes = upstreamSpikes.size,
                localSpikes = localSpikes.size,
                probeCount = probes.size,
                hasLocalLane = hasLocal,
                hasUpstreamLane = hasUpstream,
            )
        }

        // Each stutter is attributed once per lane: a spike that lines up with both is evidence for
        // both, which is exactly why the both-spiked verdict exists rather than a tie-break. Every
        // outlier is counted, not only the few the attribution card chose to explain.
        val localMatches = stutterTimes.count { time -> localSpikes.any { it.near(time) } }
        val upstreamMatches = stutterTimes.count { time -> upstreamSpikes.any { it.near(time) } }

        val correlated = stutterTimes.count { time ->
            (localSpikes + upstreamSpikes).any { it.near(time) }
        }

        val verdict = when {
            localMatches > 0 && upstreamMatches > 0 -> NetworkVerdict.NETWORK_CORRELATED
            localMatches > 0 -> NetworkVerdict.LOCAL_LINK
            upstreamMatches > 0 -> NetworkVerdict.UPSTREAM
            else -> NetworkVerdict.UNKNOWN
        }

        return NetworkReport(
            verdict = verdict,
            stutterCount = stutterCount,
            correlatedStutters = correlated,
            upstreamSpikes = upstreamSpikes.size,
            localSpikes = localSpikes.size,
            probeCount = probes.size,
            hasLocalLane = hasLocal,
            hasUpstreamLane = hasUpstream,
        )
    }

    /**
     * The sentence for a card with no stutters, or null when there is something to draw.
     *
     * Kept here rather than in the composable so the wording about what the record can and cannot say
     * is testable alongside the rules that produce it.
     */
    fun explainsAbsence(samples: List<SessionSample>, events: List<SessionEvent>): String? {
        val report = attribute(samples, events)
        return when {
            !report.hasProbes && !report.hasStutters ->
                "This session has no frame-time outliers and no network probes in its record. That " +
                    "is not the same as a clean network: nothing was measured to compare."
            !report.hasProbes ->
                "This session has frame-time outliers but no network probes, so there is nothing to " +
                    "compare them against. Network attribution needs probes taken during the session."
            !report.hasStutters && report.probeCount > 0 ->
                "No frame-time outliers were recorded, so there is nothing for the network record to " +
                    "explain. ${report.probeCount} network probe(s) were taken."
            else -> null
        }
    }

    /** The lane a probe or spike belongs to, from the detail the recorder writes. */
    private fun SessionEvent.lane(): String? = when (detail?.lowercase()) {
        LANE_UPSTREAM -> LANE_UPSTREAM
        LANE_LOCAL -> LANE_LOCAL
        else -> null
    }

    private fun SessionEvent.near(outlierElapsedMillis: Long): Boolean =
        kotlin.math.abs(elapsedMillis - outlierElapsedMillis) <= CORRELATION_WINDOW_MILLIS

    /**
     * The detail strings the recorder writes.
     *
     * Constants rather than free text because the engine matches on them: a typo in one call site
     * would silently stop a lane from being counted, which is the kind of bug that reads as "the
     * network was fine".
     */
    const val LANE_UPSTREAM = "upstream"
    const val LANE_LOCAL = "gateway"
}
