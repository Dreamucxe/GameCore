package com.gamecore.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.aimlab.AimLabRepository
import com.gamecore.core.model.DashboardItem
import com.gamecore.core.model.DashboardLayout
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.MetricHistory
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.plugin.PluginRegistry
import com.gamecore.core.plugin.overlay.ModuleStat
import com.gamecore.data.repository.DashboardRepository
import com.gamecore.data.repository.SessionRepository
import com.gamecore.domain.monitoring.HudStatReader
import com.gamecore.domain.monitoring.PerformanceMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * The Dashboard Composer's ViewModel: the thin live layer around the pure [reconcile].
 *
 * The layout being edited is held in [workingLayout], a single hot [MutableStateFlow] that nothing in the
 * background ever overwrites — the same draft discipline [com.gamecore.ui.hud.HudEditorViewModel] keeps, so
 * a registry tick or a sampler emission can never move an item out from under an edit. The live inputs are
 * projected into the plain values [reconcile] consumes: the registry's three StateFlows become one
 * [PluginAvailabilitySnapshot], and the host's session aggregate plus the Aim Lab run list become one
 * [DashboardChartData]. Everything with a decision in it is in [reconcile] and tested there; this class only
 * wires flows and persists edits.
 *
 * The plugin-widget resolvers ([stats], [series], [snapshot], [history]) are duplicated verbatim from
 * [com.gamecore.ui.home.PluginWidgetsSectionViewModel] on purpose: they are the only channel a drawn widget
 * has to live host data, and copying the eight-line mapping is cheaper and safer than widening the Home
 * ViewModel into a shared dependency of a second screen.
 *
 * Edits persist immediately rather than behind a Save button: a dashboard is a thing you arrange and leave,
 * not a document you commit. The first edit is what writes the first row — opening the screen on a fresh
 * install stores nothing (see [DashboardRepository.selectedLayout]) — and [persist] folds the id Room assigns
 * back into the draft under a [Mutex] so a burst of edits can never insert a second selected layout.
 */
