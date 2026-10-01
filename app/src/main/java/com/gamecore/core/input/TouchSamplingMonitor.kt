package com.gamecore.core.input

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.common.Precision

/**
 * Folds a session of real touch samples into the figures the Touch Sampling Monitor screen reports,
 * one delivered pointer event at a time.
 *
 * Immutable and android-free, exactly like [com.gamecore.domain.monitoring.LatencyLogger]: [record]
 * returns a new monitor and the caller keeps the result, so every rule here is testable without a device,
 * a `MotionEvent` or a clock. Nothing in this file opens an input device or reads the panel — it is handed
 * the sample timestamps a `PointerInputChange` already carries (its current `uptimeMillis` and every
 * `historical` sample's) and does arithmetic on them.
 *
 * The honesty of this screen is the whole point, so the readings are deliberately different claims:
 *
 *  - [deliveredEventRatePerSecond] counts the samples this app was actually handed and divides by the
 *    capture window — a throughput, over at least [WINDOW_MILLIS] ms, on the same "no rate below a second
 *    of capture" rule `TouchLog` puts under its touch density.
 *  - [observedSampleRateHz] is the cadence the timestamps *imply* while a finger moves. It is offered only
 *    once the framework has actually batched sub-frame samples, because without batching the timestamps
 *    reveal the delivery rate and nothing more; and it is an *observed* rate, since Android may resample
 *    touch, not a claim about the panel's own hardware rate.
 *  - [hardwareTouchLatency] is always [Observed.Restricted] and never a number — see its own note.
 *  - [inputProcessingDelayMillis] is an *estimate* of queue delay inside this process, named so it cannot
 *    be read as touch-to-photon latency.
 *
 * A ring buffer was considered ([com.gamecore.core.common.SampleRingBuffer]) and does not fit: it is
 * mutable and stores `Float`, while a sample timestamp is a large `Long` and this accumulator is a value.
 * Running integer totals keep [record] allocation-free and the type immutable, as LatencyLogger's are.
 */
