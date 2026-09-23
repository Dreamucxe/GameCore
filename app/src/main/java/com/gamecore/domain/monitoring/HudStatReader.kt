package com.gamecore.domain.monitoring

import com.gamecore.core.common.Formatters
import com.gamecore.core.common.Observed
import com.gamecore.core.common.unavailabilityText
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.NetworkTransport
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.model.ThermalClassifier
import com.gamecore.domain.network.compactNetworkLine
import com.gamecore.domain.network.wifiBand

/**
 * One stat, resolved to the text that gets drawn, or to the reason there is no text.
 *
 * [value] is the bare figure with no unit — "42", not "42%". The unit lives on [HudStat] because the
 * pill and the HUD draw it differently: a pill with five stats in a row shows "42%" and a HUD widget
 * with a label shows "CPU 42%" on one line or "CPU" above "42%" on two. Formatting the unit in here
 * would mean the renderer had to strip it back off to lay it out.
 *
 * [reason] is never null when [value] is. That is the point of this type: a stat that cannot be shown
 * carries the explanation with it, so the overlay renders "n/a" and the same object tells the
 * performance screen *why* without a second lookup or a parallel set of `if` branches.
 */
data class StatReading(
    val stat: HudStat,
    val value: String?,
    val reason: String? = null,
) {
    val isAvailable: Boolean get() = value != null

    /**
     * True while there is no reading *yet*, as opposed to none at all.
     *
     * The two look identical in [display] — both render [PLACEHOLDER] — but they mean opposite things to
     * a user. "This device does not report a CPU temperature" is a fact about the device; "the sampler
     * has not ticked yet" is a fact about the last few hundred milliseconds. A builder that told the user
     * their phone cannot measure CPU because the first sample had not landed would be wrong every time
     * the screen opened, so the HUD builder's "no reading on this device" note excludes these.
     */
    val isAwaitingFirstSample: Boolean get() = value == null && reason == AWAITING_REASON

    /** What a renderer draws. Falls back to [PLACEHOLDER], never to a zero. */
    fun display(withUnit: Boolean = true): String {
        val text = value ?: return PLACEHOLDER
        return if (withUnit && stat.unit.isNotEmpty()) "$text${stat.unit}" else text
    }

    /** "CPU 42%" — the labelled form, for HUD widgets with `showLabel` on. */
    fun displayLabelled(): String = "${stat.shortLabel} ${display()}"

    companion object {
        const val PLACEHOLDER = "n/a"

        /** Carried by every reading taken before the sampler's first tick. See [isAwaitingFirstSample]. */
        const val AWAITING_REASON = "Waiting for the first reading."
    }
}

/**
 * Turns a snapshot into the strings the pill and the HUD draw.
 *
 * Pure, and takes the snapshot and the clock as arguments, so §31's HUD-configuration tests can
 * assert that a stat with no reading renders as unavailable rather than as zero without an Android
 * device or a running sampler in the way.
 *
 * Every branch here either produces a real figure or produces a reason. There is no default case that
 * returns "0", and the two stats that could plausibly be faked — frame rate and latency — read from
 * [Observed] fields that are only ever populated by a real measurement.
 */
object HudStatReader {

