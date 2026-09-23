package com.gamecore.domain.network

import com.gamecore.core.model.ConnectionStability
import com.gamecore.core.model.NetworkTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The network-quality check (spec §C) proved as pure arithmetic: the probe window's caps and figures,
 * every rating boundary at its exact value, the Wi-Fi band edges, the pill lines with pieces missing, the
 * one-per-minute alert gate on a fake clock, and each pre-launch branch. No `ConnectivityManager`, no
 * socket, no `Thread.sleep` — every timestamp is a plain advanced-by-hand `Long`.
 */
class NetworkQualityMachineTest {

    private val t0 = 100_000L

    private fun window(vararg latencies: Int?): NetworkQualityWindow {
        var w = NetworkQualityWindow.EMPTY
        var now = t0
        for (l in latencies) {
            w = w.record(NetworkSample(l, now))
            now += 1_000L
        }
        return w
    }

    // ---------------------------------------------------------------- ring buffer

    @Test
    fun `window caps at thirty samples dropping the oldest`() {
        var w = NetworkQualityWindow.EMPTY
        for (i in 1..40) w = w.record(NetworkSample(i, t0 + i))
        assertEquals(30, w.size)
        // The last recorded probe (40) survives; the earliest ten are gone.
        assertEquals(40, w.lastLatencyMillis)
    }

    @Test
    fun `an empty window reads null figures and zero loss`() {
        val w = NetworkQualityWindow.EMPTY
        assertNull(w.averageLatencyMillis)
        assertNull(w.lastLatencyMillis)
        assertNull(w.jitterMillis)
        assertEquals(0, w.lossPercent)
        assertEquals(0, w.completed)
        assertEquals(0, w.failed)
    }

    @Test
    fun `average is over successful probes only`() {
        val w = window(20, null, 40) // 30 average of 20 and 40, the failure ignored
        assertEquals(30, w.averageLatencyMillis)
        assertEquals(2, w.completed)
        assertEquals(1, w.failed)
    }

    @Test
    fun `last latency is null when the most recent probe failed`() {
        assertNull(window(20, 40, null).lastLatencyMillis)
        assertEquals(40, window(20, null, 40).lastLatencyMillis)
    }

    // ---------------------------------------------------------------- jitter

    @Test
    fun `jitter is the mean gap between consecutive successful probes`() {
        // gaps 10, 10 -> mean 10
        assertEquals(10, window(20, 30, 40).jitterMillis)
    }

    @Test
    fun `jitter needs two successful probes`() {
        assertNull(window(20).jitterMillis)
        assertNull(window(20, null).jitterMillis)
    }

    @Test
    fun `a failure between two successes does not widen jitter`() {
        // successes 20, 40 -> single gap 20, the null is not a probe in the chain
        assertEquals(20, window(20, null, 40).jitterMillis)
    }

    // ---------------------------------------------------------------- loss

    @Test
    fun `loss is failed over total as a truncated percent`() {
        assertEquals(50, window(20, null).lossPercent)
        assertEquals(25, window(20, 30, 40, null).lossPercent)
        assertEquals(0, window(20, 30).lossPercent)
    }

    // ---------------------------------------------------------------- latency rating

    @Test
    fun `latency rating boundaries at fifty and one hundred`() {
        assertEquals(QualityRating.UNAVAILABLE, LatencyRating.rate(null))
        assertEquals(QualityRating.GOOD, LatencyRating.rate(49))
        assertEquals(QualityRating.FAIR, LatencyRating.rate(50))
        assertEquals(QualityRating.FAIR, LatencyRating.rate(100))
        assertEquals(QualityRating.POOR, LatencyRating.rate(101))
    }

    @Test
    fun `latency thresholds are overridable`() {
        // Tighter thresholds move the same value into a worse band.
        assertEquals(QualityRating.POOR, LatencyRating.rate(60, goodBelow = 20, fairBelow = 40))
    }

    // ---------------------------------------------------------------- jitter rating

    @Test
    fun `jitter rating boundaries at fifteen and thirty`() {
        assertEquals(QualityRating.UNAVAILABLE, JitterRating.rate(null))
        assertEquals(QualityRating.GOOD, JitterRating.rate(15))
        assertEquals(QualityRating.FAIR, JitterRating.rate(16))
        assertEquals(QualityRating.FAIR, JitterRating.rate(30))
        assertEquals(QualityRating.POOR, JitterRating.rate(31))
    }

    // ---------------------------------------------------------------- loss rating

    @Test
    fun `loss rating flags anything over two percent as poor`() {
        assertEquals(QualityRating.GOOD, LossRating.rate(0))
        assertEquals(QualityRating.FAIR, LossRating.rate(1))
        assertEquals(QualityRating.FAIR, LossRating.rate(2))
        assertEquals(QualityRating.POOR, LossRating.rate(3))
    }

