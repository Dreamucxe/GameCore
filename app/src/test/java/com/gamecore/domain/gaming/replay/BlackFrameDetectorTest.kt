package com.gamecore.domain.gaming.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [BlackFrameDetector]. The buffer blocks only after a run of uniform (flat) frames, the run resets on
 * any textured frame, and the block is sticky for the session.
 */
class BlackFrameDetectorTest {

    private val blackVariance = 0.0   // perfectly flat frame
    private val liveVariance = 250.0  // a normal, textured game frame

    @Test
    fun `a uniform run trips exactly at the fifth sample, not before`() {
        var state = BlackFrameState()
        // Samples 1..4 keep going.
        for (n in 1..4) {
            val (next, decision) = BlackFrameDetector.decide(state, blackVariance)
            assertEquals("sample $n should keep going", BlackFrameDecision.KeepGoing, decision)
            assertEquals(n, next.consecutiveUniform)
            assertFalse(next.blocked)
            state = next
        }
        // The fifth trips it.
        val (fifth, decision) = BlackFrameDetector.decide(state, blackVariance)
        assertEquals(BlackFrameDecision.BlockedThisSession, decision)
        assertTrue(fifth.blocked)
        assertEquals(5, fifth.consecutiveUniform)
    }

    @Test
    fun `a single textured frame mid-run resets the counter`() {
        var state = BlackFrameState()
        repeat(3) { state = BlackFrameDetector.decide(state, blackVariance).first }
        assertEquals(3, state.consecutiveUniform)
        // One textured frame wipes the run.
        val (reset, decision) = BlackFrameDetector.decide(state, liveVariance)
        assertEquals(BlackFrameDecision.KeepGoing, decision)
        assertEquals(0, reset.consecutiveUniform)
        assertFalse(reset.blocked)
        state = reset
        // It now takes a fresh run of five to block — four is not enough.
        repeat(4) { state = BlackFrameDetector.decide(state, blackVariance).first }
        assertFalse(state.blocked)
        val (fifth, d) = BlackFrameDetector.decide(state, blackVariance)
        assertEquals(BlackFrameDecision.BlockedThisSession, d)
        assertTrue(fifth.blocked)
    }

    @Test
    fun `once blocked it stays blocked, even for a textured frame`() {
        var state = BlackFrameState()
        repeat(5) { state = BlackFrameDetector.decide(state, blackVariance).first }
        assertTrue(state.blocked)
        // A perfectly good frame arrives late — the session is still blocked.
        val (next, decision) = BlackFrameDetector.decide(state, liveVariance)
        assertEquals(BlackFrameDecision.BlockedThisSession, decision)
        assertTrue(next.blocked)
    }

    @Test
    fun `variance exactly at the threshold is textured and never trips`() {
        var state = BlackFrameState()
        // threshold is 1.0 and the test is strict `< threshold`, so 1.0 is NOT uniform.
        repeat(10) { state = BlackFrameDetector.decide(state, luminanceVariance = 1.0).first }
        assertFalse(state.blocked)
        assertEquals(0, state.consecutiveUniform)
    }

    @Test
    fun `variance just below the threshold is uniform`() {
        val (state, decision) = BlackFrameDetector.decide(BlackFrameState(), luminanceVariance = 0.999)
        assertEquals(1, state.consecutiveUniform)
        assertEquals(BlackFrameDecision.KeepGoing, decision)
    }

    @Test
    fun `a custom required-consecutive trips at that count`() {
        var state = BlackFrameState()
        val (first, d1) = BlackFrameDetector.decide(state, blackVariance, requiredConsecutive = 2)
        assertEquals(BlackFrameDecision.KeepGoing, d1)
        state = first
        val (second, d2) = BlackFrameDetector.decide(state, blackVariance, requiredConsecutive = 2)
        assertEquals(BlackFrameDecision.BlockedThisSession, d2)
        assertTrue(second.blocked)
    }

    @Test
    fun `a custom threshold changes what counts as uniform`() {
        // With threshold 300, a 250-variance frame is now "uniform".
        val (state, decision) = BlackFrameDetector.decide(
            BlackFrameState(), luminanceVariance = 250.0, uniformThreshold = 300.0,
        )
        assertEquals(1, state.consecutiveUniform)
        assertEquals(BlackFrameDecision.KeepGoing, decision)
    }

    @Test
    fun `defaults are the documented values`() {
        assertEquals(1.0, BlackFrameDetector.DEFAULT_UNIFORM_THRESHOLD, 0.0)
        assertEquals(5, BlackFrameDetector.DEFAULT_REQUIRED_CONSECUTIVE)
    }
}
