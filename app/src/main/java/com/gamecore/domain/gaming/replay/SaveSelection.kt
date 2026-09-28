package com.gamecore.domain.gaming.replay

/**
 * Which segments to stitch when the user saves "the last N seconds" (audit A6).
 *
 * The same window predicate as [SegmentRing.evict] — a segment is in the clip if any of it is newer than
 * `now - windowSeconds` (`endMillis > cutoff`) — but the result is explicitly ordered ascending by
 * [ReplaySegment.startMillis], because the muxer remuxes them head-to-tail and an out-of-order list would
 * produce a clip that jumps in time. Kept separate from eviction so the "what to keep on disk" and "what to
 * play back" decisions can be read and tested as the distinct things they are.
 */
object SaveSelection {

    /**
     * The ordered segments overlapping the last `windowSeconds` up to `nowMillis`, ascending by start.
     *
     * Boundary matches [SegmentRing]: a segment ending exactly at the cutoff is excluded; one ending a
     * millisecond later is included. Returns an empty list when nothing overlaps the window.
     */
    fun select(
        segments: List<ReplaySegment>,
        windowSeconds: Int,
        nowMillis: Long,
    ): List<ReplaySegment> {
        val cutoff = SegmentRing.cutoffMillis(windowSeconds, nowMillis)
        return segments
            .filter { it.endMillis > cutoff }
            .sortedBy { it.startMillis }
    }
}
