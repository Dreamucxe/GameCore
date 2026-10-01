package com.gamecore.domain.trigger

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.gamecore.MainActivity
import com.gamecore.core.common.ApplicationScope
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.DisplaySize
import com.gamecore.core.model.FractionPoint
import com.gamecore.core.model.QuickTriggerAction
import com.gamecore.core.model.QuickTriggerMethod
import com.gamecore.core.model.QuickTriggerSettings
import com.gamecore.core.model.TriggerAvailability
import com.gamecore.core.model.VolumeTriggerBinding
import com.gamecore.core.model.VolumeTriggerButton
import com.gamecore.core.model.VolumeTriggerConfig
import com.gamecore.core.model.VolumeTriggerPressMode
import com.gamecore.core.permissions.PermissionChecker
import com.gamecore.core.system.DisplayReader
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.GameProfileRepository
import com.gamecore.domain.display.DisplaySizeController
import com.gamecore.domain.gaming.GamingCoordinator
import com.gamecore.domain.overlay.OverlayController
import com.gamecore.service.QuickTriggerService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Quick Trigger: one place that decides whether a shortcut can fire, and what happens when it does.
 *
 * Three callers feed it. [MainActivity] forwards its key events, which is the only path an ordinary app
 * has to a hardware button and works only while a GameCore window has focus. The optional accessibility
 * service forwards the same events without that limit, if the user has switched it on. [QuickTriggerService]
 * forwards accelerometer samples for the shake method. All three end at [fire], and [fire] opens the panel
 * that already exists rather than a second one.
 *
 * Nothing here reflects into a system service, reads a hidden field or shells out. If a method cannot work
 * on this device, [availability] says so with the reason, and the settings screen does not offer it.
 *
 * §3.7 adds a second, narrower claim on the volume keys: a game's own profile may bind one of them to a
 * point on the screen (see [onPointTriggerKey]). That path shells out — through [PointTapController] — but
 * only while a game whose profile asked for it is the app being tracked, and it is the only thing in this
 * file that consumes a key by default.
 */
