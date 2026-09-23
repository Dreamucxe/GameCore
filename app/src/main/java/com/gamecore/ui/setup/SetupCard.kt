package com.gamecore.ui.setup

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gamecore.BuildConfig
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.ShizukuState
import com.gamecore.core.permissions.PermissionChecker
import com.gamecore.core.shizuku.ShizukuManager
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.GameProfileRepository
import com.gamecore.data.repository.SessionRepository
import com.gamecore.domain.setup.SetupSignals
import com.gamecore.domain.setup.WizardEntry
import com.gamecore.domain.setup.decideEntry
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Check your setup" on Home (spec §A1), and the decision about whether it belongs there at all.
 *
 * §A1 gives this card two independent conditions and both have to hold. [decideEntry] answers the first —
 * has this install seen the wizard, at this version, and was the card already dismissed — and it is the
 * committed pure function, so upgraders get a card and someone who said no never sees it again. [needsAttention]
 * answers the second: is anything the user *turned on* actually unable to work. A card that appeared without
 * the second condition would be the app inventing a chore.
 *
 * Renders nothing when either condition fails. That is why it is safe to call unconditionally from Home — the
 * call site is one `item { SetupCard(...) }` and the decision stays here rather than being duplicated into
 * `HomeUiState`, which would make the rule something two files have to agree about.
 *
 * It carries its own ViewModel for the same reason. The alternative is adding four sources and a dismissal to
 * `HomeViewModel`, which already combines most of the app; a card whose entire content is one line and two
 * buttons does not justify that, and scoping a second ViewModel to the Home back-stack entry costs a single
 * object.
 */
@Composable
fun SetupCard(
    onOpenWizard: () -> Unit,
    onOpenHealth: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SetupCardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.isVisible) return

    SectionCard(
        title = "Check your setup",
        subtitle = state.line,
        icon = Icons.Filled.Tune,
        modifier = modifier,
    ) {
        ActionRow {
            // Two different places on purpose. An upgrader who never saw the wizard is offered it; anyone
            // else is offered the screen that lists exactly what is missing, which is the shorter path when
            // there is one broken row rather than a whole setup to do.
            TextButton(
                onClick = if (state.offersWizard) onOpenWizard else onOpenHealth,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (state.offersWizard) "Run setup" else "Check it")
            }
            Spacer(modifier = Modifier.weight(1f))
            // Permanent, and §A1 says so: "one-time dismissible". The wizard's own dismissal writes the same
            // flag, so saying no here and saying no there mean the same thing.
            TextButton(onClick = viewModel::dismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Not now")
            }
        }
    }
}

/** What the card needs to decide whether it exists, and what it says if it does. */
data class SetupCardState(
    val isVisible: Boolean = false,
    val offersWizard: Boolean = false,
    val line: String = "",
)

/**
 * The card's own state, kept out of `HomeViewModel` (see [SetupCard] for why).
 *
 * The [combine] is over the same sources Setup health reads plus the session count, which is the one extra
 * signal [decideEntry] needs: a user with recorded sessions and no completion flag upgraded from before the
 * wizard existed, and treating them as a fresh install would take over their Home screen.
 *
 * The grant readings are pulled once per composition of Home rather than on a resume ticker. That is the
 * honest trade: the card is a prompt, not a live report, and a grant made two seconds ago showing up on the
 * next visit to Home is fine where the same delay on Setup health would not be.
 */
@HiltViewModel
class SetupCardViewModel @Inject constructor(
    private val preferences: SecurePreferenceStore,
    private val permissions: PermissionChecker,
    private val shizuku: ShizukuManager,
    profiles: GameProfileRepository,
    sessions: SessionRepository,
) : ViewModel() {

    private data class Probe(
        val isLoaded: Boolean = false,
        val hasOverlay: Boolean = false,
        val hasUsageAccess: Boolean = false,
        val hasDoNotDisturb: Boolean = false,
        val hasNotifications: Boolean = false,
        val shizuku: ShizukuState = ShizukuState.RUNNING_PERMISSION_UNKNOWN,
        val dismissedNow: Boolean = false,
    )

    private val probe = MutableStateFlow(Probe())

    val state: StateFlow<SetupCardState> = combine(
        preferences.settings,
        profiles.profiles,
        sessions.sessionCount,
        probe,
    ) { settings, saved, sessionCount, readings ->
        // Nothing is decided until the grants have actually been read. A card that appeared for one frame
        // on every cold start, before the checker had answered, would be a flash of "something is wrong"
        // on a device where nothing is.
        if (!readings.isLoaded || readings.dismissedNow) {
            SetupCardState()
        } else {
            val entry = decideEntry(
                SetupSignals(
                    hasCompletedVersion = settings.setupCompletedVersion.takeIf { it > 0 },
                    currentVersion = BuildConfig.VERSION_CODE,
                    dismissed = settings.setupDismissed,
                    hasProfiles = saved.isNotEmpty(),
                    hasSessions = sessionCount > 0,
                ),
            )
            val rows = buildRows(inputsFrom(settings, saved, readings))
            SetupCardState(
                // FullWizard is not a card. §A1 sends a fresh install into the wizard itself, and the root
                // is what acts on that — a card offering setup on an empty Home would be the takeover it is
                // supposed to replace.
                isVisible = entry == WizardEntry.HomeCard && needsAttention(rows),
                offersWizard = settings.setupCompletedVersion == 0,
                line = attentionLine(rows),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), SetupCardState())

    init {
        viewModelScope.launch {
            preferences.preload()
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

    /**
     * The same mapping [SetupHealthViewModel] uses, so the card and the screen can never disagree.
     *
     * Duplicated as a private function rather than shared, because sharing it would mean a class one of the
     * two does not otherwise need; the part that matters — [buildRows] — is already the single source of the
     * decision, and that is what both call.
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
     * Records the dismissal permanently, and hides the card without waiting for the write to come back.
     *
     * Both halves are needed. The stored flag is what keeps it gone across launches; [Probe.dismissedNow] is
     * what makes the tap feel like it did something, because the settings flow's next emission is a hop
     * through the encrypted store away.
     */
    fun dismiss() {
        probe.value = probe.value.copy(dismissedNow = true)
        preferences.updateSettings { it.copy(setupDismissed = true) }
    }
}
