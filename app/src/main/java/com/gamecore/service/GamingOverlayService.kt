package com.gamecore.service

import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gamecore.MainActivity
import com.gamecore.R
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.NotificationChannels
import com.gamecore.core.common.unavailabilityText
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.AccentChoice
import com.gamecore.core.model.CrosshairPreset
import com.gamecore.core.model.FloatingButtonConfig
import com.gamecore.core.model.HudLayout
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.OverlayConfig
import com.gamecore.core.model.OverlayStatus
import com.gamecore.core.overlay.CrosshairOverlay
import com.gamecore.core.overlay.FloatingGameButton
import com.gamecore.core.overlay.HudOverlay
import com.gamecore.core.overlay.OverlayAction
import com.gamecore.core.overlay.OverlayControlPanel
import com.gamecore.core.overlay.OverlayFrame
import com.gamecore.core.overlay.OverlayLevel
import com.gamecore.core.overlay.OverlayLevelState
import com.gamecore.core.overlay.OverlayPanelState
import com.gamecore.core.overlay.OverlaySlot
import com.gamecore.core.overlay.OverlayViewHost
import com.gamecore.core.overlay.OverlayWindowSpec
import com.gamecore.core.overlay.OverlayWindows
import com.gamecore.core.overlay.PANEL_WIDTH_DP
import com.gamecore.core.overlay.PerformancePill
import com.gamecore.core.permissions.PermissionChecker
import com.gamecore.core.system.AudioControls
import com.gamecore.core.system.ControlOutcome
import com.gamecore.core.system.DisplayControls
import com.gamecore.core.system.DisplayReader
import com.gamecore.core.system.ScreenCaptureController
import com.gamecore.core.system.ScreenRotation
import com.gamecore.core.system.TorchControls
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.CrosshairRepository
import com.gamecore.data.repository.HudLayoutRepository
import com.gamecore.domain.gaming.GamingCoordinator
import com.gamecore.domain.monitoring.HudStatReader
import com.gamecore.domain.monitoring.PerformanceMonitor
import com.gamecore.domain.monitoring.StatReading
import com.gamecore.domain.overlay.OverlayController
import com.gamecore.ui.capture.CaptureConsentActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The service that owns every overlay window.
 *
 * It is a renderer and nothing else. [OverlayController.desired] says which of the five windows should
 * be up; this puts them up, reports back what it managed, and stops itself when the answer is none.
 * Keeping the decision outside means the crosshair editor, the settings screens and a game profile all
 * change the overlay the same way, and none of them holds a `WindowManager`.
 *
 * `specialUse` is the foreground service type, declared here and in the manifest with a justification.
 * Android 14 has no type for "draws over other apps"; `specialUse` is what the platform documents for
 * exactly that case. The notification names what is on screen rather than saying "service running",
 * because it is the only handle the user has on a window they may not recognise.
 *
 * Two performance rules the class is built around:
 *
 *  - **The sampler runs only when something reads it.** [PerformanceMonitor.snapshots] publishes through
 *    `WhileSubscribed`, so subscribing is what starts the shared sampling loop. This subscribes when the
 *    pill, the HUD or the panel is up and not otherwise — a floating button on its own costs nothing.
 *  - **Windows are moved, never re-added.** `updateViewLayout` keeps the composition; removing and
 *    re-adding a `ComposeView` restarts it, which during a drag is a flicker per frame.
 *
 * Actions from the control panel are executed here through the same `core.system` controllers the rest
 * of the app uses, and a [ControlOutcome] that is not applied becomes a toast naming the reason. §32's
 * "no button that does nothing" is enforced twice over: an action the device cannot perform is greyed
 * with its reason before it is tapped, and one that fails anyway says so.
 */
@AndroidEntryPoint
class GamingOverlayService : GameCoreService() {

    @Inject lateinit var preferences: SecurePreferenceStore

    @Inject lateinit var permissions: PermissionChecker

    @Inject lateinit var overlays: OverlayController

    @Inject lateinit var monitor: PerformanceMonitor

    @Inject lateinit var coordinator: GamingCoordinator

    @Inject lateinit var crosshairs: CrosshairRepository

    @Inject lateinit var hudLayouts: HudLayoutRepository

    @Inject lateinit var audio: AudioControls

    @Inject lateinit var displayControls: DisplayControls

    @Inject lateinit var displayReader: DisplayReader

    @Inject lateinit var torch: TorchControls

    @Inject lateinit var capture: ScreenCaptureController

    override val notificationId: Int = NotificationChannels.ID_OVERLAY

    override val serviceType: Int = TYPE_SPECIAL_USE

    /** One host for all five windows; see [OverlayViewHost] for why it is not one per window. */
    private val host = OverlayViewHost()

    private var windows: OverlayWindows? = null

    private val pillReadings = MutableStateFlow<List<StatReading>>(emptyList())

    private val hudReadings = MutableStateFlow<Map<HudStat, StatReading>>(emptyMap())

    private val crosshairPreset = MutableStateFlow<CrosshairPreset?>(null)

    private val hudLayout = MutableStateFlow<HudLayout?>(null)

    private val panel = MutableStateFlow(OverlayPanelState.EMPTY)

