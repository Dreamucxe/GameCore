package com.gamecore.core.system

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.common.Precision
import com.gamecore.core.model.ConnectionStability
import com.gamecore.core.model.LatencyProbe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Latency, measured the only way an ordinary Android app can.
 *
 * An app cannot send ICMP. That needs a raw socket, which needs root, so there is no
 * `ping` in GameCore at any privilege level. What it can do is open a TCP connection and
 * time the handshake, which traverses the same path and is a real round trip — it simply
 * is not the same measurement, reads a few milliseconds higher, and can be shaped
 * differently by middleboxes. So the figure is labelled with its method
 * ([LatencyProbe.method]) everywhere it appears, and never called ping in the UI.
 *
 * What this class refuses to do is as important as what it does:
 *
 *  * **No packet loss.** A TCP connect either completes or does not, and a refused or
 *    filtered connection is not a dropped packet. Failed probes are counted in
 *    [ConnectionStability.failedProbes] and described as probes that did not complete.
 *  * **No payload, ever.** The socket is closed the instant it connects. Nothing is
 *    written to it, nothing is read from it, and no name of a game, device or user is
 *    involved — which is what keeps an app with no backend from acquiring one by
 *    accident.
 *  * **Nothing runs unless something is on screen asking for it.** There is no
 *    background probe loop in this class; the caller drives it.
 */
@Singleton
class LatencyProber @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * One handshake against [host]:[port].
     *
     * `elapsedRealtimeNanos`-grade timing is unnecessary here — a millisecond is the unit
     * the result is shown in — but `System.nanoTime` is used rather than
     * `currentTimeMillis` because it is monotonic, and a clock adjustment during a probe
     * would otherwise produce a negative latency.
     *
     * DNS is deliberately inside the timed region only on the first probe of a run: the
     * address is resolved by `InetSocketAddress` and the platform caches it, so the
     * jitter figure across a window is not dominated by a resolver that answered once.
     */
    suspend fun probe(
        host: String = DEFAULT_HOST,
        port: Int = DEFAULT_PORT,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    ): Observed<LatencyProbe> = withContext(io) {
        val target = try {
            InetSocketAddress(host, port)
        } catch (error: Throwable) {
            return@withContext Observed.Failed("$host could not be resolved", error.message)
        }
        if (target.isUnresolved) {
            return@withContext Observed.Failed("$host could not be resolved")
        }

        val started = System.nanoTime()
        try {
            Socket().use { socket ->
                socket.connect(target, timeoutMillis)
            }
        } catch (error: Throwable) {
            return@withContext Observed.Failed(
                "The probe to $host did not complete",
                error.message,
            )
        }
        val millis = ((System.nanoTime() - started) / 1_000_000L).toInt().coerceAtLeast(0)

        Observed.of(
            LatencyProbe(millis = millis, host = host),
            DataSource.SOCKET_PROBE,
            // A real measurement, but of a handshake rather than of an ICMP echo, and
            // interval-dependent in the sense that it describes one moment.
            Precision.SAMPLED,
        )
    }

    /**
     * A window of probes, for the jitter figure.
     *
     * Jitter is the honest substitute for the packet-loss reading GameCore cannot make:
     * the spread between consecutive round trips is computable from probes the app really
     * can send, and it is what an unstable connection actually looks like to a game.
     *
     * Sequential with a gap between probes, not concurrent. Five simultaneous handshakes
     * measure how well the device parallelises connections; five spaced ones measure the
     * path, which is the question.
     */
    suspend fun measureStability(
        host: String = DEFAULT_HOST,
        port: Int = DEFAULT_PORT,
        probeCount: Int = DEFAULT_PROBE_COUNT,
        gapMillis: Long = DEFAULT_GAP_MILLIS,
    ): ConnectionStability = withContext(io) {
        val samples = ArrayList<Int>(probeCount)
        var failed = 0
        repeat(probeCount.coerceIn(2, MAX_PROBES)) { index ->
            if (index > 0) delay(gapMillis)
            when (val result = probe(host, port)) {
                is Observed.Value -> samples += result.value.millis
                else -> failed++
            }
        }
        ConnectionStability(samples = samples, failedProbes = failed)
    }

    companion object {
        /**
         * Cloudflare's resolver on the DNS-over-TLS port.
         *
         * Chosen for three properties rather than for the brand: it answers TCP on a port
         * that is almost never filtered, it is anycast so the handshake terminates at a
         * nearby edge rather than across an ocean, and it is a resolver — a host whose
         * entire purpose is answering, so a probe against it is not an imposition. The
         * user can change it in settings; nothing in GameCore assumes this value.
         */
        const val DEFAULT_HOST = "1.1.1.1"

        /** 853 — DNS over TLS. Open on essentially every network that carries games. */
        const val DEFAULT_PORT = 853

        const val DEFAULT_TIMEOUT_MILLIS = 2_000

        const val DEFAULT_PROBE_COUNT = 5

        const val DEFAULT_GAP_MILLIS = 200L

        /** More than this and the measurement is itself a burst of traffic. */
        const val MAX_PROBES = 10
    }
}
