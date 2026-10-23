package com.gamecore.core.sensors

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

/**
 * The raw accelerometer and gyroscope stream used by the calibration screen.
 *
 * Registration is scoped to collection: `callbackFlow` registers the listener when someone starts
 * collecting and [awaitClose] unregisters it and stops the private [HandlerThread] when they stop. A
 * screen that goes to the background therefore stops the sensors without needing a `stop()` call, which
 * is the same lifetime rule the rest of GameCore's live readers follow.
 *
 * Samples are emitted on accelerometer events, carrying the most recent gyroscope reading. That makes the
 * accelerometer the clock and avoids emitting two nearly-identical samples for every sensor pair. A device
 * with no gyroscope produces the same stream with `gx`, `gy` and `gz` set to zero, so downstream maths has
 * one shape to handle; a device with no accelerometer has no usable trace and the flow closes.
 */
@Singleton
class SensorTraceReader @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /**
     * Streams samples at [SensorManager.SENSOR_DELAY_GAME] until collection is cancelled.
     *
     * A sensor service that is absent, an accelerometer the device does not have, or a registration the
     * platform refuses all close the flow rather than throwing. The caller analyses whatever samples it
     * collected, and [SensorCalibration] reports [SensorQuality.UNKNOWN] for a trace that is too short.
     */
    fun stream(): Flow<TraceSample> = callbackFlow {
        val manager = sensorManager
        if (manager == null) {
            close()
            return@callbackFlow
        }

        val accelerometer = try {
            manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        } catch (_: SecurityException) {
            null
        }
        if (accelerometer == null) {
            close()
            return@callbackFlow
        }
        // A refused gyroscope is treated exactly like an absent one: the trace continues with zeroes.
        val gyroscope = try {
            manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        } catch (_: SecurityException) {
            null
        }

        val thread = HandlerThread("gamecore-sensor-trace").apply { start() }
        val handler = Handler(thread.looper)

        var latestAx = 0f
        var latestAy = 0f
        var latestAz = 0f
        var latestGx = 0f
        var latestGy = 0f
        var latestGz = 0f

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        latestAx = event.values[0]
                        latestAy = event.values[1]
                        latestAz = event.values[2]
                        trySend(
                            TraceSample(
                                millis = event.timestamp / NANOS_PER_MILLI,
                                ax = latestAx,
                                ay = latestAy,
                                az = latestAz,
                                gx = latestGx,
                                gy = latestGy,
                                gz = latestGz,
                            ),
                        )
                    }

                    Sensor.TYPE_GYROSCOPE -> {
                        latestGx = event.values[0]
                        latestGy = event.values[1]
                        latestGz = event.values[2]
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                // Accuracy is not part of the calibration figures; the raw readings are.
            }
        }

        val registered = try {
            val accelRegistered =
                manager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME, handler)
            val gyroRegistered = if (gyroscope == null) {
                true
            } else {
                manager.registerListener(listener, gyroscope, SensorManager.SENSOR_DELAY_GAME, handler)
            }
            accelRegistered && gyroRegistered
        } catch (_: SecurityException) {
            false
        }

        if (!registered) {
            unregisterQuietly(manager, listener)
            thread.quitSafely()
            close()
            return@callbackFlow
        }

        awaitClose {
            unregisterQuietly(manager, listener)
            thread.quitSafely()
        }
    }

    /**
     * Permission can be revoked while a trace is running, so the cleanup path must not throw either.
     */
    private fun unregisterQuietly(manager: SensorManager, listener: SensorEventListener) {
        try {
            manager.unregisterListener(listener)
        } catch (_: SecurityException) {
            // Nothing is listening any more either way.
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