    /**
     * Reasons learned from an action that was actually tried and refused.
     *
     * A capability like "can this device write `user_rotation`" cannot be answered honestly without
     * trying: the write goes through the elevated shell when there is one, so checking `WRITE_SETTINGS`
     * alone would grey out a button that works fine under Shizuku. So the first attempt is allowed, its
     * [ControlOutcome] becomes a toast, and if the reason was structural — an access that is missing, or
     * hardware that is absent — it is remembered here and the button is greyed with it from then on.
     * Cleared whenever the panel is opened, because by then the user may have granted the access.
     */
    private val denied = mutableMapOf<OverlayAction, String>()

    /**
     * The same memory for the two sliders.
     *
     * Volume is the case that makes it necessary. `setStreamVolume` throws while Do Not Disturb is
     * silencing the device, and the state that would have predicted it — [AudioControls.doNotDisturbState]
     * — is itself unreadable without notification-policy access, which is exactly the situation in which
     * the throw happens. So the first drag is allowed to try, and its refusal is what dims the slider.
     */
    private val deniedLevels = mutableMapOf<OverlayLevel, String>()

    private val panelOpen = MutableStateFlow(false)

    private val accent = MutableStateFlow(Color(AccentChoice.CYAN.argb))

    private var panelJob: Job? = null

    /**
     * True between the first drag frame and the finger lifting.
     *
     * Without it, a preference write that lands mid-drag — the pill's position, an accent change — would
     * reconcile the button back to its stored coordinate and the user would watch it snap out from under
     * their finger.
     */
    private var draggingButton = false

    /**
     * The frame the stored button and pill coordinates were last placed in.
     *
     * Kept so a rotation can go through [OverlayFrame.rescaleFrom] rather than [OverlayFrame.place]. The
     * difference is not cosmetic: a button parked at x = 1032 on a 1080-wide screen is at the right edge,
     * and re-snapping that same 1032 on a 2400-wide screen puts it on the *left*, because its centre is
     * now in the left half. Rescaling from the old frame keeps the side the user chose.
     */
    private var lastButtonFrame: OverlayFrame? = null

    private var lastPillFrame: OverlayFrame? = null

    private var status = OverlayStatus.OFF

    override fun buildNotification() = ServiceNotifications.ongoing(
        context = this,
        channelId = NotificationChannels.OVERLAY,
        title = getString(R.string.notification_overlay_title),
        text = status.summary,
        stopTarget = GamingOverlayService::class.java,
        notificationId = notificationId,
    )

    override fun onCreate() {
        super.onCreate()
        if (!goForeground()) return
        host.start()
        windows = OverlayWindows(this, permissions)
        observe()
    }

    override fun onStopRequested(): Boolean {
        // The user's Stop is a decision about the overlay, not about this process: clearing the request
        // is what keeps the button from coming straight back the next time a profile is applied.
        overlays.hideAll()
        return true
    }

    /**
     * Re-places the windows after a rotation.
     *
     * The button and the pill hold pixel coordinates, so a device that was 1080 wide and is now 2400 has
     * a button parked in the middle of the screen and a pill off the right edge. Rescaling carries both
     * across proportionally; the crosshair and the HUD are full-screen and position their own content, so
     * they need nothing. The panel is closed rather than moved — it is anchored to the button, and the
     * button is about to be somewhere else.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        closePanel()
        rescaleWindows()
        reconcile()
    }

    override fun onDestroy() {
        panelJob?.cancel()
        windows?.hideAll()
        windows = null
        // After the windows, never before: `DESTROYED` disposes every composition, and a composition
        // disposed while its view is still attached to the WindowManager leaves a blank window behind.
        host.stop()
        overlays.report(OverlayStatus(serviceRunning = false, hasPermission = permissions.hasOverlayPermission()))
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------- observation

    private fun observe() {
        lifecycleScope.launch { overlays.desired.collect { reconcile() } }
        lifecycleScope.launch { preferences.floatingButton.collect { reconcile() } }
        lifecycleScope.launch { preferences.overlay.collect { reconcile() } }
        lifecycleScope.launch {
            preferences.settings
                .map { it.accent }
                .distinctUntilChanged()
                .collect { accent.value = Color(it.argb) }
        }
        lifecycleScope.launch { resolveCrosshair() }
        lifecycleScope.launch { resolveHud() }
        lifecycleScope.launch { streamReadings() }
        lifecycleScope.launch {
            // The panel header names the game and its session timer; both change without any overlay
            // window changing, so the panel state is refreshed rather than the windows reconciled.
            coordinator.gaming.collect { if (panelOpen.value) probePanel() }
        }
    }

    /**
     * Loads the crosshair a request names, or the first saved one when it names none.
     *
     * Falling back to the first preset rather than to nothing: the request can come from a game profile
     * whose chosen preset was deleted, and a crosshair the user asked for that silently does not appear
     * is worse than one that is not the exact design they picked. The repository seeds defaults, so
     * "first saved preset" is never empty in practice.
     *
     * Combined against the saved list rather than read once per request, so a preset edited while its
     * crosshair is on screen redraws without the window being taken down and put back. The crosshair screen
     * stores a change as it is made and a slider when it is released, so that is the granularity a user
     * sees over the game. The trailing `distinctUntilChanged` stops a re-emission that changed nothing —
     * Room republishes the whole table on any write — from reconciling the windows again.
     */
    private suspend fun resolveCrosshair() {
        combine(
            overlays.desired.map { it.crosshair to it.crosshairPresetId }.distinctUntilChanged(),
            crosshairs.presets,
        ) { (wanted, id), saved ->
            if (!wanted) null else saved.firstOrNull { it.id == id } ?: saved.firstOrNull()
        }
            .distinctUntilChanged()
            .collect { preset ->
                crosshairPreset.value = preset
                reconcile()
            }
    }

