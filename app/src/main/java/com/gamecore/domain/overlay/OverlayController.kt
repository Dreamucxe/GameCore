package com.gamecore.domain.overlay

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.OverlayRequest
import com.gamecore.core.model.OverlayStatus
import com.gamecore.core.permissions.PermissionChecker
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.service.GamingOverlayService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What should be on screen, and the only thing that starts or stops the overlay service.
 *
 * The service is a renderer: it draws whatever [desired] says and reports back what it managed to draw
 * through [report]. Everything that wants an overlay — the settings screens, the crosshair editor's
 * "show now", a game profile, the control panel's own toggles — writes here instead, which is what
 * keeps two of them from fighting over `WindowManager`.
 *
 * It lives in `domain` rather than in `core.overlay` because it reads preferences, and `core` must not
 * depend on `data`. The trade is that this class knows the name of a service class; that is deliberate
 * and contained — one `startForegroundService` call site in the whole app, so a ViewModel never has to
 * reach for a `Context` to make a crosshair appear.
 *
 * Manual state and profile state are kept apart. A profile that switches the crosshair on for one game
 * must not leave it on for the desktop afterwards, so [applyProfile] publishes without touching the
 * remembered manual request and [clearProfile] republishes that. §26's "stop when not needed" falls out
 * of the same mechanism: when the published request asks for nothing, the service is stopped rather
 * than left running with no windows.
 */
@Singleton
class OverlayController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: SecurePreferenceStore,
    private val permissions: PermissionChecker,
) {

    /** What the user asked for by hand, restored when a game's profile stops driving the overlay. */
    private var manual: OverlayRequest = OverlayRequest.NONE

    private val requested = MutableStateFlow(OverlayRequest.NONE)

    /** Read by [GamingOverlayService]; the windows follow it. */
    val desired: StateFlow<OverlayRequest> = requested.asStateFlow()

    private val reported = MutableStateFlow(OverlayStatus.OFF)

    /** What is actually up, for the dashboard. Written by the service, never guessed here. */
    val status: StateFlow<OverlayStatus> = reported.asStateFlow()

    fun hasPermission(): Boolean = permissions.hasOverlayPermission()

    /**
     * Publishes the button and pill the user's preferences ask for.
     *
     * Called when the app is opened, not from `Application.onCreate` and not from a boot receiver:
     * GameCore has no `RECEIVE_BOOT_COMPLETED`, so an overlay the user enabled comes back the next time
     * they open the app or a tracked game starts, which is the honest behaviour to build the settings
     * copy around.
     */
    fun restoreManualState() {
        val button = preferences.floatingButton.value.show
        val pill = preferences.overlay.value.showPill
        manual = manual.copy(button = button, pill = pill)
        if (!requested.value.fromProfile) publish(manual)
    }

    fun setButton(visible: Boolean) = update { it.copy(button = visible) }

    fun setPill(visible: Boolean) = update { it.copy(pill = visible) }

    /**
     * Shows or hides the crosshair, optionally switching which preset is drawn.
     *
     * A null [presetId] keeps whichever preset was already selected, so the crosshair editor can toggle
     * the live preview without having to re-state its own id on every call.
     */
    fun setCrosshair(visible: Boolean, presetId: Long? = null) = update {
        it.copy(crosshair = visible, crosshairPresetId = presetId ?: it.crosshairPresetId)
    }

    fun setHud(visible: Boolean, layoutId: Long? = null) = update {
        it.copy(hud = visible, hudLayoutId = layoutId ?: it.hudLayoutId)
    }

    /** Hides everything, manual and profile alike. The panel's own "stop overlay" and Settings' switch. */
    fun hideAll() {
        manual = OverlayRequest.NONE
        publish(OverlayRequest.NONE)
    }

    /**
     * Puts the overlays a game's profile asks for on screen.
     *
     * The label comes from the caller rather than from `profile.label` because the coordinator resolves
     * the installed application label first — a game renamed by an update should appear in the panel
     * header under the name the user sees in their launcher.
     */
    fun applyProfile(profile: GameProfile, gameLabel: String) {
        publish(
            OverlayRequest(
                button = profile.showFloatingButton,
                pill = profile.showPerformancePill,
                crosshair = profile.showCrosshair,
                hud = profile.hudLayoutId != null,
                crosshairPresetId = profile.crosshairPresetId,
                hudLayoutId = profile.hudLayoutId,
                gameLabel = gameLabel,
                fromProfile = true,
            ),
        )
    }

    /** Called when the game stops. Returns the overlay to whatever the user had asked for by hand. */
    fun clearProfile() {
        if (!requested.value.fromProfile) return
        publish(manual)
    }

    /**
     * The service's report of what it managed to put up.
     *
     * Taken as given rather than merged with [desired]: the difference between the two is the whole
     * point — a request for four windows that produced none because the permission was revoked mid-game
     * is exactly what the dashboard needs to be able to say.
     */
    fun report(status: OverlayStatus) {
        reported.value = status
    }

    private inline fun update(transform: (OverlayRequest) -> OverlayRequest) {
        val next = transform(requested.value)
        if (!next.fromProfile) manual = next
        publish(next)
    }

    /**
     * Publishes a request and brings the service into line with it.
     *
     * The order matters on the way up — the request has to be visible before the service reads it,
     * because the service composes from [desired] the moment it starts — and on the way down the stop
     * is what disposes the windows, so the request is cleared first and the service tears down after.
     */
    private fun publish(next: OverlayRequest) {
        requested.value = next
        val permitted = permissions.hasOverlayPermission()
        if (next.anythingVisible && permitted) {
            start()
        } else {
            stop()
            reported.value = OverlayStatus(serviceRunning = false, hasPermission = permitted)
        }
    }

    /**
     * Starts the overlay service, or reports why it could not be.
     *
     * `startForegroundService` throws when the app is in the background on API 31+. The reachable case
     * is not a game launch — an app running a foreground service is exempt, and the detection service is
     * one — but a stale notification action or a widget could still land here from a cold background,
     * and a crash in that path would take the app down for a floating button.
     */
    private fun start() {
        val intent = Intent(context, GamingOverlayService::class.java)
        val started = try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (refused: Exception) {
            false
        }
        if (!started) {
            reported.value = OverlayStatus(serviceRunning = false, hasPermission = true)
        }
    }

    private fun stop() {
        try {
            context.stopService(Intent(context, GamingOverlayService::class.java))
        } catch (refused: SecurityException) {
            // A service the system has already torn down. Nothing to stop and nothing to report.
        }
    }
}
