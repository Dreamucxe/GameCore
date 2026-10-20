package com.gamecore.themeengine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.common.Observed
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.domain.monitoring.PerformanceMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Which of the three readings this device actually produced.
 *
 * A number the app could not read is drawn as an em dash, never as a plausible zero — the same rule the
 * rest of GameCore follows, and it matters twice here, because a temperature invented as 0 °C would
 * paint the backdrop glacier-cold while the device is on fire.
 */
data class MetricAvailability(
    val cpu: Boolean,
    val ram: Boolean,
    val temperature: Boolean,
) {
    companion object {
        val NONE = MetricAvailability(cpu = false, ram = false, temperature = false)
        val ALL = MetricAvailability(cpu = true, ram = true, temperature = true)
    }
}

/** Everything one frame of the calibration screen needs. */
data class CalibrationUiState(
    val metrics: ThemeEngineMetrics,
    val availability: MetricAvailability,
    val resolution: AuroraResolution,
    val preset: CalibrationPreset,
    val presets: List<CalibrationPreset>,
    val isFollowingLive: Boolean,
    val isActive: Boolean,
) {
    val canDeletePreset: Boolean get() = preset.id != AuroraDefaults.DEFAULT_PRESET_ID

    companion object {
        fun initial(): CalibrationUiState {
            val preset = AuroraDefaults.preset()
            return CalibrationUiState(
                metrics = ThemeEngineMetrics.EMPTY,
                availability = MetricAvailability.NONE,
                resolution = AuroraVisualEngine.resolve(ThemeEngineMetrics.EMPTY, preset),
                preset = preset,
                presets = listOf(preset),
                isFollowingLive = true,
                isActive = false,
            )
        }
    }
}

/**
 * The calibration screen's state, and the one thing that starts and stops the engine.
 *
 * The screen owns the override, not the plugin. When every slider is untouched the plugin is fed the
 * live readings and behaves exactly as it will in production; the moment a slider moves, the same feed
 * carries the slider's value instead and the whole pipeline — resolution, smoothing, table, canvas —
 * responds as though the device had said so. Nothing switches "preview mode" on: there is only ever one
 * stream, and the screen decides what is on it.
 *
 * The preset in force lives in [ThemeEnginePlugin], not here, because saving and selecting are the
 * repository's job and the plugin is already watching its result. This view model only asks the
 * repository to change it.
 */
