package com.gamecore.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.MetricHistory
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.plugin.ActiveDashboardWidget
import com.gamecore.core.plugin.PluginRegistry
import com.gamecore.core.plugin.dashboard.WidgetSize
import com.gamecore.core.plugin.overlay.ModuleStat
import com.gamecore.domain.monitoring.HudStatReader
import com.gamecore.domain.monitoring.PerformanceMonitor
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.plugins.render.PluginNodeTree
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * The Home surface for the dashboard widgets installed plugins contribute.
 *
 * The dashboard twin of the overlay's module path. Where [com.gamecore.service.GamingOverlayService]
 * draws a plugin's module over a running game, this draws a plugin's widget inside Home — the same
 * validated node tree, the same read-only renderer, the same statically-knowable data appetite — but
 * on a surface the user is already looking at rather than over one they are playing. Both read an
 * already-filtered list off [PluginRegistry] and neither can be put into an error state by a plugin:
 * the registry hands over only what may be shown.
 *
 * This holds nothing a plugin authored. It takes [PluginRegistry] for the list of widgets the running,
 * granted plugins are currently allowed to draw, and [PerformanceMonitor] for the host figures those
 * widgets display. It exposes two resolvers — [stats] and [series] — which are the only channel a
 * widget's tree has to real data, and both speak the plugin-facing [ModuleStat] vocabulary rather than
 * the host's internal [HudStat], so a widget asks for "cpu" and never learns the host calls it
 * CPU_USAGE.
 */
@HiltViewModel
class PluginWidgetsSectionViewModel @Inject constructor(
    registry: PluginRegistry,
    private val monitor: PerformanceMonitor,
) : ViewModel() {

    /**
     * The widgets to draw, already filtered to what is allowed on screen.
     *
     * Taken straight off the registry and not re-derived here, for the same reason the overlay takes
     * [PluginRegistry.activeOverlayModules] straight: every condition that could make a widget not
     * allowed — the kill switch, the plugin being disabled, suspended or incompatible, a missing
     * ADD_DASHBOARD_WIDGETS in the grant, a data scope the user never approved, a section that no
     * longer parses — has already removed it from this list upstream. There is nothing left for the UI
     * to check, which is what keeps the gate in one place rather than two that can disagree.
     */
    val widgets: StateFlow<List<ActiveDashboardWidget>> = registry.activeDashboardWidgets

    /**
     * The live sample feed, re-exposed so the composable (which holds only this ViewModel) can
     * subscribe to it.
     *
     * Collecting this in composition is load-bearing twice over: [PerformanceMonitor.snapshots] samples
     * only while something collects it, so the subscription is what keeps the loop running while
     * widgets are on screen; and the read is what recomposes the read-only trees each tick, so a figure
     * a widget draws moves with the one in the metric tiles rather than freezing at the first frame.
     */
    val snapshot: StateFlow<PerformanceSnapshot?> = monitor.snapshots

    /** The rolling window a live chart series is drawn from, re-exposed for the same reason as [snapshot]. */
    val history: StateFlow<MetricHistory> = monitor.history

    /**
     * The display string for a `stat` node's reading, or null when it has none to show.
     *
     * Resolves exactly as the overlay's own `pluginStatValue` does, deliberately: a plugin-facing
     * [ModuleStat] id in, the host's [HudStat] in the middle, [HudStatReader]'s rendered reading out —
     * so a figure in a dashboard widget reads identically to the same figure in the overlay and in the
     * host's own HUD, down to the unit and the "n/a" placeholder. Null is returned for a stat the host
     * cannot currently read and for the two motion stats this surface has no sampler for; the renderer
     * draws null as the absent em dash, never as a zero.
     *
     * No new stats are invented here. The vocabulary is [ModuleStat] and nothing else; an id outside it
     * resolves to null.
     */
    fun stats(id: String): String? {
        val hud = ModuleStat.fromId(id)?.toHudStat() ?: return null
        val reading = HudStatReader.read(hud, monitor.snapshots.value)
        return if (reading.isAvailable) reading.display() else null
    }

    /**
     * The recent samples behind a live chart series id, or null when the host retains none for it.
     *
     * Only four of the six figures have a rolling history the host keeps: frame rate, CPU, memory and
     * temperature. GPU and battery are sampled for the instantaneous readout but not retained as a
     * plottable window, and the two motion stats are not sampled on this surface at all — so each is
     * null, which the chart reader treats as "no live feed", falling back to the author's literal
     * values and drawing an empty plot if there are none. An unknown id is null for the same reason.
     * Null rather than an empty list is the honest distinction: an empty list would claim the host owns
     * the feed and it merely happens to be empty, which for GPU and battery is a lie.
     */
    fun series(id: String): List<Float>? = when (ModuleStat.fromId(id)) {
        ModuleStat.FPS -> monitor.history.value.frameRateSeries()
        ModuleStat.CPU -> monitor.history.value.cpuSeries()
        ModuleStat.RAM -> monitor.history.value.memorySeries()
        ModuleStat.TEMP -> monitor.history.value.temperatureSeries()
        else -> null
    }

    /**
     * The host stat behind a plugin-facing one, or null when the host does not read it on this surface.
     *
     * The same mapping the overlay makes, including answering a request for temperature with the
     * battery sensor rather than the CPU one: battery temperature exists on every device, where a CPU
     * thermal zone does not. The two motion stats map to null because resolving them needs a motion
     * sampler this surface does not hold.
     */
    private fun ModuleStat.toHudStat(): HudStat? = when (this) {
        ModuleStat.FPS -> HudStat.FRAME_RATE
        ModuleStat.CPU -> HudStat.CPU_USAGE
        ModuleStat.GPU -> HudStat.GPU_USAGE
        ModuleStat.RAM -> HudStat.RAM_USAGE
        ModuleStat.BATTERY -> HudStat.BATTERY_LEVEL
        ModuleStat.TEMP -> HudStat.BATTERY_TEMPERATURE
        ModuleStat.ROTATION_RATE, ModuleStat.MOVEMENT -> null
    }
}
/**
 * The Home section that draws every active plugin dashboard widget, each in its own titled card.
 *
 * Renders nothing when there are no widgets — not an empty card, nothing — so a Home with no dashboard
 * plugins looks exactly as it did before the feature existed. (The caller should likewise emit this
 * section's `item {}` only when it has content, so the list's `spacedBy` gap is not spent on a
 * zero-height section; see NEEDS-MAIN.)
 *
 * Everything inside a card is drawn by [PluginNodeTree], which attaches no gesture of any kind, and
 * this composable adds none either: a dashboard widget is a readout, not a control, and the one way to
 * keep that true is to never hang a click, toggle or drag on anything a plugin drew. The card's own
 * chrome — the title and the plugin's name — is host-drawn, so the user can always see which plugin
 * put a widget on their Home even if the widget's content says otherwise.
 */
