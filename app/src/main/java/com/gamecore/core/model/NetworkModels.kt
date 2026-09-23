package com.gamecore.core.model

import com.gamecore.core.common.Observed

/**
 * One network observation.
 *
 * Three separate kinds of fact live here and are deliberately not blended:
 *
 *  * [transport] and [isConnected] come from `ConnectivityManager` and are as
 *    reliable as anything on Android gets.
 *  * [linkDownstreamKbps] / [linkUpstreamKbps] are the *carrier's or driver's own
 *    estimate* of link capacity, not a measurement, and are marked
 *    `Precision.ESTIMATED` at the source. A Wi-Fi driver reporting 433 Mbps is
 *    describing the negotiated PHY rate, which is not throughput a game will see.
 *  * [rxRateBytesPerSecond] / [txRateBytesPerSecond] are real, measured deltas of
 *    `TrafficStats`, and are **device-wide** — every app's traffic, not the game's.
 *    The UI says so. Android gives no per-app byte counter to an ordinary app
 *    without usage access, and even then only in coarse buckets.
 *
 * There is no packet-loss field. Measuring loss requires sending a sequence of
 * probes and counting what does not come back, which needs ICMP — a raw socket, and
 * so root — or a cooperating server on the other end, which GameCore does not have
 * and will not add: the app has no backend of its own and is not going to grow one
 * to produce a single readout. (Since 3.4 there is no outbound traffic of any other
 * kind either — the optional latency probe is the only connection the app makes, and
 * a TCP handshake cannot echo anything back to count.) A "0% packet loss" readout
 * produced from a TCP connect probe would be an invention.
 */
data class NetworkReading(
    val transport: NetworkTransport,
    val isConnected: Boolean,
    val isMetered: Boolean,
    val isVpnActive: Boolean,
    /** The link's claimed capacity, which is not a measurement of throughput. */
    val linkDownstreamKbps: Observed<Int>,
    val linkUpstreamKbps: Observed<Int>,
    /** Device-wide, since boot. */
    val totalRxBytes: Observed<Long>,
    val totalTxBytes: Observed<Long>,
    /** Device-wide throughput, from two counter readings. Absent on the first tick. */
    val rxRateBytesPerSecond: Observed<Double>,
    val txRateBytesPerSecond: Observed<Double>,
    /** Signal strength where the transport exposes one. Wi-Fi RSSI in dBm. */
    val signalStrengthDbm: Observed<Int>,
    /**
     * The Wi-Fi centre frequency in MHz, from which §C derives the band (2.4 / 5 / 6 GHz). Absent on any
     * non-Wi-Fi transport and on a device that does not report it. Added in 3.5; a plain nullable so
     * every existing construction that omits it reads as "not reported".
     */
    val wifiFrequencyMhz: Observed<Int> = Observed.notPresent("No Wi-Fi frequency reported"),
    /** The Wi-Fi negotiated link speed in Mbps — an estimate, not throughput. Absent off Wi-Fi. */
    val wifiLinkSpeedMbps: Observed<Int> = Observed.notPresent("No Wi-Fi link speed reported"),
) {
    companion object {
        val DISCONNECTED = NetworkReading(
            transport = NetworkTransport.NONE,
            isConnected = false,
            isMetered = false,
            isVpnActive = false,
            linkDownstreamKbps = Observed.notPresent("Not connected"),
            linkUpstreamKbps = Observed.notPresent("Not connected"),
            totalRxBytes = Observed.notPresent("Not connected"),
            totalTxBytes = Observed.notPresent("Not connected"),
            rxRateBytesPerSecond = Observed.notPresent("Not connected"),
            txRateBytesPerSecond = Observed.notPresent("Not connected"),
            signalStrengthDbm = Observed.notPresent("Not connected"),
            wifiFrequencyMhz = Observed.notPresent("Not connected"),
            wifiLinkSpeedMbps = Observed.notPresent("Not connected"),
        )
    }
}

