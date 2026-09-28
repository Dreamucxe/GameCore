package com.gamecore.domain.gaming.replay

/**
 * The ring-buffer eviction policy: which finished segments the rolling buffer keeps, and which it drops.
 *
 * The rule is a single statement about time, so it lives here as pure arithmetic the caller (the recording
 * service, on each `NEXT_OUTPUT_FILE_STARTED` boundary) drives with the list it has and the clock it reads.
 * A segment belongs to the window while any part of it is newer than the cutoff `now - windowSeconds`; the
 * one segment straddling the cutoff is kept because it still holds footage inside the window.
 *
 * The boundary is deliberately exact and half-open: a segment ending *at* the cutoff instant holds nothing
 * newer than the cutoff and is dropped; a segment ending one millisecond later is kept. Pinning that here
 * (rather than in a `>=`/`>` a service author guesses at) is the whole point of the pure seam.
 */
object SegmentRing {

    /**
     * The cutoff instant: everything ending at or before this is fully outside the window.
     *
     * Extracted so [evict], [toEvict] and the tests all speak of the same number.
     */
    fun cutoffMillis(windowSeconds: Int, nowMillis: Long): Long =
        nowMillis - windowSeconds.toLong() * 1000L

    /**
     * The segments to RETAIN — those with any footage still inside the window (`endMillis > cutoff`).
     *
     * Input order is preserved (the buffer feeds these in ascending time, so the result stays ascending).
     *
     * @return the kept segments, in the order given.
     */
    fun evict(
        segments: List<ReplaySegment>,
        windowSeconds: Int,
        nowMillis: Long,
    ): List<ReplaySegment> {
        val cutoff = cutoffMillis(windowSeconds, nowMillis)
        return segments.filter { it.endMillis > cutoff }
    }

    /**
     * The complement of [evict]: the segments to DELETE — those ending at or before the cutoff, so wholly
     * older than the window. These are the files the caller unlinks after re-arming the encoder.
     */
    fun toEvict(
        segments: List<ReplaySegment>,
        windowSeconds: Int,
        nowMillis: Long,
    ): List<ReplaySegment> {
        val cutoff = cutoffMillis(windowSeconds, nowMillis)
        return segments.filter { it.endMillis <= cutoff }
    }

    /**
     * Total bytes the buffer would hold after eviction — the retained segments summed.
     *
     * This is the live-buffer figure the storage guard and any "buffer using N MB" line should read, so the
     * count of bytes on disk and the count of bytes retained can never drift apart.
     */
    fun retainedBytes(
        segments: List<ReplaySegment>,
        windowSeconds: Int,
        nowMillis: Long,
    ): Long = evict(segments, windowSeconds, nowMillis).sumOf { it.sizeBytes }

    /** The bytes of an arbitrary segment list, for callers that already hold a retained/selected set. */
    fun totalBytes(segments: List<ReplaySegment>): Long = segments.sumOf { it.sizeBytes }
}
