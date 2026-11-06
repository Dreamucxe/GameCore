package com.gamecore.domain.intelligence

import com.gamecore.core.model.BenchmarkPoint
import com.gamecore.core.model.BenchmarkProfile
import com.gamecore.core.model.ComparisonVerdict
import com.gamecore.core.model.FrameRateSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkMathTest {

    @Test
    fun `summary weights real fps windows by frame count and preserves missing metrics`() {
        val points = listOf(
            point(0L, fps = 30f, frames = 30, cpu = null, gpu = 20f),
            point(2_000L, fps = 60f, frames = 90, cpu = null, gpu = 40f),
        )

        val summary = BenchmarkMath.summarize(points, BenchmarkProfile.QUICK)

        assertEquals(52.5f, summary.averageFps!!, 0.001f)
        assertEquals(30f, summary.lowestWindowFps!!, 0.001f)
        assertNull(summary.averageCpuPercent)
        assertEquals(30f, summary.averageGpuPercent!!, 0.001f)
        assertEquals("/sys (sampled)", summary.gpuSource)
        assertTrue(summary.unavailable.any { it.contains("CPU utilisation") })
        assertEquals(BenchmarkMath.FPS_SOURCE, summary.fpsSource)
    }

    @Test
    fun `comparison reports measured improvement only for comparable significant windows`() {
        val baseline = benchmarkRun(1L, 60f)
        val candidate = benchmarkRun(2L, 70f)

        val result = BenchmarkMath.compare(
            baseline,
            listOf(59f, 60f, 61f, 59.5f, 60.5f),
            candidate,
            listOf(69f, 70f, 71f, 69.5f, 70.5f),
        )

        assertEquals(ComparisonVerdict.IMPROVED, result.verdict)
        assertTrue((result.confidencePercent ?: 0) >= BenchmarkMath.MIN_CONFIDENCE_PERCENT)
        assertEquals(1L, result.baselineRunId)
        assertEquals(2L, result.candidateRunId)
    }

    @Test
    fun `comparison refuses a performance verdict without frame timing`() {
        val result = BenchmarkMath.compare(
            benchmarkRun(1L, null),
            emptyList(),
            benchmarkRun(2L, null),
            emptyList(),
        )

        assertEquals(ComparisonVerdict.INCONCLUSIVE, result.verdict)
        assertNull(result.confidencePercent)
        assertTrue(result.explanation.contains("not measured"))
    }

    private fun point(
        elapsed: Long,
        fps: Float,
        frames: Int,
        cpu: Float?,
        gpu: Float?,
    ) = BenchmarkPoint(
        elapsedMillis = elapsed,
        cpuPercent = cpu,
        cpuSource = cpu?.let { "/proc (sampled)" },
        memoryPercent = 50f,
        memorySource = "ActivityManager (exact)",
        batteryPercent = 80,
        batterySource = "ACTION_BATTERY_CHANGED (exact)",
        charging = false,
        temperatureDeciCelsius = 450,
        temperatureSource = "/sys (exact)",
        refreshRateHz = 120f,
        refreshRateSource = "DisplayManager (exact)",
        frameRate = FrameRateSample(
            averageFps = fps,
            onePercentLowFps = fps - 5f,
            frameCount = frames,
            windowMillis = 2_000L,
            jankFrames = 1,
            p95FrameMillis = 20f,
            p99FrameMillis = 25f,
        ),
        gpuPercent = gpu,
        gpuSource = gpu?.let { "/sys (sampled)" },
    )
}
