package com.gamecore.domain.monitoring

import com.gamecore.core.common.ApplicationScope
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.common.Precision
import com.gamecore.core.model.ConnectionStability
import com.gamecore.core.system.LatencyProber
import com.gamecore.data.preferences.SecurePreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A jitter measurement, with what it was measured against.
 *
 * [host] and [measuredAtMillis] are in here because a stability figure with no provenance invites the
 * reader to assume it is current and universal. It is neither: it describes the path to one host at
 * one moment, and the network screen says so underneath the number.
 */
data class StabilityReport(
    val stability: ConnectionStability,
    val host: String,
    val measuredAtMillis: Long,
) {
    val isUnstable: Boolean get() = stability.isUnstable

    /** The sentence to show when something is wrong, from [ConnectionStability.warning]. */
    val warning: String? get() = stability.warning

    fun ageMillis(nowMillis: Long): Long = (nowMillis - measuredAtMillis).coerceAtLeast(0L)
}

/**
 * Connection stability, measured in deliberate bursts rather than continuously.
 *
 * §21 asks for an instability warning, and the honest way to produce one is a window of probes: jitter
 * between consecutive round trips is computable from connections the app can really make, whereas
 * packet loss needs ICMP and therefore root. [ConnectionStability] carries that distinction in its own
 * documentation and this class does not blur it.
 *
 * Separate from [PerformanceMonitor] because the cadence is different in kind. The monitor's per-tick
 * latency field is one handshake every ten seconds; a stability window is five handshakes 200 ms apart
 * and is a small burst of traffic, so it runs when a screen asks for it or when a session starts —
 * never on a timer of its own.
 *
 * Concurrent callers share one burst. Two screens both asking on open would otherwise produce ten
 * handshakes and two answers that disagree, which is a worse measurement than either alone.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    private val latencyProber: LatencyProber,
    private val preferences: SecurePreferenceStore,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val reportState = MutableStateFlow<Observed<StabilityReport>>(
        Observed.awaitingSample("No stability measurement has been taken yet."),
    )
    private val measuringState = MutableStateFlow(false)

    private val mutex = Mutex()

    @Volatile
    private var inFlight: Deferred<Observed<StabilityReport>>? = null

    /** The last measurement, or the reason there has not been one. */
    val report: StateFlow<Observed<StabilityReport>> = reportState.asStateFlow()

    /** For the button's spinner. A burst takes about a second. */
    val isMeasuring: StateFlow<Boolean> = measuringState.asStateFlow()

    /**
     * Runs a burst, or joins the one already running.
     *
     * [connected] is passed in rather than read here on purpose. `NetworkReader` derives throughput
     * from the delta between two counter readings, so an extra read from this class would shorten the
     * interval the next sampled rate covers — the same hazard [PerformanceMonitor.sampleOnce]
     * documents for the CPU sampler. Every caller already holds a snapshot with the answer in it.
     */
    suspend fun measure(
        connected: Boolean,
        probeCount: Int = LatencyProber.DEFAULT_PROBE_COUNT,
    ): Observed<StabilityReport> {
        val settings = preferences.settings.value
        if (!settings.measureLatency) {
            return Observed
                .samplingDisabled("Latency measurement is switched off in Settings.")
                .also { reportState.value = it }
        }
        if (!connected) {
            return Observed
                .notPresent("No network connection to measure.")
                .also { reportState.value = it }
        }

        val job = mutex.withLock {
            inFlight ?: scope
                .async { burst(settings.latencyHost, probeCount) }
                .also { started ->
                    inFlight = started
                    started.invokeOnCompletion { inFlight = null }
                }
        }
        return job.await()
    }

    /**
     * Measures only if the last answer is older than [maxAgeMillis].
     *
     * What a screen calls on open. Navigating away and back within a few seconds reuses the reading
     * rather than sending five more handshakes to somebody else's resolver.
     */
    suspend fun measureIfStale(
        connected: Boolean,
        maxAgeMillis: Long = DEFAULT_STALE_AFTER_MILLIS,
        nowMillis: Long = System.currentTimeMillis(),
    ): Observed<StabilityReport> {
        val current = reportState.value
        if (current is Observed.Value && current.value.ageMillis(nowMillis) < maxAgeMillis) {
            return current
        }
        return measure(connected)
    }

    private suspend fun burst(host: String, probeCount: Int): Observed<StabilityReport> {
        measuringState.value = true
        return try {
            val stability = latencyProber.measureStability(host = host, probeCount = probeCount)
            val report = StabilityReport(
                stability = stability,
                host = host,
                measuredAtMillis = System.currentTimeMillis(),
            )
            // A window in which every probe failed is still a real result — "nothing answered" is
            // what the user needs told — so it is a Value carrying a ConnectionStability that says
            // so, not a Failed hiding the detail behind an error string.
            Observed.of(report, DataSource.SOCKET_PROBE, Precision.SAMPLED)
                .also { reportState.value = it }
        } finally {
            measuringState.value = false
        }
    }

    companion object {
        /** Half a minute. Long enough to cover navigation, short enough to still describe now. */
        const val DEFAULT_STALE_AFTER_MILLIS = 30_000L
    }
}
