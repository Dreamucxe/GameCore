package com.gamecore

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.gamecore.core.input.ControllerInputBus
import com.gamecore.domain.BackgroundServiceGate
import com.gamecore.domain.StartupCoordinator
import com.gamecore.domain.overlay.OverlayController
import com.gamecore.domain.trigger.QuickTriggerCoordinator
import com.gamecore.ui.Destination
import com.gamecore.ui.GameCoreRoot
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The only Activity with a UI, and the only one the launcher can start.
 *
 * It is deliberately thin. Everything about what is on screen belongs to [GameCoreRoot] and the
 * ViewModels under it; what is left here is the four things that are the *window's* to do and cannot
 * be done from a composable:
 *
 *  - **The splash handover.** `Theme.GameCore.Starting` is named for both the application and this
 *    activity in the manifest, and `installSplashScreen` is what swaps it for `postSplashScreenTheme`.
 *    Without this call the splash theme stays on and the window keeps a solid background under
 *    Compose. It is not held open waiting for [prepare] — the startup pass includes shell round trips
 *    for the capability probe, and a splash screen that lasts as long as the slowest device's Shizuku
 *    handshake is a launch that looks broken.
 *  - **Edge to edge.** The theme already makes both system bars transparent; this is the other half,
 *    and the screens inset themselves from `WindowInsets`.
 *  - **The once-per-process startup pass**, on a coroutine, after the window exists.
 *  - **The intent handover.** An `Intent` is a window-level thing and a composable has no access to one,
 *    so the extra naming a screen is read here, validated here, and handed down as a value.
 *  - **Raw input dispatch.** Hardware keys and joystick axes arrive at the focused *window* and are not
 *    available to a composable at all. The Controller Lab and the Quick Trigger both need them, so
 *    [dispatchKeyEvent] and [dispatchGenericMotionEvent] forward here and nothing else in the app has to
 *    know that an Activity was involved.
 *
 * `configChanges` in the manifest covers rotation, size, locale, ui mode and font scale, so a rotation
 * does not restart this activity and Compose reads the new configuration directly. Nothing here holds
 * state that a recreation would lose either way: the settings are in the preference store and the
 * session is in a service.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var startup: StartupCoordinator

    @Inject lateinit var services: BackgroundServiceGate

    @Inject lateinit var overlays: OverlayController

    @Inject lateinit var triggers: QuickTriggerCoordinator

    @Inject lateinit var controllers: ControllerInputBus

    /**
     * The screen an intent asked to be opened at, once, or null.
     *
     * A flow rather than a value read during composition, because the request can arrive after the window
     * exists: this activity is the app's only one, so the overlay's colour tile reaches an already-running
     * GameCore through [onNewIntent] rather than a fresh [onCreate]. Cleared as soon as the navigation has
     * happened, so the screen is opened once and not again on the next recomposition.
     */
    private val openAt = MutableStateFlow<Destination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super, which is where the library installs itself into the window.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch { prepare() }
        openAt.value = destinationOf(intent)
        setContent {
            GameCoreRoot(openAt = openAt, onOpened = { openAt.value = null })
        }
    }

    /**
     * A second intent for an activity that is already up — the overlay's colour tile, in practice.
     *
     * `setIntent` first, so that [getIntent] agrees with what was just handled: a configuration change
     * that did slip past `configChanges` would otherwise recreate this activity from the *launch* intent
     * and open the wrong screen.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        destinationOf(intent)?.let { openAt.value = it }
    }

    /**
     * The requested screen, validated against the closed set in [Destination.fromExternal].
     *
     * This activity is exported, because a launcher icon requires it, so this extra can be sent by any app
     * on the device. What that buys a sender is one of a handful of GameCore screens and nothing more —
     * the extra is a token that is looked up in a map, never a route, a path, an id or a package name, so
     * there is no value to smuggle and nothing to escape. An unrecognised token opens the app at Home,
     * which is what a launcher tap does anyway.
     */
    private fun destinationOf(intent: Intent?): Destination? =
        Destination.fromExternal(intent?.getStringExtra(EXTRA_DESTINATION))

    /**
     * Every key event the window receives, before the view tree sees it.
     *
     * Two consumers, in a fixed order, and both are allowed to decline:
     *
     *  - [ControllerInputBus] first, because it only ever claims gamepad and joystick keys and only while
     *    the Controller Lab is on screen asking for them. A controller's A button pressed there should
     *    light up the tester rather than activating whatever Compose thinks is focused.
     *  - [QuickTriggerCoordinator] second. It returns true only when the trigger fired *and* the user has
     *    turned the key pass-through off, which is not the default: a volume key that stops changing the
     *    volume is a bug from the user's side of the screen even when they configured it deliberately.
     *
     * This is also the honest limit of the whole trigger feature. Android delivers hardware keys to the
     * focused window, so this override is reached while GameCore is the app on screen and not otherwise —
     * the accessibility service in [com.gamecore.service.QuickTriggerAccessibilityService] is the only
     * supported way past that, and it is the user's to enable.
     *
     * Anything neither consumer claims goes to `super`, so Back, the volume keys and hardware keyboards
     * behave exactly as they did before this existed.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (controllers.onKeyEvent(event)) return true
        val fired = triggers.onKeyEvent(
            keyCode = event.keyCode,
            action = event.action,
            repeatCount = event.repeatCount,
            eventTimeMillis = event.eventTime,
        )
        if (fired) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Joystick axis movement, which arrives as a motion event rather than as a key.
     *
     * `dispatchGenericMotionEvent` rather than `onGenericMotionEvent`, for the same reason the key
     * override is a dispatch: a `ComposeView` with focus consumes generic motion events, and the
     * un-dispatched callback would never run while the Controller Lab's own scroll container has focus.
     *
     * [ControllerInputBus] claims nothing unless it is capturing and the event came from a joystick, so a
     * mouse wheel or a trackpad gesture still reaches the scrolling it belongs to.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (controllers.onMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    /**
     * The work a launch has to do once, in the order it has to happen.
     *
     * [StartupCoordinator.run] is idempotent and caches its findings, so the screens that want the
     * report — Home, for the repaired-session and outstanding-restore cards — call it again and get
     * this pass's result rather than a second repair pass. Calling it here is about *when* the cost
     * lands: the keystore unwrap and the database open happen behind the first frame instead of in
     * front of it.
     *
     * [BackgroundServiceGate.syncDetection] comes second because it depends on the first: it reads the
     * settings and the profile table, which the preload has just brought into memory. It is also the
     * one place detection can be restored after a reboot or a force-stop, which is why it runs on every
     * launch rather than only when a switch is touched — the user's answer to "watch for my games" is
     * in the settings, and this is the launch acting on it.
     *
     * [OverlayController.restoreManualState] is the same sentence for the overlay windows, and last
     * because it publishes a request the moment it runs: the crosshair, HUD, button and pill the user
     * left switched on come back here, with the preset and layout they were left on. Nothing else reads
     * those stored choices, so without this call a fresh process knows the user wants a crosshair but
     * not which one.
     *
     * [QuickTriggerCoordinator.syncService] afterwards, because a shake trigger has the same problem in
     * the same shape: the user's answer to "open the panel when I shake" is in the settings, the watcher
     * is a process that a reboot or a force-stop has taken away, and this launch is the only place it can
     * come back. It is also the only place `startForegroundService` is guaranteed to be legal — the call
     * throws from the background on API 31+ — which is why the settings screen calls it again rather than
     * relying on this one pass.
     */
    private suspend fun prepare() {
        startup.run()
        services.syncDetection()
        overlays.restoreManualState()
        triggers.syncService()
    }

    companion object {
        /**
         * The extra that names the screen to open at.
         *
         * Fully qualified, because an exported activity's extras share a namespace with every other app
         * that sends it an intent, and a bare "destination" is the kind of key two apps collide on.
         */
        const val EXTRA_DESTINATION = "com.gamecore.extra.DESTINATION"

        /** The colour editor, for the overlay panel's colour tile. One literal, shared with the route. */
        const val DESTINATION_COLOUR = Destination.Colour.EXTERNAL

        /** The media access explanation, for the overlay panel's media strip. Same arrangement. */
        const val DESTINATION_MEDIA_ACCESS = Destination.MediaAccess.EXTERNAL
    }
}
