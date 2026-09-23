package com.gamecore.domain.monitoring

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.model.BatteryReading
import com.gamecore.core.model.CpuReading
import com.gamecore.core.model.DisplayReading
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.LatencyProbe
import com.gamecore.core.model.MemoryReading
import com.gamecore.core.model.NetworkReading
import com.gamecore.core.model.NetworkTransport
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.model.StorageReading
import com.gamecore.core.model.ThermalReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §C composite `NETWORK` stat: transport, Wi-Fi band, signal and latency joined into one pill field,
 * with every absent piece dropped and no piece invented.
 *
 * This is the seam where [com.gamecore.domain.network.compactNetworkLine] meets the live snapshot, so it
 * is worth pinning here rather than only in the pure test: a connected network with no probe yet must
 * still read (its latency piece simply omitted), and only a genuinely absent connection reads unavailable.
 */
class HudStatReaderNetworkTest {

    @Test
    fun `a full wifi reading is the spec composite line`() {
        val reading = HudStatReader.read(
            HudStat.NETWORK,
            snapshot(
                transport = NetworkTransport.WIFI,
                rssiDbm = -58,
                freqMhz = 5200,
                latencyMillis = 32,
            ),
        )
        assertEquals("Wi-Fi 5 GHz · -58 dBm · 32 ms", reading.value)
        assertTrue(reading.isAvailable)
    }

    @Test
    fun `a connected network with no probe yet omits the latency piece rather than reading unavailable`() {
        val reading = HudStatReader.read(
            HudStat.NETWORK,
            snapshot(transport = NetworkTransport.WIFI, rssiDbm = -60, freqMhz = 2437, latencyMillis = null),
        )
        assertEquals("Wi-Fi 2.4 GHz · -60 dBm", reading.value)
        assertTrue(reading.isAvailable)
    }

    @Test
    fun `cellular with no band or signal is just the transport`() {
        val reading = HudStatReader.read(
            HudStat.NETWORK,
            snapshot(transport = NetworkTransport.CELLULAR, rssiDbm = null, freqMhz = null, latencyMillis = null),
        )
        assertEquals("Mobile data", reading.value)
    }

    @Test
    fun `no connection reads unavailable with a reason, not a blank line`() {
        val reading = HudStatReader.read(HudStat.NETWORK, snapshot(transport = NetworkTransport.NONE))
        assertNull(reading.value)
        assertNotNull(reading.reason)
        assertFalse(reading.isAvailable)
    }

    private fun snapshot(
        transport: NetworkTransport,
        rssiDbm: Int? = null,
        freqMhz: Int? = null,
        latencyMillis: Int? = null,
    ): PerformanceSnapshot = PerformanceSnapshot(
        capturedAtMillis = 0L,
        cpu = CpuReading.unavailable(coreCount = 0, absence = Observed.notPresent("test")),
        memory = MemoryReading.EMPTY,
        battery = BatteryReading.EMPTY,
        thermal = ThermalReading.unavailable("test", statusSupported = false),
        storage = StorageReading.EMPTY,
        display = DisplayReading.unavailable("test"),
        network = NetworkReading.DISCONNECTED.copy(
            transport = transport,
            isConnected = transport != NetworkTransport.NONE,
            signalStrengthDbm = rssiDbm?.let { Observed.of(it, DataSource.CONNECTIVITY) }
                ?: Observed.notPresent("test"),
            wifiFrequencyMhz = freqMhz?.let { Observed.of(it, DataSource.CONNECTIVITY) }
                ?: Observed.notPresent("test"),
        ),
        frameRate = Observed.notPresent("test"),
        latency = latencyMillis?.let { Observed.of(LatencyProbe(millis = it, host = "1.1.1.1"), DataSource.TRAFFIC_STATS) }
            ?: Observed.notPresent("test"),
        accessLevel = AccessLevel.NORMAL,
    )
}
