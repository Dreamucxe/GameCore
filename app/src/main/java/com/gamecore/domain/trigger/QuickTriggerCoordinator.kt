package com.gamecore.domain.trigger

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.gamecore.MainActivity
import com.gamecore.core.model.QuickTriggerAction
import com.gamecore.core.model.QuickTriggerMethod
import com.gamecore.core.model.QuickTriggerSettings
import com.gamecore.core.model.TriggerAvailability
import com.gamecore.core.permissions.PermissionChecker
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.domain.overlay.OverlayController
import com.gamecore.service.QuickTriggerService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

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
 */
@Singleton
class QuickTriggerCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: SecurePreferenceStore,
    private val permissions: PermissionChecker,
    private val overlays: OverlayController,
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
     * Returns true when the trigger fired *and* the caller should swallow the key. Which is almost never:
     * [QuickTriggerSettings.passThroughKeys] defaults to on, because a volume key that stops changing the
     * volume is a bug from the user's side of the screen even when it was configured deliberately.
     *
     * Only `ACTION_DOWN` is looked at, and repeats are ignored, so holding a key to scroll the volume does
     * not accumulate presses.
     */
    fun onKeyEvent(keyCode: Int, action: Int, repeatCount: Int, eventTimeMillis: Long): Boolean {
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
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                otherVolumeDownAt = nowMillis
                false
            }

            KeyEvent.KEYCODE_VOLUME_UP -> {
                val paired = nowMillis - otherVolumeDownAt in 0..window
                if (paired) otherVolumeDownAt = 0L
                paired
            }

            else -> false
        }
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
