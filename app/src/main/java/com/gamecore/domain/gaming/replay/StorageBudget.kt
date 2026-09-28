package com.gamecore.domain.gaming.replay

/**
 * Whether there is room to run the buffer AND save one clip, with the real figures when there is not.
 *
 * [Unavailable] carries both numbers so the chip and any dialog can state exactly what was needed and what
 * was free rather than a bare "not enough space" — §0's "no fake data" rule applied to the storage guard.
 */
sealed interface StorageVerdict {
    /** Enough free space for the live buffer, one worst-case saved clip, and headroom. */
    data object Available : StorageVerdict

    /**
     * Not enough space.
     *
     * @param neededBytes the threshold that was required (buffer + one clip + headroom).
     * @param freeBytes the free space that was reported.
     */
    data class Unavailable(val neededBytes: Long, val freeBytes: Long) : StorageVerdict
}

/**
 * The storage guard (audit A8), as pure arithmetic on a free-space number read on-device.
 *
 * The Android seam (`StatFs`/`File.usableSpace`) only produces `freeBytes`; the decision of how much is
 * enough is here, so it can be unit-tested against every preset. "Enough" is sized for the honest
 * worst case: the live rolling buffer is full (`windowSeconds` of footage) AND the user saves a clip of the
 * same window at the same moment (a second copy of that footage while the buffer keeps rolling), plus a
 * fixed headroom so a save does not fill the disk to the last byte.
 *
 *     needed = windowSeconds * bytesPerSecond   (the live buffer)
 *            + windowSeconds * bytesPerSecond   (one worst-case saved clip of the same window)
 *            + headroomBytes
 */
object StorageBudget {

    /**
     * Slack left free after the buffer and one clip — 250 MB.
     *
     * Room for the muxer's temp output, a MediaStore pending copy, thumbnails, and the OS's own need for a
     * non-full volume, so a save never drives free space to zero. Chosen to comfortably exceed the largest
     * single artefact the feature writes (a HIGH-quality 120 s clip is ~180 MB per the audit's table).
     */
    const val DEFAULT_HEADROOM_BYTES = 250_000_000L

    /** The threshold: buffer + one worst-case clip of the same window + headroom. */
    fun neededBytes(
        windowSeconds: Int,
        bytesPerSecond: Long,
        headroomBytes: Long = DEFAULT_HEADROOM_BYTES,
    ): Long = 2L * windowSeconds.toLong() * bytesPerSecond + headroomBytes

    /**
     * Decide whether `freeBytes` covers the buffer + one clip + headroom.
     *
     * Available iff `freeBytes >= needed`; the boundary (exactly equal) is Available, since the threshold is
     * the amount that must fit.
     */
    fun evaluate(
        windowSeconds: Int,
        bytesPerSecond: Long,
        freeBytes: Long,
        headroomBytes: Long = DEFAULT_HEADROOM_BYTES,
    ): StorageVerdict {
        val needed = neededBytes(windowSeconds, bytesPerSecond, headroomBytes)
        return if (freeBytes >= needed) {
            StorageVerdict.Available
        } else {
            StorageVerdict.Unavailable(neededBytes = needed, freeBytes = freeBytes)
        }
    }
}
