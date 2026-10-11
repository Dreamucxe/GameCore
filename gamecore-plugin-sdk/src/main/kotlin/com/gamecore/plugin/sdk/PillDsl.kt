package com.gamecore.plugin.sdk

import org.json.JSONArray
import org.json.JSONObject

/**
 * The authoring surface for the `pill` section: one bounded, read-only module GameCore draws
 * over a running game.
 *
 * A module is a tree of nodes plus a position. GameCore renders it with its own Compose code,
 * so nothing written through this scope executes — the DSL emits `pill.json` and the host
 * interprets it, exactly as it interprets a theme.
 *
 * **The interactive node types are absent, not discouraged.** There is no `button`, `slider`,
 * `switch` or `scroll` function on this scope, so an interactive node is not something an
 * author can write and have refused later: it does not compile. The host rejects those four
 * types inside an overlay module because the window it draws them in is `FLAG_NOT_TOUCHABLE` —
 * a node that wanted a touch could never be given one, and accepting it would be a promise the
 * system cannot keep. Mirroring that as a missing function rather than a validation message is
 * what makes the rule cost an author nothing to obey.
 *
 * The nine functions here are therefore the whole component set a module may use: `column`,
 * `row`, `grid`, `card`, `text`, `stat`, `spacer`, `image`, `chart`.
 */
@PluginDsl
abstract class OverlayNodeScope internal constructor() {

    /** Nodes added at this level, in author order. Emitted as a node's `children`. */
    internal val nodes = JSONArray()

    // ------------------------------------------------------------------------------ containers

    /**
     * A vertical stack. [gap] is dp between children.
     *
     * [align] is the cross axis, which for a column is the horizontal one: `start`, `left`,
     * `center`, `middle`, `end` or `right`. A vertical word like `top` is refused here rather
     * than passed on, because the host reads an alignment it does not recognise as the axis
     * default — so `top` on a column is not an error on a device, it is a layout quietly
     * aligned left.
     */
    fun column(
        gap: Int? = null,
        align: String? = null,
        padding: Int? = null,
        block: OverlayNodeBuilder.() -> Unit,
    ) {
        container("column", layoutProps(gap, align, HORIZONTAL_ALIGN, padding), block)
    }

    /**
     * A horizontal strip — the usual shape of a readout: label, value, label, value.
     *
     * [align] is the cross axis, which for a row is the vertical one: `top`, `start`, `center`,
     * `middle`, `bottom` or `end`. Note that `end` means *bottom* on a row and *right* on a
     * column, as it does in Compose; see [column] for why a word from the wrong axis is refused.
     */
    fun row(
        gap: Int? = null,
        align: String? = null,
        padding: Int? = null,
        block: OverlayNodeBuilder.() -> Unit,
    ) {
        container("row", layoutProps(gap, align, VERTICAL_ALIGN, padding), block)
    }

    /**
     * A fixed-column grid. [columns] is required rather than defaulted because the host
     * defaults an absent `columns` to 1 and then validates it — a module that meant four
     * columns and said nothing would render as a single file of nodes, silently.
     */
    fun grid(
        columns: Int,
        gap: Int? = null,
        padding: Int? = null,
        block: OverlayNodeBuilder.() -> Unit,
    ) {
        require(columns in 1..OverlayBounds.MAX_GRID_COLUMNS) {
            "grid columns is $columns; an overlay module allows 1..${OverlayBounds.MAX_GRID_COLUMNS}"
        }
        container("grid", layoutProps(gap, null, VERTICAL_ALIGN, padding).put("columns", columns), block)
    }

    /**
     * A surface behind its children, for a readout that needs to separate from the game art.
     *
     * [background] is the card's fill. It is emitted as the node's `color` prop, which is the
     * one name the host reads on every node that has a colour at all — a card's colour is its
     * fill, a text's is its ink. The parameter keeps the name that reads correctly at the call
     * site; the JSON keeps the name the host reads.
     */
    fun card(
        padding: Int? = null,
        background: String? = null,
        block: OverlayNodeBuilder.() -> Unit,
    ) {
        val props = layoutProps(null, null, HORIZONTAL_ALIGN, padding)
        background?.let { props.put("color", hexColor("card background", it)) }
        container("card", props, block)
    }