    /**
     * Loads the HUD layout a request names.
     *
     * No fallback here, unlike the crosshair: a HUD is the user's own arrangement of widgets, and showing
     * a different layout than the one a profile asked for would put stats they did not choose over their
     * game. A missing layout means no HUD window and a greyed HUD button in the panel.
     *
     * Observed rather than read once, for the same reason as the crosshair: a widget moved in the HUD
     * builder moves on the live overlay when the builder saves.
     */
    private suspend fun resolveHud() {
        combine(
            overlays.desired.map { it.hud to it.hudLayoutId }.distinctUntilChanged(),
            hudLayouts.layouts,
        ) { (wanted, id), saved ->
            when {
                !wanted -> null
                id != null -> saved.firstOrNull { it.id == id }
                else -> saved.firstOrNull()
            }
        }
            .distinctUntilChanged()
            .collect { layout ->
                hudLayout.value = layout
                reconcile()
            }
    }

    /**
     * Keeps the pill, HUD and panel readings current while any of them is on screen.
     *
     * The empty `collect` is deliberate and load-bearing. [PerformanceMonitor.snapshots] is a
     * `WhileSubscribed` stream, so holding a subscription is what keeps the one shared sampling loop
     * alive — and the loop underneath reads its latest value at the pill's own configured interval
     * instead of on every sample, which is what §8's update-interval setting is for. Sampling cadence
     * and display cadence are separate knobs, and this is the seam between them.
     */
    private suspend fun streamReadings() {
        combine(overlays.desired, panelOpen) { request, open -> request.pill || request.hud || open }
            .distinctUntilChanged()
            .collectLatest { needed ->
                if (!needed) {
                    pillReadings.value = emptyList()
                    hudReadings.value = emptyMap()
                    return@collectLatest
                }
                coroutineScope {
                    launch { monitor.snapshots.collect { } }
                    while (isActive) {
                        publishReadings()
                        delay(preferences.overlay.value.normalised().updateIntervalMillis)
                    }
                }
            }
    }

    private fun publishReadings() {
        val snapshot = monitor.snapshots.value
        val elapsed = sessionElapsedMillis()
        pillReadings.value = HudStatReader.readAll(
            stats = preferences.overlay.value.normalised().stats,
            snapshot = snapshot,
            sessionElapsedMillis = elapsed,
        )
        val wanted = hudLayout.value?.widgets?.map { it.stat }?.distinct() ?: emptyList()
        hudReadings.value = wanted.associateWith { HudStatReader.read(it, snapshot, elapsed) }
    }

    private fun sessionElapsedMillis(): Long? {
        val started = coordinator.session.value?.startedAtMillis ?: return null
        return (System.currentTimeMillis() - started).coerceAtLeast(0L)
    }

    private fun sessionElapsedLabel(): String? = sessionElapsedMillis()?.let(Formatters::duration)

    // ------------------------------------------------------------------------------- reconciling

    /**
     * Brings the windows in line with the request. The only place anything is added or removed.
     *
     * Called from every observer rather than each one changing its own window, because the decisions
     * interact: the panel belongs to the button, the pill's position is clamped against the screen, and
     * a request that asks for nothing has to stop the service. One function that reads the current
     * request and the current preferences and makes the screen match is easier to be sure of than six
     * that each move one piece.
     */
    private fun reconcile() {
        val manager = windows ?: return
        val request = overlays.desired.value
        // Two ways to end up with nothing on screen, both of them a stop. Either the user (or a profile
        // ending) asked for nothing, or the overlay permission was revoked from Settings while this ran —
        // in which case `addView` would throw `BadTokenException` on the next attempt anyway. `onDestroy`
        // reports the final status, and it reads the permission, so the dashboard says which it was.
        if (!request.anythingVisible || !manager.canDraw()) {
            closePanel()
            manager.hideAll()
            stopSelf()
            return
        }
        val button = preferences.floatingButton.value.normalised()
        val pill = preferences.overlay.value.normalised()
        if (request.button) {
            showButton(button)
        } else {
            closePanel()
            manager.hide(OverlaySlot.BUTTON)
        }
        if (request.pill) showPill(pill) else manager.hide(OverlaySlot.PILL)
        if (request.crosshair && crosshairPreset.value != null) {
            showCrosshair()
        } else {
            manager.hide(OverlaySlot.CROSSHAIR)
        }
        if (request.hud && hudLayout.value != null) showHud() else manager.hide(OverlaySlot.HUD)
        publishStatus()
    }

