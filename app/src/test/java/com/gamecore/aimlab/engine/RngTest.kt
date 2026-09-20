package com.gamecore.aimlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * The determinism the rest of the engine is built on, and the quality of the randomness it draws.
 *
 * Every spawn, kick and jitter comes from an [Rng], and two promises hang off that: a seed replays a
 * session exactly (which is what lets [RecoilEngineTest] and [TrackingPathTest] assert reproducibility at
 * all), and the draws are genuinely uniform rather than a constant or a stuck low-entropy stream — a
 * degenerate RNG would still pass every reproducibility test while making "random" patterns learnable by
 * accident. Both are asserted here, along with the bounds every caller assumes: a unit draw in [0,1), a
 * ranged draw inside its range, an int draw below its bound, and a Box–Muller gaussian that really is
 * centred on zero with unit spread. The samples are large but seeded, so these are deterministic checks,
 * not flaky statistics.
 */
class RngTest {

    private val eps = 1e-6f

    /** A fixed unit draw, to pin the interface's default range mapping without any randomness at all. */
    private class FixedRng(private val value: Float) : Rng {
        override fun nextFloat(): Float = value
        override fun nextInt(bound: Int): Int = 0
        override fun nextGaussian(): Float = 0f
    }

    @Test
    fun `the same seed replays an identical sequence of every kind of draw`() {
        val first = SeededRng(42)
        val second = SeededRng(42)
        // Interleaved, because the gaussian caches a spare: drawing a float between two gaussians must not
        // desynchronise the two streams.
        val a = List(200) { listOf(first.nextFloat(), first.nextInt(1_000).toFloat(), first.nextGaussian()) }
        val b = List(200) { listOf(second.nextFloat(), second.nextInt(1_000).toFloat(), second.nextGaussian()) }
        assertEquals(a, b)
    }

    @Test
    fun `two different seeds diverge instead of sharing a stream`() {
        val fromOne = SeededRng(42).let { rng -> List(32) { rng.nextFloat() } }
        val fromAnother = SeededRng(7).let { rng -> List(32) { rng.nextFloat() } }
        assertNotEquals(fromOne, fromAnother)

        val gaussiansOne = SeededRng(42).let { rng -> List(32) { rng.nextGaussian() } }
        val gaussiansAnother = SeededRng(7).let { rng -> List(32) { rng.nextGaussian() } }
        assertNotEquals(gaussiansOne, gaussiansAnother)
    }

    @Test
    fun `every unit draw lands in zero up to one`() {
        val rng = SeededRng(3)
        repeat(10_000) {
            val v = rng.nextFloat()
            assertTrue("out of range: $v", v >= 0f && v < 1f)
        }
    }

    @Test
    fun `unit draws spread across the whole range rather than repeating a constant`() {
        val rng = SeededRng(17)
        val draws = List(10_000) { rng.nextFloat() }
        // A stuck or constant source would collapse the distinct count; 24 random bits per draw means a
        // handful of collisions at most.
        assertTrue("only ${draws.distinct().size} distinct draws", draws.distinct().size > 9_900)
        assertEquals(0.5, draws.average(), 0.02)
        assertTrue(draws.count { it < 0.5f } > 4_500)
        assertTrue(draws.count { it >= 0.5f } > 4_500)
        // Every tenth of the range is visited at least once.
        assertEquals(10, draws.map { (it * 10f).toInt() }.distinct().size)
    }

    @Test
    fun `a ranged draw stays inside its range and is never lopsided`() {
        val rng = SeededRng(11)
        val draws = List(5_000) { rng.nextFloat(0.25f, 0.75f) }
        for (v in draws) {
            // Closed at the top: min + f * (max - min) can round up to max for the largest representable f.
            assertTrue("out of range: $v", v >= 0.25f && v <= 0.75f)
        }
        assertEquals(0.5, draws.average(), 0.02)
        assertTrue("never near the bottom", draws.min() < 0.3f)
        assertTrue("never near the top", draws.max() > 0.7f)

        val negative = List(2_000) { rng.nextFloat(-1.5f, -0.5f) }
        for (v in negative) assertTrue("out of range: $v", v >= -1.5f && v <= -0.5f)
        assertEquals(-1.0, negative.average(), 0.05)
    }

    @Test
    fun `a range of zero width returns its single value rather than drifting`() {
        val rng = SeededRng(5)
        repeat(100) { assertEquals(2f, rng.nextFloat(2f, 2f), 0f) }
        repeat(100) { assertEquals(0f, rng.nextFloat(0f, 0f), 0f) }
    }

    @Test
    fun `the ranged draw maps the unit draw linearly onto the range`() {
        assertEquals(10f, FixedRng(0f).nextFloat(10f, 20f), eps)
        assertEquals(12.5f, FixedRng(0.25f).nextFloat(10f, 20f), eps)
        assertEquals(17.5f, FixedRng(0.75f).nextFloat(10f, 20f), eps)
        assertEquals(-4f, FixedRng(0.25f).nextFloat(-6f, 2f), eps)
    }

    @Test
    fun `int draws stay under their bound and reach every value below it`() {
        val rng = SeededRng(23)
        val seen = mutableSetOf<Int>()
        repeat(4_000) {
            val v = rng.nextInt(6)
            assertTrue("out of range: $v", v in 0..5)
            seen += v
        }
        assertEquals(setOf(0, 1, 2, 3, 4, 5), seen)
        repeat(50) { assertEquals(0, rng.nextInt(1)) }
    }

    @Test
    fun `gaussian draws cluster on zero with unit spread and reach both tails`() {
        val rng = SeededRng(29)
        val draws = List(20_000) { rng.nextGaussian() }
        val mean = draws.average()
        val sd = sqrt(draws.sumOf { (it - mean) * (it - mean) } / draws.size)
        assertEquals(0.0, mean, 0.05)
        assertEquals(1.0, sd, 0.05)
        assertTrue("no upper tail", draws.any { it > 2f })
        assertTrue("no lower tail", draws.any { it < -2f })
        assertTrue("produced a NaN or infinity", draws.all { it.isFinite() })
        // Roughly two thirds of a standard normal falls inside one sd; a jitter source that failed this
        // would not cluster near zero the way the engine assumes.
        val within = draws.count { it > -1f && it < 1f }
        assertTrue("$within of 20000 within one sd", within in 12_800..14_500)
    }
}
