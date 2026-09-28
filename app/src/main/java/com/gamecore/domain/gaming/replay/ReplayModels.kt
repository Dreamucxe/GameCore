package com.gamecore.domain.gaming.replay

/**
 * One closed, playable buffer segment on disk.
 *
 * The rolling buffer is a run of these — each a complete mp4 the encoder finished writing at a segment
 * boundary (see the audit's segmentation note). The pure logic never opens the file; it reasons only about
 * the four facts a caller can state without decoding anything: where it is, the wall-clock window it covers,
 * and how big it is.
 *
 * @param path the private-cache path of the finished segment file.
 * @param startMillis when the segment's first frame was captured (epoch millis).
 * @param endMillis when the segment closed (epoch millis); always `>= startMillis` for a real segment.
 * @param sizeBytes the file's size on disk.
 */
data class ReplaySegment(
    val path: String,
    val startMillis: Long,
    val endMillis: Long,
    val sizeBytes: Long,
)

/**
 * How the rolling buffer is shaped.
 *
 * @param windowSeconds how many seconds of the most recent footage the buffer keeps. The caller coerces this
 *   to the allowed preset set {15, 30, 60, 120} before constructing the config — that clamp is a UI concern
 *   and is deliberately *not* re-done here, so this type never silently disagrees with the preference store.
 * @param segmentSeconds the length of each rollover segment; ~5 s by default (a 120 s window is then ~25
 *   segments). Guarded `> 0` because a zero or negative segment length would make the retained-file count
 *   `ceil(window/segment)` undefined.
 * @param bytesPerSecond the on-disk size the current quality writes per second, used to size the buffer for
 *   the storage guard. BALANCED video-only H264 is ~750_000 B/s (see the audit's storage table).
 */
data class ReplayBufferConfig(
    val windowSeconds: Int,
    val segmentSeconds: Int = 5,
    val bytesPerSecond: Long,
) {
    init {
        require(segmentSeconds > 0) {
            "segmentSeconds must be positive, was $segmentSeconds"
        }
    }
}
