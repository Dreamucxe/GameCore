package com.gamecore.core.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * The three motion sensors, read through `SensorManager` and nothing else.
 *
 * Every figure this class produces is a value the platform handed over: the angular velocities are the
 * gyroscope's own `rad/s`, the accelerations are the accelerometer's own `m/s²` including gravity, and
 * the orientation angles come from `getOrientation` applied to the rotation vector's matrix. Nothing is
 * modelled, smoothed or filled in — a device without a gyroscope reports [Observed.Restricted] for it
 * and the screen says so rather than showing three zeroes.
 *
 * Two structural decisions, both about cost. First, delivery is on a private [HandlerThread] rather than
 * the main looper, so 50 Hz of sensor callbacks never share a thread with composition. Second, the
 * callback allocates nothing at all: it writes floats into [LiveMotion]'s fields, and a separate ticker
 * inside the flow builds one immutable [MotionFrame] every [DEFAULT_FRAME_INTERVAL_MS]. The UI therefore
 * recomposes about fifteen times a second whatever the sensor rate is, while the raw stream stays intact
 * for whoever is recording it.
 *
 * Registration is scoped to collection. `callbackFlow` unregisters the listener and quits the thread in
 * `awaitClose`, so a screen that goes to the background stops the sensors — §26's requirement, enforced
 * by the flow rather than by a `stop()` somebody has to remember to call.
 */
@Singleton
class MotionSampler @Inject constructor(@ApplicationContext context: Context) {

    private val manager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** True when the platform exposes a sensor service at all. False on a stripped or emulated build. */
    val hasSensorService: Boolean get() = manager != null

    /**
     * What the platform says about one sensor, or why there is nothing to say.
     *
     * The numbers are `Sensor`'s own accessors. [Sensor.getMinDelay] is the shortest period the hardware
     * will accept, not a rate anything is running at, and it is presented as such.
     */
    fun describe(kind: MotionSensorKind): Observed<SensorDescriptor> {
        val service = manager
            ?: return Observed.notPresent("This build exposes no SensorManager.")
        val sensor = service.getDefaultSensor(kind.type)
            ?: return Observed.notPresent("No ${kind.label.lowercase()} is present on this device.")
        return Observed.catching(DataSource.SENSOR_MANAGER) {
            SensorDescriptor(
                kind = kind,
                name = sensor.name,
                vendor = sensor.vendor,
                version = sensor.version,
                stringType = sensor.stringType.orEmpty(),
                maximumRange = sensor.maximumRange,
                resolution = sensor.resolution,
                minDelayMicros = sensor.minDelay,
                maxDelayMicros = sensor.maxDelay,
                powerMilliAmp = sensor.power,
                isWakeUp = sensor.isWakeUpSensor,
                reportingMode = reportingModeLabel(sensor.reportingMode),
            )
        }
    }

    /** Every motion sensor GameCore reads, described or explained, in display order. */
    fun describeAll(): List<Pair<MotionSensorKind, Observed<SensorDescriptor>>> =
        MotionSensorKind.entries.map { it to describe(it) }

    /** True when at least one of the three is present, so the screen can offer to sample at all. */
    fun hasAnySensor(): Boolean = MotionSensorKind.entries.any { manager?.getDefaultSensor(it.type) != null }

