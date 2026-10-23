package com.gamecore.core.system

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.model.FrameRateSample
import com.gamecore.core.model.FrameTiming
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import com.gamecore.core.shizuku.ShellResult
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The frame-time percentiles behind the performance screen's p95 and p99 rows.
 *
 * These are **durations**, not frame rates. The figure is the nearest-rank percentile of the real
 * inter-vsync intervals, converted from nanoseconds to milliseconds, so a larger number means a slower
 * frame. The threshold is [FrameRateSample.MIN_FRAMES] intervals: below it there is no distribution to
 * take a percentile of, and the honest answer is null rather than the single worst frame.
 */
class FrameTimeStatsTest {

    private fun nanos(millis: Long): Long = millis * 1_000_000L

    @Test
    fun `p95 and p99 read known durations by nearest rank`() {
        // 100 one-millisecond-spaced frame intervals: ranks are ceil(95) and ceil(99).
        val hundred = (1L..100L).map(::nanos)

        assertEquals(95f, frameTimePercentileMillis(hundred, 95f)!!, EPSILON)
        assertEquals(99f, frameTimePercentileMillis(hundred, 99f)!!, EPSILON)
    }

    @Test
    fun `p95 and p99 are null below the minimum frame count`() {
        val belowMinimum = (1L..(FrameRateSample.MIN_FRAMES - 1L)).map(::nanos)

        assertNull(frameTimePercentileMillis(belowMinimum, 95f))
        assertNull(frameTimePercentileMillis(belowMinimum, 99f))
    }

    @Test
    fun `an all-equal distribution returns that duration for both percentiles`() {
        val equal = List(FrameRateSample.MIN_FRAMES + 5) { nanos(16) + 700_000L }

        assertEquals(16.7f, frameTimePercentileMillis(equal, 95f)!!, EPSILON)
        assertEquals(16.7f, frameTimePercentileMillis(equal, 99f)!!, EPSILON)
    }

    @Test
    fun `the minimum frame count is the exact boundary where percentiles become available`() {
        val atMinimum = (1L..FrameRateSample.MIN_FRAMES.toLong()).map(::nanos)

        val p95 = frameTimePercentileMillis(atMinimum, 95f)
        val p99 = frameTimePercentileMillis(atMinimum, 99f)
        assertNotNull(p95)
        assertNotNull(p99)
        // 20 samples: p95 is ceil(19) = the 19th, p99 is ceil(19.8) = the 20th.
        assertEquals(19f, p95!!, EPSILON)
        assertEquals(20f, p99!!, EPSILON)
    }

    @Test
    fun `unsorted intervals are ranked in sorted order`() {
        val sorted = (1L..100L).map(::nanos)
        val unsorted = sorted.reversed()

        assertEquals(
            frameTimePercentileMillis(sorted, 95f)!!,
            frameTimePercentileMillis(unsorted, 95f)!!,
            EPSILON,
        )
        assertEquals(
            frameTimePercentileMillis(sorted, 99f)!!,
            frameTimePercentileMillis(unsorted, 99f)!!,
            EPSILON,
        )
    }

    @Test
    fun `a single outlier lands at p99 but not p95 with enough samples`() {
        // 100 intervals: 98 at 10 ms and 2 at 100 ms. p95 (rank 95) is still 10 ms; p99 (rank 99)
        // falls in the slow tail and is 100 ms.
        val withOutlier = List(98) { nanos(10) } + List(2) { nanos(100) }

        assertEquals(10f, frameTimePercentileMillis(withOutlier, 95f)!!, EPSILON)
        assertEquals(100f, frameTimePercentileMillis(withOutlier, 99f)!!, EPSILON)
    }

    @Test
    fun `aggregate carries the percentiles onto the frame rate sample`() {
        // 100 frames, so 99 inter-vsync gaps, ascending from 1 ms to 99 ms.
        var vsync = 0L
        val frames = (1L..100L).map { millis ->
            val at = vsync
            vsync += nanos(millis)
            FrameTiming(intendedVsyncNanos = at, frameCompletedNanos = at + nanos(5))
        }

        val sample = probe.aggregate(frames)

        assertNotNull(sample)
        assertEquals(95f, sample!!.p95FrameMillis!!, EPSILON)
        assertEquals(99f, sample.p99FrameMillis!!, EPSILON)
    }

    private val probe = FrameRateProbe(
        shell = object : ElevatedShell {
            override val accessLevel = AccessLevel.NORMAL

            override suspend fun isAvailable(): Boolean = false

            override suspend fun execute(command: ShellCommand, timeoutMillis: Long): ShellResult =
                ShellResult.failure("not used by this test", AccessLevel.NORMAL)
        },
        io = Dispatchers.Unconfined,
    )

    private companion object {
        const val EPSILON = 0.0001f
    }
}
