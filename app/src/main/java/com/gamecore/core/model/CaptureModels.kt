package com.gamecore.core.model

/**
 * What a screenshot or a screen recording did.
 *
 * Screen capture is the one gaming tool with no standard-Android shortcut: an app cannot
 * read the framebuffer, and `View`-level capture only ever draws GameCore's own overlay
 * windows — a "screenshot" made that way would be a picture of the HUD on a transparent
 * background, which is why that path does not exist here. The only honest mechanism is
 * `MediaProjection`, and it costs a system consent dialog every session.
 *
 * [NeedsConsent] therefore is not an error case. It is the normal first state, and the UI
 * turns it into the consent prompt rather than into a failure message.
 */
sealed interface CaptureOutcome {

    /** A file exists on disk and can be shared. */
    data class Saved(val capture: SavedCapture) : CaptureOutcome

    /** Recording started and is running. */
    data class Recording(val startedAtMillis: Long) : CaptureOutcome

    /** The user has not granted a projection session, or the last one was revoked. */
    data object NeedsConsent : CaptureOutcome

    /** This build has no `MediaProjection` service, or the encoder refused the size. */
    data class Unsupported(val detail: String) : CaptureOutcome

    /** A genuine failure — no frame arrived, the encoder threw, the disk was full. */
    data class Failed(val detail: String) : CaptureOutcome

    val isSuccess: Boolean get() = this is Saved || this is Recording

    val message: String
        get() = when (this) {
            is Saved -> "Saved ${capture.fileName}"
            is Recording -> "Recording"
            NeedsConsent -> "Screen capture needs your permission each time GameCore starts."
            is Unsupported -> detail
            is Failed -> detail
        }
}

/**
 * A capture on disk.
 *
 * A path and four facts, not a `File` and not a `Uri` — §24A.2. The UI shows a name, a
 * size and a timestamp, and asks the capture layer for a share URI when the user taps
 * share; handing a `Uri` to a Composable would put URI permission grants one call away
 * from the presentation layer.
 */
data class SavedCapture(
    val absolutePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val createdAtMillis: Long,
    val kind: CaptureKind,
    /** Present for recordings only; a screenshot has no duration. */
    val durationMillis: Long? = null,
) {
    val sizeLabel: String
        get() = when {
            sizeBytes >= 1_048_576L -> "%.1f MB".format(sizeBytes / 1_048_576f)
            sizeBytes >= 1_024L -> "${sizeBytes / 1_024L} KB"
            else -> "$sizeBytes B"
        }

    val durationLabel: String?
        get() = durationMillis?.let {
            val totalSeconds = it / 1_000L
            "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
        }
}

enum class CaptureKind(val label: String, val extension: String, val mimeType: String) {
    SCREENSHOT("Screenshot", "png", "image/png"),
    RECORDING("Recording", "mp4", "video/mp4"),
}

/**
 * What to ask the encoder for.
 *
 * [scalePercent] exists because a 1080×2400 panel at 60 fps is about 12 MB per second of
 * H.264 and phones record games for minutes at a time. Scaling is done by the virtual
 * display, so it costs nothing at encode time.
 *
 * There is no audio option, and its absence is deliberate rather than unfinished. Capturing
 * the game's own audio needs `AudioPlaybackCaptureConfiguration` (API 29+) fed through an
 * `AudioRecord` and muxed by hand — `MediaRecorder` cannot take it — and any app is free to
 * mark its audio uncapturable, which most media apps and some games do. Microphone audio is
 * possible but would mean holding RECORD_AUDIO, which GameCore does not request. A switch
 * labelled "record audio" that produced a silent track on half the games it was used with
 * would be exactly the kind of control this app refuses to ship, so the recording is video
 * and the UI says so.
 *
 * Nothing here is a promise either. The encoder decides what it will accept, and
 * [com.gamecore.core.system.ScreenCaptureController] reports the size it actually got
 * rather than the size asked for — a recording that silently came out at half the requested
 * frame rate is the same class of lie as a refresh-rate button that does nothing.
 */
data class RecordingSpec(
    val quality: RecordingQuality = RecordingQuality.BALANCED,
) {
    val scalePercent: Int get() = quality.scalePercent
    val frameRate: Int get() = quality.frameRate
    val bitRate: Int get() = quality.bitRate
}

/**
 * The three presets, with their real costs stated.
 *
 * Bit rates are per-second byte budgets the encoder is asked to hit, not guarantees:
 * hardware encoders overshoot on motion and undershoot on a static menu.
 */
enum class RecordingQuality(
    val label: String,
    val scalePercent: Int,
    val frameRate: Int,
    val bitRate: Int,
    val explanation: String,
) {
    HIGH(
        label = "High",
        scalePercent = 100,
        frameRate = 60,
        bitRate = 12_000_000,
        explanation = "Full panel resolution at 60 fps. About 90 MB per minute.",
    ),
    BALANCED(
        label = "Balanced",
        scalePercent = 75,
        frameRate = 30,
        bitRate = 6_000_000,
        explanation = "Three-quarter resolution at 30 fps. About 45 MB per minute.",
    ),
    SMALL(
        label = "Small",
        scalePercent = 50,
        frameRate = 30,
        bitRate = 2_500_000,
        explanation = "Half resolution at 30 fps. About 19 MB per minute.",
    ),
}

/** Whether a recording is running, for the panel button and the service notification. */
data class RecordingState(
    val isRecording: Boolean = false,
    val startedAtMillis: Long = 0L,
    val outputPath: String? = null,
) {
    fun elapsedMillis(nowMillis: Long): Long =
        if (isRecording && startedAtMillis > 0L) nowMillis - startedAtMillis else 0L

    companion object {
        val IDLE = RecordingState()
    }
}
