package com.gamecore.core.plugin.dashboard

import com.gamecore.core.plugin.overlay.ModuleStat
import com.gamecore.core.plugin.ui.ComponentNode
import com.gamecore.core.plugin.ui.ComponentType
import com.gamecore.core.plugin.ui.PropValue
import com.gamecore.core.plugin.ui.ScreenValidator

/**
 * The sizes a dashboard widget may be, as a closed set.
 *
 * Four coarse spans rather than two free numbers, and the reasoning is [ModuleSize]'s: a free
 * dimension is how a "widget" grows into something that owns the whole dashboard, and any
 * arithmetic bound on it has to be evaluated against a surface the plugin can only guess at. Named
 * spans have no arithmetic to get wrong — the dashboard decides how many grid cells each name
 * claims, the same way GameCore's own home tiles do, and the widget's content lays out inside
 * whichever span the author picked.
 *
 * Unlike [com.gamecore.core.plugin.overlay.ModuleSize] the enum carries no dp figures. An overlay
 * module is a fixed badge floating over a game, so its box is a fixed box; a dashboard widget's
 * footprint is the grid's business and changes with the column count the host happens to be drawing
 * at. The id is the whole contract here, and the span-to-cells mapping lives with the renderer,
 * which is the only place that knows the grid.
 */
enum class WidgetSize(val id: String) {
    /** A single tile — one cell, the least a widget can claim. */
    SMALL("small"),

    /** The ordinary tile, and the span a file gets for saying nothing. */
    MEDIUM("medium"),

    /** A wide panel — half a row on a typical grid. */
    LARGE("large"),

    /** The full row — the most a single widget may claim. */
    FULL("full");

    companion object {
        /**
         * Every size, in declaration order, smallest footprint first.
         *
         * The codec validates an authored size against this set and the dashboard editor offers it
         * as the span picker, so the one list is both the allowlist and the menu — a fifth span
         * cannot be accepted by the loader without also appearing in the picker, and vice versa.
         */
        val ALL: List<WidgetSize> = entries

        private val byId = entries.associateBy { it.id }

        /** Resolves an authored id, or null. Never `valueOf` on untrusted input. */
        fun fromId(id: String): WidgetSize? = byId[id.trim().lowercase()]
    }
}

/**
 * One bounded, read-only panel a plugin may place on the GameCore dashboard.
 *
 * This is the sibling of [com.gamecore.core.plugin.overlay.PluginOverlayModule] for the home
 * surface rather than the in-game one, and it is deliberately the same shape: an id, a label, one
 * of a closed set of sizes, and a tree of validated nodes. There is no touch target, no action
 * dispatch and no live chart feed — a widget is drawn by GameCore's own Compose code, and the
 * plugin's contribution is the *content*, not the behaviour. A widget never gets a callback,
 * because there is no plugin code for a callback to reach.
 *
 * It differs from an overlay module in the two ways the two surfaces differ. A widget has no anchor
 * and no opacity: it does not float over anything the user is looking at, so there is nothing to
 * position by fraction or fade for legibility — the dashboard lays it out in a grid. And a widget
 * may contain a `scroll`, because the dashboard *does* take touches to scroll, whereas an overlay
 * window takes none; the three genuinely interactive leaves (`button`, `slider`, `switch`) are
 * still refused, because a widget still dispatches nothing. The allowed set is spelled out once in
 * [ALLOWED_TYPES] so that single list is the whole of what a reviewer must check.
 *
 * Nothing in this file imports `android.*` or the dashboard UI layer, the same boundary the overlay
 * model keeps: the model can be parsed, stored and shown in the install dialog by code that never
 * touches a composable, and every rule here is a JVM unit test. The stat vocabulary is reused from
 * the overlay rather than copied — a `stat` node displays the same host readings on either surface,
 * and [ModuleStat] already pins each reading to the data scope it rides behind, so a dashboard that
 * invented its own copy would be a second place for that mapping to drift.
 *
 * An instance is *not* automatically in bounds — a hand-built one, or one straight from a decoder
 * that has not finished, may carry an over-long title. Call [normalised] to get one that is, and
 * route anything from a plugin through
 * [com.gamecore.core.plugin.dashboard.PluginDashboardCodec] rather than constructing it directly.
 */
