package com.gamecore.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gamecore.MainActivity
import com.gamecore.R
import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.NotificationChannels
import com.gamecore.core.common.Observed
import com.gamecore.core.common.shortUnavailabilityText
import com.gamecore.core.common.unavailabilityText
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.AccentChoice
import com.gamecore.core.model.AspectChoice
import com.gamecore.core.model.CROSSHAIR_COLOURS
import com.gamecore.core.model.CrosshairDesign
import com.gamecore.core.model.CrosshairPreset
import com.gamecore.core.model.DisplaySizeState
import com.gamecore.core.model.FloatingButtonConfig
import com.gamecore.core.model.HudLayout
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.OverlayConfig
import com.gamecore.core.model.OverlayStatus
import com.gamecore.core.model.PanelLayoutStyle
import com.gamecore.core.model.ThermalClassifier
import com.gamecore.core.overlay.CrosshairOverlay
import com.gamecore.core.overlay.FloatingGameButton
import com.gamecore.core.overlay.HeldRow
import com.gamecore.core.overlay.HudOverlay
import com.gamecore.core.overlay.OverlayAction
import com.gamecore.core.overlay.OverlayControlPanel
import com.gamecore.core.overlay.OverlayCrosshair
import com.gamecore.core.overlay.OverlayFrame
import com.gamecore.core.overlay.OverlayLevel
import com.gamecore.core.overlay.OverlayLevelState
import com.gamecore.core.overlay.OverlayPanelState
import com.gamecore.core.overlay.OverlayPlacement
import com.gamecore.core.overlay.AspectChips
import com.gamecore.core.overlay.CrosshairChips
import com.gamecore.core.overlay.FullPanel
import com.gamecore.core.overlay.HoldToConfirm
import com.gamecore.core.overlay.OverlayPreset
import com.gamecore.core.overlay.OverlaySamplingGate
import com.gamecore.core.overlay.OverlaySlider
import com.gamecore.core.overlay.OverlaySlot
import com.gamecore.core.overlay.OverlayToggle
import com.gamecore.core.overlay.PanelControl
import com.gamecore.core.overlay.PanelTab
import com.gamecore.core.overlay.PresetChips
import com.gamecore.core.overlay.RateChips
import com.gamecore.core.overlay.OverlaySplitPanel
import com.gamecore.core.overlay.OverlayViewHost
import com.gamecore.core.overlay.OverlayWindowSpec
import com.gamecore.core.overlay.OverlayWindows
import com.gamecore.core.overlay.PerformancePill
import com.gamecore.core.overlay.PositionFraction
import com.gamecore.core.overlay.QuickSheet
import com.gamecore.core.overlay.QuickSheetAutoClose
import com.gamecore.core.overlay.QuickSheetPins
import com.gamecore.core.overlay.QuickSheetSide
import com.gamecore.core.overlay.QuickToggle
import com.gamecore.core.overlay.QuickToggleState
import com.gamecore.core.overlay.ThermalDot
import com.gamecore.core.overlay.toOverlayAction
import com.gamecore.core.overlay.refreshRateFeedback
import com.gamecore.core.overlay.thermalDot
import com.gamecore.core.permissions.PermissionChecker
import com.gamecore.core.system.AudioControls
import com.gamecore.core.system.ControlOutcome
import com.gamecore.core.system.DisplayControls
import com.gamecore.core.system.DisplayReader
import com.gamecore.core.system.RefreshRateController
import com.gamecore.core.system.ScreenCaptureController
import com.gamecore.core.system.ScreenRotation
import com.gamecore.core.system.TorchControls
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.ColorPresetRepository
import com.gamecore.data.repository.CrosshairRepository
import com.gamecore.data.repository.HudLayoutRepository
import com.gamecore.domain.color.ColorApplyResult
import com.gamecore.domain.color.ColorCorrectionController
import com.gamecore.domain.display.DisplaySizeController
import com.gamecore.domain.gaming.GamingCoordinator
import com.gamecore.domain.media.MediaCommand
import com.gamecore.domain.media.MediaSessionReader
import com.gamecore.domain.media.NowPlaying
import com.gamecore.domain.monitoring.HudStatReader
import com.gamecore.domain.monitoring.PerformanceMonitor
import com.gamecore.domain.monitoring.StatReading
import com.gamecore.domain.overlay.OverlayController
import com.gamecore.domain.overlay.QuickApp
import com.gamecore.domain.overlay.QuickAppLauncher
import com.gamecore.domain.overlay.QuickLaunchOutcome
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
import kotlin.math.roundToInt
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

    @Inject lateinit var displaySize: DisplaySizeController

    /**
     * The same controller the profile applier and the performance screen use, injected rather than
     * reimplemented: writing `peak_refresh_rate` from here would be a second path to the same two keys, with
     * its own idea of whether the write took. The verification in [RefreshRateController] is most of what
     * that class is, and it is exactly the part a panel over a running game must not skip.
     */
    @Inject lateinit var refreshRate: RefreshRateController

    @Inject lateinit var torch: TorchControls

    @Inject lateinit var capture: ScreenCaptureController

    @Inject lateinit var colour: ColorCorrectionController

    @Inject lateinit var colorPresets: ColorPresetRepository

    /**
     * The media strip's only dependency, and the only class in the app that touches a media session.
     *
     * Injected here rather than reached through a controller in the domain layer, because unlike every
     * other control on this panel there is nothing to coordinate: no setting is written, no state is
     * restored at the end of a session, and no profile can configure it. The panel reads a session and
     * forwards three commands to whichever app owns it.
     */
    @Inject lateinit var media: MediaSessionReader

    /**
     * The quick-launch row's only dependency: package names in, icons out, and one launch intent.
     *
     * Injected for the same reason [media] is — there is nothing to coordinate. No setting is written by
     * a tap, no state is restored at the end of a session, and no profile configures it. It resolves
     * what the user chose in settings and starts whatever they tapped, with a plain launch intent and
     * no elevated shell of any kind.
     */
    @Inject lateinit var quickAppLauncher: QuickAppLauncher

    override val notificationId: Int = NotificationChannels.ID_OVERLAY

    override val serviceType: Int = TYPE_SPECIAL_USE

    /** One host for all five windows; see [OverlayViewHost] for why it is not one per window. */
    private val host = OverlayViewHost()

    private var windows: OverlayWindows? = null

    private val pillReadings = MutableStateFlow<List<StatReading>>(emptyList())

    private val hudReadings = MutableStateFlow<Map<HudStat, StatReading>>(emptyMap())

    /**
     * The thermal dot the floating button shows (spec §2), classified through the shared [ThermalClassifier]
     * so the button, the pill's thermal stat and the dashboard can never disagree about how hot is hot.
     *
     * It reflects heat only while the sampler is running — the same subscription that feeds the pill and HUD
     * ([streamReadings]) — and returns to [ThermalDot.NONE] the moment sampling stops. A button on screen by
     * itself does not spin the sampler up, so a bare button stays exactly as quiet as it is today; whether it
     * *should* keep the sampler alive is a performance-profile call left to a later step.
     */
    private val buttonThermalDot = MutableStateFlow(ThermalDot.NONE)

    private val crosshairPreset = MutableStateFlow<CrosshairPreset?>(null)

    private val hudLayout = MutableStateFlow<HudLayout?>(null)

    private val panel = MutableStateFlow(OverlayPanelState.EMPTY)

    /**
     * What is playing, for the panel's media strip.
     *
     * Its own flow rather than a field of [OverlayPanelState], for two reasons that both matter. It
     * carries a [android.graphics.Bitmap], and that class has no business inside a state object the
     * rest of the panel is rebuilt from on every tick of [runPanel] — copying a state holding a bitmap
     * five times a second is a copy nobody asked for. And it updates on the session's schedule, not on
     * the panel's: a track change arrives when the app changes the track, and folding it into the tick
     * would mean the title lagged the music by up to a full interval for no gain.
     *
     * Reset to [NowPlaying.Silent] on close rather than left holding the last track, so the strip cannot
     * flash stale metadata in the moment between a panel opening and its first read landing.
     */
    private val nowPlaying = MutableStateFlow<NowPlaying>(NowPlaying.Silent)

    /**
     * The quick-launch row, for the same two reasons [nowPlaying] is its own flow.
     *
     * It carries bitmaps, which have no business in a state object [runPanel] rebuilds five times a
     * second; and it changes on the settings' schedule rather than the panel's, so folding it into the
     * tick would resolve six icons from `PackageManager` every interval to answer a question whose
     * answer almost never changes.
     *
     * Empty when the feature is off, which is the single place that decision is made — see
     * [startPanelWatch]. The row composable draws nothing for an empty list, so "off", "on with nothing
     * chosen" and "the packages were all uninstalled" converge on the same honest outcome: no row.
     */
    private val quickApps = MutableStateFlow<List<QuickApp>>(emptyList())

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

    /**
     * Whether the display is on — the third term of the sampling gate in [streamReadings].
     *
     * Seeded optimistically to `true` and corrected from [PowerManager] the moment there is a context to
     * ask (see [watchScreenState]); a field initialiser runs before the service is attached, so the real
     * answer cannot be read here. Optimistic is right for the frame it lasts, because the overlay is put
     * up by a user action and the screen is therefore on — a `false` seed would blank the pill for one
     * tick on every single start.
     */
    private val screenInteractive = MutableStateFlow(true)

    /**
     * The listener that keeps [screenInteractive] honest, held so it can be released in [onDestroy].
     *
     * Null when registration was refused, which is why every use is null-checked rather than asserted.
     */
    private var screenReceiver: BroadcastReceiver? = null

    /**
     * Whether the quick sheet (§4) is on screen. Its own flag, not a mode of [panelOpen], because the two
     * are separate windows in separate slots: a tap opens the sheet, a double tap (or the sheet's "More")
     * opens the full panel, and the probe that fills [panel] has to run whenever *either* is up.
     */
    private val quickSheetOpen = MutableStateFlow(false)

    /**
     * The sheet's watch, the sibling of [panelJob].
     *
     * The sheet draws the same [panel] state the full panel does — its toggles, its two sliders and its
     * clock all read from it — so it needs its own probe-and-tick running while it is open, for the reason
     * [openQuickSheet] gives: [panel] is empty unless something is filling it, and a sheet opened on its
     * own (the common single-tap path, with the full panel never touched) would otherwise show every
     * toggle off and both sliders dead. Cancelled in [closeQuickSheet], the one place the sheet comes down.
     */
    private var quickSheetJob: Job? = null

    /**
     * When the sheet was last touched, on [android.os.SystemClock.elapsedRealtime]'s monotonic clock, for
     * the auto-close of §4. Fed to [QuickSheetAutoClose]; every toggle tap and slider move pushes it
     * forward, and the watch loop closes the sheet once it is [QuickSheetAutoClose.idleAfterMillis] stale.
     *
     * `elapsedRealtime` and not `currentTimeMillis` for the reason the whole idle machine is arithmetic
     * rather than a `postDelayed`: it cannot run backwards when the wall clock is corrected mid-session,
     * so a clock change can never leave the sheet stuck open or snap it shut a beat early.
     */
    private var lastQuickInteraction: Long = 0L

    /**
     * The pending "commit this slider" per level, so a drag on the sheet writes the device once it settles
     * rather than every frame. Keyed by level because brightness and volume can be dragged in one sitting;
     * [onQuickLevel] cancels the level's own pending commit before posting a fresh one, and
     * [closeQuickSheet] cancels them all. See [onQuickLevel] for why the sheet needs this and the panel
     * does not.
     */
    private val quickLevelCommits = mutableMapOf<OverlayLevel, Job>()

    /**
     * The width the open panel is drawn at, in dp, while it is open.
     *
     * Held here rather than remembered inside the composable because a resize has to change two things at
     * once: the plate the panel draws and the window it is drawn in. The grip reports a new width, this
     * flow moves the plate, and [OverlayWindows.resize] moves the window in the same frame — a plate that
     * grew while its window did not would be a panel clipped by its own edge for as long as the drag
     * lasted, and a window that grew while its plate did not would be a band of empty screen beside it.
     *
     * Seeded from the stored preference at every open and written back once on release, so this is the
     * in-flight value and [FloatingButtonConfig.panelWidthDp] is the remembered one.
     */
    private val panelWidth = MutableStateFlow(FloatingButtonConfig.DEFAULT_PANEL_WIDTH_DP)

    private val accent = MutableStateFlow(Color(AccentChoice.CYAN.argb))

    /** The full panel's selected tab (§5). Reset to [PanelTab.DEFAULT] on close so a reopen starts on Display. */
    private val panelTab = MutableStateFlow(PanelTab.DEFAULT)

    /**
     * The end-session hold (§5), as [HoldToConfirm] arithmetic against a monotonic clock — never a timer.
     *
     * [endPressStart] is the finger-down timestamp on [android.os.SystemClock.elapsedRealtime] (monotonic,
     * for the same reason [lastQuickInteraction] is: a hold *duration* must not jump if the wall clock is
     * corrected mid-press, unlike the session *clock* which is deliberately wall-time). Null when the finger
     * is up, which is the whole of the cancel — every [HoldToConfirm] query reads null as "not pressing".
     * [endWasConfirmed] is the once-only latch, and [endProgress] is what the ring paints.
     */
    private val endHold = HoldToConfirm()
    private var endPressStart: Long? = null
    private var endWasConfirmed = false
    private val endProgress = MutableStateFlow(0f)

    /** The end-session ring's own fast loop, a child of [panelJob], live only while the finger is down. */
    private var endHoldJob: Job? = null

    private var panelJob: Job? = null

    /** The pending "open the panel" from a Quick Trigger, waiting for the button window to exist. */
    private var panelRequestJob: Job? = null

    /**
     * True between the first drag frame and the finger lifting.
     *
     * Without it, a preference write that lands mid-drag — the pill's position, an accent change — would
     * reconcile the button back to its stored coordinate and the user would watch it snap out from under
     * their finger.
     */
    private var draggingButton = false

    /**
     * The same guard for the panel's resize grip.
     *
     * The settings screen writes the panel's width to preferences, [reconcile] re-applies it to an open
     * panel, and both are wanted — that is what makes the slider a live preview over a game. But the grip
     * writes to the same preference on release, and a reconcile that landed between two drag frames would
     * re-apply the last *stored* width in the middle of the gesture and drag the edge back out from under
     * the finger.
     */
    private var resizingPanel = false

    /**
     * Which layout the open panel was built with, or null while it is closed.
     *
     * Kept because the layout is a preference and preferences change while the panel is up. It is not
     * something a running window can be edited into either: the two layouts differ in the window's flags,
     * its size and how it is positioned, so switching means taking one down and putting the other up —
     * see [syncPanelLayout]. Reading [FloatingButtonConfig.panelLayout] instead would answer "what should
     * be on screen", and the question here is "what *is*".
     */
    private var panelLayoutShown: PanelLayoutStyle? = null

    /**
     * The rate GameCore last wrote *and confirmed the display adopted*, or null in every other case.
     *
     * The one piece of panel state held here rather than read back on each probe, and the reason is worth
     * being explicit about because the alternative looks better and is a lie. There are two things that
     * could be read instead, and both fail:
     *
     * `Display.getRefreshRate()` reports what is being composited now. The platform is entitled to drop a
     * panel pinned at 120 to 60 while the screen is static, so a low reading is not evidence the pin came
     * off — and unlighting the chip on it would mean the row went dark every time the player stopped moving.
     *
     * The stored `peak_refresh_rate` bound reports what was *asked for*. On the chipsets
     * [RefreshRateController.isKnownUnreliableChipset] names, that value is written, read back intact, and
     * the panel stays at 60. Filling a chip from it is precisely the claim
     * [com.gamecore.core.model.RefreshRateOutcome.NotHonoured] exists to refuse.
     *
     * So this holds the one thing that is evidence: a change GameCore made and verified this session. It
     * costs something and the cost is disclosed in [OverlayPanelState.pinnedRefreshRate] — a rate pinned by
     * a game profile rather than by this panel shows no chip filled. An unfilled row claims nothing, which
     * is the failure worth having.
     */
    private var pinnedRate: Float? = null

    /**
     * What the last rate change reported, when it was not a confirmed success. Null once one is.
     *
     * Kept beside the note the chips already show rather than only toasted, because the toast is gone in
     * three seconds and the row it explains is still on screen. Cleared by a confirmed change so the note
     * under the chips is never a stale reason for a rate that has since taken.
     */
    private var rateNote: String? = null

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
     * The Quick Trigger asking for the panel.
     *
     * The wait is the load-bearing part. A trigger fired while this service was not running starts it,
     * and the start intent arrives on the same looper as the flow collection that puts the floating
     * button up — so at this instant the button window very often does not exist yet, and a panel opened
     * against a button that is not there is anchored to the top-left corner of the screen. Waiting for
     * the anchor, briefly and with a ceiling, is the difference between the panel appearing where the
     * user expects it and appearing in the corner.
     */
    override fun onStartAction(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != OverlayController.ACTION_OPEN_PANEL &&
            action != OverlayController.ACTION_TOGGLE_PANEL
        ) {
            return
        }
        panelRequestJob?.cancel()
        panelRequestJob = lifecycleScope.launch {
            var waited = 0L
            while (windows?.frameFor(OverlaySlot.BUTTON) == null && waited < PANEL_ANCHOR_TIMEOUT_MS) {
                delay(PANEL_ANCHOR_POLL_MS)
                waited += PANEL_ANCHOR_POLL_MS
            }
            if (action == OverlayController.ACTION_TOGGLE_PANEL) {
                togglePanel()
            } else if (!panelOpen.value) {
                openPanel()
            }
        }
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
        // Anchored to the button, which is about to be rescaled to a new position — the same reason the
        // panel closes on a rotation rather than being carried across.
        closeQuickSheet()
        rescaleWindows()
        reconcile()
    }

    override fun onDestroy() {
        panelJob?.cancel()
        panelRequestJob?.cancel()
        quickSheetJob?.cancel()
        quickLevelCommits.values.forEach { it.cancel() }
        // Released before the windows go, and guarded: unregistering a receiver that never registered
        // throws, and a receiver left behind by a dying service is a logged leak.
        screenReceiver?.let { receiver -> runCatching { unregisterReceiver(receiver) } }
        screenReceiver = null
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
        watchScreenState()
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
     * Starts watching the display, which is what lets [streamReadings] stop sampling in a pocket.
     *
     * A broadcast is the only way to learn this: there is no flow for screen state and polling
     * [PowerManager] would be the very wake-up this exists to avoid, so this is the app's one registered
     * receiver. Both actions are protected system broadcasts — no other app can send them — so this needs
     * no permission, and the filter is declared not-exported because nothing outside the system should be
     * able to reach it.
     *
     * The seed carries as much weight as the broadcasts. A receiver only ever reports the *next* change,
     * so the current state is read from [PowerManager] once, here. Without that read, an overlay put up
     * while the screen was already off — a profile applied by a trigger, say — would sample continuously
     * until the user next happened to wake the phone, which is precisely the leak being closed.
     *
     * Failure is survivable and deliberately not fatal: if registration is refused, [screenInteractive]
     * stays at its seeded value and the overlay behaves exactly as it did before §9, sampling whenever a
     * surface is up. Worse battery, never a blank pill.
     */
    private fun watchScreenState() {
        screenInteractive.value = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> screenInteractive.value = true
                    Intent.ACTION_SCREEN_OFF -> screenInteractive.value = false
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onSuccess { screenReceiver = receiver }
    }

    /**
     * Loads the crosshair the request names.
     *
     * The request names one whenever there is one to name: [OverlayController] fills the id in from the
     * preset the user last picked when the caller does not supply it, so the panel's own toggle and a
     * profile that switches the crosshair on without choosing a design both arrive here with an id. That
     * matters because the fallback below cannot tell "this preset was deleted" from "nobody said which",
     * and answering the second with the lowest-numbered saved preset is every design in the list looking
     * like the first one.
     *
     * The fallback is kept for what is left: an id that no longer exists, and a user who has never picked
     * a crosshair at all. A crosshair that was asked for and silently does not appear is worse than one
     * that is not the exact design intended, and the repository seeds defaults, so "first saved preset" is
     * never empty in practice.
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
                // Re-probed only while the panel is open, and probed *here* rather than in the chip handler
                // that caused the change. The quick-pick row's filled chip is meant to describe the crosshair
                // on the screen, and this is the line where that crosshair changes — a probe in the handler
                // would run before Room had republished the table and would read the design the user just
                // replaced. It costs one extra probe per crosshair edit with the panel open, which is the same
                // cost every other chip in that panel already pays.
                if (panelOpen.value) probePanel()
            }
    }

    /**
     * Loads the HUD layout a request names.
     *
     * A named layout that is not there is no HUD at all, unlike the crosshair: a HUD is the user's own
     * arrangement of widgets, and showing a different layout than the one a profile asked for would put
     * stats they did not choose over their game. That means no HUD window and a greyed HUD button in the
     * panel. An *unnamed* one takes the first saved layout, which after [OverlayController] has filled in
     * the last one the user picked can only be a user who has never picked one.
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
     * Keeps the pill, HUD and panel readings current while any of them is on screen *and the user can see
     * it* — the rule itself lives in [OverlaySamplingGate], which is where the reasoning is written down.
     *
     * The empty `collect` is deliberate and load-bearing. [PerformanceMonitor.snapshots] is a
     * `WhileSubscribed` stream, so holding a subscription is what keeps the one shared sampling loop
     * alive — and the loop underneath reads its latest value at the pill's own configured interval
     * instead of on every sample, which is what §8's update-interval setting is for. Sampling cadence
     * and display cadence are separate knobs, and this is the seam between them.
     *
     * That same seam is why the screen term costs nothing to add: dropping the subscription is all it
     * takes to stop the sampler, and `collectLatest` already tears the whole block down on every change,
     * so a screen going off cancels the `delay` mid-wait rather than finishing one more round of work.
     * The readings are cleared on the way out so the pill has nothing stale to draw if the display comes
     * back before the first new sample lands.
     */
    private suspend fun streamReadings() {
        combine(overlays.desired, panelOpen, screenInteractive) { request, open, awake ->
            OverlaySamplingGate.shouldSample(
                pillVisible = request.pill,
                hudVisible = request.hud,
                panelOpen = open,
                screenInteractive = awake,
            )
        }
            .distinctUntilChanged()
            .collectLatest { needed ->
                if (!needed) {
                    pillReadings.value = emptyList()
                    hudReadings.value = emptyMap()
                    buttonThermalDot.value = ThermalDot.NONE
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
        // The button's dot rides the same sample. NONE until the first snapshot lands, and NONE for OK,
        // WARM and an unavailable temperature — only HOT and CRITICAL draw, so the button is silent unless
        // there is something worth glancing at.
        buttonThermalDot.value = snapshot?.let { thermalDot(ThermalClassifier.classify(it).level) } ?: ThermalDot.NONE
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
            closeQuickSheet()
            manager.hideAll()
            stopSelf()
            return
        }
        val button = preferences.floatingButton.value.normalised()
        val pill = preferences.overlay.value.normalised()
        if (request.button) {
            showButton(button)
            syncPanelLayout(button.panelLayout)
            applyPanelWidth(button.panelWidthDp)
        } else {
            closePanel()
            // The sheet hangs off the button; with the button gone there is nothing for it to anchor to.
            closeQuickSheet()
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
            // A fraction already remembered for the orientation we just turned into wins — that is the
            // whole point of storing per orientation. Only when this orientation has never been placed do
            // we carry the old screen's pixels across proportionally, then remember where that landed.
            val stored = config.positionFraction(portrait = currentButton.isPortrait)
            val placed = if (stored != null) {
                currentButton.fromFraction(PositionFraction(stored.first, stored.second), config.snapToEdge)
            } else {
                currentButton.rescaleFrom(previousButton, config.x, config.y, config.snapToEdge)
            }
            manager.move(OverlaySlot.BUTTON, placed.x, placed.y)
            lastButtonFrame = currentButton
            persistButtonPlacement(currentButton, placed)
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
        val placement = resolveButtonPlacement(frame, config)
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
            val panelUp by panelOpen.collectAsState()
            val sheetUp by quickSheetOpen.collectAsState()
            val dot by buttonThermalDot.collectAsState()
            val normalised = live.normalised()
            FloatingGameButton(
                config = normalised,
                accent = tint,
                positionProvider = ::buttonOffset,
                onDragTo = ::dragButtonTo,
                onDragFinished = ::finishButtonDrag,
                // §4: a tap opens the quick sheet, the tap-and-back surface. The full panel is one gesture
                // further in — the sheet's "More", or the double tap when the user has turned it on.
                onTap = ::toggleQuickSheet,
                // Null unless the setting is on, so a single tap has no double-tap latency (see
                // [FloatingGameButton]); wired live from the collected config, so turning the setting on or
                // off takes effect on the next recomposition without the window being re-added.
                onDoubleTap = if (normalised.doubleTapForPanel) ::openPanel else null,
                // Lit while either surface is up — both are "the button is open" as far as the idle fade
                // and the expanded look are concerned.
                isExpanded = panelUp || sheetUp,
                thermalDot = dot,
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

    /**
     * Where the button belongs on [frame]: the fraction remembered for this orientation if there is one,
     * otherwise the stored pixels (spec §2).
     *
     * The fraction is the position that survives a rotation, so it wins whenever the user has placed the
     * button in this orientation. A config that has never been placed here — an older saved file, or the
     * first time the phone is turned this way — has no fraction, and the legacy pixel pair is exactly the
     * behaviour that shipped before, re-clamped to today's safe area.
     */
    private fun resolveButtonPlacement(frame: OverlayFrame, config: FloatingButtonConfig): OverlayPlacement {
        val fraction = config.positionFraction(portrait = frame.isPortrait)
        return if (fraction != null) {
            frame.fromFraction(PositionFraction(fraction.first, fraction.second), config.snapToEdge)
        } else {
            frame.place(config.x, config.y, config.snapToEdge)
        }
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
        // The panel and the sheet are both anchored to where the button was. Closing beats dragging a
        // stale anchor around.
        closePanel()
        closeQuickSheet()
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
        persistButtonPlacement(frame, placed)
    }

    /**
     * Stores where the button ended up as both the pixels for this screen and the fraction for this
     * orientation, from the placement that was actually applied so the two never disagree (spec §2).
     *
     * The fraction is taken of the placed position — after any snap — so what re-resolves on the next
     * rotation is the corner the button is sitting in, not the raw spot the finger left.
     */
    private fun persistButtonPlacement(frame: OverlayFrame, placed: OverlayPlacement) {
        val fraction = frame.fractionOf(placed.x, placed.y)
        preferences.updateButtonPlacement(
            x = placed.x,
            y = placed.y,
            portrait = frame.isPortrait,
            xFraction = fraction.xFraction,
            yFraction = fraction.yFraction,
        )
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
            val dot by buttonThermalDot.collectAsState()
            PerformancePill(readings = readings, config = live.normalised(), thermalDot = dot)
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
     *
     * The width, by contrast, is the user's and is known before anything is drawn — which is what lets the
     * x above be exact. [panelWidthFor] is what keeps a figure chosen on a portrait settings screen from
     * opening a panel wider than the landscape game it opens over.
     *
     * None of the above applies to [PanelLayoutStyle.SPLIT_EDGES], which is why it leaves through
     * [openSplitPanel] before any of it runs: that layout is not positioned from the button, takes the
     * whole screen rather than the room beside it, and derives its own width. The branch is here rather
     * than inside the composable because it is a difference in the *window*, not in what is drawn in it.
     */
    private fun openPanel() {
        // The panel supersedes the sheet — a double tap, or the sheet's "More", brings the full surface up
        // and the narrow one has to go, or both would be touchable at once. Covers the split layout too,
        // which is only ever reached through here.
        if (quickSheetOpen.value) closeQuickSheet()
        val config = preferences.floatingButton.value.normalised()
        if (config.panelLayout == PanelLayoutStyle.SPLIT_EDGES) {
            openSplitPanel()
            return
        }
        val manager = windows ?: return
        val widthDp = panelWidthFor(config.panelWidthDp)
        panelWidth.value = widthDp
        val width = px(widthDp)
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
            PanelContent(maxHeightDp = maxHeightDp)
        }
        if (!shown) return
        startPanelWatch(PanelLayoutStyle.CENTERED)
    }

    /**
     * The full panel's UI, wired to the same handlers the split panel and the sheet use (§5).
     *
     * Split out of [openPanel] so the `@OptIn` for the chip composables sits on a composable, and so the
     * whole controls map — which reads both [panel] and [nowPlaying] — is built where those two are
     * collected. The map is a **projection**: every `enabled`/`reason`/on-state is a read of the
     * [OverlayPanelState] `probePanel` already filled, and every slot points at an existing handler
     * ([onAction], [onSliderLevel], [onPreset], …) unchanged, so nothing here decides what a control does.
     */
    @Composable
    private fun PanelContent(maxHeightDp: Int) {
        val state by panel.collectAsState()
        val readings by pillReadings.collectAsState()
        val playing by nowPlaying.collectAsState()
        val apps by quickApps.collectAsState()
        val tint by accent.collectAsState()
        val tab by panelTab.collectAsState()
        val progress by endProgress.collectAsState()
        val live by panelWidth.collectAsState()

        val track = playing as? NowPlaying.Track
        FullPanel(
            tab = tab,
            onTabSelected = { panelTab.value = it },
            gameLabel = state.gameLabel,
            clock = state.sessionElapsed.orEmpty(),
            controls = panelControls(state, tint, live),
            readings = readings,
            quickApps = apps,
            onLaunchApp = ::onLaunchApp,
            mediaPlaying = track != null,
            mediaPreviousEnabled = track?.canSkipPrevious == true,
            mediaPlayPauseEnabled = track?.canPlayPause == true,
            mediaNextEnabled = track?.canSkipNext == true,
            mediaIsPlaying = track?.isPlaying == true,
            onMediaPrevious = { onMedia(MediaCommand.PREVIOUS) },
            onMediaPlayPause = { onMedia(MediaCommand.PLAY_PAUSE) },
            onMediaNext = { onMedia(MediaCommand.NEXT) },
            endHoldProgress = progress,
            endEnabled = OverlayAction.STOP_SESSION !in state.unavailable,
            endReason = state.unavailable[OverlayAction.STOP_SESSION],
            onEndPressStart = ::onEndPressStart,
            onEndPressRelease = ::onEndPressRelease,
            onClose = ::closePanel,
            maxHeightDp = maxHeightDp,
            accent = tint,
        )
    }

    /**
     * The full panel's controls, keyed by tab (§5) — a projection of [OverlayPanelState], nothing more.
     *
     * Every control's `enabled` is `action !in state.unavailable`, its `reason` is `state.unavailable[..]`
     * (drawn once, by the panel's `ControlSlot`, so the [OverlayToggle]/[OverlaySlider] inside is passed
     * `enabled` but `reason = null`), and a toggle's on-state is `action in state.active`. Every slot fires
     * an **existing** handler — [onAction], [onSliderLevel], [onPreset]/[onAspect]/[onRate]/[onCrosshair*]
     * — so nothing here changes what a control does (§0). Two SESSION ids (`end_session`, `media_row`) are
     * not in this map: [FullPanel] draws them with its own footer and media row.
     *
     * The expandable rows (colour presets, aspect, refresh, crosshair chips) are drawn **inline** under
     * their toggle rather than behind the old long-press, because a tabbed panel has the room and a hidden
     * gesture in a redesigned surface is a control the user has to be told about. The old `*Expanded`
     * flags and the long-press stay live for the SPLIT_EDGES [OverlayControlPanel], untouched.
     */
    private fun panelControls(
        state: OverlayPanelState,
        accent: Color,
        widthDp: Int,
    ): Map<PanelTab, List<PanelControl>> {
        fun toggle(id: String, action: OverlayAction): PanelControl = PanelControl(
            id = id,
            label = action.label,
            enabled = state.isUsable(action),
            reason = state.unavailable[action],
        ) {
            OverlayToggle(
                label = action.label,
                checked = action in state.active,
                onCheckedChange = { onAction(action) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.isUsable(action),
                accent = accent,
            )
        }

        fun slider(id: String, level: OverlayLevel): PanelControl {
            val levelState = state.levelFor(level)
            return PanelControl(
                id = id,
                label = level.label,
                enabled = levelState.isUsable,
                reason = levelState.detail,
            ) {
                OverlaySlider(
                    label = level.label,
                    value = (levelState.value ?: level.range.first).toFloat(),
                    min = level.range.first.toFloat(),
                    max = level.range.last.toFloat(),
                    step = 1f,
                    unit = "",
                    onValueChange = { onSliderLevel(level, it, markInteraction = false) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = levelState.isUsable,
                    // The app-wide formatter, so a hue reads "+90°" / "0°" and an unread level reads "--"
                    // exactly as the colour editor and the old panel show it — one formatter, not two.
                    valueText = levelState.value?.let { level.format(it) } ?: "--",
                    accent = accent,
                )
            }
        }

        return mapOf(
            PanelTab.DISPLAY to listOf(
                slider("brightness", OverlayLevel.BRIGHTNESS),
                PanelControl(
                    id = "colour",
                    label = OverlayAction.COLOR.label,
                    enabled = state.isUsable(OverlayAction.COLOR),
                    reason = state.unavailable[OverlayAction.COLOR],
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OverlayToggle(
                            label = OverlayAction.COLOR.label,
                            checked = OverlayAction.COLOR in state.active,
                            onCheckedChange = { onAction(OverlayAction.COLOR) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.isUsable(OverlayAction.COLOR),
                            accent = accent,
                        )
                        // The three quick colour sliders and the saved presets, inline (the tile above
                        // opens the full editor). Sliders are signed — a saturation reads "−40%", a hue "+90°".
                        OverlayLevel.entries.filter { it.isColour }.forEach { level ->
                            val levelState = state.levelFor(level)
                            OverlaySlider(
                                label = level.label,
                                // Neutral (the range's midpoint) for an unread colour level, so an unknown
                                // hue parks mid-track rather than reading as fully anticlockwise; the "--"
                                // readout carries that it is unknown.
                                value = (levelState.value ?: (level.range.first + level.range.last) / 2).toFloat(),
                                min = level.range.first.toFloat(),
                                max = level.range.last.toFloat(),
                                step = 1f,
                                unit = if (level == OverlayLevel.HUE) "°" else "%",
                                onValueChange = { onSliderLevel(level, it, markInteraction = false) },
                                modifier = Modifier.fillMaxWidth(),
                                signed = true,
                                enabled = levelState.isUsable,
                                valueText = levelState.value?.let { level.format(it) } ?: "--",
                                accent = accent,
                            )
                        }
                        PresetChips(state = state, accent = accent, widthDp = widthDp, onPreset = ::onPreset)
                    }
                },
                PanelControl(
                    id = "aspect_ratio",
                    label = OverlayAction.ASPECT.label,
                    enabled = state.isUsable(OverlayAction.ASPECT),
                    reason = state.unavailable[OverlayAction.ASPECT],
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OverlayToggle(
                            label = OverlayAction.ASPECT.label,
                            checked = OverlayAction.ASPECT in state.active,
                            onCheckedChange = { onAction(OverlayAction.ASPECT) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.isUsable(OverlayAction.ASPECT),
                            accent = accent,
                        )
                        AspectChips(state = state, accent = accent, widthDp = widthDp, onAspect = ::onAspect)
                    }
                },
                PanelControl(
                    id = "refresh_rate",
                    label = OverlayAction.REFRESH_RATE.label,
                    enabled = state.isUsable(OverlayAction.REFRESH_RATE),
                    reason = state.unavailable[OverlayAction.REFRESH_RATE],
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OverlayToggle(
                            label = OverlayAction.REFRESH_RATE.label,
                            checked = OverlayAction.REFRESH_RATE in state.active,
                            onCheckedChange = { onAction(OverlayAction.REFRESH_RATE) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.isUsable(OverlayAction.REFRESH_RATE),
                            accent = accent,
                        )
                        RateChips(state = state, accent = accent, widthDp = widthDp, onRate = ::onRate)
                    }
                },
                toggle("rotation", OverlayAction.ROTATION_LOCK),
            ),
            PanelTab.OVERLAYS to listOf(
                toggle("stats_pill", OverlayAction.PILL),
                PanelControl(
                    id = "crosshair",
                    label = OverlayAction.CROSSHAIR.label,
                    enabled = state.isUsable(OverlayAction.CROSSHAIR),
                    reason = state.unavailable[OverlayAction.CROSSHAIR],
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OverlayToggle(
                            label = OverlayAction.CROSSHAIR.label,
                            checked = OverlayAction.CROSSHAIR in state.active,
                            onCheckedChange = { onAction(OverlayAction.CROSSHAIR) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.isUsable(OverlayAction.CROSSHAIR),
                            accent = accent,
                        )
                        CrosshairChips(
                            state = state,
                            accent = accent,
                            widthDp = widthDp,
                            onCrosshairDesign = ::onCrosshairDesign,
                            onCrosshairColour = ::onCrosshairColour,
                        )
                    }
                },
                toggle("hud", OverlayAction.HUD),
                toggle("overlay_layout", OverlayAction.PANEL_LAYOUT),
            ),
            PanelTab.CAPTURE to listOf(
                toggle("screenshot", OverlayAction.SCREENSHOT),
                toggle("record", OverlayAction.RECORD),
                toggle("torch", OverlayAction.FLASHLIGHT),
            ),
            PanelTab.SESSION to listOf(
                toggle("silence_dnd", OverlayAction.DO_NOT_DISTURB),
                toggle("open_gamecore", OverlayAction.OPEN_APP),
            ),
        )
    }

    /**
     * Opens the split-edges panel: one full-screen window, both plates inside it.
     *
     * Almost none of [openPanel]'s arithmetic survives here, and that is the point of the two being
     * separate. There is no x to compute because the window starts at the origin; no y and no
     * `anchorBottom`, because it is as tall as the screen and its height is therefore known in advance
     * rather than measured; no `maxHeightDp` for the same reason. The button's position is not consulted
     * at all — a panel pinned to both edges cannot also be positioned from a button that is near one of
     * them.
     *
     * `dismissOnOutsideTouch` is left false, and not as an oversight to tidy up later: it sets
     * `FLAG_WATCH_OUTSIDE_TOUCH`, whose `ACTION_OUTSIDE` is delivered for touches beyond the window's
     * bounds, and a window with `MATCH_PARENT` in both directions has none. Asking for it would install a
     * dismissal path that can never fire. [OverlaySplitPanel] carries its own — the gap between the plates
     * and the ✕ in its header.
     *
     * The width handed down is the screen's, in dp, because the plates are a share of it. Unmeasured is
     * possible for one frame after a window is added but not here — nothing of GameCore's has to be up for
     * this to be asked — so the fallback is the model's own minimum times two rather than a guess at a
     * screen size, which keeps the two plates drawable even in the case that cannot happen.
     */
    private fun openSplitPanel() {
        val manager = windows ?: return
        val frame = manager.frameFor(0, 0)
        val screenWidthDp = if (frame.isMeasured) {
            dp(frame.screenWidth)
        } else {
            FloatingButtonConfig.MIN_PANEL_WIDTH_DP * 2
        }
        val shown = manager.show(
            slot = OverlaySlot.PANEL,
            spec = OverlayWindowSpec(touchable = true, fullScreen = true),
            host = host,
        ) {
            val state by panel.collectAsState()
            val readings by pillReadings.collectAsState()
            val playing by nowPlaying.collectAsState()
            val apps by quickApps.collectAsState()
            val tint by accent.collectAsState()
            OverlaySplitPanel(
                state = state,
                readings = readings,
                nowPlaying = playing,
                quickApps = apps,
                accent = tint,
                screenWidthDp = screenWidthDp,
                onAction = ::onAction,
                onLongAction = ::onLongAction,
                onPreset = ::onPreset,
                onAspect = ::onAspect,
                onRate = ::onRate,
                onCrosshairDesign = ::onCrosshairDesign,
                onCrosshairColour = ::onCrosshairColour,
                onDragLevel = ::onDragLevel,
                onCommitLevel = ::onCommitLevel,
                onMedia = ::onMedia,
                onEnableMedia = ::onEnableMedia,
                onLaunchApp = ::onLaunchApp,
                onDismiss = ::closePanel,
            )
        }
        if (!shown) return
        startPanelWatch(PanelLayoutStyle.SPLIT_EDGES)
    }

    /**
     * What both layouts do once their window is up: mark it open, forget last time's refusals, start the
     * tick and start listening for what is playing.
     *
     * Shared rather than repeated because it is bookkeeping about *a panel being open*, which neither
     * layout has its own version of — and a copy in each would be one copy to forget to update. [denied]
     * and [deniedLevels] are cleared on open rather than on close so a capability the user has since
     * granted is retried, not remembered as refused.
     *
     * The media collection is a child of [panelJob] and not a job of its own, so that the one cancel in
     * [closePanel] takes it down with the tick. `MediaSessionReader.watch` registers two platform
     * listeners and unregisters them from `awaitClose`, which only runs when the collection is cancelled
     * — a second job tracked in a second field is a second thing to forget, and forgetting it would leave
     * a notification-listener callback alive against a panel that closed an hour ago.
     */
    private fun startPanelWatch(layout: PanelLayoutStyle) {
        panelOpen.value = true
        panelLayoutShown = layout
        denied.clear()
        deniedLevels.clear()
        panelJob?.cancel()
        panelJob = lifecycleScope.launch {
            launch { media.watch().collect { nowPlaying.value = it } }
            launch { watchQuickApps() }
            runPanel()
        }
    }

    /**
     * Keeps [quickApps] in step with the two settings that decide it, for as long as a panel is open.
     *
     * Mapped to the pair *before* `distinctUntilChanged`, and that ordering is the whole point of this
     * being a function rather than a line. [preferences] `.floatingButton` also carries the button's x and
     * y, which a drag rewrites on every frame the finger moves; collecting the config itself would hand
     * this a new value dozens of times a second and re-resolve six packages against `PackageManager` for
     * each one. Mapped first, a drag is not a change.
     *
     * The toggle is applied here rather than in the composable, so that "turned off", "turned on with
     * nothing chosen yet" and "every chosen app has since been uninstalled" all arrive at the row as the
     * same empty list. One decision, one place — and the row's own rule stays a single line: no apps, no
     * row.
     */
    private suspend fun watchQuickApps() {
        preferences.floatingButton
            .map { it.showQuickApps to it.quickAppPackages }
            .distinctUntilChanged()
            .collectLatest { (show, packages) ->
                quickApps.value = if (show && packages.isNotEmpty()) {
                    quickAppLauncher.resolve(packages)
                } else {
                    emptyList()
                }
            }
    }

    private fun closePanel() {
        panelJob?.cancel()
        panelJob = null
        // Cleared rather than left at the last track: see [nowPlaying]. The cancel above has already
        // stopped the collection, so nothing is going to write over this.
        nowPlaying.value = NowPlaying.Silent
        // Dropped for a second reason as well as that one: the row holds six bitmaps, and a closed panel
        // has no use for half a megabyte of icons. They are cheap to rebuild — the next open resolves them
        // again, which is also what makes an app uninstalled in the meantime show as gone.
        quickApps.value = emptyList()
        // A grip drag still in flight when the window goes is still the user's choice, and the disposal of
        // the composition is not guaranteed to deliver a cancel to the detector that would have persisted
        // it. Only when one was in flight, though: [panelWidth] also holds screen-clamped widths, and
        // writing one of those back would turn a panel narrowed to fit this screen into the width stored
        // for every screen.
        if (resizingPanel) finishPanelResize()
        // The end-session hold is a child of panelJob, so the cancel above already stopped its loop; the
        // fields are reset here so a reopened panel starts with an empty ring rather than mid-hold.
        endHoldJob = null
        endPressStart = null
        endWasConfirmed = false
        endProgress.value = 0f
        panelTab.value = PanelTab.DEFAULT
        if (panelOpen.value) panelOpen.value = false
        panelLayoutShown = null
        windows?.hide(OverlaySlot.PANEL)
    }

    /**
     * The finger landing on "End session": start the ring, unless there is no session to end.
     *
     * Guarded on the same `unavailable[STOP_SESSION]` the footer dims from — a press on a disabled footer
     * does nothing rather than starting a hold that could never fire. The ring runs on its own fast loop
     * (a child of [panelJob], so [closePanel]'s one cancel takes it down): [HoldToConfirm.firesAt] is the
     * rising edge that ends the session exactly once, [endWasConfirmed] is the latch that stops a second
     * fire while the finger stays down, and [endProgress] is what the footer paints. `elapsedRealtime` for
     * the duration, monotonic, so a wall-clock correction mid-hold cannot skew the ring.
     */
    private fun onEndPressStart() {
        if (OverlayAction.STOP_SESSION in panel.value.unavailable) return
        endPressStart = SystemClock.elapsedRealtime()
        endWasConfirmed = false
        endHoldJob?.cancel()
        endHoldJob = lifecycleScope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                if (endHold.firesAt(endWasConfirmed, endPressStart, now)) onAction(OverlayAction.STOP_SESSION)
                endWasConfirmed = endHold.isConfirmedAt(endPressStart, now)
                endProgress.value = endHold.progressAt(endPressStart, now)
                // Nothing left to schedule once the ring is full — stop waking rather than spin at the top.
                if (endHold.nextChangeAfterMillis(endPressStart, now) == null) break
                delay(END_HOLD_FRAME_MILLIS)
            }
        }
    }

    /**
     * The finger lifting (or the gesture cancelling): empty the ring and drop the latch.
     *
     * Setting [endPressStart] to null is the whole of the cancel — every [HoldToConfirm] query reads null as
     * "not pressing" and returns zero progress — so an early release unwinds nothing. The fire, if it
     * happened, already ran and closed the panel; this only matters for a release *before* the threshold.
     */
    private fun onEndPressRelease() {
        endHoldJob?.cancel()
        endHoldJob = null
        endPressStart = null
        endWasConfirmed = false
        endProgress.value = 0f
    }

    // ------------------------------------------------------------------------------- quick sheet

    private fun toggleQuickSheet() {
        if (quickSheetOpen.value) closeQuickSheet() else openQuickSheet()
    }

    /**
     * Opens the quick sheet (§4): a narrow strip hugging the screen edge nearest the button.
     *
     * The side is the button's own — a sheet on the far edge from the icon the user just tapped makes them
     * look across the screen for what they opened — so it hugs the edge the button's centre is closer to,
     * and its content is told which side that is so the header and close X sit against the same edge (see
     * [QuickSheetSide]). The width is the sheet's own clamp read back as a fixed figure: the composable
     * pins itself to at most [QUICK_SHEET_WIDTH_DP], so laying the window out at that width lets the right
     * edge be placed exactly rather than after a measure, the same trick [openPanel] uses for its width.
     *
     * The vertical anchor mirrors the panel's: grow down from the button's top when it sits high enough,
     * anchor the bottom and grow up when it does not, so a button near either edge does not push the sheet
     * off screen. Neither y needs the sheet's height — [OverlayWindowSpec.anchorBottom] lets the window
     * system do that subtraction with the real measurement.
     *
     * The full panel is closed first: the two are one gesture apart (a double tap, or the sheet's "More"),
     * and both up at once would be two touchable windows fighting for the same outside tap.
     */
    private fun openQuickSheet() {
        val manager = windows ?: return
        if (panelOpen.value) closePanel()
        val width = px(QUICK_SHEET_WIDTH_DP)
        val margin = px(EDGE_MARGIN_DP)
        val gap = px(PANEL_GAP_DP)
        val frame = manager.frameFor(width, 0, margin)
        val placement = manager.positionOf(OverlaySlot.BUTTON)
        val button = manager.frameFor(OverlaySlot.BUTTON)
        val buttonTop = placement?.y ?: 0
        val buttonHeight = button?.windowHeight ?: 0
        val buttonCentreX = (placement?.x ?: 0) + (button?.windowWidth ?: 0) / 2

        // The edge the button's centre is nearer to. Ties go right, matching the panel's own left/right
        // default; a measured screen is needed to know where the middle is, so an unmeasured frame keeps
        // the sheet on the right rather than guessing a side from a zero width.
        val onLeft = frame.isMeasured && buttonCentreX < frame.screenWidth / 2
        val side = if (onLeft) QuickSheetSide.LEFT else QuickSheetSide.RIGHT
        val x = if (onLeft) margin else maxOf(margin, frame.maxX)

        val roomBelow = frame.screenHeight - (buttonTop + gap) - margin
        val roomAbove = buttonTop - gap - margin
        val below = !frame.isMeasured || roomBelow >= roomAbove
        val y = if (below) {
            (buttonTop + gap).coerceAtLeast(margin)
        } else {
            (frame.screenHeight - (buttonTop - gap)).coerceAtLeast(0)
        }

        val shown = manager.show(
            slot = OverlaySlot.QUICK_SHEET,
            spec = OverlayWindowSpec(
                x = x,
                y = y,
                width = width,
                touchable = true,
                dismissOnOutsideTouch = true,
                anchorBottom = !below,
            ),
            host = host,
        ) {
            val state by panel.collectAsState()
            val tint by accent.collectAsState()
            QuickSheetContent(state = state, accent = tint, side = side)
        }
        if (!shown) return
        startQuickSheetWatch()
    }

    /**
     * The sheet's UI, wired to the same handlers the panel uses.
     *
     * Split out of [openQuickSheet] so the `@OptIn` for the outside-touch filter sits on a composable
     * rather than the whole opener. The filter is the §4 close-on-outside-tap: [OverlayWindowSpec]'s
     * `dismissOnOutsideTouch` sets `FLAG_WATCH_OUTSIDE_TOUCH`, whose `ACTION_OUTSIDE` arrives here for a
     * touch beyond the sheet, and returning `true` for it (and `false` for everything else, so the grid's
     * own taps still reach the toggles) is what closes the sheet while the tap still falls through to the
     * game — the same mechanism [OverlayControlPanel] uses.
     *
     * Pins, on-states and slider values are all read from [panel]: the watch fills it exactly as it does
     * for the full panel, so the sheet's toggles agree with the panel's tiles by construction. Every touch
     * — a toggle, a slider — pushes [lastQuickInteraction] forward, which is the whole of the auto-close
     * reset (§4): the watch closes the sheet once that timestamp is [QuickSheetAutoClose.idleAfterMillis]
     * stale.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun QuickSheetContent(state: OverlayPanelState, accent: Color, side: QuickSheetSide) {
        val available: (QuickToggle) -> Boolean = { toggle ->
            toggle.isAlwaysAvailable || state.isUsable(toggle.toOverlayAction())
        }
        val pins = QuickSheetPins
            .resolve(preferences.overlay.value.quickPins, available)
            .map { toggle ->
                val action = toggle.toOverlayAction()
                QuickToggleState(
                    toggle = toggle,
                    isOn = action in state.active,
                    onToggle = {
                        markQuickInteraction()
                        onAction(action)
                    },
                )
            }
        val brightness = state.levelFor(OverlayLevel.BRIGHTNESS)
        val volume = state.levelFor(OverlayLevel.VOLUME)
        QuickSheet(
            gameLabel = state.gameLabel,
            clock = state.sessionElapsed.orEmpty(),
            pins = pins,
            brightness = (brightness.value ?: 0).toFloat(),
            onBrightnessChange = { percent -> onQuickLevel(OverlayLevel.BRIGHTNESS, percent) },
            volume = (volume.value ?: 0).toFloat(),
            onVolumeChange = { percent -> onQuickLevel(OverlayLevel.VOLUME, percent) },
            onMore = {
                markQuickInteraction()
                openPanel()
            },
            onClose = ::closeQuickSheet,
            accent = accent,
            side = side,
            modifier = Modifier.pointerInteropFilter { event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    closeQuickSheet()
                    true
                } else {
                    false
                }
            },
        )
    }

    /**
     * A quick-sheet slider moved. Displays now, commits when the finger settles.
     *
     * The [OverlaySlider] has one per-frame callback, not a drag-then-commit pair — it fires on every frame
     * of a drag. Writing the device on each would be the per-frame settings write §9 rules out (brightness
     * goes through the elevated shell), so this splits the one callback back into the two the service wants:
     * [onDragLevel] moves the thumb every frame by writing the requested value into [panel], and a single
     * commit is posted [QUICK_LEVEL_COMMIT_MILLIS] later, cancelling any still pending for this level, so
     * the write lands once the finger has stopped rather than sixty times while it moves.
     *
     * [markInteraction] is the one thing that differs between the two callers. The sheet marks every drag so
     * an adjustment keeps the sheet open (§4 auto-close); the panel has no such idle timer, so it passes
     * `false`. The debounce, the per-level cancel and the commit are identical for both — one implementation
     * of the "commit once the finger settles" rule, shared by every slider in the overlay.
     *
     * The value arrives as a **percent** (0..100), the value-space the [OverlaySlider] is configured with,
     * so there is no ×100 conversion here — it is clamped and rounded straight to the `Int` the level layer
     * stores.
     */
    private fun onSliderLevel(level: OverlayLevel, percent: Float, markInteraction: Boolean) {
        if (markInteraction) markQuickInteraction()
        onDragLevel(level, percent.roundToInt().coerceIn(0, 100))
        quickLevelCommits[level]?.cancel()
        quickLevelCommits[level] = lifecycleScope.launch {
            delay(QUICK_LEVEL_COMMIT_MILLIS)
            onCommitLevel(level)
        }
    }

    /** The quick sheet's slider: [onSliderLevel] with the auto-close reset the sheet needs and the panel does not. */
    private fun onQuickLevel(level: OverlayLevel, percent: Float) =
        onSliderLevel(level, percent, markInteraction = true)

    /** Push the auto-close deadline forward: any touch on the sheet resets its idle timer (§4). */
    private fun markQuickInteraction() {
        lastQuickInteraction = SystemClock.elapsedRealtime()
    }

    /**
     * The sheet's watch, the sibling of [startPanelWatch].
     *
     * Probes once so the toggles and sliders are live the instant the sheet appears — the bug this exists
     * to prevent is a sheet opened on its own (single tap, panel never touched) showing every toggle off,
     * because [panel] is only ever filled by a running watch. Then it ticks: the session clock is
     * arithmetic, so it is refreshed on the tick like the panel's, and the same loop checks the §4
     * auto-close, closing the sheet once [lastQuickInteraction] is stale. Capabilities are cleared on open,
     * not close, so an access granted since last time is retried — the rule [startPanelWatch] states.
     *
     * The auto-close gate is the user's setting ([OverlayConfig.quickAutoClose], default on). Turned off,
     * the loop still runs — it is also what ticks the session clock in the sheet's header — it simply has
     * no deadline to reach, and the sheet stays until something takes it down. The loop sleeps until the
     * *sooner* of the next tick and the close deadline, so it is one scheduled wake rather than a busy
     * poll (§9).
     */
    private fun startQuickSheetWatch() {
        quickSheetOpen.value = true
        denied.clear()
        deniedLevels.clear()
        markQuickInteraction()
        quickSheetJob?.cancel()
        quickSheetJob = lifecycleScope.launch {
            probePanel()
            // Read once, at open: a setting changed while the sheet is up would otherwise arm or disarm a
            // deadline the user is in the middle of, and the sheet is short-lived enough that the next
            // open picks the change up anyway.
            val autoClose = QuickSheetAutoClose().takeIf { preferences.overlay.value.quickAutoClose }
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                if (autoClose?.shouldCloseAt(lastQuickInteraction, now) == true) break
                panel.value = panel.value.copy(sessionElapsed = sessionElapsedLabel())
                // Two different nulls, which is why the setting is folded into `autoClose` rather than
                // checked here: no machine at all means no deadline and a plain tick, while a machine
                // returning null means the deadline has already passed and the sheet is going now.
                val untilClose = when (autoClose) {
                    null -> PANEL_TICK_MILLIS
                    else -> autoClose.nextChangeAfterMillis(lastQuickInteraction, now) ?: break
                }
                delay(minOf(PANEL_TICK_MILLIS, untilClose))
            }
            // Reached only by the auto-close breaks above; an external close cancels this job at the
            // `delay` and never arrives here, so there is no double close.
            if (isActive) closeQuickSheet()
        }
    }

    /**
     * Takes the sheet down and stops everything it started.
     *
     * The one place the sheet closes, so it is where the watch is cancelled and the pending slider commits
     * are dropped — a commit still posted when the sheet goes would write a level the user was mid-drag on
     * and has since abandoned. The window is hidden last; the flag is dropped so [showButton] stops drawing
     * the button as expanded.
     */
    private fun closeQuickSheet() {
        quickSheetJob?.cancel()
        quickSheetJob = null
        quickLevelCommits.values.forEach { it.cancel() }
        quickLevelCommits.clear()
        if (quickSheetOpen.value) quickSheetOpen.value = false
        windows?.hide(OverlaySlot.QUICK_SHEET)
    }

    /**
     * One frame of a grip drag: clamp, resize the plate and its window together, write nothing.
     *
     * Nothing is written for the reason [dragButtonTo] gives — a preference write per touch event is a
     * keystore round trip per touch event — and for one more: the width is clamped against the screen on
     * the way in, so a drag that runs past the edge would otherwise store the clamp again on every frame.
     */
    private fun resizePanelTo(widthDp: Int) {
        if (setPanelWidth(widthDp)) resizingPanel = true
    }

    /** The finger lifted: persist once, for every game and every open from here on. */
    private fun finishPanelResize() {
        if (!resizingPanel) return
        resizingPanel = false
        preferences.updatePanelWidth(panelWidth.value)
    }

    /**
     * Re-applies a width changed elsewhere to a panel that is already open.
     *
     * This is what makes the settings screen's slider a live preview rather than a number: the panel is up,
     * the slider writes, [observe]'s `floatingButton` collector reconciles, and the panel on screen follows
     * the finger on the slider. Ignored while the grip is being dragged — see [resizingPanel].
     *
     * Also ignored while the split layout is on screen, and that is not the same kind of guard. The grip
     * case is a race worth waiting out; this one is a width that does not exist. A split panel's window is
     * `MATCH_PARENT`, so [setPanelWidth] would hand [OverlayWindows.resize] a figure for a dimension the
     * window does not have — narrowing the whole thing to a third of the screen and leaving the right-hand
     * plate off the edge of it. The stored width stays stored and applies when the user switches back.
     */
    private fun applyPanelWidth(requestedDp: Int) {
        if (resizingPanel) return
        if (panelLayoutShown == PanelLayoutStyle.SPLIT_EDGES) return
        setPanelWidth(requestedDp)
    }

    /**
     * Rebuilds the open panel when the user changes which layout it should be in.
     *
     * A close and a re-open rather than an update, because the two layouts differ in everything
     * [OverlayWindowSpec] carries — flags, size, position, whether outside touches are watched — and
     * [OverlayWindows.resize] moves a window without re-specifying it. Re-opening also re-runs
     * [startPanelWatch], so the tick and the refusal lists belong to the layout on screen.
     *
     * Only while the panel is open. Closed, there is nothing to rebuild and the next [openPanel] reads the
     * preference itself.
     */
    private fun syncPanelLayout(style: PanelLayoutStyle) {
        if (!panelOpen.value || panelLayoutShown == style) return
        closePanel()
        openPanel()
    }

    /**
     * The one place the open panel's width changes. True when it did.
     *
     * Both the window and the flow the composable collects, in that order and in one call, because they are
     * two halves of the same change — see [panelWidth].
     */
    private fun setPanelWidth(requestedDp: Int): Boolean {
        val manager = windows ?: return false
        if (!panelOpen.value) return false
        val clamped = panelWidthFor(requestedDp)
        if (clamped == panelWidth.value) return false
        panelWidth.value = clamped
        manager.resize(OverlaySlot.PANEL, px(clamped), px(EDGE_MARGIN_DP))
        return true
    }

    /**
     * The stored width, clamped to the screen the panel is on.
     *
     * The second of the two clamps [FloatingButtonConfig.panelWidthDp] describes. The first, its
     * `normalised()`, holds the figure inside [FloatingButtonConfig.PANEL_WIDTH_RANGE] and can do no more
     * than that — a data class has no display to measure. This one is the honest limit: the panel opens
     * over a game, [FloatingButtonConfig.MAX_PANEL_WIDTH_DP] is deliberately wider than a phone in
     * portrait, and a width the screen cannot hold would put the grip past the edge with nothing left to
     * drag it back by.
     *
     * Floored at [FloatingButtonConfig.MIN_PANEL_WIDTH_DP] rather than at whatever the screen leaves: two
     * action tiles per row is the narrowest thing that is still a grid, and on a screen too narrow for even
     * that, a panel a few dp wider than its margins allow is better than one that has quietly become a
     * single column of buttons.
     */
    private fun panelWidthFor(requestedDp: Int): Int {
        val requested = requestedDp.coerceIn(FloatingButtonConfig.PANEL_WIDTH_RANGE)
        val frame = windows?.frameFor(0, 0) ?: return requested
        if (!frame.isMeasured) return requested
        val room = (dp(frame.screenWidth) - EDGE_MARGIN_DP * 2)
            .coerceAtLeast(FloatingButtonConfig.MIN_PANEL_WIDTH_DP)
        return requested.coerceAtMost(room)
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

        // The colour tile is asked the narrow question — is *GameCore's* correction still in force — for
        // the reason [ColorCorrectionController.isEngagedByGameCore] gives: the full read is up to eight
        // shell round trips, and this panel is sitting over a game.
        if (colour.isEngagedByGameCore()) active += OverlayAction.COLOR

        // Deliberately never marked unavailable, unlike every other action here. A tap on it opens the
        // colour editor, and authoring a preset works on a device that cannot write a single colour key —
        // greying it out would take away the one screen that explains *why* the sliders are dimmed. The
        // access problem belongs to the controls that would actually be refused, and [levelStates] puts it
        // on all three of them.

        if (!gaming.isTracking) {
            unavailable[OverlayAction.STOP_SESSION] = getString(R.string.overlay_no_session)
        }

        // One read for the whole of the shape tile: whether it can be used at all, whether the display is
        // off its native size, which shapes this panel can take, and which of them is on screen now all
        // come out of the same [DisplaySizeController.state] call. Held to the rule the colour tile is held
        // to a few lines up — a window sitting over a game does not get to ask `wm` four separate times.
        //
        // The plate means the *display* is stretched, not that GameCore stretched it, which is the same
        // thing the rotation tile above reports and for the same reason: a player who finds their screen the
        // wrong shape needs the tile that fixes it to be lit, whoever set it.
        val sizes = displaySize.state()
        val sizeState = sizes.valueOrNull
        if (sizeState?.isOverridden == true) active += OverlayAction.ASPECT
        sizes.unavailabilityText()?.let { unavailable[OverlayAction.ASPECT] = it }

        // The rates come from the platform's own mode list, and the tile is dimmed when there is nothing to
        // choose from — a single-rate panel and an unreadable mode list being two different sentences, since
        // one has no fix and the other might. Held to the same one-read rule the two tiles above are: this is
        // `Display.getSupportedModes()`, which is a local call rather than a shell round trip, but it is
        // still one call for the tile, the row and the chips together.
        val rates = displayReader.supportedRates()
        // Two or more, matching [DisplayReader.hasVariableRefreshRate]'s own test: a row with one chip in it
        // looks like a control that failed to load, and the user has nothing to decide.
        val offeredRates = rates.valueOrNull?.takeIf { it.size >= MIN_SELECTABLE_RATES }.orEmpty()
        if (offeredRates.isEmpty()) {
            unavailable[OverlayAction.REFRESH_RATE] = rates.shortUnavailabilityText()
                ?: getString(R.string.overlay_refresh_single_rate)
        }
        // Lit from GameCore's own confirmed change and from nothing else — see [pinnedRate], which is where
        // the argument for that lives.
        if (pinnedRate != null) active += OverlayAction.REFRESH_RATE

        // The stored preference rather than [panelLayoutShown], which is the layout of the window this probe
        // is drawing into. The two agree everywhere except the one frame between the tap and the rebuild,
        // and in that frame the preference is the answer the user just gave — reading the window would make
        // the tile go dark on the tap that turned it on and light again a frame later.
        //
        // Never added to [unavailable]: there is no state of the device in which a preference of GameCore's
        // own cannot be written. See [OverlayAction.PANEL_LAYOUT].
        if (preferences.floatingButton.value.panelLayout == PanelLayoutStyle.SPLIT_EDGES) {
            active += OverlayAction.PANEL_LAYOUT
        }

        panel.value = OverlayPanelState(
            gameLabel = gaming.gameLabel.ifEmpty { request.gameLabel },
            sessionElapsed = sessionElapsedLabel(),
            active = active,
            // What was tried and refused wins over what could be worked out in advance.
            unavailable = unavailable + denied,
            levels = levelStates(),
            presets = colorPresets.all().map { OverlayPreset(it.id, it.name) },
            // Carried over rather than defaulted. This is the one field in the state that is the user's
            // own doing and not a fact about the device, and a re-probe runs after every action — a
            // screenshot taken with the chips open would otherwise fold them away.
            presetsExpanded = panel.value.presetsExpanded,
            activePresetId = preferences.activeColorPresetId,
            // Empty on a device whose panel size could not be read, which is the honest answer: every shape
            // here is computed from that size, so without it there is not even a native chip to offer.
            aspects = sizeState?.options ?: emptyList(),
            // Carried over for the same reason [presetsExpanded] is.
            aspectsExpanded = panel.value.aspectsExpanded,
            activeAspect = sizeState?.activePreset,
            aspectNote = sizeState?.let(::aspectNote),
            refreshRates = offeredRates,
            // Carried over for the same reason [presetsExpanded] is.
            refreshRatesExpanded = panel.value.refreshRatesExpanded,
            // The two fields in this state that are GameCore's own record rather than a reading. See
            // [pinnedRate] for why that is the honest way round for this one control.
            pinnedRefreshRate = pinnedRate,
            refreshRateNote = rateNote,
            // The crosshair that is actually being drawn, not the first row in the table — [resolveCrosshair]
            // has already picked between the profile's choice and the fallback, so reading its result is what
            // makes the chips describe the shape on the screen. Reduced to four fields by §24A.2.
            crosshair = crosshairPreset.value?.let {
                OverlayCrosshair(
                    id = it.id,
                    name = it.name,
                    design = it.design,
                    colorArgb = it.colorArgb,
                )
            },
            // Carried over for the same reason [presetsExpanded] is.
            crosshairExpanded = panel.value.crosshairExpanded,
                // The built-in eight plus whatever the user mixed in the picker, in that order, which is
                // the same row the crosshair screen draws. The panel offers no picker of its own — an HSV
                // square is a two-handed control and this window is open over a game — so the colours it
                // can reach are the built-ins and the ones already chosen somewhere with more room.
                crosshairColours = CROSSHAIR_COLOURS + preferences.customCrosshairColours,
        )
    }

    /**
     * What to say under the shape chips, or null when the chips say it themselves.
     *
     * Only the custom case needs words. A display sitting on one of the four shapes has that chip filled,
     * and a sentence repeating it would be noise; a display running a size the user typed into a profile
     * leaves every chip unfilled, and *that* row has to explain itself or it reads as one that failed to
     * load. The unreadable case is not handled here — the row has its own sentence for having no chips at
     * all, and the tile is already carrying the reason it cannot be used.
     */
    private fun aspectNote(state: DisplaySizeState): String? = if (state.isCustom) {
        getString(R.string.overlay_aspect_custom, state.active.label, state.active.aspectLabel)
    } else {
        null
    }

    /**
     * Where volume and brightness stand — the two device levels; the colour ones are
     * [colourLevelStates]' business, because they answer a different question.
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
                value = volume.valueOrNull,
                reason = deniedLevels[OverlayLevel.VOLUME] ?: volume.unavailabilityText(),
            ),
            OverlayLevel.BRIGHTNESS to OverlayLevelState(
                value = brightness.valueOrNull,
                reason = brightnessReason,
            ),
        ) + colourLevelStates()
    }

    /**
     * Where the three quick colour sliders stand — and it is a different shape of answer.
     *
     * The value is never unreadable, because it is not read from the device: a correction is GameCore's
     * own stored intent, and [SecurePreferenceStore.colorCorrection] holds the fourteen values the user
     * last set whether or not this display could express them. That is also what makes the panel reopen
     * where the user left off.
     *
     * So the two facts that vary are the other two. [OverlayLevelState.reason] is the write mechanism:
     * every colour key is in `Settings.Secure`, so one missing access dims all three at once, and the
     * sentence names Shizuku as the way to fix it. [OverlayLevelState.note] is the field's reach on this
     * hardware, which is the fact the colour feature needs and volume and brightness never did — a hue
     * rotation is stored, travels with a preset and is honoured on a device that has a colour matrix,
     * and there is no `Settings.Secure` key for it here. Dimming that slider would strand the value; the
     * note is how the panel says so without pretending the control is broken.
     *
     * The live plan is preferred over the standing probe because it is the more specific truth: it
     * describes the value the user actually has set, where [ColorCorrectionController.reachability]
     * describes the field in the abstract. Both are pure arithmetic — see [ColorProjection] — so this
     * costs nothing beyond the one access check the sliders share.
     */
    private suspend fun colourLevelStates(): Map<OverlayLevel, OverlayLevelState> {
        val correction = preferences.colorCorrection.value
        val plan = colour.preview(correction)
        val accessReason = colour.access().unavailabilityText()
        return OverlayLevel.entries.mapNotNull { level ->
            val field = level.colourField ?: return@mapNotNull null
            level to OverlayLevelState(
                value = correction.valueOf(field),
                reason = deniedLevels[level] ?: accessReason,
                note = plan.limitFor(field)?.message ?: colour.reachability(field)?.message,
            )
        }.toMap()
    }

    /**
     * Re-reads the sliders without re-probing the buttons.
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
            // Re-probe when either surface is up: the quick sheet draws the same [panel] the panel does,
            // so a toggle fired from the sheet has to refresh its own on-states too, not just the panel's.
            if (panelOpen.value || quickSheetOpen.value) probePanel()
        }
    }

    /**
     * A transport button in the media strip.
     *
     * Nothing is written to [nowPlaying] here, and nothing is guessed about what the tap will do. The
     * owning app publishes a new [android.media.session.PlaybackState] when it acts on the command, the
     * watch started in [startPanelWatch] delivers it, and the strip redraws from that — so the Pause
     * button turning into a Play button is evidence the app actually paused rather than an optimistic
     * repaint. On a device where the app ignores the command the button simply does not change, which is
     * the truth.
     *
     * A refusal is a toast, on the same reasoning [report] gives for every other control: a button that
     * was tapped and did nothing visible is the failure mode this overlay is built to avoid. Not recorded
     * in [denied] — that map is keyed by [OverlayAction] and holds structural refusals, and a transport
     * call failing means the session died a moment ago, which the next emission has already fixed.
     */
    private fun onMedia(command: MediaCommand) {
        lifecycleScope.launch {
            if (!media.send(command)) toast(getString(R.string.overlay_media_send_failed))
        }
    }

    /**
     * The strip's "Enable" prompt: close the panel, open the screen that explains the access.
     *
     * Straight to [com.gamecore.ui.media.MediaAccessScreen] rather than straight to the system's
     * notification-access list. The system page names every app on the device and explains nothing about
     * why this one is asking — a user who arrives there from a game overlay is being asked to grant the
     * broadest-sounding permission Android has with no context at all, and the honest thing is to say
     * what it is read for, and what it is never read for, before they get there.
     *
     * The panel closes first for the reason [OverlayAction.COLOR] closes it: the app is about to come to
     * the front, and an overlay left up over it is a panel floating on top of the screen it opened.
     */
    private fun onEnableMedia() {
        closePanel()
        openApp(MainActivity.DESTINATION_MEDIA_ACCESS)
    }

    /**
     * A tap on a quick-launch icon: start the app, say so if it did not start.
     *
     * The panel is deliberately left open, and detection is deliberately left running — the spec for this
     * row says a quick launch is the same event as switching to any other app while GameCore is up, and it
     * is. [onEnableMedia] closes the panel because it brings *GameCore* to the front and an overlay over
     * its own settings screen is a bug; this brings a third-party app to the front, which is what the
     * overlay is for. The launched app covers the panel window itself, and coming back from it finds the
     * panel where it was left.
     *
     * Not filtered on [QuickApp.isAvailable] before calling, even though the row does not make unavailable
     * icons clickable. The check is in [QuickAppLauncher.launch] because that is where it can still be true
     * at the moment it matters: the row was resolved when the panel opened and an app can go away after
     * that. Two guards for one fact, and the one that runs last is the one that counts.
     */
    private fun onLaunchApp(app: QuickApp) {
        lifecycleScope.launch {
            val outcome = quickAppLauncher.launch(app.packageName)
            if (outcome is QuickLaunchOutcome.Failed) toast(outcome.reason)
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
    private fun onDragLevel(level: OverlayLevel, value: Int) {
        val current = panel.value
        val state = current.levelFor(level)
        if (!state.isUsable || state.value == value) return
        panel.value = current.copy(levels = current.levels + (level to state.copy(value = value)))
    }

    /**
     * The finger lifting: one write, then a read-back.
     *
     * The read-back is what makes the thumb honest. A media stream has around fifteen steps, so most
     * percentages a finger can land on are not reachable, and the level the device settled on is the one
     * the slider should show — [refreshLevels] is what moves it there.
     */
    private fun onCommitLevel(level: OverlayLevel) {
        val requested = panel.value.levelFor(level).value ?: return
        lifecycleScope.launch {
            val outcome = when (level) {
                OverlayLevel.VOLUME -> audio.setMediaVolumePercent(requested)
                OverlayLevel.BRIGHTNESS -> displayControls.setBrightnessPercent(requested)
                // All three take the same path and differ only by the field they carry, which is why
                // [OverlayLevel.applyTo] does the writing: the field the slider was drawn from is the
                // field it writes to, structurally.
                OverlayLevel.SATURATION,
                OverlayLevel.CONTRAST,
                OverlayLevel.HUE,
                -> commitColour(level, requested)
            }
            reportLevel(level, outcome)
            // Either surface: the sheet's brightness and volume sliders read back the same [panel] levels,
            // so a commit from a sheet drag has to re-read them too — otherwise the thumb would sit at the
            // requested percent rather than the one the device settled on.
            if (panelOpen.value || quickSheetOpen.value) refreshLevels()
        }
    }

    /**
     * Stores one colour value, then applies the whole correction.
     *
     * Stored first and unconditionally, because the store is the user's intent and the apply is what this
     * display can make of it. A hue this device has no sink for still has to survive the panel closing,
     * still has to travel into a preset the user saves later, and still has to be the number the slider
     * comes back to — §24's honesty cuts this way too: refusing to remember a value because the hardware
     * cannot express it is its own kind of lie.
     *
     * The whole correction goes to [ColorCorrectionController.apply] rather than the one field, because a
     * colour sink is not per-field: the night display's temperature is the red−blue difference and the
     * daltonizer is a mode, so a saturation change can move a key that the previous drag of a different
     * slider also moved. Applying the correction as a unit is what keeps the eight sinks consistent with
     * the fourteen values, and it is the same call the profile applier makes.
     *
     * The preset link is dropped, because it is no longer true. A user who loads "Night" and then drags
     * saturation is not on Night any more, and leaving the chip highlighted would have the panel claiming
     * a preset is applied when the values on screen are the user's own.
     */
    private suspend fun commitColour(level: OverlayLevel, value: Int): ControlOutcome {
        preferences.updateColorCorrection { level.applyTo(it, value) }
        preferences.activeColorPresetId = null
        return colour.apply(
            correction = preferences.colorCorrection.value,
            // The game the change was made for, so a correction still in force months later can be
            // attributed in session history rather than appearing from nowhere.
            packageName = coordinator.gaming.value.playing,
        ).toOutcome()
    }

    /**
     * A colour apply in the panel's vocabulary, so [reportLevel] needs no colour-specific branch.
     *
     * The restricted case is kept distinct from a failure on purpose: it is the one outcome the user can
     * do something about, and [ControlOutcome.RequiresAccess.needsShizuku] is what decides which button
     * the app offers them. Everything else that did not fully apply is a failure with the sentence the
     * result already composed.
     */
    private fun ColorApplyResult.toOutcome(): ControlOutcome = when {
        access is Observed.Restricted -> ControlOutcome.RequiresAccess(
            detail = message,
            needsShizuku = (access as Observed.Restricted).unlockedBy == AccessLevel.SHIZUKU,
        )
        isApplied -> ControlOutcome.Applied()
        else -> ControlOutcome.Failed(message)
    }

    /**
     * A long press, which two tiles have: it drops a second row under the grid.
     *
     * No coroutine and no re-probe. Everything either row draws is already in the state — [probePanel] reads
     * the colour presets and the active crosshair together with everything else — so the row appears on the
     * press rather than a database round trip later, and a gesture that has to be held already feels slow
     * enough without waiting for storage. Both empty cases are the composables' own, and they say so in words
     * rather than opening a blank row.
     *
     * A `when` over [OverlayAction.heldRow] rather than over the action, so the two tiles that offer the
     * gesture and the two rows it opens are the same fact stated once. The `null` branch is the twelve tiles
     * that pass no long-press handler at all — unreachable from the panel, and handled rather than asserted
     * against because a service is not the place to throw over a gesture.
     */
    private fun onLongAction(action: OverlayAction) {
        when (action.heldRow) {
            HeldRow.COLOUR_PRESETS ->
                panel.value = panel.value.copy(presetsExpanded = !panel.value.presetsExpanded)
            HeldRow.CROSSHAIR_QUICK_PICK ->
                panel.value = panel.value.copy(crosshairExpanded = !panel.value.crosshairExpanded)
            null -> Unit
        }
    }

    /**
     * A preset chip: load its values, remember which one it was, and apply.
     *
     * Read back by id rather than carried in the window state — §24A.2, the reason [OverlayPreset] holds
     * a name and an id and not fourteen values.
     *
     * A preset deleted in the editor while this panel sat over a game is a real case, and the honest
     * answer is to say so and take the chip away rather than to apply the last thing that was there.
     */
    private fun onPreset(id: Long) {
        lifecycleScope.launch {
            val preset = colorPresets.preset(id)
            if (preset == null) {
                toast(getString(R.string.overlay_preset_missing))
                if (panelOpen.value) probePanel()
                return@launch
            }
            preferences.setColorCorrection(preset.correction)
            preferences.activeColorPresetId = preset.id
            val result = colour.apply(preset.correction, coordinator.gaming.value.playing)
            // Silent only when the screen actually changed. A preset whose every value this display has no
            // sink for "applies" without a single write, and a chip that lights up over an unchanged screen
            // is exactly the button §32 rules out — so the sentence the result carries is shown.
            if (!result.isApplied || result.plan.engagesNothing) toast(result.message)
            if (panelOpen.value) probePanel()
        }
    }

    /**
     * A shape chip: stretch the display to it, or take the stretch off.
     *
     * Applies on the tap with no confirmation step, which is what "instant apply" means and what every
     * other control in this panel does — and it is safe to do here for a reason worth naming: the row's
     * first chip is always native, so the undo for a tap the user regrets is one tap away and is on screen
     * already. [DisplaySizeController.apply] refuses a size the panel cannot take before writing anything,
     * so the chips cannot walk the display somewhere it cannot come back from.
     *
     * The outcome is announced whenever it is not a confirmed success. A display size that was asked for
     * and silently ignored is the specific failure §3 names — some builds decline an override for the
     * built-in screen — and the chip row would otherwise sit there with nothing filled and nothing said.
     *
     * Re-probed afterwards for the same reason the colour chips are: the plate on the tile, the filled
     * chip and the note under the row are all read from the display, not from what was just requested.
     */
    private fun onAspect(choice: AspectChoice) {
        lifecycleScope.launch {
            val outcome = displaySize.apply(choice.preset, coordinator.gaming.value.playing)
            if (!outcome.isSuccess) toast(outcome.message)
            if (panelOpen.value) probePanel()
        }
    }

    /**
     * A rate chip: hold the display at it, or stop holding it.
     *
     * Straight through [RefreshRateController], which is the same object the profile editor's rate field
     * writes through and the same one a game launch applies a profile's `targetRefreshRate` with. That
     * matters more here than it reads: pinning a rate is `Settings.System.min_refresh_rate` and
     * `peak_refresh_rate` written together, verified by reading them back, with a per-chipset caveat about
     * builds that accept both and honour neither. A second path that wrote one key, or skipped the
     * verification, or reported "done", would be a control that disagreed with the one in Settings about
     * what the display is doing. So the panel has no rate logic of its own — [refreshRateFeedback] is a
     * pure mapping of the outcome onto two pieces of panel state, not a second implementation.
     *
     * Applied on the tap with no confirmation, like the shape chips, and safe for the same structural
     * reason: the row's first chip is "leave alone", so the undo is one tap away and already on screen.
     *
     * The re-probe at the end is not what fills the chip — [pinnedRate] is set here, from the outcome,
     * before it runs. It is there because a rate change is a real change to the display and the rest of
     * the panel reads from the display.
     */
    private fun onRate(rateHz: Float?) {
        lifecycleScope.launch {
            val outcome = if (rateHz == null) refreshRate.release() else refreshRate.apply(rateHz)
            val feedback = refreshRateFeedback(requestedHz = rateHz, outcome = outcome)
            pinnedRate = feedback.pinnedRateHz
            rateNote = feedback.message
            // Toasted as well as noted under the row, because the row can be scrolled off a short plate
            // and "nothing happened" needs to reach the user who is looking at the game.
            feedback.message?.let(::toast)
            if (panelOpen.value) probePanel()
        }
    }

    /**
     * A design chip: change the saved crosshair's shape.
     *
     * The same write the crosshair screen makes, and that is the requirement rather than an implementation
     * detail. [CrosshairRepository.save] is a whole-row upsert, so the change is read-modify-write on the
     * preset that is *resolved* — `copy`, `normalised()`, save — which is exactly what the editor's own
     * `edit {}` does. There is deliberately no quick-pick copy of the crosshair anywhere: a design tapped
     * here is the design the crosshair screen opens on, because there is only one row.
     *
     * Nothing is poked afterwards. [resolveCrosshair] is collecting the presets table, so the save re-emits,
     * the resolved preset changes, and the crosshair window redraws where it stands — see its own KDoc, which
     * is the same mechanism a slider in the editor uses to redraw over a game. The panel's filled chip follows
     * from there rather than from what was just requested, which is why the probe is in that collector and not
     * in this handler: a chip filled here would be filled from the write, and a chip filled there is filled
     * from the crosshair that is actually on the screen.
     */
    private fun onCrosshairDesign(design: CrosshairDesign) {
        editCrosshair { it.copy(design = design) }
    }

    /** A swatch: change the saved crosshair's colour. Everything in [onCrosshairDesign] applies. */
    private fun onCrosshairColour(argb: Int) {
        editCrosshair { it.copy(colorArgb = argb) }
    }

    /**
     * The one write path both crosshair chips take.
     *
     * Reads [crosshairPreset] rather than the repository, so the row that is changed is the row that is being
     * drawn — on a device with several saved crosshairs those are not the same thing, and editing the first
     * one in the table while a profile draws the third is the bug this closes.
     *
     * Null means the presets table is empty, which the seeded defaults normally rule out and which the row
     * itself already explains. Guarded anyway rather than asserted: a service that threw here would take the
     * overlay down over a chip tap.
     */
    private fun editCrosshair(transform: (CrosshairPreset) -> CrosshairPreset) {
        val current = crosshairPreset.value ?: return
        lifecycleScope.launch {
            crosshairs.save(transform(current).normalised())
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
            OverlayAction.CROSSHAIR -> {
                val request = overlays.desired.value
                val next = !request.crosshair
                overlays.setCrosshair(next)
                // Persisted like the pill, and for the same reason, with one condition the pill does not
                // need: these two flags are the manual state the next launch is rebuilt from, and while a
                // profile is driving the overlay the controller deliberately leaves the manual request
                // alone. Writing the flag then would put the stored state out of step with it — the
                // profile's session-long override would outlive the session.
                if (!request.fromProfile) preferences.showCrosshairOverlay = next
            }
            OverlayAction.HUD -> {
                val request = overlays.desired.value
                val next = !request.hud
                overlays.setHud(next)
                if (!request.fromProfile) preferences.showHudOverlay = next
            }
            OverlayAction.SCREENSHOT -> takeScreenshot()
            OverlayAction.RECORD -> toggleRecording()
            OverlayAction.FLASHLIGHT -> report(action, torch.toggle())
            OverlayAction.DO_NOT_DISTURB -> {
                val silencing = audio.doNotDisturbState().valueOrNull?.isSilencing == true
                report(action, audio.setDoNotDisturb(!silencing))
            }
            OverlayAction.ROTATION_LOCK -> report(action, toggleRotationLock())
            // The one tile that opens a screen instead of changing something. Its filled plate still means
            // what every other plate in this grid means — the correction is in force — but fourteen values,
            // a gamma mode and a preset library do not fit in a window that has to stay out of the way of a
            // game, and the three sliders above are the part of it that belongs here. So the tap is a door,
            // and the long press is the shortcut to the thing users want most often: their saved presets.
            OverlayAction.COLOR -> {
                closePanel()
                openApp(MainActivity.DESTINATION_COLOUR)
            }
            // Opens the shape chips rather than a screen, unlike the tile above it. The whole control is
            // four sizes computed from this display, and they fit in a row; there is nothing left over to
            // put on a screen. So the tap that would have been a door is the control itself, and the way
            // back to native is the first chip it reveals.
            OverlayAction.ASPECT ->
                panel.value = panel.value.copy(aspectsExpanded = !panel.value.aspectsExpanded)
            // Opens its chips on the tap for the same reason the tile above it does, and the row it opens
            // has the same shape: every rate this panel reports, plus the way back off them. The chips
            // themselves are [onRate]'s business; this only decides whether they are showing.
            OverlayAction.REFRESH_RATE ->
                panel.value = panel.value.copy(refreshRatesExpanded = !panel.value.refreshRatesExpanded)
            // The one action that changes the window it was tapped in, and it does so without naming a
            // window: the preference is written, [observe]'s `floatingButton` collector reconciles, and
            // [syncPanelLayout] rebuilds the open panel in the other shape. Which means a switch from here
            // and a switch from the settings screen take the same path, and this branch cannot leave a
            // panel on screen in a layout the stored preference disagrees with.
            //
            // Written through the narrow [SecurePreferenceStore.updatePanelLayout] rather than
            // `updateFloatingButton`, for the reason that writer gives — the panel is open, so the button's
            // position in this process may be a frame ahead of the stored one.
            OverlayAction.PANEL_LAYOUT ->
                preferences.updatePanelLayout(preferences.floatingButton.value.panelLayout.other())
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
        // A Screenshot pin can fire this from the sheet; the sheet has to leave the frame too, or the
        // screenshot has GameCore's own strip in the corner of it.
        closeQuickSheet()
        delay(CAPTURE_SETTLE_MILLIS)
        startCapture(CapturePurpose.SCREENSHOT)
    }

    private suspend fun toggleRecording() {
        if (capture.recording.value.isRecording) {
            startCapture(CapturePurpose.STOP_RECORDING)
            return
        }
        closePanel()
        closeQuickSheet()
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

    /**
     * Brings GameCore to the front, optionally on a particular screen.
     *
     * [destination] is one of [MainActivity]'s own constants and never anything the user typed. That is
     * the whole of the contract: the activity treats the extra as untrusted and matches it against a
     * closed list, so a third-party app that guesses the extra's name can reach a GameCore screen and
     * nothing else — no path, no id, no package name travels this way.
     */
    private fun openApp(destination: String? = null) {
        val intent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        destination?.let { intent.putExtra(MainActivity.EXTRA_DESTINATION, it) }
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
         * The width the quick sheet's window is laid out at, matching the top of the composable's own
         * `widthIn(max = 300.dp)` clamp so the window is exactly as wide as the sheet draws itself. Laying
         * it out at a known width is what lets [openQuickSheet] place the right edge without waiting for a
         * measure — the same reason [openPanel] uses a fixed width for its own placement.
         */
        const val QUICK_SHEET_WIDTH_DP = 300

        /**
         * How long after a quick-sheet slider stops moving before its value is written.
         *
         * The sheet's slider fires on every drag frame (§4 gives it one callback, not the panel's
         * drag-then-commit pair), and brightness writes go through the elevated shell. Coalescing to one
         * write this long after the finger settles keeps a drag from being sixty shell round trips (§9),
         * while being short enough that the level lands as soon as the user lets go. See [onQuickLevel].
         */
        const val QUICK_LEVEL_COMMIT_MILLIS = 120L

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
         * How often the end-session ring repaints while the finger is held.
         *
         * Its own fast loop, not the 1 s [PANEL_TICK_MILLIS] — a ~2 s ring ticked once a second would jump
         * in two steps. ~30 ms is roughly a frame; the loop stops the instant the hold completes, so it is
         * live only for the couple of seconds a finger is actually down.
         */
        const val END_HOLD_FRAME_MILLIS = 30L

        /**
         * How long a Quick Trigger waits for the floating button before opening the panel anyway.
         *
         * Long enough for a cold service start to get its first window up, short enough that a trigger
         * never feels like it was ignored. On expiry the panel opens regardless, positioned against
         * whatever the window manager reports, because a panel in the wrong place is still better than
         * a shortcut that did nothing.
         */
        const val PANEL_ANCHOR_TIMEOUT_MS = 900L

        const val PANEL_ANCHOR_POLL_MS = 30L

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

        /**
         * How many rates a display has to report before the panel offers a choice of them.
         *
         * Two, matching [DisplayReader.hasVariableRefreshRate]'s own test rather than restating it as one:
         * a row holding a single chip reads as a control that failed to load, and there is nothing for the
         * user to decide on a panel with one mode. Below this the tile is dimmed with a reason, which is a
         * different thing from a row of one.
         */
        const val MIN_SELECTABLE_RATES = 2
    }
}
