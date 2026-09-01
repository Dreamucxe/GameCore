package com.gamecore.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.DeviceCapabilities
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.model.StopReason
import com.gamecore.core.shizuku.ShizukuManager
import com.gamecore.data.repository.GameProfileRepository
import com.gamecore.domain.StartupCoordinator
import com.gamecore.domain.gaming.GamingCoordinator
import com.gamecore.domain.monitoring.PerformanceMonitor
import com.gamecore.domain.optimization.DeviceCapabilityChecker
import com.gamecore.domain.optimization.OptimizationManager
import com.gamecore.domain.overlay.OverlayController
import com.gamecore.ui.components.TIGHT_STORAGE_FRACTION
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.batteryDetail
import com.gamecore.ui.components.batteryTone
import com.gamecore.ui.components.memoryTone
import com.gamecore.ui.components.readout
import com.gamecore.ui.components.readoutOf
import com.gamecore.ui.components.refreshDetail
import com.gamecore.ui.components.temperatureDetail
import com.gamecore.ui.components.temperatureTone
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The dashboard's state, its live figures, and the actions it offers.
 *
 * [readouts] is a *cold* flow deliberately. [PerformanceMonitor.snapshots] only samples while it has a
 * collector, and `map` passes a subscription straight through, so the screen's
 * `collectAsStateWithLifecycle` is what starts and stops the sampler. A `stateIn(viewModelScope)` here
 * would hold that subscription for as long as the back stack entry lives, and the app would quietly
 * sample forever after the user's first visit to the dashboard.
 *
 * [state] is the opposite case and is hot: it is assembled from flows that are cheap to observe and
 * expensive to re-derive, and losing it on a configuration change would flash the whole dashboard back
 * to zeroes.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    monitor: PerformanceMonitor,
    private val overlay: OverlayController,
    private val optimizations: OptimizationManager,
    private val capabilityChecker: DeviceCapabilityChecker,
    private val coordinator: GamingCoordinator,
    startup: StartupCoordinator,
    shizuku: ShizukuManager,
    profiles: GameProfileRepository,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())

    val readouts: Flow<HomeReadouts> = monitor.snapshots.map(::readoutsOf)

    val state: StateFlow<HomeUiState> = combine(
        shizuku.state,
        overlay.status,
        profiles.profileCount,
        coordinator.gaming,
        local,
    ) { shizukuState, overlayStatus, profileCount, gaming, own ->
        HomeUiState(
            shizuku = shizukuState,
            overlay = overlayStatus,
            profileCount = profileCount,
            gaming = gaming,
            capabilities = own.capabilities,
            repairedSessions = own.repairedSessions,
            outstandingRestores = own.outstandingRestores,
            isRestoring = own.isRestoring,
            message = own.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        initialValue = HomeUiState(),
    )

    init {
        viewModelScope.launch {
            // Already done by MainActivity in the normal case, so this returns the cached findings
            // rather than repeating the repair pass. It is here for the case where it is not: a
            // process restored straight into a saved back stack reaches a ViewModel before the
            // Activity's own launch has finished.
            val report = startup.run()
            local.value = local.value.copy(repairedSessions = report.repairedSessions)
        }
        viewModelScope.launch {
            capabilityChecker.capabilities.collect { capabilities ->
                local.value = local.value.copy(capabilities = capabilities)
            }
        }
        viewModelScope.launch {
            // Cheap when the probe ran during startup: this returns the cached answer unless something
            // has invalidated it, which is what makes it safe to call on every dashboard open. The
            // result arrives through the collector above.
            capabilityChecker.current()
        }
        viewModelScope.launch {
            // A game ending is the moment the restore table can have changed, so the count is re-read
            // rather than taken from the last report. Authoritative in a way the report is not: a
            // restore the user has since applied by hand is already gone from the table, and a report
            // held in memory would keep offering to undo it. Also covers the first emission, which is
            // the launch's own state.
            coordinator.gaming
                .map { it.isTracking }
                .distinctUntilChanged()
                .collect { isTracking ->
                    if (!isTracking) {
                        local.value = local.value.copy(
                            outstandingRestores = optimizations.pendingRestoreCount(),
                        )
                    }
                }
        }
    }

    // ------------------------------------------------------------------------------ overlay toggles

    /** The stats pill, from the dashboard's own switch. Ignored without the permission. */
    fun setStatsOverlay(visible: Boolean) {
        if (visible && !overlay.hasPermission()) {
            local.value = local.value.copy(message = OVERLAY_PERMISSION_MESSAGE)
            return
        }
        overlay.setPill(visible)
    }

    fun setFloatingButton(visible: Boolean) {
        if (visible && !overlay.hasPermission()) {
            local.value = local.value.copy(message = OVERLAY_PERMISSION_MESSAGE)
            return
        }
        overlay.setButton(visible)
    }

    /** Takes every window down and stops the service with it. The panic button. */
    fun hideOverlays() = overlay.hideAll()

    // ------------------------------------------------------------------------- outstanding restores

    /**
     * Puts back the device settings a killed session never restored.
     *
     * Offered rather than done at launch, for the reason [StartupCoordinator] gives: the user may still
     * be playing, and dropping them out of 120 Hz unasked is worse than the state being wrong.
     */
    fun restoreOutstanding() {
        if (local.value.isRestoring) return
        local.value = local.value.copy(isRestoring = true, message = null)
        viewModelScope.launch {
            val report = optimizations.restoreAll()
            local.value = local.value.copy(
                isRestoring = false,
                outstandingRestores = report.outstanding,
                message = when {
                    report.didNothing -> "There was nothing left to put back."
                    report.isComplete -> "Restored ${Formatters.count(report.restored, "setting")}."
                    else -> "Restored ${report.restored} of ${report.restored + report.outstanding}. " +
                        "The rest need an access GameCore does not currently have."
                },
            )
            // A restore can change what is available — a refresh rate released back to the panel's
            // default, battery saver switched back on — so the cached capability answer is now a guess.
            capabilityChecker.invalidate()
        }
    }

    /**
     * Forgets the outstanding restore points without applying them.
     *
     * For the user who has already put the settings back by hand, or who does not want them changed.
     * The rows are dropped rather than hidden, so the card does not return on the next launch.
     */
    fun forgetOutstanding() {
        viewModelScope.launch {
            optimizations.forgetPending()
            local.value = local.value.copy(
                outstandingRestores = 0,
                message = "Left as they are. GameCore will not change them back.",
            )
        }
    }

    /** Clears the repaired-session notice, which is information rather than a task. */
    fun dismissRepairNotice() {
        local.value = local.value.copy(repairedSessions = 0)
    }

    fun dismissMessage() {
        local.value = local.value.copy(message = null)
    }

    // ------------------------------------------------------------------------------- current session

    /** Ends the tracked session from the dashboard. The game itself is left alone. */
    fun stopSession() = coordinator.stopCurrent(StopReason.STOPPED_BY_USER)

    private companion object {
        /**
         * How long [state] stays alive after the screen stops collecting.
         *
         * Long enough to survive a configuration change and a navigation to a detail screen and back,
         * short enough that the flows it observes are not held open behind a backgrounded app.
         */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L

        const val OVERLAY_PERMISSION_MESSAGE =
            "Drawing over other apps has not been granted yet. The Permissions screen has the switch."
    }
}

