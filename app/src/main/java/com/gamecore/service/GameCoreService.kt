package com.gamecore.service

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.gamecore.core.common.NotificationChannels

/**
 * The parts every GameCore foreground service repeats.
 *
 * A base class rather than a helper object because the thing being shared is a *sequence*: create the
 * channels, post the notification, go foreground, and give up cleanly if the system refuses. Getting
 * that order wrong is how a service dies with `ForegroundServiceDidNotStartInTimeException` five
 * seconds after launch, and it is not the kind of bug that shows up on a development device with the
 * app in front.
 *
 * `LifecycleService` supplies `lifecycleScope`, which is the point: every service here collects flows,
 * and a scope tied to `onDestroy` means a stopped service leaves nothing behind sampling `/proc/stat`
 * — §26's requirement, enforced by the scope rather than by remembering to cancel jobs.
 *
 * The service's own lifecycle is *not* used as a `ComposeView`'s owner. It never reaches `RESUMED`, it
 * provides no `SavedStateRegistry` and no `ViewModelStore`, and overlay windows need all three; that is
 * [com.gamecore.core.overlay.OverlayViewHost]'s job.
 */
abstract class GameCoreService : LifecycleService() {

    /** The notification id from [NotificationChannels], so the Stop action can be addressed. */
    protected abstract val notificationId: Int

    /**
     * The Android 14+ foreground service type, matching the manifest entry exactly.
     *
     * A mismatch is fatal on API 34: the system throws rather than downgrading, so the constant is
     * declared here next to the code that passes it instead of being left to the manifest alone.
     *
     * Not called `foregroundServiceType`, tempting as that is: `Service.getForegroundServiceType()` has
     * existed since API 29, and a Kotlin property of that name compiles to exactly that JVM signature —
     * an accidental override of a platform getter that reports something else entirely.
     */
    protected abstract val serviceType: Int

    /** The notification to run behind. Called again by [refresh] whenever its content changes. */
    protected abstract fun buildNotification(): Notification

    /** True once [goForeground] has succeeded, so [refresh] cannot post a notification too early. */
    var isForeground: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        // Idempotent, and cheap after the first call. Here rather than in `Application.onCreate` because
        // a service can be started by the system before any activity has run, and posting to a channel
        // that does not exist is silently dropped on API 26+.
        NotificationChannels.createAll(this)
    }

    /**
     * The common `onStartCommand`: dispatch the lifecycle event, then honour a Stop action.
     *
     * Every notification's Stop button lands here. The action is checked against one known constant and
     * anything else is passed to [onStartAction] — §24A.3 applied to the only intent these services
     * receive. They are not exported, so this is a defence against our own stale `PendingIntent`s rather
     * than against another app, but an unrecognised action must still not be treated as a start.
     *
     * `START_NOT_STICKY` for all of them. A redelivered start intent arrives with none of the in-process
     * state that justified the service — the overlay's request, the game being tracked — so a sticky
     * restart would put up a foreground notification for work nobody asked for.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ServiceNotifications.ACTION_STOP) {
            if (onStopRequested()) stopSelf()
            return START_NOT_STICKY
        }
        onStartAction(intent)
        return START_NOT_STICKY
    }

    /** Called for every start that is not a Stop. Default does nothing; overridden where a start carries data. */
    protected open fun onStartAction(intent: Intent?) = Unit

    /**
     * Called when the user taps Stop, before the service is stopped. Returns whether to stop it.
     *
     * Almost always true, and the default. `false` is for the one case where Stop means something this
     * service has to outlive: [SessionTrackingService]'s button ends a *session*, which is asynchronous
     * and produces the database writes this service exists to declare. Stopping immediately would
     * withdraw the `dataSync` foreground service from the writes it was started for and leave the user
     * with a session still recording and nothing on screen saying so. It stops when the session is gone.
     */
    protected open fun onStopRequested(): Boolean = true

    /**
     * Enters the foreground, or stops the service.
     *
     * The three failures are all real and all outside our control: `startForegroundService` was called
     * while the app was in the background on API 31+ (`ForegroundServiceStartNotAllowedException`, an
     * `IllegalStateException`), the declared type needs a permission this build was denied
     * (`SecurityException`), or the OEM's own policy refused. Every one of them means this service cannot
     * legally run, so it stops itself rather than sitting there doing invisible work until the system
     * kills it — which on API 34 it will, with a crash.
     */
    protected fun goForeground(notification: Notification = buildNotification()): Boolean {
        return try {
            ServiceCompat.startForeground(this, notificationId, notification, serviceType)
            isForeground = true
            true
        } catch (refused: Exception) {
            isForeground = false
            stopSelf()
            false
        }
    }

    /**
     * Re-posts the notification with new content.
     *
     * Through `NotificationManagerCompat` rather than a second `startForeground`, which would be
     * harmless but counts as a foreground-service start on some ROMs. Silently a no-op when the
     * notification permission is denied on API 33+ — the service is still allowed to run, it just has no
     * visible notification, and that is a state to tolerate rather than to crash on.
     */
    protected fun refresh(notification: Notification = buildNotification()) {
        if (!isForeground) return
        try {
            NotificationManagerCompat.from(this).notify(notificationId, notification)
        } catch (denied: SecurityException) {
            // POST_NOTIFICATIONS revoked while running. Nothing to do; the service continues.
        }
    }

    override fun onDestroy() {
        isForeground = false
        // `false` keeps nothing behind: the notification is removed with the service, because every one
        // of them describes work that has now stopped.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    protected companion object {
        /**
         * `specialUse`, for the three services no other type describes.
         *
         * Android 14 requires a type per service and provides none for "draws an overlay", "polls the
         * foreground app" or "samples device metrics". `specialUse` with the manifest justification
         * strings is what the platform asks for in exactly that case; it is not a way around the
         * requirement, and each of the three declares what it is for in `AndroidManifest.xml`.
         */
        const val TYPE_SPECIAL_USE = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

        const val TYPE_DATA_SYNC = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

        const val TYPE_MEDIA_PROJECTION = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    }
}
