package com.gamecore.domain.network

import com.gamecore.core.common.ApplicationScope
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.Observed
import com.gamecore.core.common.Precision
import com.gamecore.core.common.map
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.ConnectionStability
import com.gamecore.core.model.NetworkCapabilityRow
import com.gamecore.core.model.NetworkMetric
import com.gamecore.core.model.NetworkReading
import com.gamecore.core.model.NetworkStabilitySnapshot
import com.gamecore.core.model.NetworkTransport
import com.gamecore.core.system.NetworkReader
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.domain.monitoring.NetworkMonitor
import com.gamecore.domain.monitoring.StabilityReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The passive network reading the controller composes. Backed by [NetworkReader] in production; an
 * interface so the controller's own logic — the honest rows, the gating, the rating — is reachable from
 * a JVM test without a `ConnectivityManager`, the same seam [com.gamecore.core.shizuku.ElevatedShell]
 * gives the config editor.
 */
interface NetworkSnapshotSource {
    suspend fun read(): NetworkReading
    fun resetSampling()
}

/** The on-demand stability burst. Backed by [NetworkMonitor] in production. */
interface StabilityBurstSource {
    val isMeasuring: StateFlow<Boolean>
    suspend fun measureIfStale(
        connected: Boolean,
        maxAgeMillis: Long = NetworkMonitor.DEFAULT_STALE_AFTER_MILLIS,
        nowMillis: Long = System.currentTimeMillis(),
    ): Observed<StabilityReport>
}

/** The settings the module reads. Backed by [SecurePreferenceStore] in production. */
interface NetworkSettingsSource {
    val settings: StateFlow<AppSettings>
}

/**
 * The Network Stability screen's controller (spec §C).
 *
 * It measures nothing itself. It composes the app's existing passive [NetworkReader] and on-demand
 * [NetworkMonitor] burst, rates the result through the pure [com.gamecore.domain.network] logic
 * ([wifiBand], [qualityWindowOf], [poorForAlert], [shouldAlert], [alertReason], [compactNetworkLine]),
 * and folds it all into one immutable [NetworkStabilitySnapshot]. No new socket, no `ConnectivityManager`
 * call, and no fabricated figure lives here: a value the readers could not produce is carried down as the
 * [Observed] they returned, reason intact.
 *
 * Two honesty gates come first, before anything is read: the module can be switched off ([disabled]), and
 * latency measurement is a separate switch the burst itself honours. A poor-network alert is rate-limited
 * to one a minute by carrying the last-alert time forward in the snapshot rather than in a mutable field,
 * so the decision stays a pure function of state.
 */