    // ---------------------------------------------------------------- rssi rating

    @Test
    fun `rssi rating buckets at minus fifty five and minus seventy`() {
        assertEquals(QualityRating.UNAVAILABLE, RssiRating.rate(null))
        assertEquals(QualityRating.GOOD, RssiRating.rate(-55))
        assertEquals(QualityRating.FAIR, RssiRating.rate(-56))
        assertEquals(QualityRating.FAIR, RssiRating.rate(-70))
        assertEquals(QualityRating.POOR, RssiRating.rate(-71))
    }

    // ---------------------------------------------------------------- wifi band boundaries

    @Test
    fun `wifi band maps each range and its boundaries`() {
        assertNull(wifiBand(null))
        assertNull(wifiBand(2399))
        assertEquals(WifiBand.BAND_2_4, wifiBand(2400))
        assertEquals(WifiBand.BAND_2_4, wifiBand(2500))
        assertNull(wifiBand(2501))
        assertEquals(WifiBand.BAND_5, wifiBand(4900))
        assertEquals(WifiBand.BAND_5, wifiBand(5900))
        assertNull(wifiBand(5901)) // the documented gap between 5 and 6 GHz
        assertEquals(WifiBand.BAND_6, wifiBand(5925))
        assertEquals(WifiBand.BAND_6, wifiBand(7125))
        assertNull(wifiBand(7126))
    }

    // ---------------------------------------------------------------- compact line

    @Test
    fun `compact line is the spec example with all pieces`() {
        assertEquals(
            "Wi-Fi 5 GHz · -58 dBm · 32 ms",
            compactNetworkLine(NetworkTransport.WIFI, WifiBand.BAND_5, -58, 32),
        )
    }

    @Test
    fun `compact line omits every missing piece and never invents one`() {
        // No band, no rssi, no latency -> just the transport label.
        assertEquals("Mobile data", compactNetworkLine(NetworkTransport.CELLULAR, null, null, null))
        // Band is only shown for Wi-Fi even if one is passed for another transport.
        assertEquals("Ethernet · 12 ms", compactNetworkLine(NetworkTransport.ETHERNET, WifiBand.BAND_5, null, 12))
        // Wi-Fi with no band drops the band but keeps the rest.
        assertEquals("Wi-Fi · -60 dBm", compactNetworkLine(NetworkTransport.WIFI, null, -60, null))
    }

    @Test
    fun `detailed line adds link speed jitter and loss when present`() {
        assertEquals(
            "Wi-Fi 5 GHz · -58 dBm · 32 ms · 433 Mbps · 8 ms jitter · 0% loss",
            detailedNetworkLine(NetworkTransport.WIFI, WifiBand.BAND_5, -58, 32, 433, 8, 0),
        )
        // Absent extras are omitted just like the compact line.
        assertEquals(
            "Wi-Fi 5 GHz · -58 dBm · 32 ms",
            detailedNetworkLine(NetworkTransport.WIFI, WifiBand.BAND_5, -58, 32, null, null, null),
        )
    }

    // ---------------------------------------------------------------- alert gate

    @Test
    fun `a good reading never alerts and leaves the gate untouched`() {
        val (gate, alert) = shouldAlert(AlertGate(), t0, isPoor = false)
        assertFalse(alert)
        assertNull(gate.lastAlertMillis)
    }

    @Test
    fun `the first poor reading alerts and records the time`() {
        val (gate, alert) = shouldAlert(AlertGate(), t0, isPoor = true)
        assertTrue(alert)
        assertEquals(t0, gate.lastAlertMillis)
    }

    @Test
    fun `a second poor reading inside the minute is suppressed`() {
        val (gate, _) = shouldAlert(AlertGate(), t0, isPoor = true)
        val (gate2, alert2) = shouldAlert(gate, t0 + 59_999L, isPoor = true)
        assertFalse(alert2)
        assertEquals(t0, gate2.lastAlertMillis) // unchanged
        // Exactly one minute later it fires again.
        val (gate3, alert3) = shouldAlert(gate2, t0 + 60_000L, isPoor = true)
        assertTrue(alert3)
        assertEquals(t0 + 60_000L, gate3.lastAlertMillis)
    }

    // ---------------------------------------------------------------- pre-launch verdict

    @Test
    fun `no connection allows launch without warning`() {
        val v = preLaunchVerdict(NetworkQualityWindow.EMPTY, timedOut = false, connected = false)
        assertEquals(PreLaunchResult.NO_CONNECTION, v)
        assertTrue(v.allowsLaunch)
        assertFalse(v.warns)
    }

