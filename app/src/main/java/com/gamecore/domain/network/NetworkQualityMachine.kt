package com.gamecore.domain.network

import com.gamecore.core.model.ConnectionStability
import com.gamecore.core.model.NetworkTransport
import kotlin.math.abs

/**
 * The network-quality check (spec §C), as pure logic on injected values rather than a live radio.
 *
 * Everything the check needs to *decide* — whether a window of probes is good, whether an alert is due,
 * whether a game should launch — is arithmetic on numbers the caller hands in. So this follows the same
 * shape as [com.gamecore.core.overlay.HoldToConfirm] and [com.gamecore.domain.optimization.WriteLedger]:
 * immutable values, time in as a plain `nowMillis: Long`, no `android.*`, and every rule reachable from a
 * JVM test without a `ConnectivityManager` or a socket.
 *
 * The pieces here are deliberately small and separate:
 *  - [NetworkSample] / [NetworkQualityWindow] hold the last [NetworkQualityWindow.MAX_SAMPLES] probe
 *    outcomes and compute average / jitter / loss over them. A null latency is a probe that did not come
 *    back, which is counted as loss and never folded into the latency figures — the same distinction
 *    [com.gamecore.core.model.ConnectionStability] draws between completed and failed probes.
 *  - The rating enums each carry a pure `rate(...)` with the thresholds as overridable parameters, so
 *    spec §C2's "user-adjustable alert thresholds" is a matter of passing different numbers, not editing
 *    this file.
 *  - [wifiBand] maps a Wi-Fi centre frequency to a band, [compactNetworkLine] / [detailedNetworkLine]
 *    build the pill's one-liners omitting anything absent, [AlertGate] rate-limits alerts to one per
 *    minute (spec §C5), and [preLaunchVerdict] is the non-blocking pre-launch decision (spec §C4).
 */

/**
 * One probe outcome. [latencyMillis] is `null` when the probe failed or was never sent — a distinct
 * state from a slow-but-successful probe, and the reason loss can be counted at all.
 */
data class NetworkSample(val latencyMillis: Int?, val nowMillis: Long)

/**
 * The last [MAX_SAMPLES] probe outcomes, oldest→newest, as an immutable ring.
 *
 * [record] returns a new window with the sample appended and the oldest dropped once the cap is reached,
 * exactly like [com.gamecore.core.common.SampleRingBuffer] but immutable so the caller can keep and test
 * intermediate states. Every readout is a computed property over [samples], derived fresh, so a window
 * read back is always self-consistent.
 */
data class NetworkQualityWindow(private val samples: List<NetworkSample> = emptyList()) {

    /** Appends [sample], dropping the oldest so the window never exceeds [MAX_SAMPLES]. */
    fun record(sample: NetworkSample): NetworkQualityWindow {
        val next = samples + sample
        val capped = if (next.size > MAX_SAMPLES) next.subList(next.size - MAX_SAMPLES, next.size) else next
        return NetworkQualityWindow(capped.toList())
    }

    /** How many samples are held, never more than [MAX_SAMPLES]. */
    val size: Int get() = samples.size

    /** Successful probe latencies, in order — the only samples the latency figures are built from. */
    private val successes: List<Int> get() = samples.mapNotNull { it.latencyMillis }

    /** Probes that came back. */
    val completed: Int get() = successes.size

    /** Probes that did not come back (loss). */
    val failed: Int get() = samples.count { it.latencyMillis == null }

    /** Mean latency over successful probes, or null when none completed. */
    val averageLatencyMillis: Int? get() = if (successes.isEmpty()) null else (successes.sum() / successes.size)

    /** The most recent probe's latency, or null when the window is empty or that probe failed. */
    val lastLatencyMillis: Int? get() = samples.lastOrNull()?.latencyMillis