    /**
     * Carries the two positioned windows onto a screen that changed size.
     *
     * The rescaled position is written back to preferences along with the frame it was computed in, so
     * the stored coordinate and [lastButtonFrame] always describe the same screen. Without the write the
     * next reconcile would place the button from the old orientation's coordinate again and undo this.
     */
    private fun rescaleWindows() {
        val manager = windows ?: return
        val previousButton = lastButtonFrame
        val currentButton = buttonFrame()
        if (previousButton != null && currentButton != null && manager.isVisible(OverlaySlot.BUTTON)) {
            val config = preferences.floatingButton.value.normalised()
            val placed = currentButton.rescaleFrom(previousButton, config.x, config.y, config.snapToEdge)
            manager.move(OverlaySlot.BUTTON, placed.x, placed.y)
            lastButtonFrame = currentButton
            preferences.updateButtonPosition(placed.x, placed.y)
        }
        val previousPill = lastPillFrame
        val currentPill = manager.frameFor(OverlaySlot.PILL)
        if (previousPill != null && currentPill != null && manager.isVisible(OverlaySlot.PILL)) {
            val config = preferences.overlay.value.normalised()
            // The pill never snaps: it is not draggable at the edge of a game and the user placed it
            // where they placed it. Proportional is the whole behaviour.
            val placed = currentPill.rescaleFrom(previousPill, config.pillX, config.pillY, snapToEdge = false)
            manager.move(OverlaySlot.PILL, placed.x, placed.y)
            lastPillFrame = currentPill
            preferences.updatePillPosition(placed.x, placed.y)
        }
    }

    // ---------------------------------------------------------------------------------- windows

    /**
     * Adds the button, or moves the one that is up.
     *
     * The window is added once and the composable reads its own config from the preference flow, so
     * changing the size or the opacity recomposes rather than re-adding — see [OverlayWindows.move].
     * The position is applied from the outside because clamping and snapping need the screen, which the
     * composable deliberately knows nothing about.
     */
    private fun showButton(config: FloatingButtonConfig) {
        val manager = windows ?: return
        val frame = buttonFrame() ?: return
        lastButtonFrame = frame
        val placement = frame.place(config.x, config.y, config.snapToEdge)
        if (manager.isVisible(OverlaySlot.BUTTON)) {
            // Not while a finger is on it: a preference write landing mid-drag would otherwise pull the
            // button back to its stored coordinate from under the user.
            if (!draggingButton) manager.move(OverlaySlot.BUTTON, placement.x, placement.y)
            return
        }
        manager.show(
            slot = OverlaySlot.BUTTON,
            spec = OverlayWindowSpec(x = placement.x, y = placement.y, touchable = true),
            host = host,
        ) {
            val live by preferences.floatingButton.collectAsState()
            val tint by accent.collectAsState()
            val expanded by panelOpen.collectAsState()
            FloatingGameButton(
                config = live.normalised(),
                accent = tint,
                positionProvider = ::buttonOffset,
                onDragTo = ::dragButtonTo,
                onDragFinished = ::finishButtonDrag,
                onTap = ::togglePanel,
                isExpanded = expanded,
            )
        }
    }

    /**
     * The screen as the button sees it — measured from the live view when there is one, and from the
     * configured size in dp before it has been added.
     */
    private fun buttonFrame(): OverlayFrame? {
        val manager = windows ?: return null
        val margin = px(EDGE_MARGIN_DP)
        manager.frameFor(OverlaySlot.BUTTON, margin)?.let { return it }
        val size = px(preferences.floatingButton.value.normalised().sizeDp)
        return manager.frameFor(size, size, margin)
    }

    /** Where the button is right now, for a drag that must start from the window's real position. */
    private fun buttonOffset(): IntOffset {
        val live = windows?.positionOf(OverlaySlot.BUTTON)
        if (live != null) return IntOffset(live.x, live.y)
        val config = preferences.floatingButton.value
        return IntOffset(config.x, config.y)
    }

    /**
     * One drag frame: clamp to the screen and move the window. Nothing is written.
     *
     * A write per frame would be a keystore round trip per touch event, and snapping mid-drag would fight
     * the finger — both belong in [finishButtonDrag].
     */
    private fun dragButtonTo(x: Int, y: Int) {
        val manager = windows ?: return
        draggingButton = true
        // The panel is anchored to where the button was. Closing beats dragging a stale anchor around.
        closePanel()
        val clamped = buttonFrame()?.clamp(x, y) ?: return
        manager.move(OverlaySlot.BUTTON, clamped.x, clamped.y)
    }

    /** The finger lifted: snap if the user wants snapping, then persist once. */
    private fun finishButtonDrag(x: Int, y: Int) {
        val manager = windows ?: return
        draggingButton = false
        val config = preferences.floatingButton.value.normalised()
        val frame = buttonFrame() ?: return
        lastButtonFrame = frame
        val placed = frame.place(x, y, config.snapToEdge)
        manager.move(OverlaySlot.BUTTON, placed.x, placed.y)
        preferences.updateButtonPosition(placed.x, placed.y)
    }

    /**
     * Adds the pill, or moves the one that is up.
     *
     * Clamped, never snapped: the pill is not draggable over a game — its position comes from the overlay
     * settings screen — so the only job here is keeping a coordinate stored on one screen size on screen
     * at another. Before the first layout the frame has no window size to clamp against, which leaves the
     * user's coordinate untouched; the next reconcile has the measured width and does it properly.
     */
    private fun showPill(config: OverlayConfig) {
        val manager = windows ?: return
        val frame = manager.frameFor(OverlaySlot.PILL) ?: manager.frameFor(0, 0)
        lastPillFrame = frame
        val placement = frame.clamp(config.pillX, config.pillY)
        if (manager.isVisible(OverlaySlot.PILL)) {
            manager.move(OverlaySlot.PILL, placement.x, placement.y)
            return
        }
        manager.show(
            slot = OverlaySlot.PILL,
            spec = OverlayWindowSpec(x = placement.x, y = placement.y),
            host = host,
        ) {
            val readings by pillReadings.collectAsState()
            val live by preferences.overlay.collectAsState()
            PerformancePill(readings = readings, config = live.normalised())
        }
    }

