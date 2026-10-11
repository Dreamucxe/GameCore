package com.gamecore.core.plugin.ui.chart

import com.gamecore.core.plugin.ui.ComponentNode
import com.gamecore.core.plugin.ui.PropValue

/**
 * Reads a `CHART` [ComponentNode]'s props into a trusted [ChartSpec].
 *
 * This is a **total, defensive reader, not a second validator**: it never throws and never rejects.
 * That is deliberate and matches the platform's division of labour. A chart's props have already
 * crossed every trust boundary that can refuse them by the time one reaches here —
 * [com.gamecore.core.plugin.ui.PluginNodeCodec] has refused a non-primitive or non-finite prop
 * value, [com.gamecore.core.plugin.ui.ScreenValidator] has capped every string, and for an overlay
 * module `PluginOverlayCodec`'s audit has sanitised every string and refused a live series. So the
 * only things left for this reader to decide are *author mistakes that are not security problems*:
 * a stray `kind`, a blank in a values list, a half-specified range. Each resolves to the least
 * surprising reading rather than to a rejection, for the same reason [ChartKind.fromId] falls back
 * to a line — the api gate already refused anything genuinely out of contract.
 *
 * Because it reads only props and holds no Android or Compose type, every rule here is a JVM unit
 * test, exactly as [com.gamecore.core.plugin.ui.PluginNodeCodec] is.
 *
 * ### The prop vocabulary
 *
 *  - `kind` — `line` or `bar` ([ChartKind]); unknown or absent → [ChartKind.LINE].
 *  - `values` / `values2` — a delimited list of numbers, the literal data for each series. Carried
 *    as a bounded string and not a JSON array on purpose: the node codec forbids arrays in props so
 *    that [PropValue] stays a closed set of primitives, so a series rides as text and is parsed here.
 *  - `stat` / `stat2` — the id of a live host feed for each series, resolved at render time. When a
 *    series names both a live id and literal values, the live id wins and the values are the
 *    fallback a host with no resolver uses.
 *  - `min` / `max` — an explicit vertical range; honoured only when both are present and `max > min`.
 *  - `label` / `label2` — a legend name for each series. `note` — a caveat drawn under the plot.
 *  - `color` / `color2` — each series' colour, already a [PropValue.Color] because the node codec
 *    turned a `#RRGGBB` string into one regardless of the key it sat under.
 */
object ChartNodeCodec {

    /**
     * The most literal points a chart keeps, mirroring `MetricHistory.CAPACITY` — the length of the
     * host's own rolling history, and therefore the longest a *live* series could ever resolve to.
     * A literal list longer than this is an author shipping more data than any host feed holds, so
     * the tail is dropped rather than drawn off the end of a plot.
     *
     * Duplicated here as a plain constant rather than read from `MetricHistory`, so this reader keeps
     * no dependency on the model layer and stays a pure, model-free unit test; `ChartNodeCodecTest`
     * pins the two together.
     */
    const val MAX_POINTS = 120

    /** The delimiters a values string may use between numbers: comma, semicolon or whitespace. */
    private val DELIMITERS = Regex("[,;\\s]+")

    /**
     * Reads [node]'s props as a [ChartSpec].
     *
     * Reads props only and does not inspect [ComponentNode.type]; the renderer's exhaustive `when`
     * over `ComponentType` is what guarantees this is called on a `CHART` node and nothing else.
     */
    fun decode(node: ComponentNode): ChartSpec {
        val values2 = parseSeries(node.strProp("values2")).ifEmpty { null }
        return ChartSpec(
            kind = ChartKind.fromId(node.strProp("kind")) ?: ChartKind.LINE,
            values = parseSeries(node.strProp("values")),
            values2 = values2,
            seriesId = node.idProp("stat"),
            series2Id = node.idProp("stat2"),
            min = node.numProp("min"),
            max = node.numProp("max"),
            label = node.textProp("label"),
            label2 = node.textProp("label2"),
            note = node.textProp("note"),
            colorArgb = node.colorProp("color"),
            color2Argb = node.colorProp("color2"),
        )
    }

    /**
     * Splits a delimited numeric string into finite floats, dropping blanks and anything that is not
     * a number, and stopping at [MAX_POINTS]. A `null` or blank string is an empty series — the
     * reading a host with no resolver falls back to when a live id did not resolve either.
     */
    private fun parseSeries(raw: String?): List<Float> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<Float>()
        for (token in raw.split(DELIMITERS)) {
            if (token.isBlank()) continue
            val value = token.toFloatOrNull() ?: continue
            if (!value.isFinite()) continue
            out += value
            if (out.size >= MAX_POINTS) break
        }
        return out
    }

    // --------------------------------------------------------------------------------- prop readers

    /** A string prop, raw. Only a [PropValue.Str] is a string; a colour or action is not. */
    private fun ComponentNode.strProp(key: String): String? = (props[key] as? PropValue.Str)?.value

    /** A string prop trimmed to an id/label, or null when absent or blank. */
    private fun ComponentNode.idProp(key: String): String? =
        strProp(key)?.trim()?.takeIf { it.isNotEmpty() }

    /** Same shape as [idProp]; named apart so the call site reads as text rather than as an id. */
    private fun ComponentNode.textProp(key: String): String? = idProp(key)

    /**
     * A numeric prop as a finite float. A [PropValue.Num] is the usual form; a numeric
     * [PropValue.Str] is also accepted, so a hand-written `"min": "0"` works as well as `"min": 0`.
     */
    private fun ComponentNode.numProp(key: String): Float? = when (val v = props[key]) {
        is PropValue.Num -> v.value.toFloat().takeIf { it.isFinite() }
        is PropValue.Str -> v.value.trim().toFloatOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

    /** A colour prop as packed ARGB, or null. The node codec already parsed the `#` string into one. */
    private fun ComponentNode.colorProp(key: String): Int? = (props[key] as? PropValue.Color)?.argb
}
