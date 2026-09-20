package com.gamecore.ui.motion

import android.hardware.SensorManager
import androidx.compose.runtime.Immutable
import com.gamecore.core.common.Observed
import com.gamecore.core.common.isAvailable
import com.gamecore.core.sensors.Axis3
import com.gamecore.core.sensors.MotionFrame
import com.gamecore.core.sensors.MotionSensorKind
import com.gamecore.core.sensors.MotionSummary
import com.gamecore.core.sensors.MotionTrace
import com.gamecore.core.sensors.RecordingState
import com.gamecore.core.sensors.SensorDescriptor
import com.gamecore.data.repository.DiagnosticsExport
import kotlin.math.abs
import kotlin.math.max

/**
 * The motion screen's state: three sensors, two plots and one recording.
 *
 * Every figure here came out of `SensorManager`. Nothing is modelled, smoothed or filled in — a device
 * without a gyroscope has an absent [Observed] for it and two empty plots, rather than a flat line at zero
 * that a reader would take for a very steady hand.
 *
 * The aim figures in [summary] are the same measurements arithmetic: integrated angular velocity, the
 * magnitude of acceleration less gravity, and a count of sign changes in yaw. They describe how the *phone*
 * moved. Whether that movement was a good shot is not something a sensor can answer, which is why the card
 * carrying them says so rather than calling any of it accuracy.
 */
data class MotionUiState(
    val isLoaded: Boolean = false,
    val hasSensorService: Boolean = true,
    val sensors: List<Pair<MotionSensorKind, Observed<SensorDescriptor>>> = emptyList(),
    val rate: SampleRate = SampleRate.GAME,
    val frame: MotionFrame = MotionFrame.EMPTY,
    val gyroSeries: AxisSeries = AxisSeries.EMPTY,
    val accelerationSeries: AxisSeries = AxisSeries.EMPTY,
    val summary: MotionSummary = MotionSummary.EMPTY,
    val recording: RecordingState = RecordingState.IDLE,
    val recordedSamples: Int = 0,
    val isTraceFull: Boolean = false,
    val isExporting: Boolean = false,
    val export: DiagnosticsExport? = null,
    val message: String? = null,
) {
    fun sensor(kind: MotionSensorKind): Observed<SensorDescriptor>? =
        sensors.firstOrNull { it.first == kind }?.second

    val gyroscope: Observed<SensorDescriptor>? get() = sensor(MotionSensorKind.GYROSCOPE)

    val accelerometer: Observed<SensorDescriptor>? get() = sensor(MotionSensorKind.ACCELEROMETER)

    val rotationVector: Observed<SensorDescriptor>? get() = sensor(MotionSensorKind.ROTATION_VECTOR)

    /** Whether this device gave us anything at all to read. Drives the empty state. */
    val hasAnySensor: Boolean get() = sensors.any { it.second.isAvailable }

    /** True once a sample has actually arrived, which is not the same as having registered a listener. */
    val isStreaming: Boolean get() = frame.sampleCount > 0L

    val isRecording: Boolean get() = recording == RecordingState.RECORDING

    val isPaused: Boolean get() = recording == RecordingState.PAUSED

    val isStopped: Boolean get() = recording == RecordingState.STOPPED

    val hasTrace: Boolean get() = recordedSamples > 0

    /** How full the raw buffer is, for the meter under the recording controls. */
    val traceFraction: Float
        get() = (recordedSamples.toFloat() / MotionTrace.DEFAULT_CAPACITY).coerceIn(0f, 1f)

    val lastExport: DiagnosticsExport.Written? get() = export as? DiagnosticsExport.Written
}

/**
 * The rate asked of the platform.
 *
 * Android treats the argument as a hint: a device is free to deliver slower, and several deliver faster
 * because another app asked for more. That is why the screen shows [MotionFrame.deliveredHz] beside this
 * choice instead of echoing the request back as though it were the answer.
 */
enum class SampleRate(val label: String, val micros: Int, val detail: String) {
    FASTEST("Fastest", SensorManager.SENSOR_DELAY_FASTEST, "As fast as the sensor reports. Costs the most."),
    GAME("Game", SensorManager.SENSOR_DELAY_GAME, "About 50 Hz. The default, and enough for aim movement."),
    UI("UI", SensorManager.SENSOR_DELAY_UI, "About 15 Hz. Cheaper, and coarse for fast flicks."),
    NORMAL("Normal", SensorManager.SENSOR_DELAY_NORMAL, "About 5 Hz. Orientation only."),
}

/**
 * The last [CAPACITY] samples of one three-axis sensor, kept per axis.
 *
 * Three lists rather than a list of triples, because the plot draws one axis at a time and a
 * `List<Axis3>` would be mapped three times on every frame to get there.
 *
 * A null sample is dropped rather than recorded as zero. The gap that leaves shortens the line, which is
 * the honest picture of a sensor that stopped reporting — a zero would be a reading this device never gave.
 */
@Immutable
data class AxisSeries(
    val x: List<Float> = emptyList(),
    val y: List<Float> = emptyList(),
    val z: List<Float> = emptyList(),
) {
    val size: Int get() = x.size

    val isEmpty: Boolean get() = x.isEmpty()

    fun add(sample: Axis3?): AxisSeries {
        if (sample == null) return this
        return AxisSeries(push(x, sample.x), push(y, sample.y), push(z, sample.z))
    }

    /**
     * The largest absolute value anywhere in the window, which is what the plots scale to.
     *
     * Symmetric on purpose: angular velocity is signed, and a range that ran from the window's minimum to
     * its maximum would put zero somewhere other than the middle and make a leftward turn look different
     * in size from the identical rightward one.
     */
    val extreme: Float
        get() = max(max(peak(x), peak(y)), peak(z))

    private fun peak(values: List<Float>): Float {
        var highest = 0f
        for (value in values) {
            val magnitude = abs(value)
            if (magnitude > highest) highest = magnitude
        }
        return highest
    }

    private fun push(values: List<Float>, value: Float): List<Float> =
        if (values.size >= CAPACITY) values.subList(values.size - CAPACITY + 1, values.size) + value
        else values + value

    companion object {
        /** The same window the performance graphs use, so the two screens read at the same pace. */
        const val CAPACITY = 120

        val EMPTY = AxisSeries()
    }
}

/**
 * One frame of live motion plus the trails behind it, folded together as samples arrive.
 *
 * Kept as one object so the fold that builds the trails also carries the frame that produced them; a
 * screen that combined a separate "latest frame" flow with a separate "history" flow could draw a point
 * that is not on its own line.
 */
@Immutable
data class MotionLive(
    val frame: MotionFrame = MotionFrame.EMPTY,
    val gyro: AxisSeries = AxisSeries.EMPTY,
    val acceleration: AxisSeries = AxisSeries.EMPTY,
) {
    fun add(next: MotionFrame): MotionLive = MotionLive(
        frame = next,
        gyro = gyro.add(next.gyro),
        acceleration = acceleration.add(next.acceleration),
    )

    companion object {
        val EMPTY = MotionLive()
    }
}
