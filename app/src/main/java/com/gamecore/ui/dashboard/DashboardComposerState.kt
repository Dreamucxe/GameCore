package com.gamecore.ui.dashboard

import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.core.model.BuiltinDashboardComponent
import com.gamecore.core.model.DashboardItem
import com.gamecore.core.model.DashboardItemKind
import com.gamecore.core.model.DashboardLayout
import com.gamecore.core.model.SessionStatistics
import com.gamecore.core.plugin.ActiveDashboardWidget

/**
 * The pure core behind the Dashboard Composer (plugin #8 / Personal OS #5).
 *
 * Everything with a decision in it lives here as a top-level function over plain values, so it is
 * exercised on the JVM without a ViewModel, a Compose runtime or a third [com.gamecore.core.plugin.PluginRegistry]
 * fake: the ViewModel's one job is to project the live registry/monitor flows into the plain
 * [PluginAvailabilitySnapshot] and [DashboardChartData] these functions take, and to turn their output
 * into state.
 *
 * The honest-boundary rule the master prompt sets (§1, §4.1, §16–18) is spent in [resolveItem]: a
 * placed plugin widget is marked unavailable only for a reason the host can *prove* from its own state
 * — the kill switch, or the plugin being absent, disabled, suspended or incompatible. When the plugin
 * is present and runnable but the host simply is not exposing the widget (a grant the user never gave,
 * a scope withdrawn, a section that no longer parses, the author having dropped it), the cause is not
 * knowable from here, so it collapses to one truthful [ItemUnavailableReason.WIDGET_NOT_PROVIDED]
 * rather than guessing at a reason it cannot stand behind.
 */

/** Why a placed item cannot be drawn right now — only causes the host can prove from its own state. */
enum class ItemUnavailableReason(val label: String) {
    KILL_SWITCH_ON("Plugins are turned off"),
    PLUGIN_NOT_INSTALLED("Plugin not installed"),
    PLUGIN_DISABLED("Plugin disabled"),
    PLUGIN_SUSPENDED("Plugin suspended"),
    PLUGIN_INCOMPATIBLE("Plugin isn't compatible with this version"),
    WIDGET_NOT_PROVIDED("This widget isn't being provided right now"),
}

/**
 * One installed plugin's state, reduced to just the four facts a dashboard reference needs to resolve.
 * Projected from [com.gamecore.core.plugin.InstalledPlugin] so the pure layer never holds a manifest or
 * a grant it has no business reading.
 */
data class PluginPresence(
    val name: String,
    val enabled: Boolean,
    val suspended: Boolean,
    val runnable: Boolean,
)

/**
 * A snapshot of everything the composer needs from the live plugin layer at one instant, flattened to
 * plain maps. [activeWidgets] is keyed by `"pluginId#widgetId"` and holds only widgets the host is
 * currently willing to draw — the same already-gated list Home renders — so a key present here is a
 * widget that can be shown, and a key absent for an otherwise-runnable plugin is the honest "not
 * provided" case.
 */
data class PluginAvailabilitySnapshot(
    val killSwitchOn: Boolean,
    val plugins: Map<String, PluginPresence>,
    val activeWidgets: Map<String, ActiveDashboardWidget>,
) {
    companion object {
        val EMPTY = PluginAvailabilitySnapshot(killSwitchOn = false, plugins = emptyMap(), activeWidgets = emptyMap())

        /** The map key a plugin widget reference resolves against. */
        fun widgetKey(pluginId: String, widgetId: String): String = "$pluginId#$widgetId"
    }
}

/** What a placed item resolves to right now: a drawable built-in, a drawable plugin widget, or a reason it is neither. */
sealed interface ResolvedContent {
    data class Builtin(val component: BuiltinDashboardComponent) : ResolvedContent
    data class PluginWidget(val active: ActiveDashboardWidget) : ResolvedContent
    data class Unavailable(val reason: ItemUnavailableReason) : ResolvedContent
}