@Singleton
class QuickTriggerCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: SecurePreferenceStore,
    private val permissions: PermissionChecker,
    private val overlays: OverlayController,
    private val gaming: GamingCoordinator,
    private val profiles: GameProfileRepository,
    private val displaySizes: DisplaySizeController,
    private val displays: DisplayReader,
    private val pointTaps: PointTapController,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val settings: QuickTriggerSettings get() = preferences.settings.value.quickTrigger

    private var lastPressMillis = 0L
    private var lastPressCode = 0
    private var otherVolumeDownAt = 0L
    private var lastFiredMillis = 0L

    // Shake state: two sharp accelerations inside the same window, same rule as a double press.
    private var lastShakeMillis = 0L
    private var shakeCount = 0

    /** Every method, with whether it can run right now and why not if it cannot. */
    fun availability(): List<TriggerAvailability> = QuickTriggerMethod.entries.map { method ->
        when (method) {
            QuickTriggerMethod.VOLUME_UP_DOUBLE,
            QuickTriggerMethod.VOLUME_DOWN_DOUBLE,
            QuickTriggerMethod.VOLUME_BOTH,
            -> TriggerAvailability(
                method = method,
                status = TriggerAvailability.Status.AVAILABLE,
                detail = if (isAccessibilityEnabled()) {
                    "Works anywhere: GameCore's accessibility service is on and is receiving key events."
                } else {
                    "Works while GameCore is the app on screen. Android sends hardware keys to the " +
                        "focused window only, and no permission changes that for an ordinary app."
                },
            )

            QuickTriggerMethod.SHAKE -> when {
                !hasAccelerometer() -> TriggerAvailability(
                    method = method,
                    status = TriggerAvailability.Status.UNSUPPORTED,
                    detail = "This device reports no accelerometer, so a shake cannot be detected.",
                )

                else -> TriggerAvailability(
                    method = method,
                    status = TriggerAvailability.Status.AVAILABLE,
                    detail = "Runs a foreground service while armed, so it works from inside other " +
                        "apps. The notification is Android's requirement, not a choice.",
                )
            }

            QuickTriggerMethod.QUICK_TILE -> TriggerAvailability(
                method = method,
                status = TriggerAvailability.Status.NEEDS_SETUP,
                detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    "GameCore can ask Android to offer the tile. Android decides whether to show " +
                        "the prompt, and the tile can always be added by hand from the Quick " +
                        "Settings edit screen."
                } else {
                    "Pull down Quick Settings, open its edit screen and drag the GameCore tile into " +
                        "the active row. Android before 13 has no way for an app to ask on your behalf."
                },
            )

            QuickTriggerMethod.FLOATING_BUTTON -> if (permissions.hasOverlayPermission()) {
                TriggerAvailability(
                    method = method,
                    status = TriggerAvailability.Status.AVAILABLE,
                    detail = "The floating button is already GameCore's shortcut to this panel.",
                )
            } else {
                TriggerAvailability(
                    method = method,
                    status = TriggerAvailability.Status.NEEDS_PERMISSION,
                    detail = "Needs the display-over-other-apps permission before a button can be drawn.",
                )
            }
        }
    }

    fun availabilityOf(method: QuickTriggerMethod): TriggerAvailability =
        availability().first { it.method == method }

    /**
     * The action the Quick Settings tile performs, for its own subtitle.
     *
     * A separate accessor rather than exposing [settings], because the tile is the one caller that lives
     * in another process's shade and has no business reading the rest of the configuration.
     */
    fun tileAction(): QuickTriggerAction = settings.action

    /** Whether Android can be asked to offer the Quick Settings tile, rather than only told about it. */
    fun canRequestTile(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private fun hasAccelerometer(): Boolean = try {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    } catch (error: Throwable) {
        false
    }

    /**
     * Whether GameCore's own accessibility service is switched on.
     *
     * Read from `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, which is the documented way to ask, and
     * matched against this app's own component only. GameCore never reads which *other* services the user
     * has enabled beyond the one string split it takes to find its own.
     */
    fun isAccessibilityEnabled(): Boolean = try {
        val expected = ComponentName(context, TRIGGER_ACCESSIBILITY_CLASS).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        var found = false
        while (splitter.hasNext()) {
            val entry = splitter.next()
            if (entry.equals(expected, ignoreCase = true)) found = true
        }
        found
    } catch (error: Throwable) {
        false
    }

    /** The system screen where the accessibility service is turned on, or null if it does not resolve. */
    fun accessibilitySettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ------------------------------------------------------------------------------- key detection

    /**
     * A hardware key, from the activity or from the accessibility service.
     *
     * Returns true when the caller should swallow the key. Two separate things can claim it, and they are
     * asked in order of how specific the claim is:
     *
     *  1. §3.7's point trigger, which is one key bound to one spot in one game. It swallows the key it owns —
     *     see [onPointTriggerKey] for why that one is not pass-through.
     *  2. The Quick Trigger method, which is app-wide. It almost never swallows anything:
     *     [QuickTriggerSettings.passThroughKeys] defaults to on, because a volume key that stops changing the
     *     volume is a bug from the user's side of the screen even when it was configured deliberately.
     *
     * For the Quick Trigger only `ACTION_DOWN` is looked at, and repeats are ignored, so holding a key to
     * scroll the volume does not accumulate presses. The point trigger needs both edges and reads them
     * itself, which is why it is consulted before that guard rather than after it.
     */
    fun onKeyEvent(keyCode: Int, action: Int, repeatCount: Int, eventTimeMillis: Long): Boolean {
        if (onPointTriggerKey(keyCode, action, repeatCount, eventTimeMillis)) return true

        val config = settings
        if (!config.enabled || !config.method.isKeyBased) return false
        if (action != KeyEvent.ACTION_DOWN || repeatCount != 0) return false

        val fired = when (config.method) {
            QuickTriggerMethod.VOLUME_UP_DOUBLE ->
                doublePress(KeyEvent.KEYCODE_VOLUME_UP, keyCode, eventTimeMillis, config.windowMillis)

            QuickTriggerMethod.VOLUME_DOWN_DOUBLE ->
                doublePress(KeyEvent.KEYCODE_VOLUME_DOWN, keyCode, eventTimeMillis, config.windowMillis)

            QuickTriggerMethod.VOLUME_BOTH ->
                bothVolumeKeys(keyCode, eventTimeMillis, config.windowMillis)

            else -> false
        }
        if (!fired) return false
        fire(config.action)
        return !config.passThroughKeys
    }

    private fun doublePress(wanted: Int, keyCode: Int, nowMillis: Long, windowMillis: Long): Boolean {
        if (keyCode != wanted) {
            // A different key in the middle breaks the sequence, so up-down-up is not a double tap.
            lastPressCode = keyCode
            lastPressMillis = nowMillis
            return false
        }
        val isSecond = lastPressCode == wanted && nowMillis - lastPressMillis in 0..windowMillis
        lastPressCode = keyCode
        lastPressMillis = nowMillis
        if (!isSecond) return false
        // Consumed: the next press starts a fresh pair rather than completing a third.
        lastPressCode = 0
        return true
    }

    /**
     * Both volume keys inside the window.
     *
     * Android delivers a chord as two separate key events a few milliseconds apart rather than as one
     * event with both codes, so "together" has to mean "within the window", and the window is the user's.
     */
    private fun bothVolumeKeys(keyCode: Int, nowMillis: Long, windowMillis: Long): Boolean {
        val window = minOf(windowMillis, CHORD_WINDOW_MAX)
        val other = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> KeyEvent.KEYCODE_VOLUME_UP
            KeyEvent.KEYCODE_VOLUME_UP -> KeyEvent.KEYCODE_VOLUME_DOWN
            else -> {
                // Any other key breaks a half-formed chord, same as it breaks a double tap.
                lastPressCode = keyCode
                lastPressMillis = nowMillis
                return false
            }
        }
        // Order-independent: whichever volume key lands first opens the chord, the other one closes it.
        val paired = lastPressCode == other && nowMillis - lastPressMillis in 0..window
        if (paired) {
            // Consumed: the next press starts a fresh chord rather than pairing with this one again.
            lastPressCode = 0
            otherVolumeDownAt = 0L
            return true
        }
        lastPressCode = keyCode
        lastPressMillis = nowMillis
        otherVolumeDownAt = nowMillis
        return false
    }

    // ------------------------------------------------------------------- volume point trigger (§3.7)

    /** The press thresholds. Not user-configurable: the classifier's defaults are the platform's own. */
    private val pressConfig = VolumePressConfig()

    /**
     * Guards [pressStates] and [tickJobs].
     *
     * Key events arrive on the main thread and the scheduled ticks resolve on [scope]'s dispatcher, so the
     * two press machines are touched from two threads. A lock rather than confining everything to one
     * thread, because the key path must not wait for a dispatch and a `Handler` hop would add latency to the
     * one path where latency is the whole point.
     */
    private val triggerLock = Any()

    /** One [VolumePressState] per key. The classifier shares nothing between buttons, so neither does this. */
    private val pressStates = mutableMapOf<VolumeTriggerButton, VolumePressState>()

    /** The pending [VolumePressClassifier.tick] per key: at most one, replaced whenever the state moves on. */
    private val tickJobs = mutableMapOf<VolumeTriggerButton, Job>()

    /**
     * What the key path is allowed to fire, or null when nothing is.
     *
     * Volatile because it is written from the game-change collector and read on every key event from the main
     * thread. Null is the resting state and the safe one: every reason the feature cannot fire ends with this
     * being null, and the key path's first act is to check it.
     */
    @Volatile
    private var armed: ArmedPointTrigger? = null

    /**
     * The resolved trigger for the game being tracked.
     *
     * Resolved once per game rather than per key press, because both halves of it are slow: the profile is a
     * database read and the size is a shell call. The cost of that is stated plainly — the trigger arms a
     * moment after the game is detected rather than the instant it appears — and it is the right trade, since
     * a key event has milliseconds and neither read fits in them.
     */
    private data class ArmedPointTrigger(
        val packageName: String,
        val config: VolumeTriggerConfig,
        /** The panel as `wm size` reports it, which is always its **natural** orientation. */
        val naturalSize: DisplaySize,
    )

    init {
        scope.launch {
            gaming.gaming
                .map { it.playing }
                .distinctUntilChanged()
                .collect { packageName -> rearm(packageName) }
        }
    }

    /**
     * Re-reads [armed] for the game now being tracked, and disarms when there is none.
     *
     * A profile with the switch on but no point placed arms nothing, and neither does a device that cannot
     * report its own display size: without a real panel there is no pixel to tap, and a key consumed for a
     * tap that cannot be injected is a volume key that stopped working for nothing. Staying disarmed is the
     * honest degradation, and the editor's own card is where the user is told Shizuku is missing.
     */
    private suspend fun rearm(packageName: String?) {
        clearPressStates()
        if (packageName == null) {
            armed = null
            return
        }
        val config = profiles.profileFor(packageName)?.volumeTrigger?.normalised()
        val hasPoint = config?.up?.isAssigned == true || config?.down?.isAssigned == true
        if (config == null || !config.enabled || !hasPoint) {
            armed = null
            return
        }
        val size = displaySizes.state().valueOrNull?.active
        armed = size?.let { ArmedPointTrigger(packageName, config, it) }
    }

    /**
     * A volume key the foreground game has bound to a point on its screen.
     *
     * Returns true — swallowing the key — only for a key that an armed binding actually owns, and then for
     * every event of that gesture: the `DOWN`, the auto-repeats and the `UP`. That is the one place in this
     * file where a volume key is taken by default, and it is deliberate. The user bound *that* button in
     * *that* game to a tap, so passing it through as well would put a volume bar over the game on every
     * shot fired.
     *
     * Everything else returns false before anything is touched — the master switch off, no game tracked, the
     * tracked game no longer the armed one, no profile, that key unbound, no point placed, the panel size
     * unreadable. A volume key that quietly stops changing the volume is the failure this whole path is
     * written against, so the checks are ordered cheapest-first and all of them fall through to the ordinary
     * behaviour.
     */
    private fun onPointTriggerKey(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        eventTimeMillis: Long,
    ): Boolean {
        if (!preferences.settings.value.volumePointTriggerEnabled) return false
        val armedNow = armed ?: return false
        // The live value rather than the armed one: [armed] is refreshed by a collector, so for a moment
        // after the user leaves the game it still names it, and a tap injected then would land on whatever
        // they opened next.
        if (gaming.gaming.value.playing != armedNow.packageName) return false
        val button = buttonFor(keyCode) ?: return false
        val binding = armedNow.config.binding(button) ?: return false
        if (!binding.isAssigned) return false

        // Android's auto-repeat while the key is held. The classifier measures a hold from the first `DOWN`,
        // so a repeat carries nothing — but it is still this trigger's key, so it is swallowed rather than
        // let through to change the volume halfway through a hold.
        if (action == KeyEvent.ACTION_DOWN && repeatCount != 0) return true

        val phase = when (action) {
            KeyEvent.ACTION_DOWN -> KeyPhase.DOWN
            KeyEvent.ACTION_UP -> KeyPhase.UP
            // ACTION_MULTIPLE and anything new: not an edge the classifier models, and not a key to hand
            // back either, for the same reason a repeat is not.
            else -> return true
        }

        val decision = synchronized(triggerLock) {
            val current = pressStates[button] ?: VolumePressState.initial(button)
            val (next, outcome) = VolumePressClassifier.evaluate(
                state = current,
                config = pressConfig,
                nowMillis = eventTimeMillis,
                phase = phase,
            )
            pressStates[button] = next
            scheduleTick(next)
            outcome
        }
        dispatch(armedNow, button, decision)
        return true
    }

    /**
     * Schedules the [VolumePressClassifier.tick] this state is waiting for, if it is waiting for one.
     *
     * Two of the outcomes are time-based and arrive with no key event at all: a lone tap only becomes a
     * single once the double window has passed with no second press, and a press only becomes a hold once
     * the threshold has elapsed. The classifier says when each is due; this schedules the wake-up and
     * decides nothing. Called with [triggerLock] held.
     */
    private fun scheduleTick(state: VolumePressState) {
        tickJobs.remove(state.button)?.cancel()
        val deadline = state.pendingSingleDeadlineMillis(pressConfig)
            ?: state.pendingLongPressDeadlineMillis(pressConfig)
            ?: return
        tickJobs[state.button] = scope.launch {
            // `KeyEvent.eventTime` is on the uptime clock, so the deadline derived from it is too, and the
            // wait has to be measured against that same clock rather than the wall one — which a time-zone
            // change or an NTP correction can move underneath a pending press.
            val wait = deadline - SystemClock.uptimeMillis()
            if (wait > 0) delay(wait)
            // Off the map before running, so the re-schedule inside does not cancel this coroutine.
            synchronized(triggerLock) { tickJobs.remove(state.button) }
            runTick(state.button)
        }
    }

    /** Advances one button's press machine with no key event, and fires whatever that resolves. */
    private fun runTick(button: VolumeTriggerButton) {
        val armedNow = armed ?: return
        val decision = synchronized(triggerLock) {
            val current = pressStates[button]
            if (current == null) {
                PressDecision.None
            } else {
                val (next, outcome) = VolumePressClassifier.tick(
                    state = current,
                    config = pressConfig,
                    nowMillis = SystemClock.uptimeMillis(),
                )
                pressStates[button] = next
                scheduleTick(next)
                outcome
            }
        }
        dispatch(armedNow, button, decision)
    }

    /** Injects the tap for [decision], if it is the gesture this button's binding was set to. */
    private fun dispatch(
        armedNow: ArmedPointTrigger,
        button: VolumeTriggerButton,
        decision: PressDecision,
    ) {
        // A hold fires on LongPressStart — the instant the hold is recognised — so the synthetic touch goes
        // down while the user is still holding the key rather than after they let go. LongPressEnd maps to
        // the same press mode and is the release edge of a gesture already fired, so it is dropped here;
        // firing on both would double every hold.
        if (decision is PressDecision.LongPressEnd) return
        val binding = armedNow.config.binding(button) ?: return
        val point = binding.point ?: return
        if (decision.pressMode != binding.pressMode) return
        scope.launch { inject(armedNow, point, binding) }
    }

    /**
     * Resolves the stored fraction against the live display and injects the touch.
     *
     * `wm size` reports the panel in its natural orientation, so the dimensions are swapped for a quarter or
     * three-quarter turn before the fraction is scaled — a landscape game on a portrait panel is the normal
     * case here, not the exception.
     */
    private suspend fun inject(
        armedNow: ArmedPointTrigger,
        point: FractionPoint,
        binding: VolumeTriggerBinding,
    ) {
        val rotation = TapCoordinateMapper.rotationFromDegrees(displays.read().rotationDegrees)
        val isQuarterTurn = rotation == ScreenRotation.ROTATION_90 || rotation == ScreenRotation.ROTATION_270
        val widthPx = if (isQuarterTurn) armedNow.naturalSize.heightPixels else armedNow.naturalSize.widthPixels
        val heightPx = if (isQuarterTurn) armedNow.naturalSize.widthPixels else armedNow.naturalSize.heightPixels
        // The same rotation for both ends, which is a statement rather than a missing value: a binding stores
        // a fraction and no rotation, so the fraction is read in whichever frame is on screen — the same
        // fraction means the same corner of what the user is looking at now.
        val pixel = TapCoordinateMapper.toCurrentPixels(
            point = point,
            captureRotation = rotation,
            currentRotation = rotation,
            currentWidthPx = widthPx,
            currentHeightPx = heightPx,
        )
        // The result is not surfaced, because there is nowhere in a game to surface it to. The editor's Test
        // button runs the same three calls and reports what came back, which is where a user finds out that
        // the shell is gone — and arming already required a shell read, so a failure here is a rare one.
        when (binding.pressMode) {
            VolumeTriggerPressMode.SINGLE_TAP -> pointTaps.tap(pixel.x, pixel.y)
            VolumeTriggerPressMode.DOUBLE_TAP -> pointTaps.doubleTap(pixel.x, pixel.y)
            VolumeTriggerPressMode.HOLD -> pointTaps.hold(pixel.x, pixel.y, binding.holdMs)
        }
    }

    /** Drops every half-finished press. Called when the tracked game changes, so a press cannot cross games. */
    private fun clearPressStates() = synchronized(triggerLock) {
        tickJobs.values.forEach { it.cancel() }
        tickJobs.clear()
        pressStates.clear()
    }

    private fun buttonFor(keyCode: Int): VolumeTriggerButton? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> VolumeTriggerButton.VOLUME_UP
        KeyEvent.KEYCODE_VOLUME_DOWN -> VolumeTriggerButton.VOLUME_DOWN
        else -> null
    }

    // ----------------------------------------------------------------------------- shake detection

    /**
     * One accelerometer sample from [QuickTriggerService].
     *
     * Two jolts above the threshold inside the press window, so that carrying the phone or setting it down
     * does not open a panel over whatever the user was doing. Gravity is subtracted by magnitude rather
     * than by axis, so the device's orientation does not matter.
     */
    fun onAccelerometerSample(x: Float, y: Float, z: Float, nowMillis: Long) {
        val config = settings
        if (!config.enabled || config.method != QuickTriggerMethod.SHAKE) return
        val magnitude = abs(sqrt(x * x + y * y + z * z) - GRAVITY)
        if (magnitude < config.shakeThreshold) return
        if (nowMillis - lastShakeMillis > config.windowMillis) shakeCount = 0
        // A single jolt spans several samples at 50 Hz. Counting each of them would turn one shake into
        // five, so a jolt is only counted once the previous one has had time to settle.
        if (nowMillis - lastShakeMillis < SHAKE_DEBOUNCE_MS) return
        lastShakeMillis = nowMillis
        shakeCount++
        if (shakeCount >= SHAKE_COUNT) {
            shakeCount = 0
            fire(config.action)
        }
    }

    // -------------------------------------------------------------------------------------- firing

    /**
     * Performs the configured action.
     *
     * Rate-limited, because every path into here can repeat: a chord fires on the second key, a tile can be
     * double-tapped, and a shake that straddles the debounce could land twice. Opening the same panel twice
     * in 300 ms would close it again, which looks like the trigger not working.
     */
    fun fire(action: QuickTriggerAction = settings.action) {
        val now = System.currentTimeMillis()
        if (now - lastFiredMillis < FIRE_COOLDOWN_MS) return
        lastFiredMillis = now
        when (action) {
            QuickTriggerAction.TOGGLE_PANEL -> if (!overlays.togglePanel()) openApp()
            QuickTriggerAction.OPEN_PANEL -> if (!overlays.openPanel()) openApp()
            QuickTriggerAction.OPEN_APP -> openApp()
        }
    }

    /**
     * Opens GameCore itself. Also the fallback when the panel cannot be drawn.
     *
     * A trigger that silently does nothing because the overlay permission was revoked is the failure mode
     * this app is written against, so the app opens instead and the settings screen says why.
     */
    fun openApp() {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try {
            context.startActivity(intent)
        } catch (refused: Exception) {
            // Background activity starts are restricted from API 29. Nothing else to try, and nothing
            // to invent: the tile path is exempt and the in-app path is already in the foreground.
        }
    }

    /**
     * Starts or stops the shake watcher to match the settings.
     *
     * Called after every settings change and on launch. The service exists only while the shake method is
     * the armed one, which is what keeps the trigger from costing anything when it is a key or a tile.
     */
    fun syncService() {
        val config = settings
        val wanted = config.enabled &&
            config.method == QuickTriggerMethod.SHAKE &&
            hasAccelerometer()
        val intent = Intent(context, QuickTriggerService::class.java)
        try {
            if (wanted) ContextCompat.startForegroundService(context, intent) else context.stopService(intent)
        } catch (refused: Exception) {
            // startForegroundService throws from the background on API 31+. The user's next visit to
            // Settings calls this again from the foreground, which is the only place it can succeed.
        }
    }

    companion object {
        /** Standard gravity, subtracted from the accelerometer magnitude. */
        const val GRAVITY = 9.80665f

        /** Two jolts make a shake. One is a bump. */
        const val SHAKE_COUNT = 2

        const val SHAKE_DEBOUNCE_MS = 120L

        const val FIRE_COOLDOWN_MS = 600L

        /** A chord is pressed together; beyond this it is two presses, whatever the user's window says. */
        const val CHORD_WINDOW_MAX = 400L

        /**
         * The accessibility service's class name, as a string.
         *
         * A string rather than a class literal so that `domain` does not have to reference a component
         * whose only job is to call back into it, which would be a cycle through the manifest.
         */
        const val TRIGGER_ACCESSIBILITY_CLASS = "com.gamecore.service.QuickTriggerAccessibilityService"
    }
}
