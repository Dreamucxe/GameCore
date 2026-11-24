package com.gamecore.core.wheel

import android.content.Context
import android.view.MotionEvent
import com.gamecore.core.model.WheelConfig
import com.gamecore.core.model.WheelOperatingMode
import com.gamecore.core.model.WheelProfile
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.domain.overlay.OverlayController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Measured runtime facts about the wheel.
 *
 * Every number here is something this app actually timed or counted:
 *  - [processedEvents] is the number of injection calls whose app-side duration was measured.
 *  - [injectedEvents] / [refusedEvents] are counts of calls whose dispatcher result was true/false.
 *  - [delayedTaps] counts taps whose UP arrived before the hand-off accepted the DOWN. Those taps are
 *    completed, not dropped; the count is how often the hand-off took longer than the tap.
 *  - [handoffMicros] is the measured time from the physical DOWN to the accepted injected DOWN. It is
 *    zero until the first successful hand-off and is the only place the window-flag wait is charged.
 *  - [averageProcessingMicros] is an EWMA of app-side processing *including* the synchronous binder call,
 *    measured around [ShizukuInputBridge.send].
 *  - [binderMicros] is the bridge's own AIDL round trip.
 *
 * None of them is end-to-end latency, and none is shown as such. The time from the game's frame being
 * drawn to the player seeing the result includes the display pipeline and the game's own input handling,
 * which GameCore cannot observe without a capture feed and frame timestamps from the game.
 */
data class WheelDiagnostics(
    val processedEvents: Long = 0L,
    val injectedEvents: Long = 0L,
    val refusedEvents: Long = 0L,
    /** Taps that completed after the hand-off, because their UP arrived while the DOWN was still pending. */
    val delayedTaps: Long = 0L,
    /** Measured physical DOWN to accepted injected DOWN, including the window-flag wait. */
    val handoffMicros: Int = 0,
    val lastProcessingMicros: Int = 0,
    val averageProcessingMicros: Int = 0,
    val peakProcessingMicros: Int = 0,
    val binderMicros: Int = 0,
    val pointerActive: Boolean = false,
    val lastFrame: WheelTouchFrame? = null,
    val lastError: String? = null,
    val lastGestureAtElapsed: Long = 0L,
)

/**
 * The wheel's observable runtime state.
 *
 * [mode] is runtime-only by design. It starts at [WheelOperatingMode.DISABLED] in every process and is
 * never written to storage: a restart must not resume injecting touches into another app. [arming] is
 * the short interval in which the privileged helper is binding; the wheel window is not shown until the
 * helper is ready, so there is no state in which a touchable wheel exists and injects nothing.
 */
data class WheelRuntimeState(
    val mode: WheelOperatingMode = WheelOperatingMode.DISABLED,
    val arming: Boolean = false,
    val effectiveProfile: WheelProfile = WheelProfile.DEFAULT,
    val activeProfileId: String = WheelProfile.DEFAULT_ID,
    val perGameProfile: Boolean = false,
    val quickPanelOpen: Boolean = false,
    val precision: Boolean = false,
    val turbo: Boolean = false,
    val calibration: WheelTouchFrame? = null,
    val diagnostics: WheelDiagnostics = WheelDiagnostics(),
    /** True when enabling was refused until the user accepts the injection risk warning. */
    val consentRequired: Boolean = false,
    val lastError: String? = null,
    val gamePackage: String? = null,
    val gameLabel: String = "",
    val injector: InjectionState = InjectionState(),
) {
    /** True when the wheel window should be on screen. [arming] alone is not enough — see the class KDoc. */
    val visible: Boolean get() = mode != WheelOperatingMode.DISABLED

    /** True when touches are being consumed and re-emitted. */
    val injecting: Boolean get() = mode == WheelOperatingMode.GAMING && injector.canInject

    /** Calibration and positioning consume touches but never inject. */
    val capturesTouches: Boolean get() = mode.capturesTouches
}

/**
 * The one owner of the High Sensitivity Wheel at runtime.
 *
 * It sits between three things that must not know about each other:
 *  - the overlay service, which reports raw pointer samples and draws what this state says;
 *  - [WheelTouchProcessor], which is pure maths;
 *  - [ShizukuInputBridge], which is a binder to a shell-uid process.
 *
 * The split exists so the dangerous, testable and visual concerns each have one home. The controller
 * never constructs a window, never constructs a `MotionEvent`, and never decides a colour. The service
 * never does maths. The bridge never decides whether the wheel should be armed.
 *
 * **Input path.** A pointer callback calls [onTouch] synchronously. In GAMING the service makes the overlay
 * non-touchable first, then the first DOWN is handed to the dispatcher as soon as that flag can be seen.
 * On Android 13+ with a resolved game uid the targeted path is attempted immediately and retried on a
 * target-mismatch refusal; otherwise the first attempt waits one conservative frame for an untargeted
 * event to stop landing on the overlay. Only the newest MOVE is retained during that hand-off, and a tap
 * whose UP arrives first is completed rather than dropped. After the DOWN is accepted every sample is
 * synchronous and unbuffered. StateFlow drives visuals but injection never waits for recomposition.
 *
 * **Safety.** [disengage], a lost Shizuku binder, a screen-off event and service destruction all funnel
 * through [abortGesture] and [releaseAll]. A pointer left down in a game is the worst failure this
 * feature can produce, so every exit cancels rather than merely stops.
 */