enum class NetworkTransport(val label: String, val isWireless: Boolean) {
    WIFI("Wi-Fi", true),
    CELLULAR("Mobile data", true),
    ETHERNET("Ethernet", false),
    BLUETOOTH("Bluetooth", true),
    /** A VPN is checked first, because it sits on top of one of the others and hides it. */
    VPN("VPN", false),
    OTHER("Other", false),
    NONE("Not connected", false),
}

/**
 * The result of one latency probe.
 *
 * The mechanism matters, so it is named rather than hidden: GameCore opens a TCP
 * connection to a well-known host and times the handshake. That is not ICMP ping —
 * an ordinary Android app cannot send ICMP, which needs a raw socket and therefore
 * root — and the two do not measure the same thing. A TCP handshake traverses the
 * same path but is answered by a userspace listener rather than the kernel, so it
 * reads a few milliseconds higher and can be shaped differently by middleboxes.
 *
 * The figure is still useful and still real; it is simply labelled for what it is,
 * and [method] is shown next to it.
 */
data class LatencyProbe(
    val millis: Int,
    val host: String,
    val method: String = "TCP handshake",
) {
    val quality: LatencyQuality get() = LatencyQuality.forMillis(millis)
}

enum class LatencyQuality(val label: String) {
    EXCELLENT("Excellent"),
    GOOD("Good"),
    FAIR("Fair"),
    POOR("Poor"),
    ;

    companion object {
        fun forMillis(ms: Int): LatencyQuality = when {
            ms < 50 -> EXCELLENT
            ms < 100 -> GOOD
            ms < 200 -> FAIR
            else -> POOR
        }
    }
}

/**
 * Latency variation across a window of probes.
 *
 * This is the honest substitute for the packet-loss figure GameCore cannot measure.
 * Jitter — the spread of successive round-trip times — is computable from probes the
 * app really can send, and an unstable connection shows up in it clearly. A probe
 * that times out is counted in [failedProbes] and reported as "probes that did not
 * complete", never as packet loss, because a refused TCP connection and a dropped
 * packet are different events.
 */
data class ConnectionStability(
    val samples: List<Int>,
    val failedProbes: Int,
) {
    val completedProbes: Int get() = samples.size

    val averageMillis: Int? get() = if (samples.isEmpty()) null else samples.average().toInt()

    val minMillis: Int? get() = samples.minOrNull()

    val maxMillis: Int? get() = samples.maxOrNull()

    /**
     * Mean absolute deviation between consecutive probes, which is what the user
     * feels as an unstable connection. Needs at least two completed probes.
     */
    val jitterMillis: Int?
        get() {
            if (samples.size < 2) return null
            var sum = 0L
            for (i in 1 until samples.size) {
                sum += kotlin.math.abs(samples[i] - samples[i - 1]).toLong()
            }
            return (sum / (samples.size - 1)).toInt()
        }

    /**
     * True when the connection is measurably unstable. Two independent triggers:
     * jitter above 40 ms, or any probe failing to complete at all.
     */
    val isUnstable: Boolean
        get() = (jitterMillis?.let { it > UNSTABLE_JITTER_MILLIS } == true) || failedProbes > 0

    val warning: String?
        get() = when {
            !isUnstable -> null
            failedProbes > 0 && completedProbes == 0 ->
                "No probe completed. The connection is down, or something is blocking outbound " +
                    "connections."
            failedProbes > 0 ->
                "$failedProbes of ${failedProbes + completedProbes} probes did not complete. " +
                    "This is not a packet-loss measurement — GameCore cannot make one — but it " +
                    "does mean the connection is dropping requests."
            else ->
                "Latency is varying by about ${jitterMillis} ms between probes, which is what " +
                    "an unstable connection looks like in an online game."
        }

    companion object {
        const val UNSTABLE_JITTER_MILLIS = 40

        val EMPTY = ConnectionStability(emptyList(), 0)
    }
}
