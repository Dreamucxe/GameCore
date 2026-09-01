package com.gamecore.core.system

import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.FrameRateCapability
import com.gamecore.core.model.FrameRateSample
import com.gamecore.core.model.FrameTiming
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The frame-rate probe, which mostly answers "no".
 *
 * §24B of GameCore's specification singles this feature out: reliable per-frame FPS is
 * generally unavailable through public APIs without root or instrumentation, it varies by
 * OEM and GPU, and the app must detect whether a reliable signal exists and otherwise say
 * "Not available on this device" rather than estimating. So the shape of this class is
 * inverted from the usual: [capabilityFor] is the primary entry point, and [sample] can
 * only be called meaningfully once it has returned
 * [FrameRateCapability.GameFrameStats].
 *
 * The one honest source is `dumpsys gfxinfo <package> framestats` — real nanosecond
 * timestamps recorded by the platform's own renderer for the named package. It has two
 * hard limits, both of which are reported rather than worked around:
 *
 *  * It needs ADB-level access, because an ordinary app cannot dump another package.
 *  * It only covers content drawn through HWUI. A Unity, Unreal or any other engine
 *    rendering into a `SurfaceView` from native code bypasses HWUI, and its framestats
 *    section comes back empty — which is the majority of real games. That empty section
 *    becomes [FrameRateCapability.Unavailable] with
 *    [FrameRateCapability.NATIVE_RENDERER] as the reason, and no number is shown.
 *
 * What this class will not do, ever: read GameCore's own window's composition rate and
 * label it the game's, read `Display.getRefreshRate()` and call it FPS, or derive
 * anything from CPU load. `Choreographer` and `FrameMetrics` are per-window and only ever
 * see our own overlay; the capability for that case is
 * [FrameRateCapability.OwnWindowOnly], which exists so the UI can explain why the answer
 * is still no.
 */
