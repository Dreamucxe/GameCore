package com.gamecore.core.model

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed

/**
 * The Network Stability screen's whole state, as one immutable object (spec §C).
 *
 * The screen shows two kinds of thing and the honesty discipline turns on keeping them apart. The
 * top half is what GameCore *can* read on this connection — transport, signal, negotiated link speed,
 * device-wide throughput, and a burst of TCP-handshake round trips reported as latency and jitter.
 * The bottom half, [capabilities], is a flat list in which every one of those live metrics sits
 * beside the things GameCore *cannot* read — packet loss, a per-app byte rate, mobile signal, a true
 * ICMP ping — each carrying an [Observed] that says exactly why. A missing value is never a zero and
 * never absent from the list; it is present with its reason, so the screen can never imply it measured
 * something it did not.
 *
 * Held as one value, re-derived rather than mutated, so a screen cannot see a half-updated snapshot —
 * the same shape [DeviceCapabilities] uses. Nothing here reaches into `android.*` or the domain layer:
 * the controller in `domain/network` does the reading and the rating, and hands down this core value.
 */
data class NetworkStabilitySnapshot(
    /** False when the user has switched the module off. Nothing is measured while it is false. */
    val enabled: Boolean,
    val transport: NetworkTransport,
    val isConnected: Boolean,
    val isMetered: Boolean,
    val isVpnActive: Boolean,
    /**
     * The one-line summary, e.g. "Wi-Fi 5 GHz · -58 dBm · 23 ms", with every absent field left out
     * rather than shown as a placeholder. Null when there is nothing yet to summarise.
     */
    val summaryLine: String?,
    /** The last stability burst, or the reason there is not one. Never fabricated into an empty burst. */
    val stability: Observed<ConnectionStability>,
    /** The instability explanation, already user-facing, or null when the connection is steady. */
    val warning: String?,
    /** A poor-network alert fired on this refresh, rate-limited by [lastAlertMillis]; null otherwise. */
    val alert: String?,
    /** True while a burst is in flight, for the button's spinner. */
    val isMeasuring: Boolean,
    /** Every network metric, live ones first, then the four GameCore cannot measure. Always populated. */
    val capabilities: List<NetworkCapabilityRow>,
    /** When the last alert fired, carried so rate-limiting survives a refresh without a mutable field. */
    val lastAlertMillis: Long?,
    /** The host the burst probed, for the "measured against" line, or null when none was. */
    val host: String?,
    val measuredAtMillis: Long,
) {

    /** True when the last burst rated the connection unstable. False when there is no burst to judge. */
    val isUnstable: Boolean
        get() = (stability as? Observed.Value)?.value?.isUnstable == true

    companion object {

        /** The metrics GameCore can read on a live connection, in the order the screen lists them. */
        val LIVE_METRICS: List<NetworkMetric> = listOf(
            NetworkMetric.TRANSPORT,
            NetworkMetric.SIGNAL_STRENGTH,
            NetworkMetric.LINK_SPEED,
            NetworkMetric.DOWNLOAD_THROUGHPUT,
            NetworkMetric.UPLOAD_THROUGHPUT,
            NetworkMetric.LATENCY,
            NetworkMetric.JITTER,
        )

        /**
         * The four metrics GameCore will never report, each with the reason it cannot — the honest
         * counterpart to the live rows. These are constant: no permission, API level or device changes
         * them, because each is a limit of what an ordinary app can do, not of this particular phone.
         */
        val ALWAYS_UNSUPPORTED: List<NetworkCapabilityRow> = listOf(
            NetworkCapabilityRow(
                NetworkMetric.PACKET_LOSS,
                Observed.notPresent(
                    "GameCore does not report packet loss. Measuring it needs ICMP — a raw socket, and " +
                        "therefore root — or a server on the other end to echo probes back, and the app " +
                        "has neither. Connection stability is reported as jitter instead, which a TCP " +
                        "handshake can measure honestly.",
                ),
            ),
            NetworkCapabilityRow(
                NetworkMetric.PER_APP_THROUGHPUT,
                Observed.needsPermission(
                    "A per-app byte counter needs usage-access, a special permission GameCore does not " +
                        "request, and even with it Android reports only coarse buckets rather than a live " +
                        "rate. The throughput shown here is device-wide — every app's traffic — and " +
                        "labelled as such.",
                ),
            ),
            NetworkCapabilityRow(
                NetworkMetric.MOBILE_SIGNAL,
                Observed.needsPermission(
                    "Mobile signal strength needs READ_PHONE_STATE, a runtime permission GameCore does " +
                        "not declare and has no other use for. Wi-Fi signal strength is read without it, " +
                        "so it is shown wherever the connection is Wi-Fi.",
                ),
            ),
            NetworkCapabilityRow(
                NetworkMetric.ICMP_PING,
                Observed.notPresent(
                    "GameCore does not send an ICMP ping. A raw ICMP socket needs root, which an ordinary " +
                        "app cannot have, so latency is timed from a TCP handshake to a well-known host " +
                        "instead — the same path, answered in userspace, and labelled 'TCP handshake' " +
                        "rather than 'ping'.",
                ),
            ),
        )

        /** Live rows all sharing one absence [status], followed by the always-unsupported rows. */
        private fun rowsWith(status: Observed<String>): List<NetworkCapabilityRow> =
            LIVE_METRICS.map { NetworkCapabilityRow(it, status) } + ALWAYS_UNSUPPORTED

        /** The seed before the first refresh: known-nothing, but honest about why. */
        fun awaiting(enabled: Boolean): NetworkStabilitySnapshot = NetworkStabilitySnapshot(
            enabled = enabled,
            transport = NetworkTransport.NONE,
            isConnected = false,
            isMetered = false,
            isVpnActive = false,
            summaryLine = null,
            stability = Observed.awaitingSample("No stability measurement has been taken yet."),
            warning = null,
            alert = null,
            isMeasuring = false,
            capabilities = rowsWith(Observed.awaitingSample("No network reading has been taken yet.")),
            lastAlertMillis = null,
            host = null,
            measuredAtMillis = 0L,
        )

        /** The module is switched off in Settings — the user's choice, not a device limit. */
        fun disabled(): NetworkStabilitySnapshot {
            val off = Observed.samplingDisabled("The network-stability module is switched off in Settings.")
            return NetworkStabilitySnapshot(
                enabled = false,
                transport = NetworkTransport.NONE,
                isConnected = false,
                isMetered = false,
                isVpnActive = false,
                summaryLine = null,
                stability = off,
                warning = null,
                alert = null,
                isMeasuring = false,
                capabilities = rowsWith(off),
                lastAlertMillis = null,
                host = null,
                measuredAtMillis = 0L,
            )
        }

        /**
         * There is no connection to measure. [transport] is preserved when the platform still reports
         * one (a connection that exists but did not validate), and left [NetworkTransport.NONE] otherwise.
         */
        fun disconnected(transport: NetworkTransport = NetworkTransport.NONE): NetworkStabilitySnapshot {
            val notConnected = Observed.notPresent("Not connected.")
            val transportRow = if (transport == NetworkTransport.NONE) {
                NetworkCapabilityRow(NetworkMetric.TRANSPORT, notConnected)
            } else {
                NetworkCapabilityRow(NetworkMetric.TRANSPORT, Observed.of(transport.label, DataSource.CONNECTIVITY))
            }
            val otherLiveRows = LIVE_METRICS
                .filter { it != NetworkMetric.TRANSPORT }
                .map { NetworkCapabilityRow(it, notConnected) }
            return NetworkStabilitySnapshot(
                enabled = true,
                transport = transport,
                isConnected = false,
                isMetered = false,
                isVpnActive = false,
                summaryLine = if (transport == NetworkTransport.NONE) null else transport.label,
                stability = Observed.notPresent("No network connection to measure."),
                warning = null,
                alert = null,
                isMeasuring = false,
                capabilities = listOf(transportRow) + otherLiveRows + ALWAYS_UNSUPPORTED,
                lastAlertMillis = null,
                host = null,
                measuredAtMillis = 0L,
            )
        }
    }
}