    /**
     * Adds the crosshair window once and leaves it alone.
     *
     * The composable reads the preset from [crosshairPreset], so editing a design in the crosshair editor
     * redraws this window instead of replacing it. That is what makes the editor's live preview *over the
     * game* smooth rather than a flicker per slider movement.
     */
    private fun showCrosshair() {
        val manager = windows ?: return
        if (manager.isVisible(OverlaySlot.CROSSHAIR)) return
        manager.show(OverlaySlot.CROSSHAIR, OverlayWindowSpec(fullScreen = true), host) {
            val preset by crosshairPreset.collectAsState()
            preset?.let { CrosshairOverlay(preset = it) }
        }
    }

    /** As [showCrosshair], for the HUD: added once, redrawn from [hudLayout] and [hudReadings]. */
    private fun showHud() {
        val manager = windows ?: return
        if (manager.isVisible(OverlaySlot.HUD)) return
        manager.show(OverlaySlot.HUD, OverlayWindowSpec(fullScreen = true), host) {
            val layout by hudLayout.collectAsState()
            val readings by hudReadings.collectAsState()
            layout?.let { HudOverlay(layout = it, readings = readings) }
        }
    }

    /**
     * Tells [OverlayController] what is actually on screen, and re-titles the notification to match.
     *
     * Only when it changed. The status is recomputed on every reconcile, and a reconcile happens on every
     * drag frame; re-posting an identical notification sixty times a second is not free and shows up as a
     * notification-shade flicker on some ROMs.
     */
    private fun publishStatus() {
        val manager = windows
        val visible = manager?.visible ?: emptySet()
        val next = OverlayStatus(
            serviceRunning = true,
            buttonVisible = OverlaySlot.BUTTON in visible,
            pillVisible = OverlaySlot.PILL in visible,
            crosshairVisible = OverlaySlot.CROSSHAIR in visible,
            hudVisible = OverlaySlot.HUD in visible,
            hasPermission = manager?.canDraw() ?: false,
        )
        if (next == status) return
        status = next
        overlays.report(next)
        refresh()
    }

    // ------------------------------------------------------------------------------------ panel

    private fun togglePanel() {
        if (panelOpen.value) closePanel() else openPanel()
    }

    /**
     * Opens the panel directly against the floating button.
     *
     * Below it when there is room, above it when there is not, and centred on it horizontally — the panel
     * belongs to that button, and a panel that appears somewhere else on screen makes the user look for
     * what they just opened.
     *
     * Neither y is an estimate, and neither needs the panel's height, which is the one measurement that
     * cannot be known before it is laid out: opening downwards is `buttonBottom + gap` with the window's
     * top edge anchored, and opening upwards is `screenHeight - (buttonTop - gap)` with its *bottom* edge
     * anchored — see [OverlayWindowSpec.anchorBottom]. The window system then does the subtraction with
     * the real measurement.
     *
     * What the height does decide is how much of the panel fits, so the room on the chosen side is passed
     * down as the panel's cap. A button parked near the bottom of the screen gets a shorter, scrolling
     * panel rather than one whose last row of buttons is under the edge.
     */
    private fun openPanel() {
        val manager = windows ?: return
        val width = px(PANEL_WIDTH_DP)
        val margin = px(EDGE_MARGIN_DP)
        val gap = px(PANEL_GAP_DP)
        val frame = manager.frameFor(width, 0, margin)
        val placement = manager.positionOf(OverlaySlot.BUTTON)
        val button = manager.frameFor(OverlaySlot.BUTTON)
        val buttonTop = placement?.y ?: 0
        val buttonHeight = button?.windowHeight ?: 0
        val buttonCentreX = (placement?.x ?: 0) + (button?.windowWidth ?: 0) / 2

        val roomBelow = frame.screenHeight - (buttonTop + buttonHeight + gap) - margin
        val roomAbove = buttonTop - gap - margin
        // Below unless below is both too thin to be worth it and worse than above: the user asked for the
        // panel under the icon, so above is the exception rather than the better half of a coin toss.
        val below = !frame.isMeasured || roomBelow >= px(PANEL_MIN_ROOM_DP) || roomBelow >= roomAbove
        val maxHeightDp = dp(if (below) roomBelow else roomAbove)
        val shown = manager.show(
            slot = OverlaySlot.PANEL,
            spec = OverlayWindowSpec(
                // `maxOf` because `maxX` is floored at zero: on a screen narrower than the panel the
                // margin is above it, and `coerceIn` with a bound below its floor throws.
                x = (buttonCentreX - width / 2).coerceIn(margin, maxOf(margin, frame.maxX)),
                y = if (below) {
                    buttonTop + buttonHeight + gap
                } else {
                    (frame.screenHeight - (buttonTop - gap)).coerceAtLeast(0)
                },
                width = width,
                touchable = true,
                dismissOnOutsideTouch = true,
                anchorBottom = !below,
            ),
            host = host,
        ) {
            val state by panel.collectAsState()
            val readings by pillReadings.collectAsState()
            val tint by accent.collectAsState()
            OverlayControlPanel(
                state = state,
                readings = readings,
                accent = tint,
                maxHeightDp = maxHeightDp,
                onAction = ::onAction,
                onDragLevel = ::onDragLevel,
                onCommitLevel = ::onCommitLevel,
                onDismiss = ::closePanel,
            )
        }
        if (!shown) return
        panelOpen.value = true
        denied.clear()
        deniedLevels.clear()
        panelJob?.cancel()
        panelJob = lifecycleScope.launch { runPanel() }
    }