    @Test
    fun `a timeout allows launch without warning`() {
        val v = preLaunchVerdict(window(20, 20), timedOut = true, connected = true)
        assertEquals(PreLaunchResult.TIMED_OUT, v)
        assertTrue(v.allowsLaunch)
        assertFalse(v.warns)
    }

    @Test
    fun `a connected window with no completed probes warns`() {
        val v = preLaunchVerdict(window(null, null), timedOut = false, connected = true)
        assertEquals(PreLaunchResult.POOR, v)
        assertTrue(v.warns)
    }

    @Test
    fun `poor latency warns but still allows launch`() {
        val v = preLaunchVerdict(window(300, 320), timedOut = false, connected = true)
        assertEquals(PreLaunchResult.POOR, v)
        assertTrue(v.allowsLaunch)
        assertTrue(v.warns)
    }

    @Test
    fun `a good window launches`() {
        val v = preLaunchVerdict(window(20, 25, 22), timedOut = false, connected = true)
        assertEquals(PreLaunchResult.OK, v)
        assertTrue(v.allowsLaunch)
        assertFalse(v.warns)
    }

    // ---------------------------------------------------------------- burst → window

    @Test
    fun `a burst becomes a window with the same figures it was reported with`() {
        val burst = ConnectionStability(samples = listOf(20, 60, 30), failedProbes = 1)
        val w = qualityWindowOf(burst)

        assertEquals(4, w.size)
        assertEquals(3, w.completed)
        assertEquals(1, w.failed)
        // Average and jitter are computed over completed probes only, so they match the burst exactly.
        assertEquals(burst.averageMillis, w.averageLatencyMillis)
        assertEquals(burst.jitterMillis, w.jitterMillis)
        // A failed probe is loss, never a slow success: one of four did not come back.
        assertEquals(25, w.lossPercent)
    }

    @Test
    fun `a burst where nothing came back is all loss and no latency`() {
        val w = qualityWindowOf(ConnectionStability(samples = emptyList(), failedProbes = 4))
        assertEquals(4, w.size)
        assertEquals(0, w.completed)
        assertEquals(100, w.lossPercent)
        assertNull(w.averageLatencyMillis)
        assertNull(w.jitterMillis)
    }

    // ---------------------------------------------------------------- pre-launch warning

    @Test
    fun `nothing measured does not warn`() {
        // Measurement off, timed out, or failed — none of those is a poor connection.
        assertNull(preLaunchWarning(stability = null, connected = true))
    }

    @Test
    fun `no connection does not warn`() {
        val burst = ConnectionStability(samples = emptyList(), failedProbes = 3)
        assertNull(preLaunchWarning(stability = burst, connected = false))
    }

    @Test
    fun `a good burst does not warn`() {
        val burst = ConnectionStability(samples = listOf(20, 22, 21), failedProbes = 0)
        assertNull(preLaunchWarning(stability = burst, connected = true))
    }

    @Test
    fun `a burst where nothing completed warns that the connection may be down`() {
        val warning = preLaunchWarning(
            stability = ConnectionStability(samples = emptyList(), failedProbes = 4),
            connected = true,
        )
        assertEquals("No recent probes are completing — the connection may be down.", warning?.reason)
    }

    @Test
    fun `high latency warns with the measured figure`() {
        val warning = preLaunchWarning(
            stability = ConnectionStability(samples = listOf(300, 300, 300), failedProbes = 0),
            connected = true,
        )
        assertEquals("Latency is high, around 300 ms.", warning?.reason)
    }

    @Test
    fun `high jitter warns even when the average latency is fine`() {
        // Average 50 ms rates Fair, so latency is not what trips this — the 60 ms swing between probes is.
        val warning = preLaunchWarning(
            stability = ConnectionStability(samples = listOf(20, 80, 20, 80), failedProbes = 0),
            connected = true,
        )
        assertEquals("The connection is unstable — about 60 ms of jitter.", warning?.reason)
    }

    @Test
    fun `probes failing above the loss threshold warn`() {
        // Nineteen quick probes and one that never came back: 5%, above the 2% Poor line, with latency
        // and jitter both Good.
        val warning = preLaunchWarning(
            stability = ConnectionStability(samples = List(19) { 20 }, failedProbes = 1),
            connected = true,
        )
        assertEquals("5% of recent probes did not complete.", warning?.reason)
    }

    @Test
    fun `a burst with no probes at all falls back to the generic reason`() {
        val warning = preLaunchWarning(stability = ConnectionStability.EMPTY, connected = true)
        assertEquals(PreLaunchResult.POOR.message, warning?.reason)
    }
}