    /**
     * Mean absolute difference between *consecutive successful* probes, matching
     * [com.gamecore.core.model.ConnectionStability.jitterMillis]. A failure in between breaks the chain:
     * only pairs where both probes came back contribute, so a timeout is never mistaken for a spread of
     * round-trip times. Null until at least two successful probes exist.
     */
    val jitterMillis: Int?
        get() {
            if (successes.size < 2) return null
            var sum = 0L
            for (i in 1 until successes.size) sum += abs(successes[i] - successes[i - 1]).toLong()
            return (sum / (successes.size - 1)).toInt()
        }

    /** Failed probes as a percent of all probes, 0 when the window is empty. Integer, truncated. */
    val lossPercent: Int get() = if (samples.isEmpty()) 0 else (failed * 100) / samples.size

    companion object {
        /** The check keeps the last 30 probe outcomes — a couple of minutes at the usual probe cadence. */
        const val MAX_SAMPLES = 30

        val EMPTY = NetworkQualityWindow()
    }
}

/** A quality band shared by the four ratings, worst→best ordered so `>=`/`<=` comparisons read naturally. */
enum class QualityRating(val label: String) {
    UNAVAILABLE("Unavailable"),
    POOR("Poor"),
    FAIR("Fair"),
    GOOD("Good"),
}

/**
 * Latency rating (spec §C defaults): `< goodBelow` Good, `< fairBelow` Fair, at or above Poor, null
 * Unavailable. Thresholds default to 50 ms / 100 ms and are parameters so §C2 can adjust them.
 */
object LatencyRating {
    const val DEFAULT_GOOD_BELOW = 50
    const val DEFAULT_FAIR_BELOW = 100

    fun rate(latencyMillis: Int?, goodBelow: Int = DEFAULT_GOOD_BELOW, fairBelow: Int = DEFAULT_FAIR_BELOW): QualityRating =
        when {
            latencyMillis == null -> QualityRating.UNAVAILABLE
            latencyMillis < goodBelow -> QualityRating.GOOD
            latencyMillis <= fairBelow -> QualityRating.FAIR
            else -> QualityRating.POOR
        }
}

/**
 * Jitter rating: `<= goodAtMost` Good, `<= fairAtMost` Fair, above Poor, null Unavailable. Defaults are
 * 15 ms / 30 ms, so anything over 30 ms is flagged Poor — the "flagged above 30" the spec asks for, with
 * a documented Fair/Poor split at 15. Overridable per §C2.
 */
object JitterRating {
    const val DEFAULT_GOOD_AT_MOST = 15
    const val DEFAULT_FAIR_AT_MOST = 30

    fun rate(jitterMillis: Int?, goodAtMost: Int = DEFAULT_GOOD_AT_MOST, fairAtMost: Int = DEFAULT_FAIR_AT_MOST): QualityRating =
        when {
            jitterMillis == null -> QualityRating.UNAVAILABLE
            jitterMillis <= goodAtMost -> QualityRating.GOOD
            jitterMillis <= fairAtMost -> QualityRating.FAIR
            else -> QualityRating.POOR
        }
}

/**
 * Loss rating: 0% Good, `> 0` up to and including [DEFAULT_POOR_ABOVE] Fair, above it Poor. Default poor
 * threshold is 2%, so anything over 2% is flagged Poor. Overridable per §C2.
 */
object LossRating {
    const val DEFAULT_POOR_ABOVE = 2

    fun rate(lossPercent: Int, poorAbove: Int = DEFAULT_POOR_ABOVE): QualityRating =
        when {
            lossPercent <= 0 -> QualityRating.GOOD
            lossPercent <= poorAbove -> QualityRating.FAIR
            else -> QualityRating.POOR
        }
}

/**
 * Wi-Fi signal-strength rating from RSSI in dBm (spec §C defaults): `>= goodAtLeast` Good, down to
 * `fairAtLeast` Fair, below Poor, null Unavailable. Defaults -55 / -70 dBm. Only meaningful for Wi-Fi;
 * the caller decides whether to consult it. Overridable per §C2.
 */
object RssiRating {
    const val DEFAULT_GOOD_AT_LEAST = -55
    const val DEFAULT_FAIR_AT_LEAST = -70

