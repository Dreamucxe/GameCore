package com.gamecore.aimlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guarantee that a tracking target never leaves the screen, and the error it accumulates.
 *
 * §B2.4 promises a target is always fully on screen — inset by its own radius — however long the run
 * lasts and whatever pattern it follows. That is asserted here by sweeping every pattern across a minute
 * of time and checking the disc stays inside `[radius, 1-radius]` on both axes; a single unbounded branch
 * would escape and fail. The accumulator's own promise is zero-safety: an empty or off-target run reports
 * defined zeros, never a NaN a results screen would show.
 */
class TrackingPathTest {

    private val radius = 0.05f
    private val speed = 0.3f
    private val eps = 1e-3f

    @Test
    fun `every pattern keeps the target fully on screen for a full minute`() {
        val lo = radius
        val hi = 1f - radius
        for (pattern in TrackingPattern.entries) {
            val path = TrackingPath(pattern, radius, speed, SeededRng(1))
            var t = 0f
            while (t <= 60f) {
                val p = path.positionAt(t)
                assertTrue("$pattern x=${p.x} at t=$t", p.x >= lo - eps && p.x <= hi + eps)
                assertTrue("$pattern y=${p.y} at t=$t", p.y >= lo - eps && p.y <= hi + eps)
                t += 0.05f
            }
        }
    }

    @Test
    fun `the random pattern is reproducible from its seed`() {
        val a = TrackingPath(TrackingPattern.RANDOM, radius, speed, SeededRng(99))
        val b = TrackingPath(TrackingPattern.RANDOM, radius, speed, SeededRng(99))
        var t = 0f
        while (t <= 30f) {
            assertEquals(a.positionAt(t), b.positionAt(t))
            t += 0.25f
        }
    }

    @Test
    fun `position is defined at time zero and at a large time`() {
        for (pattern in TrackingPattern.entries) {
            val path = TrackingPath(pattern, radius, speed, SeededRng(1))
            val start = path.positionAt(0f)
            val late = path.positionAt(10_000f)
            assertTrue("$pattern NaN at t=0", !start.x.isNaN() && !start.y.isNaN())
            assertTrue("$pattern NaN at large t", !late.x.isNaN() && !late.y.isNaN())
        }
    }

    @Test
    fun `a crosshair sitting on the target is fully on target with no error`() {
        val acc = TrackingAccumulator(targetRadius = 0.05f)
        val target = Vec2(0.5f, 0.5f)
        repeat(10) { acc.add(target, target, frameSeconds = 0.016f) }
        assertEquals(1f, acc.timeOnTargetFraction(), eps)
        assertEquals(0f, acc.averageError(), eps)
    }

    @Test
    fun `a crosshair far from the target is never on target`() {
        val acc = TrackingAccumulator(targetRadius = 0.05f)
        repeat(10) { acc.add(Vec2(0.1f, 0.1f), Vec2(0.9f, 0.9f), frameSeconds = 0.016f) }
        assertEquals(0f, acc.timeOnTargetFraction(), eps)
        assertTrue(acc.averageError() > 0f)
    }

    @Test
    fun `an empty run reports defined zeros rather than a NaN`() {
        val acc = TrackingAccumulator(targetRadius = 0.05f)
        assertEquals(0f, acc.timeOnTargetFraction(), eps)
        assertEquals(0f, acc.averageError(), eps)
    }

    @Test
    fun `a non-positive frame duration is ignored`() {
        val acc = TrackingAccumulator(targetRadius = 0.05f)
        acc.add(Vec2(0.5f, 0.5f), Vec2(0.5f, 0.5f), frameSeconds = 0f)
        acc.add(Vec2(0.5f, 0.5f), Vec2(0.5f, 0.5f), frameSeconds = -0.1f)
        assertEquals(0L, acc.sampleCount)
        assertEquals(0f, acc.timeOnTargetFraction(), eps)
    }
}