@Singleton
class WheelController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: SecurePreferenceStore,
    private val bridge: ShizukuInputBridge,
    private val overlays: OverlayController,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val processor = WheelTouchProcessor()

    private val _state = MutableStateFlow(WheelRuntimeState())
    val state: StateFlow<WheelRuntimeState> = _state.asStateFlow()

    /** A cheaper flow for the live vector, so 120 Hz movement does not rebuild the whole runtime state. */
    private val _liveFrame = MutableStateFlow<WheelTouchFrame?>(null)
    val liveFrame: StateFlow<WheelTouchFrame?> = _liveFrame.asStateFlow()

    /**
     * Emits when a pending first DOWN has been accepted or the hand-off has failed.
     *
     * The service keeps the wheel non-touchable while a gesture is in flight. If the finger lifts before
     * the hand-off finishes, the service must not restore touchability on that UP — the retry would then
     * hit the wheel again. It listens here and restores once no gesture remains.
     */
    private val _handoffFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val handoffFinished: SharedFlow<Unit> = _handoffFinished.asSharedFlow()

    private var gameOverride: WheelProfile? = null
    /** A quick/manual profile choice wins until the foreground game changes. Never persisted as runtime state. */
    private var manualOverride: WheelProfile? = null
    private var gamePackage: String? = null
    private var gameLabel: String = ""

    private var precision = false
    private var turbo = false

    private var processed = 0L
    private var injected = 0L
    private var refused = 0L
    private var delayedTaps = 0L
    private var lastProcessingMicros = 0
    private var averageProcessingMicros = 0L
    private var peakProcessingMicros = 0
    private var lastHandoffMicros = 0
    private var lastGestureAt = 0L
    private var lastError: String? = null

    /**
     * The processed DOWN held while the wheel window's `FLAG_NOT_TOUCHABLE` update reaches the input
     * dispatcher, plus the newest MOVE and any UP seen in that interval. At most one of each is ever held;
     * this is a one-time start-of-gesture cost, not a per-sample queue. A pending UP is completed after
     * the DOWN is accepted so a short tap is delayed, never dropped.
     */
    private var pendingDown: WheelTouchFrame? = null
    private var pendingMove: WheelTouchFrame? = null
    private var pendingUp: WheelTouchFrame? = null
    private var pendingStartJob: Job? = null

    /** `SystemClock.uptimeMillis` when the physical DOWN started the hand-off. */
    private var handoffStartedAt = 0L

    /**
     * True while a gesture is in flight or its first DOWN is still being handed to the game.
     *
     * The overlay is kept non-touchable for exactly this span. The service must not restore
     * touchability while it is true, or a retry would be delivered back to the wheel instead of the game.
     */
    fun isGestureActive(): Boolean = pendingDown != null || processor.isActive

    /** The uid the helper should address injected events to, or -1 when no game is known. */
    private var targetUid = -1

    /** Timestamp of the last live-visual publish, in elapsedRealtime milliseconds. */
    private var lastVisualPublish = 0L

    private val wheelConfig: WheelConfig
        get() = preferences.wheel.value.normalised()

    /**
     * The profile the processor actually uses: the bound or manually selected profile, with the quick
     * panel's precision/turbo scale folded in. Both quick controls are real multipliers, not labels.
     */
    private fun effectiveProfile(): WheelProfile {
        val base = manualOverride ?: gameOverride ?: wheelConfig.activeProfile
        val scaled = when {
            precision -> base.copy(sensitivity = base.sensitivity * wheelConfig.precisionMultiplier)
            turbo -> base.copy(sensitivity = base.sensitivity * wheelConfig.turboMultiplier)
            else -> base
        }
        return scaled.normalised()
    }

    init {
        scope.launch {
            preferences.wheel.collect { stored ->
                // A profile edit while the wheel is up must change the next sample, and the floating
                // button's visibility is part of what keeps the overlay service alive.
                // Re-resolve a live game binding too: changing an assignment on the settings screen must
                // affect the next sample without waiting for the foreground game to change away and back.
                val config = stored.normalised()
                gameOverride = config.profileFor(gamePackage)
                // Preserve a deliberate quick-panel selection, but resolve it back to the newly stored
                // object so edits to that profile affect the next sample. Deleting it ends the override.
                manualOverride = manualOverride?.let { selected ->
                    config.profiles.firstOrNull { it.id == selected.id }
                }
                refreshState()
                syncOverlayFlags()
            }
        }
        scope.launch {
            bridge.state.collect { injection ->
                val current = _state.value
                when {
                    current.arming && injection.canInject -> {
                        // Binding finished: the wheel may now be shown. Until this instant it was not on
                        // screen, so there was never a touchable window with nothing behind it.
                        enterGaming()
                    }
                    current.arming && injection.status.isTerminalFailure -> {
                        _state.value = _state.value.copy(
                            arming = false,
                            mode = WheelOperatingMode.DISABLED,
                            injector = injection,
                            lastError = injection.detail,
                        )
                        processor.reset()
                        syncOverlayFlags()
                    }
                    current.mode == WheelOperatingMode.GAMING && !injection.canInject -> {
                        // The helper died mid-gesture. Cancel the injected pointer and take the wheel down;
                        // leaving a touchable ring that injects nothing would eat the player's touches.
                        abortGesture("The injector connection was lost.")
                        _state.value = _state.value.copy(
                            mode = WheelOperatingMode.DISABLED,
                            injector = injection,
                            lastError = injection.detail,
                        )
                        syncOverlayFlags()
                    }
                    else -> {
                        _state.value = _state.value.copy(injector = injection)
                    }
                }
            }
        }
        refreshState()
    }

    // ---------------------------------------------------------------------------------- lifecycle

    /**
     * Arms the wheel. The window appears when the backend reports ready, not before.
     *
     * Returns false only when the backend is already known to be unusable; a bind in progress returns
     * true and leaves [WheelRuntimeState.arming] set.
     */
    fun engage(): Boolean {
        // The risk gate is here, at the one runtime entry point for both the screen and the floating
        // handle. A caller that has not accepted the warning cannot arm the helper, and nothing is bound
        // until it does — the warning is not a decorative banner beside a switch that already works.
        if (!wheelConfig.riskAcknowledged) {
            _state.value = _state.value.copy(
                consentRequired = true,
                // A floating-button tap can happen while the settings Activity is not visible. Opening
                // its panel gives that path an immediate explanation and a route to the full warning;
                // otherwise the button would appear to ignore the tap.
                quickPanelOpen = true,
                arming = false,
                mode = WheelOperatingMode.DISABLED,
                lastError = null,
            )
            syncOverlayFlags()
            return false
        }
        if (_state.value.mode == WheelOperatingMode.GAMING && bridge.state.value.canInject) return true
        if (_state.value.arming) return true
        lastError = null
        _state.value = _state.value.copy(
            arming = true,
            mode = WheelOperatingMode.DISABLED,
            lastError = null,
        )
        val injection = bridge.arm()
        refreshState(injection)
        if (injection.canInject) {
            enterGaming()
            return true
        }
        if (injection.status.isTerminalFailure) {
            _state.value = _state.value.copy(arming = false, lastError = injection.detail)
            return false
        }
        // Binding is asynchronous. The collector above completes the transition.
        syncOverlayFlags()
        return true
    }

    /** The floating button and the quick panel's power action. */
    fun toggle(): Boolean =
        if (isOn()) disengage().let { false } else engage()

    /** True when the wheel is armed, binding, or in a touch-capturing mode. */
    fun isOn(): Boolean = _state.value.mode != WheelOperatingMode.DISABLED || _state.value.arming

    /**
     * Records the one-time injection warning and enables the wheel.
     *
     * Persisted so the warning is not repeated on every arm. It is deliberately not persisted as runtime
     * state: a restart still starts DISABLED, the acknowledgement only removes the gate.
     */
    fun acceptRisk() {
        preferences.updateWheel { it.copy(riskAcknowledged = true) }
        _state.value = _state.value.copy(consentRequired = false, quickPanelOpen = false)
        engage()
    }

    /** Dismisses the warning without accepting it. The wheel stays disabled. */
    fun dismissRiskPrompt() {
        _state.value = _state.value.copy(consentRequired = false, quickPanelOpen = false)
    }

    /**
     * Emergency deactivation and the normal "off" path. Idempotent.
     *
     * Releases the injected pointer before the helper goes away, so the game receives an ACTION_CANCEL
     * rather than a finger that never lifts. [reason] keeps a failure visible on the floating handle after
     * the wheel has been taken down; passing null is the ordinary "switched off" path and clears it.
     */
    fun disengage(reason: String? = null) {
        abortGesture(reason ?: "Wheel switched off.")
        bridge.disarm()
        lastError = reason
        _state.value = _state.value.copy(
            mode = WheelOperatingMode.DISABLED,
            arming = false,
            calibration = null,
            consentRequired = false,
            lastError = reason,
        )
        _liveFrame.value = null
        syncOverlayFlags()
    }

    /**
     * Switches modes from the screen or quick panel.
     *
     * GAMING goes through [engage] and may stay in [WheelOperatingMode.DISABLED] while it binds.
     * POSITIONING and CALIBRATION need no privileged helper: they consume touches by design and inject
     * nothing, so they are the safe way to place and test a wheel before trusting it in a game.
     */
    fun setMode(mode: WheelOperatingMode) {
        when (mode) {
            WheelOperatingMode.DISABLED -> disengage()
            WheelOperatingMode.GAMING -> engage()
            WheelOperatingMode.POSITIONING, WheelOperatingMode.CALIBRATION -> {
                abortGesture("Mode changed to ${mode.label}.")
                // The bridge can stay bound; switching back to GAMING is then immediate. It cannot inject
                // anything while the mode is not GAMING, because [onTouch] never calls it.
                _state.value = _state.value.copy(
                    mode = mode,
                    arming = false,
                    calibration = null,
                    consentRequired = false,
                    lastError = null,
                )
                _liveFrame.value = null
                refreshState()
                syncOverlayFlags()
            }
        }
    }

    /** Called by the service when the foreground game changes. */
    fun setGame(packageName: String?, label: String): Boolean {
        if (packageName == gamePackage && label == gameLabel) return false
        // A gesture must not straddle a target change: the helper would apply the new uid to the next
        // event, and the game that received the DOWN might never receive its UP. Release first, retarget
        // second. This is also why the target is set here rather than lazily in `send`.
        if (processor.isActive || pendingDown != null || pendingStartJob != null) {
            abortGesture("The foreground app changed.")
        }
        // A manual quick switch is scoped to the game/session in which it was made. Carrying it into the
        // next game would silently defeat that game's own binding.
        manualOverride = null
        gamePackage = packageName
        gameLabel = label
        val config = wheelConfig
        val bound = config.profileFor(packageName)
        gameOverride = bound
        targetUid = resolveTargetUid(packageName)
        bridge.setTargetUid(targetUid)
        refreshState()
        return true
    }

    /**
     * The game's uid, for the API 33+ targeted injector. -1 when there is no game or the package is not
     * visible to this user; the helper then injects untargeted, which is the fallback it documents.
     */
    private fun resolveTargetUid(packageName: String?): Int {
        val name = packageName?.takeIf { it.isNotBlank() } ?: return -1
        return runCatching { context.packageManager.getApplicationInfo(name, 0).uid }
            .getOrDefault(-1)
    }

    // ------------------------------------------------------------------------------------- touch

    /**
     * One pointer sample from the overlay, in absolute screen coordinates.
     *
     * The return value is whether the overlay should consume the touch. Callers already know that a
     * touchable window consumes it; the boolean exists so the Compose layer can make the positioning
     * drag and the calibration read-out mutually exclusive without reaching into this class's state.
     *
     * After a GAMING DOWN, only that first injection waits for the overlay hand-off; subsequent samples
     * are synchronous and unbuffered. The wait is adaptive, measured in [WheelDiagnostics.handoffMicros]
     * and never a fixed drop window. UI state is published at most every [VISUAL_PUBLISH_INTERVAL_MS] so a
     * 120 Hz gesture does not rebuild the overlay canvas every frame; that throttle never delays input.
     *
     * [originX]/[originY] are the injected stream's neutral point, normally the wheel's centre. They
     * default to the physical sample so callers without a window (and the original tests) keep the
     * relative-to-the-finger behaviour.
     */
    fun onTouch(
        action: Int,
        x: Float,
        y: Float,
        eventTime: Long,
        downTime: Long = eventTime,
        originX: Float = x,
        originY: Float = y,
    ): Boolean {
        val runtime = _state.value
        val profile = effectiveProfile()
        when (runtime.mode) {
            WheelOperatingMode.DISABLED -> return false
            WheelOperatingMode.POSITIONING -> return true
            WheelOperatingMode.CALIBRATION -> {
                val frame = processAndMaybeInject(
                    profile, action, x, y, eventTime, downTime, originX, originY, inject = false,
                )
                if (frame != null) {
                    _state.value = _state.value.copy(calibration = frame)
                    _liveFrame.value = frame
                }
                return true
            }
            WheelOperatingMode.GAMING -> {
                if (!bridge.state.value.canInject) return false
                return processAndMaybeInject(
                    profile, action, x, y, eventTime, downTime, originX, originY, inject = true,
                ) != null
            }
        }
    }

    private fun processAndMaybeInject(
        profile: WheelProfile,
        action: Int,
        x: Float,
        y: Float,
        eventTime: Long,
        downTime: Long,
        originX: Float,
        originY: Float,
        inject: Boolean,
    ): WheelTouchFrame? {
        // GAMING only. In calibration the same maths runs and nothing is deferred, because there is no
        // overlay hand-off to wait for.
        if (inject) {
            if (action == MotionEvent.ACTION_DOWN) {
                return beginDeferredGesture(profile, x, y, eventTime, downTime, originX, originY)
            }
            if (pendingDown != null) {
                return continueDeferredGesture(profile, action, x, y, eventTime)
            }
        }

        val frame = when (action) {
            MotionEvent.ACTION_DOWN -> {
                // A second DOWN without an UP is a lifecycle bug. Release the old pointer first.
                if (processor.isActive) abortGesture("Replaced before its UP.")
                processor.down(profile, x, y, eventTime, downTime, originX, originY)
            }
            MotionEvent.ACTION_MOVE -> processor.move(profile, x, y, eventTime) ?: return null
            MotionEvent.ACTION_UP -> processor.up(profile, x, y, eventTime) ?: return null
            MotionEvent.ACTION_CANCEL -> processor.cancel(eventTime) ?: return null
            else -> return null
        }

        // Calibration follows the same maths but never touches the binder. That is the point of it: the
        // user sees what the wheel would do before enabling it in a game.
        if (!inject) {
            publishVisual(frame)
            return frame
        }

        injectFrame(frame)
        return frame
    }

    /**
     * Starts a gaming gesture and hands its DOWN to the dispatcher.
     *
     * The service sets `FLAG_NOT_TOUCHABLE` before calling this. On Android 13+ with a resolved game uid
     * the targeted attempt is made immediately: if the flag has not reached `InputDispatcher` yet, the
     * targeted call is refused rather than delivered back to the overlay, so a retry is safe. On the
     * untargeted path the first attempt waits one conservative frame, because an untargeted event could
     * land on the overlay.
     *
     * Only the newest MOVE and at most one UP are retained while the DOWN is pending. A short tap is
     * completed after the hand-off instead of being dropped.
     */
    private fun beginDeferredGesture(
        profile: WheelProfile,
        x: Float,
        y: Float,
        eventTime: Long,
        downTime: Long,
        originX: Float,
        originY: Float,
    ): WheelTouchFrame {
        if (pendingDown != null || processor.isActive) {
            // `abortGesture` knows a pending DOWN was never injected and therefore sends no CANCEL.
            abortGesture("Replaced before its UP.")
        }
        val frame = processor.down(profile, x, y, eventTime, downTime, originX, originY)
        pendingDown = frame
        pendingMove = null
        pendingUp = null
        handoffStartedAt = android.os.SystemClock.uptimeMillis()
        publishVisual(frame)
        if (bridge.state.value.targeted) {
            if (tryPendingStart()) return frame
            scheduleHandoffAttempt(HANDOFF_RETRY_MILLIS)
        } else {
            scheduleHandoffAttempt(HANDOFF_SETTLE_MILLIS)
        }
        return frame
    }

    /** A MOVE/UP/CANCEL that arrived before the held DOWN was accepted by the dispatcher. */
    private fun continueDeferredGesture(
        profile: WheelProfile,
        action: Int,
        x: Float,
        y: Float,
        eventTime: Long,
    ): WheelTouchFrame? {
        return when (action) {
            MotionEvent.ACTION_MOVE -> {
                val frame = processor.move(profile, x, y, eventTime) ?: return null
                pendingMove = frame
                publishVisual(frame)
                frame
            }
            MotionEvent.ACTION_UP -> {
                val frame = processor.up(profile, x, y, eventTime) ?: return null
                pendingUp = frame
                delayedTaps++
                publishVisual(frame)
                publishDiagnostics()
                frame
            }
            MotionEvent.ACTION_CANCEL -> {
                val frame = processor.cancel(eventTime) ?: return null
                // A cancel is a release of the physical gesture; no injected pointer exists yet, so
                // there is nothing to send. Drop the pending start with the physical stream.
                cancelPendingStart()
                publishVisual(frame)
                frame
            }
            else -> null
        }
    }

    /**
     * One hand-off attempt. Returns true when the DOWN was accepted and any pending MOVE/UP were sent.
     *
     * A refusal here is expected while the window flag is in flight, so [injectFrame] is asked not to
     * abort the gesture. The caller decides whether to retry or fail.
     */
    private fun tryPendingStart(): Boolean {
        val down = pendingDown ?: return true
        if (!bridge.state.value.canInject) {
            lastError = "The injector stopped before the gesture could be handed over."
            return false
        }
        if (!injectFrame(down, publishFrame = false, abortOnFailure = false)) return false
        pendingDown = null
        pendingStartJob = null
        lastHandoffMicros = (android.os.SystemClock.uptimeMillis() - handoffStartedAt)
            .coerceAtLeast(0L)
            .toInt()
        val move = pendingMove
        pendingMove = null
        if (move != null && processor.isActive) {
            injectFrame(move, publishFrame = false)
        }
        val up = pendingUp
        pendingUp = null
        if (up != null) {
            injectFrame(up, publishFrame = false)
        }
        // The window may become touchable again only if no gesture remains. If the finger is still down
        // the service keeps waiting; its UP path restores touchability itself.
        _handoffFinished.tryEmit(Unit)
        return true
    }

    /** Schedules another hand-off attempt, or takes the wheel down when the deadline has passed. */
    private fun scheduleHandoffAttempt(delayMillis: Long) {
        pendingStartJob?.cancel()
        pendingStartJob = scope.launch {
            if (delayMillis > 0L) delay(delayMillis)
            if (pendingDown == null) return@launch
            if (tryPendingStart()) return@launch
            val elapsed = android.os.SystemClock.uptimeMillis() - handoffStartedAt
            if (elapsed >= HANDOFF_TIMEOUT_MILLIS) {
                failPendingStart(
                    lastError ?: "The wheel could not hand the gesture to the game.",
                )
            } else {
                pendingStartJob = null
                scheduleHandoffAttempt(HANDOFF_RETRY_MILLIS)
            }
        }
    }

    /**
     * Ends a hand-off that never reached the game. The wheel is taken down so the touchable ring cannot
     * keep eating the game's controls while injecting nothing.
     */
    private fun failPendingStart(reason: String) {
        cancelPendingStart()
        processor.reset()
        _liveFrame.value = null
        disengage(reason)
        // disengage() hides the wheel, but the window may still exist for a frame. Let the service put
        // it back into touch mode so a subsequent raise cannot inherit the non-touchable flag.
        _handoffFinished.tryEmit(Unit)
    }

    /** Cancels the held start without sending anything. The caller decides whether a release is needed. */
    private fun cancelPendingStart() {
        pendingStartJob?.cancel()
        pendingStartJob = null
        pendingDown = null
        pendingMove = null
        pendingUp = null
    }

    /**
     * Sends one processed frame and records what was actually measured around the binder call.
     *
     * [publishFrame] is false for deferred frames whose visual was already published when they arrived;
     * re-publishing the old DOWN after the settle would make the drawn vector jump backwards.
     * [abortOnFailure] is false during the hand-off, where a refusal is the expected "the flag has not
     * landed yet" result and the caller retries instead of tearing the gesture down.
     */
    private fun injectFrame(
        frame: WheelTouchFrame,
        publishFrame: Boolean = true,
        abortOnFailure: Boolean = true,
    ): Boolean {
        val started = System.nanoTime()
        val accepted = bridge.send(frame)
        val elapsedMicros = ((System.nanoTime() - started) / 1_000L).coerceAtLeast(0L)
        processed++
        if (accepted) injected++ else refused++
        lastProcessingMicros = elapsedMicros.toInt()
        peakProcessingMicros = max(peakProcessingMicros, lastProcessingMicros)
        averageProcessingMicros = if (processed <= 1L) {
            elapsedMicros
        } else {
            averageProcessingMicros + (elapsedMicros - averageProcessingMicros) / 8L
        }
        lastGestureAt = android.os.SystemClock.elapsedRealtime()
        if (publishFrame) publishVisual(frame)
        publishDiagnostics()
        if (!accepted) {
            lastError = bridge.state.value.lastError
                ?: "The privileged dispatcher refused a ${frame.action.name} event."
            // A refused DOWN/MOVE means the stream is no longer trustworthy. Cancel everything rather
            // than letting the next move build on a pointer the game may not hold. The hand-off caller
            // opts out: a refusal there is retried until the window flag lands or the deadline expires.
            if (abortOnFailure && frame.action != WheelAction.UP && frame.action != WheelAction.CANCEL) {
                abortGesture(lastError ?: "Injection failed.")
            }
            refreshState()
        }
        return accepted
    }

    /** Publishes the vector for the drawn wheel at a bounded rate. Injection has already happened. */
    private fun publishVisual(frame: WheelTouchFrame) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastVisualPublish >= VISUAL_PUBLISH_INTERVAL_MS ||
            frame.action == WheelAction.DOWN ||
            frame.action == WheelAction.UP ||
            frame.action == WheelAction.CANCEL
        ) {
            lastVisualPublish = now
            _liveFrame.value = frame
        }
    }

    /**
     * Cancels an in-flight gesture and releases the helper's pointer. Safe to call at any time.
     *
     * The processor emits the CANCEL sample, so the helper receives a valid platform cancel with the
     * correct down time. If the binder is already gone, [ShizukuInputBridge.releaseAll] is still called
     * so a reconnected helper starts with no stale pointer.
     */
    fun abortGesture(reason: String) {
        val pendingStart = pendingDown != null
        cancelPendingStart()
        if (processor.isActive) {
            // A pending DOWN was never injected and the helper holds nothing for it: sending a CANCEL
            // would be a refusal against a pointer that never existed.
            if (!pendingStart) {
                val frame = processor.cancel(android.os.SystemClock.uptimeMillis())
                if (frame != null && bridge.state.value.bound) {
                    bridge.send(frame)
                }
            }
            processor.reset()
            lastError = reason
        }
        bridge.releaseAll()
        _liveFrame.value = null
        if (_state.value.calibration != null) {
            _state.value = _state.value.copy(calibration = null)
        }
    }

    /** Screen off and service teardown: no pointer may survive. */
    fun releaseAll(reason: String) {
        abortGesture(reason)
        publishDiagnostics()
    }

    // ------------------------------------------------------------------------------- quick panel

    fun openQuickPanel() {
        _state.value = _state.value.copy(quickPanelOpen = true)
    }

    fun closeQuickPanel() {
        _state.value = _state.value.copy(quickPanelOpen = false)
    }

    fun toggleQuickPanel() {
        _state.value = _state.value.copy(quickPanelOpen = !_state.value.quickPanelOpen)
    }

    /**
     * Precision and turbo are runtime modifiers, not profile edits.
     *
     * They are separate because a player wants precision for one shot, not to rewrite the profile they
     * tuned. Their multipliers are visible in the quick panel and in the diagnostics line, and both are
     * folded into the profile handed to the processor, so they affect the actual injected sample.
     */
    fun setPrecision(on: Boolean) {
        precision = on
        if (on) turbo = false
        refreshState()
    }

    fun setTurbo(on: Boolean) {
        turbo = on
        if (on) precision = false
        refreshState()
    }

    fun toggleLock() {
        val profile = effectiveProfile()
        updateProfile { it.copy(locked = !profile.locked) }
    }

    /**
     * Releases the position lock when the user starts dragging the wheel in positioning mode.
     *
     * An explicit placement gesture is a clearer statement of intent than a lock set from a settings
     * screen possibly days earlier. Keeping the lock immune to dragging is how a wheel placed over a
     * game's joystick becomes impossible to move away from it.
     */
    fun unlockPosition() {
        if (effectiveProfile().locked) {
            updateProfile { it.copy(locked = false) }
        }
    }

    /** A one-tap sensitivity change from the quick panel, persisted to the effective profile. */
    fun nudgeSensitivity(delta: Float) {
        val profile = effectiveProfile()
        updateProfile {
            it.copy(sensitivity = (profile.sensitivity + delta).coerceIn(
                WheelProfile.MIN_SENSITIVITY,
                WheelProfile.MAX_SENSITIVITY,
            ))
        }
    }

    // ---------------------------------------------------------------------------------- profiles

    /**
     * Writes a change to the profile the wheel is currently using.
     *
     * If a per-game binding is in force, that profile is edited; otherwise the active one. The active id
     * is left alone, so editing a bound profile from inside a game does not silently switch the library's
     * default when the game closes.
     */
    fun updateProfile(transform: (WheelProfile) -> WheelProfile) {
        val target = (manualOverride ?: gameOverride ?: wheelConfig.activeProfile).id
        preferences.updateWheel { config ->
            val existing = config.profiles.firstOrNull { it.id == target }
            if (existing == null) config else config.withProfile(transform(existing))
        }
        refreshState()
    }

    fun selectProfile(id: String) {
        val selected = wheelConfig.profiles.firstOrNull { it.id == id } ?: return
        manualOverride = selected
        preferences.updateWheel { it.withActiveProfile(id) }
        refreshState()
        selectManual(selected)
    }

    private fun selectManual(profile: WheelProfile) {
        // The selection itself already went through the store; this only makes the controller's effective
        // profile agree before the store's StateFlow emission arrives.
        manualOverride = profile
        _state.value = _state.value.copy(effectiveProfile = profile.normalised())
    }

    /**
     * Saves a dragged wheel position. The service passes the window's centre as a fraction of the
     * usable box, which is how the position survives every rotation without a pixel being carried.
     */
    fun updatePosition(xFraction: Float, yFraction: Float) {
        updateProfile {
            it.copy(
                xFraction = xFraction.coerceIn(0f, 1f),
                yFraction = yFraction.coerceIn(0f, 1f),
            )
        }
    }

    fun setButtonVisible(visible: Boolean) {
        preferences.updateWheel { it.copy(button = it.button.copy(visible = visible)) }
        syncOverlayFlags()
    }

    fun updateButton(transform: (com.gamecore.core.model.WheelButtonConfig) -> com.gamecore.core.model.WheelButtonConfig) {
        preferences.updateWheel { it.copy(button = transform(it.button)) }
    }

    fun updateConfig(transform: (WheelConfig) -> WheelConfig) {
        preferences.updateWheel(transform)
    }

    /** The diagnostics text the advanced section shows. Includes the remote helper's own counters. */
    fun readDiagnostics(): String = bridge.readDiagnostics()

    fun refreshBackend() {
        bridge.refresh()
        refreshState()
    }

    // ------------------------------------------------------------------------------------ state

    private fun enterGaming() {
        _state.value = _state.value.copy(
            mode = WheelOperatingMode.GAMING,
            arming = false,
            consentRequired = false,
            injector = bridge.state.value,
            lastError = null,
        )
        refreshState()
        syncOverlayFlags()
    }

    private fun refreshState(injection: InjectionState = bridge.state.value) {
        _state.value = _state.value.copy(
            effectiveProfile = effectiveProfile(),
            activeProfileId = wheelConfig.activeProfileId,
            perGameProfile = manualOverride == null && gameOverride != null,
            precision = precision,
            turbo = turbo,
            injector = injection,
            diagnostics = diagnostics(),
            lastError = lastError,
            gamePackage = gamePackage,
            gameLabel = gameLabel,
        )
    }

    /** Diagnostics are written by the touch path without allocating the whole state for every sample. */
    private fun publishDiagnostics() {
        _state.value = _state.value.copy(diagnostics = diagnostics())
    }

    private fun diagnostics(): WheelDiagnostics = WheelDiagnostics(
        processedEvents = processed,
        injectedEvents = injected,
        refusedEvents = refused,
        delayedTaps = delayedTaps,
        handoffMicros = lastHandoffMicros,
        lastProcessingMicros = lastProcessingMicros,
        averageProcessingMicros = averageProcessingMicros.toInt(),
        peakProcessingMicros = peakProcessingMicros,
        binderMicros = bridge.state.value.binderMicros,
        pointerActive = processor.isActive,
        lastFrame = _liveFrame.value,
        lastError = lastError,
        lastGestureAtElapsed = lastGestureAt,
    )

    /**
     * Keeps the overlay request in step with the wheel's two windows.
     *
     * The button is persisted, so it is restored after a restart and remains the emergency handle. The
     * stick is runtime-only and is cleared here on every process start — [WheelController]'s initial state
     * is DISABLED — which is the "never auto-enable injection" rule enforced at its source rather than by
     * remembering to reset it somewhere.
     */
    private fun syncOverlayFlags() {
        overlays.setWheelButton(wheelConfig.button.visible)
        overlays.setWheelStick(_state.value.mode != WheelOperatingMode.DISABLED)
    }

    private companion object {
        /** 120 Hz is ~8 ms; publishing the visual at ~60 Hz halves the Compose work without touching input. */
        const val VISUAL_PUBLISH_INTERVAL_MS = 16L

        /**
         * The first hand-off attempt on the untargeted path.
         *
         * `updateViewLayout` is asynchronous. A targeted event that still sees the touchable wheel is refused,
         * which is a safe result to retry; an untargeted event would be delivered back to the overlay, so it
         * must wait for the flag to land. Two 60 Hz frames is the conservative bound, paid once per gesture
         * and measured in [WheelDiagnostics.handoffMicros].
         */
        const val HANDOFF_SETTLE_MILLIS = 32L

        /** The gap between targeted hand-off attempts while the window flag is in flight. */
        const val HANDOFF_RETRY_MILLIS = 6L

        /**
         * How long the wheel waits for a hand-off before taking itself down.
         *
         * This is not a styling delay: a DOWN that never reaches the game means the touchable wheel is
         * consuming the player's controls while injecting nothing. Failing safe is better than leaving the
         * game's joystick dead.
         */
        const val HANDOFF_TIMEOUT_MILLIS = 150L
    }
}

/** True for a backend state that will not become ready without the user doing something. */
private val InjectionBackendStatus.isTerminalFailure: Boolean
    get() = this == InjectionBackendStatus.SHIZUKU_MISSING ||
        this == InjectionBackendStatus.SHIZUKU_STOPPED ||
        this == InjectionBackendStatus.PERMISSION_REQUIRED ||
        this == InjectionBackendStatus.UNSUPPORTED ||
        this == InjectionBackendStatus.ERROR ||
        this == InjectionBackendStatus.CONNECTION_LOST
