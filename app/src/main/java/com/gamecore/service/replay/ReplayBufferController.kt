package com.gamecore.service.replay

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import com.gamecore.core.model.RecordingQuality
import com.gamecore.domain.gaming.replay.ReplayClock
import com.gamecore.domain.gaming.replay.ReplaySegment
import com.gamecore.domain.gaming.replay.SegmentRing
import com.gamecore.domain.gaming.replay.SystemReplayClock
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Drives a `MediaRecorder` as a gap-free ring of ~5 s mp4 segments — the ShadowPlay-style rolling buffer
 * (audit's segmentation note, A4). It reuses the existing recorder pipeline rather than hand-muxing
 * MediaCodec/MediaMuxer: `setMaxDuration` + `setNextOutputFile` + an OnInfoListener roll one segment into the
 * next with the encoder and its VirtualDisplay staying live across every boundary, so there is no gap and no
 * re-consent.
 *
 * Ownership split, per this feature's design: the SERVICE owns the MediaProjection and the VirtualDisplay,
 * this owns only the encoder. The service reads the display size, calls [prepare] to get the encoder's input
 * Surface, hangs its own VirtualDisplay on that surface, then calls [start]. On each rollover the just-closed
 * file is finalised into a [ReplaySegment], the pure [SegmentRing] decides what falls outside the window, and
 * [ReplaySegmentStore] deletes those files. Video only; no audio (audit Q3). Nothing here logs a path.
 *
 * Threading: the MediaRecorder is built on, and only ever touched from, one recorder thread — its
 * OnInfoListener lands there because it is constructed there — so no lock guards the recorder; the service
 * thread reaches it only through [runOnBufferThread].
 */
class ReplayBufferController(
    private val context: Context,
    private val store: ReplaySegmentStore,
    private val windowSeconds: Int,
    private val quality: RecordingQuality = RecordingQuality.BALANCED,
    private val segmentSeconds: Int = DEFAULT_SEGMENT_SECONDS,
    private val clock: ReplayClock = SystemReplayClock(),
    private val onError: (String) -> Unit = {},
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var recorder: MediaRecorder? = null

    private var currentOutput: File? = null
    private var armedNext: File? = null
    private var currentSegmentStartMillis: Long = 0L
    private var running = false
    private var paused = false

    /**
     * Builds and prepares the encoder, returning its input [Surface] for the service to hang a VirtualDisplay
     * on. Null when the device's encoder refuses the size/bitrate (ScreenCaptureController's Unsupported
     * case). [width]/[height] are the raw mirror dimensions; the quality preset's scale and H.264's
     * even-dimension rule are applied here so the caller passes the display's own numbers. The service uses
     * its own density when it creates the VirtualDisplay from the returned surface.
     */
    fun prepare(width: Int, height: Int): Surface? {
        val w = even(width * quality.scalePercent / 100)
        val h = even(height * quality.scalePercent / 100)
        if (w <= 0 || h <= 0) return null
        val t = HandlerThread(THREAD_NAME).apply { start() }
        thread = t
        handler = Handler(t.looper)
        return try {
            runOnBufferThread {
                val first = store.newSegmentFile()
                val encoder = buildRecorder(first, w, h)
                recorder = encoder
                currentOutput = first
                encoder.surface
            }
        } catch (error: Throwable) {
            onError("This device's encoder would not accept the replay buffer.")
            releaseInternalQuietly()
            null
        }
    }

    /** Starts the encoder and arms the first rollover file. False (with [onError]) if the encoder refuses. */
    fun start(): Boolean = runOnBufferThread {
        val encoder = recorder ?: return@runOnBufferThread false
        try {
            encoder.start()
            currentSegmentStartMillis = clock.nowMillis()
            running = true
            paused = false
            armNext(encoder)
            true
        } catch (error: Throwable) {
            onError("Instant Replay could not start on this device.")
            false
        }
    }

    /** Pauses the feed to the encoder (thermal auto-pause, A7). The buffer and its files are kept. */
    fun pause() = runOnBufferThread {
        val encoder = recorder ?: return@runOnBufferThread
        if (running && !paused) {
            try {
                encoder.pause()
                paused = true
            } catch (error: Throwable) {
                // A refused pause leaves the buffer running, the safe direction.
            }
        }
    }

    /** Resumes a thermally-paused buffer once the device has cooled (A7). */
    fun resume() = runOnBufferThread {
        val encoder = recorder ?: return@runOnBufferThread
        if (running && paused) {
            try {
                encoder.resume()
                paused = false
            } catch (error: Throwable) {
                // Staying paused is the safe direction if the encoder refuses.
            }
        }
    }

    /**
     * Stops and releases the encoder and tears the recorder thread down.
     *
     * Deliberately does NOT delete the buffered files — that is the caller's [ReplaySegmentStore.cleanupAll]
     * step, gated by the StopCleanup plan's `discardBuffer`. Safe to call more than once.
     */
    fun stop() {
        val t = thread
        if (t == null) {
            releaseInternalQuietly()
            return
        }
        try {
            runOnBufferThread {
                val encoder = recorder
                if (encoder != null && running) {
                    try {
                        encoder.stop()
                    } catch (error: Throwable) {
                        // Too few frames to finalise; nothing to keep from the open segment anyway.
                    }
                }
                releaseRecorderOnThread()
            }
        } catch (error: Throwable) {
            // Fall through to thread teardown regardless.
        } finally {
            try {
                t.quitSafely()
            } catch (error: Throwable) {
                // Gone either way.
            }
            thread = null
            handler = null
        }
    }

    // ------------------------------------------------------------------ rollover

    /**
     * The rollover callback, on the recorder thread.
     *
     * At `MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED` (803) the encoder has already switched to the armed
     * file, so the previous [currentOutput] is a closed, complete mp4: finalise it into a [ReplaySegment], run
     * the pure ring policy over the whole tracked set, delete what falls outside the window, then arm the next
     * file so the chain never breaks.
     */
    private fun onInfo(what: Int, extra: Int) {
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> onSegmentRolled()
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> onMaxDurationReached()
        }
    }

    private fun onSegmentRolled() {
        val encoder = recorder ?: return
        val closed = currentOutput
        val now = clock.nowMillis()
        if (closed != null) {
            val size = try {
                closed.length()
            } catch (error: Throwable) {
                0L
            }
            if (size > 0L) {
                store.record(ReplaySegment(closed.path, currentSegmentStartMillis, now, size))
            } else {
                try {
                    closed.delete()
                } catch (error: Throwable) {
                    // A zero-length rolled file is not a segment; a failed delete is reclaimed later.
                }
            }
        }
        val evicted = SegmentRing.toEvict(store.current(), windowSeconds, now)
        if (evicted.isNotEmpty()) store.remove(evicted)
        // The file just armed is now the one being written.
        currentOutput = armedNext
        currentSegmentStartMillis = now
        armNext(encoder)
    }

    /**
     * With a next file armed the recorder rolls over (803) instead of stopping, so this is normally a
     * companion signal to ignore. If nothing is armed the chain has broken and the buffer has stopped
     * producing — surface it so the service can tear down and, if it chooses, restart.
     */
    private fun onMaxDurationReached() {
        if (armedNext == null) onError("Instant Replay stopped unexpectedly.")
    }

    private fun armNext(encoder: MediaRecorder) {
        try {
            val next = store.newSegmentFile()
            encoder.setNextOutputFile(next)
            armedNext = next
        } catch (error: Throwable) {
            armedNext = null
            onError("Instant Replay could not continue the rolling buffer.")
        }
    }

    // ------------------------------------------------------------------ encoder

    private fun buildRecorder(first: File, width: Int, height: Int): MediaRecorder {
        val encoder = newRecorder()
        return try {
            encoder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(quality.frameRate)
                setVideoEncodingBitRate(quality.bitRate)
                setOutputFile(first)
                setMaxDuration(segmentSeconds * MILLIS_PER_SECOND)
                setOnInfoListener { _, what, extra -> onInfo(what, extra) }
                prepare()
            }
        } catch (error: Throwable) {
            try {
                encoder.reset()
                encoder.release()
            } catch (inner: Throwable) {
                // Nothing to keep.
            }
            throw error
        }
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private fun releaseRecorderOnThread() {
        try {
            recorder?.reset()
            recorder?.release()
        } catch (error: Throwable) {
            // Gone either way.
        }
        recorder = null
        running = false
        paused = false
        currentOutput = null
        armedNext = null
    }

    private fun releaseInternalQuietly() {
        try {
            recorder?.reset()
            recorder?.release()
        } catch (error: Throwable) {
            // Gone either way.
        }
        recorder = null
        running = false
        paused = false
        try {
            thread?.quitSafely()
        } catch (error: Throwable) {
            // Gone either way.
        }
        thread = null
        handler = null
    }

    /**
     * Runs [block] on the recorder thread and returns its result, so callers on the service thread never
     * touch the MediaRecorder directly. Runs inline when already on that thread (the OnInfoListener path).
     */
    private fun <T> runOnBufferThread(block: () -> T): T {
        val t = thread ?: throw IllegalStateException("buffer not prepared")
        val h = handler ?: throw IllegalStateException("buffer not prepared")
        if (Looper.myLooper() == t.looper) return block()
        val latch = CountDownLatch(1)
        var result: T? = null
        var thrown: Throwable? = null
        h.post {
            try {
                result = block()
            } catch (error: Throwable) {
                thrown = error
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        thrown?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun even(value: Int): Int = (value - value % 2).coerceAtLeast(2)

    private companion object {
        const val THREAD_NAME = "gamecore-replay-buffer"
        const val DEFAULT_SEGMENT_SECONDS = 5
        const val MILLIS_PER_SECOND = 1_000
    }
}
