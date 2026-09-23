package com.gamecore.ui.setup

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.ShizukuState
import com.gamecore.core.permissions.PermissionChecker
import com.gamecore.core.shizuku.ShizukuManager
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.GameProfileRepository
import com.gamecore.domain.setup.setupStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Setup health (spec §A3): every setup item, its real state, and a way to fix it.
 *
 * The opposite shape to [SetupWizardViewModel], on purpose. There is nothing to draft here — the screen is a
 * report — so this is the app's usual [combine] of the live sources, and a profile saved on another screen or
 * a setting changed in another process shows up without the user doing anything. What [combine] cannot see is
 * a permission granted in the system Settings app, because Android emits no event for it; [recheck] covers
 * that and the screen calls it from `OnResume`.
 *
 * Every state on the screen comes out of [buildRows], which is pure. This class's only job is to answer the
 * questions [SetupHealthInputs] asks, and the two halves of each question are deliberately different sources:
 * "does the user want this" comes from their saved profiles and settings, "is it present" comes from the
 * platform. Reading both from the same place is how a screen ends up reporting a permission as needed because
 * it is granted.
 */
@HiltViewModel
class SetupHealthViewModel @Inject constructor(
    private val preferences: SecurePreferenceStore,
    private val permissions: PermissionChecker,
    private val shizuku: ShizukuManager,
    profiles: GameProfileRepository,
) : ViewModel() {

    /**
     * The grant readings, held rather than read inside [combine].
     *
     * They have to live in a flow of their own because they are pulled, not pushed: [PermissionChecker] is a
     * set of synchronous queries with no change notification, so the only way a [combine] can re-run when a
     * grant changes is for something to write a new value in. [recheck] is that something.
     */
    private data class Probe(
        val isLoaded: Boolean = false,
        val hasOverlay: Boolean = false,
        val hasUsageAccess: Boolean = false,
        val hasDoNotDisturb: Boolean = false,
        val hasNotifications: Boolean = false,
        val shizuku: ShizukuState = ShizukuState.RUNNING_PERMISSION_UNKNOWN,
        val message: String? = null,
    )

    private val probe = MutableStateFlow(Probe())

    val state: StateFlow<SetupHealthUiState> = combine(
        preferences.settings,
        profiles.profiles,
        probe,
    ) { settings, saved, readings ->
        val rows = orderedRows(buildRows(inputsFrom(settings, saved, readings)))
        SetupHealthUiState(
            isLoaded = readings.isLoaded,
            rows = rows,
            summary = summarise(rows),
            message = readings.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), SetupHealthUiState())

    init {
        viewModelScope.launch { preferences.preload() }
        recheck()
    }

    /**
     * Re-reads every grant and Shizuku's state (§A3's "live re-check on resume").
     *
     * Deliberately cheap and deliberately uncached. Each of these is a synchronous platform query on the
     * order of microseconds, so there is no reason to debounce it, and a cache in front of it is exactly what
     * would make the screen show a stale "Not set up" one second after the user granted the thing.
     */
    fun recheck() {
        viewModelScope.launch {
            val shizukuState = shizuku.refresh()
            probe.value = probe.value.copy(
                isLoaded = true,
                hasOverlay = permissions.hasOverlayPermission(),
                hasUsageAccess = permissions.hasUsageAccess(),
                hasDoNotDisturb = permissions.hasNotificationPolicyAccess(),
                hasNotifications = permissions.hasNotificationPermission(),
                shizuku = shizukuState,
            )
        }
    }

    fun onResume() = recheck()

    /**
     * Turns the user's saved configuration into the "wanted" half of every row.
     *
     * Each flag below is the honest reading of "a feature the user turned on", which is §A3's own phrase for
     * when a row may nag:
     *  - the overlay is per-profile, so it is wanted when an *enabled* profile draws either the pill or the
     *    floating button. Disabled profiles are excluded because a profile that will not be applied cannot
     *    be missing a permission.
     *  - usage access is wanted when automatic profiles are on, since that is the only feature that needs to
     *    know which app is in front.
     *  - Do Not Disturb is wanted when a profile actually switches it on. The permission being granted is not
     *    evidence that anything wants it.
     *  - notifications are wanted when something will run in the background and therefore has to post a
     *    foreground-service notice. On API 32 and below [PermissionChecker.hasNotificationPermission] answers
     *    true because the grant happens at install, so the row reads Ready rather than inventing a state.
     *  - Shizuku is wanted when a profile uses any setting the elevated shell is required for. A pinned
     *    refresh rate counts: on most builds that write needs the shell, and a profile carrying one on a
     *    device without it is the case where a silently skipped setting looks like a bug.
     */
    private fun inputsFrom(
        settings: AppSettings,
        saved: List<GameProfile>,
        readings: Probe,
    ): SetupHealthInputs {
        val active = saved.filter { it.isEnabled }
        return SetupHealthInputs(
            wantsOverlay = active.any { it.showPerformancePill || it.showFloatingButton },
            hasOverlay = readings.hasOverlay,
            wantsUsageAccess = settings.autoApplyProfiles,
            hasUsageAccess = readings.hasUsageAccess,
            wantsDoNotDisturb = active.any { it.enableDoNotDisturb },
            hasDoNotDisturb = readings.hasDoNotDisturb,
            wantsNotifications = settings.autoApplyProfiles || settings.backgroundMonitoring,
            hasNotifications = readings.hasNotifications,
            wantsShizuku = active.any {
                it.useShizukuOptimizations ||
                    it.thermalDownshiftEnabled ||
                    it.fullPerformanceEnabled ||
                    it.targetRefreshRate != null
            },
            isShizukuUsable = readings.shizuku.isUsable,
            // The one genuinely unavailable case: Shizuku is present but this build's version cannot be
            // talked to. Everything else — not installed, not started, permission not asked — is a state the
            // user can act on, so it stays "Not set up" with a working Fix button rather than becoming a
            // dead end.
            shizukuUnavailableReason = if (readings.shizuku == ShizukuState.VERSION_UNSUPPORTED) {
                readings.shizuku.explanation
            } else {
                null
            },
            measureLatency = settings.measureLatency,
            latencyHost = settings.latencyHost,
            profileCount = saved.size,
        )
    }

    /**
     * The system page a row's Fix button should open, or null when the fix is inside GameCore.
     *
     * Null is not a failure — it is how the screen knows to navigate instead of launching an Activity. The
     * three rows with no permission are all internal: Shizuku opens its own app when installed, the ping host
     * lives on the Settings screen, and the first profile is the new-profile editor.
     */
    fun fixIntentFor(row: SetupHealthRow): Intent? = when (row.item) {
        SetupHealthItem.SHIZUKU -> shizuku.managerLaunchIntent()
        SetupHealthItem.PING_HOST, SetupHealthItem.FIRST_PROFILE -> null
        else -> row.item.permission?.let { permissions.settingsIntentFor(it) }
    }

    /**
     * Clears the wizard's completion so it can be run again (§A3's "Run setup again").
     *
     * Dismissal is cleared too. A user pressing this button is asking for the wizard, and leaving the
     * dismissal set would mean the Home card stayed gone afterwards for a reason they could not see. The
     * saved step is cleared so the flow starts at Welcome rather than resuming wherever it was abandoned
     * months ago — the feature ticks are left alone, because they are the user's answers and re-asking for
     * them from blank would be the wizard forgetting what it was told.
     */
    fun restartWizard() {
        preferences.updateSettings { it.copy(setupCompletedVersion = 0, setupDismissed = false) }
        preferences.setupStep = null
    }

    /** The screen calls this when `startActivity` threw, so a dead Fix button says so instead of doing nothing. */
    fun onIntentFailed() {
        probe.value = probe.value.copy(message = INTENT_FAILED)
    }

    fun dismissMessage() {
        probe.value = probe.value.copy(message = null)
    }

    private companion object {
        const val INTENT_FAILED =
            "Android would not open that screen on this device. The same setting can be reached from " +
                "Settings › Apps › GameCore."
    }
}
