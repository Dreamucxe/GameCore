package com.gamecore.core.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.FileProvider
import com.gamecore.core.common.ApplicationScope
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.model.CaptureKind
import com.gamecore.core.model.CaptureOutcome
import com.gamecore.core.model.RecordingQuality
import com.gamecore.core.model.RecordingSpec
import com.gamecore.core.model.RecordingState
import com.gamecore.core.model.SavedCapture
import com.gamecore.core.system.capture.CropRect
import com.gamecore.core.system.capture.ImageMediaStoreSaver
import com.gamecore.core.system.replay.save.ReplayClipSaver
import com.gamecore.core.system.replay.save.ReplaySaveResult
import com.gamecore.domain.gaming.replay.ReplayClock
import com.gamecore.domain.gaming.replay.ReplayThermalDecision
import com.gamecore.domain.gaming.replay.SystemReplayClock
import com.gamecore.domain.monitoring.PerformanceMonitor
import com.gamecore.service.replay.ReplayBufferController
import com.gamecore.service.replay.ReplaySegmentStore
import com.gamecore.service.replay.ReplayThermalCollector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Screenshots and screen recording, both through `MediaProjection`.
 *
 * There is no other honest mechanism, and the alternatives are worth naming because they
 * are what a screen-capture feature usually ships instead:
 *
 *  - Drawing a `View` to a `Bitmap` captures only GameCore's own overlay windows. On a
 *    transparent overlay that produces a picture of the HUD over nothing. It is not a
 *    screenshot of the game and is not offered as one.
 *  - `screencap` through Shizuku would work, but it writes to a path the shell owns and
 *    needs a second command to move it — and it silently produces a black image on devices
 *    with a secure layer on screen, with no way to detect that from the exit code.
 *  - `AccessibilityService.takeScreenshot()` needs an accessibility service, which is a far
 *    broader grant than screen capture and is the single most abused permission on Android.
 *    Asking for it to take a screenshot would be disproportionate.
 *
 * So: `MediaProjection`, which means a system consent dialog. Per §24B that dialog is its
 * own flow through `CaptureConsentActivity`, and recording runs in `ScreenRecordingService`
 * with the `mediaProjection` foreground-service type — from Android 14 the platform
 * *requires* a projection to be attached to such a service and throws otherwise.
 *
 * **This class does not obtain consent and does not start a service.** It takes the
 * `Intent` result the consent Activity already received and turns it into pixels or an mp4.
 * That split is what keeps the UI layer away from `MediaProjection` while leaving the
 * Activity-only part of the API in the one place that can legally do it.
 *
 * The projection is held for as long as the user leaves it granted, because the consent
 * dialog appears once per token and a screenshot button that re-prompted on every tap
 * would be unusable. From Android 14 the platform invalidates a token after one use for
 * *new* projections; [mediaProjection] is therefore checked for liveness before each use
 * and [CaptureOutcome.NeedsConsent] is returned rather than a crash when it has gone.
 */
