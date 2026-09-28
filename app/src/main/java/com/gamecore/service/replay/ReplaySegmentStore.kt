package com.gamecore.service.replay

import com.gamecore.domain.gaming.replay.ReplaySegment
import com.gamecore.domain.gaming.replay.SegmentRing
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * The thin Android seam over the rolling buffer's on-disk home, `File(cacheDir, "replay")`.
 *
 * It owns the directory and the list of finished segments, and nothing more: the eviction *decision* is the
 * pure [SegmentRing]'s (the buffer controller drives it), and this only carries it out — hand out fresh
 * segment paths, remember the segments the controller finalises, delete the ones eviction drops, and wipe the
 * lot when a [com.gamecore.domain.gaming.replay.StopCleanup] plan says to discard the buffer.
 *
 * The private cache is chosen deliberately (audit's backup-exclusion note): `cacheDir` is never auto-backed
 * up and sits behind `allowBackup="false"`, so the rolling footage is inherently excluded from backups with
 * no manifest change. Segment file names are opaque and are never logged — the buffer's paths are release
 * secrets per the feature's rules.
 *
 * Thread-safe: the buffer controller records and evicts from its own recorder thread, while a stop/cleanup
 * may arrive from the service thread, so every list mutation is guarded by one lock.
 */
class ReplaySegmentStore(cacheDir: File) {

    /** The directory the rolling segments live in; created eagerly so the first arm has somewhere to write. */
    val directory: File = File(cacheDir, DIR_NAME).apply { mkdirs() }

    private val lock = Any()
    private val segments = ArrayList<ReplaySegment>()
    private val sequence = AtomicLong(0L)

    /**
     * A fresh, unique path for the next segment the encoder will roll into.
     *
     * Monotonic (`nanoTime` + a counter) so two files created in the same millisecond cannot collide and the
     * on-disk order matches the capture order. The file is not created here — `MediaRecorder.setNextOutputFile`
     * opens it — this only names it.
     */
    fun newSegmentFile(): File = synchronized(lock) {
        File(directory, "seg-${System.nanoTime()}-${sequence.incrementAndGet()}.$EXTENSION")
    }

    /** Remembers a segment the controller has finalised (a just-closed, complete mp4). */
    fun record(segment: ReplaySegment) = synchronized(lock) {
        segments.add(segment)
    }

    /** A snapshot of the currently-retained segments, ascending by capture time (the order they were added). */
    fun current(): List<ReplaySegment> = synchronized(lock) { segments.toList() }

    /**
     * Drops the given segments: forgets them and unlinks their files.
     *
     * Called with exactly the list [SegmentRing.toEvict] returned, so the count on disk and the count the
     * pure logic retains never drift apart. A delete that fails is swallowed — a leftover file is reclaimed by
     * the next [cleanupAll], and a throw here would take down the recorder thread.
     */
    fun remove(toRemove: List<ReplaySegment>) = synchronized(lock) {
        if (toRemove.isEmpty()) return
        val paths = toRemove.mapTo(HashSet(toRemove.size)) { it.path }
        segments.removeAll { it.path in paths }
        toRemove.forEach { deleteQuietly(File(it.path)) }
    }

    /** The retained buffer's size on disk, summed from the tracked segments (the pure [SegmentRing] figure). */
    fun totalBytes(): Long = synchronized(lock) { SegmentRing.totalBytes(segments) }

    /**
     * Discards the whole buffer: forgets every segment and deletes every file in the directory.
     *
     * The teardown for a [com.gamecore.domain.gaming.replay.CleanupPlan] whose `discardBuffer` is set — which
     * is all of them. Sweeps the directory rather than only the tracked list so a mid-roll file the controller
     * never finalised (the open segment at the instant of a stop) is cleaned up too.
     */
    fun cleanupAll() = synchronized(lock) {
        segments.clear()
        directory.listFiles()?.forEach { if (it.isFile) deleteQuietly(it) }
    }

    private fun deleteQuietly(file: File) {
        try {
            file.delete()
        } catch (error: Throwable) {
            // Best-effort; a leftover file is reclaimed by the next cleanupAll.
        }
    }

    private companion object {
        const val DIR_NAME = "replay"
        const val EXTENSION = "mp4"
    }
}