    fun rate(rssiDbm: Int?, goodAtLeast: Int = DEFAULT_GOOD_AT_LEAST, fairAtLeast: Int = DEFAULT_FAIR_AT_LEAST): QualityRating =
        when {
            rssiDbm == null -> QualityRating.UNAVAILABLE
            rssiDbm >= goodAtLeast -> QualityRating.GOOD
            rssiDbm >= fairAtLeast -> QualityRating.FAIR
            else -> QualityRating.POOR
        }
}

/** A Wi-Fi band, with the short label the pill shows next to the transport ("Wi-Fi 5 GHz"). */
enum class WifiBand(val label: String) {
    BAND_2_4("2.4 GHz"),
    BAND_5("5 GHz"),
    BAND_6("6 GHz"),
}

/**
 * The band a Wi-Fi centre frequency in MHz falls in, or null when it is outside the known ranges (or
 * absent). Documented ranges, boundary-inclusive at the low end:
 *  - 2.4 GHz: 2400–2500 MHz
 *  - 5 GHz:   4900–5900 MHz
 *  - 6 GHz:   5925–7125 MHz (Wi-Fi 6E / 7)
 *
 * The 5 and 6 GHz ranges do not touch (5901–5924 is a gap), so a stray value there returns null rather
 * than being forced into a band.
 */
fun wifiBand(freqMhz: Int?): WifiBand? = when {
    freqMhz == null -> null
    freqMhz in 2400..2500 -> WifiBand.BAND_2_4
    freqMhz in 4900..5900 -> WifiBand.BAND_5
    freqMhz in 5925..7125 -> WifiBand.BAND_6
    else -> null
}

/** The middle dot the pill uses to join fields, matching the HUD's compact line. */
private const val SEP = " · "

/**
 * The pill's one-line network summary, e.g. "Wi-Fi 5 GHz · -58 dBm · 32 ms". Any absent field is left
 * out entirely — a missing band, RSSI or latency is never invented or shown as a placeholder. The
 * transport label always leads; the band only appears when the transport is Wi-Fi and a band is known.
 */
fun compactNetworkLine(
    transport: NetworkTransport,
    band: WifiBand?,
    rssiDbm: Int?,
    latencyMillis: Int?,
): String {
    val parts = ArrayList<String>(4)
    val head = if (transport == NetworkTransport.WIFI && band != null) "${transport.label} ${band.label}" else transport.label
    parts.add(head)
    if (rssiDbm != null) parts.add("$rssiDbm dBm")
    if (latencyMillis != null) parts.add("$latencyMillis ms")
    return parts.joinToString(SEP)
}

/**
 * The detailed variant of [compactNetworkLine], adding link speed and the window's jitter and loss when
 * they are present, e.g. "Wi-Fi 5 GHz · -58 dBm · 32 ms · 433 Mbps · 8 ms jitter · 0% loss". Everything
 * absent is still omitted; only the transport label is guaranteed.
 */
fun detailedNetworkLine(
    transport: NetworkTransport,
    band: WifiBand?,
    rssiDbm: Int?,
    latencyMillis: Int?,
    linkSpeedMbps: Int?,
    jitterMillis: Int?,
    lossPercent: Int?,
): String {
    val parts = ArrayList<String>(6)
    parts.add(compactNetworkLine(transport, band, rssiDbm, latencyMillis))
    if (linkSpeedMbps != null) parts.add("$linkSpeedMbps Mbps")
    if (jitterMillis != null) parts.add("$jitterMillis ms jitter")
    if (lossPercent != null) parts.add("$lossPercent% loss")
    return parts.joinToString(SEP)
}

/**
 * The rate-limiter for poor-network alerts (spec §C5): at most one alert per [minIntervalMillis].
 *
 * [lastAlertMillis] is when an alert last fired, null when none has. Immutable and pure like the ledger:
 * [shouldAlert] returns the gate to carry forward and whether to alert now, and the caller keeps the
 * returned gate. A null last time or a gap of at least the interval both permit an alert.
 */
data class AlertGate(val lastAlertMillis: Long? = null)

