package com.gamecore.core.model

import com.gamecore.core.common.Observed

/**
 * One memory observation.
 *
 * [totalBytes] and [availableBytes] are plain numbers because `ActivityManager
 * .getMemoryInfo()` is a permitted call on every API level GameCore supports and
 * cannot be withheld — it is the one memory fact always available. The rest comes
 * from `/proc/meminfo`, which SELinux policy denies on some vendor kernels, so each
 * of those fields carries its own absence.
 *
 * There is deliberately no "RAM that could be freed" field. Killing background
 * applications to produce a larger available figure is what a fake booster does:
 * Android relaunches them within seconds, the user's music stops, and no frame is
 * gained. GameCore reports what the platform reports.
 */
data class MemoryReading(
    val totalBytes: Long,
    val availableBytes: Long,
    /** Page cache. Counted as available by the kernel, so it is shown separately. */
    val cachedBytes: Observed<Long>,
    val freeBytes: Observed<Long>,
    val swapTotalBytes: Observed<Long>,
    val swapFreeBytes: Observed<Long>,
    /** The level at which the platform starts killing background processes. */
    val lowMemoryThresholdBytes: Observed<Long>,
    val isLowMemory: Boolean,
) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0L)

    /** 0..1. Zero when the total is unknown, which only happens if the service is absent. */
    val usedFraction: Float
        get() = if (totalBytes <= 0L) 0f else (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)

    val usedPercent: Float get() = usedFraction * 100f

    companion object {
        /** Nothing readable. Distinct from "0 bytes used", which would be a claim. */
        val EMPTY = MemoryReading(
            totalBytes = 0L,
            availableBytes = 0L,
            cachedBytes = Observed.Failed("Memory counters could not be read"),
            freeBytes = Observed.Failed("Memory counters could not be read"),
            swapTotalBytes = Observed.Failed("Memory counters could not be read"),
            swapFreeBytes = Observed.Failed("Memory counters could not be read"),
            lowMemoryThresholdBytes = Observed.Failed("Memory counters could not be read"),
            isLowMemory = false,
        )
    }
}

/**
 * Internal storage, from `StatFs` on the app's own data directory.
 *
 * Not [Observed]-wrapped: an app can always stat its own filesystem. What it cannot
 * do is see the physical device size — the figure here is the user-visible partition,
 * which is what every other app on the phone shows too, and is smaller than the
 * number on the box.
 */
data class StorageReading(
    val totalBytes: Long,
    val availableBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0L)

    val usedFraction: Float
        get() = if (totalBytes <= 0L) 0f else (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)

    companion object {
        val EMPTY = StorageReading(0L, 0L)
    }
}
