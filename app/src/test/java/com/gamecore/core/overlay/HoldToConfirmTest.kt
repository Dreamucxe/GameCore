package com.gamecore.core.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "press and hold to end session" gesture (spec §5), proved on a fake clock (spec §10).
 *
 * The whole gesture is arithmetic on an injected `nowMillis`, so every promise the spec makes — the ring
 * fills over the hold window, early release cancels, it completes once with no double fire — is an assertion
 * on advanced-by-hand timestamps rather than a wait. No `Thread.sleep`, no Android. The base press time is a
 * round 10_000L so the arithmetic reads cleanly, matching the idle-dimmer tests.
 */
class HoldToConfirmTest {

    private val gesture = HoldToConfirm(holdMillis = 2_000L)
    private val start = 10_000L
    private val delta = 0.0001f

    // ---------------------------------------------------------------------- progress

    @Test
    fun `progress rises from zero to one across the hold window`() {
        assertEquals(0f, gesture.progressAt(pressStartMillis = start, nowMillis = start), delta)
        assertEquals(0.25f, gesture.progressAt(pressStartMillis = start, nowMillis = start + 500L), delta)
        assertEquals(0.5f, gesture.progressAt(pressStartMillis = start, nowMillis = start + 1_000L), delta)
        assertEquals(1f, gesture.progressAt(pressStartMillis = start, nowMillis = start + 2_000L), delta)
    }

    @Test
    fun `progress clamps to one and never exceeds it`() {
        // Holding well past the threshold is still a full ring, not an overshoot the view would have to clamp.
        assertEquals(1f, gesture.progressAt(pressStartMillis = start, nowMillis = start + 10_000L), delta)
    }

    @Test
    fun `no press means an empty ring and no confirmation`() {
        assertEquals(0f, gesture.progressAt(pressStartMillis = null, nowMillis = start + 5_000L), delta)
        assertFalse(gesture.isConfirmedAt(pressStartMillis = null, nowMillis = start + 5_000L))
    }

    @Test
    fun `early release resets progress to zero`() {
        // Held halfway, then the finger comes up: pressStart goes null and the ring is empty again at once.
        assertEquals(0.5f, gesture.progressAt(pressStartMillis = start, nowMillis = start + 1_000L), delta)
        assertEquals(0f, gesture.progressAt(pressStartMillis = null, nowMillis = start + 1_000L), delta)
    }

    // ---------------------------------------------------------------------- confirmation boundary

    @Test
    fun `confirmation happens exactly at the hold duration`() {
        assertFalse(gesture.isConfirmedAt(pressStartMillis = start, nowMillis = start + 1_999L))
        assertTrue(gesture.isConfirmedAt(pressStartMillis = start, nowMillis = start + 2_000L))
        assertTrue(gesture.isConfirmedAt(pressStartMillis = start, nowMillis = start + 2_001L))
    }

    // ---------------------------------------------------------------------- the firing edge

    @Test
    fun `the firing edge fires exactly once and not again while still held`() {
        // Frame at completion: not previously confirmed, confirmed now -> fires.
        assertTrue(gesture.firesAt(previouslyConfirmed = false, pressStartMillis = start, nowMillis = start + 2_000L))
        // The caller's latch is now true; every later frame while still held must NOT fire again.
        assertFalse(gesture.firesAt(previouslyConfirmed = true, pressStartMillis = start, nowMillis = start + 2_000L))
        assertFalse(gesture.firesAt(previouslyConfirmed = true, pressStartMillis = start, nowMillis = start + 5_000L))
    }

    @Test
    fun `the firing edge does not fire before the window closes or when not pressing`() {
        assertFalse(gesture.firesAt(previouslyConfirmed = false, pressStartMillis = start, nowMillis = start + 1_999L))
        assertFalse(gesture.firesAt(previouslyConfirmed = false, pressStartMillis = null, nowMillis = start + 5_000L))
    }

    @Test
    fun `a fresh press after a release can confirm and fire again`() {
        // First hold completes and fires; the latch follows isConfirmedAt.
        assertTrue(gesture.firesAt(previouslyConfirmed = false, pressStartMillis = start, nowMillis = start + 2_000L))
        var latch = gesture.isConfirmedAt(pressStartMillis = start, nowMillis = start + 2_000L)
        assertTrue(latch)

        // Finger comes up: pressStart null -> the latch drops back to false.
        latch = gesture.isConfirmedAt(pressStartMillis = null, nowMillis = start + 3_000L)
        assertFalse(latch)

        // A brand-new press from its own timestamp confirms and fires a second time.
        val secondStart = 20_000L
        assertTrue(gesture.firesAt(previouslyConfirmed = latch, pressStartMillis = secondStart, nowMillis = secondStart + 2_000L))
    }

    // ---------------------------------------------------------------------- scheduling one repaint

    @Test
    fun `the view is told exactly when to repaint at completion`() {
        assertEquals(2_000L, gesture.nextChangeAfterMillis(pressStartMillis = start, nowMillis = start))
        assertEquals(1L, gesture.nextChangeAfterMillis(pressStartMillis = start, nowMillis = start + 1_999L))
    }

    @Test
    fun `once complete or not pressing there is nothing to schedule`() {
        assertNull(gesture.nextChangeAfterMillis(pressStartMillis = start, nowMillis = start + 2_000L))
        assertNull(gesture.nextChangeAfterMillis(pressStartMillis = start, nowMillis = start + 9_000L))
        assertNull(gesture.nextChangeAfterMillis(pressStartMillis = null, nowMillis = start))
    }

    @Test
    fun `remaining time counts down, is null when not pressing, and never goes negative`() {
        assertNull(gesture.remainingMillis(pressStartMillis = null, nowMillis = start))
        assertEquals(2_000L, gesture.remainingMillis(pressStartMillis = start, nowMillis = start))
        assertEquals(500L, gesture.remainingMillis(pressStartMillis = start, nowMillis = start + 1_500L))
        // Completed but still held: nothing left to wait for, clamped to zero rather than a negative.
        assertEquals(0L, gesture.remainingMillis(pressStartMillis = start, nowMillis = start + 5_000L))
    }

    // ---------------------------------------------------------------------- the default

    @Test
    fun `the default hold is the spec's two seconds`() {
        assertEquals(2_000L, HoldToConfirm.DEFAULT_HOLD_MILLIS)
        assertEquals(2_000L, HoldToConfirm().holdMillis)
    }
}