    private fun closePanel() {
        panelJob?.cancel()
        panelJob = null
        if (panelOpen.value) panelOpen.value = false
        windows?.hide(OverlaySlot.PANEL)
    }

    /**
     * Keeps the open panel current.
     *
     * Capabilities are probed once here and again after every action, never on the tick:
     * [DisplayControls.isRotationLocked] and [AudioControls.doNotDisturbState] can go through the
     * elevated shell, and a shell round trip every second behind a panel that is sitting over a game is
     * exactly the background cost §26 rules out. The tick recomputes the session timer, which is
     * arithmetic on a start time and costs nothing.
     */
    private suspend fun runPanel() {
        probePanel()
        while (true) {
            delay(PANEL_TICK_MILLIS)
            panel.value = panel.value.copy(sessionElapsed = sessionElapsedLabel())
        }
    }

    /**
     * Gathers what the panel draws: which toggles are on, and for the rest, why not.
     *
     * Every unavailable entry carries a sentence, because a greyed button with no explanation is the
     * thing §24 objects to most — the user cannot tell a missing permission from a device that simply
     * does not have the hardware.
     */
    private suspend fun probePanel() {
        val request = overlays.desired.value
        val gaming = coordinator.gaming.value
        val active = mutableSetOf<OverlayAction>()
        val unavailable = mutableMapOf<OverlayAction, String>()

        if (request.pill) active += OverlayAction.PILL
        if (request.crosshair) active += OverlayAction.CROSSHAIR
        if (request.hud) active += OverlayAction.HUD
        // The HUD is the user's own arrangement; with none saved there is nothing to put on screen, and
        // that is a different sentence from "it is switched off".
        if (hudLayouts.layouts.first().isEmpty()) {
            unavailable[OverlayAction.HUD] = getString(R.string.overlay_no_hud_layout)
        }

        val recording = capture.recording.value
        if (recording.isRecording) active += OverlayAction.RECORD
        if (!capture.isSupported()) {
            val reason = getString(R.string.overlay_capture_unsupported)
            unavailable[OverlayAction.SCREENSHOT] = reason
            unavailable[OverlayAction.RECORD] = reason
        } else if (recording.isRecording) {
            // One MediaProjection at a time is all the platform gives, and the recording owns it.
            unavailable[OverlayAction.SCREENSHOT] = getString(R.string.overlay_recording_in_progress)
        }

        if (torch.isAvailable()) {
            if (torch.isOn()) active += OverlayAction.FLASHLIGHT
        } else {
            unavailable[OverlayAction.FLASHLIGHT] = getString(R.string.overlay_no_flash)
        }

        val dnd = audio.doNotDisturbState()
        if (dnd.valueOrNull?.isSilencing == true) active += OverlayAction.DO_NOT_DISTURB
        if (!permissions.hasNotificationPolicyAccess()) {
            unavailable[OverlayAction.DO_NOT_DISTURB] = getString(R.string.overlay_dnd_needs_access)
        } else {
            dnd.unavailabilityText()?.let { unavailable[OverlayAction.DO_NOT_DISTURB] = it }
        }

        val rotation = displayControls.isRotationLocked()
        if (rotation.valueOrNull == true) active += OverlayAction.ROTATION_LOCK
        rotation.unavailabilityText()?.let { unavailable[OverlayAction.ROTATION_LOCK] = it }

        if (!gaming.isTracking) {
            unavailable[OverlayAction.STOP_SESSION] = getString(R.string.overlay_no_session)
        }

        panel.value = OverlayPanelState(
            gameLabel = gaming.gameLabel.ifEmpty { request.gameLabel },
            sessionElapsed = sessionElapsedLabel(),
            active = active,
            // What was tried and refused wins over what could be worked out in advance.
            unavailable = unavailable + denied,
            levels = levelStates(),
        )
    }

