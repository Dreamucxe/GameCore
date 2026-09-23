package com.gamecore.service

import android.app.Notification
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.gamecore.R
import com.gamecore.core.common.NotificationChannels
import com.gamecore.core.model.StopReason
import com.gamecore.domain.BackgroundServiceGate
import com.gamecore.domain.gaming.GameDetector
import com.gamecore.domain.gaming.GamingCoordinator
import com.gamecore.domain.gaming.GamingState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * The service that notices a game has started.
 *
 * All of the deciding is [GamingCoordinator]'s: this hosts it, gives it a scope that dies with the
 * service, and turns what it reports into a notification. That split is what makes §26's "stop when not
 * needed" true by construction — the foreground-app poll, the sample loop and the session writes are all
 * children of `lifecycleScope`, so a stopped service leaves nothing behind querying `UsageStatsManager`
 * every two seconds. There is no other way to switch detection off, and none needed.
 *
 * `specialUse` is the foreground-service type, declared in the manifest with the justification Android 14
 * asks for. None of the defined types describes watching for a game launch: it is not a data transfer,
 * not media, not location, and calling it `dataSync` to get past review would be a false declaration.
 *
 * Two things it does that are not detection, both because it is the component that knows:
 *
 *  - **It starts and stops the session service.** A session is opened inside this service's scope, so the
 *    `dataSync` service that legitimises those writes must live and die with it rather than be started
 *    from a screen the user may never open.
 *  - **It starts the background sampler while a session runs**, so thermal and battery warnings have
 *    something watching them during the game rather than only while a screen is open.
 */
@AndroidEntryPoint
class GameDetectionService : GameCoreService() {

    @Inject lateinit var coordinator: GamingCoordinator

    @Inject lateinit var detector: GameDetector

    @Inject lateinit var services: BackgroundServiceGate

    override val notificationId: Int = NotificationChannels.ID_DETECTION

    override val serviceType: Int = TYPE_SPECIAL_USE

    private var state: GamingState = GamingState.IDLE

    /** True once the user has tapped Stop, so [onDestroy] can record who ended the session. */
    private var stoppedByUser = false