/**
 * Whether to raise an alert at [nowMillis]. Returns the (possibly updated) gate and the decision.
 *
 * No alert unless [isPoor]; when good the gate is returned unchanged. When poor, an alert fires only if
 * none has fired or at least [minIntervalMillis] has passed since the last, and the returned gate then
 * records [nowMillis]. A clock that appears to go backwards is treated as "too soon" and suppressed.
 */
fun shouldAlert(
    gate: AlertGate,
    nowMillis: Long,
    isPoor: Boolean,
    minIntervalMillis: Long = DEFAULT_MIN_ALERT_INTERVAL_MILLIS,
): Pair<AlertGate, Boolean> {
    if (!isPoor) return gate to false
    val last = gate.lastAlertMillis
    val due = last == null || nowMillis - last >= minIntervalMillis
    return if (due) AlertGate(nowMillis) to true else gate to false
}

/** One poor-network alert per minute, per spec §C5. */
const val DEFAULT_MIN_ALERT_INTERVAL_MILLIS = 60_000L

/**
 * The pre-launch check's verdict (spec §C4). It never blocks a launch — every branch allows the game to
 * start. Only [POOR] asks the UI to show its non-blocking warning sheet; the others are informational.
 */
enum class PreLaunchResult(val allowsLaunch: Boolean, val warns: Boolean, val message: String) {
    NO_CONNECTION(true, false, "No network connection. Launching anyway — offline games are unaffected."),
    TIMED_OUT(true, false, "Network check timed out. Launching anyway."),
    POOR(true, true, "Network quality looks poor. You can still launch."),
    OK(true, false, "Network looks good."),
}

/**
 * Whether a probe [window]'s own quality — latency, jitter or loss — rates [QualityRating.POOR] on any
 * one axis. The shared core of the two §C decisions: the pre-launch verdict (§C4) and the in-session
 * alert (§C5) mean the same thing by "poor", so they compute it here rather than drifting apart. It says
 * nothing about *connection* or *sample count* — those belong to the caller, which is why [preLaunchVerdict]
 * checks `connected`/`completed` around it and [poorForAlert] adds a minimum-samples guard.
 */
fun windowRatesPoor(window: NetworkQualityWindow): Boolean =
    LatencyRating.rate(window.averageLatencyMillis) == QualityRating.POOR ||
        JitterRating.rate(window.jitterMillis) == QualityRating.POOR ||
        LossRating.rate(window.lossPercent) == QualityRating.POOR

/**
 * Decides the pre-launch verdict from a probe [window] and two facts the check already knows: whether it
 * [timedOut] before enough probes ran, and whether the device reports itself [connected].
 *
 * Order matters. No connection wins first (there is nothing to probe), then a timeout (the probes did not
 * finish), then quality: a window whose latency, jitter or loss rates as Poor warns, otherwise OK. A
 * window with no completed probes but a live connection is treated as poor rather than OK — an all-fail
 * window is exactly the case the warning exists for.
 */
fun preLaunchVerdict(
    window: NetworkQualityWindow,
    timedOut: Boolean,
    connected: Boolean,
): PreLaunchResult = when {
    !connected -> PreLaunchResult.NO_CONNECTION
    timedOut -> PreLaunchResult.TIMED_OUT
    window.completed == 0 -> PreLaunchResult.POOR
    windowRatesPoor(window) -> PreLaunchResult.POOR
    else -> PreLaunchResult.OK
}

/**
 * How many probe outcomes the in-session alert (§C5) needs before it will judge a window poor at all.
 *
 * A session's first probe can be slow or drop while the radio wakes and the game's own traffic ramps up;
 * firing an alert on one blip would cry wolf. Three outcomes is roughly the first half-minute at the probe
 * cadence — long enough to mean something, short enough to still warn early in a genuinely bad session.
 */
const val MIN_SAMPLES_FOR_ALERT = 3

/**
 * Whether an in-session probe [window] is poor enough to alert on (§C5).
 *
 * Two differences from [windowRatesPoor], both about not crying wolf. It waits for [minSamples] outcomes
 * before judging anything, and it treats a window whose probes are *all* failing as poor in its own right —
 * a connection that has gone away is precisely the case the alert exists for, and there is no latency figure
 * to rate in that case. Rate-limiting the alerts that result is [shouldAlert]'s job, not this one's.
 */
