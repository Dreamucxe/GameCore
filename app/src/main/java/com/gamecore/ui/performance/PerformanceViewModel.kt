package com.gamecore.ui.performance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.Observed
import com.gamecore.core.common.unavailabilityText
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.BatteryReading
import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.DeviceCapabilities
import com.gamecore.core.model.FrameRateCapability
import com.gamecore.core.model.FrameRateSample
import com.gamecore.core.model.MetricHistory
import com.gamecore.core.model.OptimizationAction
import com.gamecore.core.model.OptimizationResult
import com.gamecore.core.model.PerformanceMode
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.model.ThermalStatus
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.domain.BackgroundServiceGate
import com.gamecore.domain.gaming.GamingCoordinator
import com.gamecore.domain.monitoring.BatteryTrend
import com.gamecore.domain.monitoring.DrainConfidence
import com.gamecore.domain.monitoring.NetworkMonitor
import com.gamecore.domain.monitoring.PerformanceMonitor
import com.gamecore.domain.monitoring.StabilityReport
import com.gamecore.domain.optimization.DeviceCapabilityChecker
import com.gamecore.domain.optimization.OptimizationManager
import com.gamecore.domain.optimization.OptimizationRequest
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.PENDING
import com.gamecore.ui.components.Readout
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
import java.util.Locale
import javax.inject.Inject

/**
 * The live figures, the graphs, and the small set of device changes a user can make without a profile.
 *
 * Two flows with two different lifetimes, for the reason §26 gives. [live] is **cold**:
 * [PerformanceMonitor.snapshots] samples only while something collects it, and `combine` passes a
 * subscription straight through, so the screen's `collectAsStateWithLifecycle` is what starts the
 * sampling loop and — the part that matters — stops it when the user navigates away. A
 * `stateIn(viewModelScope)` here would keep sampling behind a backgrounded app, in a screen whose whole
 * subject is battery and heat. [state] is the opposite: cheap to observe, expensive to re-derive, and
 * kept hot so a rotation does not flash the page back to "not supported".
 *
 * Nothing in this class touches a `Settings.System` key, a shell, or a `WindowManager`. A rate change is
 * an [OptimizationRequest] handed to [OptimizationManager], which records the previous value before it
 * writes and reports back whether the device actually moved — so the screen can say "not confirmed"
 * where that is the truth, which is the §24B requirement the refresh-rate card exists for.
 */