data class TouchSamplingMonitor(
    private val firstSampleUptimeMillis: Long? = null,
    private val lastSampleUptimeMillis: Long? = null,
    private val deliveredSampleCount: Int = 0,
    private val batchedSampleCount: Int = 0,
    private val eventCount: Int = 0,
    private val intervalSumMillis: Long = 0L,
    private val intervalCount: Int = 0,
    private val nowMillis: Long = 0L,
    private val lastProcessingDelayMillis: Float? = null,
) {
    /**
     * Takes the sample timestamps of one delivered pointer event, newest last, and the clock reading at
     * the instant they were processed.
     *
     * [eventTimesMillis] is a change's `historical` sample uptimes followed by its own `uptimeMillis`, so a
     * size above one is the framework having batched sub-frame samples into a single delivery. An empty
     * array is a clock tick: it only advances [nowMillis] — which is how an idle finger falls back to "move
     * to measure" without a fresh event — and records no sample. Timestamps are assumed ascending; any
     * non-positive gap is ignored rather than trusted.
     */
    fun record(eventTimesMillis: LongArray, nowMillis: Long): TouchSamplingMonitor {
        if (eventTimesMillis.isEmpty()) return copy(nowMillis = maxOf(nowMillis, this.nowMillis))

        var sum = intervalSumMillis
        var count = intervalCount
        var previous = lastSampleUptimeMillis
        for (time in eventTimesMillis) {
            val gap = previous?.let { time - it }
            if (gap != null && gap in 1..MAX_MOVEMENT_GAP_MILLIS) {
                sum += gap
                count++
            }
            previous = time
        }

        val newest = eventTimesMillis.last()
        return copy(
            firstSampleUptimeMillis = firstSampleUptimeMillis ?: eventTimesMillis.first(),
            lastSampleUptimeMillis = newest,
            deliveredSampleCount = deliveredSampleCount + eventTimesMillis.size,
            batchedSampleCount = batchedSampleCount + (eventTimesMillis.size - 1),
            eventCount = eventCount + 1,
            intervalSumMillis = sum,
            intervalCount = count,
            nowMillis = maxOf(nowMillis, newest),
            lastProcessingDelayMillis = (nowMillis - newest).toFloat().coerceAtLeast(0f),
        )
    }

    /**
     * Input samples per second this pad was handed, over a capture window of at least [WINDOW_MILLIS] ms.
     *
     * A count of delivered samples over elapsed capture time — so it includes every batched sub-frame
     * sample — and it stays [Observed.awaitingSample] until a full window has been seen, the same floor
     * `TouchLog` puts under its touch density, for the same reason: a rate over a fraction of a second is
     * noise wearing a measurement's label.
     */
    val deliveredEventRatePerSecond: Observed<Float>
        get() {
            val first = firstSampleUptimeMillis
            val last = lastSampleUptimeMillis
            if (first == null || last == null) return Observed.awaitingSample(FILLING_WINDOW)
            val spanMillis = last - first
            if (spanMillis < WINDOW_MILLIS) return Observed.awaitingSample(FILLING_WINDOW)
            return Observed.of(deliveredSampleCount * 1_000f / spanMillis, DataSource.INPUT_EVENT, Precision.SAMPLED)
        }

    /**
     * The sampling cadence the timestamps imply while the finger moves, in Hz.
     *
     * Offered only once the framework has actually batched sub-frame samples ([batchedSampleCount] > 0):
     * with one sample per delivered event the timestamps reveal the delivery rate and nothing more, so a
     * figure there would be the frame rate wearing a sampling rate's label. Once [MIN_EVENTS_TO_JUDGE_BATCHING]
     * events have gone by with no batching among them, that is reported as [Observed.notPresent] rather than
     * left pending forever. While batched samples exist but the finger is still, it asks for movement. The
     * figure is [Precision.SAMPLED] and, per the screen's copy, an *observed* rate — Android may resample
     * touch input, so it is never a claim about the panel's own hardware rate.
     */
    val observedSampleRateHz: Observed<Float>
        get() {
            if (batchedSampleCount == 0) {
                return if (eventCount >= MIN_EVENTS_TO_JUDGE_BATCHING) {
                    Observed.notPresent(NO_BATCHING)
                } else {
                    Observed.awaitingSample(MOVE_TO_MEASURE)
                }
            }
            val last = lastSampleUptimeMillis
            if (last == null || nowMillis - last > IDLE_GAP_MILLIS || intervalCount == 0) {
                return Observed.awaitingSample(MOVE_TO_MEASURE)
            }
            val meanMillis = intervalSumMillis.toDouble() / intervalCount
            if (meanMillis <= 0.0) return Observed.awaitingSample(MOVE_TO_MEASURE)
            return Observed.of((1_000.0 / meanMillis).toFloat(), DataSource.INPUT_EVENT, Precision.SAMPLED)
        }

    /**
     * Never a number.
     *
     * Android timestamps a touch event when the *framework* receives it, which already includes the
     * digitizer, the driver and the input pipeline, and gives no access to the moment the finger physically
     * met the glass. A figure derived from those timestamps would describe queueing inside this process,
     * not touch-to-photon latency, so this reports the absence in the same terms [ControllerReader] uses for
     * a controller and makes no latency claim at all.
     */
    val hardwareTouchLatency: Observed<Nothing>
        get() = Observed.notPresent(HARDWARE_LATENCY)

    /**
     * An *estimate* of the delay between the framework timestamping the newest sample and this process
     * reading it — `now − sample uptime`. [Precision.ESTIMATED], and named a processing delay rather than a
     * latency precisely so it cannot be mistaken for the touch-to-photon figure [hardwareTouchLatency]
     * refuses to invent. It awaits the first sample before it can be measured.
     */
    val inputProcessingDelayMillis: Observed<Float>
        get() {
            val delay = lastProcessingDelayMillis ?: return Observed.awaitingSample(AWAITING_SAMPLE)
            return Observed.of(delay, DataSource.INPUT_EVENT, Precision.ESTIMATED)
        }

    companion object {
        /** The shortest capture a delivered-rate figure may be computed over — `TouchLog`'s one-second floor. */
        const val WINDOW_MILLIS = 1_000L

        /** A gap between samples longer than this is between two movements, not within one, so it is not cadence. */
        const val MAX_MOVEMENT_GAP_MILLIS = 100L

        /** Longer than this since the last sample and the finger is treated as still. */
        const val IDLE_GAP_MILLIS = 200L

        /** Delivered events seen with no batching among them before concluding the framework does not batch. */
        const val MIN_EVENTS_TO_JUDGE_BATCHING = 8

        private const val FILLING_WINDOW = "Measuring over at least a second of touch."
        private const val MOVE_TO_MEASURE = "Move your finger to measure."
        private const val AWAITING_SAMPLE = "Touch the pad to measure."
        private const val NO_BATCHING =
            "This device delivered one sample per event, so a sub-frame input rate cannot be observed here."
        private const val HARDWARE_LATENCY =
            "Android timestamps a touch when the framework receives it — after the digitizer, the driver and " +
                "the input pipeline — and never exposes the moment the finger met the glass. Any figure would " +
                "describe queueing inside this app, not touch-to-photon latency, so none is shown."

        val EMPTY = TouchSamplingMonitor()
    }
}