    /**
     * Where volume and brightness stand.
     *
     * The two questions are kept apart because they have different answers. A level that could not be
     * *read* has no percentage to draw; a level that reads but cannot be *written* has a number and a
     * reason. Brightness is the case that needs both: `screen_brightness` is readable by any app and
     * writable only with WRITE_SETTINGS or a live elevated shell.
     *
     * Volume is deliberately given no pre-check. There is no way to ask whether `setStreamVolume` will be
     * refused without calling it — see [deniedLevels] — and greying the slider on a guess would be the
     * opposite failure to the one §32 names: a control that works, switched off in advance.
     */
    private suspend fun levelStates(): Map<OverlayLevel, OverlayLevelState> {
        val volume = audio.mediaVolumePercent()
        val brightness = displayControls.brightnessPercent()
        val brightnessReason = deniedLevels[OverlayLevel.BRIGHTNESS]
            ?: brightness.unavailabilityText()
            ?: getString(R.string.overlay_brightness_needs_access)
                .takeIf { !displayControls.canSetBrightness() }
        return mapOf(
            OverlayLevel.VOLUME to OverlayLevelState(
                percent = volume.valueOrNull,
                reason = deniedLevels[OverlayLevel.VOLUME] ?: volume.unavailabilityText(),
            ),
            OverlayLevel.BRIGHTNESS to OverlayLevelState(
                percent = brightness.valueOrNull,
                reason = brightnessReason,
            ),
        )
    }

    /**
     * Re-reads the two levels without re-probing the buttons.
     *
     * Run after a slider is committed. The buttons' capabilities can go through the elevated shell and
     * have not changed because the volume moved, so paying for that round trip on every drag release is
     * the background cost §26 rules out.
     */
    private suspend fun refreshLevels() {
        panel.value = panel.value.copy(levels = levelStates())
    }

    // ---------------------------------------------------------------------------------- actions

    private fun onAction(action: OverlayAction) {
        lifecycleScope.launch {
            execute(action)
            if (panelOpen.value) probePanel()
        }
    }

    /**
     * The finger moving on a slider. Displayed, not written.
     *
     * The requested level goes into [panel] so the thumb has somewhere to be — a Compose slider draws
     * where its value says — and so that the write, which arrives later with no value of its own, has
     * something to read. Nothing touches the device here: a write per drag frame would be sixty settings
     * writes a second, each one possibly a shell command.
     */
    private fun onDragLevel(level: OverlayLevel, percent: Int) {
        val current = panel.value
        val state = current.levelFor(level)
        if (!state.isUsable || state.percent == percent) return
        panel.value = current.copy(levels = current.levels + (level to state.copy(percent = percent)))
    }

    /**
     * The finger lifting: one write, then a read-back.
     *
     * The read-back is what makes the thumb honest. A media stream has around fifteen steps, so most
     * percentages a finger can land on are not reachable, and the level the device settled on is the one
     * the slider should show — [refreshLevels] is what moves it there.
     */
    private fun onCommitLevel(level: OverlayLevel) {
        val requested = panel.value.levelFor(level).percent ?: return
        lifecycleScope.launch {
            val outcome = when (level) {
                OverlayLevel.VOLUME -> audio.setMediaVolumePercent(requested)
                OverlayLevel.BRIGHTNESS -> displayControls.setBrightnessPercent(requested)
            }
            reportLevel(level, outcome)
            if (panelOpen.value) refreshLevels()
        }
    }

    /**
     * Runs one panel action.
     *
     * A `when` with no `else`, so an action added to [OverlayAction] and not handled here fails to
     * compile — §32's "no button that does nothing" made structural rather than remembered. The overlay
     * toggles go back through [OverlayController] instead of touching a window directly, so a toggle from
     * the panel and the same toggle from the settings screen end in the same state.
     */
    private suspend fun execute(action: OverlayAction) {
        when (action) {
            OverlayAction.PILL -> {
                val next = !overlays.desired.value.pill
                overlays.setPill(next)
                // Persisted, because a toggle here is the user changing their mind about the pill rather
                // than a temporary override: the settings screen shows the same switch.
                preferences.updateOverlay { it.copy(showPill = next) }
            }
            OverlayAction.CROSSHAIR -> overlays.setCrosshair(!overlays.desired.value.crosshair)
            OverlayAction.HUD -> overlays.setHud(!overlays.desired.value.hud)
            OverlayAction.SCREENSHOT -> takeScreenshot()
            OverlayAction.RECORD -> toggleRecording()
            OverlayAction.FLASHLIGHT -> report(action, torch.toggle())
            OverlayAction.DO_NOT_DISTURB -> {
                val silencing = audio.doNotDisturbState().valueOrNull?.isSilencing == true
                report(action, audio.setDoNotDisturb(!silencing))
            }
            OverlayAction.ROTATION_LOCK -> report(action, toggleRotationLock())
            OverlayAction.STOP_SESSION -> {
                closePanel()
                // Through the coordinator, which routes it through the detector: that is what suppresses
                // the game until the user actually leaves it, instead of it being announced again on the
                // next poll a second later.
                coordinator.stopCurrent()
            }
            OverlayAction.OPEN_APP -> {
                closePanel()
                openApp()
            }
        }
    }

    /**
     * Pins the screen to the orientation it is in, or releases the pin.
     *
     * The current rotation has to be read first. A lock writes `user_rotation`, and writing anything
     * other than what the user is holding rotates the screen under them — locking a phone in landscape
     * would flip it to portrait and then hold it there.
     */
    private suspend fun toggleRotationLock(): ControlOutcome {
        val locked = displayControls.isRotationLocked().valueOrNull ?: false
        if (locked) return displayControls.unlockRotation()
        val degrees = displayReader.read().rotationDegrees
        return displayControls.lockRotation(ScreenRotation.fromSurface(degrees / DEGREES_PER_STEP))
    }

