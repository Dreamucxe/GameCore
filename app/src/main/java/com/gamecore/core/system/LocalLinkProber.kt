package com.gamecore.core.system

import android.content.Context
import android.net.ConnectivityManager
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.model.LatencyProbe
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The router's address on the network the device is using, or null when it cannot be known.
 *
 * Read from the active network's link properties rather than guessed as `192.168.1.1`: a phone on a
 * hotspot, a mesh network or a corporate VLAN has a different gateway, and probing the wrong address
 * would produce a "local link" lane that measures nothing and reports it as clean.
 */
@Singleton
class GatewayResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** The IPv4 default gateway, or null when there is no connected network or no IPv4 route. */
    fun ipv4(): String? = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = manager.activeNetwork ?: return null
        val properties = manager.getLinkProperties(network) ?: return null
        properties.routes
            .firstOrNull { route -> route.isDefaultRoute && route.gateway is Inet4Address }
            ?.gateway
            ?.hostAddress
    }.getOrNull()
}

/**
 * A round trip to the router, measured without ICMP.
 *
 * The same constraint [LatencyProber] documents applies here — an ordinary app cannot send a ping —
 * with one addition that matters for a local measurement: **a refused connection is a measurement.**
 * A router that answers a TCP handshake attempt with a reset has demonstrably reached this device and
 * answered it, which is what a local-link round trip is asking. Treating that reset as a failure
 * would make almost every home router look like a dead link, because most do not listen on the port
 * being probed.
 *
 * A timeout is different. No answer within the window means the probe did not measure a path; it is
 * reported as a failure and never as a slow success, so a filtered port cannot become evidence that
 * the local link was bad. That distinction is the whole reason this is a separate class from
 * [LatencyProber], whose contract is one handshake and no interpretation.
 */
@Singleton
class LocalLinkProber @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun probe(
        gateway: String,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    ): Observed<LatencyProbe> = withContext(io) {
        val target = try {
            InetSocketAddress(gateway, PROBE_PORT)
        } catch (error: Throwable) {
            return@withContext Observed.Failed("$gateway could not be resolved", error.message)
        }
        if (target.isUnresolved) return@withContext Observed.Failed("$gateway could not be resolved")

        val started = System.nanoTime()
        try {
            Socket().use { socket -> socket.connect(target, timeoutMillis) }
            Observed.Value(
                value = LatencyProbe(
                    millis = elapsedMillis(started),
                    host = gateway,
                    method = METHOD,
                ),
                source = DataSource.SOCKET_PROBE,
            )
        } catch (error: ConnectException) {
            // The router answered with a reset: a completed round trip, just not a listening service.
            Observed.Value(
                value = LatencyProbe(
                    millis = elapsedMillis(started),
                    host = gateway,
                    method = METHOD,
                ),
                source = DataSource.SOCKET_PROBE,
            )
        } catch (error: SocketTimeoutException) {
            Observed.Failed("The router did not answer within $timeoutMillis ms", error.message)
        } catch (error: Throwable) {
            Observed.Failed("The probe to the router did not complete", error.message)
        }
    }

    private fun elapsedMillis(startedNanos: Long): Int =
        ((System.nanoTime() - startedNanos) / 1_000_000L).toInt().coerceAtLeast(0)

    companion object {
        /**
         * 443, then nothing.
         *
         * One port, tried once. A second connection to a different port doubles the traffic for a
         * measurement whose answer — the router is reachable — is already given by a reset on the
         * first. The value is a probe target, not a claim that the router serves HTTPS.
         */
        const val PROBE_PORT = 443

        /** One second: a local round trip that takes longer is already the finding. */
        const val DEFAULT_TIMEOUT_MILLIS = 1_000

        /** Said everywhere the figure appears, because this is a TCP handshake and not a ping. */
        const val METHOD = "TCP handshake to the router"
    }
}