/** The part of the dashboard's state this ViewModel owns rather than observes. */
private data class LocalState(
    val capabilities: DeviceCapabilities = DeviceCapabilities.UNKNOWN,
    val repairedSessions: Int = 0,
    val outstandingRestores: Int = 0,
    val isRestoring: Boolean = false,
    val message: String? = null,
)

/**
 * One sample, converted into the seven tiles §5 asks for.
 *
 * The whole of the snapshot-to-string conversion for this screen, in one pure function — which is what
 * makes the dashboard's honesty testable without a device: given a snapshot whose CPU reading is
 * `Restricted`, the CPU tile's value is `—` and its detail is the reason, and no arrangement of the UI
 * can turn that into a number.
 *
 * Where a figure is always present — RAM total, screen size, storage — [readoutOf] is used directly.
 * Those are not [com.gamecore.core.common.Observed] because the platform cannot fail to report them
 * without the process being in a state where nothing else works either.
 */
private fun readoutsOf(snapshot: PerformanceSnapshot?): HomeReadouts {
    if (snapshot == null) return HomeReadouts.AWAITING

    val cpu = snapshot.cpu
    val memory = snapshot.memory
    val battery = snapshot.battery
    val display = snapshot.display
    val storage = snapshot.storage
    val thermal = snapshot.thermal.status.valueOrNull

    return HomeReadouts(
        tiles = listOf(
            cpu.overallPercent.readout(
                label = HomeLabels.CPU,
                fractionOf = { it / 100f },
                detailOf = { Formatters.count(cpu.coreCount, "core") },
                format = { Formatters.percentValue(it) },
            ),
            readoutOf(
                label = HomeLabels.MEMORY,
                value = Formatters.percent(memory.usedFraction),
                detail = Formatters.memoryPair(memory.usedBytes, memory.totalBytes),
                fraction = memory.usedFraction,
                tone = memoryTone(memory),
            ),
            readoutOf(
                label = HomeLabels.BATTERY,
                value = "${battery.levelPercent}%",
                detail = batteryDetail(battery),
                fraction = battery.levelPercent / 100f,
                tone = batteryTone(battery),
            ),
            snapshot.primaryTemperatureDeciCelsius.readout(
                label = HomeLabels.TEMPERATURE,
                tone = temperatureTone(snapshot.primaryTemperatureDeciCelsius.valueOrNull, thermal),
                detailOf = { temperatureDetail(snapshot, thermal) },
                format = { Formatters.temperature(it) },
            ),
            display.currentRefreshRate.readout(
                label = HomeLabels.REFRESH,
                detailOf = { refreshDetail(display) },
                format = { Formatters.hertz(it) },
            ),
            readoutOf(
                label = HomeLabels.DISPLAY,
                value = Formatters.resolution(display.widthPixels, display.heightPixels),
                detail = "${display.densityDpi} dpi${if (display.isHdr) " · HDR" else ""}",
            ),
            readoutOf(
                label = HomeLabels.STORAGE,
                value = Formatters.percent(storage.usedFraction),
                detail = "${Formatters.bytes(storage.availableBytes)} free",
                fraction = storage.usedFraction,
                tone = if (storage.usedFraction >= TIGHT_STORAGE_FRACTION) Tone.Warning else Tone.Neutral,
            ),
        ),
        // Only the levels at which the platform is actually clamping performance. "Normal" and "Light"
        // are not news, and a banner that is always there is a banner nobody reads.
        thermalNote = thermal?.takeIf { it.warrantsAlert }?.explanation,
        hasSample = true,
    )
}

// The tone and detail rules live in ui/components/Readings.kt, shared with the performance screen and
// the session report: the same reading has to be the same colour everywhere it appears.
