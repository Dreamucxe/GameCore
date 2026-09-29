package com.gamecore.ui.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.Observed
import com.gamecore.core.common.isAvailable
import com.gamecore.core.common.unavailabilityText
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.ConnectionStability
import com.gamecore.core.model.LatencyQuality
import com.gamecore.core.model.NetworkCapabilityRow
import com.gamecore.core.model.NetworkMetric
import com.gamecore.core.model.NetworkStabilitySnapshot
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.domain.network.NetworkStabilityController
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.Readout
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.readout
import com.gamecore.ui.components.readoutOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The network-stability screen (§21/§C): the live connection, the jitter window, and what this device
 * will let GameCore read.
 *
 * Like the other leaf screens in this app there is no save button — the one thing the user changes here,
 * whether the stability watch runs at all, writes straight through [setEnabled]. Everything else is a
 * reading, so the flow of work runs the other way: [NetworkStabilityController] composes the existing
 * network reader and monitor into a [NetworkStabilitySnapshot], and this class turns that snapshot into
 * the [Readout] strings the screen draws. Nothing here opens a socket or holds a reader; a stability
 * burst is the controller's to run, which is why a refresh is a call to it and not work done in here.
 *
 * §24A.2 lives in [stateOf] and the helpers under it: they are the whole of the coupling to the snapshot's
 * shape, and the whole of the place a figure that could not be measured becomes "—" with a reason rather
 * than a zero. The compiler enforces the second half — [Observed.readout] cannot reach its `format` lambda
 * for a restricted or failed reading — so there is no path by which an absent value renders as a number.
 */