    // ----------------------------------------------------------------------------------- leaves

    /** A literal label. [color] is `#RRGGBB` or `#AARRGGBB`; [size] is sp. */
    fun text(text: String, color: String? = null, size: Int? = null) {
        val props = JSONObject().put("text", strProp("text", text))
        color?.let { props.put("color", hexColor("text color", it)) }
        size?.let { props.put("size", it) }
        leaf("text", props)
    }

    /**
     * A live figure GameCore reads and fills in. [stat] is one of [OverlayStats]; an unknown
     * id fails here rather than rendering an empty field on someone's screen.
     *
     * The stat's [OverlayStats.scopeFor] scope must also be requested in the manifest, or the
     * host loads the module and declines to fill the field — which looks like a bug in the
     * plugin and is not one the codec can catch, because a missing grant is not malformed JSON.
     */
    fun stat(stat: String, size: Int? = null, color: String? = null) {
        require(stat in OverlayStats.ALL) {
            "unknown overlay stat \"$stat\"; expected one of ${OverlayStats.ALL.joinToString()}"
        }
        val props = JSONObject().put("stat", stat)
        size?.let { props.put("size", it) }
        color?.let { props.put("color", hexColor("stat color", it)) }
        leaf("stat", props)
    }

    /**
     * Fixed empty space, in dp. Prefer a container's `gap` for even spacing.
     *
     * One dimension and not two: the host draws a spacer square, so the same node works in a
     * row and in a column without a second prop. A width and a height would read as a promise
     * the renderer does not keep.
     */
    fun spacer(size: Int? = null) {
        val props = JSONObject()
        size?.let { props.put("size", it) }
        leaf("spacer", props)
    }

    /**
     * One of GameCore's own icons, named from [OverlayIcons]. [size] is dp.
     *
     * **A module ships no artwork.** The host loads no plugin bytes — no packaged asset, no
     * file, no URL — so an image node names a vector already compiled into the app. An unknown
     * name draws a neutral placeholder rather than failing on load, which is a blank glyph on
     * someone's screen, so it is refused here instead.
     */
    fun image(icon: String, size: Int? = null) {
        require(icon in OverlayIcons.ALL) {
            "unknown overlay icon \"$icon\"; a module draws one of GameCore's own: " +
                OverlayIcons.ALL.joinToString()
        }
        val props = JSONObject().put("icon", icon)
        size?.let { props.put("size", it) }
        leaf("image", props)
    }

