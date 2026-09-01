package com.gamecore.core.permissions

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single source of truth for what GameCore is currently permitted to do.
 *
 * Ported from ProcessLens, extended with the four accesses GameCore needs and it did
 * not: overlay, WRITE_SETTINGS, notification policy, and the rotation/brightness
 * writes that depend on the second of those.
 *
 * Three categories are checked three different ways, and conflating any two of them
 * is a bug that presents as a SecurityException at the worst possible moment:
 *
 *  * **Runtime permissions** (POST_NOTIFICATIONS, QUERY_ALL_PACKAGES) —
 *    `checkSelfPermission`.
 *  * **App-ops** (usage access, modify-system-settings) — `AppOpsManager`.
 *    `checkSelfPermission` returns GRANTED for PACKAGE_USAGE_STATS as soon as it is
 *    declared in the manifest, which is not the gate; the op is.
 *  * **Special accesses** (overlay, DND, Doze exemption) — each has its own platform
 *    call and no `checkSelfPermission` equivalent at all.
 *
 * Every check is wrapped. These calls throw on OEM builds that have stripped or
 * renamed an op, and a permissions screen that crashes while reporting the permission
 * state is worse than one that reports "no".
 */
@Singleton
class PermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    // ------------------------------------------------------------------- overlay

    /**
     * Whether GameCore may add a window over other apps.
     *
     * `Settings.canDrawOverlays` is authoritative and cheap, and it is checked
     * immediately before every `WindowManager.addView` rather than cached: the user can
     * revoke it from Settings while a service is running, and the revocation arrives as
     * a thrown exception on the next add, not as a callback.
     */
    fun hasOverlayPermission(): Boolean = try {
        Settings.canDrawOverlays(context)
    } catch (error: Throwable) {
        false
    }

    // ------------------------------------------------------------------- app-ops

    /**
     * Usage access — the gate on foreground-app detection.
     *
     * `unsafeCheckOpNoThrow` is the non-deprecated spelling from API 29; below that the
     * older `checkOpNoThrow` is the only one present. `MODE_DEFAULT` means "defer to
     * the permission", which for this op means asking whether we hold it as a
     * privileged app — we do not, but the check is cheap and correct.
     */
    fun hasUsageAccess(): Boolean = appOpAllowed(
        op = AppOpsManager.OPSTR_GET_USAGE_STATS,
        fallbackPermission = android.Manifest.permission.PACKAGE_USAGE_STATS,
    )

    /**
     * Modify-system-settings — the gate on brightness, rotation and the screen timeout.
     *
     * `Settings.System.canWrite` is the documented check and is used in preference to
     * the app-op, because it is the exact predicate the settings provider itself
     * applies when the write arrives.
     */
    fun hasWriteSettings(): Boolean = try {
        Settings.System.canWrite(context)
    } catch (error: Throwable) {
        false
    }

    // ---------------------------------------------------------- special accesses

    /** Do Not Disturb, through `NotificationManager.setInterruptionFilter`. */
    fun hasNotificationPolicyAccess(): Boolean = try {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.isNotificationPolicyAccessGranted == true
    } catch (error: Throwable) {
        false
    }

    /**
     * Whether GameCore is exempt from Doze and App Standby.
     *
     * Relevant because a two-hour session recorded by a throttled sampler has gaps in
     * it, and an overlay service can be killed outright mid-game. The app asks for this
     * when the user switches on overlays or session tracking, and explains why.
     */
    fun isIgnoringBatteryOptimisations(): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    } catch (error: Throwable) {
        false
    }

    // ------------------------------------------------------- runtime permissions

    /**
     * POST_NOTIFICATIONS exists from API 33. Below that notifications are granted at
     * install, so the honest answer is "yes" rather than "unknown" — and a first-run
     * flow that asked for it on API 26 would show a dialog that cannot appear.
     */
    fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasRuntimePermission(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

    /**
     * Whether the game picker sees every installed application. Package visibility is
     * filtered from API 30; below it, everything is visible without a permission.
     */
    fun hasFullPackageVisibility(): Boolean = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> true
        else -> hasRuntimePermission(android.Manifest.permission.QUERY_ALL_PACKAGES)
    }

    fun hasRuntimePermission(permission: String): Boolean = try {
        ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (error: Throwable) {
        false
    }

    /** The state of one catalogue entry, so the permissions centre can render a list. */
    fun isGranted(permission: GamePermission): Boolean = when (permission) {
        GamePermission.OVERLAY -> hasOverlayPermission()
        GamePermission.USAGE_ACCESS -> hasUsageAccess()
        GamePermission.WRITE_SETTINGS -> hasWriteSettings()
        GamePermission.NOTIFICATION_POLICY -> hasNotificationPolicyAccess()
        GamePermission.POST_NOTIFICATIONS -> hasNotificationPermission()
        GamePermission.BATTERY_OPTIMISATION_EXEMPTION -> isIgnoringBatteryOptimisations()
        GamePermission.PACKAGE_VISIBILITY -> hasFullPackageVisibility()
    }

    // ------------------------------------------------------------------- intents

    /**
     * The Settings page for one access, or null when it cannot be reached by intent.
     *
     * Every intent is resolved before it is returned. The per-app deep links are absent
     * on a number of OEM builds, and an unresolvable intent thrown at `startActivity`
     * is an ActivityNotFoundException on a screen whose entire purpose is helping the
     * user grant something. Each falls back to the global page, then to GameCore's own
     * app-details page, which exists everywhere.
     */
    fun settingsIntentFor(permission: GamePermission): Intent? = when (permission) {
        GamePermission.OVERLAY -> resolveOrFallback(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", context.packageName, null),
            ),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        )

        GamePermission.USAGE_ACCESS -> resolveOrFallback(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            },
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
        )

        GamePermission.WRITE_SETTINGS -> resolveOrFallback(
            Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS),
        )

        GamePermission.NOTIFICATION_POLICY -> resolveOrFallback(
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
        )

        GamePermission.POST_NOTIFICATIONS ->
            // Requested with a runtime dialog; the Settings page is the fallback for a
            // user who has already declined twice, at which point the dialog no longer
            // appears and only Settings will do.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                resolveOrFallback(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    },
                )
            } else {
                appDetailsIntent()
            }

        GamePermission.BATTERY_OPTIMISATION_EXEMPTION -> resolveOrFallback(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )

        // Granted at install; there is no page to send the user to.
        GamePermission.PACKAGE_VISIBILITY -> null
    }

    /**
     * The direct "allow unrestricted battery use" dialog.
     *
     * Kept apart from [settingsIntentFor] because this one names GameCore's package in
     * a `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` action, which Play policy restricts to
     * apps whose core function genuinely requires it and which some builds hide
     * entirely. The caller uses it when available and the list page otherwise.
     */
    fun requestBatteryExemptionIntent(): Intent? {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", context.packageName, null),
        )
        return if (resolves(direct)) direct else null
    }

    fun appDetailsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )

    /** App-details for another package, for the game list's "app info" action. */
    fun appDetailsIntent(packageName: String): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    )

    // --------------------------------------------------------------------- utils

    private fun appOpAllowed(op: String, fallbackPermission: String): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        if (appOps == null) {
            false
        } else {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(op, android.os.Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(op, android.os.Process.myUid(), context.packageName)
            }
            when (mode) {
                AppOpsManager.MODE_ALLOWED -> true
                AppOpsManager.MODE_DEFAULT -> hasRuntimePermission(fallbackPermission)
                else -> false
            }
        }
    } catch (error: Throwable) {
        false
    }

    /**
     * Returns the first candidate that resolves, then GameCore's app-details page.
     *
     * Never returns an unresolvable intent, which is the whole point: `startActivity`
     * on one throws, and the caller here is a settings screen.
     */
    private fun resolveOrFallback(vararg candidates: Intent): Intent {
        for (candidate in candidates) {
            if (resolves(candidate)) return candidate
        }
        return appDetailsIntent()
    }

    private fun resolves(intent: Intent): Boolean = try {
        intent.resolveActivityInfo(context.packageManager, 0) != null
    } catch (error: Throwable) {
        false
    }
}
