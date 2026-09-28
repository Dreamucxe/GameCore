package com.gamecore.service.replay

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.gamecore.domain.gaming.replay.BlackFrameDecision
import com.gamecore.domain.gaming.replay.BlackFrameDetector
import com.gamecore.domain.gaming.replay.BlackFrameState
import java.nio.ByteBuffer

/**
 * Blocked-capture detection (audit A5) as a thin sampler over a low-rate mirror of the screen.
 *
 * The encoder's input Surface cannot be read back, so — exactly as the magnifier feed in
 * ScreenCaptureController does — this hangs its own small `ImageReader` + `VirtualDisplay` on the SAME
 * projection the service holds, mirrored down to a tiny size (a black feed is black at any resolution). Once
 * a second it computes the mean luminance variance of a frame and folds that one number into the pure
 * [BlackFrameDetector]; when a run of uniform frames latches the detector, [onBlocked] fires once and the
 * sampler stops. Video pixels only; no path is logged.
 *
 * Runs on its own HandlerThread and holds no lock — it is meant to run alongside the buffer's own encoder
 * without contending with it, just like the magnifier feed runs alongside a recording.
 */
class ReplayBlackFrameSampler(
    private val uniformThreshold: Double = BlackFrameDetector.DEFAULT_UNIFORM_THRESHOLD,
    private val requiredConsecutive: Int = BlackFrameDetector.DEFAULT_REQUIRED_CONSECUTIVE,
    private val onBlocked: () -> Unit,
) {
    private var thread: HandlerThread? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var lastSampleElapsed = 0L
    private var state = BlackFrameState()
    private var reported = false

    /**
     * Starts the low-rate mirror on [projection]. [width]/[height]/[densityDpi] are the display's mirror
     * metrics; the mirror is scaled down so the per-frame luminance scan stays cheap. Returns false when the
     * size is unreadable or the device refuses the capture surface; idempotent when already running.
     */
    fun start(projection: MediaProjection, width: Int, height: Int, densityDpi: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (reader != null) return true
        val scale = maxOf(1, maxOf(width, height) / TARGET_MAX_DIMENSION)
        val w = even(width / scale)
        val h = even(height / scale)
        return try {
            val t = HandlerThread(THREAD_NAME).apply { start() }
            thread = t
            val handler = Handler(t.looper)
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, BUFFERS)
            reader = r
            r.setOnImageAvailableListener({ ir -> onImage(ir, w, h) }, handler)
            display = projection.createVirtualDisplay(
                "GameCore-replay-blackframe",
                w,
                h,
                densityDpi.coerceAtLeast(1),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface,
                null,
                handler,
            ) ?: run {
                stop()
                return false
            }
            lastSampleElapsed = 0L
            true
        } catch (error: Throwable) {
            stop()
            false
        }
    }

    /** Stops the mirror and frees its display, reader and thread. Safe to call when nothing is running. */
    fun stop() {
        try {
            display?.release()
        } catch (error: Throwable) {
            // Gone either way.
        }
        display = null
        try {
            reader?.setOnImageAvailableListener(null, null)
            reader?.close()
        } catch (error: Throwable) {
            // Same.
        }
        reader = null
        try {
            thread?.quitSafely()
        } catch (error: Throwable) {
            // Same.
        }
        thread = null
    }

    /**
     * One delivered frame, on the mirror thread. Throttled to one sample a second — five uniform samples
     * trip the detector, so ~5 s of a flat feed blocks the session, matching the pure defaults. The image is
     * always closed so the reader's buffers are freed even on a dropped sample.
     */
    private fun onImage(ir: ImageReader, width: Int, height: Int) {
        val image = try {
            ir.acquireLatestImage()
        } catch (error: Throwable) {
            null
        } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSampleElapsed < SAMPLE_MIN_INTERVAL_MILLIS) return
            lastSampleElapsed = now
            val variance = luminanceVariance(image, width, height)
            val (next, decision) = BlackFrameDetector.decide(
                state,
                variance,
                uniformThreshold,
                requiredConsecutive,
            )
            state = next
            if (decision is BlackFrameDecision.BlockedThisSession && !reported) {
                reported = true
                onBlocked()
            }
        } catch (error: Throwable) {
            // A dropped sample costs one frame, not the sampler.
        } finally {
            try {
                image.close()
            } catch (error: Throwable) {
                // Already closed.
            }
        }
    }

    /**
     * Mean luminance variance of the frame, from `planes[0]` (RGBA_8888), sampling every [SAMPLE_STEP]
     * pixel. A truly flat (black or single-colour) frame has variance ~0; real game frames sit far above the
     * uniform threshold. Row/pixel stride are honoured so the padded buffer is read correctly.
     */
    private fun luminanceVariance(image: Image, width: Int, height: Int): Double {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val rowStride = plane.rowStride
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        var y = 0
        while (y < height) {
            val rowStart = y * rowStride
            var x = 0
            while (x < width) {
                val index = rowStart + x * pixelStride
                if (index + 2 >= buffer.limit()) break
                val r = buffer.get(index).toInt() and 0xFF
                val g = buffer.get(index + 1).toInt() and 0xFF
                val b = buffer.get(index + 2).toInt() and 0xFF
                val lum = LUMA_R * r + LUMA_G * g + LUMA_B * b
                sum += lum
                sumSq += lum * lum
                count++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        if (count == 0) return Double.MAX_VALUE // No pixels read: treat as textured, never a false block.
        val mean = sum / count
        val variance = sumSq / count - mean * mean
        return if (variance < 0.0) 0.0 else variance
    }

    private fun even(value: Int): Int = (value - value % 2).coerceAtLeast(2)

    private companion object {
        const val THREAD_NAME = "gamecore-replay-blackframe"
        const val BUFFERS = 2
        const val TARGET_MAX_DIMENSION = 160
        const val SAMPLE_STEP = 2
        const val SAMPLE_MIN_INTERVAL_MILLIS = 1_000L
        const val LUMA_R = 0.299
        const val LUMA_G = 0.587
        const val LUMA_B = 0.114
    }
}
