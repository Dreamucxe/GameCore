package com.gamecore.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent

/**
 * Starting another app's screen, for the two places in GameCore that do it.
 *
 * Every intent this app launches comes from [com.gamecore.core.permissions.PermissionChecker] or
 * [com.gamecore.core.shizuku.ShizukuManager], both of which resolve before returning — and it can still
 * fail. `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` is absent on a handful of Android TV and tablet
 * builds, OEM ROMs remove `ACTION_USAGE_ACCESS_SETTINGS`, and a `SecurityException` comes back from
 * builds that guard the battery-optimisation dialog. §28 does not allow any of those to be a crash, and
 * §32 does not allow a button that silently does nothing, so the caller is told which happened and says
 * so.
 *
 * Returns true only when Android accepted the intent.
 */
fun Context.startIntentSafely(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (notFound: ActivityNotFoundException) {
    false
} catch (denied: SecurityException) {
    false
} catch (illegal: IllegalArgumentException) {
    false
}

/**
 * The Activity behind a Compose `Context`, or null.
 *
 * `LocalContext` inside `setContent` is usually the Activity itself, but a theme overlay or a dialog window
 * wraps it, and the wrapper is what a composable is handed — so the cast that looks obvious returns null on
 * exactly the devices where it matters. Unwrapping the chain is the documented way to find it.
 *
 * Null rather than an exception, because a caller can genuinely have no Activity and should carry on: a
 * `@Preview` renders in a context that never had one, and a composable that needs a window has to draw
 * nothing rather than crash the preview of every screen it sits under.
 *
 * Nothing calls this at present. Its two callers were the ad banner and the consent form it opened, both
 * removed in 3.4; it is kept because it is the correct unwrap for the next composable that needs a window,
 * and that is the same structural reason the overlay can never show one: a Service has no Activity to
 * unwrap to.
 */
fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
