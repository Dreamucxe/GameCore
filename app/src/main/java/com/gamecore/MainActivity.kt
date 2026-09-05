package com.gamecore

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.gamecore.domain.BackgroundServiceGate
import com.gamecore.domain.StartupCoordinator
import com.gamecore.domain.overlay.OverlayController
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
     */
    private suspend fun prepare() {
        startup.run()
        services.syncDetection()
        overlays.restoreManualState()
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
    }
}
