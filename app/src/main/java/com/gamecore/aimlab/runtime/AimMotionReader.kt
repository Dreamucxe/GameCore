package com.gamecore.aimlab.runtime

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.gamecore.aimlab.engine3d.TiltAim
import com.gamecore.aimlab.engine3d.TiltPose
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

/** One measured aim step: how far the view should turn this sample, in radians. `dx` right, `dy` up. */
data class AimDelta(val dx: Float, val dy: Float)

/**
 * The motion sensors, read for aim training and nothing else.
 *
 * Modelled on [com.gamecore.core.sensors.MotionSampler]: listeners are registered on a private
 * [HandlerThread] when the flow is collected and unregistered in `awaitClose`, so a sensor runs only while
 * a motion training screen is actually collecting and stops the instant that screen goes away. There is no
 * `stop()` for anyone to forget and no service involved — collection is the lifecycle (§29).
 *
 * [capability] answers the device question honestly and [stream] follows it: a gyroscope when one exists, an
 * accelerometer-derived tilt fallback when only that exists, and an empty stream otherwise. Nothing here
 * fabricates a sample (§30), and the two sources are never mixed — a phone with a gyroscope never silently
 * falls back, because that would make two different measurements look like one.
 *
 * The rate is [SensorManager.SENSOR_DELAY_GAME] for both sources — about 50 Hz. Aim training does not need
 * the fastest the hardware offers, and the faster rate would cost battery for no gain (§20). Tilt's per
 * sample differences telescope, so the rate changes smoothness, not the total angle moved.
 */
@Singleton
class AimMotionReader @Inject constructor(@ApplicationContext private val context: Context) {

    private val manager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** Which sensor the aim modes can read on this device. */
    fun capability(): AimInputSource = AimInputSource.choose(
        hasGyroscope = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null,
        hasAccelerometer = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
    )

    /**
     * Per-sample view deltas from whichever sensor [capability] selected, for as long as the flow is
     * collected.
     *
     * A device with neither sensor gets an empty flow rather than a zero, and the screen gates itself on
     * the same [capability] answer so that state is explained before a run is ever offered.
     */
    fun stream(): Flow<AimDelta> = when (capability()) {
        AimInputSource.GYRO -> gyroStream()
        AimInputSource.TILT -> tiltStream()
        AimInputSource.NONE -> emptyFlow()
    }

    /**
     * Angular-velocity samples turned into per-sample view deltas.
     *
     * The gyroscope reports angular velocity in rad/s about each axis; multiplying by the time since the
     * last sample gives the angle turned, which is the delta an aim system applies. Rotation about the
     * device's Y axis turns the view horizontally and about its X axis vertically; the signs match the
     * convention that turning the phone right moves the aim right. Yaw (Z) is not used for aim.
     */
    private fun gyroStream(): Flow<AimDelta> = callbackFlow {
        val service = manager
        val sensor = service?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (service == null || sensor == null) {
            close()
            return@callbackFlow
        }

        val thread = HandlerThread("aimlab-gyro").apply { start() }
        val handler = Handler(thread.looper)
        var lastNanos = 0L

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (lastNanos == 0L) {
                    lastNanos = event.timestamp
                    return
                }
                val dt = (event.timestamp - lastNanos) / 1_000_000_000f
                lastNanos = event.timestamp
                if (dt <= 0f || dt > MAX_INTERVAL_SECONDS) return
                // event.values[1] = angular velocity about Y (horizontal turn),
                // event.values[0] = about X (vertical turn). Turn = velocity * dt.
                val dx = event.values[1] * dt
                val dy = event.values[0] * dt
                trySend(AimDelta(dx, dy))
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }

        val registered = service.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
        if (!registered) {
            service.unregisterListener(listener)
            thread.quitSafely()
            close()
            return@callbackFlow
        }

        awaitClose {
            service.unregisterListener(listener)
            thread.quitSafely()
        }
    }

    /**
     * Accelerometer samples differenced into per-sample view deltas — the fallback for phones with no
     * gyroscope.
     *
     * The first trustworthy pose only sets the reference. A pose that [TiltAim.read] refuses (the phone is
     * being swung, or the screen is near horizontal) also clears the reference, so the next good sample is
     * a new starting point rather than a jump measured across the gap. The same re-arm happens after a
     * pause longer than [MAX_GAP_NANOS], because the phone may have moved while nothing was listening.
     *
     * No smoothing is applied here: the repo's sensor layer reports measured values, and the
     * user-configurable smoothing stage downstream already exists for the aim pipeline.
     */
    private fun tiltStream(): Flow<AimDelta> = callbackFlow {
        val service = manager
        val sensor = service?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (service == null || sensor == null) {
            close()
            return@callbackFlow
        }

        val thread = HandlerThread("aimlab-tilt").apply { start() }
        val handler = Handler(thread.looper)
        var lastNanos = 0L
        var lastPose: TiltPose? = null

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val gap = if (lastNanos == 0L) 0L else event.timestamp - lastNanos
                lastNanos = event.timestamp

                val pose = TiltAim.read(event.values[0], event.values[1], event.values[2])
                val previous = lastPose
                if (pose == null || previous == null || gap > MAX_GAP_NANOS) {
                    // A reference, not a measurement: drop this sample and start from here.
                    lastPose = pose
                    return
                }

                val (dx, dy) = TiltAim.delta(previous, pose)
                lastPose = pose
                if (abs(dx) + abs(dy) > MIN_DELTA_RADIANS) trySend(AimDelta(dx, dy))
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }

        val registered = service.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
        if (!registered) {
            service.unregisterListener(listener)
            thread.quitSafely()
            close()
            return@callbackFlow
        }

        awaitClose {
            service.unregisterListener(listener)
            thread.quitSafely()
        }
    }

    private companion object {
        /** A gap longer than this is a resume after a pause, not a real interval; skip it. */
        const val MAX_INTERVAL_SECONDS = 0.25f

        /** The same pause, in the event timestamps' own unit (nanoseconds). */
        const val MAX_GAP_NANOS = 250_000_000L

        /** Below this a difference is sensor noise on a still phone, not motion worth a frame. */
        const val MIN_DELTA_RADIANS = 1e-5f
    }
}
