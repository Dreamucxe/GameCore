package com.gamecore.domain.network

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.common.Precision
import com.gamecore.core.common.RestrictionReason
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.ConnectionStability
import com.gamecore.core.model.NetworkMetric
import com.gamecore.core.model.NetworkReading
import com.gamecore.core.model.NetworkStabilitySnapshot
import com.gamecore.core.model.NetworkTransport
import com.gamecore.domain.monitoring.StabilityReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controller's own behaviour — the honesty gates, the row composition and the alert gating —
 * verified against three plain fakes standing in for the passive reader, the on-demand burst and the
 * settings store. No `ConnectivityManager`, no socket and no Hilt: the interface seams the controller
 * takes in its `internal` constructor are exactly what make the logic reachable from a JVM test, the
 * same way [com.gamecore.domain.config.ConfigEditorControllerTest] fakes an elevated shell. What is
 * proven here is the discipline, not the platform — switched off measures nothing and says why, a
 * disconnected read never fabricates a figure, a good Wi-Fi burst folds into the summary line and the
 * live rows exactly as read, and the four metrics GameCore cannot measure stay present with their
 * reasons in every snapshot.
 */
class NetworkStabilityControllerTest {

    private val now = 1_000_000L

    private fun build(
        settings: AppSettings,
        reading: NetworkReading = NetworkReading.DISCONNECTED,
        burst: Observed<StabilityReport> = Observed.awaitingSample("No burst yet."),
    ): Triple<NetworkStabilityController, FakeReadings, FakeStability> {
        val readings = FakeReadings(reading)
        val stability = FakeStability(burst)
        val store = FakeSettings(settings)
        val controller = NetworkStabilityController(
            readings = readings,
            stability = stability,
            settings = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            clock = { now },
        )
        return Triple(controller, readings, stability)
    }

    @Test
    fun `switched off measures nothing and every live row says why`() = runBlocking {
        val (controller, readings, stability) = build(AppSettings(networkStabilityEnabled = false))

        controller.refresh()
        val snapshot = controller.state.value

        assertFalse(snapshot.enabled)
        assertFalse(snapshot.isConnected)
        assertNull(snapshot.summaryLine)
        assertNull(snapshot.alert)
        assertEquals("nothing is measured while the module is off", 0, stability.measureCalls)
        // The throughput baseline is dropped so a later re-enable does not divide by a stale interval.
        assertTrue(readings.resetCount >= 1)

        // Every live metric reports the user's own choice, not a device limit or a fabricated zero.
        val liveRows = snapshot.capabilities.filter { it.id in NetworkStabilitySnapshot.LIVE_METRICS }
        assertEquals(NetworkStabilitySnapshot.LIVE_METRICS.size, liveRows.size)
        liveRows.forEach { row ->
            val status = row.status
            assertTrue("${row.id} should be restricted while off", status is Observed.Restricted)
            assertEquals(RestrictionReason.SAMPLING_DISABLED, (status as Observed.Restricted).reason)
        }
    }

    @Test
    fun `the four unmeasurable metrics are always present with a real reason`() = runBlocking {
        // On the constant list itself: the honest counterpart to the live rows, in order.
        val unsupported = NetworkStabilitySnapshot.ALWAYS_UNSUPPORTED
        assertEquals(
            listOf(
                NetworkMetric.PACKET_LOSS,
                NetworkMetric.PER_APP_THROUGHPUT,
                NetworkMetric.MOBILE_SIGNAL,
                NetworkMetric.ICMP_PING,
            ),
            unsupported.map { it.id },
        )
        unsupported.forEach { row ->
            val status = row.status
            assertTrue("${row.id} is never a value", status is Observed.Restricted)
            assertTrue(
                "${row.id} carries a full-sentence reason",
                (status as Observed.Restricted).detail.length > 40,
            )
        }

        // And carried into a live snapshot, still restricted rather than dropped or fabricated.
        val (controller, _, _) = build(
            settings = AppSettings(networkStabilityEnabled = true),
            reading = wifiReading(),
            burst = burstOf(ConnectionStability(samples = listOf(20, 24, 22, 26), failedProbes = 0)),
        )
        controller.refresh()
        val capabilities = controller.state.value.capabilities
        unsupported.map { it.id }.forEach { metric ->
            val row = capabilities.firstOrNull { it.id == metric }
            assertNotNull("$metric is present in a live snapshot", row)
            assertTrue("$metric stays restricted on a live connection", row!!.status is Observed.Restricted)
        }
    }