data class PluginDashboardWidget(
    val id: String,
    val title: String,
    val size: WidgetSize,
    val content: ComponentNode,
) {

    /**
     * Every host stat this widget's content displays, derived from the tree itself.
     *
     * Two callers need this and they need it for opposite reasons, which is why it is one walk
     * rather than two. The registry turns it into [requiredScopes] to decide whether the widget may
     * be shown at all; the dashboard host turns it into the set of readings to sample, so a widget
     * displaying a frame rate is what starts the sampler that gives it one. A widget with no stat
     * node returns the empty set and therefore costs nothing on either path — a widget that only
     * labels a tile asks for no data and starts no loop.
     *
     * The walk is iterative because a model may also be built in code, where nothing has bounded
     * its depth yet, and a recursive walk over an unbounded tree is a stack overflow in the host
     * process. If the tree is somehow larger than [MAX_SCOPE_WALK_NODES] the result is every stat
     * in the vocabulary, not a partial set: under-reporting here would show a stat whose aperture
     * was never granted, so the truncated case fails closed. This is the overlay module's walk with
     * one addition the dashboard needs and the overlay does not: a `chart` may name a live host feed
     * in `stat`/`stat2` here (the overlay codec refuses one), so the walk folds a chart's series ids
     * through [chartSeriesStats] alongside each `stat` node. Without that fold a widget chart could
     * name a feed [requiredScopes] never sees and the registry prefilter could not gate it.
     */
    val requiredStats: Set<ModuleStat>
        get() {
            val out = LinkedHashSet<ModuleStat>()
            val pending = ArrayDeque<ComponentNode>()
            pending.addLast(content)
            var visited = 0
            while (pending.isNotEmpty()) {
                if (visited++ >= MAX_SCOPE_WALK_NODES) return ModuleStat.entries.toSet()
                val node = pending.removeLast()
                ModuleStat.fromNode(node)?.let { out += it }
                out += chartSeriesStats(node)
                node.children.forEach { pending.addLast(it) }
            }
            return out
        }

    /**
     * Every data scope this widget's content needs granted, derived from [requiredStats].
     *
     * The registry consults this to refuse a widget whose readings the user never approved, and it
     * is computed from the content rather than declared alongside it because a declared list can
     * disagree with the tree — and the disagreement would be resolved in the plugin's favour. A
     * widget declaring `device_stats` while displaying a temperature would show a reading from an
     * aperture the user closed. Derived, the two cannot differ, and the fail-closed truncation in
     * [requiredStats] covers this too: there is one walk, so there is one place that can be wrong.
     */
    val requiredScopes: Set<String>
        get() = requiredStats.mapTo(LinkedHashSet()) { it.scope }

    /**
     * The same widget with its title forced into range.
     *
     * The overlay module clamps an anchor and an opacity here; a widget has neither, so the one
     * bounded scalar it owns is the title, and that is what this touches. Everything else — the id,
     * the size, the node tree — is either already validated or not something a clamp could fix, and
     * a "normalise" that quietly rewrote content would be a second, invisible validator. The codec
     * sanitises and truncates the title on the way in, so this is idempotent for any widget that
     * came from [PluginDashboardCodec]; it earns its keep on a widget built directly in code.
     */
    fun normalised(): PluginDashboardWidget = copy(title = title.take(MAX_TITLE_LENGTH))

    companion object {
        /** The ceilings [validator] applies. The overlay module's badge caps, verbatim. */
        const val MAX_NODES = 40
        const val MAX_DEPTH = 6
        const val MAX_GRID_COLUMNS = 4
        const val MAX_STRING_LENGTH = 120
        const val MAX_CHILDREN = 12

        /** A widget label has to fit a dashboard tile header and a Settings row. */
        const val MAX_TITLE_LENGTH = 48

        /** A bound on [requiredScopes]' walk over a tree that may not have been validated yet. */
        const val MAX_SCOPE_WALK_NODES = 512

        /**
         * The props by which a `chart` names a live host feed, one per series.
         *
         * Unlike an overlay module, a dashboard widget may carry a live chart series: these are the
         * two prop keys [PluginDashboardCodec] validates against [ModuleStat] and the appetite walk
         * folds through [chartSeriesStats]. The one list is read by both so the gate and the walk
         * cannot drift — relaxing the codec without the walk, or the walk without the codec, is the
         * privilege escalation this single source of truth exists to prevent.
         */
        val CHART_LIVE_SERIES_PROPS: List<String> = listOf("stat", "stat2")

        /**
         * The live host feeds a `chart` node names in its series props, resolved against [ModuleStat].
         *
         * A literal chart (no series props) yields nothing and costs the walk nothing. A node that is
         * not a [ComponentType.CHART] yields nothing, so the walk can call this unconditionally. A
         * series prop present but outside the vocabulary also yields nothing here — but it never
         * reaches a trusted tree, because [PluginDashboardCodec] refuses such a chart before any widget
         * is built; this only ever resolves ids the codec already accepted. Reading the same prop keys
         * through the same [ModuleStat.fromId] the codec uses is what keeps the gate and the walk in
         * lockstep.
         */
        fun chartSeriesStats(node: ComponentNode): List<ModuleStat> {
            if (node.type != ComponentType.CHART) return emptyList()
            return CHART_LIVE_SERIES_PROPS.mapNotNull { key ->
                (node.props[key] as? PropValue.Str)?.value?.let { ModuleStat.fromId(it) }
            }
        }

        /**
         * The node types a widget's content may use.
         *
         * The complement — `BUTTON`, `SLIDER`, `SWITCH` — is rejected by the codec, and this single
         * set is why that rejection is reviewable: a widget is drawn read-only and dispatches
         * nothing, so content that could want to dispatch must not be able to reach it. `SCROLL` is
         * *allowed* here, unlike in an overlay module, because the dashboard is a surface the user
         * scrolls with a finger — the one input a widget's container legitimately needs and the one
         * an overlay window cannot offer. Expressing it as "these ten are allowed" rather than
         * "those three are banned" means a future component type is excluded until someone adds it
         * here, which is the safe direction for a list that gates what can appear on the dashboard.
         */
        val ALLOWED_TYPES: Set<ComponentType> = setOf(
            ComponentType.COLUMN,
            ComponentType.ROW,
            ComponentType.GRID,
            ComponentType.SCROLL,
            ComponentType.CARD,
            ComponentType.TEXT,
            ComponentType.STAT,
            ComponentType.SPACER,
            ComponentType.IMAGE,
            ComponentType.CHART,
        )

        /**
         * The validator a widget's tree must pass.
         *
         * The existing [ScreenValidator], parameterised down — not a second validator. A widget is
         * a tile, not a screen: forty nodes and six levels is more than any readout needs, and the
         * caps being this tight is what keeps a widget cheap enough to recompose on every stat tick
         * without costing the dashboard frames. These are the overlay module's caps exactly, so a
         * content tree that fits a game badge fits a dashboard tile and the two surfaces agree.
         */
        fun validator(): ScreenValidator = ScreenValidator(
            maxNodes = MAX_NODES,
            maxDepth = MAX_DEPTH,
            maxGridColumns = MAX_GRID_COLUMNS,
            maxStringLength = MAX_STRING_LENGTH,
            maxChildren = MAX_CHILDREN,
        )
    }
}