@Singleton
class ScreenCaptureController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val display: DisplayReader,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clipSaver: ReplayClipSaver,
    private val imageSaver: ImageMediaStoreSaver,
    private val performanceMonitor: PerformanceMonitor,
    @ApplicationScope private val scope: CoroutineScope,
) {

    // The clock the rolling buffer stamps segments with. A plain field rather than a constructor
    // parameter: it has no Hilt binding and needs none — the real implementation is the only one used
    // outside tests, and the buffer that consumes it is built here, not injected.
    private val replayClock: ReplayClock = SystemReplayClock()

    private val recordingState = MutableStateFlow(RecordingState.IDLE)

    /** Observed by the panel button and by the recording service's notification. */
    val recording: StateFlow<RecordingState> = recordingState.asStateFlow()

    /**
     * The latest screen frame for the pinned magnifier (§13), or null when the feed is not running or
     * has not produced a frame yet.
     *
     * A whole `Bitmap` handed over on each update rather than a reused buffer: the overlay reads this on
     * the main thread while the feed thread produces the next one, and overwriting a bitmap Compose is
     * mid-draw on would tear the picture. The previous frame is left for the garbage collector rather than
     * recycled for the same reason — the composable may still be holding it — so the feed is deliberately
     * throttled ([FEED_MIN_INTERVAL_MILLIS]) to keep that allocation rate sane.
     */
    private val magnifierFrameState = MutableStateFlow<Bitmap?>(null)
    val magnifierFrame: StateFlow<Bitmap?> = magnifierFrameState.asStateFlow()

    /**
     * The one frame the Screen Extraction feature (§Screen-Extraction) last grabbed into memory, or null
     * before the first extraction and after [clearExtractedFrame].
     *
     * The channel between the capture — which can only happen inside the `mediaProjection` service, since
     * Android 14 forbids `createVirtualDisplay` anywhere else — and [com.gamecore.ui.extraction.ScreenExtractionViewModel],
     * which collects this flow and cannot itself hold a projection. A non-null emission is the view-model's
     * proof that consent was granted and a frame arrived, which is exactly what ends its busy state.
     *
     * A whole `Bitmap` handed over rather than a reused buffer, and the previous frame left for the garbage
     * collector rather than recycled, for the same reason as [magnifierFrame]: the cropper composable may
     * still be mid-draw on the frame this replaces. Unlike the feed this is one frame taken on demand, so at
     * most a single full-screen bitmap is ever retained here — [captureFrameToMemory] simply overwrites it.
     */
    private val lastExtractedFrameState = MutableStateFlow<Bitmap?>(null)
    val lastExtractedFrame: StateFlow<Bitmap?> = lastExtractedFrameState.asStateFlow()

    /**
     * Whether the magnifier feed is live, so the panel's Magnifier toggle can light up from the real
     * state of the capture rather than from a guess. Distinct from [recording]: the feed and a recording
     * are independent virtual displays on the same projection and either can be up without the other.
     */
    private val magnifierRunningState = MutableStateFlow(false)
    val magnifierRunning: StateFlow<Boolean> = magnifierRunningState.asStateFlow()

    /**
     * Whether the Instant Replay rolling buffer is live (§3.6), so the panel's Instant Replay toggle and
     * the recording service's notification decision read the real state of the capture rather than a guess.
     * A third independent virtual display on the same projection, peer to [magnifierRunning] and [recording]:
     * any of the three can be up without the others.
     */
    private val replayRunningState = MutableStateFlow(false)
    val replayRunning: StateFlow<Boolean> = replayRunningState.asStateFlow()

    /**
     * Whether the running buffer is paused by [ReplayThermalMachine] (§3.6) — the device went critically
     * hot and [ReplayBufferController.pause] stopped feeding the encoder until it cools. Distinct from
     * [replayRunning], which stays true across a thermal pause: the buffer is still armed and its display
     * still mirrored, it just is not retaining new footage. The in-game save pill reads this to disable the
     * save while paused, and the status chip to say "Paused: overheating" rather than the bare "Buffering".
     * Only meaningful while [replayRunning]; reset to false when the buffer stops.
     */
    private val replayPausedState = MutableStateFlow(false)
    val replayPaused: StateFlow<Boolean> = replayPausedState.asStateFlow()

    /**
     * Whether a [saveReplayClip] call is in flight (§3.6). The save stitches the retained segments off the
     * main thread, so it is not instant; the pill shows "Saving…" and drops a second tap while this is true,
     * which is the only guard against a double-save starting a second mux over the first. Latched true around
     * the delegation to [ReplayClipSaver] and cleared in a `finally` so a failed or cancelled save still
     * releases it.
     */
    private val replaySavingState = MutableStateFlow(false)
    val replaySaving: StateFlow<Boolean> = replaySavingState.asStateFlow()

    /**
     * The window, in seconds, the running buffer is currently holding (§3.6) — the real `windowSeconds` the
     * live [startReplayBuffer] was given, not the profile's stored preference, so the save pill's "Save last
     * N" names what pressing it would actually keep. One of `GameProfile.INSTANT_REPLAY_BUFFER_CHOICES`.
     * Holds [IDLE_REPLAY_WINDOW_SECONDS] while no buffer is running, where it is not shown.
     */
    private val replayWindowState = MutableStateFlow(IDLE_REPLAY_WINDOW_SECONDS)
    val replayWindowSeconds: StateFlow<Int> = replayWindowState.asStateFlow()

    /**
     * Per game-session Instant Replay tally, for the session summary (§3.6). Distinct from
     * [replayRunning], which is the instantaneous live/not-live state: these answer "did the buffer run
     * at any point during this game session" and "how many clips did the player keep from it", which is
     * what the finished session row records.
     *
     * Written from the recording service (the latch in [startReplayBuffer], the count in [saveReplayClip])
     * and reset from the coordinator ([beginReplaySession]); read from the coordinator when it closes the
     * row. Every one of those callers runs on the main thread today, but the summary has to keep reading
     * true once [saveReplayClip] moves onto an IO dispatcher the way its sibling capture calls already
     * have, so the two carry their own cross-thread guarantees — a `@Volatile` latch and an
     * [AtomicInteger] counter — rather than leaning on that confinement.
     */
    @Volatile
    private var replayUsedThisSession = false
    private val clipsSavedThisSession = AtomicInteger(0)

    /** Whether the rolling buffer ran at any point since the last [beginReplaySession]. */
    val replayUsedInSession: Boolean get() = replayUsedThisSession

    /** How many clips the player has saved since the last [beginReplaySession]. */
    val clipsSavedInSession: Int get() = clipsSavedThisSession.get()

    /**
     * Resets the session tally at the start of a game session. A buffer that is already running — armed
     * by hand from the panel before the game reached the foreground — counts as used from the first
     * second, so a clip saved off that carried-over buffer is still attributed to this session.
     */
    fun beginReplaySession() {
        replayUsedThisSession = replayRunningState.value
        clipsSavedThisSession.set(0)
    }

    /**
     * One capture at a time. A virtual display is a real system resource, and two
     * concurrent ones on a mid-range phone drop frames in the game rather than in
     * GameCore.
     */
    private val captureLock = Mutex()

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var recordingDisplay: VirtualDisplay? = null
    private var recordingFile: File? = null
    private var recordingStartedElapsed: Long = 0L

    // The magnifier feed's own resources, separate from the recorder's so stopping one never touches the
    // other. The frames are converted on [feedThread] rather than the main looper, because a full-screen
    // bitmap copy every frame on the main thread would jank the game the loupe is laid over.
    private var feedReader: ImageReader? = null
    private var feedDisplay: VirtualDisplay? = null
    private var feedThread: android.os.HandlerThread? = null
    private var feedLastFrameElapsed: Long = 0L

    // The Instant Replay rolling buffer's resources (§3.6), independent of both the recorder and the
    // magnifier feed. The buffer controller owns its own encoder and thread; this class owns only the
    // mirror VirtualDisplay hung on the buffer's input surface, plus the per-session segment store and
    // thermal collector. All null while the buffer is not running.
    private var replayController: ReplayBufferController? = null
    private var replayStore: ReplaySegmentStore? = null
    private var replayDisplay: VirtualDisplay? = null
    private var replayThermal: ReplayThermalCollector? = null

    /**
     * Stops everything if the user revokes the projection from the system UI.
     *
     * Without this the recorder would keep writing an mp4 that receives no frames, and the
     * file would be a valid container with a frozen first frame — a recording that looks
     * like it worked.
     */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseRecorder(keepFile = true)
            stopFrameFeed()
            stopReplayBuffer(discard = true)
            projection = null
            recordingState.value = RecordingState.IDLE
        }
    }

    // ------------------------------------------------------------------- consent

    /**
     * The Intent that opens the system's screen-capture consent dialog.
     *
     * Returned rather than launched: only an Activity can start it for a result, and
     * `CaptureConsentActivity` is the one place in GameCore that does.
     */
    fun consentIntent(): Intent? = try {
        projectionManager()?.createScreenCaptureIntent()
    } catch (error: Throwable) {
        null
    }

    fun isSupported(): Boolean = projectionManager() != null

    /** True while a granted projection is held, so the UI can skip the prompt. */
    fun hasProjection(): Boolean = projection != null

    /**
     * Takes the consent result and holds the projection.
     *
     * [resultCode] is checked against `Activity.RESULT_OK` first: a user who dismissed the
     * dialog produces a null data Intent on some builds and a non-OK code on all of them,
     * and passing that to `getMediaProjection` throws.
     */
    fun acceptConsent(resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) return false
        val manager = projectionManager() ?: return false
        return try {
            releaseProjection()
            val granted = manager.getMediaProjection(resultCode, data) ?: return false
            granted.registerCallback(projectionCallback, mainHandler())
            projection = granted
            true
        } catch (error: Throwable) {
            // Android 14+ throws here when no mediaProjection-typed foreground service is
            // running yet. The caller starts the service first; this is the honest failure
            // rather than a crash in the consent Activity.
            projection = null
            false
        }
    }

    /** Gives the projection up. Called when recording ends and when the overlay stops. */
    fun releaseProjection() {
        releaseRecorder(keepFile = true)
        stopFrameFeed()
        try {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        } catch (error: Throwable) {
            // Already dead. Nothing to report: the caller is giving it up either way.
        }
        projection = null
        recordingState.value = RecordingState.IDLE
    }

    // ---------------------------------------------------------------- screenshot

    /**
     * One frame of the screen, as a PNG.
     *
     * A virtual display is created at the panel's own resolution, one image is pulled from
     * an `ImageReader`, and both are torn down immediately — a projection left running is a
     * screen-recording indicator the user did not ask for.
     *
     * The row stride matters and is the usual bug here: `ImageReader` hands back a buffer
     * whose rows are padded to a hardware alignment, so a bitmap created at the requested
     * width is skewed. The padding is computed and the bitmap cropped back.
     */
    suspend fun takeScreenshot(): CaptureOutcome = captureLock.withLock {
        withContext(io) {
            val active = projection ?: return@withContext CaptureOutcome.NeedsConsent
            val reading = display.read()
            val width = reading.widthPixels
            val height = reading.heightPixels
            if (width <= 0 || height <= 0) {
                return@withContext CaptureOutcome.Failed(
                    "The screen size could not be read, so a screenshot could not be sized.",
                )
            }

            var reader: ImageReader? = null
            var virtual: VirtualDisplay? = null
            try {
                reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                virtual = active.createVirtualDisplay(
                    "GameCore-screenshot",
                    width,
                    height,
                    reading.densityDpi.coerceAtLeast(1),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    mainHandler(),
                ) ?: return@withContext CaptureOutcome.Failed(
                    "This device would not create a capture surface.",
                )

                val image = awaitImage(reader)
                    ?: return@withContext CaptureOutcome.Failed(
                        "No frame arrived from the screen within a second.",
                    )
                val bitmap = try {
                    bitmapFrom(image, width, height)
                } finally {
                    image.close()
                }
                writePng(bitmap)
            } catch (error: SecurityException) {
                // The token expired between the check and the call.
                projection = null
                CaptureOutcome.NeedsConsent
            } catch (error: Throwable) {
                CaptureOutcome.Failed("The screenshot could not be taken on this device.")
            } finally {
                try {
                    virtual?.release()
                } catch (error: Throwable) {
                    // Releasing twice is harmless; a failure here loses nothing.
                }
                try {
                    reader?.close()
                } catch (error: Throwable) {
                    // Same.
                }
            }
        }
    }

    /**
     * Polls the reader for up to a second.
     *
     * `acquireLatestImage` returns null until the first frame is composited into the virtual
     * display, which takes a frame or two after creation — an immediate read always returns
     * nothing. A poll rather than a listener because the caller is already suspended and a
     * listener would need its own thread to deliver on.
     */
    private suspend fun awaitImage(reader: ImageReader): android.media.Image? {
        val deadline = SystemClock.elapsedRealtime() + FRAME_WAIT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            val image = try {
                reader.acquireLatestImage()
            } catch (error: Throwable) {
                null
            }
            if (image != null) return image
            delay(FRAME_POLL_MILLIS)
        }
        return null
    }

    /** Copies the padded buffer into a bitmap of the real size. */
    private fun bitmapFrom(image: android.media.Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val rowPadding = plane.rowStride - plane.pixelStride * width
        val paddedWidth = width + rowPadding / plane.pixelStride.coerceAtLeast(1)
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        return if (paddedWidth == width) {
            padded
        } else {
            Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
        }
    }

    private fun writePng(bitmap: Bitmap): CaptureOutcome {
        val target = File(directoryFor(CaptureKind.SCREENSHOT), fileName(CaptureKind.SCREENSHOT))
        return try {
            FileOutputStream(target).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            CaptureOutcome.Saved(describe(target, CaptureKind.SCREENSHOT, null))
        } catch (error: Throwable) {
            bitmap.recycle()
            target.delete()
            CaptureOutcome.Failed("The screenshot could not be written to storage.")
        }
    }

    // ---------------------------------------------------------- screen extraction

    /**
     * Grabs one frame of the screen into [lastExtractedFrame] for Screen Extraction (§Screen-Extraction),
     * or reports why it could not.
     *
     * The capture mirrors [takeScreenshot] exactly — a virtual display at panel resolution, one image pulled
     * from an `ImageReader`, both torn down immediately — but the frame is *not* written to disk: it is handed
     * to the in-app cropper through [lastExtractedFrame], and only the region the user then chooses is written
     * by [saveExtractedBitmap]. One-shot: nothing here keeps the projection alive, so a projection started only
     * for an extraction does not outlive this call (the service stops once its last live capture is gone).
     *
     * Returns the screenshot path's [CaptureOutcome] vocabulary so the service can tell a revoked token
     * ([CaptureOutcome.NeedsConsent]) from a real failure, but success is [CaptureOutcome.Extracted] and
     * carries no file — the frame lands in the flow, which is the feedback, so the service does not toast it.
     */
    suspend fun captureFrameToMemory(): CaptureOutcome = captureLock.withLock {
        withContext(io) {
            val active = projection ?: return@withContext CaptureOutcome.NeedsConsent
            val reading = display.read()
            val width = reading.widthPixels
            val height = reading.heightPixels
            if (width <= 0 || height <= 0) {
                return@withContext CaptureOutcome.Failed(
                    "The screen size could not be read, so a frame could not be extracted.",
                )
            }
            var reader: ImageReader? = null
            var virtual: VirtualDisplay? = null
            try {
                reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                virtual = active.createVirtualDisplay(
                    "GameCore-extract",
                    width,
                    height,
                    reading.densityDpi.coerceAtLeast(1),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    mainHandler(),
                ) ?: return@withContext CaptureOutcome.Failed(
                    "This device would not create a capture surface.",
                )
                val image = awaitImage(reader)
                    ?: return@withContext CaptureOutcome.Failed(
                        "No frame arrived from the screen within a second.",
                    )
                val bitmap = try {
                    bitmapFrom(image, width, height)
                } finally {
                    image.close()
                }
                // Published, not written: the cropper reads this and the user picks the region to save.
                lastExtractedFrameState.value = bitmap
                CaptureOutcome.Extracted
            } catch (error: SecurityException) {
                // The token expired between the liveness check and the call.
                projection = null
                CaptureOutcome.NeedsConsent
            } catch (error: Throwable) {
                CaptureOutcome.Failed("A frame could not be extracted on this device.")
            } finally {
                try {
                    virtual?.release()
                } catch (error: Throwable) {
                    // Releasing twice is harmless; a failure here loses nothing.
                }
                try {
                    reader?.close()
                } catch (error: Throwable) {
                    // Same.
                }
            }
        }
    }

    /**
     * Crops [bitmap] to [cropPx] and writes it to the gallery, handing back a shareable `content://` URI, or
     * null on any failure (§Screen-Extraction).
     *
     * The view-model has already resolved the preview-space selection into bitmap pixels through
     * [com.gamecore.core.system.capture.CropMath] and clamped it to the frame, so [cropPx] is trusted to be
     * in-bounds; it is re-clamped here anyway, and a degenerate or whole-frame rect keeps the frame intact
     * rather than saving nothing. The write itself — the MediaStore-or-FileProvider two-path, the never-a-
     * fabricated-success contract — belongs to [ImageMediaStoreSaver]; this only applies the crop and delegates.
     *
     * The cropped bitmap is recycled once written, but only when it is a *distinct* object from [bitmap]:
     * the whole-frame case returns the caller's own frame, which the cropper is still showing, so recycling
     * it would blank the screen. Runs off the main thread so a large `createBitmap` copy never janks the UI.
     */
    suspend fun saveExtractedBitmap(bitmap: Bitmap, cropPx: CropRect): Uri? = withContext(io) {
        val cropped = cropOrWhole(bitmap, cropPx)
        try {
            imageSaver.save(cropped, EXTRACTION_BASE_NAME)
        } finally {
            if (cropped !== bitmap) {
                try {
                    cropped.recycle()
                } catch (error: Throwable) {
                    // A failed recycle leaks one bitmap to the collector, no worse than not recycling.
                }
            }
        }
    }

    /**
     * Drops the held extraction frame so a large full-screen bitmap is not retained in this singleton after
     * the cropper is gone. Left for the garbage collector rather than recycled: the composable that last drew
     * it may still be tearing down. Idempotent and safe to call when nothing was captured.
     */
    fun clearExtractedFrame() {
        lastExtractedFrameState.value = null
    }

    /**
     * Applies [cropPx] to [bitmap], re-clamped to the frame. Returns the original frame unchanged for a
     * degenerate rect or one that covers the whole frame, so a distinct cropped bitmap is created only when
     * there is a real sub-region — which is exactly the case [saveExtractedBitmap] is safe to recycle.
     */
    private fun cropOrWhole(bitmap: Bitmap, cropPx: CropRect): Bitmap {
        val left = cropPx.left.coerceIn(0, bitmap.width)
        val top = cropPx.top.coerceIn(0, bitmap.height)
        val right = cropPx.right.coerceIn(left, bitmap.width)
        val bottom = cropPx.bottom.coerceIn(top, bitmap.height)
        val cropWidth = right - left
        val cropHeight = bottom - top
        val wholeFrame = left == 0 && top == 0 &&
            cropWidth == bitmap.width && cropHeight == bitmap.height
        return if (cropWidth <= 0 || cropHeight <= 0 || wholeFrame) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
        }
    }

    // ----------------------------------------------------------------- recording

    /**
     * Starts recording, or explains why it cannot.
     *
     * The encoder is configured before the virtual display exists, because `MediaRecorder`
     * has to be `prepare()`d to produce the `Surface` the display draws into. A device that
     * refuses the requested size or bit rate throws from `prepare()`, and that is reported
     * as [CaptureOutcome.Unsupported] — it is a fact about the hardware encoder, not a bug.
     *
     * Dimensions are rounded to even numbers. H.264 requires it, and an odd width is the
     * other classic cause of a `prepare()` failure that looks inexplicable.
     */
    suspend fun startRecording(spec: RecordingSpec = RecordingSpec()): CaptureOutcome =
        captureLock.withLock {
            withContext(io) {
                if (recordingState.value.isRecording) {
                    return@withContext CaptureOutcome.Failed("A recording is already running.")
                }
                val active = projection ?: return@withContext CaptureOutcome.NeedsConsent
                val reading = display.read()
                if (reading.widthPixels <= 0 || reading.heightPixels <= 0) {
                    return@withContext CaptureOutcome.Failed(
                        "The screen size could not be read, so a recording could not be sized.",
                    )
                }
                val width = even(reading.widthPixels * spec.scalePercent / 100)
                val height = even(reading.heightPixels * spec.scalePercent / 100)
                val target = File(
                    directoryFor(CaptureKind.RECORDING),
                    fileName(CaptureKind.RECORDING),
                )

                val encoder = try {
                    buildRecorder(target, width, height, spec)
                } catch (error: Throwable) {
                    target.delete()
                    return@withContext CaptureOutcome.Unsupported(
                        "This device's encoder would not accept " +
                            "${width}×$height at ${spec.frameRate} fps.",
                    )
                }

                try {
                    recordingDisplay = active.createVirtualDisplay(
                        "GameCore-recording",
                        width,
                        height,
                        reading.densityDpi.coerceAtLeast(1),
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        encoder.surface,
                        null,
                        mainHandler(),
                    ) ?: throw IllegalStateException("no virtual display")
                    encoder.start()
                } catch (error: SecurityException) {
                    releaseRecorder(keepFile = false)
                    projection = null
                    return@withContext CaptureOutcome.NeedsConsent
                } catch (error: Throwable) {
                    releaseRecorder(keepFile = false)
                    return@withContext CaptureOutcome.Failed(
                        "The recording could not be started on this device.",
                    )
                }

                recorder = encoder
                recordingFile = target
                recordingStartedElapsed = SystemClock.elapsedRealtime()
                val startedAt = System.currentTimeMillis()
                recordingState.value = RecordingState(
                    isRecording = true,
                    startedAtMillis = startedAt,
                    outputPath = target.absolutePath,
                )
                CaptureOutcome.Recording(startedAt)
            }
        }

    /**
     * Stops recording and returns the file.
     *
     * `stop()` throws when fewer than a handful of frames reached the encoder — the mp4 has
     * no moov atom and is unplayable. That file is deleted rather than handed over: a
     * zero-length recording in the list would be a capture that claims to exist.
     */
    suspend fun stopRecording(): CaptureOutcome = captureLock.withLock {
        withContext(io) {
            if (!recordingState.value.isRecording) {
                return@withContext CaptureOutcome.Failed("No recording is running.")
            }
            val file = recordingFile
            val elapsed = SystemClock.elapsedRealtime() - recordingStartedElapsed
            val stopped = try {
                recorder?.stop()
                true
            } catch (error: Throwable) {
                false
            }
            releaseRecorder(keepFile = stopped)
            recordingState.value = RecordingState.IDLE

            when {
                file == null -> CaptureOutcome.Failed("The recording file was lost.")
                !stopped -> {
                    file.delete()
                    CaptureOutcome.Failed(
                        "The recording was too short to save. Record for at least a second.",
                    )
                }
                !file.exists() || file.length() <= 0L -> {
                    file.delete()
                    CaptureOutcome.Failed("The recording produced no data on this device.")
                }
                else -> CaptureOutcome.Saved(
                    describe(file, CaptureKind.RECORDING, elapsed),
                )
            }
        }
    }

    // ------------------------------------------------------------- magnifier feed

    /**
     * Starts a continuous feed of screen frames for the pinned magnifier (§13), or returns false.
     *
     * A second virtual display on the held projection, independent of any recording: it mirrors the
     * display into an [ImageReader] whose frames become [magnifierFrame]. False when there is no live
     * projection — the caller then routes through consent exactly as the screenshot and recording paths
     * do — or when the display size cannot be read or the device refuses the capture surface. Idempotent:
     * a feed already running is left alone and reported as success.
     *
     * Not `suspend` and holds no lock, unlike [takeScreenshot] and [startRecording]: the feed is meant to
     * run *alongside* those, so blocking on [captureLock] for its whole lifetime would wedge every other
     * capture while the magnifier is pinned. Setup is a couple of cheap system calls, done inline.
     */
    fun startFrameFeed(): Boolean {
        val active = projection ?: return false
        if (magnifierRunningState.value) return true
        val metrics = display.mirrorMetrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width <= 0 || height <= 0) return false
        return try {
            val thread = android.os.HandlerThread("GameCore-magnifier-feed").apply { start() }
            feedThread = thread
            val handler = Handler(thread.looper)
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, FEED_BUFFERS)
            feedReader = reader
            reader.setOnImageAvailableListener({ r -> onFeedImage(r, width, height) }, handler)
            feedDisplay = active.createVirtualDisplay(
                "GameCore-magnifier",
                width,
                height,
                metrics.densityDpi.coerceAtLeast(1),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            ) ?: run {
                stopFrameFeed()
                return false
            }
            feedLastFrameElapsed = 0L
            magnifierRunningState.value = true
            true
        } catch (error: SecurityException) {
            // The token expired between the liveness check and the call.
            projection = null
            stopFrameFeed()
            false
        } catch (error: Throwable) {
            stopFrameFeed()
            false
        }
    }
    /**
     * Stops the feed and frees its display, reader and thread. Safe to call when nothing is running.
     *
     * Clears [magnifierFrame] to null so the overlay draws nothing the instant the feed goes down, rather
     * than holding the last frame frozen on screen until a stale bitmap is noticed.
     */
    fun stopFrameFeed() {
        magnifierRunningState.value = false
        try {
            feedDisplay?.release()
        } catch (error: Throwable) {
            // Gone either way.
        }
        feedDisplay = null
        try {
            feedReader?.setOnImageAvailableListener(null, null)
            feedReader?.close()
        } catch (error: Throwable) {
            // Same.
        }
        feedReader = null
        try {
            feedThread?.quitSafely()
        } catch (error: Throwable) {
            // Same.
        }
        feedThread = null
        magnifierFrameState.value = null
    }
    /**
     * Turns one delivered frame into [magnifierFrame], on the feed thread.
     *
     * `acquireLatestImage` drops any backlog and returns the newest frame; the returned image is always
     * closed so the reader's two buffers are freed even on a frame this drops. Throttled to
     * [FEED_MIN_INTERVAL_MILLIS] so a 120 Hz display does not mean 120 full-screen bitmap copies a second.
     * A conversion that throws costs one frame, not the feed.
     */
    private fun onFeedImage(reader: ImageReader, width: Int, height: Int) {
        val image = try {
            reader.acquireLatestImage()
        } catch (error: Throwable) {
            null
        } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - feedLastFrameElapsed < FEED_MIN_INTERVAL_MILLIS) return
            feedLastFrameElapsed = now
            magnifierFrameState.value = bitmapFrom(image, width, height)
        } catch (error: Throwable) {
            // A dropped frame is not worth taking the feed down for.
        } finally {
            try {
                image.close()
            } catch (error: Throwable) {
                // Already closed.
            }
        }
    }

    // ------------------------------------------------------------- instant replay
    /**
     * Starts the Instant Replay rolling buffer (§3.6), or returns false.
     *
     * A third virtual display on the held projection, peer to the recorder and the magnifier feed and
     * independent of both: it mirrors the display onto the encoder-input surface the per-session
     * [ReplayBufferController] prepares, and the controller keeps only the last [windowSeconds] of footage
     * as a ring of short mp4 segments in the private cache. False when there is no live projection — the
     * caller then routes through consent as the other capture paths do — or when the display size cannot be
     * read or the device's encoder refuses the buffer. Idempotent: a buffer already running is reported as
     * success.
     *
     * Not `suspend` and holds no lock, like [startFrameFeed] and for the same reason: the buffer runs
     * alongside a recording or the magnifier, so blocking [captureLock] for its lifetime would wedge every
     * other capture. Each field is assigned as its resource is built so any failure — including a mid-setup
     * throw — is undone by [stopReplayBuffer], which tears down exactly what exists.
     */
    fun startReplayBuffer(windowSeconds: Int, quality: RecordingQuality): Boolean {
        val active = projection ?: return false
        if (replayRunningState.value) return true
        val metrics = display.mirrorMetrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width <= 0 || height <= 0) return false
        return try {
            val store = ReplaySegmentStore(context.cacheDir)
            replayStore = store
            val controller = ReplayBufferController(
                context = context,
                store = store,
                windowSeconds = windowSeconds,
                quality = quality,
                clock = replayClock,
                onError = {},
            )
            replayController = controller
            val surface = controller.prepare(width, height) ?: run {
                stopReplayBuffer(discard = true)
                return false
            }
            replayDisplay = active.createVirtualDisplay(
                "GameCore-replay",
                width,
                height,
                metrics.densityDpi.coerceAtLeast(1),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                mainHandler(),
            ) ?: run {
                stopReplayBuffer(discard = true)
                return false
            }
            if (!controller.start()) {
                stopReplayBuffer(discard = true)
                return false
            }
            val thermal = ReplayThermalCollector(
                monitor = performanceMonitor,
                scope = scope,
                onDecision = { decision ->
                    when (decision) {
                        is ReplayThermalDecision.Pause -> {
                            controller.pause()
                            replayPausedState.value = true
                        }
                        is ReplayThermalDecision.Resume -> {
                            controller.resume()
                            replayPausedState.value = false
                        }
                        ReplayThermalDecision.NoChange -> Unit
                    }
                },
            )
            replayThermal = thermal
            thermal.start()
            replayRunningState.value = true
            replayWindowState.value = windowSeconds
            // The session summary (§3.6) records that replay ran even if it is stopped again before the
            // game exits, so the flag latches here and is only cleared by the next beginReplaySession.
            replayUsedThisSession = true
            true
        } catch (error: SecurityException) {
            // The token expired between the liveness check and the call.
            projection = null
            stopReplayBuffer(discard = true)
            false
        } catch (error: Throwable) {
            stopReplayBuffer(discard = true)
            false
        }
    }

    /**
     * Stops the rolling buffer and frees its display, encoder and thermal collector. Safe to call when
     * nothing is running.
     *
     * [discard] deletes the buffered segments through [ReplaySegmentStore.cleanupAll]; false keeps the
     * files on disk so a save can still reach them (the service passes false on its own teardown, true on
     * a revoked projection where the footage must not be orphaned). The controller's own `stop()` never
     * deletes files, so the choice is made here.
     */
    fun stopReplayBuffer(discard: Boolean) {
        try {
            replayThermal?.stop()
        } catch (error: Throwable) {
            // Gone either way.
        }
        replayThermal = null
        try {
            replayDisplay?.release()
        } catch (error: Throwable) {
            // Gone either way.
        }
        replayDisplay = null
        try {
            replayController?.stop()
        } catch (error: Throwable) {
            // Gone either way.
        }
        replayController = null
        if (discard) {
            try {
                replayStore?.cleanupAll()
            } catch (error: Throwable) {
                // A leftover cache file is reclaimed by the OS or the next start.
            }
        }
        replayStore = null
        replayRunningState.value = false
        replayPausedState.value = false
        replayWindowState.value = IDLE_REPLAY_WINDOW_SECONDS
    }

    /**
     * Stitches the retained buffer into one clip in the gallery, or reports why it could not.
     *
     * Delegates to the injected [ReplayClipSaver]; this only supplies the currently-retained segments,
     * which the store already keeps ordered by capture time and trimmed to the window by the ring. Returns
     * [ReplaySaveResult.Failed] with [ReplaySaveResult.Reason.NoSegments] when the buffer is not running.
     */
    suspend fun saveReplayClip(displayName: String): ReplaySaveResult {
        val store = replayStore ?: return ReplaySaveResult.Failed(ReplaySaveResult.Reason.NoSegments)
        replaySavingState.value = true
        try {
            val result = clipSaver.save(store.current(), displayName)
            // Count only clips the pipeline confirmed on disk, so the session summary's tally matches what
            // is actually in the gallery rather than the number of times the player tapped save.
            if (result is ReplaySaveResult.Saved) clipsSavedThisSession.incrementAndGet()
            return result
        } finally {
            // Cleared on success, failure and cancellation alike, so a dropped save never wedges the pill
            // in its "Saving…" state. CancellationException propagates through the finally uncaught (§style).
            replaySavingState.value = false
        }
    }

    /**
     * Configures the encoder.
     *
     * `setVideoEncodingBitRate` and `setVideoFrameRate` are requests. What the hardware
     * actually delivers is not readable back from `MediaRecorder`, so the saved capture
     * carries the file's real size and duration and the UI reports those rather than the
     * preset's advertised figures.
     */
    private fun buildRecorder(
        target: File,
        width: Int,
        height: Int,
        spec: RecordingSpec,
    ): MediaRecorder {
        @Suppress("DEPRECATION")
        val encoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        return encoder.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(width, height)
            setVideoFrameRate(spec.frameRate)
            setVideoEncodingBitRate(spec.bitRate)
            setOutputFile(target.absolutePath)
            prepare()
        }
    }

    /** Tears the encoder and its display down, in the order that does not deadlock. */
    private fun releaseRecorder(keepFile: Boolean) {
        try {
            recordingDisplay?.release()
        } catch (error: Throwable) {
            // The display is gone either way.
        }
        recordingDisplay = null
        try {
            recorder?.reset()
            recorder?.release()
        } catch (error: Throwable) {
            // Same.
        }
        recorder = null
        if (!keepFile) {
            try {
                recordingFile?.delete()
            } catch (error: Throwable) {
                // A leftover partial file is preferable to a crash here.
            }
        }
        recordingFile = if (keepFile) recordingFile else null
    }

    // ------------------------------------------------------------------- library

    /** Everything captured so far, newest first, for the tools screen. */
    suspend fun captures(): List<SavedCapture> = withContext(io) {
        CaptureKind.entries.flatMap { kind ->
            directoryFor(kind).listFiles()
                ?.filter { it.isFile && it.extension.equals(kind.extension, ignoreCase = true) }
                ?.map { describe(it, kind, null) }
                .orEmpty()
        }.sortedByDescending { it.createdAtMillis }
    }

    suspend fun delete(capture: SavedCapture): Boolean = withContext(io) {
        val file = File(capture.absolutePath)
        // Confined to GameCore's own capture directories, so a path from a stale UI state
        // cannot be used to delete anything else.
        val allowed = CaptureKind.entries.any { file.parentFile == directoryFor(it) }
        allowed && try {
            file.delete()
        } catch (error: Throwable) {
            false
        }
    }

    /**
     * A content URI for sharing.
     *
     * `FileProvider`, not a `file://` path: a file URI needs a storage permission and throws
     * `FileUriExposedException` on anything since Android 7. The receiving app gets a
     * one-shot read grant on this file and nothing else.
     */
    fun shareUri(capture: SavedCapture): Uri? = try {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(capture.absolutePath),
        )
    } catch (error: Throwable) {
        null
    }

    // ----------------------------------------------------------------- internals

    private fun describe(file: File, kind: CaptureKind, durationMillis: Long?): SavedCapture =
        SavedCapture(
            absolutePath = file.absolutePath,
            fileName = file.name,
            sizeBytes = file.length(),
            createdAtMillis = file.lastModified(),
            kind = kind,
            durationMillis = durationMillis,
        )

    private fun directoryFor(kind: CaptureKind): File {
        val name = when (kind) {
            CaptureKind.SCREENSHOT -> "captures"
            CaptureKind.RECORDING -> "recordings"
        }
        return File(context.filesDir, name).apply { mkdirs() }
    }

    /** Timestamped, so two captures in the same second cannot overwrite each other. */
    private fun fileName(kind: CaptureKind): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        return "gamecore-${kind.name.lowercase(Locale.US)}-$stamp.${kind.extension}"
    }

    private fun projectionManager(): MediaProjectionManager? = try {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
    } catch (error: Throwable) {
        null
    }

    /**
     * The virtual-display and projection callbacks are delivered on this handler's looper.
     * `MediaProjection.registerCallback` rejects a null handler on some builds, so the main
     * looper is passed explicitly rather than left to the platform's default.
     */
    private fun mainHandler(): Handler = Handler(Looper.getMainLooper())

    private fun even(value: Int): Int = (value - value % 2).coerceAtLeast(2)

    private companion object {
        /** Long enough for the first composited frame on a slow device. */
        const val FRAME_WAIT_MILLIS = 1_000L
        const val FRAME_POLL_MILLIS = 40L

        /**
         * The preferred base name for a saved extraction. [ImageMediaStoreSaver] sanitizes it and appends its
         * own timestamp, so this is only the human-readable stem the gallery file starts with.
         */
        const val EXTRACTION_BASE_NAME = "extract"

        /**
         * The window shown while no buffer is running — the idle placeholder for [replayWindowSeconds],
         * never displayed (the save pill is up only while buffering, when the flow holds the real window).
         * Matches `GameProfile.instantReplayBufferSeconds`'s default so the two agree if it is ever read
         * before the first start.
         */
        const val IDLE_REPLAY_WINDOW_SECONDS = 30

        /** Two buffers for the feed reader: one being read while the next is composited. */
        const val FEED_BUFFERS = 2

        /**
         * Floor on the gap between magnifier frames — about 15 a second. Enough to read a moving screen
         * through the loupe, and low enough that a full-screen bitmap copy per frame is not what heats
         * the phone. On-device thermals are the part of §13 a JVM cannot check, so this errs conservative.
         */
        const val FEED_MIN_INTERVAL_MILLIS = 66L
    }
}
