package com.gamecore.service

import android.app.Notification
import androidx.lifecycle.lifecycleScope
import com.gamecore.R
import com.gamecore.core.common.NotificationChannels
import com.gamecore.core.model.GameSession
import com.gamecore.domain.gaming.GamingCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The foreground service a recorded session runs behind.
 *
 * It records nothing itself. The samples are written by [com.gamecore.domain.gaming.SessionRecorder],
 * inside [GamingCoordinator]'s job, in [GameDetectionService]'s scope — and that is on purpose, because
 * the order those writes happen in relative to the profile restore is the substance of a correct session
 * and moving them into a second service would put a process boundary's worth of uncertainty through the
 * middle of it.
 *
 * What this service is, then, is the two things a session needs that the recorder cannot provide:
 *
 *  - **The declaration.** §24B asks for an explicit Android 14 foreground-service type per service, and
 *    `dataSync` is the one that fits sustained local persistence — a row and its samples, written for as
 *    long as the game runs. It exists for exactly the period those writes happen, and not a second
 *    either side: [GameDetectionService] starts it when the row opens and stops it when the row closes.
 *  - **The user's control.** A session that can only be stopped from inside the app is a session the
 *    user cannot stop while they are playing. This is the notification with the game's name, a running
 *    clock, and a Stop that ends the session rather than the app.
 *
 * That Stop is why [onStopRequested] returns false. Ending a session is asynchronous — sampling is
 * cancelled, the row is aggregated and closed, the device is put back — and a service that stopped itself
 * the instant the button was tapped would withdraw the `dataSync` declaration from the writes it was
 * started for. It asks for the stop and lets the session's end take it down.
 */
@AndroidEntryPoint
class SessionTrackingService : GameCoreService() {

    @Inject lateinit var coordinator: GamingCoordinator

    override val notificationId: Int = NotificationChannels.ID_SESSION

    override val serviceType: Int = TYPE_DATA_SYNC

    private var session: GameSession? = null

    /**
     * Names the game and counts up from when the session began.
     *
     * The label goes through [ServiceNotifications.gameLabel] because it came from another app's
     * manifest, and an installed application can call itself anything — including a string with newlines
     * and bidirectional overrides in it, which in a notification is one app drawing text that appears to
     * come from another. A name that sanitises to nothing is dropped for a generic line rather than shown
     * as an empty quote.
     */
    override fun buildNotification(): Notification {
        val current = session
        val name = ServiceNotifications.gameLabel(current?.gameLabel)
        return ServiceNotifications.ongoing(
            context = this,
            channelId = NotificationChannels.SESSION,
            title = getString(R.string.notification_session_title),
            text = if (name.isBlank()) {
                getString(R.string.notification_session_unnamed)
            } else {
                getString(R.string.notification_session_text, name)
            },
            stopTarget = SessionTrackingService::class.java,
            notificationId = notificationId,
            hideOnLockScreen = true,
            startedAtMillis = current?.startedAtMillis ?: 0L,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Read before going foreground: the notification is built from it, and a session that opened a
        // moment ago is already in the flow's current value.
        session = coordinator.session.value
        if (!goForeground()) return
        lifecycleScope.launch { follow() }
    }

    /**
     * Follows the session, and stops when there is no longer one.
     *
     * Both halves matter. The label arrives with the row rather than before it, so the first emission is
     * usually what puts the game's name on the notification; and a session that ends while this service
     * is up must take it down even though [GameDetectionService] normally does that — the two orders of
     * events are indistinguishable from here, and a `dataSync` notification left up over nothing is
     * exactly what §26 asks not to happen.
     *
     * Only the fields the notification draws are compared. The recorder republishes the session as
     * samples accumulate, and re-posting a notification whose text has not changed on every sample tick
     * would be the per-second `notify` the chronometer exists to avoid.
     */
    private suspend fun follow() {
        coordinator.session.collect { current ->
            if (current == null) {
                stopSelf()
                return@collect
            }
            val previous = session
            session = current
            val unchanged = previous != null &&
                previous.id == current.id &&
                previous.gameLabel == current.gameLabel &&
                previous.startedAtMillis == current.startedAtMillis
            if (!unchanged) refresh()
        }
    }

    /**
     * Ends the session, and keeps the service alive to see it through.
     *
     * Routed through [GamingCoordinator.stopCurrent] rather than closing the row here, so a stop from
     * this button is the same transition as a game the detector saw close: the profile is restored, the
     * overlay's per-game state is cleared and the game is not immediately re-detected. [follow] stops the
     * service when the row closes.
     */
    override fun onStopRequested(): Boolean {
        coordinator.stopCurrent()
        return false
    }
}