    @Test
    fun `a good wifi burst folds into the summary and the live rows`() = runBlocking {
        val (controller, _, stability) = build(
            settings = AppSettings(networkStabilityEnabled = true),
            reading = wifiReading(),
            burst = burstOf(ConnectionStability(samples = listOf(20, 24, 22, 26), failedProbes = 0)),
        )

        controller.refresh()
        val snapshot = controller.state.value

        assertEquals(1, stability.measureCalls)
        assertTrue(snapshot.enabled)
        assertTrue(snapshot.isConnected)
        assertEquals(NetworkTransport.WIFI, snapshot.transport)
        assertEquals("1.1.1.1", snapshot.host)
        assertEquals(now, snapshot.measuredAtMillis)

        // The burst is carried as the Value it was, all four probes intact — never flattened to a number.
        val stabilityObs = snapshot.stability
        assertTrue(stabilityObs is Observed.Value)
        assertEquals(4, (stabilityObs as Observed.Value).value.completedProbes)

        // Average of 20/24/22/26 is 23 ms; 5200 MHz is the 5 GHz band; RSSI is read straight through.
        val summary = snapshot.summaryLine
        assertNotNull(summary)
        assertTrue("summary was $summary", summary!!.startsWith("Wi-Fi 5 GHz"))
        assertTrue("summary was $summary", summary.contains("-58 dBm"))
        assertTrue("summary was $summary", summary.contains("23 ms"))

        // A good connection raises no alert.
        assertNull(snapshot.alert)

        val signal = snapshot.capabilities.first { it.id == NetworkMetric.SIGNAL_STRENGTH }.status
        assertEquals("-58 dBm", signal.valueOrNull)

        val latency = snapshot.capabilities.first { it.id == NetworkMetric.LATENCY }.status
        assertTrue(latency is Observed.Value)
        assertEquals("23 ms", latency.valueOrNull)

        // Jitter is the mean step between consecutive probes: |24-20|,|22-24|,|26-22| averaged is 3 ms.
        val jitter = snapshot.capabilities.first { it.id == NetworkMetric.JITTER }.status
        assertEquals("3 ms", jitter.valueOrNull)
    }

    @Test
    fun `a disconnected read reports not connected without a figure`() = runBlocking {
        val (controller, _, stability) = build(
            settings = AppSettings(networkStabilityEnabled = true),
            reading = NetworkReading.DISCONNECTED,
        )

        controller.refresh()
        val snapshot = controller.state.value

        assertEquals("a disconnected read never triggers a burst", 0, stability.measureCalls)
        assertTrue(snapshot.enabled)
        assertFalse(snapshot.isConnected)
        assertTrue("no burst to judge, so stability is absent with a reason", snapshot.stability is Observed.Restricted)
        snapshot.capabilities
            .filter { it.id in NetworkStabilitySnapshot.LIVE_METRICS }
            .forEach { assertFalse("${it.id} is never a value while disconnected", it.status is Observed.Value) }
    }

    // ---------------------------------------------------------------- fixtures

    private fun wifiReading(): NetworkReading = NetworkReading(
        transport = NetworkTransport.WIFI,
        isConnected = true,
        isMetered = false,
        isVpnActive = false,
        linkDownstreamKbps = Observed.of(120_000, DataSource.CONNECTIVITY, Precision.ESTIMATED),
        linkUpstreamKbps = Observed.of(20_000, DataSource.CONNECTIVITY, Precision.ESTIMATED),
        totalRxBytes = Observed.of(10_000_000L, DataSource.TRAFFIC_STATS),
        totalTxBytes = Observed.of(2_000_000L, DataSource.TRAFFIC_STATS),
        rxRateBytesPerSecond = Observed.of(1_200_000.0, DataSource.TRAFFIC_STATS, Precision.SAMPLED),
        txRateBytesPerSecond = Observed.of(64_000.0, DataSource.TRAFFIC_STATS, Precision.SAMPLED),
        signalStrengthDbm = Observed.of(-58, DataSource.CONNECTIVITY),
        wifiFrequencyMhz = Observed.of(5200, DataSource.CONNECTIVITY),
        wifiLinkSpeedMbps = Observed.of(433, DataSource.CONNECTIVITY, Precision.ESTIMATED),
    )

    private fun burstOf(stability: ConnectionStability): Observed<StabilityReport> = Observed.of(
        StabilityReport(stability = stability, host = "1.1.1.1", measuredAtMillis = now),
        DataSource.SOCKET_PROBE,
        Precision.SAMPLED,
    )
}

// -------------------------------------------------------------------- fakes

/** The passive reader, faked: returns whatever [reading] is set and counts baseline resets. */
private class FakeReadings(var reading: NetworkReading) : NetworkSnapshotSource {
    var resetCount = 0
    override suspend fun read(): NetworkReading = reading
    override fun resetSampling() {
        resetCount++
    }
}

/** The on-demand burst, faked: hands back a fixed [result] and counts how often it was asked. */
private class FakeStability(var result: Observed<StabilityReport>) : StabilityBurstSource {
    val measuringState = MutableStateFlow(false)
    override val isMeasuring: StateFlow<Boolean> = measuringState
    var measureCalls = 0
    override suspend fun measureIfStale(
        connected: Boolean,
        maxAgeMillis: Long,
        nowMillis: Long,
    ): Observed<StabilityReport> {
        measureCalls++
        return result
    }
}

/** The settings store, faked over a plain flow. */
private class FakeSettings(initial: AppSettings) : NetworkSettingsSource {
    val flow = MutableStateFlow(initial)
    override val settings: StateFlow<AppSettings> = flow
}