@HiltViewModel
class PerformanceViewModel @Inject constructor(
    private val monitor: PerformanceMonitor,
    private val network: NetworkMonitor,
    private val optimizations: OptimizationManager,
    private val capabilityChecker: DeviceCapabilityChecker,
    private val preferences: SecurePreferenceStore,
    private val services: BackgroundServiceGate,
    private val coordinator: GamingCoordinator,
) : ViewModel() {

    /** The parts of the state this ViewModel owns or polls, rather than observes continuously. */
    private data class LocalState(
        val capabilities: DeviceCapabilities = DeviceCapabilities.UNKNOWN,
        val frameRate: FrameRateCapability =
            FrameRateCapability.Unavailable(FrameRateCapability.NO_GAME_SELECTED),
        val stability: Observed<StabilityReport> =
            Observed.awaitingSample("No stability measurement has been taken yet."),
        val isMeasuringStability: Boolean = false,
        val statuses: Map<OptimizationAction, CapabilityStatus> = emptyMap(),
        val results: List<OptimizationResult> = emptyList(),
        val isApplying: Boolean = false,
        val pendingRestores: Int = 0,
        val isRestoring: Boolean = false,
        val message: String? = null,
    )

    private val local = MutableStateFlow(LocalState())

    /**
     * One sample, the rolling window it belongs to, and the battery window that outlives both.
     *
     * Cold. Collecting this is what makes the device measure anything.
     */
    val live: Flow<PerformanceLive> = combine(
        monitor.snapshots,
        monitor.history,
        monitor.batteryTrend,
    ) { snapshot, history, trend -> liveOf(snapshot, history, trend) }

    val state: StateFlow<PerformanceUiState> = combine(
        preferences.settings,
        coordinator.gaming,
        local,
    ) { settings, gaming, own ->
        PerformanceUiState(
            capabilities = own.capabilities,
            frameRate = own.frameRate,
            watchedGameLabel = gaming.gameLabel,
            stability = own.stability,
            isMeasuringStability = own.isMeasuringStability,
            statuses = own.statuses,
            results = own.results,
            isApplying = own.isApplying,
            pendingRestores = own.pendingRestores,
            isRestoring = own.isRestoring,
            sampleIntervalMillis = settings.sampleIntervalMillis,
            measureLatency = settings.measureLatency,
            backgroundMonitoring = settings.backgroundMonitoring,
            message = own.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        initialValue = PerformanceUiState(),
    )

    init {
        viewModelScope.launch {
            capabilityChecker.capabilities.collect { local.value = local.value.copy(capabilities = it) }
        }
        viewModelScope.launch {
            monitor.frameRateCapability.collect { local.value = local.value.copy(frameRate = it) }
        }
        viewModelScope.launch {
            network.report.collect { local.value = local.value.copy(stability = it) }
        }
        viewModelScope.launch {
            network.isMeasuring.collect { local.value = local.value.copy(isMeasuringStability = it) }
        }
        // Frame timing is read per game, so the probe is pointed at whatever is being tracked and at
        // nothing when nothing is. Without this the frame-rate card would say "start a game" during a
        // game, which is the opposite of the honesty it exists for.
        viewModelScope.launch {
            coordinator.gaming
                .map { it.playing }
                .distinctUntilChanged()
                .collect { monitor.watch(it) }
        }
        viewModelScope.launch {
            capabilityChecker.current()
            loadEngineState()
        }
    }

    // ------------------------------------------------------------------------------ the engine

    /**
     * Re-reads what the engine can do, cheaply.
     *
     * Called when the screen resumes, because the user may have come back from granting WRITE_SETTINGS
     * or connecting Shizuku. [DeviceCapabilityChecker.current] returns the cached answer unless
     * something invalidated it, so a resume costs a map of status lookups rather than a full probe.
     */
    fun onResume() {
        viewModelScope.launch {
            capabilityChecker.current()
            loadEngineState()
        }
    }

    /** The full probe, from the card's own button. What to press after changing something in Settings. */
    fun recheck() {
        viewModelScope.launch {
            capabilityChecker.refresh()
            loadEngineState()
        }
    }

    private suspend fun loadEngineState() {
        local.value = local.value.copy(
            statuses = optimizations.statuses(),
            pendingRestores = optimizations.pendingRestoreCount(),
        )
    }

    /**
     * Applies one change, and reports exactly what happened to it.
     *
     * The result replaces the previous list rather than appending: this is "what the last thing you
     * pressed did", and a growing log of six attempts is a screen the user has to scroll past.
     */
    fun apply(action: OptimizationAction) {
        applyRequests(listOf(OptimizationRequest.of(action)))
    }

    /**
     * Pins the panel to one of the rates it reports.
     *
     * The action is chosen by where the rate sits in the panel's own list, because the two pin actions
     * differ only in which rate they fall back to when none is named — [OptimizationRequest.rateHz]
     * outranks both. What the user is shown afterwards is the outcome's own sentence, which names the
     * rate the display ended up at, rather than the action's label.
     */
    fun pinRefreshRate(rateHz: Float) {
        val rates = local.value.capabilities.supportedRefreshRates
        val action = if (rates.isNotEmpty() && rateHz <= (rates.minOrNull() ?: rateHz)) {
            OptimizationAction.PIN_LOWEST_REFRESH_RATE
        } else {
            OptimizationAction.PIN_PEAK_REFRESH_RATE
        }
        applyRequests(listOf(OptimizationRequest.refreshRate(action, rateHz)))
    }

    fun releaseRefreshRate() {
        applyRequests(listOf(OptimizationRequest.of(OptimizationAction.RELEASE_REFRESH_RATE)))
    }

    /**
     * Applies a mode to the device now, outside any game profile.
     *
     * [PerformanceMode.BALANCED] is not a set of writes — its own explanation is "changes nothing" — so
     * it is honoured by putting back everything GameCore has changed. That is what "let the device's own
     * governor decide" means once something has been pinned, and it reuses the restore path rather than
     * inventing a second idea of what the default was.
     *
     * [PerformanceMode.CUSTOM] is not offered here: it means "the individual switches below", and the
     * screen prints that rather than giving it a button that does nothing.
     *
     * The mapping matches what a profile's mode contributes in
     * [com.gamecore.domain.gaming.ProfileApplier], which remains the authority for profiles — a profile
     * also has explicit fields, and those outrank its mode.
     */
    fun applyMode(mode: PerformanceMode) {
        when (mode) {
            PerformanceMode.BALANCED -> restore()
            PerformanceMode.PERFORMANCE -> applyRequests(
                listOf(
                    OptimizationRequest.of(OptimizationAction.PIN_PEAK_REFRESH_RATE),
                    OptimizationRequest.of(OptimizationAction.DISABLE_BATTERY_SAVER),
                    OptimizationRequest.of(OptimizationAction.DISABLE_ANIMATIONS),
                ),
            )
            PerformanceMode.BATTERY_SAVER -> applyRequests(
                listOf(
                    OptimizationRequest.of(OptimizationAction.PIN_LOWEST_REFRESH_RATE),
                    OptimizationRequest.of(OptimizationAction.ENABLE_BATTERY_SAVER),
                ),
            )
            PerformanceMode.CUSTOM -> local.value = local.value.copy(message = CUSTOM_MODE_MESSAGE)
        }
    }

    /**
     * Runs the requests and keeps their results.
     *
     * Sequential inside [OptimizationManager], which is where the ordering guarantee belongs. The
     * capability map is re-read afterwards because a change can alter what is available next: battery
     * saver off releases the rate cap it was holding, and a pinned rate changes what "release" means.
     */
    private fun applyRequests(requests: List<OptimizationRequest>) {
        if (local.value.isApplying) return
        local.value = local.value.copy(isApplying = true, message = null, results = emptyList())
        viewModelScope.launch {
            val results = optimizations.applyAll(requests)
            local.value = local.value.copy(isApplying = false, results = results)
            capabilityChecker.invalidate()
            capabilityChecker.current()
            loadEngineState()
        }
    }

    /**
     * Puts every setting GameCore changed back where it found it.
     *
     * The same operation the dashboard offers, on the screen where the changes were made. A row is only
     * cleared when the old value has gone back and been read back, so an incomplete restore says so and
     * stays available rather than being forgotten.
     */
    fun restore() {
        if (local.value.isRestoring) return
        local.value = local.value.copy(isRestoring = true, message = null, results = emptyList())
        viewModelScope.launch {
            val report = optimizations.restoreAll()
            local.value = local.value.copy(
                isRestoring = false,
                pendingRestores = report.outstanding,
                message = listOfNotNull(
                    when {
                        report.didNothing -> "There was nothing to put back."
                        // Nothing to claim credit for: everything pending turned out to be the user's.
                        report.restored == 0 && report.isComplete -> null
                        report.isComplete -> "Put back ${Formatters.count(report.restored, "setting")}."
                        else -> "Put back ${report.restored} of ${report.restored + report.outstanding}. " +
                            "The rest need an access GameCore does not currently have."
                    },
                    report.keptNote,
                ).joinToString(" "),
            )
            capabilityChecker.invalidate()
            capabilityChecker.current()
            loadEngineState()
        }
    }

    // ------------------------------------------------------------------------------- the network

    /**
     * Runs a burst of probes for the jitter figure.
     *
     * `connected` comes from the sample the screen is already showing rather than a fresh read, because
     * `NetworkReader` derives throughput from the delta between two counter readings and an extra read
     * here would shorten the interval the next sampled rate covers.
     */
    fun measureStability() {
        viewModelScope.launch {
            network.measure(connected = monitor.snapshots.value?.network?.isConnected == true)
        }
    }

    // -------------------------------------------------------------------------------- sampling

    /** Seconds, from the slider. The loop re-reads this each tick, so it takes effect without a restart. */
    fun setSampleIntervalSeconds(seconds: Int) {
        preferences.updateSettings { it.copy(sampleIntervalMillis = seconds * 1_000L) }
    }

    fun setMeasureLatency(enabled: Boolean) {
        preferences.updateSettings { it.copy(measureLatency = enabled) }
    }

    /**
     * Keeps the sampler running with nothing on screen, or stops it.
     *
     * The setting is written first and the service is synced from it, never the other way round:
     * [BackgroundServiceGate.wantsMonitoring] is the only predicate that decides whether that service
     * should exist, and the service watches the same setting itself, so switching this off takes it down
     * without this screen knowing a service was involved.
     *
     * §24B's Doze note is why turning it on says something. Android is entitled to stop a foreground
     * service on a device that has GameCore under battery optimization, and a user whose background
     * samples quietly stop should be told where the exemption lives rather than concluding the feature
     * is broken.
     */
    fun setBackgroundMonitoring(enabled: Boolean) {
        preferences.updateSettings { it.copy(backgroundMonitoring = enabled) }
        services.syncMonitoring(sessionActive = coordinator.gaming.value.isTracking)
        local.value = local.value.copy(
            message = if (enabled) BACKGROUND_MONITORING_MESSAGE else null,
        )
    }

    /** Empties the graph window. The battery window survives, because it measures the device, not a visit. */
    fun clearGraphs() = monitor.clearHistory()

    fun dismissMessage() {
        local.value = local.value.copy(message = null)
    }

    fun dismissResults() {
        local.value = local.value.copy(results = emptyList())
    }

    private companion object {
        /** Long enough to survive a rotation, short enough to stop observing when the screen is left. */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L

        const val CUSTOM_MODE_MESSAGE =
            "Custom is not a preset. Set the individual changes below, or put them in a game profile " +
                "so they apply when the game starts and come back off when it closes."

        const val BACKGROUND_MONITORING_MESSAGE =
            "Sampling will continue while GameCore is closed. If Android has this app under battery " +
                "optimization it may stop the service anyway — the Permissions screen has the " +
                "exemption, and an explanation of what it costs."
    }
}

/**
 * One sample, turned into every string and series the screen draws.
 *
 * Pure, top-level and total: the same input always produces the same page, and there is no device in
 * here to mock. That is §24A.2's practical benefit as much as its security one — the honesty rules on
 * this screen are the hardest part of the whole app to get right, and this is the function a unit test
 * can point at.
 *
 * `null` is the state before the first tick, which is a real state on a screen whose sampler starts when
 * the user arrives. [PerformanceLive.AWAITING] has the same shape as a real page, so nothing jumps.
 */
private fun liveOf(
    snapshot: PerformanceSnapshot?,
    history: MetricHistory,
    trend: BatteryTrend,
): PerformanceLive {
    if (snapshot == null) return PerformanceLive.AWAITING

    val memory = snapshot.memory
    val battery = snapshot.battery
    val thermal = snapshot.thermal.status.valueOrNull

    return PerformanceLive(
        tiles = listOf(
            snapshot.cpu.overallPercent.readout(
                label = PerformanceLabels.CPU,
                fractionOf = { it / 100f },
                detailOf = { Formatters.count(snapshot.cpu.coreCount, "core") },
                format = { Formatters.percentValue(it) },
            ),
            readoutOf(
                label = PerformanceLabels.MEMORY,
                value = Formatters.percent(memory.usedFraction),
                detail = Formatters.memoryPair(memory.usedBytes, memory.totalBytes),
                fraction = memory.usedFraction,
                tone = memoryTone(memory),
            ),
            snapshot.primaryTemperatureDeciCelsius.readout(
                label = PerformanceLabels.TEMPERATURE,
                tone = temperatureTone(snapshot.primaryTemperatureDeciCelsius.valueOrNull, thermal),
                detailOf = { temperatureDetail(snapshot, thermal) },
                format = { Formatters.temperature(it) },
            ),
            readoutOf(
                label = PerformanceLabels.BATTERY,
                value = "${battery.levelPercent}%",
                detail = batteryDetail(battery),
                fraction = battery.levelPercent / 100f,
                tone = batteryTone(battery),
            ),
        ),
        cpuSeries = history.cpuSeries(),
        memorySeries = history.memorySeries(),
        temperatureSeries = history.temperatureSeries(),
        frameRateSeries = history.frameRateSeries(),
        cpuRows = cpuRowsOf(snapshot),
        coreRows = coreRowsOf(snapshot),
        batteryRows = batteryRowsOf(battery, trend),
        thermalRows = thermalRowsOf(snapshot, thermal),
        networkRows = networkRowsOf(snapshot),
        displayRows = displayRowsOf(snapshot),
        frameRateRows = frameRateRowsOf(snapshot.frameRate),
        frameRateNote = snapshot.frameRate.unavailabilityText(),
        // Only the levels at which the platform is actually clamping performance. A banner that is
        // always there is a banner nobody reads.
        thermalNote = thermal?.takeIf { it.warrantsAlert }?.explanation,
        currentRefreshRate = snapshot.display.currentRefreshRate.valueOrNull,
        isConnected = snapshot.network.isConnected,
        hasSample = true,
    )
}

/**
 * The CPU card's rows.
 *
 * Four figures with four different failure modes, which is why each is built through [readout] rather
 * than a `?: 0`: `/proc/stat` is denied outright on several vendor kernels, `cpufreq` moved out of app
 * reach around Android 10, and `coreCount` is the one that cannot fail.
 */
private fun cpuRowsOf(snapshot: PerformanceSnapshot): List<Readout> {
    val cpu = snapshot.cpu
    return listOf(
        cpu.overallPercent.readout(
            label = "Total load",
            fractionOf = { it / 100f },
            format = { Formatters.percentValue(it, decimals = 1) },
        ),
        readoutOf(label = "Cores", value = Formatters.count(cpu.coreCount, "core")),
        cpu.frequenciesKHz.readout(
            label = "Fastest core now",
            detailOf = { cores -> "${cores.count { it.isOnline }} of ${cores.size} cores online" },
            format = { cores ->
                val current = cores.filter { it.isOnline }.maxOfOrNull { it.currentKHz }
                if (current == null) "Every core parked" else Formatters.frequencyKHz(current)
            },
        ),
        cpu.loadAverage.readout(
            label = "Load average",
            detailOf = { "Runnable processes over 1, 5 and 15 minutes" },
            format = { average ->
                listOf(average.oneMinute, average.fiveMinutes, average.fifteenMinutes)
                    .joinToString(" · ") { String.format(Locale.US, "%.2f", it) }
            },
        ),
        cpu.temperatureDeciCelsius.readout(
            label = "CPU zone",
            detailOf = { "From the first plausible CPU thermal zone, which is OEM-specific" },
            format = { Formatters.temperature(it) },
        ),
    )
}

/**
 * One row per core, from whichever of the two per-core sources this device exposes.
 *
 * Frequency first because it is the more informative of the two — a core sitting at its minimum under
 * load is a governor decision the user can see here and nowhere else — and a parked core says so rather
 * than reading 0 MHz, which would be mistaken for idle.
 *
 * Empty when neither source is readable. The card's "Fastest core now" row already carries the reason,
 * so an empty section here adds a heading with nothing under it and is left out instead.
 */
private fun coreRowsOf(snapshot: PerformanceSnapshot): List<Readout> {
    val cpu = snapshot.cpu
    cpu.frequenciesKHz.valueOrNull?.let { cores ->
        return cores.map { core ->
            readoutOf(
                label = "Core ${core.coreIndex}",
                value = if (core.isOnline) Formatters.frequencyKHz(core.currentKHz) else "Parked",
                detail = "${Formatters.frequencyKHz(core.minKHz)} – " +
                    Formatters.frequencyKHz(core.maxKHz),
                fraction = core.loadFraction,
                tone = if (core.isOnline) Tone.Neutral else Tone.Muted,
            )
        }
    }
    val perCore = cpu.perCorePercent.valueOrNull ?: return emptyList()
    return perCore.mapIndexed { index, percent ->
        readoutOf(
            label = "Core $index",
            value = Formatters.percentValue(percent),
            detail = "Utilisation",
            fraction = percent / 100f,
        )
    }
}

/**
 * The battery card's rows, ending with the one figure on this screen that is a measurement rather than a
 * reading.
 *
 * Everything above the drain rate is an instant: the level, the health, the voltage the fuel gauge
 * reports right now. The drain rate is the only thing here that took time to produce, and the four
 * confidence states are spelled out rather than collapsed into a dash — "plugged in" and "not yet" are
 * different sentences, and a screen that shows the same "—" for both is telling the user their phone is
 * broken.
 */
private fun batteryRowsOf(battery: BatteryReading, trend: BatteryTrend): List<Readout> = listOf(
    readoutOf(
        label = "Level",
        value = "${battery.levelPercent}%",
        detail = batteryDetail(battery),
        fraction = battery.levelPercent / 100f,
        tone = batteryTone(battery),
    ),
    readoutOf(
        label = "Status",
        value = battery.status.label,
        detail = battery.chargingSource.label,
    ),
    battery.health.readout(label = "Health", format = { it.label }),
    battery.temperatureDeciCelsius.readout(
        label = "Battery temperature",
        tone = temperatureTone(
            battery.temperatureDeciCelsius.valueOrNull,
            null,
            com.gamecore.core.model.ThermalSensorType.BATTERY,
        ),
        format = { Formatters.temperature(it) },
    ),
    battery.voltageMilliVolts.readout(label = "Voltage", format = { Formatters.voltage(it) }),
    battery.currentMicroAmps.readout(
        label = "Current",
        // A magnitude: the sign convention is not consistent across OEMs, so the direction comes from
        // the charging flag instead of from the number's sign.
        detailOf = { if (battery.isCharging) "Into the battery" else "Out of the battery" },
        format = { String.format(Locale.US, "%d mA", it / 1000) },
    ),
    drainRowOf(trend),
)

/**
 * The drain window as one row, and never an extrapolation the window is too short to support.
 *
 * [DrainConfidence] is the whole reason this is a function. `percentPerHour` withholds itself below five
 * minutes, so the row shows the measurement that does exist — points lost over a real interval — and
 * says what is still missing, rather than printing a rate that swings from 0 to 20 %/hour with one
 * quantisation step of the fuel gauge.
 */
private fun drainRowOf(trend: BatteryTrend): Readout {
    val label = "Drain rate"
    val drain = trend.drain()
    val observed = Formatters.durationCoarse(trend.observedMillis)
    return when (trend.confidence) {
        DrainConfidence.NO_READING -> readoutOf(
            label = label,
            value = PENDING,
            detail = "Nothing sampled yet.",
            tone = Tone.Muted,
        )
        DrainConfidence.CHARGING -> readoutOf(
            label = label,
            value = ABSENT,
            detail = "The device is charging, so there is no drain to measure. " +
                "Unplugging starts a new window.",
            tone = Tone.Muted,
        )
        DrainConfidence.MEASURING -> readoutOf(
            label = label,
            value = PENDING,
            detail = "Watching for ${observed}. A rate needs five minutes off charge before it " +
                "means anything" +
                (drain?.let { ", and ${Formatters.count(it.pointsLost, "point")} has been lost" } ?: "") +
                ".",
            tone = Tone.Muted,
        )
        DrainConfidence.MEASURED -> {
            val rate = drain?.percentPerHour
            if (rate == null) {
                readoutOf(label = label, value = ABSENT, tone = Tone.Muted)
            } else {
                readoutOf(
                    label = label,
                    value = String.format(Locale.US, "%.1f%% / hour", rate),
                    detail = "${drain.pointsLost}% over $observed of watching",
                    tone = if (rate >= HEAVY_DRAIN_PERCENT_PER_HOUR) Tone.Warning else Tone.Neutral,
                )
            }
        }
    }
}

/**
 * The thermal card: the platform's verdict first, then the raw zones behind it.
 *
 * In that order because only one of them is authoritative. A vendor zone reading 48 °C means nothing on
 * its own — the same figure is normal on one chipset and throttling on the next — and the zone names are
 * the vendor's own, never renamed to something friendlier, because guessing which component a
 * `tsens_tz_sensor12` measures is exactly the invention this app refuses.
 */
private fun thermalRowsOf(snapshot: PerformanceSnapshot, thermal: ThermalStatus?): List<Readout> {
    val reading = snapshot.thermal
    val rows = mutableListOf(
        reading.status.readout(
            label = "Platform status",
            tone = temperatureTone(null, thermal),
            detailOf = { it.explanation },
            format = { it.label },
        ),
        reading.cpuTemperatureDeciCelsius.readout(
            label = "Hottest CPU zone",
            tone = temperatureTone(reading.cpuTemperatureDeciCelsius.valueOrNull, thermal),
            format = { Formatters.temperature(it) },
        ),
    )
    val sensors = reading.sensors.valueOrNull
    if (sensors == null) {
        rows += reading.sensors.readout(
            label = "Sensors",
            format = { Formatters.count(it.size, "zone") },
        )
        return rows
    }
    val hottest = sensors.sortedByDescending { it.deciCelsius }.take(MAX_SENSOR_ROWS)
    hottest.forEach { sensor ->
        rows += readoutOf(
            label = sensor.label,
            value = Formatters.temperature(sensor.deciCelsius),
            detail = if (sensor.isCpuZone) "Name suggests a CPU or SoC zone" else null,
            tone = temperatureTone(sensor.deciCelsius, null),
        )
    }
    if (sensors.size > hottest.size) {
        rows += readoutOf(
            label = "Cooler zones",
            value = Formatters.count(sensors.size - hottest.size, "zone"),
            detail = "Not shown. Zone names are vendor-defined and are not comparable between devices.",
            tone = Tone.Muted,
        )
    }
    return rows
}

/**
 * The network card, with the three kinds of fact it contains kept visibly apart.
 *
 * The measured throughput is device-wide — Android exposes no per-app byte counter to an ordinary app —
 * and the link figures are the carrier's or driver's own estimate of capacity, which is a different claim
 * from throughput a game will see. Both say so in their detail line, because a user comparing "433 Mbps"
 * against "1.2 MB/s" needs to know they are not two measurements of the same thing.
 *
 * There is no packet-loss row. Measuring loss needs ICMP, which needs a raw socket and so root; the
 * jitter figure from the stability burst is the honest substitute and lives in its own card.
 */
private fun networkRowsOf(snapshot: PerformanceSnapshot): List<Readout> {
    val network = snapshot.network
    if (!network.isConnected) {
        return listOf(
            readoutOf(
                label = "Connection",
                value = network.transport.label,
                detail = "Nothing to measure while there is no network.",
                tone = Tone.Muted,
            ),
        )
    }
    val flags = listOfNotNull(
        "Metered".takeIf { network.isMetered },
        "VPN".takeIf { network.isVpnActive },
    )
    return listOf(
        readoutOf(
            label = "Connection",
            value = network.transport.label,
            detail = flags.joinToString(" · ").ifEmpty { null },
            tone = Tone.Good,
        ),
        snapshot.latency.readout(
            label = "Latency",
            detailOf = { "${it.method} to ${it.host} · ${it.quality.label}" },
            format = { Formatters.millis(it.millis) },
        ),
        network.rxRateBytesPerSecond.readout(
            label = "Download",
            detailOf = { "Device-wide: every app's traffic, not the game's" },
            format = { Formatters.rate(it) },
        ),
        network.txRateBytesPerSecond.readout(
            label = "Upload",
            detailOf = { "Device-wide" },
            format = { Formatters.rate(it) },
        ),
        network.linkDownstreamKbps.readout(
            label = "Link estimate down",
            detailOf = { "The link's claimed capacity, not a measurement" },
            format = { Formatters.rate(it * 1000.0 / 8.0) },
        ),
        network.linkUpstreamKbps.readout(
            label = "Link estimate up",
            detailOf = { "Claimed capacity" },
            format = { Formatters.rate(it * 1000.0 / 8.0) },
        ),
        network.signalStrengthDbm.readout(
            label = "Signal",
            detailOf = { "As the transport reports it" },
            format = { "$it dBm" },
        ),
        network.totalRxBytes.readout(
            label = "Received since boot",
            detailOf = { "Device-wide" },
            format = { Formatters.bytes(it) },
        ),
        network.totalTxBytes.readout(
            label = "Sent since boot",
            detailOf = { "Device-wide" },
            format = { Formatters.bytes(it) },
        ),
    )
}

/**
 * The display card. What the panel is doing now, and separately what it can do.
 *
 * Never merged, because `Display.getRefreshRate()` reports the mode active at the instant it was called
 * and Android switches modes constantly: a 120 Hz panel showing a still screen reports 60, and a
 * "current" reading below the peak says nothing about the panel's capability.
 */
private fun displayRowsOf(snapshot: PerformanceSnapshot): List<Readout> {
    val display = snapshot.display
    return listOf(
        display.currentRefreshRate.readout(
            label = "Refresh rate now",
            detailOf = { refreshDetail(display) },
            format = { Formatters.hertz(it) },
        ),
        display.supportedRates.readout(
            label = "Rates the panel reports",
            detailOf = { "Read from the display's own mode list" },
            format = { rates -> rates.joinToString(" · ") { Formatters.hertz(it) } },
        ),
        readoutOf(
            label = "Resolution",
            value = display.resolutionLabel,
            detail = "${display.densityDpi} dpi${if (display.isHdr) " · HDR" else ""}",
        ),
        display.activeMode.readout(
            label = "Active mode",
            detailOf = { "Mode ${it.modeId}" },
            format = { it.label },
        ),
    )
}

/**
 * The frame-rate figures — and only when a real per-frame source produced them.
 *
 * §24B in its most literal form. There is no branch here that turns a display refresh rate, an overlay's
 * own composition rate, or a CPU load into a frame rate: the sample either came from real frame
 * timestamps or this list is empty and the card prints why. [FrameRateSample.isReliable] is surfaced as
 * its own row rather than used to hide the figures, because twelve real frames are worth showing as long
 * as the screen says twelve frames is not enough to trust the one-percent low.
 */
private fun frameRateRowsOf(sample: Observed<FrameRateSample>): List<Readout> {
    val value = sample.valueOrNull ?: return emptyList()
    val rows = mutableListOf(
        readoutOf(
            label = "Average",
            value = String.format(Locale.US, "%.1f fps", value.averageFps),
            detail = "From ${Formatters.count(value.frameCount, "real frame")} over " +
                Formatters.duration(value.windowMillis),
        ),
        readoutOf(
            label = "1% low",
            value = value.onePercentLowFps
                ?.let { String.format(Locale.US, "%.1f fps", it) }
                ?: ABSENT,
            detail = "The slowest one percent of frames, which is what stutter feels like",
        ),
        readoutOf(
            label = "Janky frames",
            value = "${value.jankFrames} (${Formatters.percentValue(value.jankPercent)})",
            detail = "Frames that missed the display's frame budget",
            tone = if (value.jankPercent >= HEAVY_JANK_PERCENT) Tone.Warning else Tone.Neutral,
        ),
    )
    if (!value.isReliable) {
        rows += readoutOf(
            label = "Confidence",
            value = "Low",
            detail = "Under ${FrameRateSample.MIN_FRAMES} frames in the window, so a single slow " +
                "frame dominates the figures above.",
            tone = Tone.Warning,
        )
    }
    return rows
}

private const val MAX_SENSOR_ROWS = 6
private const val HEAVY_DRAIN_PERCENT_PER_HOUR = 25f
private const val HEAVY_JANK_PERCENT = 10f
