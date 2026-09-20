package com.gamecore.aimlab.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure arithmetic a results screen and every mode lean on.
 *
 * §30 forbids inventing a number where there is none, so the load-bearing cases here are the empty ones:
 * every helper must return a defined zero rather than a NaN a user would read as "NaN%". The normal cases
 * are written out literally so a refactor that changes the formula (a divisor, a rounding rule) is caught
 * rather than silently rescaling every stat the app shows.
 */
class StatsTest {

    private val eps = 1e-4f

    @Test
    fun `accuracy is zero for no shots, clamps an impossible over-hit, and is exact otherwise`() {
        assertEquals(0f, Stats.accuracy(hits = 5, shots = 0), eps)
        assertEquals(1f, Stats.accuracy(hits = 12, shots = 10), eps)
        assertEquals(0.75f, Stats.accuracy(hits = 3, shots = 4), eps)
    }

    @Test
    fun `accuracy percent rounds to a whole number`() {
        assertEquals(0, Stats.accuracyPercent(0, 0))
        assertEquals(75, Stats.accuracyPercent(3, 4))
        assertEquals(33, Stats.accuracyPercent(1, 3))
    }

    @Test
    fun `mean is zero for an empty list and exact otherwise`() {
        assertEquals(0f, Stats.mean(emptyList()), eps)
        assertEquals(2f, Stats.mean(listOf(1f, 2f, 3f)), eps)
        assertEquals(0f, Stats.meanLong(emptyList()), eps)
        assertEquals(20f, Stats.meanLong(listOf(10L, 20L, 30L)), eps)
    }

    @Test
    fun `median takes the middle for odd counts and the mean of the two middles for even`() {
        assertEquals(0f, Stats.median(emptyList()), eps)
        assertEquals(3f, Stats.median(listOf(5L, 1L, 3L)), eps) // sorted 1,3,5
        assertEquals(2.5f, Stats.median(listOf(1L, 2L, 3L, 4L)), eps)
    }

    @Test
    fun `the fiftieth percentile equals the median for both parities`() {
        assertEquals(0f, Stats.percentile(emptyList(), 50f), eps)
        val odd = listOf(5L, 1L, 3L)
        assertEquals(Stats.median(odd), Stats.percentile(odd, 50f), eps)
        val even = listOf(1L, 2L, 3L, 4L)
        assertEquals(Stats.median(even), Stats.percentile(even, 50f), eps)
    }

    @Test
    fun `a single-element percentile is that element, and the extremes are the min and max`() {
        assertEquals(7f, Stats.percentile(listOf(7L), 50f), eps)
        val values = listOf(10L, 20L, 30L, 40L)
        assertEquals(10f, Stats.percentile(values, 0f), eps)
        assertEquals(40f, Stats.percentile(values, 100f), eps)
    }

    @Test
    fun `an empty reaction list folds to the shared all-zero summary`() {
        assertEquals(ReactionStats.EMPTY, ReactionStats.from(emptyList()))
    }

    @Test
    fun `a reaction list computes attempts, fastest, slowest, average and median`() {
        val stats = ReactionStats.from(listOf(300L, 100L, 200L, 400L))
        assertEquals(4, stats.attempts)
        assertEquals(100L, stats.fastestMillis)
        assertEquals(400L, stats.slowestMillis)
        assertEquals(250f, stats.averageMillis, eps)
        assertEquals(250f, stats.medianMillis, eps)
    }
}
