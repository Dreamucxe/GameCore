package com.gamecore.core.shizuku

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.gamecore.BuildConfig
import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.common.RestrictionReason
import com.gamecore.core.model.RootState
import com.gamecore.core.model.ShizukuState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The seam between the elevated shell and the rest of GameCore.
 *
 * Everything above `core` — every ViewModel, every screen, every service — depends on
 * this class or on something that wraps it, and never on [ShizukuShell] or
 * [ShellCommand] directly. That is what keeps the architecture's rule that the UI
 * never executes shell commands true by construction: the UI layer is not given a
 * type that can express one. What it gets instead is a state to render, a permission
 * request to fire, an intent to open Shizuku, and three named operations.
 *
 * The three operations are the whole of what the Shizuku *screen* can do. Everything
 * else Shizuku enables — refresh-rate pinning, animation scales, thermal sensor
 * names — is reached through the readers and the optimizer, which each hold their own
 * narrow interface and are responsible for their own standard-Android fallback.
 */
@Singleton
class ShizukuManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: ShizukuShell,
    private val rootDetector: RootDetector,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Live connection state. Updates when the service starts, stops, or answers a request. */
    val state: StateFlow<ShizukuState> get() = shell.state

    val isConnected: Boolean get() = shell.state.value.isUsable

    /** The highest access GameCore actually has. Never [AccessLevel.ROOT]: root is not used. */
    val accessLevel: AccessLevel
        get() = if (isConnected) AccessLevel.SHIZUKU else AccessLevel.NORMAL

    fun refresh(): ShizukuState = shell.refresh()

    suspend fun requestPermission(): ShizukuState = shell.requestPermission()

    suspend fun rootState(): RootState = rootDetector.detect()

    /**
     * Proves the shell actually works, rather than trusting that a granted permission
     * implies a working binder.
     *
     * They come apart in practice: a permission granted before a reboot survives in
     * Shizuku's own records while the service does not, and `newProcess` is a hidden
     * method that a future Shizuku could rename. So the Shizuku screen reports
     * "connected" only after an `id` has come back with a uid, and shows the uid —
     * which is `2000`, the shell user, and is the most direct demonstration
     * available that this is ADB-level access and not root.
     */
    suspend fun verify(): Observed<String> = withContext(io) {
        val state = shell.refresh()
        if (!state.isUsable) {
            return@withContext Observed.Restricted(
                reason = RestrictionReason.REQUIRES_ELEVATED_ACCESS,
                unlockedBy = AccessLevel.SHIZUKU,
                detail = state.explanation,
            )
        }
        val result = shell.execute(ShellCommand.Probe)
        when {
            !result.isSuccess ->
                Observed.Failed("The shell did not respond.", result.failureReason())
            else -> {
                val uid = UID_PATTERN.find(result.stdout)?.groupValues?.getOrNull(1)
                if (uid == null) {
                    Observed.Failed("The shell responded but its identity could not be read.")
                } else {
                    Observed.of(
                        value = if (uid == SHELL_UID) "uid $uid (shell)" else "uid $uid",
                        source = DataSource.SHELL_SHIZUKU,
                    )
                }
            }
        }
    }

    /**
     * Grants GameCore one of the special accesses it cannot request with a dialog.
     *
     * Usage access and modify-system-settings are appop gates that a normal app can
     * only send the user to Settings for. With Shizuku they can be set directly,
     * which turns a three-screen detour into one tap. Two paths are attempted because
     * neither works everywhere: `pm grant` handles the permission on some versions,
     * `appops set` handles the underlying op on the rest.
     *
     * WRITE_SECURE_SETTINGS has no op behind it and no Settings page in front of it, so
     * for that one `pm grant` is the only path and its failure is reported as the final
     * answer rather than retried against an op that does not exist.
     *
     * Reports what actually happened. A granted-looking failure would be worse than
     * useless here, because the very next thing the app does is try to read usage
     * stats and get a SecurityException.
     */
    suspend fun grantSelfAccess(access: SelfGrantablePermission): GrantOutcome =
        withContext(io) {
            if (!isConnected) return@withContext GrantOutcome.NotConnected

            val ownPackage = BuildConfig.APPLICATION_ID
            val grant = ShellCommand.grantSelfPermission(ownPackage, access)
                ?: return@withContext GrantOutcome.Failed("The permission could not be prepared.")

            val grantResult = shell.execute(grant)
            if (grantResult.isSuccess) return@withContext GrantOutcome.Granted

            // `pm grant` refuses appop-backed permissions on several versions with
            // "not a changeable permission type". The op itself is still settable.
            val op = access.appOp
                ?: return@withContext GrantOutcome.Failed(grantResult.failureReason())
            val appOp = ShellCommand.setSelfAppOp(ownPackage, op, allow = true)
                ?: return@withContext GrantOutcome.Failed(grantResult.failureReason())

            val appOpResult = shell.execute(appOp)
            if (appOpResult.isSuccess) {
                GrantOutcome.Granted
            } else {
                GrantOutcome.Failed(appOpResult.failureReason())
            }
        }

    /**
     * An intent that opens the installed Shizuku manager, or null if none is present.
     *
     * Resolved through the launcher intent rather than by naming an activity, because
     * Shizuku and Sui use different components and both have renamed theirs across
     * releases. Null is the honest answer when nothing is installed, and the UI shows
     * install guidance in that case instead of a button that fails.
     */
    fun managerLaunchIntent(): Intent? {
        val pm = context.packageManager
        for (pkg in MANAGER_PACKAGES) {
            val intent = try {
                pm.getLaunchIntentForPackage(pkg)
            } catch (error: Throwable) {
                null
            }
            if (intent != null) return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return null
    }

    /**
     * The Play listing for Shizuku, for a device that does not have it.
     *
     * A web URL rather than a `market://` one: `market://` fails on a device with no
     * Play Store, which describes a good share of the users who install Shizuku in
     * the first place. The browser handles the same link, and F-Droid users are given
     * the direct link in the setup text.
     */
    fun installIntent(): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://shizuku.rikka.app/"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private companion object {
        val MANAGER_PACKAGES = listOf("moe.shizuku.privileged.api", "moe.shizuku.manager")
        val UID_PATTERN = Regex("""uid=(\d+)""")
        const val SHELL_UID = "2000"
    }
}

/** What a self-grant attempt did. Reported to the user as-is; nothing is assumed. */
sealed interface GrantOutcome {
    data object Granted : GrantOutcome
    data object NotConnected : GrantOutcome
    data class Failed(val detail: String) : GrantOutcome
}
