package com.gamecore.service

import android.app.Notification
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import androidx.core.os.HandlerCompat
import androidx.lifecycle.lifecycleScope
import com.gamecore.R
import com.gamecore.core.common.NotificationChannels
import com.gamecore.core.model.QuickTriggerMethod
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.domain.trigger.QuickTriggerCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The one trigger method that needs a process of its own: shake.
 *
 * A key press reaches an app only through the focused window, and a Quick Settings tile is dispatched by
 * the system, so neither costs anything while the app is closed. A shake has to be *watched for*, and the
 * only lawful way for an app to keep reading a sensor while the user is in a game is a foreground service
 * — which is why this class exists and why it exists only while the shake method is the armed one.
 * [QuickTriggerCoordinator.syncService] starts and stops it; nothing else does.
 *
 * `specialUse` is the foreground-service type. Android 14 has no type for "watches the accelerometer for a
 * shortcut gesture", and the manifest carries the justification string the platform asks for in that case.
 *
 * Two things this service deliberately does not do:
 *
 *  - **Decide anything.** The samples go straight to [QuickTriggerCoordinator], which holds the threshold,
 *    the debounce and the fire cooldown, so the in-app path and this one cannot drift apart in how many
 *    jolts count as a shake.
 *  - **Batch.** `maxReportLatency` of zero on the registration. Batching is the right default for a sensor
 *    being logged and the wrong one for a gesture: samples delivered in a burst two seconds later would
 *    open the panel two seconds after the user shook the phone, which reads as the trigger being broken.
 *
 * The listener runs on its own [HandlerThread] at a background priority rather than on the main looper.
 * At 50 Hz this is a callback every twenty milliseconds, and the point of the whole feature is that it
 * costs the game nothing.
 */
@AndroidEntryPoint
class QuickTriggerService : GameCoreService() {

    @Inject lateinit var coordinator: QuickTriggerCoordinator

    @Inject lateinit var preferences: SecurePreferenceStore

    override val notificationId: Int = NotificationChannels.ID_TRIGGER

    override val serviceType: Int = TYPE_SPECIAL_USE

    private var sensors: SensorManager? = null

    private var thread: HandlerThread? = null

    /**
     * The listener, which allocates nothing.
     *
     * `values` belongs to the platform's own reused event object, so the three floats are read out and
     * handed on as primitives rather than the array being kept. The timestamp is `elapsedRealtime` and not
     * `event.timestamp`: the latter is a sensor-hardware clock whose epoch is not `SystemClock`'s on every
     * device, and the coordinator only ever compares one sample's time against the last one's, so what it
     * needs is a monotonic clock that no one is going to reset mid-gesture.
     */
    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val values = event.values
            if (values.size < 3) return
            coordinator.onAccelerometerSample(
                x = values[0],
                y = values[1],
                z = values[2],
                nowMillis = SystemClock.elapsedRealtime(),
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun buildNotification(): Notification = ServiceNotifications.ongoing(
        context = this,
        channelId = NotificationChannels.TRIGGER,
        title = getString(R.string.notification_trigger_title),
        text = getString(R.string.notification_trigger_text),
        stopTarget = QuickTriggerService::class.java,
        notificationId = notificationId,
    )

    override fun onCreate() {
        super.onCreate()
        if (!goForeground()) return
        if (!register()) {
            // No accelerometer, or the platform refused the registration. A notification for a service
            // that is watching nothing would be a lie, so it stops instead.
            stopSelf()
            return
        }
        lifecycleScope.launch { watchArmed() }
    }

    /**
     * Registers the listener, reporting whether the device actually gave us the sensor.
     *
     * `getDefaultSensor` returning null is the normal answer on a device without an accelerometer — an
     * emulator, a TV box — not an error, and the caller turns it into a service that stops rather than
     * one that sits in the shade claiming to be armed.
     */
    private fun register(): Boolean {
        val manager = getSystemService(SensorManager::class.java) ?: return false
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        val worker = HandlerThread("gamecore-trigger", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        val registered = try {
            manager.registerListener(
                listener,
                sensor,
                SAMPLE_INTERVAL_MICROS,
                0,
                HandlerCompat.createAsync(worker.looper),
            )
        } catch (refused: Exception) {
            false
        }
        if (!registered) {
            worker.quitSafely()
            return false
        }
        sensors = manager
        thread = worker
        return true
    }

    /**
     * Stops the service as soon as the shake method stops being the armed one.
     *
     * The same shape as [PerformanceMonitorService]'s own reason-watch and for the same reason: the condition
     * that starts a service should be the condition that stops it, so a user switching the method to
     * "Volume Up ×2" in Settings takes this process down without the settings screen knowing there was a
     * process. It also answers a start this service should never have had — a stale intent reviving it
     * after the trigger was disarmed — by stopping on the first emission.
     */
    private suspend fun watchArmed() {
        preferences.settings.collect { settings ->
            val armed = settings.quickTrigger.enabled &&
                settings.quickTrigger.method == QuickTriggerMethod.SHAKE
            if (!armed) stopSelf()
        }
    }

    /**
     * Honours Stop by disarming the trigger, not just by ending the process.
     *
     * A Stop that only stopped the service would be undone by the next call to
     * [QuickTriggerCoordinator.syncService] — opening the app is enough — and a button that turns a
     * feature off until you next launch GameCore is a button that does not work.
     */
    override fun onStopRequested(): Boolean {
        preferences.updateSettings { it.copy(quickTrigger = it.quickTrigger.copy(enabled = false)) }
        return true
    }

    override fun onDestroy() {
        sensors?.let {
            try {
                it.unregisterListener(listener)
            } catch (ignored: Exception) {
                // A manager that has already dropped the listener. Nothing to undo.
            }
        }
        sensors = null
        thread?.quitSafely()
        thread = null
        super.onDestroy()
    }

    private companion object {
        /**
         * 20 ms, so 50 Hz.
         *
         * Named in microseconds because `registerListener` is, and given as a number rather than as
         * `SENSOR_DELAY_GAME` because the rate is load-bearing here: the coordinator's debounce is
         * written against a jolt spanning several samples, and `SENSOR_DELAY_UI` would deliver three
         * where this delivers ten. Android treats it as a hint and may deliver slower, which is fine —
         * the debounce is a minimum interval, not an assumption about the interval.
         */
        const val SAMPLE_INTERVAL_MICROS = 20_000
    }
}