@Composable
fun PluginWidgetsSection(
    modifier: Modifier = Modifier,
    viewModel: PluginWidgetsSectionViewModel = hiltViewModel(),
) {
    val widgets by viewModel.widgets.collectAsStateWithLifecycle()
    if (widgets.isEmpty()) return

    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    // Re-made whenever the live frame changes, which is the point: reading `snapshot`/`history` here
    // recomposes the section each tick, and handing PluginNodeTree a fresh resolver instance is what
    // makes it re-read the latest value through vm.stats / vm.series. A bare `viewModel::stats` would
    // be a stable reference reading a StateFlow value Compose does not track, so every figure would
    // freeze at the first frame. The chart decode is keyed on its node, not on the frame, so this
    // re-runs the resolver without re-parsing the tree.
    val stats = remember(snapshot) { { id: String -> viewModel.stats(id) } }
    val series = remember(history) { { id: String -> viewModel.series(id) } }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        widgets.forEach { active ->
            SectionCard(
                title = active.widget.title,
                subtitle = active.pluginName,
                icon = Icons.Filled.Extension,
            ) {
                PluginNodeTree(
                    node = active.widget.content,
                    stats = stats,
                    series = series,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = minHeightForSpan(active.widget.size)),
                )
            }
        }
    }
}

/**
 * The minimum height a widget's content is given for its declared span.
 *
 * [WidgetSize] carries no dp on purpose — a span is a grid footprint, and the span-to-size mapping
 * belongs to the renderer, the only code that knows the surface (see [WidgetSize]'s own note). On Home
 * the widgets stack full-width rather than tiling a grid, so a span here is a height floor the content
 * lays out within rather than a cell count; the four values rise with the span, and a card grows past
 * its floor whenever its content needs more.
 */
private fun minHeightForSpan(size: WidgetSize): Dp = when (size) {
    WidgetSize.SMALL -> 56.dp
    WidgetSize.MEDIUM -> 88.dp
    WidgetSize.LARGE -> 132.dp
    WidgetSize.FULL -> 180.dp
}
