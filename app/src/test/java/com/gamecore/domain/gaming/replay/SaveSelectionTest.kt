package com.gamecore.domain.gaming.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [SaveSelection] — the segments stitched for "save the last N seconds". Same window predicate as
 * [SegmentRing], but the result is explicitly ordered ascending by start so the remux plays in time order.
 */
class SaveSelectionTest {

    // now = 100_000, window = 30s → cutoff = 70_000.
    private val now = 100_000L
    private val window = 30

    private val old = ReplaySegment("old.mp4", startMillis = 50_000, endMillis = 60_000, sizeBytes = 1)
    private val exact = ReplaySegment("exact.mp4", startMillis = 65_000, endMillis = 70_000, sizeBytes = 2)
    private val straddle = ReplaySegment("straddle.mp4", startMillis = 68_000, endMillis = 70_001, sizeBytes = 3)
    private val recent = ReplaySegment("recent.mp4", startMillis = 90_000, endMillis = 95_000, sizeBytes = 4)

    @Test
    fun `selects the in-window segments ascending by start, whatever the input order`() {
        val shuffled = listOf(recent, old, straddle, exact)
        assertEquals(listOf(straddle, recent), SaveSelection.select(shuffled, window, now))
    }

    @Test
    fun `a segment ending exactly at the cutoff is excluded`() {
        assertTrue(exact !in SaveSelection.select(listOf(exact), window, now))
        assertTrue(SaveSelection.select(listOf(exact), window, now).isEmpty())
    }

    @Test
    fun `a segment ending one millisecond after the cutoff is included`() {
        assertEquals(listOf(straddle), SaveSelection.select(listOf(straddle), window, now))
    }

    @Test
    fun `returns empty when nothing overlaps the window`() {
        val future = 10_000_000L
        assertTrue(SaveSelection.select(listOf(old, exact, straddle, recent), window, future).isEmpty())
    }

    @Test
    fun `empty input returns empty`() {
        assertTrue(SaveSelection.select(emptyList(), window, now).isEmpty())
    }

    @Test
    fun `unsorted equal-window segments come back sorted by start`() {
        val a = ReplaySegment("a.mp4", startMillis = 96_000, endMillis = 99_000, sizeBytes = 1)
        val b = ReplaySegment("b.mp4", startMillis = 72_000, endMillis = 77_000, sizeBytes = 1)
        val c = ReplaySegment("c.mp4", startMillis = 84_000, endMillis = 89_000, sizeBytes = 1)
        assertEquals(listOf(b, c, a), SaveSelection.select(listOf(a, b, c), window, now))
    }
}
