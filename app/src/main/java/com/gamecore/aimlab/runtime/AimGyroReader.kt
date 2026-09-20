package com.gamecore.aimlab.runtime

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/** One integrated gyro step: how far the view should turn this sample, in radians. */
data class GyroDelta(val dx: Float, val dy: Float)

/**
 * The gyroscope, read for aim training and nothing else.
 *
 * Modelled on [com.gamecore.core.sensors.MotionSampler]: listeners are registered on a private
 * [HandlerThread] when the flow is collected and unregistered in `awaitClose`, so the sensor runs only
 * while a gyro training screen is actually collecting and stops the instant that screen goes away. There
 * is no `stop()` for anyone to forget and no service involved — collection is the lifecycle (§29).
 *
 * [isAvailable] answers the capability question honestly: a device with no gyroscope returns false, the
 * gyro screen shows its "unavailable" state, and [stream] completes immediately without ever emitting.
 * Nothing here fabricates a sample (§30).
 *
 * The rate is [SensorManager.SENSOR_DELAY_GAME], not the fastest the hardware offers — aim training does
 * not need the extra callbacks and the faster rate would cost battery for no gain (§20).
 */
@Singleton
class AimGyroReader @Inject constructor(@ApplicationContext private val context: Context) {

    private val manager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** Whether this device has a gyroscope at all. The gyro screen gates itself on this. */
    fun isAvailable(): Boolean = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

    /**
     * Angular-velocity samples turned into per-sample view deltas, for as long as the flow is collected.
     *
     * The gyroscope reports angular velocity in rad/s about each axis; multiplying by the time since the
     * last sample gives the angle turned, which is the delta an aim system applies. Rotation about the
     * device's Y axis turns the view horizontally and about its X axis vertically; the signs match the
     * convention that turning the phone right moves the aim right. Yaw (Z) is not used for aim.
     */
    fun stream(): Flow<GyroDelta> = callbackFlow {
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
                trySend(GyroDelta(dx, dy))
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
    }
}