    /**
     * Hands the screenshot to [ScreenRecordingService].
     *
     * Not taken here, and the reason is Android 14: `createVirtualDisplay` is only allowed while a
     * foreground service of type `mediaProjection` is running, so the only place a screenshot can legally
     * be taken is the service that declares that type — even though a screenshot is not a recording.
     *
     * The panel closes first and the capture waits for the window to actually leave the screen. A
     * screenshot with GameCore's own control panel in the middle of it is not the screenshot the user
     * asked for.
     */
    private suspend fun takeScreenshot() {
        closePanel()
        delay(CAPTURE_SETTLE_MILLIS)
        startCapture(CapturePurpose.SCREENSHOT)
    }

    private suspend fun toggleRecording() {
        if (capture.recording.value.isRecording) {
            startCapture(CapturePurpose.STOP_RECORDING)
            return
        }
        closePanel()
        delay(CAPTURE_SETTLE_MILLIS)
        startCapture(CapturePurpose.START_RECORDING)
    }

    /**
     * Starts a capture, through the consent activity when there is no projection to reuse.
     *
     * A service cannot ask for `MediaProjection` consent — it arrives as an activity result — which is
     * why §24B's "recording gets its own permission flow" is not a matter of style. An existing consent
     * is reused, so a second screenshot in the same session does not re-prompt.
     */
    private fun startCapture(purpose: CapturePurpose) {
        try {
            if (capture.hasProjection()) {
                ContextCompat.startForegroundService(this, ScreenRecordingService.intentFor(this, purpose))
            } else {
                startActivity(CaptureConsentActivity.intentFor(this, purpose))
            }
        } catch (refused: IllegalStateException) {
            // Foreground-service or background-activity start refused by the platform.
            toast(getString(R.string.overlay_capture_failed))
        } catch (denied: SecurityException) {
            toast(getString(R.string.overlay_capture_failed))
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
        } catch (denied: SecurityException) {
            // Background activity starts are restricted from Android 10, and the exemption that applies
            // here is the overlay permission itself. If it has just been revoked, this is how we find out.
            toast(getString(R.string.overlay_open_failed))
        }
    }

    /**
     * Says what an action did, and remembers a refusal that is not going to change by itself.
     *
     * An applied outcome is silent: the user watched the torch come on. Anything else is a toast, because
     * the alternative — a button that was tapped and did nothing visible — is the failure mode §32 names.
     * A structural refusal is also recorded in [denied] so the second tap is prevented rather than
     * repeated, while a one-off [ControlOutcome.Failed] leaves the button alone; a write the platform
     * refused once may well work on the next attempt.
     */
    private fun report(action: OverlayAction, outcome: ControlOutcome) {
        if (outcome.isApplied) {
            denied.remove(action)
            return
        }
        when (outcome) {
            is ControlOutcome.RequiresAccess, is ControlOutcome.Unsupported ->
                denied[action] = outcome.message
            else -> Unit
        }
        toast(outcome.message)
    }

    private fun toast(message: String) {
        if (message.isBlank()) return
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * [report] for a level, with one deliberate difference: an applied outcome is silent even when it
     * carries a detail.
     *
     * [AudioControls.setMediaVolumePercent] says "set to 40%, the closest level this device allows" when
     * it rounds a request to a stream step, and that is true of nearly every drag — a toast each time
     * would be noise over a game. The slider says the same thing better by moving to where the device
     * actually went, which [refreshLevels] makes it do a moment later.
     */
    private fun reportLevel(level: OverlayLevel, outcome: ControlOutcome) {
        if (outcome.isApplied) {
            deniedLevels.remove(level)
            return
        }
        when (outcome) {
            is ControlOutcome.RequiresAccess, is ControlOutcome.Unsupported ->
                deniedLevels[level] = outcome.message
            else -> Unit
        }
        toast(outcome.message)
    }

    private fun px(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /** The inverse of [px], for handing a measured amount of room to a composable. */
    private fun dp(px: Int): Int = (px / resources.displayMetrics.density).toInt()

    private companion object {
        /**
         * How far the button and the panel stop short of the physical edge.
         *
         * A few pixels, for curved glass: a control flush against the edge of a phone with a waterfall
         * display is partly under the curve, where it both looks wrong and competes with the back gesture.
         */
        const val EDGE_MARGIN_DP = 6

        /** The gap between the floating button and the panel it opens. */
        const val PANEL_GAP_DP = 8

        /**
         * The room below the button that is worth opening into.
         *
         * Above the button is only preferred when below is thinner than this *and* thinner than above:
         * the panel scrolls, so a slightly short one is better than one that jumps to the other side of
         * an icon the user just tapped.
         */
        const val PANEL_MIN_ROOM_DP = 260

        /** The panel's session timer is the only thing in it that changes on a clock. */
        const val PANEL_TICK_MILLIS = 1_000L

        /**
         * How long to wait after closing the panel before capturing.
         *
         * `removeView` is asynchronous — the window is gone once the system has composited a frame
         * without it, not when the call returns. A handful of frames is enough to be sure the screenshot
         * does not contain the panel the user just tapped, and short enough that it does not feel like a
         * delay.
         */
        const val CAPTURE_SETTLE_MILLIS = 120L

        /** `Surface.ROTATION_*` counts quarter turns; [DisplayReader] reports degrees. */
        const val DEGREES_PER_STEP = 90
    }
}
