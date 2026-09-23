package com.gamecore.core.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.common.Precision
import com.gamecore.core.model.NetworkReading
import com.gamecore.core.model.NetworkTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The network, read passively.
 *
 * Nothing in this class sends a packet. Transport, metering and link estimates come from
 * `ConnectivityManager`; throughput is a delta of `TrafficStats`' own counters, which the
 * kernel maintains whether GameCore looks at them or not. That matters for a gaming
 * utility specifically: an app that generates its own traffic to measure throughput is
 * competing with the game for the radio it claims to be monitoring.
 *
 * Two honesty constraints are structural rather than cosmetic:
 *
 *  * **The link speeds are the carrier's or driver's estimate, not a measurement.** A
 *    Wi-Fi driver reporting 433 000 Kbps is describing a negotiated PHY rate; no game
 *    will see it. They are marked [Precision.ESTIMATED] here, at the source, so no
 *    downstream screen can present them as measured throughput.
 *  * **The throughput figures are device-wide.** `TrafficStats.getTotalRxBytes()` counts
 *    every process. Android exposes no per-app byte counter to an ordinary app, so the
 *    number is labelled for what it is rather than being attributed to the game.
 *
 * There is no packet-loss reading anywhere in this file. See
 * [com.gamecore.core.model.NetworkReading] for why: it cannot be measured without ICMP,
 * which needs a raw socket and therefore root. [LatencyProber] measures jitter instead,
 * which is real.
 */
