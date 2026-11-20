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
import kotlin.math.ceil

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
    suspend fun capabilityFor(
        packageName: String?,
        /**
         * When supplied, the same dump is used to start that consumer's frame cursor at the
         * newest frame already in the buffer. The consumer's first [sample] then contains only
         * frames drawn after this call, instead of the seconds of play that preceded it.
         */
        primeConsumer: String? = null,
    ): FrameRateCapability = withContext(io) {
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
        when (val parsed = DumpsysParsers.parseFrameStats(result.stdout)) {
            is Observed.Value -> {
                if (primeConsumer != null) {
                    parsed.value.maxOfOrNull { it.intendedVsyncNanos }?.let { newest ->
                        synchronized(cursors) {
                            cursors[cursorKey(primeConsumer, packageName)] = newest
                        }
                    }
                }
                FrameRateCapability.GameFrameStats(packageName)
            }
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
     * `framestats` prints what is in it — so consecutive calls overlap. Each [consumer]
     * keeps its own cursor: only frames newer than that consumer's previous window are
     * aggregated, and the cursor advances only when a window was actually produced. A
     * sample with fewer than [MIN_FRESH_FRAMES] unseen frames returns null instead of
     * re-aggregating frames that another window already counted. Two consumers therefore
     * see two independent, non-overlapping series over the same dumps.
     */
    suspend fun sample(
        packageName: String,
        consumer: String = DEFAULT_CONSUMER,
    ): FrameRateSample? = withContext(io) {
        val command = ShellCommand.frameStats(packageName) ?: return@withContext null
        val result = shell.execute(command)
        if (!result.isSuccess) return@withContext null

        val frames = DumpsysParsers.parseFrameStats(result.stdout).valueOrNull
            ?: return@withContext null

        val key = cursorKey(consumer, packageName)
        val lastFrameSeenNanos = synchronized(cursors) { cursors[key] ?: 0L }
        val fresh = frames.filter { it.intendedVsyncNanos > lastFrameSeenNanos }
        if (fresh.size < MIN_FRESH_FRAMES) return@withContext null

        val sample = aggregate(fresh) ?: return@withContext null
        val newest = fresh.maxOf { it.intendedVsyncNanos }
        synchronized(cursors) { cursors[key] = newest }
        sample
    }

    /**
     * Forgets one consumer's frame cursor, so its next [sample] starts from a clean buffer.
     *
     * Cursors are keyed by consumer and package, so this never discards another consumer's
     * progress. [capabilityFor] with `primeConsumer` is the normal way to start a capture:
     * it consumes the capability dump into the cursor and keeps pre-capture frames out of
     * the first window.
     */
    fun reset(consumer: String = DEFAULT_CONSUMER) {
        synchronized(cursors) {
            val prefix = "$consumer|"
            cursors.keys.removeAll { it.startsWith(prefix) }
        }
    }

    private val cursors = HashMap<String, Long>()

    private fun cursorKey(consumer: String, packageName: String): String =
        "$consumer|$packageName"

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
        val p95 = frameTimePercentileMillis(intervals, P95_PERCENT)
        val p99 = frameTimePercentileMillis(intervals, P99_PERCENT)
        val budgetNanos = (NANOS_PER_SECOND / averageFps).toLong()
        val jank = ordered.count { it.totalDurationNanos > budgetNanos }

        return FrameRateSample(
            averageFps = averageFps,
            onePercentLowFps = onePercentLow,
            frameCount = ordered.size,
            windowMillis = spanNanos / 1_000_000L,
            jankFrames = jank,
            p95FrameMillis = p95,
            p99FrameMillis = p99,
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

    companion object {
        /** The consumer key used when a caller does not name one. */
        const val DEFAULT_CONSUMER = "default"

        /** The live session monitor's cursor. */
        const val SESSION_CONSUMER = "session"

        /** A benchmark capture's cursor. */
        const val BENCHMARK_CONSUMER = "benchmark"

        private const val NANOS_PER_SECOND = 1_000_000_000f

        /** A gap longer than this is a pause, not a frame interval. */
        private const val MAX_INTERVAL_NANOS = 1_000_000_000L

        /**
         * Below this many unseen frames the ring buffer had not turned over enough to
         * measure from the new frames alone. The window is refused rather than padded
         * with frames an earlier window already counted, because overlapping windows are
         * not independent evidence.
         */
        private const val MIN_FRESH_FRAMES = 10

        private const val P95_PERCENT = 95f
        private const val P99_PERCENT = 99f
    }
}

/**
 * The nearest-rank percentile of real frame intervals, in milliseconds.
 *
 * Durations, not rates: each interval is the time between two consecutive vsyncs, so a
 * larger value is a slower frame. The rank is `ceil(p / 100 * n)` over the ascending
 * sorted intervals, which is the standard nearest-rank definition — no interpolation,
 * because a percentile of real frame durations should be one of the durations that
 * actually happened.
 *
 * Null below [FrameRateSample.MIN_FRAMES] intervals. At 20 samples the 99th percentile is
 * already the slowest frame; below that it would be the slowest one or two frames wearing
 * a percentile's name, which is exactly the noise this figure exists to avoid.
 */
internal fun frameTimePercentileMillis(intervals: List<Long>, percentile: Float): Float? {
    if (intervals.size < FrameRateSample.MIN_FRAMES) return null
    if (percentile !in 0f..100f) return null
    val sorted = intervals.sorted()
    val rank = ceil(sorted.size * (percentile / 100.0)).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1] / 1_000_000f
}
