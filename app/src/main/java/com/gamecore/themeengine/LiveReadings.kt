package com.gamecore.themeengine

import com.gamecore.core.common.Observed
import com.gamecore.core.model.PerformanceSnapshot

/**
 * One snapshot, as a reading the engine can paint from, plus which of the three it actually got.
 *
 * The temperature is the deci-Celsius integer the sampler reports, divided once here; everything
 * downstream is Celsius. A reading the device withheld contributes 0 to the engine *and* a false in
 * [MetricAvailability], which is what keeps the canvas honest: cold is the safe direction to be wrong
 * in, and the card beside it says "—" rather than the 0.
 *
 * It lives here rather than inside a view model because two of them feed the same engine now — the
 * calibration screen and the shell that paints the app itself — and a mapping written twice is a
 * mapping that disagrees with itself the first time one copy is fixed.
 */
data class LiveReading(
    val metrics: ThemeEngineMetrics,
    val availability: MetricAvailability,
) {
    companion object {
        val EMPTY = LiveReading(ThemeEngineMetrics.EMPTY, MetricAvailability.NONE)
    }
}

fun readingsOf(snapshot: PerformanceSnapshot?): LiveReading {
    if (snapshot == null) return LiveReading.EMPTY
    val cpu = (snapshot.cpu.overallPercent as? Observed.Value)?.value
    val ram = snapshot.memory.takeIf { it.totalBytes > 0L }?.usedPercent
    val temperature = (snapshot.primaryTemperatureDeciCelsius as? Observed.Value)?.value?.let { it / 10f }
    return LiveReading(
        metrics = ThemeEngineMetrics(
            cpuUsage = cpu ?: 0f,
            ramAllocation = ram ?: 0f,
            coreTemperatureC = temperature ?: 0f,
        ),
        availability = MetricAvailability(
            cpu = cpu != null,
            ram = ram != null,
            temperature = temperature != null,
        ),
    )
}