    fun readAll(
        stats: List<HudStat>,
        snapshot: PerformanceSnapshot?,
        sessionElapsedMillis: Long? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<StatReading> = stats.map { read(it, snapshot, sessionElapsedMillis, nowMillis) }

    fun read(
        stat: HudStat,
        snapshot: PerformanceSnapshot?,
        sessionElapsedMillis: Long? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): StatReading {
        // The clock and the session timer do not come from the sampler, so they work before the
        // first sample lands — which is what the user sees in the first two seconds of a session.
        when (stat) {
            HudStat.CLOCK -> return StatReading(stat, Formatters.clockTime(nowMillis))
            HudStat.SESSION_DURATION -> return StatReading(
                stat = stat,
                value = sessionElapsedMillis?.let(Formatters::duration),
                reason = if (sessionElapsedMillis == null) "No session is being recorded." else null,
            )
            else -> Unit
        }

        snapshot ?: return StatReading(stat, null, StatReading.AWAITING_REASON)
        return when (stat) {
            HudStat.CPU_USAGE -> from(stat, snapshot.cpu.overallPercent) { whole(it) }
            HudStat.CPU_TEMPERATURE -> from(stat, snapshot.thermal.cpuTemperatureDeciCelsius) {
                degrees(it)
            }
            HudStat.RAM_USAGE -> StatReading(stat, whole(snapshot.memory.usedPercent))
            HudStat.RAM_FREE -> StatReading(
                stat = stat,
                value = (snapshot.memory.availableBytes / MEGABYTE).toString(),
            )
            HudStat.BATTERY_LEVEL -> StatReading(stat, snapshot.battery.levelPercent.toString())
            HudStat.BATTERY_TEMPERATURE -> from(stat, snapshot.battery.temperatureDeciCelsius) {
                degrees(it)
            }
            HudStat.BATTERY_CURRENT -> from(stat, snapshot.battery.currentMicroAmps) {
                (it / 1000).toString()
            }
            HudStat.REFRESH_RATE -> from(stat, snapshot.display.currentRefreshRate) {
                Formatters.hertzValue(it)
            }
            HudStat.FRAME_RATE -> from(stat, snapshot.frameRate) { whole(it.averageFps) }
            HudStat.NETWORK_LATENCY -> from(stat, snapshot.latency) { it.millis.toString() }
            HudStat.NETWORK_DOWN -> from(stat, snapshot.network.rxRateBytesPerSecond) {
                kilobytes(it)
            }
            HudStat.NETWORK_UP -> from(stat, snapshot.network.txRateBytesPerSecond) { kilobytes(it) }
            // The §C composite line, built from the pieces the snapshot already carries. Not routed through
            // `from` because it is not one Observed field: the pure `compactNetworkLine` omits whatever is
            // absent, and the field reads unavailable only when there is no connection at all — a connected
            // network with no latency probe yet still shows its transport and signal.
            HudStat.NETWORK -> networkLine(snapshot)
            HudStat.STORAGE_FREE -> StatReading(
                stat = stat,
                value = Formatters.gigabytes(snapshot.storage.availableBytes),
            )
            // Route through the shared classifier, not the raw platform label: the OS reports "Normal"
            // well past 80 °C, the bug §8 exists to kill. classify(snapshot) is the same CPU-first/
            // battery-second seam the button and pill dots use, so all three thermal read-outs agree.
            HudStat.THERMAL_STATUS -> StatReading(stat, ThermalClassifier.classify(snapshot).label)
            // Handled above, before the snapshot was required.
            HudStat.CLOCK, HudStat.SESSION_DURATION -> StatReading(stat, null, "Unreachable.")
        }
    }

    /**
     * The §C composite network line: transport, Wi-Fi band, signal and the last latency, joined by
     * [compactNetworkLine] with every absent piece dropped.
     *
     * Unavailable only when there is no connection — a live network with no probe yet is not "unavailable",
     * it is a network whose latency piece is simply omitted, which is the honest state and the common one
     * in the first seconds of a session. The band comes from [wifiBand] over the reported frequency; the
     * latency is this tick's probe if it landed.
     */
    private fun networkLine(snapshot: PerformanceSnapshot): StatReading {
        val network = snapshot.network
        if (!network.isConnected && network.transport == NetworkTransport.NONE) {
            return StatReading(HudStat.NETWORK, null, "No network connection.")
        }
        val band = wifiBand(network.wifiFrequencyMhz.valueOrNull)
        val line = compactNetworkLine(
            transport = network.transport,
            band = band,
            rssiDbm = network.signalStrengthDbm.valueOrNull,
            latencyMillis = snapshot.latency.valueOrNull?.millis,
        )
        return StatReading(HudStat.NETWORK, line)
    }

    /**
     * Wraps an [Observed] field, keeping the reason when there is no value.
     *
     * `unavailabilityText()` is the same sentence the dashboard shows for that field, so a stat that
     * reads "n/a" in the pill and the same stat on the performance screen give the user one
     * explanation rather than two differently-worded ones.
     */
    private inline fun <T> from(
        stat: HudStat,
        observed: Observed<T>,
        transform: (T) -> String,
    ): StatReading {
        val value = observed.valueOrNull
            ?: return StatReading(stat, null, observed.unavailabilityText() ?: "Not available.")
        return StatReading(stat, transform(value))
    }

    private fun whole(value: Float): String = Math.round(value).toString()

    private fun degrees(deciCelsius: Int): String = Math.round(deciCelsius / 10.0f).toString()

    private fun kilobytes(bytesPerSecond: Double): String =
        Math.round(bytesPerSecond / 1024.0).toString()

    private const val MEGABYTE = 1024L * 1024L
}
