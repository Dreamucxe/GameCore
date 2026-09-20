package com.gamecore.aimlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tap-vs-drag rule the training surface fires shots from (§5).
 *
 * The bug this pins: a Reaction run showed 17 "misses" with 0 attempts while still WAITING, which is a
 * look-drag being counted as a shot. So the strict cases here are the ones that matter — a drag, however
 * it ends, is never a tap, and touch-down alone is never a shot.
 */
class TouchClassifierTest {

    private val slop = 12f

    @Test
    fun `a pointer that stayed within slop is a tap`() {
        assertEquals(TouchResult.TAP, TouchClassifier.classifyRelease(cumulativeTravelPx = 0f, slopPx = slop))
        assertEquals(TouchResult.TAP, TouchClassifier.classifyRelease(cumulativeTravelPx = slop, slopPx = slop))
        assertEquals(TouchResult.TAP, TouchClassifier.classifyRelease(cumulativeTravelPx = slop - 0.1f, slopPx = slop))
    }

    @Test
    fun `a pointer that travelled past slop is a drag, never a tap`() {
        assertEquals(TouchResult.DRAG, TouchClassifier.classifyRelease(cumulativeTravelPx = slop + 0.1f, slopPx = slop))
        assertEquals(TouchResult.DRAG, TouchClassifier.classifyRelease(cumulativeTravelPx = 500f, slopPx = slop))
    }

    @Test
    fun `a look drag is never scored as a shot — the reaction-while-waiting bug`() {
        // A drag that wandered a long way and then ended is a look, not a false start.
        val result = TouchClassifier.classifyRelease(cumulativeTravelPx = 320f, slopPx = slop)
        assertFalse("a drag must not fire a shot", result == TouchResult.TAP)
    }

    @Test
    fun `isLooking turns true only past the slop`() {
        assertFalse(TouchClassifier.isLooking(cumulativeTravelPx = slop, slopPx = slop))
        assertTrue(TouchClassifier.isLooking(cumulativeTravelPx = slop + 0.5f, slopPx = slop))
    }
}
