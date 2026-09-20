package com.gamecore.core.sensors

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * What a run of motion samples adds up to.
 *
 * These are arithmetic over gyroscope and accelerometer readings and nothing more. They describe how the
 * *device* moved: how far it was rotated, how fast, how often the rotation reversed, and how much of the
 * time it was effectively still. They are not a measure of how well anybody aimed at anything — GameCore
 * cannot see a game's camera, its sensitivity, its input or its target, and no arrangement of sensor data
 * substitutes for them. Each figure carries its own definition on screen for exactly that reason.
 *
 * Every accumulation is done in `Double` and every input is a raw sensor value. Nothing is filtered,
 * calibrated or corrected: a gyroscope with a bias reports that bias here, which is the honest outcome
 * and is visible as a non-zero average with the device sitting on a table.
 */
data class MotionSummary(
    val samples: Long,
    val durationMillis: Long,
    /** Total yaw travelled, as the integral of |ω_y| over time. Degrees. */
    val horizontalDegrees: Float,
    /** Total pitch travelled, as the integral of |ω_x| over time. Degrees. */
    val verticalDegrees: Float,
    /** Mean |ω| across every sample. Degrees per second. */
    val averageRotationDegreesPerSecond: Float,
    /** Largest single |ω| seen. Degrees per second. */
    val peakRotationDegreesPerSecond: Float,
    /** Mean | |a| − g |: how far the accelerometer strayed from rest, in m/s². */
    val movementIntensity: Float,
    /** Largest | |a| − g | seen, in m/s². */
    val peakMovementIntensity: Float,
    /** Yaw sign reversals outside the dead zone. */
    val directionChanges: Int,
    /** Share of samples whose |ω| was below the still threshold, 0..1. */
    val stillFraction: Float,
    /** Samples the trace could not keep, if the buffer filled. */
    val droppedSamples: Long,
) {
    val isEmpty: Boolean get() = samples == 0L

    companion object {
        val EMPTY = MotionSummary(
            samples = 0L,
            durationMillis = 0L,
            horizontalDegrees = 0f,
            verticalDegrees = 0f,
            averageRotationDegreesPerSecond = 0f,
            peakRotationDegreesPerSecond = 0f,
            movementIntensity = 0f,
            peakMovementIntensity = 0f,
            directionChanges = 0,
            stillFraction = 0f,
            droppedSamples = 0L,
        )

        /** Standard gravity, the reference [MotionSummary.movementIntensity] is a deviation from. */
        const val GRAVITY = 9.80665f

        /** |ω| below this counts as still. About 1.7°/s — below a deliberate movement, above sensor noise. */
        const val STILL_THRESHOLD_RAD = 0.03f

        /** |ω_y| must exceed this before a sign change counts, so noise around zero is not a reversal. */
        const val DIRECTION_DEAD_ZONE_RAD = 0.12f

        /** A gap longer than this is treated as a break rather than integrated over. */
        const val MAX_INTERVAL_NANOS = 200_000_000L
    }
}

/**
 * Running motion statistics, updated one sample at a time and allocating nothing.
 *
 * Fed from the sensor thread through [MotionSink]; read from the UI thread through [summary]. The
 * counters are plain fields rather than atomics because a diagnostic display reading a count that is one
 * sample behind is not a defect, and a `CAS` per axis at 50 Hz would be.
 */
class MotionAccumulator {

    @Volatile private var samples = 0L
    @Volatile private var dropped = 0L
    private var firstNanos = 0L
    private var lastNanos = 0L
    private var previousNanos = 0L

    private var horizontalRadians = 0.0
    private var verticalRadians = 0.0
    private var rotationSum = 0.0
    private var rotationPeak = 0f
    private var intensitySum = 0.0
    private var intensityPeak = 0f
    private var stillSamples = 0L
    private var directionChanges = 0
    private var lastYawSign = 0

    fun add(eventNanos: Long, gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float) {
        if (samples == 0L) {
            firstNanos = eventNanos
            previousNanos = eventNanos
        }
        val interval = (eventNanos - previousNanos).coerceIn(0L, MotionSummary.MAX_INTERVAL_NANOS)
        previousNanos = eventNanos
        lastNanos = eventNanos
        samples++

        val seconds = interval / 1_000_000_000.0
        horizontalRadians += abs(gy) * seconds
        verticalRadians += abs(gx) * seconds

        val rotation = sqrt(gx * gx + gy * gy + gz * gz)
        rotationSum += rotation
        if (rotation > rotationPeak) rotationPeak = rotation
        if (rotation < MotionSummary.STILL_THRESHOLD_RAD) stillSamples++

        val intensity = abs(sqrt(ax * ax + ay * ay + az * az) - MotionSummary.GRAVITY)
        intensitySum += intensity
        if (intensity > intensityPeak) intensityPeak = intensity

        val sign = when {
            gy > MotionSummary.DIRECTION_DEAD_ZONE_RAD -> 1
            gy < -MotionSummary.DIRECTION_DEAD_ZONE_RAD -> -1
            else -> 0
        }
        if (sign != 0) {
            if (lastYawSign != 0 && sign != lastYawSign) directionChanges++
            lastYawSign = sign
        }
    }

    fun noteDropped() {
        dropped++
    }

    fun reset() {
        samples = 0L
        dropped = 0L
        firstNanos = 0L
        lastNanos = 0L
        previousNanos = 0L
        horizontalRadians = 0.0
        verticalRadians = 0.0
        rotationSum = 0.0
        rotationPeak = 0f
        intensitySum = 0.0
        intensityPeak = 0f
        stillSamples = 0L
        directionChanges = 0
        lastYawSign = 0
    }