@Singleton
class FrameRateProbe @Inject constructor(
    private val shell: ElevatedShell,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Whether this device exposes frame timing for [packageName], determined by actually
     * trying it.
     *
     * There is no way to answer this from device properties, an API level or a chipset
     * name — whether a given game draws through HWUI is a fact about that game's engine.
     * So the dump is taken and parsed, and the result of the parse *is* the answer. One
     * round trip, done when the user opens the performance screen or a session starts,
     * not per tick.
     */
    suspend fun capabilityFor(packageName: String?): FrameRateCapability = withContext(io) {
        if (packageName.isNullOrBlank()) {
            return@withContext FrameRateCapability.Unavailable(
                FrameRateCapability.NO_GAME_SELECTED,
            )
        }
        if (!shell.isAvailable()) {
            return@withContext FrameRateCapability.Unavailable(FrameRateCapability.NEEDS_SHIZUKU)
        }
        val command = ShellCommand.frameStats(packageName)
            ?: return@withContext FrameRateCapability.Unavailable(
                "\"$packageName\" is not a valid package name.",
            )

        val result = shell.execute(command)
        if (!result.isSuccess) {
            return@withContext FrameRateCapability.Unavailable(
                "Android's rendering profiler could not be read for this game on this device.",
            )
        }
        when (DumpsysParsers.parseFrameStats(result.stdout)) {
            is Observed.Value -> FrameRateCapability.GameFrameStats(packageName)
            // The empty-section case, which is the normal outcome for a native renderer.
            else -> FrameRateCapability.Unavailable(FrameRateCapability.NATIVE_RENDERER)
        }
    }

    /**
     * Whether the device exposes whole-device frame timing at all.
     *
     * Used only by the capability screen, to distinguish "this device has no frame data
     * whatsoever" from "this device has it and this particular game does not draw through
     * the pipeline that exposes it". The boolean never becomes a frame rate: see
     * [DumpsysParsers.hasUsableFrameLatency] for why a SurfaceFlinger latency dump cannot
     * be attributed to the game.
     */
    suspend fun deviceExposesFrameTiming(): Boolean = withContext(io) {
        if (!shell.isAvailable()) return@withContext false
        val result = shell.execute(ShellCommand.SurfaceFlingerLatency)
        result.isSuccess && DumpsysParsers.hasUsableFrameLatency(result.stdout)
    }

    /**
     * One measurement window for a package already known to be measurable.
     *
     * Returns null rather than a zero sample when there is nothing to measure, so a
     * caller cannot accidentally render "0 fps" for a game the platform is silent about.
     *
     * The dump is a *ring buffer* — `gfxinfo` keeps roughly the last 120 frames and
     * `framestats` prints what is in it — so consecutive calls overlap. That is why
     * [lastFrameSeenNanos] is tracked: only frames newer than the last window are
     * aggregated, which turns overlapping dumps into a non-overlapping series and stops
     * the same slow frame being counted as jank in five successive readings.
     */
    suspend fun sample(packageName: String): FrameRateSample? = withContext(io) {
        val command = ShellCommand.frameStats(packageName) ?: return@withContext null
        val result = shell.execute(command)
        if (!result.isSuccess) return@withContext null

        val frames = DumpsysParsers.parseFrameStats(result.stdout).valueOrNull
            ?: return@withContext null

        val fresh = frames.filter { it.intendedVsyncNanos > lastFrameSeenNanos }
        val window = if (fresh.size >= MIN_FRESH_FRAMES) fresh else frames
        lastFrameSeenNanos = frames.lastOrNull()?.intendedVsyncNanos ?: lastFrameSeenNanos

        aggregate(window)
    }

    /** Forgets the frame cursor, so the next [sample] takes the whole buffer. */
    fun reset() {
        lastFrameSeenNanos = 0L
    }

    private var lastFrameSeenNanos: Long = 0L

    // ------------------------------------------------------------------ aggregation

    /**
     * Frame timings to a rate.
     *
     * The average is frames divided by the span they cover — not the mean of the
     * per-frame durations, which is a different quantity. A frame's `totalDuration` is
     * how long that frame took to *produce*, and frames pipeline: on a 60 Hz display a
     * game can hold 60 fps with every frame taking 14 ms of work, and averaging the work
     * would report 71 fps. The interval between successive vsyncs is what the player sees.
     *
     * The one-percent low uses the slowest 1% of those intervals, which is the figure
     * that corresponds to perceived stutter — an average of 60 with a one-percent low of
     * 22 stutters visibly. It needs enough frames for "1%" to mean at least one frame,
     * and is null below that rather than being silently redefined as the single worst
     * frame.
     *
     * Jank is counted from `totalDurationNanos` against the frame budget implied by the
     * measured rate, matching what the platform's own `gfxinfo` summary counts.
     */
    internal fun aggregate(frames: List<FrameTiming>): FrameRateSample? {
        if (frames.size < 2) return null
        val ordered = frames.sortedBy { it.intendedVsyncNanos }

        val intervals = ArrayList<Long>(ordered.size - 1)
        for (i in 1 until ordered.size) {
            val delta = ordered[i].intendedVsyncNanos - ordered[i - 1].intendedVsyncNanos
            // A zero interval means two frames shared a vsync, which the dump does report;
            // an interval over a second means the game was paused inside the window, and
            // including it would halve the average for a gap the player did not see as a
            // frame rate drop.
            if (delta in 1..MAX_INTERVAL_NANOS) intervals += delta
        }
        if (intervals.isEmpty()) return null

        val spanNanos = intervals.sum()
        if (spanNanos <= 0L) return null
        val averageFps = intervals.size.toFloat() * NANOS_PER_SECOND / spanNanos.toFloat()
        if (!averageFps.isFinite() || averageFps <= 0f) return null

        val onePercentLow = onePercentLowFps(intervals)
        val budgetNanos = (NANOS_PER_SECOND / averageFps).toLong()
        val jank = ordered.count { it.totalDurationNanos > budgetNanos }

        return FrameRateSample(
            averageFps = averageFps,
            onePercentLowFps = onePercentLow,
            frameCount = ordered.size,
            windowMillis = spanNanos / 1_000_000L,
            jankFrames = jank,
        )
    }

    /**
     * The rate implied by the slowest 1% of intervals.
     *
     * Averaged across that slice rather than taken as the single worst interval, which is
     * how the figure is defined in the benchmarking convention players are used to and is
     * far less noisy — one scheduler hiccup should not define the number.
     */
    private fun onePercentLowFps(intervals: List<Long>): Float? {
        val sliceSize = intervals.size / 100
        if (sliceSize < 1) return null
        val slowest = intervals.sortedDescending().take(sliceSize)
        val mean = slowest.average()
        if (mean <= 0.0) return null
        val fps = (NANOS_PER_SECOND / mean).toFloat()
        return if (fps.isFinite() && fps > 0f) fps else null
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f

        /** A gap longer than this is a pause, not a frame interval. */
        const val MAX_INTERVAL_NANOS = 1_000_000_000L

        /**
         * Below this many unseen frames the ring buffer had not turned over enough to
         * measure from the new frames alone, so the whole buffer is used again rather
         * than reporting a rate from three frames.
         */
        const val MIN_FRESH_FRAMES = 10
    }
}