    /**
     * A declarative plot — a line or a bar chart — drawn from numbers the module ships.
     *
     * **Literal-only, by construction.** There is no `stat` parameter, so an overlay chart cannot
     * name one of GameCore's live feeds the way a [stat] node does. That is not a style choice: a
     * module's data appetite is walked from its `stat` nodes, a live chart series would not be seen
     * by that walk, and the host's overlay audit refuses one outright. A chart over a game shows the
     * numbers it was built with.
     *
     * [values] is the primary series and [values2] an optional comparison line; the host plots at
     * most these two. [kind] is one of [ChartKinds]. [min]/[max] pin the vertical range and must be
     * given together with [max] above [min], or omitted to let the host auto-scale — a half or an
     * inverted range is refused here rather than silently dropped on a device. [label]/[label2] name
     * the series in the legend, [note] is a caption, and [color]/[color2] are `#RRGGBB`/`#AARRGGBB`.
     *
     * The series ride as bounded delimited strings, so each must fit the host's tightest string cap
     * ([ChartBounds.MAX_VALUE_STRING_LENGTH]); an overrun is reported at build time, because the host
     * would otherwise truncate it unseen.
     */
    fun chart(
        values: List<Number>,
        kind: String = ChartKinds.LINE,
        values2: List<Number>? = null,
        min: Number? = null,
        max: Number? = null,
        label: String? = null,
        label2: String? = null,
        note: String? = null,
        color: String? = null,
        color2: String? = null,
    ) {
        val kindId = kind.trim().lowercase()
        require(kindId in ChartKinds.ALL) {
            "chart kind is \"$kind\"; a chart is one of ${ChartKinds.ALL.joinToString()} — the host " +
                "falls back to a line on anything else, so a typo would draw silently wrong"
        }
        require(values.isNotEmpty()) { "a chart needs at least one value" }
        require(values.size <= ChartBounds.MAX_POINTS) {
            "a chart carries ${values.size} points; the cap is ${ChartBounds.MAX_POINTS}, the length " +
                "of the host's own rolling history"
        }
        values2?.let {
            require(it.size <= ChartBounds.MAX_POINTS) {
                "a chart's second series carries ${it.size} points; the cap is ${ChartBounds.MAX_POINTS}"
            }
        }
        require((min == null) == (max == null)) {
            "a chart range needs both min and max, or neither; the host honours a range only when both are set"
        }
        if (min != null && max != null) {
            require(max.toDouble() > min.toDouble()) {
                "chart max (${max.toDouble()}) must be above min (${min.toDouble()}); the host ignores " +
                    "a range that is not and auto-scales instead"
            }
        }
        val props = JSONObject()
        props.put("kind", kindId)
        props.put("values", seriesProp("chart values", values))
        values2?.let { props.put("values2", seriesProp("chart values2", it)) }
        if (min != null && max != null) {
            props.put("min", min.toDouble())
            props.put("max", max.toDouble())
        }
        label?.let { props.put("label", plainText("chart label", it)) }
        label2?.let { props.put("label2", plainText("chart label2", it)) }
        note?.let { props.put("note", plainText("chart note", it)) }
        color?.let { props.put("color", hexColor("chart color", it)) }
        color2?.let { props.put("color2", hexColor("chart color2", it)) }
        leaf("chart", props)
    }

    // ---------------------------------------------------------------------------------- internals

    private fun layoutProps(
        gap: Int?,
        align: String?,
        allowedAlign: Set<String>,
        padding: Int?,
    ): JSONObject {
        val props = JSONObject()
        gap?.let { props.put("gap", it) }
        align?.let {
            val word = it.trim().lowercase()
            require(word in allowedAlign) {
                "align is \"$it\"; this node's cross axis allows ${allowedAlign.joinToString()}"
            }
            props.put("align", word)
        }
        padding?.let { props.put("padding", it) }
        return props
    }

    private fun container(type: String, props: JSONObject, block: OverlayNodeBuilder.() -> Unit) {
        nodes.put(OverlayNodeBuilder(type, props).apply(block).toJson())
    }

    private fun leaf(type: String, props: JSONObject) {
        nodes.put(
            JSONObject().apply {
                put("type", type)
                if (props.length() > 0) put("props", props)
            },
        )
    }
}

/**
 * One container node being filled in. Only [OverlayNodeScope] creates these, so a node's type
 * is always one of the eight the host accepts in an overlay module.
 */
@PluginDsl
class OverlayNodeBuilder internal constructor(
    private val type: String,
    private val props: JSONObject,
) : OverlayNodeScope() {

    /** `props` and `children` are both omitted when empty, which is how the host reads them. */
    internal fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        if (props.length() > 0) put("props", props)
        if (nodes.length() > 0) put("children", nodes)
    }
}

/**
 * Builds a plugin's `pill.json` — the module's identity, where it sits, how solid it is, and
 * its one root node.
 *
 * The root is added the same way any other node is, by calling one of [OverlayNodeScope]'s
 * component functions directly in the `pill { }` block; exactly one is allowed, because the
 * file carries one `id` and the host registers one module per section.
 *
 * ```kotlin
 * pill {
 *     id = "fps-badge"
 *     title = "Frame rate"
 *     anchor(x = 0.5, y = 0.08)
 *     size = OverlaySizes.SMALL
 *     row(gap = 6, align = "center") {
 *         text("FPS", color = "#99FFFFFF", size = 11)
 *         stat(OverlayStats.FPS, size = 14)
 *     }
 * }
 * ```
 */
@PluginDsl
class PillBuilder internal constructor() : OverlayNodeScope() {

    /** The module's id, and its key in the host's registry. Required. */
    var id: String = ""

