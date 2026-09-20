package com.gamecore.aimlab.ui.input

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.ContextWrapper
import android.view.View
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.gamecore.core.model.AimLabOrientation

/**
 * Forces a screen's orientation and immersive mode for exactly as long as it is on screen, then restores
 * what was there before (§1 orientation, §6 lifecycle).
 *
 * This is the whole of the "change orientation for Aim Lab training screens only" rule. A training or
 * HUD-editor composable calls [ApplyAimLabOrientation]; a `DisposableEffect` sets the host activity's
 * `requestedOrientation` and hides the system bars on enter, and its `onDispose` restores the orientation
 * and re-shows the bars if they were showing before — so leaving the screen, the run stopping, or the
 * composable being torn down by process death all return the rest of GameCore to how it behaved. It does
 * NOT touch `setDecorFitsSystemWindows`: the app is edge-to-edge for its whole life (MainActivity's
 * `enableEdgeToEdge()`), and flipping that flag here then "restoring" it shifted the whole UI until a
 * restart. No manifest attribute is touched and no existing activity's `configChanges` is changed;
 * session state lives in the ViewModel, so the activity recreation a rotation triggers is safe.
 *
 * Immersive mode is `BEHAVE_SHOW_TRANSIENT_BARS_BY_SWIPE`, so the bars are hidden but a swipe brings them
 * back transiently — the arena gets the whole screen without trapping the user.
 */
@androidx.compose.runtime.Composable
fun ApplyAimLabOrientation(orientation: AimLabOrientation) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = rememberActivity(context)

    DisposableEffect(orientation, activity) {
        val window = activity?.window
        val previousOrientation = activity?.requestedOrientation
        val insetsController = window?.let { WindowInsetsControllerCompat(it, view) }
        val previousBarsShown = window != null &&
            ViewCompatInsets.isVisible(view)

        activity?.requestedOrientation = orientation.toActivityInfo()
        if (window != null && insetsController != null) {
            // Do NOT touch setDecorFitsSystemWindows here. The app is edge-to-edge for its whole life —
            // MainActivity calls enableEdgeToEdge(), which sets decorFitsSystemWindows=false as the
            // baseline — so toggling it and then "restoring" it to true on exit left the window fitting
            // its content differently than the rest of GameCore expects, shifting the whole layout and
            // never recovering until a restart. Immersive mode only needs the bars hidden; the
            // edge-to-edge fitting is already correct and must be left exactly as MainActivity set it.
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            // Restore only what we changed: the orientation, and the bars if they were showing before.
            // decorFitsSystemWindows is deliberately untouched (see above), so leaving Aim Lab returns the
            // rest of GameCore to exactly the window state it always had.
            if (previousOrientation != null) activity.requestedOrientation = previousOrientation
            if (insetsController != null && previousBarsShown) {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

/** Maps the user's choice to the platform's `requestedOrientation` constant (§1). */
private fun AimLabOrientation.toActivityInfo(): Int = when (this) {
    // Sensor-based landscape so both landscape directions work.
    AimLabOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    AimLabOrientation.REVERSE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    AimLabOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    AimLabOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
}

/** Walks the context wrapper chain to the host [Activity], or null. */
private fun rememberActivity(context: Context): Activity? {
    var c = context
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Small indirection so the visibility read is easy to stub; system-bar visibility from the current insets. */
private object ViewCompatInsets {
    fun isVisible(view: View): Boolean {
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view)
        return insets?.isVisible(WindowInsetsCompat.Type.systemBars()) ?: true
    }
}
