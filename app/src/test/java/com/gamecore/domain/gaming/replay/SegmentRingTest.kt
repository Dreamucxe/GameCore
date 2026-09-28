package com.gamecore.domain.gaming.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [SegmentRing] — the ring-buffer eviction policy. The load-bearing case is the exact cutoff: a segment
 * ending at the cutoff instant is dropped, one ending a millisecond later is kept.
 */
class SegmentRingTest {

    // now = 100_000, window = 30s → cutoff = 70_000.
    private val now = 100_000L
    private val window = 30

    private val old = ReplaySegment("old.mp4", startMillis = 50_000, endMillis = 60_000, sizeBytes = 1_000)
    private val exact = ReplaySegment("exact.mp4", startMillis = 65_000, endMillis = 70_000, sizeBytes = 2_000)
    private val straddle = ReplaySegment("straddle.mp4", startMillis = 68_000, endMillis = 70_001, sizeBytes = 4_000)
    private val recent = ReplaySegment("recent.mp4", startMillis = 90_000, endMillis = 95_000, sizeBytes = 8_000)

    private val all = listOf(old, exact, straddle, recent)

    @Test
    fun `cutoff is now minus window seconds in millis`() {
        assertEquals(70_000L, SegmentRing.cutoffMillis(window, now))
    }

    @Test
    fun `a segment ending exactly at the cutoff is evicted`() {
        val retained = SegmentRing.evict(all, window, now)
        assertTrue("exact-cutoff segment must be dropped", exact !in retained)
        assertTrue(exact in SegmentRing.toEvict(all, window, now))
    }

    @Test
    fun `a segment ending one millisecond after the cutoff is kept`() {
        val retained = SegmentRing.evict(all, window, now)
        assertTrue("segment 1ms past the cutoff must be kept", straddle in retained)
    }

    @Test
    fun `evict retains exactly the in-window segments in ascending order`() {
        assertEquals(listOf(straddle, recent), SegmentRing.evict(all, window, now))
    }

    @Test
    fun `toEvict is the complement, the wholly-older segments`() {
        assertEquals(listOf(old, exact), SegmentRing.toEvict(all, window, now))
    }

    @Test
    fun `retained plus evicted partitions the whole list with no overlap`() {
        val retained = SegmentRing.evict(all, window, now)
        val evicted = SegmentRing.toEvict(all, window, now)
        assertEquals(all.size, retained.size + evicted.size)
        assertTrue(retained.none { it in evicted })
    }

    @Test
    fun `retainedBytes sums only the kept segments`() {
        // straddle 4_000 + recent 8_000
        assertEquals(12_000L, SegmentRing.retainedBytes(all, window, now))
    }

    @Test
    fun `totalBytes sums the whole list`() {
        assertEquals(15_000L, SegmentRing.totalBytes(all))
    }

    @Test
    fun `everything inside the window is kept`() {
        // window large enough that even the oldest end (60_000) is newer than the cutoff.
        val big = 100 // cutoff = 100_000 - 100_000 = 0
        assertEquals(all, SegmentRing.evict(all, big, now))
        assertEquals(15_000L, SegmentRing.retainedBytes(all, big, now))
    }

    @Test
    fun `everything older than the window is evicted`() {
        // now far in the future → cutoff well past every segment's end.
        val future = 10_000_000L
        assertTrue(SegmentRing.evict(all, window, future).isEmpty())
        assertEquals(all, SegmentRing.toEvict(all, window, future))
        assertEquals(0L, SegmentRing.retainedBytes(all, window, future))
    }

    @Test
    fun `empty input yields empty results`() {
        assertTrue(SegmentRing.evict(emptyList(), window, now).isEmpty())
        assertTrue(SegmentRing.toEvict(emptyList(), window, now).isEmpty())
        assertEquals(0L, SegmentRing.retainedBytes(emptyList(), window, now))
    }
}