@Singleton
class NetworkReader @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Previous counter reading, for the rate delta. Null until the second call. */
    private var previous: CounterSample? = null

    suspend fun read(): NetworkReading = withContext(io) {
        val manager = try {
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (error: Throwable) {
            null
        } ?: return@withContext NetworkReading.DISCONNECTED

        val capabilities = activeCapabilities(manager)
            ?: return@withContext NetworkReading.DISCONNECTED.also { previous = null }

        val transport = transportOf(capabilities)
        val counters = sampleCounters()

        val wifiInfo = if (transport == NetworkTransport.WIFI) wifiConnectionInfo() else null

        NetworkReading(
            transport = transport,
            isConnected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            isVpnActive = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            linkDownstreamKbps = kbps(capabilities.linkDownstreamBandwidthKbps),
            linkUpstreamKbps = kbps(capabilities.linkUpstreamBandwidthKbps),
            totalRxBytes = counters?.let { Observed.of(it.rxBytes, DataSource.TRAFFIC_STATS) }
                ?: unsupportedCounters(),
            totalTxBytes = counters?.let { Observed.of(it.txBytes, DataSource.TRAFFIC_STATS) }
                ?: unsupportedCounters(),
            rxRateBytesPerSecond = rate(counters) { it.rxBytes },
            txRateBytesPerSecond = rate(counters) { it.txBytes },
            signalStrengthDbm = signalStrength(transport, capabilities),
            wifiFrequencyMhz = wifiInfo?.frequencyMhz ?: Observed.notPresent("No Wi-Fi frequency reported"),
            wifiLinkSpeedMbps = wifiInfo?.linkSpeedMbps ?: Observed.notPresent("No Wi-Fi link speed reported"),
        ).also { if (counters != null) previous = counters }
    }

    /**
     * Discards the counter baseline. Called when sampling is paused or the interval
     * changes: the next delta would otherwise be divided by an interval it did not span.
     */
    fun resetSampling() {
        previous = null
    }

    // ---------------------------------------------------------------------- pieces

    private fun activeCapabilities(manager: ConnectivityManager): NetworkCapabilities? = try {
        val active = manager.activeNetwork ?: return null
        manager.getNetworkCapabilities(active)
    } catch (error: Throwable) {
        null
    }

    /**
     * VPN is tested first, deliberately.
     *
     * A VPN's capabilities also carry the underlying transport, so testing Wi-Fi first
     * would report Wi-Fi on a tunnelled connection and hide the fact that every packet is
     * taking a detour — which is exactly the thing a user debugging game latency needs to
     * know.
     */
    private fun transportOf(capabilities: NetworkCapabilities): NetworkTransport = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
            NetworkTransport.CELLULAR
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
            NetworkTransport.ETHERNET
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ->
            NetworkTransport.BLUETOOTH
        else -> NetworkTransport.OTHER
    }

    /**
     * A link-capacity estimate, if the driver reports one.
     *
     * Zero means "not reported" in this API, and [Precision.ESTIMATED] travels with the
     * value so a HUD cannot render it beside a measured rate as though they were the
     * same kind of fact.
     */
    private fun kbps(value: Int): Observed<Int> = if (value > 0) {
        Observed.of(value, DataSource.CONNECTIVITY, Precision.ESTIMATED)
    } else {
        Observed.notPresent("This connection does not report an estimated link speed")
    }

    private fun sampleCounters(): CounterSample? {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        // The documented "this device does not support traffic statistics" sentinel.
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
            return null
        }
        return CounterSample(rx, tx, SystemClock.elapsedRealtime())
    }

    /**
     * Bytes per second between two counter readings.
     *
     * `elapsedRealtime` rather than wall clock, because a clock adjustment mid-session
     * would otherwise produce a throughput figure of several gigabytes per second or a
     * negative one. A counter that went backwards means the kernel counter was reset —
     * that happens on some tethering transitions — and the baseline is dropped rather
     * than a negative rate being reported.
     */
    private inline fun rate(
        current: CounterSample?,
        select: (CounterSample) -> Long,
    ): Observed<Double> {
        if (current == null) {
            return Observed.notPresent("This device does not report traffic counters")
        }
        val last = previous
            ?: return Observed.awaitingSample("Waiting for a second traffic reading.")
        val elapsed = current.elapsedRealtime - last.elapsedRealtime
        if (elapsed < MIN_INTERVAL_MILLIS) {
            return Observed.awaitingSample("Waiting for a second traffic reading.")
        }
        val delta = select(current) - select(last)
        if (delta < 0L) {
            return Observed.awaitingSample("The traffic counters were reset.")
        }
        return Observed.of(
            delta * 1000.0 / elapsed,
            DataSource.TRAFFIC_STATS,
            Precision.SAMPLED,
        )
    }

    /**
     * Signal strength, where the transport has a meaningful one.
     *
     * Wi-Fi RSSI is available without a location permission through `WifiManager`
     * ([WifiManager.getConnectionInfo] is deprecated from API 31 but still returns RSSI,
     * and `NetworkCapabilities.getSignalStrength` from API 29 supersedes it). Cellular
     * strength is not: `TelephonyManager.getSignalStrength()` requires
     * READ_PHONE_STATE, a runtime permission GameCore does not declare and has no other
     * use for, so it is reported absent with that as the reason rather than the app
     * asking for phone access to decorate a HUD.
     */
    @Suppress("DEPRECATION")
    private fun signalStrength(
        transport: NetworkTransport,
        capabilities: NetworkCapabilities,
    ): Observed<Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val reported = try {
                capabilities.signalStrength
            } catch (error: Throwable) {
                Int.MIN_VALUE
            }
            if (reported in PLAUSIBLE_DBM) {
                return Observed.of(reported, DataSource.CONNECTIVITY)
            }
        }
        if (transport != NetworkTransport.WIFI) {
            return Observed.needsPermission(
                "Mobile signal strength needs phone-state access, which GameCore does not ask for",
            )
        }
        return try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val rssi = wifi?.connectionInfo?.rssi ?: Int.MIN_VALUE
            if (rssi in PLAUSIBLE_DBM) {
                Observed.of(rssi, DataSource.CONNECTIVITY)
            } else {
                Observed.notPresent("This device did not report a Wi-Fi signal level")
            }
        } catch (error: Throwable) {
            Observed.notPresent("This device did not report a Wi-Fi signal level")
        }
    }

    /**
     * The connected Wi-Fi's centre frequency and negotiated link speed (§C3).
     *
     * Both come from `WifiInfo` — `frequency` in MHz, `linkSpeed` in Mbps — and neither needs a new
     * permission: they describe the network GameCore's own process is already on, not a scan of others
     * (which is what `ACCESS_FINE_LOCATION` gates). `getConnectionInfo()` is deprecated from API 31 but
     * still returns both for the active connection, and GameCore never reads SSID or BSSID off it. A
     * sentinel or a throw becomes absent, reported with a reason rather than a zero.
     */
    @Suppress("DEPRECATION")
    private fun wifiConnectionInfo(): WifiInfoReading? = try {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val info = wifi?.connectionInfo
        val freq = info?.frequency ?: -1
        val speed = info?.linkSpeed ?: -1
        WifiInfoReading(
            frequencyMhz = if (freq in PLAUSIBLE_WIFI_MHZ) {
                Observed.of(freq, DataSource.CONNECTIVITY)
            } else {
                Observed.notPresent("This device did not report a Wi-Fi frequency")
            },
            linkSpeedMbps = if (speed > 0) {
                Observed.of(speed, DataSource.CONNECTIVITY, Precision.ESTIMATED)
            } else {
                Observed.notPresent("This device did not report a Wi-Fi link speed")
            },
        )
    } catch (error: Throwable) {
        null
    }

    private data class WifiInfoReading(
        val frequencyMhz: Observed<Int>,
        val linkSpeedMbps: Observed<Int>,
    )

    private fun unsupportedCounters(): Observed<Long> =
        Observed.notPresent("This device does not report traffic counters")

    private data class CounterSample(
        val rxBytes: Long,
        val txBytes: Long,
        val elapsedRealtime: Long,
    )

    private companion object {
        /** Below this the delta is dominated by the read itself. */
        const val MIN_INTERVAL_MILLIS = 250L

        /** -120 dBm to -10 dBm. Anything outside is a sentinel. */
        val PLAUSIBLE_DBM = -120..-10

        /**
         * Wi-Fi centre frequencies span 2.4 GHz through the 6 GHz band; anything outside is `WifiInfo`'s
         * "-1 / not connected" sentinel. Deliberately wider than [com.gamecore.domain.network.wifiBand]'s
         * own ranges, which do the band classification — this only rejects the sentinel.
         */
        val PLAUSIBLE_WIFI_MHZ = 2000..7300
    }
}
