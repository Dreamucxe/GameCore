package com.gamecore.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
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