@Singleton
class NetworkStabilityController internal constructor(
    private val readings: NetworkSnapshotSource,
    private val stability: StabilityBurstSource,
    private val settings: NetworkSettingsSource,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        networkMonitor: NetworkMonitor,
        networkReader: NetworkReader,
        preferences: SecurePreferenceStore,
        @ApplicationScope scope: CoroutineScope,
    ) : this(
        readings = ReaderSnapshotSource(networkReader),
        stability = MonitorBurstSource(networkMonitor),
        settings = StoreSettingsSource(preferences),
        scope = scope,
        clock = { System.currentTimeMillis() },
    )

    private val _state = MutableStateFlow(
        NetworkStabilitySnapshot.awaiting(settings.settings.value.networkStabilityEnabled),
    )

    /** The screen's whole state. Seeded honest; updated only by [refresh]. */
    val state: StateFlow<NetworkStabilitySnapshot> = _state.asStateFlow()

    init {
        // Keep the spinner honest between refreshes: the burst can start and finish on another caller.
        scope.launch {
            stability.isMeasuring.collect { measuring ->
                _state.update { it.copy(isMeasuring = measuring) }
            }
        }
    }

    /**
     * Re-reads the connection and, when it is on and connected, measures a stability burst if the last
     * one is stale. Off, or disconnected, produces a snapshot whose every metric says why there is no
     * number — never a zero. Safe to call on screen open and on a button; concurrent callers share one
     * burst inside [NetworkMonitor].
     */
    suspend fun refresh() {
        val appSettings = settings.settings.value
        if (!appSettings.networkStabilityEnabled) {
            // Drop the throughput baseline so a later re-enable does not divide by a stale interval.
            readings.resetSampling()
            _state.value = NetworkStabilitySnapshot.disabled()
            return
        }

        val reading = readings.read()
        if (!reading.isConnected) {
            _state.value = NetworkStabilitySnapshot.disconnected(reading.transport)
            return
        }

        val now = clock()
        val burst = stability.measureIfStale(connected = true, nowMillis = now)
        val report = (burst as? Observed.Value)?.value

        val band = wifiBand(reading.wifiFrequencyMhz.valueOrNull)
        val window = report?.let { qualityWindowOf(it.stability) }
        val previousGate = AlertGate(_state.value.lastAlertMillis)
        val poor = window != null && poorForAlert(window)
        val (gate, fired) = shouldAlert(previousGate, now, poor)

        _state.value = NetworkStabilitySnapshot(
            enabled = true,
            transport = reading.transport,
            isConnected = true,
            isMetered = reading.isMetered,
            isVpnActive = reading.isVpnActive,
            summaryLine = compactNetworkLine(
                transport = reading.transport,
                band = band,
                rssiDbm = reading.signalStrengthDbm.valueOrNull,
                latencyMillis = report?.stability?.averageMillis,
            ),
            stability = burst.map { it.stability },
            warning = report?.warning,
            alert = if (fired && window != null) alertReason(window) else null,
            isMeasuring = stability.isMeasuring.value,
            capabilities = liveRows(reading, burst) + NetworkStabilitySnapshot.ALWAYS_UNSUPPORTED,
            lastAlertMillis = gate.lastAlertMillis,
            host = report?.host,
            measuredAtMillis = report?.measuredAtMillis ?: now,
        )
    }

    // -------------------------------------------------------------------- row composition

    private fun liveRows(
        reading: NetworkReading,
        burst: Observed<StabilityReport>,
    ): List<NetworkCapabilityRow> {
        val band = wifiBand(reading.wifiFrequencyMhz.valueOrNull)
        val transportLabel = if (reading.transport == NetworkTransport.WIFI && band != null) {
            "${reading.transport.label} ${band.label}"
        } else {
            reading.transport.label
        }
        return listOf(
            NetworkCapabilityRow(
                NetworkMetric.TRANSPORT,
                Observed.of(transportLabel, DataSource.CONNECTIVITY),
            ),
            NetworkCapabilityRow(
                NetworkMetric.SIGNAL_STRENGTH,
                reading.signalStrengthDbm.map { "$it dBm" },
            ),
            NetworkCapabilityRow(
                NetworkMetric.LINK_SPEED,
                reading.wifiLinkSpeedMbps.map { "$it Mbps" },
            ),
            NetworkCapabilityRow(
                NetworkMetric.DOWNLOAD_THROUGHPUT,
                reading.rxRateBytesPerSecond.map { Formatters.rate(it) },
            ),
            NetworkCapabilityRow(
                NetworkMetric.UPLOAD_THROUGHPUT,
                reading.txRateBytesPerSecond.map { Formatters.rate(it) },
            ),
            NetworkCapabilityRow(NetworkMetric.LATENCY, latencyRow(burst)),
            NetworkCapabilityRow(NetworkMetric.JITTER, jitterRow(burst)),
        )
    }

    /**
     * The burst's average round trip, or the burst's own reason for absence. A completed burst in which
     * no probe came back has a null average — that is not a latency of zero, so it reports as absent with
     * its own sentence rather than inventing a figure.
     */
    private fun latencyRow(burst: Observed<StabilityReport>): Observed<String> = when (burst) {
        is Observed.Value -> burst.value.stability.averageMillis
            ?.let { Observed.of(Formatters.millis(it), DataSource.SOCKET_PROBE, Precision.SAMPLED) }
            ?: Observed.notPresent("No probe completed, so there is no round-trip time to report.")
        is Observed.Restricted -> burst
        is Observed.Failed -> burst
    }

    /** Jitter needs two completed probes; until then it honestly says it is still measuring. */
    private fun jitterRow(burst: Observed<StabilityReport>): Observed<String> = when (burst) {
        is Observed.Value -> burst.value.stability.jitterMillis
            ?.let { Observed.of(Formatters.millis(it), DataSource.SOCKET_PROBE, Precision.SAMPLED) }
            ?: Observed.awaitingSample("Jitter needs at least two completed probes.")
        is Observed.Restricted -> burst
        is Observed.Failed -> burst
    }
}

// ------------------------------------------------------------------------ production adapters

/** Wraps the real [NetworkReader] as a [NetworkSnapshotSource] so Hilt keeps wiring the singleton. */
private class ReaderSnapshotSource(private val reader: NetworkReader) : NetworkSnapshotSource {
    override suspend fun read(): NetworkReading = reader.read()
    override fun resetSampling() = reader.resetSampling()
}

/** Wraps the real [NetworkMonitor] as a [StabilityBurstSource]. */
private class MonitorBurstSource(private val monitor: NetworkMonitor) : StabilityBurstSource {
    override val isMeasuring: StateFlow<Boolean> get() = monitor.isMeasuring
    override suspend fun measureIfStale(
        connected: Boolean,
        maxAgeMillis: Long,
        nowMillis: Long,
    ): Observed<StabilityReport> = monitor.measureIfStale(connected, maxAgeMillis, nowMillis)
}

/** Wraps the real [SecurePreferenceStore] as a [NetworkSettingsSource]. */
private class StoreSettingsSource(private val store: SecurePreferenceStore) : NetworkSettingsSource {
    override val settings: StateFlow<AppSettings> get() = store.settings
}