/**
 * A placed item paired with what it resolves to. [title] and [subtitle] are host-drawn chrome that is
 * always truthful even when [content] is [ResolvedContent.Unavailable]: a plugin item keeps its plugin
 * name so the user can see whose widget has gone, and never borrows a title from a widget the host is
 * not actually drawing.
 */
data class ResolvedItem(
    val item: DashboardItem,
    val title: String,
    val subtitle: String,
    val content: ResolvedContent,
) {
    val isAvailable: Boolean get() = content !is ResolvedContent.Unavailable
}

/**
 * The host's own figures the built-in components draw, assembled from data the user already has
 * elsewhere in the app. The game half is taken straight from [SessionStatistics] rather than
 * recomputed, so the dashboard's playtime and battery-drain figures are the *same* numbers (including
 * the reliability floor and the charging rule baked into the SQL aggregate) the Sessions screen shows.
 * The Aim Lab half is derived from the user's run history, 3D runs only, by [aimAccuracy].
 */
data class DashboardChartData(
    val gameStats: SessionStatistics,
    val aimRunCount: Int,
    val aimAccuracySeries: List<Float>,
    val aimBestAccuracyPercent: Int?,
    val aimAverageAccuracyPercent: Float?,
) {
    companion object {
        val EMPTY = DashboardChartData(
            gameStats = SessionStatistics.EMPTY,
            aimRunCount = 0,
            aimAccuracySeries = emptyList(),
            aimBestAccuracyPercent = null,
            aimAverageAccuracyPercent = null,
        )
    }
}

/**
 * The Aim Lab half of [DashboardChartData], derived from a run list.
 *
 * Legacy 2D runs and invalid runs are dropped before anything is computed — the same `!isLegacy2D &&
 * isValid` filter the rest of the app scores 3D accuracy through — because mixing a 2D hit-rate into a
 * 3D accuracy trend would draw a line that means two different things. Runs are ordered oldest-first so
 * the series reads left-to-right as time, and an empty result yields nulls, never a fabricated zero.
 */
fun aimAccuracy(runs: List<SessionSummary>): DashboardChartData {
    val valid = runs.asSequence()
        .filter { !it.isLegacy2D && it.isValid }
        .sortedBy { it.startedAtMillis }
        .toList()
    val accuracies = valid.map { it.accuracyPercent }
    return DashboardChartData.EMPTY.copy(
        aimRunCount = valid.size,
        aimAccuracySeries = accuracies.map { it.toFloat() },
        aimBestAccuracyPercent = accuracies.maxOrNull(),
        aimAverageAccuracyPercent = if (accuracies.isEmpty()) null else accuracies.average().toFloat(),
    )
}

/** Assembles the full [DashboardChartData] from the host's session aggregate and the Aim Lab run list. */
fun dashboardChartData(stats: SessionStatistics, aimRuns: List<SessionSummary>): DashboardChartData =
    aimAccuracy(aimRuns).copy(gameStats = stats)

/** The whole composer screen as a function of state. The ViewModel owns the live resolvers separately. */
data class DashboardComposerUiState(
    val isLoading: Boolean = true,
    val isEditing: Boolean = false,
    val layout: DashboardLayout = DashboardLayout.empty(),
    val resolvedItems: List<ResolvedItem> = emptyList(),
    val addablePluginWidgets: List<ActiveDashboardWidget> = emptyList(),
    val addableBuiltins: List<BuiltinDashboardComponent> = emptyList(),
    val atPluginCapacity: Boolean = false,
    val chartData: DashboardChartData = DashboardChartData.EMPTY,
) {
    /** Items shown when not editing: the host hides an item the user toggled off, it does not delete it. */
    val visibleItems: List<ResolvedItem> get() = resolvedItems.filter { it.item.visible }

    val isEmpty: Boolean get() = resolvedItems.isEmpty()
}

/**
 * Resolves one placed item against the live snapshot.
 *
 * The plugin-widget checks run in a deliberate order — kill switch, then not-installed, disabled,
 * suspended, incompatible, then the widget lookup — so the reason returned is the first that is true,
 * and each is one the host can stand behind. The kill switch is checked first because it suppresses
 * *every* plugin widget regardless of a plugin's own state, so it is the one honest, actionable thing
 * to say when it is on. A built-in is always drawable by the host, so the only way one is unavailable
 * is a stored id this build no longer knows (a newer build's component) — reported, not guessed past.
 */