    /** What the user sees next to the module's toggle in GameCore. Optional. */
    var title: String = ""

    /** One of [OverlaySizes]. */
    var size: String = OverlaySizes.SMALL

    /** Percent, [OverlayBounds.MIN_OPACITY]..[OverlayBounds.MAX_OPACITY]. */
    var opacity: Int = OverlayBounds.DEFAULT_OPACITY

    private var anchorX: Double = 0.5
    private var anchorY: Double = 0.08

    /**
     * Where the module sits, as fractions of the screen — `0.5, 0.08` is centred near the top.
     *
     * Fractions and not pixels, because a module positioned in pixels lands somewhere else on
     * rotation and somewhere else again on another device. A fraction outside the screen is
     * refused rather than clamped: the host would pull it back to the edge, and an author
     * should see that their 1.4 was never going to be honoured.
     */
    fun anchor(x: Double, y: Double) {
        require(x in OverlayBounds.MIN_ANCHOR..OverlayBounds.MAX_ANCHOR) {
            "anchor x is $x; an anchor is a screen fraction in " +
                "${OverlayBounds.MIN_ANCHOR}..${OverlayBounds.MAX_ANCHOR}"
        }
        require(y in OverlayBounds.MIN_ANCHOR..OverlayBounds.MAX_ANCHOR) {
            "anchor y is $y; an anchor is a screen fraction in " +
                "${OverlayBounds.MIN_ANCHOR}..${OverlayBounds.MAX_ANCHOR}"
        }
        anchorX = x
        anchorY = y
    }

    internal fun toJson(): JSONObject {
        require(id.isNotBlank()) { "an overlay module needs an id" }
        require(OverlayBounds.ID_PATTERN.matches(id)) {
            "module id is \"$id\"; an id is lowercase letters, digits, dot, dash or underscore, " +
                "starts with a letter or digit, and is at most ${OverlayBounds.MAX_ID_LENGTH} " +
                "characters — the host lowercases and trims before matching and refuses the rest, " +
                "so any other spelling is renamed or dropped on load"
        }
        require(size in OverlaySizes.ALL) {
            "unknown overlay size \"$size\"; expected one of ${OverlaySizes.ALL.joinToString()}"
        }
        require(opacity in OverlayBounds.MIN_OPACITY..OverlayBounds.MAX_OPACITY) {
            "opacity is $opacity; an overlay module allows " +
                "${OverlayBounds.MIN_OPACITY}..${OverlayBounds.MAX_OPACITY}"
        }
        require(nodes.length() == 1) {
            "an overlay module has exactly one root node, found ${nodes.length()}"
        }

        val content = nodes.getJSONObject(0)
        checkTree(content)

        return JSONObject().apply {
            put("format", Sdk.PILL_FORMAT)
            put("version", Sdk.PILL_VERSION)
            put("id", id)
            if (title.isNotEmpty()) {
                put("title", plainText("title", title, OverlayBounds.MAX_TITLE_LENGTH))
            }
            put("anchor", JSONObject().put("x", anchorX).put("y", anchorY))
            put("size", size)
            put("opacity", opacity)
            put("content", content)
        }
    }

    /**
     * Walks the finished tree for the two bounds a node cannot check as it is built: how many
     * nodes there are in total and how deep they go. Grid width, string length and colour
     * grammar are checked at the call that introduces them, where the author can see which
     * line was wrong.
     *
     * Depth counts the root as 0, matching the host's validator, so a tree of
     * `MAX_DEPTH + 1` levels is the deepest legal one.
     */
    private fun checkTree(root: JSONObject) {
        var count = 0

        fun walk(node: JSONObject, depth: Int) {
            count++
            require(count <= OverlayBounds.MAX_NODES) {
                "an overlay module is capped at ${OverlayBounds.MAX_NODES} nodes; " +
                    "a module this large is a screen, not a readout"
            }
            require(depth <= OverlayBounds.MAX_DEPTH) {
                "an overlay module is capped at depth ${OverlayBounds.MAX_DEPTH}"
            }
            val children = node.optJSONArray("children") ?: return
            require(children.length() <= OverlayBounds.MAX_CHILDREN) {
                "\"${node.optString("type")}\" has ${children.length()} children; " +
                    "an overlay module allows ${OverlayBounds.MAX_CHILDREN}"
            }
            for (i in 0 until children.length()) walk(children.getJSONObject(i), depth + 1)
        }

        walk(root, 0)
    }
}