    fun summary(): MotionSummary {
        val count = samples
        if (count == 0L) return MotionSummary.EMPTY
        return MotionSummary(
            samples = count,
            durationMillis = (lastNanos - firstNanos) / 1_000_000L,
            horizontalDegrees = Math.toDegrees(horizontalRadians).toFloat(),
            verticalDegrees = Math.toDegrees(verticalRadians).toFloat(),
            averageRotationDegreesPerSecond = Math.toDegrees(rotationSum / count).toFloat(),
            peakRotationDegreesPerSecond = Math.toDegrees(rotationPeak.toDouble()).toFloat(),
            movementIntensity = (intensitySum / count).toFloat(),
            peakMovementIntensity = intensityPeak,
            directionChanges = directionChanges,
            stillFraction = stillSamples.toFloat() / count,
            droppedSamples = dropped,
        )
    }
}

/**
 * The raw telemetry of one recording, in primitive arrays.
 *
 * Seven values per sample in three flat arrays rather than a list of objects: at 200 Hz a `List<Sample>`
 * would be two hundred allocations a second on the sensor thread, and the export is a flat table anyway.
 * The buffer is bounded and the overflow is reported — [MotionSummary.droppedSamples] is what keeps a
 * long recording from silently becoming a short one.
 */
class MotionTrace(private val capacity: Int = DEFAULT_CAPACITY) {

    private val timestamps = LongArray(capacity)
    private val gyro = FloatArray(capacity * 3)
    private val acceleration = FloatArray(capacity * 3)

    @Volatile
    var size: Int = 0
        private set

    val isFull: Boolean get() = size >= capacity

    /** Returns false when the buffer is full, so the caller can count the drop. */
    fun add(eventNanos: Long, gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float): Boolean {
        val index = size
        if (index >= capacity) return false
        timestamps[index] = eventNanos
        val base = index * 3
        gyro[base] = gx
        gyro[base + 1] = gy
        gyro[base + 2] = gz
        acceleration[base] = ax
        acceleration[base + 1] = ay
        acceleration[base + 2] = az
        size = index + 1
        return true
    }

    fun clear() {
        size = 0
    }

    /**
     * Walks the recorded rows, oldest first, handing each to [row].
     *
     * A callback rather than a list so that an export of thirty thousand samples builds one string
     * instead of thirty thousand throwaway objects on the way to it.
     */
    inline fun forEachSample(
        row: (index: Int, elapsedMillis: Double, gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float) -> Unit,
    ) {
        val count = size
        if (count == 0) return
        val origin = timestampAt(0)
        for (i in 0 until count) {
            row(
                i,
                (timestampAt(i) - origin) / 1_000_000.0,
                gyroAt(i, 0), gyroAt(i, 1), gyroAt(i, 2),
                accelerationAt(i, 0), accelerationAt(i, 1), accelerationAt(i, 2),
            )
        }
    }

    fun timestampAt(index: Int): Long = timestamps[index]

    fun gyroAt(index: Int, axis: Int): Float = gyro[index * 3 + axis]

    fun accelerationAt(index: Int, axis: Int): Float = acceleration[index * 3 + axis]

    companion object {
        /** Ten minutes at 50 Hz, about 1 MB of primitives. */
        const val DEFAULT_CAPACITY = 30_000
    }
}

/** Where a recording is. The screen offers start, pause, stop and clear against exactly these. */
enum class RecordingState { IDLE, RECORDING, PAUSED, STOPPED }

/**
 * Accumulator and trace behind one recording, wired to the sampler as a single [MotionSink].
 *
 * The accumulator runs whenever the screen is sampling, so the aim figures are live before anything is
 * recorded; starting a recording resets it and begins keeping raw rows beside it. Pausing stops both
 * without discarding either, which is the difference between pause and stop.
 */
class MotionRecorder(traceCapacity: Int = MotionTrace.DEFAULT_CAPACITY) : MotionSink {

    val trace = MotionTrace(traceCapacity)
    private val accumulator = MotionAccumulator()

    @Volatile
    var state: RecordingState = RecordingState.IDLE
        private set

    override fun onSample(
        eventNanos: Long,
        gx: Float,
        gy: Float,
        gz: Float,
        ax: Float,
        ay: Float,
        az: Float,
    ) {
        when (state) {
            RecordingState.PAUSED, RecordingState.STOPPED -> return
            RecordingState.IDLE -> accumulator.add(eventNanos, gx, gy, gz, ax, ay, az)
            RecordingState.RECORDING -> {
                accumulator.add(eventNanos, gx, gy, gz, ax, ay, az)
                if (!trace.add(eventNanos, gx, gy, gz, ax, ay, az)) accumulator.noteDropped()
            }
        }
    }

    fun start() {
        accumulator.reset()
        trace.clear()
        state = RecordingState.RECORDING
    }

    fun pause() {
        if (state == RecordingState.RECORDING) state = RecordingState.PAUSED
    }

    fun resume() {
        if (state == RecordingState.PAUSED) state = RecordingState.RECORDING
    }

    fun stop() {
        if (state == RecordingState.RECORDING || state == RecordingState.PAUSED) {
            state = RecordingState.STOPPED
        }
    }

    fun clear() {
        accumulator.reset()
        trace.clear()
        state = RecordingState.IDLE
    }

    fun summary(): MotionSummary = accumulator.summary()
}
