package com.gamecore.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import com.gamecore.MainActivity
import com.gamecore.R
import com.gamecore.core.common.NotificationChannels
import com.gamecore.core.common.TextSanitizer

/**
 * Every notification GameCore posts, in one place.
 *
 * Four of the five services are only allowed to run because they post one of these, so the wording is
 * part of the feature rather than decoration: §24B asks for a foreground service the user can identify
 * and stop, and each of these carries a Stop action wired to the service that posted it. The strings
 * come from `strings.xml` rather than from literals so a translation reaches the one surface the user
 * cannot dismiss.
 *
 * Two details that are easy to get wrong and expensive to debug:
 *
 *  - `FLAG_IMMUTABLE` on every `PendingIntent`. Mandatory from API 31, and correct anyway — a mutable
 *    one handed to the system is an intent any app holding it could rewrite, which is §24A.3's
 *    validate-your-entry-points rule applied to the entry point that is easiest to forget.
 *  - `FOREGROUND_SERVICE_IMMEDIATE`. Without it Android 12+ may hold the notification back for ten
 *    seconds, so a user who taps "start overlay" sees a floating button appear with no explanation of
 *    what is now running.
 *
 * Anything that came from another app — a game's own label — goes through
 * [TextSanitizer.sanitizeForNotification] first. An installed application can call itself whatever it
 * likes, including a string with newlines and bidirectional overrides in it, and a notification is a
 * place where that would let one app draw a convincing line of text on behalf of another.
 */
object ServiceNotifications {

    /**
     * The action a notification's Stop button sends to the service that posted it.
     *
     * One string for all five because the target is always an explicit component: the intent names the
     * class, so there is no ambiguity to resolve and no way for another app to deliver it — the services
     * are not exported.
     */
    const val ACTION_STOP = "com.gamecore.action.STOP"

    /**
     * The ongoing notification a foreground service runs behind.
     *
     * [stopTarget] is the service class the Stop button should reach; passing null omits the action, for
     * the one case where stopping is not the user's to do — a `mediaProjection` service that must live
     * exactly as long as the recording it owns.
     *
     * [startedAtMillis] turns the timestamp into a running clock. Only the session notification uses it,
     * and for the same reason [recording] does: the system ticks a chronometer for free, whereas a
     * duration this app re-posted every second would be a `notify` per second from a process already
     * sharing a device with a game.
     */
    fun ongoing(
        context: Context,
        channelId: String,
        title: String,
        text: String? = null,
        stopTarget: Class<out Service>? = null,
        notificationId: Int = 0,
        @DrawableRes smallIcon: Int = R.drawable.ic_stat_gamecore,
        hideOnLockScreen: Boolean = false,
        startedAtMillis: Long = 0L,
    ): Notification {
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setSilent(true)
            .setWhen(startedAtMillis)
            .setShowWhen(startedAtMillis > 0L)
            .setUsesChronometer(startedAtMillis > 0L)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(
                // A session notification names the game being played. That is not a secret, but it is
                // nobody else's business on a lock screen, and the notification is not dismissible.
                if (hideOnLockScreen) NotificationCompat.VISIBILITY_PRIVATE
                else NotificationCompat.VISIBILITY_PUBLIC,
            )
        if (stopTarget != null) {
            builder.addAction(
                0,
                context.getString(R.string.notification_action_stop),
                stopService(context, stopTarget, notificationId),
            )
        }
        return builder.build()
    }

    /**
     * The screen-recording notification, which needs its own builder for two reasons.
     *
     * Its Stop button must not go through [ACTION_STOP]. Stopping a recording means finalising an mp4 —
     * asynchronous work — and the common Stop path calls `stopSelf` the moment it is handled, which would
     * leave a file without its trailing atoms that no player will open. So the button carries the
     * recording service's own stop intent and the service stops itself once the file is closed.
     *
     * And the duration is a chronometer rather than text this app re-posts every second. `setWhen` plus
     * `setUsesChronometer` has the system tick the notification, which costs nothing; a per-second
     * `notify` from a service that is already sharing the device with a game and an encoder is exactly
     * the kind of background cost §26 objects to.
     */
    fun recording(context: Context, startedAtMillis: Long, stop: PendingIntent): Notification =
        NotificationCompat.Builder(context, NotificationChannels.RECORDING)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(context.getString(R.string.notification_recording_title))
            .setContentText(context.getString(R.string.notification_recording_text))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setSilent(true)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(startedAtMillis)
            .setShowWhen(startedAtMillis > 0L)
            .setUsesChronometer(startedAtMillis > 0L)
            .addAction(0, context.getString(R.string.notification_action_stop), stop)
            .build()

    /**
     * A thermal or battery warning.
     *
     * Not tied to a service and therefore dismissible, auto-cancelling, and `BigTextStyle` — the whole
     * value of these is the sentence explaining what GameCore observed and what it is not going to do
     * about it, and a collapsed notification would cut that off mid-clause.
     */
    fun alert(context: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(context, NotificationChannels.ALERTS)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    /** A game's own label, safe to put in a notification. Empty when the app has no usable name. */
    fun gameLabel(raw: String?): String = TextSanitizer.sanitizeForNotification(raw)

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            // The launcher's own flags. Without them, tapping the notification while GameCore is already
            // open starts a second copy of the activity behind the first.
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun stopService(context: Context, target: Class<out Service>, requestCode: Int): PendingIntent {
        val intent = Intent(context, target).setAction(ACTION_STOP)
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private const val REQUEST_OPEN = 900
}
