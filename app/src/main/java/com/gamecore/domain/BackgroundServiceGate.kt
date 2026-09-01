package com.gamecore.domain

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.GameProfileRepository
import com.gamecore.service.GameDetectionService
import com.gamecore.service.PerformanceMonitorService
import com.gamecore.service.SessionTrackingService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides which background services should be running, and is the only thing that starts them.
 *
 * The overlay has its own gate — [com.gamecore.domain.overlay.OverlayController], which owns the request
 * the overlay service renders — and this is the equivalent for the other three. Same reason: a ViewModel
 * should never need a `Context` to switch a feature on, and `startForegroundService` is a call that
 * throws in states that are easy to reach and hard to test, so it belongs in one place with one set of
 * guards around it.
 *
 * Every decision here is a *derivation* rather than a switch of its own. §26 asks that a service stops
 * when it is not needed, and the way to make that true is to have nothing that can turn a service on
 * except the conditions that justify it:
 *
 *  - **Detection** runs when the user wants profiles applied or sessions recorded *and* at least one
 *    enabled profile exists. Watching the foreground app for a list of zero games is pure drain.
 *  - **Monitoring** runs when the user has asked for background sampling, or while a session is being
 *    recorded and thermal warnings are on — the only two reasons to keep the sample loop alive with
 *    nothing on screen reading it.
 *  - **Session tracking** runs exactly as long as there is a session row being written to.
 *
 * None of the three is remembered anywhere. There is no "detection enabled" flag to drift out of step
 * with the services that are actually up: the answer is recomputed from the settings and the profile
 * table every time anything asks.
 */
@Singleton
class BackgroundServiceGate @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: SecurePreferenceStore,
    private val profiles: GameProfileRepository,
) {

    /**
     * Starts or stops game detection to match the settings, and says which it did.
     *
     * Suspends because it reads the profile table. Called on launch, after a profile is saved or
     * deleted, and after either of the two settings that justify it changes — so a user who switches
     * automatic profiles off does not have to know that a service existed in order to have it stopped.
     *
     * The returned value is "should be running", not "is running": whether the system honoured the
     * start is reported by the service itself through its notification, and a start refused in the
     * background is handled where it happens rather than guessed at here.
     */
    suspend fun syncDetection(): Boolean {
        val settings = preferences.settings.value
        val wanted = (settings.autoApplyProfiles || settings.trackSessions) &&
            profiles.enabledProfiles().isNotEmpty()
        return apply(GameDetectionService::class.java, wanted)
    }

    /**
     * Starts or stops the background sampler.
     *
     * [sessionActive] is passed in rather than read, because the only caller that knows it is the
     * detection service — which owns the session — and a second source of truth for "a session is
     * running" is exactly the kind of thing that ends up stuck on.
     */
    fun syncMonitoring(sessionActive: Boolean): Boolean =
        apply(PerformanceMonitorService::class.java, wantsMonitoring(sessionActive))

    /**
     * Whether the background sampler has a reason to exist right now.
     *
     * Public because [PerformanceMonitorService] asks the same question of itself: it watches the two
     * settings behind this and stops when the answer turns false, which is how a background-monitoring
     * toggle switched off from the Performance screen takes the service down without that screen
     * knowing a service was involved. One predicate, so the condition that starts it and the condition
     * that keeps it alive cannot drift apart into a service that will not stop.
     */
    fun wantsMonitoring(sessionActive: Boolean): Boolean {
        val settings = preferences.settings.value
        return settings.backgroundMonitoring || (sessionActive && settings.showThermalWarnings)
    }

    /** Starts or stops the session service, which exists for exactly as long as a session does. */
    fun setSessionTracking(active: Boolean): Boolean =
        apply(SessionTrackingService::class.java, active)

    private fun apply(service: Class<out Service>, wanted: Boolean): Boolean {
        if (!wanted) {
            stop(service)
            return false
        }
        return start(service)
    }

    /**
     * Starts a foreground service, or reports that it could not be started.
     *
     * `startForegroundService` throws `ForegroundServiceStartNotAllowedException` when the app has no
     * foreground standing on API 31+. Every intended caller has some — an Activity on screen, or a
     * service already running — but a stale notification action or a launcher shortcut can reach this
     * from a cold background, and crashing there would take the app down for a feature the user was not
     * even looking at.
     */
    private fun start(service: Class<out Service>): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, service))
        true
    } catch (refused: Exception) {
        false
    }

    private fun stop(service: Class<out Service>) {
        try {
            context.stopService(Intent(context, service))
        } catch (refused: SecurityException) {
            // Already gone. Nothing to stop.
        }
    }
}
