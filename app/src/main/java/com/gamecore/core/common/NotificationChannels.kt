package com.gamecore.core.common

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.gamecore.R

/**
 * The notification channels, and the notification identifiers that go with them.
 *
 * Every foreground service in this app must post a notification — Android requires it
 * for a service that draws over other apps or keeps running while the user is in a
 * game — so the user is going to see these whether they want to or not. The response
 * is to make each one honest about what is running and to put it in its own channel,
 * so that someone who wants the overlay's permanent notification silenced can silence
 * exactly that without also losing the thermal warning that tells them their phone is
 * getting too hot.
 *
 * Created once from `Application.onCreate`. Creating a channel that already exists is
 * a no-op, and a channel the user has reconfigured is left alone by the platform, so
 * this is safe on every launch.
 */
object NotificationChannels {

    /** The overlay host: floating button, pill, crosshair, HUD. */
    const val OVERLAY = "gamecore.overlay"

    /** Session recording. */
    const val SESSION = "gamecore.session"

    /** The foreground-app watcher. */
    const val DETECTION = "gamecore.detection"

    /**
     * Background metric sampling.
     *
     * Its own channel rather than sharing [SESSION]'s, because the two run for different reasons and a
     * user who silences one has not asked to silence the other: a session notification appears when a
     * game starts, this one appears because the user asked GameCore to keep watching the device.
     */
    const val MONITOR = "gamecore.monitor"

    /** Screen recording, which Android will not let us hide. */
    const val RECORDING = "gamecore.recording"

    /** Thermal and battery warnings. The one channel that should interrupt. */
    const val ALERTS = "gamecore.alerts"

    /**
     * The quick-trigger shake watcher.
     *
     * Its own channel because it is the one service the user arms deliberately and may want to silence
     * independently of everything else: it is not a side effect of a game starting or an overlay being
     * up, it is a shortcut they switched on.
     */
    const val TRIGGER = "gamecore.trigger"

    // Notification ids. Distinct per service, because two services posting the same id
    // replace each other's notification and Android then kills the one whose
    // notification vanished.
    const val ID_OVERLAY = 1001
    const val ID_SESSION = 1002
    const val ID_DETECTION = 1003
    const val ID_RECORDING = 1004
    const val ID_MONITOR = 1005
    const val ID_TRIGGER = 1006
    const val ID_ALERT_THERMAL = 1101
    const val ID_ALERT_BATTERY = 1102

    /** The §C5 poor-network alert. Rate-limited to one a minute by the sender, not this id. */
    const val ID_ALERT_NETWORK = 1103

    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        // IMPORTANCE_LOW for the three permanent ones: they are a platform requirement
        // rather than something the user asked to be told, so they belong in the shade
        // silently and without a heads-up banner over the game.
        manager.createNotificationChannel(
            channel(
                context, OVERLAY, R.string.channel_overlay_name,
                R.string.channel_overlay_description, NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            channel(
                context, SESSION, R.string.channel_session_name,
                R.string.channel_session_description, NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            channel(
                context, DETECTION, R.string.channel_detection_name,
                R.string.channel_detection_description, NotificationManager.IMPORTANCE_MIN,
            ),
        )
        manager.createNotificationChannel(
            channel(
                context, RECORDING, R.string.channel_recording_name,
                R.string.channel_recording_description, NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            channel(
                context, MONITOR, R.string.channel_monitor_name,
                R.string.channel_monitor_description, NotificationManager.IMPORTANCE_MIN,
            ),
        )
        manager.createNotificationChannel(
            channel(
                context, TRIGGER, R.string.channel_trigger_name,
                R.string.channel_trigger_description, NotificationManager.IMPORTANCE_MIN,
            ),
        )
        // The alerts channel is the exception. A device approaching its thermal limit
        // during a game is something the user needs to know now, so this one is allowed
        // to vibrate and appear over what they are doing.
        manager.createNotificationChannel(
            channel(
                context, ALERTS, R.string.channel_alerts_name,
                R.string.channel_alerts_description, NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                enableVibration(true)
                setShowBadge(true)
            },
        )
    }

    private fun channel(
        context: Context,
        id: String,
        nameRes: Int,
        descriptionRes: Int,
        importance: Int,
    ): NotificationChannel = NotificationChannel(id, context.getString(nameRes), importance).apply {
        description = context.getString(descriptionRes)
        setShowBadge(false)
        // No lights or vibration on the permanent channels: an overlay that stays on
        // for a two-hour session must not blink an LED for two hours.
        enableLights(false)
        enableVibration(false)
    }

    /**
     * Whether notifications can actually be posted.
     *
     * From API 33 this is a runtime permission, and a foreground service whose
     * notification is blocked still runs but shows nothing — so the app asks before
     * starting one rather than leaving the user with a service they cannot see or stop.
     */
    fun areEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()
}
