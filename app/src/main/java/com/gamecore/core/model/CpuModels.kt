package com.gamecore.core.model

import com.gamecore.core.common.Observed

/**
 * One CPU observation.
 *
 * [coreCount] is the only field that is not [Observed]-wrapped, because
 * `availableProcessors()` cannot fail and cannot be withheld. Everything else on a
 * modern Android device can be, and frequently is: `/proc/stat` is denied outright by
 * SELinux policy on several vendor kernels, `cpufreq` nodes moved out of app reach
 * around Android 10, and thermal zone naming is entirely OEM-specific. Each field
 * carries its own absence so a device that exposes utilisation but not frequency
 * shows the first and explains the second, rather than dropping both.
 */
data class CpuReading(
    val coreCount: Int,
    /** 0..100 across all cores. Needs two samples, so absent on the first tick. */
    val overallPercent: Observed<Float>,
    val perCorePercent: Observed<List<Float>>,
    val frequenciesKHz: Observed<List<CoreFrequency>>,
    val loadAverage: Observed<LoadAverage>,
    /** Tenths of a degree Celsius, from the first plausible CPU thermal zone. */
    val temperatureDeciCelsius: Observed<Int>,
) {
    companion object {
        /**
         * The reading for a device that exposes nothing, or for a sampler that has
         * been switched off. Every field carries the same explanation so no screen
         * has to invent one.
         */
        fun unavailable(coreCount: Int, absence: Observed<Nothing>) = CpuReading(
            coreCount = coreCount,
            overallPercent = absence,
            perCorePercent = absence,
            frequenciesKHz = absence,
            loadAverage = absence,
            temperatureDeciCelsius = absence,
        )
    }
}

/**
 * One core's scaling frequencies, in kHz as `cpufreq` reports them.
 *
 * [isOnline] rather than a zero current frequency: Android parks cores aggressively,
 * and a parked core has no readable `scaling_cur_freq` at all. Reporting 0 MHz would
 * be read as "this core is idle", which is a different claim from "the kernel has
 * taken this core offline and will bring it back when it is needed".
 */
data class CoreFrequency(
    val coreIndex: Int,
    val currentKHz: Long,
    val minKHz: Long,
    val maxKHz: Long,
    val isOnline: Boolean,
) {
    /** Where this core sits between its own bounds, 0..1, or null when unknowable. */
    val loadFraction: Float?
        get() {
            if (!isOnline || maxKHz <= minKHz) return null
            return ((currentKHz - minKHz).toFloat() / (maxKHz - minKHz).toFloat())
                .coerceIn(0f, 1f)
        }
}

/** `/proc/loadavg`: runnable-process averages over 1, 5 and 15 minutes. */
data class LoadAverage(
    val oneMinute: Float,
    val fiveMinutes: Float,
    val fifteenMinutes: Float,
)

/**
 * Raw jiffy counters for one line of `/proc/stat`.
 *
 * Cumulative since boot, which is why nothing here is a percentage: utilisation only
 * exists as a difference between two of these, and [utilisationSince] is the only
 * place that difference is taken.
 */
data class CpuTimes(
    val name: String,
    val user: Long,
    val nice: Long,
    val system: Long,
    val idle: Long,
    val iowait: Long,
    val irq: Long,
    val softirq: Long,
    val steal: Long,
) {
    val total: Long get() = user + nice + system + idle + iowait + irq + softirq + steal

    /** Everything except idle and iowait — a core waiting on I/O is not computing. */
    val active: Long get() = user + nice + system + irq + softirq + steal

    /**
     * Utilisation between two samples as a 0..100 percentage, or null when the
     * counters did not advance.
     *
     * Null rather than 0 on purpose. A zero here would be indistinguishable from a
     * genuinely idle CPU, and the two cases need different things said: one is
     * "nothing is running", the other is "the interval was too short to measure, or
     * the counter reset underneath us".
     */
    fun utilisationSince(previous: CpuTimes): Float? {
        val totalDelta = total - previous.total
        if (totalDelta <= 0) return null
        val activeDelta = (active - previous.active).coerceAtLeast(0)
        return (activeDelta.toFloat() / totalDelta.toFloat() * 100f).coerceIn(0f, 100f)
    }
}