    /**
     * Live motion, at a fixed UI cadence, for as long as it is collected.
     *
     * [rateMicros] is passed straight to `registerListener` as `samplingPeriodUs`; the platform treats it
     * as a hint and may deliver faster or slower, which is why [MotionFrame.deliveredHz] reports the rate
     * actually observed instead of echoing the request back.
     *
     * [sink] receives every raw sample at full rate, on the sensor thread. It exists so that recording
     * keeps the real telemetry rather than the fifteen-per-second version the screen draws.
     */
    fun stream(
        rateMicros: Int = SensorManager.SENSOR_DELAY_GAME,
        frameIntervalMillis: Long = DEFAULT_FRAME_INTERVAL_MS,
        sink: MotionSink? = null,
    ): Flow<MotionFrame> = callbackFlow {
        val service = manager
        if (service == null) {
            close()
            return@callbackFlow
        }
        val sensors = MotionSensorKind.entries.mapNotNull { kind ->
            service.getDefaultSensor(kind.type)?.let { kind to it }
        }
        if (sensors.isEmpty()) {
            close()
            return@callbackFlow
        }

        val live = LiveMotion(hasGyroscope = sensors.any { it.first == MotionSensorKind.GYROSCOPE })
        val thread = HandlerThread("gamecore-motion").apply { start() }
        val handler = Handler(thread.looper)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) = live.accept(event, sink)
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) =
                live.acceptAccuracy(sensor.type, accuracy)
        }

        var registered = false
        sensors.forEach { (_, sensor) ->
            // Per sensor, because a device can refuse one and accept another; a single false from the
            // platform is not a reason to abandon the two that did register.
            if (service.registerListener(listener, sensor, rateMicros, handler)) registered = true
        }
        if (!registered) {
            service.unregisterListener(listener)
            thread.quitSafely()
            close()
            return@callbackFlow
        }

        launch {
            while (isActive) {
                trySend(live.snapshot())
                delay(frameIntervalMillis)
            }
        }

        awaitClose {
            service.unregisterListener(listener)
            thread.quitSafely()
        }
    }

    private fun reportingModeLabel(mode: Int): String = when (mode) {
        Sensor.REPORTING_MODE_CONTINUOUS -> "Continuous"
        Sensor.REPORTING_MODE_ON_CHANGE -> "On change"
        Sensor.REPORTING_MODE_ONE_SHOT -> "One shot"
        Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "Special trigger"
        else -> "Unknown"
    }

    companion object {
        /** About fifteen frames a second: fast enough to read, slow enough not to matter. */
        const val DEFAULT_FRAME_INTERVAL_MS = 66L
    }
}

/**
 * Mutable live state, written by the sensor callback and read by the ticker.
 *
 * Deliberately not a data class and deliberately not immutable: this object is written to at the sensor
 * rate and must not allocate. The fields are `@Volatile` because the two threads are genuinely different
 * — the callback runs on the sampler's `HandlerThread`, the snapshot on whatever dispatcher collects the
 * flow. A snapshot can therefore pair a gyroscope reading with an accelerometer reading from a few
 * milliseconds earlier, which is what any simultaneous read of two independent sensors does anyway.
 */
private class LiveMotion(private val hasGyroscope: Boolean) {

    @Volatile var gx = 0f
    @Volatile var gy = 0f
    @Volatile var gz = 0f
    @Volatile var gyroSeen = false

    @Volatile var ax = 0f
    @Volatile var ay = 0f
    @Volatile var az = 0f
    @Volatile var accelSeen = false

    @Volatile var azimuth = 0f
    @Volatile var pitch = 0f
    @Volatile var roll = 0f
    @Volatile var orientationSeen = false

    @Volatile var gyroAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    @Volatile var accelAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE

    @Volatile var samples = 0L
    @Volatile var firstEventNanos = 0L
    @Volatile var lastEventNanos = 0L

    /** Pre-allocated, because `getRotationMatrixFromVector` needs somewhere to write and this is hot. */
    private val rotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)
    private val truncatedVector = FloatArray(4)

    fun accept(event: SensorEvent, sink: MotionSink?) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                gx = event.values[0]
                gy = event.values[1]
                gz = event.values[2]
                gyroSeen = true
                count(event.timestamp)
                if (hasGyroscope) sink?.onSample(event.timestamp, gx, gy, gz, ax, ay, az)
            }

            Sensor.TYPE_ACCELEROMETER -> {
                ax = event.values[0]
                ay = event.values[1]
                az = event.values[2]
                accelSeen = true
                if (!hasGyroscope) {
                    // No gyroscope on this device, so the accelerometer is what paces the recording.
                    count(event.timestamp)
                    sink?.onSample(event.timestamp, 0f, 0f, 0f, ax, ay, az)
                }
            }

            Sensor.TYPE_ROTATION_VECTOR -> {
                // Some vendors report five components. Passing the longer array straight to
                // `getRotationMatrixFromVector` throws on a handful of builds, so it is truncated first.
                val values = if (event.values.size > 4) {
                    System.arraycopy(event.values, 0, truncatedVector, 0, 4)
                    truncatedVector
                } else {
                    event.values
                }
                SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
                SensorManager.getOrientation(rotationMatrix, orientationValues)
                azimuth = Math.toDegrees(orientationValues[0].toDouble()).toFloat()
                pitch = Math.toDegrees(orientationValues[1].toDouble()).toFloat()
                roll = Math.toDegrees(orientationValues[2].toDouble()).toFloat()
                orientationSeen = true
            }
        }
    }

    fun acceptAccuracy(type: Int, accuracy: Int) {
        when (type) {
            Sensor.TYPE_GYROSCOPE -> gyroAccuracy = accuracy
            Sensor.TYPE_ACCELEROMETER -> accelAccuracy = accuracy
        }
    }

    private fun count(timestampNanos: Long) {
        if (samples == 0L) firstEventNanos = timestampNanos
        lastEventNanos = timestampNanos
        samples++
    }

    fun snapshot(): MotionFrame {
        val elapsedNanos = lastEventNanos - firstEventNanos
        return MotionFrame(
            wallClockMillis = System.currentTimeMillis(),
            eventTimestampNanos = lastEventNanos,
            gyro = if (gyroSeen) Axis3(gx, gy, gz) else null,
            acceleration = if (accelSeen) Axis3(ax, ay, az) else null,
            orientation = if (orientationSeen) Orientation(azimuth, pitch, roll) else null,
            gyroAccuracy = gyroAccuracy,
            accelerationAccuracy = accelAccuracy,
            sampleCount = samples,
            deliveredHz = if (samples > 1 && elapsedNanos > 0L) {
                (samples - 1) * 1_000_000_000.0f / elapsedNanos
            } else {
                null
            },
        )
    }
}