@HiltViewModel
class ThemeEngineViewModel @Inject constructor(
    performance: PerformanceMonitor,
    private val plugin: ThemeEnginePlugin,
    private val repository: AuroraThemeRepository,
) : ViewModel() {

    private val liveReading: StateFlow<LiveReading> = performance.snapshots
        .map(::readingsOf)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MILLIS), LiveReading.EMPTY)

    /** The smoothed vector the canvas paints. Only the canvas collects it, so only it recomposes. */
    val renderVector: StateFlow<AuroraVector> = plugin.renderVector

    /** Null while the sliders are untouched. Non-null means every figure on screen is an override. */
    private val manualOverride = MutableStateFlow<ThemeEngineMetrics?>(null)

    private val effectiveMetrics = combine(liveReading, manualOverride) { live, manual ->
        manual ?: live.metrics
    }

    val uiState: StateFlow<CalibrationUiState> = combine(
        liveReading,
        manualOverride,
        plugin.state,
        repository.presets,
    ) { live, manual, engine, presets ->
        // Resolved here from the same inputs the table prints rather than read back from the engine:
        // the engine's copy arrives one stream emission later, and a table that disagrees with the
        // cards above it for a moment is a table the user stops trusting.
        val metrics = manual ?: live.metrics
        CalibrationUiState(
            metrics = metrics,
            availability = if (manual != null) MetricAvailability.ALL else live.availability,
            resolution = AuroraVisualEngine.resolve(metrics, engine.preset),
            preset = engine.preset,
            presets = presets,
            isFollowingLive = manual == null,
            isActive = engine.isActive,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_AFTER_MILLIS),
        initialValue = CalibrationUiState.initial(),
    )

    init {
        plugin.start(viewModelScope, effectiveMetrics, repository.activePreset.value)
        // Selecting a different save point, or saving a new one, lands here and re-aims the engine
        // without restarting it — the painted colour transitions rather than snapping.
        viewModelScope.launch {
            repository.activePreset.collect { plugin.setPreset(it) }
        }
    }

    // ---------- manual override ------------------------------------------------------------------

    fun setCpuOverride(percent: Int) = applyOverride { it.copy(cpuUsage = percent.toFloat()) }

    fun setRamOverride(percent: Int) = applyOverride { it.copy(ramAllocation = percent.toFloat()) }

    fun setCoreTemperatureOverride(celsius: Int) = applyOverride { it.copy(coreTemperatureC = celsius.toFloat()) }

    /** Back to the device's own readings. */
    fun followLive() {
        manualOverride.value = null
    }

    private fun applyOverride(change: (ThemeEngineMetrics) -> ThemeEngineMetrics) {
        // Seeded from what is on screen, so the first slider moved only changes its own figure instead
        // of dropping the other two to zero.
        val base = manualOverride.value ?: uiState.value.metrics
        manualOverride.value = change(base).normalised()
    }

    // ---------- presets --------------------------------------------------------------------------

    /**
     * Stores what is currently painted as a named save point.
     *
     * The mode saved is the one the engine resolved, not the one the preset was loaded with: a user who
     * has watched the backdrop turn molten and presses Save means "like this", and the chip they get
     * back should bring this back. The thresholds and the velocity multiplier carry over unchanged —
     * they are the preset's calibration, which nothing on this screen edits.
     */
    fun savePreset(name: String) {
        val engine = plugin.state.value
        val mode = engine.resolution.mode
        val saved = engine.preset.copy(
            id = "user-${UUID.randomUUID()}",
            name = name,
            vectorMode = mode,
            customColorRgb = engine.preset.customColorRgb.takeIf { mode == ColorVectorMode.CUSTOM },
        )
        viewModelScope.launch { repository.upsert(saved) }
    }

    // Thresholds are edited on the preset in force; they are persisted only when the user saves a
    // save point, so experimenting with an edge never silently rewrites the preset they started from.

    fun setCpuThreshold(percent: Int) {
        plugin.setPreset(plugin.state.value.preset.copy(cpuThreshold = percent.toFloat()))
    }

    fun setRamThreshold(percent: Int) {
        plugin.setPreset(plugin.state.value.preset.copy(ramThreshold = percent.toFloat()))
    }

    fun setTemperatureThreshold(celsius: Int) {
        plugin.setPreset(plugin.state.value.preset.copy(tempThreshold = celsius.toFloat()))
    }

    fun selectPreset(id: String) {
        viewModelScope.launch { repository.select(id) }
    }

    fun deleteActivePreset() {
        val id = plugin.state.value.preset.id
        if (id == AuroraDefaults.DEFAULT_PRESET_ID) return
        viewModelScope.launch { repository.delete(id) }
    }

    override fun onCleared() {
        plugin.stop()
        super.onCleared()
    }

    /**
     * One snapshot, as a reading the engine can paint from.
     *
     * The temperature is the deci-Celsius integer the sampler reports, divided once here; everything
     * downstream is Celsius. A reading the device withheld contributes 0 to the engine *and* a false in
     * [MetricAvailability], which is what keeps the canvas honest: cold is the safe direction to be
     * wrong in, and the card beside it says "—" rather than the 0.
     */
    private fun readingsOf(snapshot: PerformanceSnapshot?): LiveReading {
        if (snapshot == null) return LiveReading.EMPTY
        val cpu = (snapshot.cpu.overallPercent as? Observed.Value)?.value
        val ram = snapshot.memory.takeIf { it.totalBytes > 0L }?.usedPercent
        val temperature = (snapshot.primaryTemperatureDeciCelsius as? Observed.Value)?.value
            ?.let { it / 10f }
        return LiveReading(
            metrics = ThemeEngineMetrics(
                cpuUsage = cpu ?: 0f,
                ramAllocation = ram ?: 0f,
                coreTemperatureC = temperature ?: 0f,
            ),
            availability = MetricAvailability(
                cpu = cpu != null,
                ram = ram != null,
                temperature = temperature != null,
            ),
        )
    }

    private data class LiveReading(
        val metrics: ThemeEngineMetrics,
        val availability: MetricAvailability,
    ) {
        companion object {
            val EMPTY = LiveReading(ThemeEngineMetrics.EMPTY, MetricAvailability.NONE)
        }
    }

    private companion object {
        /** Matches the monitor's own grace period; navigating back must not tear down the sampler. */
        const val STOP_AFTER_MILLIS = 5_000L
    }
}