    /**
     * "Watching", or what is being played and what happened to its profile.
     *
     * The applied-profile line is [com.gamecore.core.model.ProfileApplication.summary], which counts
     * confirmed changes and names a failure — so a refresh-rate pin the device ignored reads as
     * "3 applied · Refresh rate failed" rather than as a claim the game is running at 120 Hz.
     */
    override fun buildNotification(): Notification {
        val playing = state.playing
            ?: return ServiceNotifications.ongoing(
                context = this,
                channelId = NotificationChannels.DETECTION,
                title = getString(R.string.notification_detection_title),
                text = getString(R.string.notification_detection_text),
                stopTarget = GameDetectionService::class.java,
                notificationId = notificationId,
            )
        val name = ServiceNotifications.gameLabel(state.gameLabel)
            .ifBlank { ServiceNotifications.gameLabel(playing) }
        return ServiceNotifications.ongoing(
            context = this,
            channelId = NotificationChannels.DETECTION,
            title = getString(R.string.notification_detection_playing, name),
            text = state.application?.summary()
                ?: getString(R.string.notification_detection_tracking_only),
            stopTarget = GameDetectionService::class.java,
            notificationId = notificationId,
            hideOnLockScreen = true,
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (!goForeground()) return
        lifecycleScope.launch { watch() }
        lifecycleScope.launch { describe() }
        lifecycleScope.launch { followSession() }
        lifecycleScope.launch { networkAlerts() }
    }

    /**
     * Detects games until the service stops, or stops the service if it cannot.
     *
     * A device with no usage access is not told about here. The Games screen reads the same
     * [GameDetector.availability] and explains the remedy next to the switch that needs it; a permanent
     * notification saying detection does not work would be a notification for a feature that is off.
     */
    private suspend fun watch() {
        if (!detector.availability().isAvailable) {
            stopSelf()
            return
        }
        coordinator.run()
    }

    /** Keeps the notification saying what is true now. */
    private suspend fun describe() {
        coordinator.gaming.collect { current ->
            if (current == state) return@collect
            state = current
            refresh()
        }
    }

    /**
     * Runs the session service for exactly as long as a session row is open.
     *
     * Driven by the recorder's own state rather than by the detected game, because they are not the same
     * thing: a game with session tracking switched off in its profile is played without a session, and a
     * `dataSync` service for writes that are not happening would be a false declaration.
     */
    private suspend fun followSession() {
        coordinator.session
            .map { it != null }
            .distinctUntilChanged()
            .collect { recording ->
                services.setSessionTracking(recording)
                services.syncMonitoring(sessionActive = recording)
            }
    }

    /**
     * Posts the §C5 poor-connection alert whenever the coordinator raises one.
     *
     * On the alerts channel and its own id, not this service's ongoing "Playing X" line: that line is a
     * platform requirement, this is the interruption the user opted into. The coordinator has already
     * rate-limited these to one a minute and only emits while a profile that armed the alert is playing,
     * so there is no gating here — each event becomes one notification.
     *
     * Subscribed for the service's whole lifetime, before any game starts (the source replays nothing), so
     * an alert raised in a session's first seconds is delivered rather than missed. Dropped silently when
     * notifications are denied, like every other post in this app.
     */
    private suspend fun networkAlerts() {
        coordinator.networkAlerts.collect { alert ->
            post(
                NotificationChannels.ID_ALERT_NETWORK,
                ServiceNotifications.alert(
                    context = this,
                    title = getString(R.string.notification_network_alert_title),
                    text = alert.reason,
                ),
            )
        }
    }

    /**
     * Posts an alert on its own id, or drops it if notifications are denied.
     *
     * The same shape as [PerformanceMonitorService]'s: a revoked POST_NOTIFICATIONS must not crash a
     * service mid-session, so the [SecurityException] is swallowed and detection carries on.
     */
    private fun post(id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (denied: SecurityException) {
            // POST_NOTIFICATIONS revoked while running. Detection continues either way.
        }
    }

    /**
     * Remembers that the user asked for this, so the session can record why it ended.
     *
     * The stop itself is not done here. This runs immediately before `stopSelf`, and everything that
     * would close the session lives in the scope that is about to be cancelled; [onDestroy] does it
     * synchronously instead.
     */
    override fun onStopRequested(): Boolean {
        stoppedByUser = true
        return true
    }

    /**
     * Closes an open session before the scope that owns it dies.
     *
     * `runBlocking` on the main thread, deliberately and with a budget — see
     * [GamingCoordinator.shutdown]. The alternative is a session row left open, closed by the next
     * launch's repair pass with "GameCore was stopped by the system" as its reason, which is a false
     * account of a stop the user asked for and of a duration this service knew exactly.
     *
     * The reason distinguishes the two ways to get here. A tapped Stop is the user's; anything else is
     * the system taking the service down mid-game, which is [StopReason.DETECTION_LOST] — GameCore
     * stopped being able to see the game, and the game may well have carried on without it. That reason
     * marks the recorded duration incomplete, which it is.
     */
    override fun onDestroy() {
        runBlocking {
            coordinator.shutdown(
                if (stoppedByUser) StopReason.STOPPED_BY_USER else StopReason.DETECTION_LOST,
            )
        }
        // The session is closed now, so a poor-connection alert about it should not outlive it on screen.
        // It is auto-cancelled on tap; this covers the session that ended with the alert still showing.
        NotificationManagerCompat.from(this).cancel(NotificationChannels.ID_ALERT_NETWORK)
        // The session service is this service's dependent: nothing else starts it, and a session cannot
        // continue without the coordinator that was running here.
        services.setSessionTracking(false)
        services.syncMonitoring(sessionActive = false)
        super.onDestroy()
    }
}