/** Receives every raw sample at the sensor's own rate, on the sensor thread. Must not block. */
fun interface MotionSink {
    fun onSample(
        eventNanos: Long,
        gx: Float,
        gy: Float,
        gz: Float,
        ax: Float,
        ay: Float,
        az: Float,
    )
}

/** The three sensors this module reads, with the platform type behind each. */
enum class MotionSensorKind(val label: String, val type: Int, val unit: String) {
    GYROSCOPE("Gyroscope", Sensor.TYPE_GYROSCOPE, "rad/s"),
    ACCELEROMETER("Accelerometer", Sensor.TYPE_ACCELEROMETER, "m/s²"),
    ROTATION_VECTOR("Rotation vector", Sensor.TYPE_ROTATION_VECTOR, "unit quaternion"),
}

/** One sensor exactly as `Sensor` describes it. Nothing here is computed. */
data class SensorDescriptor(
    val kind: MotionSensorKind,
    val name: String,
    val vendor: String,
    val version: Int,
    val stringType: String,
    val maximumRange: Float,
    val resolution: Float,
    val minDelayMicros: Int,
    val maxDelayMicros: Int,
    val powerMilliAmp: Float,
    val isWakeUp: Boolean,
    val reportingMode: String,
) {
    /** The fastest rate the hardware accepts, or null for an on-change sensor that has no period. */
    val maximumHz: Float? get() = if (minDelayMicros > 0) 1_000_000f / minDelayMicros else null
}

/** Three axes of one reading. */
data class Axis3(val x: Float, val y: Float, val z: Float) {
    val magnitude: Float get() = sqrt(x * x + y * y + z * z)
}

/** Device orientation in degrees, as `SensorManager.getOrientation` reports it. */
data class Orientation(val azimuthDegrees: Float, val pitchDegrees: Float, val rollDegrees: Float)

/**
 * One UI frame of motion.
 *
 * Every field is nullable where the sensor behind it may be absent, so a device without a rotation
 * vector shows the orientation card as unavailable instead of showing zeroes for it.
 */
data class MotionFrame(
    val wallClockMillis: Long,
    val eventTimestampNanos: Long,
    val gyro: Axis3?,
    val acceleration: Axis3?,
    val orientation: Orientation?,
    val gyroAccuracy: Int,
    val accelerationAccuracy: Int,
    val sampleCount: Long,
    val deliveredHz: Float?,
) {
    companion object {
        val EMPTY = MotionFrame(
            wallClockMillis = 0L,
            eventTimestampNanos = 0L,
            gyro = null,
            acceleration = null,
            orientation = null,
            gyroAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
            accelerationAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE,
            sampleCount = 0L,
            deliveredHz = null,
        )

        /** The platform's accuracy constants, in words. */
        fun accuracyLabel(accuracy: Int): String = when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "High"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Medium"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Low"
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "Unreliable"
            SensorManager.SENSOR_STATUS_NO_CONTACT -> "No contact"
            else -> "Unknown"
        }
    }
}
