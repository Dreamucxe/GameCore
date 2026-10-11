package com.gamecore.core.plugin.ui.chart

/**
 * The kinds of chart a plugin may draw. A closed set, for the same reason [com.gamecore.core.plugin.ui.ComponentType]
 * is: a chart is data, so the only shapes that can appear are ones GameCore knows how to draw natively.
 *
 * An unknown id does not reject — it resolves to [LINE]. A chart whose `kind` this build does not
 * recognise is a chart authored against a newer API, and the api gate ([com.gamecore.core.plugin.PluginApi])
 * is what refuses such a package whole; by the time a node reaches this reader the package is already
 * known to be in range, so a stray kind is an author's typo and a line is the least surprising fallback.
 */
enum class ChartKind(val id: String) {
    LINE("line"),
    BAR("bar");

    companion object {
        private val byId = entries.associateBy { it.id }

        /** Resolves an authored id, trimmed and lower-cased, or null for an unknown one. */
        fun fromId(id: String?): ChartKind? = id?.let { byId[it.trim().lowercase()] }
    }
}

/**
 * The trusted, pure-Kotlin model of a declarative chart, decoded from a `CHART` component node.
 *
 * This is the self-contained building block the dashboard-chart work is built on: the overlay
 * renderer, the dashboard widgets (phase 5) and the plugin screens (phase 6) all consume a
 * [ChartSpec] rather than reading raw props themselves, so there is one description of what a chart
 * is and one reader that produces it. It carries no Android type and no Compose type on purpose, so
 * every rule that produced it is a JVM unit test, exactly as the node codec is.
 *
 * A chart's data arrives one of two ways, never both for a given series:
 *
 *  - **Literal** — [values] / [values2] are numbers the plugin shipped in the file. This is the only
 *    form an overlay module may use, because an overlay's data appetite is walked from its `stat`
 *    nodes and a live chart series would not be seen by that walk (see
 *    `PluginOverlayCodec`'s chart audit).
 *  - **Live** — [seriesId] / [series2Id] name one of GameCore's approved stat feeds, resolved at
 *    render time against the host's rolling history. A live series rides behind the same
 *    `READ_APPROVED_DATA` scope a `stat` node does, which is why the full-screen and dashboard hosts
 *    pass a resolver and the overlay host does not.
 *
 * When a series carries both a live id and literal values, the live id wins — the literal values are
 * the fallback a host without a resolver falls back to.
 */
data class ChartSpec(
    val kind: ChartKind,
    val values: List<Float>,
    val values2: List<Float>?,
    val seriesId: String?,
    val series2Id: String?,
    val min: Float?,
    val max: Float?,
    val label: String?,
    val label2: String?,
    val note: String?,
    val colorArgb: Int?,
    val color2Argb: Int?,
) {

    /** True when either series names a live host feed. The overlay audit refuses exactly this. */
    val isLive: Boolean get() = seriesId != null || series2Id != null

    /**
     * An explicit vertical range the author pinned, or null to auto-scale.
     *
     * Both ends are required and the top must be above the floor: a half-range or an inverted one is
     * an author's mistake, and auto-scaling is a safer answer than a plot that reads against a bound
     * that was never meant. The actual range used at render — explicit or auto — is computed by
     * [ChartGeometry.effectiveRange], because a live series' values are not known until render.
     */
    fun explicitRange(): ClosedFloatingPointRange<Float>? =
        if (min != null && max != null && max > min) min..max else null
}
