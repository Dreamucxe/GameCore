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
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.model.CaptureKind
import com.gamecore.core.model.CaptureOutcome
import com.gamecore.core.model.RecordingSpec
import com.gamecore.core.model.RecordingState
import com.gamecore.core.model.SavedCapture
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
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
) {

    private val recordingState = MutableStateFlow(RecordingState.IDLE)

    /** Observed by the panel button and by the recording service's notification. */
    val recording: StateFlow<RecordingState> = recordingState.asStateFlow()

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
    }
}