@HiltViewModel
class NetworkStabilityViewModel @Inject constructor(
    private val controller: NetworkStabilityController,
    private val preferences: SecurePreferenceStore,
) : ViewModel() {

    /** True only while a refresh the user (or the screen's cadence) asked for is still running. */
    private val refreshing = MutableStateFlow(false)

    val state: StateFlow<NetworkStabilityState> = combine(
        controller.state,
        preferences.settings,
        refreshing,
    ) { snapshot, settings, isRefreshing ->
        stateOf(snapshot, settings, isRefreshing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS), NetworkStabilityState())

    init {
        refresh()
    }

    /**
     * Asks the controller for a fresh reading and, if latency is on and the connection is up, a fresh
     * stability window.
     *
     * Called once on construction and again on the manual button and the screen's low-cadence tick. The
     * spinner flag is raised around the call rather than read from the controller, so the screen can show
     * progress without the controller having to publish a measuring flow of its own; a burst that lands on
     * top of another is already shared one-deep downstream, so a tick over a manual press costs nothing.
     */
    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            try {
                controller.refresh()
            } finally {
                refreshing.value = false
            }
        }
    }

    /** Turns the stability watch on or off. The only write this screen makes; it takes effect as it is set. */
    fun setEnabled(enabled: Boolean) {
        preferences.updateSettings { it.copy(networkStabilityEnabled = enabled) }
    }

    // --------------------------------------------------------------- snapshot → presentation (§24A.2)

    /** The one function that reads the snapshot's shape. Everything above draws only the state object. */
    private fun stateOf(
        snapshot: NetworkStabilitySnapshot,
        settings: AppSettings,
        isRefreshing: Boolean,
    ): NetworkStabilityState = NetworkStabilityState(
        isLoaded = true,
        isEnabled = settings.networkStabilityEnabled,
        isLatencyMeasured = settings.measureLatency,
        isConnected = snapshot.isConnected,
        isRefreshing = isRefreshing,
        liveRows = liveRowsOf(snapshot),
        capabilities = snapshot.capabilities.map(::capabilityViewOf),
        stabilityWarning = snapshot.warning,
    )

    /**
     * The "Now" card's rows, drawn from the snapshot the controller already composed and formatted.
     *
     * The controller does the reading and the honesty: each live metric arrives as a pre-formatted
     * [NetworkCapabilityRow.status] that is either the figure or the reason it is absent, and the stability
     * window arrives as [NetworkStabilitySnapshot.stability]. This method only lays those out — it invents
     * no figure, and an absent status becomes "—" with its reason through [Observed.readout].
     */
    private fun liveRowsOf(snapshot: NetworkStabilitySnapshot): List<Readout> {
        if (!snapshot.isConnected) {
            return listOf(
                readoutOf(
                    label = "Connection",
                    value = snapshot.transport.label,
                    detail = "Nothing to measure while there is no network.",
                    tone = Tone.Muted,
                ),
            )
        }
        val byId = snapshot.capabilities.associateBy(NetworkCapabilityRow::id)
        return buildList {
            add(
                readoutOf(
                    label = "Connection",
                    value = byId[NetworkMetric.TRANSPORT]?.status?.valueOrNull ?: snapshot.transport.label,
                    detail = "Over a VPN".takeIf { snapshot.isVpnActive },
                    tone = Tone.Good,
                ),
            )
            addAll(stabilityRows(snapshot.stability, snapshot.host))
            liveRow(byId, NetworkMetric.SIGNAL_STRENGTH, "Signal", "As the transport reports it")?.let(::add)
            liveRow(byId, NetworkMetric.LINK_SPEED, "Estimated link speed", "The negotiated rate — capacity, not throughput")?.let(::add)
            liveRow(byId, NetworkMetric.DOWNLOAD_THROUGHPUT, "Download", "Device-wide: every app's traffic, not the game's")?.let(::add)
            liveRow(byId, NetworkMetric.UPLOAD_THROUGHPUT, "Upload", "Device-wide")?.let(::add)
            add(
                readoutOf(
                    label = "Metered",
                    value = if (snapshot.isMetered) "Yes" else "No",
                    detail = "The system reports this connection as metered".takeIf { snapshot.isMetered },
                    tone = if (snapshot.isMetered) Tone.Warning else Tone.Good,
                ),
            )
        }
    }

    /**
     * The ping, jitter and probe-count rows from the stability window.
     *
     * A real window is a [ConnectionStability] `Value`, and even then a figure inside it can be absent —
     * jitter needs two completed probes, and a window in which every probe failed has no round-trip time
     * at all — so those cases draw the absent sentinel with the exact reason rather than a zero. When
     * there is no window yet, or latency is switched off, or nothing is connected, the outer [Observed]
     * carries the reason and [Observed.readout] renders it in place of all three figures.
     */
    private fun stabilityRows(stability: Observed<ConnectionStability>, host: String?): List<Readout> {
        if (stability !is Observed.Value) {
            return STABILITY_LABELS.map { label -> stability.readout(label = label, format = { "" }) }
        }
        val connection = stability.value
        val average = connection.averageMillis
        val jitter = connection.jitterMillis
        val attempted = connection.completedProbes + connection.failedProbes
        return listOf(
            readoutOf(
                label = "Ping",
                value = if (average != null) Formatters.millis(average) else ABSENT,
                detail = if (average != null) {
                    "Median TCP handshake" + (host?.let { " to $it" } ?: "") + " over ${connection.completedProbes} probes"
                } else {
                    "No probe completed, so there is no round-trip time to report."
                },
                tone = if (average != null) latencyTone(average) else Tone.Muted,
            ),
            readoutOf(
                label = "Jitter",
                value = if (jitter != null) Formatters.millis(jitter) else ABSENT,
                detail = if (jitter != null) {
                    "Variation between consecutive probes — the honest substitute for packet loss"
                } else {
                    "Needs at least two completed probes to measure variation."
                },
                tone = when {
                    jitter == null -> Tone.Muted
                    connection.isUnstable -> Tone.Warning
                    else -> Tone.Good
                },
            ),
            readoutOf(
                label = "Probes completed",
                value = "${connection.completedProbes} of $attempted",
                detail = if (connection.failedProbes > 0) {
                    "${connection.failedProbes} did not complete — this is not a packet-loss measurement"
                } else {
                    "Every probe in the window completed"
                },
                tone = if (connection.failedProbes > 0) Tone.Warning else Tone.Good,
            ),
        )
    }

    /**
     * One live metric the controller pre-formatted, as a [Readout], or null when the snapshot did not
     * carry that row. The status is already the display string, so [Observed.readout] renders it verbatim
     * when present and as "—" with the reason when it is not.
     */
    private fun liveRow(
        byId: Map<NetworkMetric, NetworkCapabilityRow>,
        metric: NetworkMetric,
        label: String,
        detail: String,
    ): Readout? = byId[metric]?.status?.readout(label = label, detailOf = { detail }, format = { it })

    /** One capability list row: its label, whether it is available here, and the reason when it is not. */
    private fun capabilityViewOf(row: NetworkCapabilityRow): NetworkCapabilityView = NetworkCapabilityView(
        title = row.label,
        isAvailable = row.status.isAvailable,
        reason = row.status.unavailabilityText(),
    )

    /** Green for a latency a game would call playable, orange once it is not, neutral in between. */
    private fun latencyTone(millis: Int): Tone = when (LatencyQuality.forMillis(millis)) {
        LatencyQuality.EXCELLENT, LatencyQuality.GOOD -> Tone.Good
        LatencyQuality.FAIR -> Tone.Neutral
        LatencyQuality.POOR -> Tone.Warning
    }

    private companion object {
        /** Long enough to survive a rotation, short enough to stop collecting when the screen is left. */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L

        /** The stability figures' labels, in one place so the placeholder rows match the real ones. */
        val STABILITY_LABELS = listOf("Ping", "Jitter", "Probes completed")
    }
}