fun resolveItem(item: DashboardItem, snapshot: PluginAvailabilitySnapshot): ResolvedItem = when (item.kind) {
    DashboardItemKind.BUILTIN -> {
        val component = BuiltinDashboardComponent.fromId(item.refId)
        if (component != null) {
            ResolvedItem(item, component.title, "Built-in", ResolvedContent.Builtin(component))
        } else {
            ResolvedItem(item, "Built-in component", "Built-in", ResolvedContent.Unavailable(ItemUnavailableReason.WIDGET_NOT_PROVIDED))
        }
    }

    DashboardItemKind.PLUGIN_WIDGET -> {
        val presence = snapshot.plugins[item.pluginId]
        val subtitle = presence?.name?.ifBlank { item.pluginId } ?: item.pluginId
        val active = snapshot.activeWidgets[PluginAvailabilitySnapshot.widgetKey(item.pluginId, item.refId)]
        val reason: ItemUnavailableReason? = when {
            snapshot.killSwitchOn -> ItemUnavailableReason.KILL_SWITCH_ON
            presence == null -> ItemUnavailableReason.PLUGIN_NOT_INSTALLED
            !presence.enabled -> ItemUnavailableReason.PLUGIN_DISABLED
            presence.suspended -> ItemUnavailableReason.PLUGIN_SUSPENDED
            !presence.runnable -> ItemUnavailableReason.PLUGIN_INCOMPATIBLE
            active == null -> ItemUnavailableReason.WIDGET_NOT_PROVIDED
            else -> null
        }
        when {
            reason != null -> ResolvedItem(item, "Plugin widget", subtitle, ResolvedContent.Unavailable(reason))
            else -> ResolvedItem(item, active!!.widget.title, active.pluginName, ResolvedContent.PluginWidget(active))
        }
    }
}

/** The active plugin widgets not already placed on this layout — what the "add" list may still offer. */
fun addablePluginWidgets(layout: DashboardLayout, snapshot: PluginAvailabilitySnapshot): List<ActiveDashboardWidget> {
    val present = layout.items
        .filter { it.kind == DashboardItemKind.PLUGIN_WIDGET }
        .map { PluginAvailabilitySnapshot.widgetKey(it.pluginId, it.refId) }
        .toHashSet()
    return snapshot.activeWidgets
        .filterKeys { it !in present }
        .values
        .sortedWith(compareBy({ it.pluginName }, { it.widget.title }))
}

/** The built-in components not already placed on this layout. */
fun addableBuiltins(layout: DashboardLayout): List<BuiltinDashboardComponent> {
    val present = layout.items
        .filter { it.kind == DashboardItemKind.BUILTIN }
        .map { it.refId }
        .toHashSet()
    return BuiltinDashboardComponent.ALL.filter { it.id !in present }
}

/**
 * The whole screen state for a layout and a snapshot. Pure: the ViewModel calls this on every combined
 * emission of the layout flow and the projected registry snapshot, and holds nothing else about them.
 */
fun reconcile(
    layout: DashboardLayout,
    snapshot: PluginAvailabilitySnapshot,
    chartData: DashboardChartData,
    isEditing: Boolean,
): DashboardComposerUiState {
    val canonical = layout.normalised()
    return DashboardComposerUiState(
        isLoading = false,
        isEditing = isEditing,
        layout = canonical,
        resolvedItems = canonical.items.map { resolveItem(it, snapshot) },
        addablePluginWidgets = if (canonical.hasPluginWidgetCapacity()) {
            addablePluginWidgets(canonical, snapshot)
        } else {
            emptyList()
        },
        addableBuiltins = addableBuiltins(canonical),
        atPluginCapacity = !canonical.hasPluginWidgetCapacity(),
        chartData = chartData,
    )
}
