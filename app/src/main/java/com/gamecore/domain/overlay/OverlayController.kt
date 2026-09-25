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
     * Publishes the overlays the user's preferences ask for, including which crosshair and HUD layout.
     *
     * Called when the app is opened, not from `Application.onCreate` and not from a boot receiver:
     * GameCore has no `RECEIVE_BOOT_COMPLETED`, so an overlay the user enabled comes back the next time
     * they open the app or a tracked game starts, which is the honest behaviour to build the settings
     * copy around.
     *
     * The two ids are the load-bearing part. `activeCrosshairPresetId` and `activeHudLayoutId` are the
     * durable record of what the user chose on the crosshair and HUD screens — `hideEverything` clears
     * them alongside the visibility flags for exactly that reason — and a process that starts without
     * reading them back has a request that says "draw a crosshair" and cannot say which one.
     */
    fun restoreManualState() {
        manual = manual.copy(
            button = preferences.floatingButton.value.show,
            pill = preferences.overlay.value.showPill,
            crosshair = preferences.showCrosshairOverlay,
            crosshairPresetId = preferences.activeCrosshairPresetId ?: manual.crosshairPresetId,
            hud = preferences.showHudOverlay,
            hudLayoutId = preferences.activeHudLayoutId ?: manual.hudLayoutId,
            // The magnifier is deliberately absent: it is a within-session control, not part of the state
            // the next launch rebuilds. Its feed needs a live MediaProjection, and consent for one does not
            // survive the process — restoring the flag would put the loupe's switch on with an empty window
            // behind it and no feed to fill it. It comes back only when the user turns it on again.
        )
        if (!requested.value.fromProfile) publish(manual)
    }

    fun setButton(visible: Boolean) = update { it.copy(button = visible) }

    fun setPill(visible: Boolean) = update { it.copy(pill = visible) }

    /**
     * Shows or hides the crosshair, optionally switching which preset is drawn.
     *
     * A null [presetId] keeps whichever preset was already selected, so the crosshair editor can toggle
     * the live preview without having to re-state its own id on every call — and falls back to the one
     * the user last picked, so the control panel's own toggle draws that rather than whichever preset
     * happens to have the lowest row id.
     */
    fun setCrosshair(visible: Boolean, presetId: Long? = null) = update {
        it.withCrosshair(visible, presetId, preferences.activeCrosshairPresetId)
    }

    fun setHud(visible: Boolean, layoutId: Long? = null) = update {
        it.withHud(visible, layoutId, preferences.activeHudLayoutId)
    }

    /**
     * Shows or hides the pinned magnifier of §13.
     *
     * The crosshair's and HUD's sibling, minus the id: the magnifier has no saved preset to name, so this
     * takes only the flag. It writes the same manual request the others do, so a magnifier switched on by
     * hand survives a profile taking over and coming back, and — through [publish]'s `anythingVisible`
     * check — is enough on its own to keep the service up.
     *
     * It does not itself start the capture feed the loupe draws from; that is the overlay service's job
     * when it reconciles this request, because the feed needs the `MediaProjection` consent the service
     * owns and this class deliberately knows nothing about.
     */
    fun setMagnifier(visible: Boolean) = update { it.withMagnifier(visible) }

    /** Hides everything, manual and profile alike. The panel's own "stop overlay" and Settings' switch. */
    fun hideAll() {
        manual = OverlayRequest.NONE
        publish(OverlayRequest.NONE)
    }

    /**
     * Opens the floating control panel — the Game Mode the app already has.
     *
     * The Quick Trigger's destination. It does not build a second panel and does not take a different
     * route to this one: it puts the same request on screen the floating button puts there, so whatever
     * the user has configured in the panel is what the trigger opens.
     *
     * Returns false when the overlay permission is missing or the system refused the service start, which
     * is the caller's cue to open the app instead of leaving the user with a shortcut that did nothing.
     */
    fun openPanel(): Boolean = requestPanel(ACTION_OPEN_PANEL)

    /** The same, but a second trigger closes the panel again. */
    fun togglePanel(): Boolean = requestPanel(ACTION_TOGGLE_PANEL)

    private fun requestPanel(action: String): Boolean {
        if (!permissions.hasOverlayPermission()) return false
        // The panel is positioned against the floating button and has nothing to anchor to without one,
        // so asking for the panel is also asking for the button. Through `update` rather than a direct
        // publish, so that a panel opened during a game does not erase what the profile asked for.
        if (!requested.value.button) update { it.copy(button = true) }
        return try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GamingOverlayService::class.java).setAction(action),
            )
            true
        } catch (refused: Exception) {
            false
        }
    }

    /**
     * Puts the overlays a game's profile asks for on screen.
     *
     * The label comes from the caller rather than from `profile.label` because the coordinator resolves
     * the installed application label first — a game renamed by an update should appear in the panel
     * header under the name the user sees in their launcher.
     *
     * A profile that switches the crosshair on without naming a preset — the switch is above the picker,
     * so it is on before there is anything to pick from, and "None" is the picker's first option — falls
     * back to the one the user last chose rather than to whatever the renderer would guess.
     */
    fun applyProfile(profile: GameProfile, gameLabel: String) {
        publish(
            OverlayRequest(
                button = profile.showFloatingButton,
                pill = profile.showPerformancePill,
                crosshair = profile.showCrosshair,
                hud = profile.hudLayoutId != null,
                crosshairPresetId = profile.crosshairPresetId
                    ?: preferences.activeCrosshairPresetId,
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

    companion object {
        /**
         * The two actions this class sends the overlay service, beside a plain start.
         *
         * Declared here rather than in the service because this is the only class in the app that starts
         * it, so the protocol has exactly one writer and one reader. The service is not exported, so
         * these are a defence against our own stale `PendingIntent`s rather than against another app.
         */
        const val ACTION_OPEN_PANEL = "com.gamecore.action.OPEN_PANEL"

        const val ACTION_TOGGLE_PANEL = "com.gamecore.action.TOGGLE_PANEL"
    }
}
