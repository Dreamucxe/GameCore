package com.gamecore.aimlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single integer a personal record ranks a session by.
 *
 * These formulas are the whole point of a scored mode: they decide which run was "better", so a sign
 * error or a missing clamp would silently rank the wrong session as a personal best. Each case pins a
 * property the design promises — zero-in means zero-out, faster and more accurate always scores higher,
 * and no input can drag a score below zero — rather than the exact arithmetic, which the code owns.
 */
class ScoringTest {

    @Test
    fun `a flick session with no hits scores nothing rather than a consolation number`() {
        assertEquals(0, Scoring.flick(hits = 0, shots = 10, avgAcquireMillis = 500f))
    }

    @Test
    fun `for the same hits, spraying fewer misses scores higher`() {
        val accurate = Scoring.flick(hits = 10, shots = 10, avgAcquireMillis = 500f)
        val sprayed = Scoring.flick(hits = 10, shots = 20, avgAcquireMillis = 500f)
        assertTrue("accurate=$accurate sprayed=$sprayed", accurate > sprayed)
    }

    @Test
    fun `acquiring targets faster than the reference earns a speed bonus`() {
        val fast = Scoring.flick(hits = 10, shots = 10, avgAcquireMillis = 400f)
        val slow = Scoring.flick(hits = 10, shots = 10, avgAcquireMillis = 700f)
        assertTrue("fast=$fast slow=$slow", fast > slow)
    }

    @Test
    fun `acquiring slower than the reference never subtracts points`() {
        // At and beyond TARGET_ACQUIRE_MS the bonus is floored at zero, so both collapse to the base.
        val atReference = Scoring.flick(hits = 10, shots = 10, avgAcquireMillis = Scoring.TARGET_ACQUIRE_MS)
        val wayPast = Scoring.flick(hits = 10, shots = 10, avgAcquireMillis = 5_000f)
        assertEquals(1_000, atReference)
        assertEquals(atReference, wayPast)
    }

    @Test
    fun `tracking with no time on target scores zero`() {
        assertEquals(0, Scoring.tracking(timeOnTargetFraction = 0f, averageError = 0.2f))
    }

    @Test
    fun `more time on target scores higher, and more error scores lower`() {
        val more = Scoring.tracking(timeOnTargetFraction = 0.9f, averageError = 0.05f)
        val less = Scoring.tracking(timeOnTargetFraction = 0.5f, averageError = 0.05f)
        assertTrue("more=$more less=$less", more > less)

        val tight = Scoring.tracking(timeOnTargetFraction = 0.8f, averageError = 0.02f)
        val loose = Scoring.tracking(timeOnTargetFraction = 0.8f, averageError = 0.20f)
        assertTrue("tight=$tight loose=$loose", tight > loose)
    }

    @Test
    fun `a reaction test with no attempts scores zero`() {
        assertEquals(0, Scoring.reaction(ReactionStats.EMPTY, hits = 0, shots = 0))
    }

    @Test
    fun `a lower average reaction time scores higher`() {
        val quick = Scoring.reaction(ReactionStats.from(listOf(200L)), hits = 10, shots = 10)
        val sluggish = Scoring.reaction(ReactionStats.from(listOf(400L)), hits = 10, shots = 10)
        assertTrue("quick=$quick sluggish=$sluggish", quick > sluggish)
    }

    @Test
    fun `recoil score is the compensation fraction clamped into zero to ten thousand`() {
        assertEquals(0, Scoring.recoil(-1f))
        assertEquals(0, Scoring.recoil(0f))
        assertEquals(5_000, Scoring.recoil(0.5f))
        assertEquals(10_000, Scoring.recoil(1f))
        assertEquals(10_000, Scoring.recoil(2f))
    }

    @Test
    fun `smoother gyro correction scores higher for the same tracking`() {
        val smooth = Scoring.gyro(timeOnTargetFraction = 0.8f, averageError = 0.02f, correctionStdDev = 0.05f)
        val jittery = Scoring.gyro(timeOnTargetFraction = 0.8f, averageError = 0.02f, correctionStdDev = 2f)
        assertTrue("smooth=$smooth jittery=$jittery", smooth > jittery)
    }

    @Test
    fun `movement score is the flick score scaled by a clamped consistency factor`() {
        val flick = Scoring.flick(hits = 10, shots = 10, avgAcquireMillis = 500f)
        assertEquals(flick, Scoring.movement(10, 10, 500f, consistency = 1f))
        assertEquals((flick * 0.5f).let { Math.round(it) }, Scoring.movement(10, 10, 500f, consistency = 0.5f))
        // Consistency out of range is clamped, never amplified beyond the flick score or below zero.
        assertEquals(flick, Scoring.movement(10, 10, 500f, consistency = 2f))
        assertEquals(0, Scoring.movement(10, 10, 500f, consistency = -1f))
    }
}