fun poorForAlert(window: NetworkQualityWindow, minSamples: Int = MIN_SAMPLES_FOR_ALERT): Boolean = when {
    window.size < minSamples -> false
    window.completed == 0 -> true
    else -> windowRatesPoor(window)
}

/**
 * A short, honest reason a [window] rates poor, for the §C5 alert's text. Names the worst axis it finds, in
 * the app's usual language: a probe that did not complete is reported as such and never called packet loss,
 * the same distinction [com.gamecore.core.model.ConnectionStability] draws. Assumes the window is already
 * poor (see [poorForAlert]); the final branch is a defensive fallback rather than an expected state.
 */
fun alertReason(window: NetworkQualityWindow): String = when {
    window.completed == 0 -> "No recent probes are completing — the connection may be down."
    LatencyRating.rate(window.averageLatencyMillis) == QualityRating.POOR ->
        "Latency is high, around ${window.averageLatencyMillis} ms."
    JitterRating.rate(window.jitterMillis) == QualityRating.POOR ->
        "The connection is unstable — about ${window.jitterMillis} ms of jitter."
    LossRating.rate(window.lossPercent) == QualityRating.POOR ->
        "${window.lossPercent}% of recent probes did not complete."
    else -> "Network quality looks poor."
}

/**
 * Rebuilds a [NetworkQualityWindow] from a [ConnectionStability] burst, so the pre-launch check (§C4) can
 * reuse the app's existing probe path — [com.gamecore.domain.monitoring.NetworkMonitor] measures a burst
 * as a `ConnectionStability` — and still reach [preLaunchVerdict] and the §C rating logic.
 *
 * Each completed probe becomes a sample carrying its latency; each failed probe becomes a null-latency
 * sample. That is the same completed-vs-failed distinction both types already draw, so average, jitter and
 * loss come out identical to the stability figure the burst was reported as — a failed probe is loss, never
 * a slow success. Probe timestamps play no part in a verdict, so a monotonic index stands in for the
 * [NetworkSample.nowMillis] a live probe would have carried.
 */
fun qualityWindowOf(stability: ConnectionStability): NetworkQualityWindow {
    var window = NetworkQualityWindow.EMPTY
    stability.samples.forEachIndexed { index, latencyMillis ->
        window = window.record(NetworkSample(latencyMillis = latencyMillis, nowMillis = index.toLong()))
    }
    repeat(stability.failedProbes) { k ->
        val at = (stability.samples.size + k).toLong()
        window = window.record(NetworkSample(latencyMillis = null, nowMillis = at))
    }
    return window
}

/** A poor-connection warning the pre-launch check (§C4) hands the UI. The reason is already user-facing. */
data class PreLaunchWarning(val reason: String)

/**
 * The pure core of the pre-launch check (§C4): given the measured [stability] of a probe burst and whether
 * the device reported itself [connected], decide whether to warn the user before they launch — and with what
 * reason — or to say nothing and let the launch proceed.
 *
 * Null [stability] means there was nothing to judge: measurement is switched off, the burst timed out, there
 * was no connection to probe, or the measurement itself failed. None of those is a poor connection, so none
 * warns — the check never invents a problem it did not measure, and (like every [PreLaunchResult]) never
 * blocks a launch. A real burst is put through [preLaunchVerdict]; only its [PreLaunchResult.warns] verdict
 * (POOR) produces a warning, and the reason is the specific worst axis via [alertReason], falling back to the
 * generic line only for the degenerate empty window.
 */
fun preLaunchWarning(stability: ConnectionStability?, connected: Boolean): PreLaunchWarning? {
    val window = stability?.let { qualityWindowOf(it) } ?: return null
    val verdict = preLaunchVerdict(window, timedOut = false, connected = connected)
    if (!verdict.warns) return null
    val reason = if (window.size == 0) PreLaunchResult.POOR.message else alertReason(window)
    return PreLaunchWarning(reason)
}
