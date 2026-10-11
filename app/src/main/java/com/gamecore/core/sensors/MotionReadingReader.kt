package com.gamecore.core.sensors

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * The motion readings a data-only plugin may display through GameCore's approved stat feed.
 *
 * This is the whole of a plugin's motion vocabulary — two derived figures over the gyroscope and
 * accelerometer, and nothing that reaches a sensor directly. A plugin never touches
 * [MotionSampler]; it names one of these ids in a `stat` node (behind `READ_APPROVED_DATA` + the
 * `motion` scope) and the service resolves it from a host-owned sampler frame. The ids here are
 * the exact strings the host publishes and the SDK's `OverlayStats` mirrors — a mismatch would be
 * the quiet kind of failure (a stat that draws blank), so the two are pinned to each other in the
 * codec tests.
 *
 * [unit] and [decimals] are presentation only; the arithmetic that produces a figure lives in
 * [MotionReadingReader] and is unit-tested on the JVM.
 */
enum class MotionReading(val id: String, val unit: String, val decimals: Int) {
    /** Angular speed: the magnitude of the gyroscope vector, in degrees per second. */
    ROTATION_RATE("rotation_rate", "°/s", 0),

    /** Linear movement: how far the accelerometer strays from rest (|a| − g), in m/s². */
    MOVEMENT("movement", "m/s²", 1);

    companion object {
        private val byId = entries.associateBy { it.id }

        /** Resolves an authored id, or null. Never `valueOf` on untrusted input. */
        fun fromId(id: String): MotionReading? = byId[id.trim().lowercase()]

        /** The ids, for the SDK-parity assertion and the per-use api bump. */
        val IDS: Set<String> = entries.mapTo(LinkedHashSet()) { it.id }
    }
}

/**
 * The outcome of resolving one [MotionReading] against a [MotionFrame]: a figure, or the honest
 * absence of one.
 *
 * [value] is null when the sensor behind the reading is not present on the device — a gyroscope-less
 * phone cannot report a rotation rate, and the right answer is "unavailable", never a fabricated 0.
 * [display] therefore returns null in that case so the overlay renderer draws its own em dash rather
 * than a zero that reads as a measurement.
 */
data class MotionReadingResult(
    val reading: MotionReading,
    val value: Double?,
) {
    val isAvailable: Boolean get() = value != null

    val unit: String get() = reading.unit

    /**
     * The figure with its unit — "124°/s", "0.3m/s²" — or null when there is nothing to show.
     *
     * Formatted with [BigDecimal] rather than `String.format` so the decimal separator is always a
     * dot regardless of the device locale: this string is parsed by nothing, but a comma here would
     * read oddly beside the app's own dot-formatted stats.
     */
    fun display(): String? {
        val v = value ?: return null
        val number = BigDecimal(v).setScale(reading.decimals, RoundingMode.HALF_UP).toPlainString()
        return "$number${reading.unit}"
    }
}

/**
 * Turns a [MotionFrame] into the figure one [MotionReading] shows.
 *
 * Pure arithmetic, no Android and no `domain`/`monitoring` types, so it is unit-tested on the JVM
 * exactly like [MotionRecorder]'s math is. The translation to the service's own `StatReading`/display
 * path happens at the service boundary, not here — this stays a function of a frame and a selector.
 *
 * Both figures mirror [MotionRecorder]'s own definitions so a plugin's live reading and GameCore's
 * recorded summary agree: rotation rate is the gyroscope magnitude in degrees per second, and
 * movement is |a| − g, the accelerometer's deviation from rest. A missing sensor is reported as
 * unavailable, never as zero.
 */
object MotionReadingReader {

    fun read(reading: MotionReading, frame: MotionFrame): MotionReadingResult = when (reading) {
        MotionReading.ROTATION_RATE ->
            MotionReadingResult(reading, frame.gyro?.let { Math.toDegrees(it.magnitude.toDouble()) })

        MotionReading.MOVEMENT ->
            MotionReadingResult(
                reading,
                frame.acceleration?.let { abs(it.magnitude - MotionSummary.GRAVITY).toDouble() },
            )
    }

    /** Resolve by id, or null if the id is not a motion reading. */
    fun read(id: String, frame: MotionFrame): MotionReadingResult? =
        MotionReading.fromId(id)?.let { read(it, frame) }
}