@HiltViewModel
class DashboardComposerViewModel @Inject constructor(
    private val dashboards: DashboardRepository,
    private val sessions: SessionRepository,
    private val aimLab: AimLabRepository,
    registry: PluginRegistry,
    private val monitor: PerformanceMonitor,
) : ViewModel() {

    /** The authoritative draft. Null only until the first seed lands; the UI shows loading until then. */
    private val workingLayout = MutableStateFlow<DashboardLayout?>(null)

    /** Whether the edit affordances are shown. Not persisted — it is screen chrome, not layout content. */
    private val isEditing = MutableStateFlow(false)

    /** Serialises saves so the id-fold after the first insert cannot race a second insert. */
    private val saveMutex = Mutex()

    /**
     * The live plugin layer, flattened to the plain snapshot [reconcile] resolves against.
     *
     * Projected from the registry's own already-gated StateFlows: [PluginRegistry.activeDashboardWidgets]
     * is the same list Home draws (only widgets the host is willing to show), so a key present here is a
     * drawable widget and a key absent for a runnable plugin is the honest "not provided" case.
     */
    private val registrySnapshot: Flow<PluginAvailabilitySnapshot> =
        combine(
            registry.installed,
            registry.activeDashboardWidgets,
            registry.killSwitchOn,
        ) { installed, widgets, killSwitchOn ->
            PluginAvailabilitySnapshot(
                killSwitchOn = killSwitchOn,
                plugins = installed.associate { plugin ->
                    plugin.id to PluginPresence(
                        name = plugin.name,
                        enabled = plugin.enabled,
                        suspended = plugin.suspended,
                        runnable = plugin.manifest.compatibility.isRunnable,
                    )
                },
                activeWidgets = widgets.associateBy {
                    PluginAvailabilitySnapshot.widgetKey(it.pluginId, it.widget.id)
                },
            )
        }

    /**
     * The host's own figures, recomputed when either source changes.
     *
     * The game half is the live [SessionStatistics] aggregate — recomputed through [SessionRepository]'s
     * own SQL on every session change, never re-derived here — and the Aim Lab half is [aimAccuracy] over
     * the run list. Both are the same numbers their own screens show.
     */
    private val chartData: StateFlow<DashboardChartData> =
        combine(sessions.sessions, aimLab.sessions) { _, aimRuns ->
            dashboardChartData(sessions.statistics(), aimRuns)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DashboardChartData.EMPTY)

    /** The whole screen, assembled by the pure reducer on every combined emission. */
    val state: StateFlow<DashboardComposerUiState> =
        combine(workingLayout, registrySnapshot, chartData, isEditing) { layout, snapshot, charts, editing ->
            if (layout == null) DashboardComposerUiState(isLoading = true)
            else reconcile(layout, snapshot, charts, editing)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DashboardComposerUiState(isLoading = true))

    /** The live sample feed and rolling window, re-exposed so the screen can subscribe and drive redraws. */
    val snapshot: StateFlow<PerformanceSnapshot?> = monitor.snapshots
    val history: StateFlow<MetricHistory> = monitor.history

    init {
        viewModelScope.launch {
            // Seed once from whatever is selected, or an empty (unsaved) working dashboard. Opening the
            // screen writes nothing; the first edit creates the row. A dashboard with nothing to view yet
            // opens in edit mode so the add controls are the first thing the user sees.
            val seed = (dashboards.selectedLayout.first() ?: DashboardLayout.empty()).normalised()
            workingLayout.value = seed
            if (seed.items.isEmpty()) isEditing.value = true
        }
    }

    fun setEditing(editing: Boolean) {
        isEditing.value = editing
    }

    /** Adds a built-in component. Re-adding one already placed is a no-op (dedupe is in [DashboardLayout]). */
    fun addBuiltin(componentId: String) = mutate { layout ->
        layout.copy(items = layout.items + DashboardItem.builtin(componentId, layout.items.size))
    }

    /** Adds a plugin widget, refusing past the layout's plugin-widget cap rather than silently dropping it. */
    fun addPluginWidget(pluginId: String, widgetId: String) = mutate { layout ->
        if (!layout.hasPluginWidgetCapacity()) {
            layout
        } else {
            layout.copy(items = layout.items + DashboardItem.pluginWidget(pluginId, widgetId, layout.items.size))
        }
    }

    fun remove(itemId: String) = mutate { layout ->
        layout.copy(items = layout.items.filterNot { it.itemId == itemId })
    }

    /** Hides or shows an item. A hidden item is kept and simply not drawn when not editing. */
    fun setVisible(itemId: String, visible: Boolean) = mutate { layout ->
        layout.copy(items = layout.items.map { if (it.itemId == itemId) it.copy(visible = visible) else it })
    }

    fun moveUp(itemId: String) = mutate { it.moved(itemId, -1) }

    fun moveDown(itemId: String) = mutate { it.moved(itemId, +1) }

    /**
     * The display string for a `stat` node, resolved exactly as the overlay and Home do: a plugin-facing
     * [ModuleStat] in, the host's [HudStat] in the middle, the rendered reading out — so a figure in a
     * composed widget reads identically here, in Home and over a game. Null for a stat the host cannot read
     * on this surface; the renderer draws null as the absent em dash, never as a zero.
     */
    fun stats(id: String): String? {
        val hud = ModuleStat.fromId(id)?.toHudStat() ?: return null
        val reading = HudStatReader.read(hud, monitor.snapshots.value)
        return if (reading.isAvailable) reading.display() else null
    }

    /** The recent samples behind a live chart series, or null when the host retains no window for it. */
    fun series(id: String): List<Float>? = when (ModuleStat.fromId(id)) {
        ModuleStat.FPS -> monitor.history.value.frameRateSeries()
        ModuleStat.CPU -> monitor.history.value.cpuSeries()
        ModuleStat.RAM -> monitor.history.value.memorySeries()
        ModuleStat.TEMP -> monitor.history.value.temperatureSeries()
        else -> null
    }

    private fun ModuleStat.toHudStat(): HudStat? = when (this) {
        ModuleStat.FPS -> HudStat.FRAME_RATE
        ModuleStat.CPU -> HudStat.CPU_USAGE
        ModuleStat.GPU -> HudStat.GPU_USAGE
        ModuleStat.RAM -> HudStat.RAM_USAGE
        ModuleStat.BATTERY -> HudStat.BATTERY_LEVEL
        ModuleStat.TEMP -> HudStat.BATTERY_TEMPERATURE
        ModuleStat.ROTATION_RATE, ModuleStat.MOVEMENT -> null
    }

    private fun mutate(transform: (DashboardLayout) -> DashboardLayout) {
        val current = workingLayout.value ?: return
        val next = transform(current).normalised()
        if (next == current) return
        workingLayout.value = next
        persist()
    }

    /**
     * Writes the current draft, folding the id Room assigns on the first insert back into the draft.
     *
     * Serialised on [saveMutex] and always saving the latest [workingLayout] value (not a captured one) so
     * a burst of edits collapses to repeated REPLACEs of one row rather than inserting a second — which,
     * because every layout is written selected, would otherwise leave two rows both reading as selected.
     */
    private fun persist() {
        viewModelScope.launch {
            saveMutex.withLock {
                val toSave = workingLayout.value ?: return@withLock
                val resolvedId = dashboards.save(toSave)
                if (toSave.id == 0L) {
                    workingLayout.update { if (it != null && it.id == 0L) it.copy(id = resolvedId) else it }
                }
            }
        }
    }

    /** Moves an item one slot in position order, renumbering densely. An out-of-range move is a no-op. */
    private fun DashboardLayout.moved(itemId: String, delta: Int): DashboardLayout {
        val ordered = items.sortedBy { it.position }.toMutableList()
        val from = ordered.indexOfFirst { it.itemId == itemId }
        if (from < 0) return this
        val to = from + delta
        if (to !in ordered.indices) return this
        ordered.add(to, ordered.removeAt(from))
        return copy(items = ordered.mapIndexed { index, item -> item.copy(position = index) })
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}




