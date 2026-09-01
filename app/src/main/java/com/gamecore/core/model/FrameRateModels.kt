package com.gamecore.core.model

import com.gamecore.core.common.Observed

/**
 * Whether a frame-rate reading is possible on this device at all, and by what route.
 *
 * This type exists because of the single most tempting lie a gaming utility can tell.
 * Every "FPS meter" on the store shows a number while a game runs; almost none of
 * them are measuring the game. What they measure is one of three things:
 *
 *  1. The rate their *own* overlay window is being composited at, which is GameCore's
 *     window, not the game's, and which tracks the display's refresh rate rather than
 *     the game's render loop. A game rendering 34 fps on a 120 Hz panel yields "120".
 *  2. The display's current mode, read from `Display.getRefreshRate()`, relabelled
 *     "FPS". That is the panel's rate, and it is not a frame rate at all.
 *  3. Nothing — a plausible-looking number synthesised from CPU load.
 *
 * All three are inventions, and the specification rejects all three by name. Android
 * exposes no public API by which one app can observe another app's frame production.
 * `FrameMetrics` and `Choreographer` are per-window and only ever see our own window.
 * `dumpsys gfxinfo <pkg> framestats` returns real per-frame timestamps for the named
 * package, but only for content drawn through HWUI: a Unity, Unreal or any other
 * engine rendering into a `SurfaceView` from native code bypasses HWUI entirely and
 * its framestats section comes back empty. `dumpsys SurfaceFlinger --latency` is
 * whole-device and its format varies by vendor, with several OEMs shipping one that
 * is empty.
 *
 * So the answer is frequently "not on this device", and that is what gets shown.
 * [Unavailable] carries the reason, and the UI prints it instead of a number.
 */
sealed interface FrameRateCapability {

    /**
     * A real per-frame source exists for the named package.
     *
     * Only [GfxInfoFrameStats]-backed: real frame timestamps from the platform's own
     * profiler, for a game that draws through HWUI. Needs Shizuku, because
     * `dumpsys gfxinfo` for another package is not readable by an ordinary app.
     */
    data class GameFrameStats(val packageName: String) : FrameRateCapability

    /**
     * Frame timing is available for GameCore's own overlay window only.
     *
     * Present on every device, and **not** usable as a game frame rate — it is listed
     * here so the capability screen can explain why the honest answer is still "no".
     * Nothing in the app reports this as FPS.
     */
    data object OwnWindowOnly : FrameRateCapability

    /** No usable source. [reason] is shown to the user verbatim. */
    data class Unavailable(val reason: String) : FrameRateCapability

    /** True only for a source that measures the game itself. */
    val canMeasureGame: Boolean get() = this is GameFrameStats

    val explanation: String
        get() = when (this) {
            is GameFrameStats ->
                "Frame timing for this game is available from Android's own rendering " +
                    "profiler, through Shizuku."
            is OwnWindowOnly ->
                "Android only exposes frame timing for an app's own windows. GameCore can " +
                    "measure its overlay, which is not the game's frame rate, so it does not " +
                    "report one."
            is Unavailable -> reason
        }

    companion object {
        const val NEEDS_SHIZUKU =
            "Reading another app's frame timing needs ADB-level access. With Shizuku running, " +
                "GameCore can read it for games that draw through Android's UI pipeline."

        const val NATIVE_RENDERER =
            "This game renders directly to the GPU rather than through Android's UI pipeline, " +
                "so the platform exposes no frame timing for it. No app can measure its frame " +
                "rate without root or an in-game overlay hook."

        const val NO_GAME_SELECTED =
            "Frame timing is read per game. Start a game with a GameCore profile to see whether " +
                "this device exposes it."
    }
}

/**
 * A frame-rate measurement, derived from real frame timestamps and nothing else.
 *
 * [Observed] is not used for the rate itself: this whole type only exists when a
 * measurement was made, and its absence is expressed by the pipeline returning null
 * or a [FrameRateCapability.Unavailable] instead. That is deliberate — it makes
 * "there is no frame rate" unrepresentable as "a FrameRateSample with 0 fps".
 *
 * [onePercentLowFps] is included because it is the figure that actually corresponds
 * to what a player notices. An average of 60 with a one-percent low of 22 stutters
 * visibly; an average of 45 with a low of 43 feels smooth. It is computed from the
 * slowest 1% of real frame intervals in the window, not modelled.
 */
data class FrameRateSample(
    val averageFps: Float,
    val onePercentLowFps: Float?,
    /** How many real frames the figures were computed from. */
    val frameCount: Int,
    val windowMillis: Long,
    /** Frames whose total duration exceeded the display's frame budget. */
    val jankFrames: Int,
) {
    val jankPercent: Float
        get() = if (frameCount <= 0) 0f else (jankFrames.toFloat() / frameCount.toFloat()) * 100f

    /**
     * Whether enough frames were seen for the figures to mean anything. Below this a
     * single slow frame dominates the one-percent low.
     */
    val isReliable: Boolean get() = frameCount >= MIN_FRAMES

    companion object {
        const val MIN_FRAMES = 20
    }
}

/**
 * One frame's timing, as `dumpsys gfxinfo … framestats` reports it.
 *
 * Nanosecond timestamps straight from the platform. Kept as a distinct type from
 * [FrameRateSample] so the parsing step and the aggregation step are separately
 * testable, and so a malformed dump fails at parse time rather than producing a
 * frame rate from partial data.
 */
data class FrameTiming(
    val intendedVsyncNanos: Long,
    val frameCompletedNanos: Long,
) {
    val totalDurationNanos: Long get() = frameCompletedNanos - intendedVsyncNanos

    val isPlausible: Boolean
        get() = totalDurationNanos in 1..MAX_PLAUSIBLE_FRAME_NANOS

    companion object {
        /** One second. A "frame" slower than this is a dump artefact, not a frame. */
        const val MAX_PLAUSIBLE_FRAME_NANOS = 1_000_000_000L
    }
}
