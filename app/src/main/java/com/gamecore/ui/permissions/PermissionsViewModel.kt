package com.gamecore.ui.permissions

import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import com.gamecore.core.permissions.GamePermission
import com.gamecore.core.permissions.PermissionChecker
import com.gamecore.data.preferences.SecurePreferenceStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * §21's permissions centre.
 *
 * Nothing is observed here, and that is deliberate. Every access on this screen is granted somewhere else —
 * a Settings page, a system dialog, another app entirely — and not one of them calls back when it happens.
 * So the state is re-read on every resume, which is the only moment GameCore reliably learns that something
 * changed, and [refresh] is cheap enough for that to be fine.
 *
 * The intents are built here and launched by the screen. [PermissionChecker] resolves each one before
 * returning it, so a row only offers a button when there is something for it to open; the ViewModel holds
 * no `Context` that could start an activity and the composable constructs no intent. That is §25's
 * boundary, kept in both directions.
 */
@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val permissions: PermissionChecker,
    private val preferences: SecurePreferenceStore,
) : ViewModel() {

    private val editing = MutableStateFlow(PermissionsUiState())
    val state: StateFlow<PermissionsUiState> = editing.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads every access from the platform. Called from the screen's `OnResume`. */
    fun refresh() {
        editing.value = editing.value.copy(
            isLoaded = true,
            rows = GamePermission.entries.map(::rowFor),
            needsBatteryExemption = wantsUnthrottledWork() &&
                !permissions.isIgnoringBatteryOptimisations(),
        )
    }

    private fun rowFor(permission: GamePermission): PermissionRow = PermissionRow(
        permission = permission,
        isGranted = permissions.isGranted(permission),
        hasSettingsPage = permissions.settingsIntentFor(permission) != null,
        canRequestDialog = permission == GamePermission.POST_NOTIFICATIONS &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        unavailableReason = unavailableReasonFor(permission),
    )

    /**
     * Why an access is not offered on this device, or null when it is.
     *
     * Both cases are a version floor rather than a refusal: below Android 13 a notification needs no
     * permission, and below Android 11 every installed app is already visible. Showing either as "denied"
     * would send the user looking for a toggle that the Settings app on their phone does not have.
     */
    private fun unavailableReasonFor(permission: GamePermission): String? = when {
        permission == GamePermission.POST_NOTIFICATIONS &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
            "Granted at install on this version of Android. The permission arrived in Android 13."

        permission == GamePermission.PACKAGE_VISIBILITY &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
            "Not filtered on this version of Android. The restriction arrived in Android 11."

        else -> null
    }

    /**
     * The Settings page for one access, for the screen to launch.
     *
     * Null when Android has nowhere to send the user, which is the case for package visibility: it is a
     * manifest declaration, not a toggle. The row already knows that and offers no button, so this
     * returning null is the second line of defence rather than the check.
     */
    fun settingsIntentFor(permission: GamePermission): Intent? =
        permissions.settingsIntentFor(permission)

    /**
     * §24B's Doze exemption request.
     *
     * The direct dialog where the build resolves it, the global battery list where it does not — never
     * nothing, because a truncated session is the failure this prevents and "we could not ask" is not a
     * useful outcome for the user. That the ask happened is recorded so no other screen raises it again.
     */
    fun batteryExemptionIntent(): Intent? {
        preferences.hasAskedBatteryExemption = true
        return permissions.requestBatteryExemptionIntent()
            ?: permissions.settingsIntentFor(GamePermission.BATTERY_OPTIMISATION_EXEMPTION)
    }

    /** The notification dialog's answer. A refusal switches that row over to the Settings page. */
    fun onNotificationResult(isGranted: Boolean) {
        editing.value = editing.value.copy(
            notificationDialogRefused = !isGranted,
            message = if (isGranted) null else NOTIFICATION_REFUSED,
        )
        refresh()
    }

    /** §28: a resolved intent can still fail to start, and the user hears about it rather than tapping on. */
    fun onIntentFailed() {
        editing.value = editing.value.copy(message = NO_SETTINGS_PAGE)
    }

    fun dismissMessage() {
        editing.value = editing.value.copy(message = null)
    }

    /**
     * Whether anything the user has switched on needs to survive Doze.
     *
     * Read from the stored flags rather than assumed: §24B asks for this prompt at the point a feature that
     * needs it is enabled, and an install that has never shown an overlay or recorded a session is being
     * asked for an exemption it has no use for.
     */
    private fun wantsUnthrottledWork(): Boolean {
        val settings = preferences.settings.value
        return settings.trackSessions || settings.backgroundMonitoring ||
            preferences.showHudOverlay || preferences.showCrosshairOverlay ||
            preferences.overlay.value.showPill || preferences.floatingButton.value.show
    }

    private companion object {
        const val NOTIFICATION_REFUSED =
            "Without this permission GameCore cannot post the notification a foreground service requires, " +
                "so overlays, session recording and background monitoring will not start at all. Android " +
                "stops showing its dialog after two refusals — the Settings page still works."

        const val NO_SETTINGS_PAGE =
            "This build of Android would not open that page. GameCore's own details page is the route " +
                "left, and the access can be granted from there."
    }
}
