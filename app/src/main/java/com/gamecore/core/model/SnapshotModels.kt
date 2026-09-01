package com.gamecore.core.model

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.Observed

/**
 * One pass over everything GameCore measures, taken at one moment.
 *
 * The readings are kept whole rather than flattened into a bag of nullable primitives. Each one
 * already carries its own [Observed] wrappers explaining why a figure is missing, and collapsing
 * them here would throw that away — the dashboard would be back to rendering "0%" for a value the
 * device never reported, which is the failure this app is built around not having.
 *
 * [display] and [storage] are in the snapshot but are not re-read on every tick. A panel's mode list
 * does not change between two samples two seconds apart, and `StatFs` is a filesystem call; the
 * monitor refreshes them on a slower cadence and carries the previous value forward. That is not a
 * lie about freshness — [capturedAtMillis] is the sample time, and nothing in these two changes
 * inside a sampling interval except by user action, which invalidates the cache anyway.
 *
 * [accessLevel] is what the metrics actually came through, not what the user permitted. A snapshot
 * taken while Shizuku was connected and one taken after it died are different data, and a report
 * that averages the two without recording the difference is describing two devices.
 */
data class PerformanceSnapshot(
    val capturedAtMillis: Long,
    val cpu: CpuReading,
    val memory: MemoryReading,
    val battery: BatteryReading,
    val thermal: ThermalReading,
    val storage: StorageReading,
    val display: DisplayReading,
    val network: NetworkReading,
    /** Only ever a value when [FrameRateCapability] said a real signal exists. */
    val frameRate: Observed<FrameRateSample>,
    val latency: Observed<LatencyProbe>,
    val accessLevel: AccessLevel,
) {

    /**
     * The temperature to show when asked for "the" device temperature.
     *
     * CPU zone first, battery second. They measure different things and neither is "the device
     * temperature", but a user watching for thermal throttling cares about the SoC, and the battery
     * sensor is the one that exists on every device. Whichever is used, the UI labels it.
     */
    val primaryTemperatureDeciCelsius: Observed<Int>
        get() = when (val cpuTemp = thermal.cpuTemperatureDeciCelsius) {
            is Observed.Value -> cpuTemp
            else -> battery.temperatureDeciCelsius
        }

    /** Turns this into a row for the session table. Nulls where nothing was measured. */
    fun toSample(sessionId: Long, elapsedMillis: Long): SessionSample = SessionSample(
        sessionId = sessionId,
        elapsedMillis = elapsedMillis,
        cpuPercent = (cpu.overallPercent as? Observed.Value)?.value,
        memoryPercent = memory.usedPercent,
        batteryPercent = battery.levelPercent,
        temperatureDeciCelsius = (primaryTemperatureDeciCelsius as? Observed.Value)?.value,
        refreshRate = (display.currentRefreshRate as? Observed.Value)?.value,
        frameRate = (frameRate as? Observed.Value)?.value?.averageFps,
        latencyMillis = (latency as? Observed.Value)?.value?.millis,
    )
}

/**
 * The rolling window the live graphs draw.
 *
 * Bounded and immutable. Bounded because a monitor left open for an hour at one sample every two
 * seconds is 1,800 points and no graph 400 pixels wide can show them; immutable because it is read
 * from Compose, and a mutable list mutated on a sampling coroutine while a `LazyRow` walks it is a
 * `ConcurrentModificationException` waiting for a slow frame.
 *
 * [add] drops from the front once [CAPACITY] is reached, so the window slides rather than growing.
 */
data class MetricHistory(
    val points: List<HistoryPoint> = emptyList(),
) {
    val isEmpty: Boolean get() = points.isEmpty()

    /** Enough points that a line is meaningful rather than two dots and a guess. */
    val isPlottable: Boolean get() = points.size >= MIN_PLOTTABLE

    fun add(point: HistoryPoint): MetricHistory {
        val next = if (points.size >= CAPACITY) {
            points.subList(points.size - CAPACITY + 1, points.size) + point
        } else {
            points + point
        }
        return MetricHistory(next)
    }

    fun cpuSeries(): List<Float> = points.mapNotNull { it.cpuPercent }

    fun memorySeries(): List<Float> = points.mapNotNull { it.memoryPercent }

    fun temperatureSeries(): List<Float> =
        points.mapNotNull { it.temperatureDeciCelsius?.let { deci -> deci / 10f } }

    fun frameRateSeries(): List<Float> = points.mapNotNull { it.frameRate }

    companion object {
        const val CAPACITY = 120
        const val MIN_PLOTTABLE = 3

        val EMPTY = MetricHistory()
    }
}

/**
 * One point on the live graphs.
 *
 * Nullable primitives rather than [Observed], for the same reason [SessionSample] uses them: the
 * reason a value is missing is a thing to say once, next to the graph, not 120 times inside it.
 */
data class HistoryPoint(
    val atMillis: Long,
    val cpuPercent: Float? = null,
    val memoryPercent: Float? = null,
    val temperatureDeciCelsius: Int? = null,
    val frameRate: Float? = null,
    val latencyMillis: Int? = null,
)
