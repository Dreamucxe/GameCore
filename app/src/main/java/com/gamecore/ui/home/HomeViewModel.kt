package com.gamecore.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.Observed
import com.gamecore.core.common.SampleRingBuffer
import com.gamecore.core.common.isAvailable
import com.gamecore.core.common.isAwaitingSample
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.DeviceCapabilities
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.HeroCandidate
import com.gamecore.core.model.HeroSelection
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.model.PlayRecord
import com.gamecore.core.model.StopReason
import com.gamecore.core.model.ThermalClassifier
import com.gamecore.core.model.ThermalSensorType
import com.gamecore.core.model.lastPlayedByPackage
import com.gamecore.core.model.profileChips
import com.gamecore.core.model.selectHero
import com.gamecore.core.model.temperatureCaption
import com.gamecore.core.shizuku.ShizukuManager
import com.gamecore.core.system.AppLauncher
import com.gamecore.core.system.InstalledAppLister
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.GameProfileRepository
import com.gamecore.data.repository.SessionRepository
import com.gamecore.domain.StartupCoordinator
import com.gamecore.domain.gaming.GameDetector
import com.gamecore.domain.gaming.GamingCoordinator
import com.gamecore.domain.gaming.ProfileApplier
import com.gamecore.domain.monitoring.PerformanceMonitor
import com.gamecore.domain.network.PreLaunchNetworkCheck
import com.gamecore.domain.optimization.DeviceCapabilityChecker
import com.gamecore.domain.optimization.OptimizationManager
import com.gamecore.domain.overlay.OverlayController
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.PendingLaunch
import com.gamecore.ui.components.Readout
import com.gamecore.ui.components.TIGHT_STORAGE_FRACTION
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.batteryDetail
import com.gamecore.ui.components.batteryTone
import com.gamecore.ui.components.memoryTone
import com.gamecore.ui.components.readout
import com.gamecore.ui.components.readoutOf
import com.gamecore.ui.components.refreshDetail
import com.gamecore.ui.components.temperatureTone
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home's state, its live figures, and the actions it offers.
 *
 * [readouts] is a *cold* flow deliberately. [PerformanceMonitor.snapshots] only samples while it has a
 * collector, and `map` passes a subscription straight through, so the screen's
 * `collectAsStateWithLifecycle` is what starts and stops the sampler. A `stateIn(viewModelScope)` here
 * would hold that subscription for as long as the back stack entry lives, and the app would quietly
 * sample forever after the user's first visit to Home.
 *
 * The §4.3 sparklines ride on that same subscription and nothing else. Their two [SampleRingBuffer]s are
 * filled inside the `map` — one entry per snapshot the monitor already produced — and emptied by
 * `onStart` and `onCompletion`, which run exactly when the screen's lifecycle collector starts and
 * stops. So there is no second sampler, no timer behind a hidden screen, and no database write: the
 * trend exists while Home is on screen and is gone the moment it is not, which is what §4 asks for.
 *
 * [state] is the opposite case and is hot: it is assembled from flows that are cheap to observe and
 * expensive to re-derive, and losing it on a configuration change would flash the whole screen back to
 * its empty defaults.
 *
 * The hero is decided here and not in the screen. [selectHero] needs the recorded session history, which
 * only the ViewModel can reach, and its answer is handed down as a package name in
 * [HomeUiState.autoHeroPackage] so the composable's only job is to draw the profile it names.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val monitor: PerformanceMonitor,
    private val overlay: OverlayController,
    private val optimizations: OptimizationManager,
    private val capabilityChecker: DeviceCapabilityChecker,
    private val coordinator: GamingCoordinator,
    private val profiles: GameProfileRepository,
    private val installedApps: InstalledAppLister,
    private val launcher: AppLauncher,
    private val applier: ProfileApplier,
    private val detector: GameDetector,
    private val preLaunchCheck: PreLaunchNetworkCheck,
    private val preferences: SecurePreferenceStore,
    startup: StartupCoordinator,
    shizuku: ShizukuManager,
    sessions: SessionRepository,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())

    // ------------------------------------------------------------------------------- the sparklines

    /** Memory used, as a fraction, one entry per snapshot while Home is on screen. */
    private val memoryTrend = SampleRingBuffer(TREND_SAMPLES)

    /** The primary temperature in tenths of a degree, same cadence. Gaps are skipped, never zeroed. */
    private val temperatureTrend = SampleRingBuffer(TREND_SAMPLES)

    /**
     * The `capturedAtMillis` of the newest snapshot the trends have taken.
     *
     * [PerformanceMonitor.snapshots] is a `StateFlow`, and a `StateFlow` replays its last value to every
     * new collector — so the first thing a re-opened Home receives is the *previous* visit's final
     * sample, possibly minutes old. It is a real reading, and the tiles may show it until the next tick
     * arrives as they always have; but a sparkline has no time axis, and an old point followed a second
     * later by a fresh one would draw a step that never happened. `onStart` sets this watermark to that
     * stale sample's timestamp, and only snapshots captured after it join the trend.
     */
    private var trendWatermark = 0L

    val readouts: Flow<HomeReadouts> = monitor.snapshots
        .onStart { resetTrends() }
        .map { snapshot -> readoutsOf(snapshot) }
        .onCompletion { resetTrends() }

    // --------------------------------------------------------------------------------- the profiles

    /**
     * The profile rows, rebuilt once per repository emission.
     *
     * Its own `map` rather than work inside the [combine] below, because building a row asks the package
     * manager whether the game is still installed. Folding that into the combine would re-query every
     * package every time the gaming state or a local flag changed — which is at the start and end of
     * every session, and on every message the screen posts.
     */
    private val rows: Flow<List<HomeProfile>> =
        profiles.profiles.map { saved -> saved.map { rowFor(it) } }

    /**
     * When each package was last played, from finished sessions.
     *
     * `distinctUntilChanged` because the session table changes for reasons that leave this map exactly
     * as it was — a session's aggregates being written at its end, an old row being deleted from history
     * — and each of those would otherwise re-run the hero rule and re-emit the whole state for nothing.
     */
    private val lastPlayed: Flow<Map<String, Long>> = sessions.sessions
        .map { list -> lastPlayedByPackage(list.map { PlayRecord(it.packageName, it.startedAtMillis) }) }
        .distinctUntilChanged()

    /**
     * The rows and the hero rule's answer, as one value.
     *
     * Combined here, ahead of the main [combine], for two reasons: it keeps that combine at five typed
     * sources, and it means the hero is recomputed only when a profile or the history actually changes
     * rather than on every state tick.
     */
    private val profileSet: Flow<ProfileSet> = combine(rows, lastPlayed) { list, history ->
        val hero = selectHero(list.map { HeroCandidate(it.packageName, it.isEnabled) }, history)
        ProfileSet(profiles = list, heroPackage = (hero as? HeroSelection.Profile)?.packageName)
    }

    val state: StateFlow<HomeUiState> = combine(
        profileSet,
        shizuku.state,
        overlay.status,
        coordinator.gaming,
        local,
    ) { set, shizukuState, overlayStatus, gaming, own ->
        HomeUiState(
            profiles = set.profiles,
            isLoaded = true,
            autoHeroPackage = set.heroPackage,
            selectedPackage = own.selectedPackage,
            shizuku = shizukuState,
            overlay = overlayStatus,
            gaming = gaming,
            capabilities = own.capabilities,
            autoApply = own.autoApply,
            detectionAvailable = own.detectionAvailable,
            isCompact = own.isCompact,
            repairedSessions = own.repairedSessions,
            outstandingRestores = own.outstandingRestores,
            isRestoring = own.isRestoring,
            busyPackage = own.busyPackage,
            pendingLaunch = own.pendingLaunch,
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
            local.update { it.copy(repairedSessions = report.repairedSessions) }
        }
        viewModelScope.launch {
            capabilityChecker.capabilities.collect { capabilities ->
                local.update { it.copy(capabilities = capabilities) }
            }
        }
        viewModelScope.launch {
            // Cheap when the probe ran during startup: this returns the cached answer unless something
            // has invalidated it, which is what makes it safe to call on every Home open. The result
            // arrives through the collector above.
            capabilityChecker.current()
        }
        viewModelScope.launch {
            // The two stored settings this screen reads: §4.1's pill needs auto-apply, §3's spacing
            // needs the density choice. Collected into local state rather than added to the combine,
            // which is already at its five typed sources.
            preferences.settings.collect { settings ->
                local.update {
                    it.copy(isCompact = settings.compactDensity, autoApply = settings.autoApplyProfiles)
                }
            }
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
                        val pending = optimizations.pendingRestoreCount()
                        local.update { it.copy(outstandingRestores = pending) }
                    }
                }
        }
        refreshDetection()
    }

    /**
     * Re-asks whether foreground detection is possible.
     *
     * Called on every resume, not only on entry. The remedy for "Not watching" is a trip to a system
     * settings page, and the user comes back to a composable that never left composition — so without
     * this the §4.1 pill would keep saying "Not watching" after the access had been granted.
     */
    fun refreshDetection() {
        viewModelScope.launch {
            // Read after the call, never as an argument inside the `copy(...)`: the receiver is
            // evaluated before the argument, so the inline form would capture the state from before the
            // suspend and write it back over anything an action landed in the meantime.
            val detection = detector.availability()
            local.update { it.copy(detectionAvailable = detection.isAvailable) }
        }
    }

    private suspend fun rowFor(profile: GameProfile) = HomeProfile(
        packageName = profile.packageName,
        label = profile.label,
        isEnabled = profile.isEnabled,
        isInstalled = installedApps.isInstalled(profile.packageName),
        // The same generator the Games cards use, so one profile cannot describe itself two ways on two
        // screens. `changesNothing` is read off the profile beside it rather than inferred from the chip
        // list: the two deliberately disagree on a fresh profile, and the card needs both to be honest.
        chips = profileChips(profile),
        changesNothing = profile.changesNothing,
    )

    // ------------------------------------------------------------------------------------- the hero

    /**
     * Features a different profile, from a §4.2 pager dot.
     *
     * The user's pick, kept apart from the rule's answer — see [HomeUiState.selectedPackage] for why the
     * two are separate fields. Nothing is stored: §4 says the hero adds no new saved data, and a
     * preference for "which card was showing" would be exactly that. It lasts as long as this ViewModel.
     */
    fun selectProfile(packageName: String) {
        local.update { it.copy(selectedPackage = packageName) }
    }

    /**
     * Starts the featured game.
     *
     * Starting it and nothing else, which is why the button says "Start game" rather than "Play" or
     * "Optimize": [AppLauncher.launch] launches the package, and applying the profile is the separate
     * menu item beside it. §4.2 asks for the button to be labelled for what it really does, and the two
     * are kept apart for the reason the Games screen keeps them apart — with automatic application on,
     * the detector applies the profile a moment after the game reaches the front, and doing it here as
     * well would mean one tap that changed the refresh rate on some devices and not others.
     *
     * Only a failure is reported. A launch that worked is already obvious — the game is on screen — and
     * a message posted behind it would be read on the way back out, minutes later, as news.
     *
     * The §C4 network check sits in front of the launch, not around it. It measures only when this game's
     * profile asked for it, it is time-boxed, and a warning holds the launch in [HomeUiState.pendingLaunch]
     * for the user to decide rather than cancelling it — see [PreLaunchNetworkCheck].
     */
    fun play(packageName: String) {
        if (local.value.busyPackage != null) return
        viewModelScope.launch {
            val profile = profiles.profileFor(packageName)
            val warning = profile?.let { preLaunchCheck.evaluate(it) }
            if (warning != null) {
                local.update {
                    it.copy(pendingLaunch = PendingLaunch(packageName, warning.reason))
                }
                return@launch
            }
            start(packageName)
        }
    }

    /**
     * Starts the game, reporting only a refusal.
     *
     * Split out of [play] so the §C4 dialog's "Launch anyway" and "Don't warn for this game" reach exactly
     * the same launch the unwarned path does — a second call site would be a second chance to diverge.
     */
    private suspend fun start(packageName: String) {
        val outcome = launcher.launch(packageName)
        if (!outcome.isApplied) {
            local.update { it.copy(message = outcome.message) }
        }
    }

    /** Launches the game the §C4 warning is holding, leaving the profile's setting alone. */
    fun confirmPendingLaunch() {
        val pending = local.value.pendingLaunch ?: return
        local.update { it.copy(pendingLaunch = null) }
        viewModelScope.launch { start(pending.packageName) }
    }

    /**
     * Launches the game and stops this profile asking again.
     *
     * For the user whose connection GameCore will always rate poor. Only the pre-launch warning is turned
     * off — the check itself and the in-session alert are separate switches and are left as they are.
     */
    fun dontWarnPendingLaunch() {
        val pending = local.value.pendingLaunch ?: return
        local.update { it.copy(pendingLaunch = null) }
        viewModelScope.launch {
            profiles.profileFor(pending.packageName)?.let { profile ->
                profiles.save(profile.copy(networkPreLaunchWarn = false))
            }
            start(pending.packageName)
        }
    }

    /** Drops the held launch. Nothing starts and nothing is saved. */
    fun dismissPendingLaunch() {
        local.update { it.copy(pendingLaunch = null) }
    }

    /**
     * Applies the featured profile now, without waiting for the game to start.
     *
     * The same path the Games screen uses, verbatim. Every settings write it attempts is reported back
     * through [com.gamecore.core.model.ProfileApplication.summary] — including the ones that were
     * refused. A profile that could not change the refresh rate says so here rather than flashing
     * "Applied" and leaving the user to notice the panel is still at 60 Hz.
     */
    fun applyNow(packageName: String) {
        if (local.value.busyPackage != null) return
        local.update { it.copy(busyPackage = packageName, message = null) }
        viewModelScope.launch {
            val profile = profiles.profileFor(packageName)
            if (profile == null) {
                local.update { it.copy(busyPackage = null, message = "That profile is no longer saved.") }
                return@launch
            }
            val application = applier.apply(profile)
            local.update {
                it.copy(
                    busyPackage = null,
                    message = when {
                        application.changedNothing -> "This profile has nothing to apply yet."
                        else -> application.summary()
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------------------------ overlay toggles

    /** The stats pill, from Home's own switch. Ignored without the permission. */
    fun setStatsOverlay(visible: Boolean) {
        if (visible && !overlay.hasPermission()) {
            local.update { it.copy(message = OVERLAY_PERMISSION_MESSAGE) }
            return
        }
        overlay.setPill(visible)
    }

    fun setFloatingButton(visible: Boolean) {
        if (visible && !overlay.hasPermission()) {
            local.update { it.copy(message = OVERLAY_PERMISSION_MESSAGE) }
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
        local.update { it.copy(isRestoring = true, message = null) }
        viewModelScope.launch {
            val report = optimizations.restoreAll()
            local.update {
                it.copy(
                    isRestoring = false,
                    outstandingRestores = report.outstanding,
                    message = listOfNotNull(
                        when {
                            report.didNothing -> "There was nothing left to put back."
                            // Nothing to claim credit for: everything pending turned out to be the user's.
                            report.restored == 0 && report.isComplete -> null
                            report.isComplete ->
                                "Restored ${Formatters.count(report.restored, "setting")}."
                            else ->
                                "Restored ${report.restored} of ${report.restored + report.outstanding}. " +
                                    "The rest need an access GameCore does not currently have."
                        },
                        report.keptNote,
                    ).joinToString(" "),
                )
            }
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
            local.update {
                it.copy(
                    outstandingRestores = 0,
                    message = "Left as they are. GameCore will not change them back.",
                )
            }
        }
    }

    /** Clears the repaired-session notice, which is information rather than a task. */
    fun dismissRepairNotice() {
        local.update { it.copy(repairedSessions = 0) }
    }

    fun dismissMessage() {
        local.update { it.copy(message = null) }
    }

    // ------------------------------------------------------------------------------ current session

    /** Ends the tracked session from Home. The game itself is left alone. */
    fun stopSession() = coordinator.stopCurrent(StopReason.STOPPED_BY_USER)

    /**
     * Clears the launch's memory summary.
     *
     * Delegated rather than tracked here: the report is a fact about the launch and not about this
     * screen, and [LocalState] is only for the things this ViewModel is the sole owner of.
     */
    fun dismissReclaim() = coordinator.dismissReclaim()

    // ------------------------------------------------------------------------------------- readouts

    /**
     * Empties both trends and sets the watermark to whatever the monitor is holding right now.
     *
     * Runs on the collector's thread — the screen's lifecycle collector, on Main — which is the only
     * thread [SampleRingBuffer] is touched from, so its single-thread contract holds without a lock.
     */
    private fun resetTrends() {
        memoryTrend.clear()
        temperatureTrend.clear()
        trendWatermark = monitor.snapshots.value?.capturedAtMillis ?: 0L
    }

    /**
     * One sample, converted into the tiles §4 asks for and the two trends beside them.
     *
     * The whole of the snapshot-to-string conversion for this screen, in one function — which is what
     * makes Home's honesty checkable without a device: given a snapshot whose CPU reading is
     * `Restricted`, the CPU tile's value is `—` and its detail is the reason, and no arrangement of the
     * UI can turn that into a number.
     *
     * Where a figure is always present — RAM total, screen size, storage — [readoutOf] is used directly.
     * Those are not [Observed] because the platform cannot fail to report them without the process being
     * in a state where nothing else works either.
     */
    private fun readoutsOf(snapshot: PerformanceSnapshot?): HomeReadouts {
        if (snapshot == null) return HomeReadouts.AWAITING

        val cpu = snapshot.cpu
        val memory = snapshot.memory
        val battery = snapshot.battery
        val display = snapshot.display
        val storage = snapshot.storage
        val thermal = snapshot.thermal.status.valueOrNull
        val temperature = snapshot.primaryTemperatureDeciCelsius

        // Real samples only, and only ones newer than the watermark (see trendWatermark). A temperature
        // the device did not report is a gap the sparkline skips, never a zero it draws to the floor.
        if (snapshot.capturedAtMillis > trendWatermark) {
            trendWatermark = snapshot.capturedAtMillis
            memoryTrend.add(memory.usedFraction)
            temperature.valueOrNull?.let { temperatureTrend.add(it.toFloat()) }
        }

        // Which sensor produced the figure selects the threshold set: a battery at 50 °C is a problem
        // where a SoC at 50 °C is idle-warm. The classification below is the ONE source for both the
        // tile's colour and its word, so the two cannot disagree — the "85.3 °C labelled Normal" bug.
        val sensor = if (snapshot.thermal.cpuTemperatureDeciCelsius.isAvailable) {
            ThermalSensorType.CPU
        } else {
            ThermalSensorType.BATTERY
        }
        val classification = ThermalClassifier.classify(temperature.valueOrNull, sensor, thermal)

        return HomeReadouts(
            metrics = listOf(
                cpuReadout(cpu.overallPercent, cpu.coreCount),
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
                temperature.readout(
                    label = HomeLabels.TEMPERATURE,
                    tone = temperatureTone(temperature.valueOrNull, thermal, sensor),
                    // §4.3: the classifier's word, plus the platform's own status where the device
                    // reports one. Both are printed even when they differ, and the word beside the
                    // figure is always the classifier's, so it can never contradict the colour.
                    detailOf = { temperatureCaption(sensor, classification.level, thermal) },
                    format = { Formatters.temperature(it) },
                ),
                display.currentRefreshRate.readout(
                    label = HomeLabels.REFRESH,
                    detailOf = { refreshDetail(display) },
                    format = { Formatters.hertz(it) },
                ),
            ),
            device = listOf(
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
                    tone = if (storage.usedFraction >= TIGHT_STORAGE_FRACTION) {
                        Tone.Warning
                    } else {
                        Tone.Neutral
                    },
                ),
            ),
            memoryTrend = memoryTrend.snapshot(),
            temperatureTrend = temperatureTrend.snapshot(),
            // Only the levels at which the platform is actually clamping performance. "Normal" and
            // "Light" are not news, and a banner that is always there is a banner nobody reads.
            thermalNote = thermal?.takeIf { it.warrantsAlert }?.explanation,
            hasSample = true,
        )
    }

    private companion object {
        /**
         * How long [state] stays alive after the screen stops collecting.
         *
         * Long enough to survive a configuration change and a navigation to a detail screen and back,
         * short enough that the flows it observes are not held open behind a backgrounded app.
         */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L

        /**
         * How many snapshots each sparkline keeps: at the monitor's default interval, the last couple
         * of minutes. §4's "e.g. 60" — small, capped, in memory only, discarded when Home is hidden.
         */
        const val TREND_SAMPLES = 60

        const val OVERLAY_PERMISSION_MESSAGE =
            "Drawing over other apps has not been granted yet. The Permissions screen has the switch."
    }
}

/** The part of Home's state this ViewModel owns rather than observes. */
private data class LocalState(
    val capabilities: DeviceCapabilities = DeviceCapabilities.UNKNOWN,
    val repairedSessions: Int = 0,
    val outstandingRestores: Int = 0,
    val isRestoring: Boolean = false,
    val autoApply: Boolean = true,
    val detectionAvailable: Boolean = false,
    val isCompact: Boolean = false,
    val selectedPackage: String? = null,
    val busyPackage: String? = null,
    val pendingLaunch: PendingLaunch? = null,
    val message: String? = null,
)

/** The profile rows and the hero rule's answer, emitted together so the two are never out of step. */
private data class ProfileSet(
    val profiles: List<HomeProfile>,
    val heroPackage: String?,
)

/**
 * The CPU tile, with the one departure from [readout] that §4.3 asks for by name.
 *
 * [readout] draws a rate still waiting for its second sample as `…` with no reason — right for the
 * refresh-rate tile, whose figure is one interval away and needs no paragraph beside it. §4.3 rules that
 * out for CPU specifically: a real value, or unavailable with the real reason, never an ellipsis. So the
 * awaiting case gets the em dash and [HomeLabels.MEASURING_CPU], the same treatment every other absence
 * on this screen gets, while the value and restricted cases go through [readout] unchanged — the honesty
 * guarantee there still holds, and a restricted reading still cannot reach the formatter.
 */
private fun cpuReadout(percent: Observed<Float>, coreCount: Int): Readout =
    if (percent.isAwaitingSample) {
        readoutOf(
            label = HomeLabels.CPU,
            value = ABSENT,
            detail = HomeLabels.MEASURING_CPU,
            tone = Tone.Muted,
        )
    } else {
        percent.readout(
            label = HomeLabels.CPU,
            fractionOf = { it / 100f },
            detailOf = { Formatters.count(coreCount, "core") },
            format = { Formatters.percentValue(it) },
        )
    }

// The tone and detail rules live in ui/components/Readings.kt, shared with the performance screen and
// the session report: the same reading has to be the same colour everywhere it appears.
