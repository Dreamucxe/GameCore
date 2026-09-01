package com.gamecore

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.gamecore.domain.BackgroundServiceGate
import com.gamecore.domain.StartupCoordinator
import com.gamecore.ui.GameCoreRoot
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The only Activity with a UI, and the only one the launcher can start.
 *
 * It is deliberately thin. Everything about what is on screen belongs to [GameCoreRoot] and the
 * ViewModels under it; what is left here is the three things that are the *window's* to do and cannot
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

    // MAIN_BODY

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super, which is where the library installs itself into the window.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch { prepare() }
        setContent { GameCoreRoot() }
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
     */
    private suspend fun prepare() {
        startup.run()
        services.syncDetection()
    }
}