/**
 * One line in the capability list: a metric and its honest status.
 *
 * [status] is an [Observed] of the already-formatted display text ("-58 dBm", "1.2 MB/s", "23 ms"). A
 * real reading is a [Observed.Value] carrying that text; anything missing is a [Observed.Restricted] or
 * [Observed.Failed] carrying the reason, so the row is never a blank or a fabricated zero.
 */
data class NetworkCapabilityRow(
    val id: NetworkMetric,
    val status: Observed<String>,
) {
    val label: String get() = id.label
}

/**
 * Every metric the Network Stability screen names. The first group is read on a live connection; the
 * second group is the set GameCore cannot measure and always reports as restricted (see
 * [NetworkStabilitySnapshot.ALWAYS_UNSUPPORTED]).
 */
enum class NetworkMetric(val label: String) {
    TRANSPORT("Connection"),
    SIGNAL_STRENGTH("Signal strength"),
    LINK_SPEED("Link speed (negotiated)"),
    DOWNLOAD_THROUGHPUT("Download (device-wide)"),
    UPLOAD_THROUGHPUT("Upload (device-wide)"),
    LATENCY("Latency (TCP handshake)"),
    JITTER("Jitter"),
    PACKET_LOSS("Packet loss"),
    PER_APP_THROUGHPUT("Per-app throughput"),
    MOBILE_SIGNAL("Mobile signal strength"),
    ICMP_PING("ICMP ping"),
}
