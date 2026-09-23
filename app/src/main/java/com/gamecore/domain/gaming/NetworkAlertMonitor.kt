package com.gamecore.domain.gaming

import com.gamecore.core.common.Observed
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.LatencyProbe
import com.gamecore.core.model.NetworkTransport
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.domain.network.AlertGate
import com.gamecore.domain.network.NetworkQualityWindow
import com.gamecore.domain.network.NetworkSample
import com.gamecore.domain.network.alertReason
import com.gamecore.domain.network.poorForAlert
import com.gamecore.domain.network.shouldAlert
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs Part C (the network check) for the length of one game session — the sibling of [SmartFeatureRunner],
 * and the thin seam between the tested [com.gamecore.domain.network] logic and the two things it drives: an
 * in-session poor-connection alert (§C5) and the transport the session summary records (§C7).
 *
 * It does no arithmetic of its own. Each probe outcome is folded into a [NetworkQualityWindow] and the
 * pure [poorForAlert] / [shouldAlert] decide whether an alert is due; [alertReason] writes its text. The
 * clock comes in as a parameter, and there is no `android.*` here, so the whole thing is reachable from a
 * plain JVM test — the same shape the decision engine already follows.
 *
 * Owned by [GamingCoordinator], which calls [onSessionStart] once, [onTick] on each sample (for the
 * transport), [onProbe] on each probe (for the alert), and [onSessionEnd] when the game stops. It holds no
 * connection and registers no callback: the transport is read from the snapshot the monitor already took,
 * and the probes are the same ones the session's latency log is built from, so nothing new is opened,
 * leaked, or needs unregistering. Delivery of an alert — posting the notification — is the service's job;
 * this only says one is due.
 */
@Singleton
class NetworkAlertMonitor @Inject constructor() {

    private var checkEnabled = false
    private var alertsEnabled = false
    private var window = NetworkQualityWindow.EMPTY
    private var gate = AlertGate()
    private var lastTransport: NetworkTransport? = null

    // replay = 0 so a collector only hears alerts raised after it subscribes; the detection service
    // subscribes for the whole service lifetime, before any game starts, so none is missed. The buffer
    // lets a burst emit without suspending the sampling loop.
    private val alertEvents = MutableSharedFlow<NetworkAlert>(replay = 0, extraBufferCapacity = 8)

    /** Poor-connection alerts (§C5), one at most per minute. Collected by the detection service. */
    val alerts: SharedFlow<NetworkAlert> = alertEvents.asSharedFlow()

    /**
     * Starts the check for a profile that opted in. The alert half also needs [GameProfile.networkAlertsEnabled];
     * transport is recorded whenever the check is on, whether or not the alert is. A profile with the check
     * off leaves both dormant and this monitor writes and emits nothing.
     */
    fun onSessionStart(profile: GameProfile) {
        reset()
        checkEnabled = profile.networkCheckEnabled
        alertsEnabled = profile.networkCheckEnabled && profile.networkAlertsEnabled
    }

    /**
     * One monitoring tick. Latches the transport carrying the session for the §C7 summary — the last
     * connected transport wins, so a session that starts on cellular and settles onto Wi-Fi records Wi-Fi.
     * A tick with no connection is ignored rather than recorded as [NetworkTransport.NONE], so a brief drop
     * does not erase the transport the session actually ran on.
     */
    fun onTick(snapshot: PerformanceSnapshot) {
        if (!checkEnabled) return
        if (snapshot.network.isConnected) lastTransport = snapshot.network.transport
    }

    /**
     * Folds one probe outcome into the window and raises an alert when it turns poor, at most once a minute.
     *
     * A failed or never-sent probe ([Observed] that is not a value) enters the window as a null latency —
     * counted as loss, never as a slow success — which is the distinction the whole check is built on. Does
     * nothing unless the alert is enabled, so a check configured for pre-launch only never buzzes mid-game.
     */
    fun onProbe(probe: Observed<LatencyProbe>, nowMillis: Long) {
        if (!alertsEnabled) return
        window = window.record(NetworkSample(latencyMillis = probe.valueOrNull?.millis, nowMillis = nowMillis))
        val (nextGate, due) = shouldAlert(gate, nowMillis, isPoor = poorForAlert(window))
        gate = nextGate
        if (due) alertEvents.tryEmit(NetworkAlert(reason = alertReason(window)))
    }

    /** Session end. Returns the summary the recorder stores, then clears state for the next session. */
    fun onSessionEnd(): NetworkSessionSummary {
        val summary = NetworkSessionSummary(transport = if (checkEnabled) lastTransport else null)
        reset()
        return summary
    }

    private fun reset() {
        checkEnabled = false
        alertsEnabled = false
        window = NetworkQualityWindow.EMPTY
        gate = AlertGate()
        lastTransport = null
    }
}

/** One poor-connection alert (§C5). The reason is already user-facing text; the service adds the title. */
data class NetworkAlert(val reason: String)

/**
 * The network figure a finished session stores (§C7). Just the transport — the session's latency already
 * lives in its own columns, and there is deliberately no jitter or loss figure beside it (see
 * [com.gamecore.core.model.GameSession.transport]). Null means the network check was off for this session.
 */
data class NetworkSessionSummary(val transport: NetworkTransport? = null)
