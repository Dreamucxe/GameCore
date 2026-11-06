package com.gamecore.domain.intelligence

import android.os.SystemClock
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.BenchmarkPoint
import com.gamecore.core.model.BenchmarkProfile
import com.gamecore.core.model.BenchmarkProgress
import com.gamecore.core.model.FrameRateCapability
import com.gamecore.core.system.DisplayReader
import com.gamecore.core.system.FrameRateProbe
import com.gamecore.core.system.GpuReader
import com.gamecore.core.system.MetricsReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real capture loop behind a benchmark.
 *
 * It does not start a workload, warm the device, raise a clock or draw frames. A benchmark here
 * observes a game or a device that is already running for a fixed window, and every point is a
 * reading that came from an existing source. When a source is absent the field is null and the
 * summary names it; when frame timing is unavailable the run still records CPU, memory, temperature,
 * battery and refresh rate, and says that no FPS figure exists.
 *
 * Sampling is every two seconds, which is the cadence the rest of the monitoring pipeline already
 * uses. Faster would add battery/binder traffic without making the summary more truthful, and slower
 * would miss the thermal transition a stability run exists to show.
 */
@Singleton
class SmartBenchmarkRunner @Inject constructor(
    private val metrics: MetricsReader,
    private val displayReader: DisplayReader,
    private val frameRate: FrameRateProbe,
    private val gpuReader: GpuReader,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun capture(
        profile: BenchmarkProfile,
        durationMillis: Long,
        gamePackage: String?,
        onProgress: (BenchmarkProgress) -> Unit,
    ): BenchmarkCapture = withContext(io) {
        val duration = durationMillis.coerceAtLeast(MIN_DURATION_MILLIS)
        val capability = if (gamePackage.isNullOrBlank()) {
            FrameRateCapability.Unavailable(FrameRateCapability.NO_GAME_SELECTED)
        } else {
            frameRate.capabilityFor(gamePackage)
        }
        frameRate.reset()

        val startedAtMillis = System.currentTimeMillis()
        val startedAtElapsed = SystemClock.elapsedRealtime()
        val points = ArrayList<BenchmarkPoint>()
        val windows = ArrayList<Float>()

        while (isActive) {
            val elapsed = (SystemClock.elapsedRealtime() - startedAtElapsed).coerceAtMost(duration)
            val point = readPoint(
                elapsedMillis = elapsed,
                gamePackage = gamePackage,
                frameTimingAvailable = capability.canMeasureGame,
            )
            points += point
            point.frameRate?.takeIf { it.isReliable }?.let { windows += it.averageFps }
            onProgress(
                BenchmarkProgress(
                    profile = profile,
                    elapsedMillis = elapsed,
                    totalMillis = duration,
                    sampleCount = points.size,
                    latestPoint = point,
                ),
            )
            if (elapsed >= duration) break
            delay(minOf(SAMPLE_INTERVAL_MILLIS, duration - elapsed))
        }

        BenchmarkCapture(
            points = points,
            fpsWindows = windows,
            startedAtMillis = startedAtMillis,
            endedAtMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun readPoint(
        elapsedMillis: Long,
        gamePackage: String?,
        frameTimingAvailable: Boolean,
    ): BenchmarkPoint {
        val cpu = metrics.readCpu()
        val memory = metrics.readMemory()
        val battery = metrics.readBattery()
        val thermal = metrics.readThermal()
        val display = displayReader.read()
        val gpu = gpuReader.read()
        val frame = if (frameTimingAvailable && gamePackage != null) {
            frameRate.sample(gamePackage)
        } else {
            null
        }
        val temperature = thermal.cpuTemperatureDeciCelsius.valueOrNull
            ?: battery.temperatureDeciCelsius.valueOrNull

        return BenchmarkPoint(
            elapsedMillis = elapsedMillis,
            cpuPercent = cpu.overallPercent.valueOrNull,
            cpuSource = cpu.overallPercent.sourceLabelOrNull(),
            memoryPercent = if (memory.totalBytes > 0L) memory.usedPercent else null,
            memorySource = if (memory.totalBytes > 0L) "ActivityManager (exact)" else null,
            batteryPercent = battery.levelPercent.takeIf { it in 0..100 },
            batterySource = if (battery.levelPercent in 0..100) {
                "ACTION_BATTERY_CHANGED (exact)"
            } else {
                null
            },
            charging = battery.isCharging,
            temperatureDeciCelsius = temperature,
            temperatureSource = if (thermal.cpuTemperatureDeciCelsius.valueOrNull != null) {
                thermal.cpuTemperatureDeciCelsius.sourceLabelOrNull()
            } else {
                battery.temperatureDeciCelsius.sourceLabelOrNull()
            },
            refreshRateHz = display.currentRefreshRate.valueOrNull,
            refreshRateSource = display.currentRefreshRate.sourceLabelOrNull(),
            frameRate = frame,
            gpuPercent = gpu.valueOrNull?.loadPercent,
            gpuSource = gpu.sourceLabelOrNull(),
        )
    }

    private fun <T> Observed<T>.sourceLabelOrNull(): String? =
        (this as? Observed.Value<*>)?.let { "${it.source.label} (${it.precision.label.lowercase()})" }

    companion object {
        const val SAMPLE_INTERVAL_MILLIS = 2_000L

        /** Shorter than this cannot produce enough points to be a benchmark at all. */
        const val MIN_DURATION_MILLIS = 5_000L
    }
}

/** What one capture produced. The summary is computed from [points], never from [fpsWindows] alone. */
data class BenchmarkCapture(
    val points: List<BenchmarkPoint>,
    val fpsWindows: List<Float>,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
)