// ---------------------------------------------------------------------------------- prop encoding

/**
 * The characters the host's `TextSanitizer` strips out of anything a module draws: control
 * codes, and the bidi marks and overrides that let text claim to say something it does not.
 * A module carrying one would render as something other than what its author wrote, so the
 * SDK refuses it here instead of shipping a string that silently changes on load.
 */
private val BIDI_CONTROLS = setOf(
    '‎', '‏', '‪', '‫', '‬', '‭', '‮',
    '⁦', '⁧', '⁨', '⁩',
)

private val HEX_COLOR = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

/**
 * The cross-axis alignment words the host recognises, per axis.
 *
 * The host resolves `align` against the node's cross axis and falls back to that axis's default
 * for anything it does not recognise — so a word from the wrong axis is not a load failure, it
 * is a layout silently pinned to the start. Each set holds both the words the host matches
 * explicitly and the ones whose fallback happens to be their plain meaning (`start` on a row is
 * the top, `left` on a column is the start), and nothing else.
 *
 * `start` and `end` appear in both sets and mean different things in each, as they do in
 * Compose: on a column `end` is the right, on a row it is the bottom.
 */
private val HORIZONTAL_ALIGN = setOf("start", "left", "center", "centre", "middle", "end", "right")
private val VERTICAL_ALIGN = setOf("top", "start", "center", "centre", "middle", "bottom", "end")

/**
 * Length- and character-checks a string the host will sanitise, returning it unchanged.
 *
 * Shared with the theme and rules builders rather than duplicated there: every string any
 * section hands the host goes through the same `TextSanitizer`, so there is one rule to mirror
 * and one place to fix it if the host's ever changes.
 */
internal fun plainText(field: String, raw: String, max: Int = OverlayBounds.MAX_STRING_LENGTH): String {
    require(raw.length <= max) { "$field is ${raw.length} characters; the cap is $max" }
    require(raw.none { it.isISOControl() || it in BIDI_CONTROLS }) {
        "$field carries a control or bidi character, which the host strips on load"
    }
    return raw
}

/**
 * Encodes a string prop.
 *
 * The host reads a leading `@` as a reference to a declared action, and an action reference is
 * rejected outright inside an overlay module — there is no path by which a module could
 * dispatch one. So a leading `@` is doubled, which is the format's own escape and decodes back
 * to the literal string the author wrote. The effect is that this DSL *cannot* emit an action
 * reference into a module, however an author spells a label.
 */
private fun strProp(field: String, raw: String): String {
    val checked = plainText(field, raw)
    return if (checked.startsWith("@")) "@$checked" else checked
}

/**
 * Encodes a chart series as the host's delimited numeric string, refusing a non-finite point and an
 * encoding longer than the host's tightest string cap — the two things the host would otherwise
 * resolve by dropping a value or truncating the string, neither of which the author would see.
 */
private fun seriesProp(field: String, values: List<Number>): String {
    val encoded = values.joinToString(",") { formatPoint(field, it) }
    return plainText(field, encoded, ChartBounds.MAX_VALUE_STRING_LENGTH)
}

/** One point, compact: a whole number loses its trailing `.0` so the series string stays short. */
private fun formatPoint(field: String, n: Number): String {
    val d = n.toDouble()
    require(d.isFinite()) { "$field has a non-finite point ($n); a chart plots real numbers" }
    return if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}

/**
 * A colour must be real hex. The two sections that carry colours fail differently on a bad one
 * — an overlay prop is read as a label, a theme colour is a hard rejection of the whole theme
 * — and neither is something an author should discover on a device, so both come through here.
 */
internal fun hexColor(field: String, raw: String): String {
    require(HEX_COLOR.matches(raw)) {
        "$field is \"$raw\"; an overlay colour is #RRGGBB or #AARRGGBB"
    }
    return raw
}
