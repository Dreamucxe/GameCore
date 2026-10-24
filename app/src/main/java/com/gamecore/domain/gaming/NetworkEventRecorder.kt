package com.gamecore.domain.gaming

import com.gamecore.core.common.Observed
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.LatencyProbe
import com.gamecore.core.model.SessionEventKind
import com.gamecore.core.system.GatewayResolver
import com.gamecore.core.system.LocalLinkProber
import com.gamecore.domain.diagnostics.NetworkAttribution
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the two network lanes into the session's event timeline.
 *
 * The timeline is what [NetworkAttribution] reads, so this class decides what the report can ever say.
 * Three rules follow from that:
 *
 *  - **Two lanes, not one.** An upstream probe alone can only say "somewhere beyond this device was
 *    slow"; paired with a probe to the router it can separate a local link from the wider path. The
 *    upstream lane is the existing [LatencyProber] stream the monitor already runs — the same probe
 *    the latency log is built from, not a second handshake — and this class adds only the router lane.
 *  - **A probe that did not complete is not a spike.** It is recorded with a null value and does not
 *    move the lane into a spiking state. A blocked port or a filtered resolver would otherwise make
 *    every stutter look network-caused, which is exactly the overclaim the feature exists to avoid.
 *  - **The event table is bounded.** A probe every two seconds for an hour is eighteen hundred rows
 *    that say almost the same thing. Probe readings are written at most every
 *    [PROBE_EVENT_INTERVAL_MILLIS] per lane, while spikes and recoveries are always written — those
 *    are the timestamps attribution needs.
 */
@Singleton
class NetworkEventRecorder @Inject constructor(
    private val localLink: LocalLinkProber,
    private val gateways: GatewayResolver,
    private val recorder: SessionRecorder,
) {

    private var upstreamSpiking = false
    private var localSpiking = false
    private var lastUpstreamProbeAt = Long.MIN_VALUE
    private var lastLocalProbeAt = Long.MIN_VALUE

    /** Clears the lane state for a new session. Called once when recording begins. */
    fun reset() {
        upstreamSpiking = false
        localSpiking = false
        lastUpstreamProbeAt = Long.MIN_VALUE
        lastLocalProbeAt = Long.MIN_VALUE
    }

    /**
     * One upstream probe outcome.
     *
     * [nowMillis] is the wall clock the event is stamped with, passed in so the caller's own clock is
     * the one the rest of the timeline uses and a probe cannot land in the future of the session it
     * belongs to.
     */
    suspend fun onUpstreamProbe(
        probe: Observed<LatencyProbe>,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        record(
            lane = NetworkAttribution.LANE_UPSTREAM,
            probe = probe,
            nowMillis = nowMillis,
            spiking = upstreamSpiking,
        ) { next -> upstreamSpiking = next }
    }

    /**
     * Probes the router until the caller's coroutine is cancelled.
     *
     * Started inside the session's sampling scope, so it ends when the game does and no probe outlives
     * the record it is writing into. Every cycle re-reads the gateway: a device that moves from Wi-Fi
     * to cellular mid-session has a different router, and continuing to probe the old one would fill
     * the local lane with failures that mean "the network changed", not "the link was bad".
     */
    suspend fun runLocalLane() {
        while (currentCoroutineContext().isActive) {
            delay(LOCAL_PROBE_INTERVAL_MILLIS)
            val gateway = gateways.ipv4() ?: continue
            val probe = localLink.probe(gateway)
            onLocalProbe(probe)
        }
    }

    /** One router probe outcome. Separate from [onUpstreamProbe] because its failures mean something else. */
    suspend fun onLocalProbe(
        probe: Observed<LatencyProbe>,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        record(
            lane = NetworkAttribution.LANE_LOCAL,
            probe = probe,
            nowMillis = nowMillis,
            spiking = localSpiking,
        ) { next -> localSpiking = next }
    }

    private suspend fun record(
        lane: String,
        probe: Observed<LatencyProbe>,
        nowMillis: Long,
        spiking: Boolean,
        setSpiking: (Boolean) -> Unit,
    ) {
        val millis = probe.valueOrNull?.millis
        val due = when (lane) {
            NetworkAttribution.LANE_LOCAL -> nowMillis - lastLocalProbeAt >= PROBE_EVENT_INTERVAL_MILLIS
            else -> nowMillis - lastUpstreamProbeAt >= PROBE_EVENT_INTERVAL_MILLIS
        }
        if (due) {
            when (lane) {
                NetworkAttribution.LANE_LOCAL -> lastLocalProbeAt = nowMillis
                else -> lastUpstreamProbeAt = nowMillis
            }
            recorder.recordEvent(
                kind = SessionEventKind.NETWORK_PROBE,
                detail = lane,
                value = millis?.toDouble(),
                nowMillis = nowMillis,
            )
        }

        // A missing reading never starts a spike. It is recorded above so the session can be told
        // apart from one with no probes at all, but calling an unanswered probe a latency spike would
        // attribute a blocked port to the network and then to the frame time.
        val nowSpiking = millis != null && millis >= SPIKE_THRESHOLD_MILLIS
        when {
            nowSpiking && !spiking -> recorder.recordEvent(
                kind = SessionEventKind.NETWORK_SPIKE,
                detail = lane,
                value = millis.toDouble(),
                nowMillis = nowMillis,
            )
            !nowSpiking && spiking -> recorder.recordEvent(
                kind = SessionEventKind.NETWORK_CLEAR,
                detail = lane,
                value = millis?.toDouble(),
                nowMillis = nowMillis,
            )
        }
        if (millis != null) setSpiking(nowSpiking)
    }

    companion object {
        /**
         * A round trip this long is a spike for both lanes.
         *
         * 150 ms is above the point where a shooter's input-to-server loop is visibly affected and
         * below the "bad but playable" range the app's quality scale calls poor, so a spike here is a
         * moment worth explaining rather than ordinary mobile latency. It is a threshold on a TCP
         * handshake, not a ping, and the card says so.
         */
        const val SPIKE_THRESHOLD_MILLIS = 150

        /**
         * How often the router is probed.
         *
         * Five seconds: frequent enough that a stutter's three-second correlation window has a probe
         * in it, sparse enough that the local lane is a measurement rather than a traffic pattern of
         * its own. The upstream lane runs at the monitor's existing two-second cadence.
         */
        const val LOCAL_PROBE_INTERVAL_MILLIS = 5_000L

        /**
         * The floor between recorded probe readings on one lane.
         *
         * Ten seconds keeps roughly three hundred and sixty rows per lane per hour while leaving the
         * spike and recovery timestamps — the ones attribution actually reads — complete.
         */
        const val PROBE_EVENT_INTERVAL_MILLIS = 10_000L
    }
}
