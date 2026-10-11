package com.gamecore.plugin.sdk

import org.json.JSONArray
import org.json.JSONObject

/**
 * The authoring surface for the `screens` section: one or more declarative, read-only screens
 * GameCore draws on a real navigation route under the user's active theme.
 *
 * A screen is a tree of nodes plus an id, a title and an optional icon — data, not code. The DSL
 * emits `screens/screens.json`; the host interprets it with its own Compose renderer, exactly as it
 * interprets a theme or an overlay module. Nothing written here executes.
 *
 * **This is the one node scope that carries the full component set.** Unlike [OverlayNodeScope],
 * which omits `button`, `slider`, `switch` and `scroll` because an overlay window takes no touches,
 * a screen is a full-size, scrollable surface, so [ScreenNodeScope] exposes all twelve component
 * types the host can render. The honest limit for this phase is spelled out on the functions that
 * have one: an interactive node is drawn **inert** (it reads as a control but dispatches nothing),
 * and a [stat] draws **absent** (an em dash) until a later phase feeds screens live readings. The
 * SDK still lets an author lay them out, so a screen built now is complete the day those arrive.
 *
 * ```kotlin
 * screens {
 *     screen("overview", "Overview", icon = OverlayIcons.INSIGHTS) {
 *         column(gap = 12, padding = 16) {
 *             text("Session summary", size = 20, bold = true)
 *             card(background = "#1A1D24", padding = 12) {
 *                 row(gap = 8) {
 *                     text("Peak FPS")
 *                     stat(OverlayStats.FPS)
 *                 }
 *             }
 *         }
 *     }
 * }
 * ```
 */
@PluginDsl
class ScreensBuilder {
    private val screens = JSONArray()
    private val ids = linkedSetOf<String>()

    /**
     * One screen: an [id] the host routes to, a [title] drawn in its top bar, an optional [icon]
     * from [OverlayIcons], and exactly one root node added in [block].
     *
     * The [id] is held to the same grammar as every other id in the platform ([OverlayBounds.ID_PATTERN]),
     * because it is the registry key a navigation link resolves against — the host lowercases and
     * trims and refuses the rest, so any other spelling would arrive renamed or not at all. A
     * duplicate id is refused here: the route resolves a screen by id, and a file with two screens
     * under one id has no answer to which a link opens.
     */
    fun screen(
        id: String,
        title: String,
        icon: String? = null,
        block: ScreenBuilder.() -> Unit,
    ) {
        require(id.isNotBlank()) { "a screen needs an id" }
        require(OverlayBounds.ID_PATTERN.matches(id)) {
            "screen id is \"$id\"; an id is lowercase letters, digits, dot, dash or underscore, " +
                "starts with a letter or digit, and is at most ${OverlayBounds.MAX_ID_LENGTH} " +
                "characters — the host lowercases and trims before matching and refuses the rest, " +
                "so any other spelling is renamed or dropped on load"
        }
        require(ids.add(id)) {
            "two screens share the id \"$id\"; the host names a screen by its id when a link " +
                "resolves to it, so a duplicate makes that link ambiguous"
        }
        require(title.isNotBlank()) { "screen \"$id\" needs a title" }
        require(screens.length() < ScreenBounds.MAX_SCREENS) {
            "a screens file holds at most ${ScreenBounds.MAX_SCREENS} screens"
        }

        val icons = icon?.let {
            require(it in OverlayIcons.ALL) {
                "screen \"$id\" names icon \"$it\"; a screen draws one of GameCore's own " +
                    "(${OverlayIcons.ALL.joinToString()}) or none — the host drops an unknown icon to none"
            }
            it
        }

        val builder = ScreenBuilder().apply(block)
        require(builder.nodes.length() == 1) {
            "screen \"$id\" has exactly one root node, found ${builder.nodes.length()}"
        }
        val content = builder.nodes.getJSONObject(0)
        checkScreenTree(id, content)

        screens.put(
            JSONObject().apply {
                put("id", id)
                put("title", screenText("screen \"$id\" title", title, ScreenBounds.MAX_TITLE_LENGTH))
                icons?.let { put("icon", it) }
                put("content", content)
            },
        )
    }

    internal fun toJson(): JSONObject {
        require(screens.length() > 0) { "a screens section needs at least one screen" }
        return JSONObject().apply {
            put("format", Sdk.SCREENS_FORMAT)
            put("version", Sdk.SCREENS_VERSION)
            put("screens", screens)
        }
    }
}

/**
 * One screen being filled in. The root node is added the same way any other node is — by calling a
 * component function directly in the `screen(...) { }` block — and exactly one is allowed, because
 * the file carries one id per screen and the host routes to one root per id.
 */
@PluginDsl
class ScreenBuilder internal constructor() : ScreenNodeScope()

/**
 * The component set a screen may be built from — all twelve types the host renders.
 *
 * The five containers (`column`, `row`, `grid`, `scroll`, `card`) and the seven leaves (`text`,
 * `button`, `slider`, `switch`, `stat`, `spacer`, `image`) are every [ComponentType] the host
 * knows. The interactive four and `scroll` are present here and absent from [OverlayNodeScope] on
 * purpose: a screen is a touchable, scrollable surface, where the overlay is neither.
 */
@PluginDsl
abstract class ScreenNodeScope internal constructor() {

    /** Nodes added at this level, in author order. Emitted as a node's `children`. */
    internal val nodes = JSONArray()

    // ------------------------------------------------------------------------------ containers

    /**
     * A vertical stack. [gap] is dp between children; [align] is the cross (horizontal) axis:
     * `start`, `left`, `center`, `middle`, `end` or `right`. A vertical word is refused rather
     * than passed on, because the host reads an alignment it does not know as the axis default.
     */
    fun column(
        gap: Int? = null,
        align: String? = null,
        padding: Int? = null,
        block: ScreenNodeBuilder.() -> Unit,
    ) {
        container("column", layoutProps(gap, align, SCREEN_HORIZONTAL_ALIGN, padding), block)
    }

    /** A horizontal strip. [align] is the cross (vertical) axis: `top`, `center`, `bottom`, `end`. */
    fun row(
        gap: Int? = null,
        align: String? = null,
        padding: Int? = null,
        block: ScreenNodeBuilder.() -> Unit,
    ) {
        container("row", layoutProps(gap, align, SCREEN_VERTICAL_ALIGN, padding), block)
    }

    /**
     * A fixed-column grid. [columns] is required because the host defaults an absent `columns` to
     * 1 and then validates it — a grid that meant four columns and said nothing would render as a
     * single file of nodes, silently.
     */
    fun grid(
        columns: Int,
        gap: Int? = null,
        padding: Int? = null,
        block: ScreenNodeBuilder.() -> Unit,
    ) {
        require(columns in 1..ScreenBounds.MAX_GRID_COLUMNS) {
            "grid columns is $columns; a screen allows 1..${ScreenBounds.MAX_GRID_COLUMNS}"
        }
        container("grid", layoutProps(gap, null, SCREEN_VERTICAL_ALIGN, padding).put("columns", columns), block)
    }

    /**
     * A scrolling column. The host's own scroll container provides the gesture on a full screen,
     * so a `scroll` here is a bounded sub-region; [align] is its cross (horizontal) axis.
     */
    fun scroll(
        gap: Int? = null,
        align: String? = null,
        padding: Int? = null,
        block: ScreenNodeBuilder.() -> Unit,
    ) {
        container("scroll", layoutProps(gap, align, SCREEN_HORIZONTAL_ALIGN, padding), block)
    }

    /**
     * A surface behind its children. [background] is emitted as the node's `color` prop — the one
     * name the host reads for a colour on every node, a card's being its fill.
     */
    fun card(
        padding: Int? = null,
        background: String? = null,
        gap: Int? = null,
        align: String? = null,
        block: ScreenNodeBuilder.() -> Unit,
    ) {
        val props = layoutProps(gap, align, SCREEN_HORIZONTAL_ALIGN, padding)
        background?.let { props.put("color", hexColor("card background", it)) }
        container("card", props, block)
    }

    // ----------------------------------------------------------------------------------- leaves

    /** A literal label. [color] is `#RRGGBB`/`#AARRGGBB`; [size] is sp; [align] is text alignment. */
    fun text(
        text: String,
        color: String? = null,
        size: Int? = null,
        align: String? = null,
        bold: Boolean = false,
    ) {
        val props = JSONObject().put("text", screenText("text", text, ScreenBounds.MAX_STRING_LENGTH))
        color?.let { props.put("color", hexColor("text color", it)) }
        size?.let { props.put("size", it) }
        align?.let { props.put("align", alignWord(it, SCREEN_HORIZONTAL_ALIGN)) }
        if (bold) props.put("bold", true)
        leaf("text", props)
    }

    /**
     * A labelled control, drawn **inert** in this phase.
     *
     * The host renders a button as a label that is visibly not pressable: this phase dispatches no
     * action, and the DSL deliberately exposes no action binding, so a button here is a styled,
     * read-only affordance. Host-dispatched interactivity is a later phase; adding an `action`
     * parameter now would let an author ship a button that looks live and does nothing.
     */
    fun button(
        text: String,
        color: String? = null,
        size: Int? = null,
        align: String? = null,
    ) {
        val props = JSONObject().put("text", screenText("button text", text, ScreenBounds.MAX_STRING_LENGTH))
        color?.let { props.put("color", hexColor("button color", it)) }
        size?.let { props.put("size", it) }
        align?.let { props.put("align", alignWord(it, SCREEN_HORIZONTAL_ALIGN)) }
        leaf("button", props)
    }

    /**
     * A value bar, drawn **static** at [value] in [min]..[max]. Read-only: the host draws a filled
     * track at the value and no thumb, because a screen reports a setting, it does not change one.
     */
    fun slider(
        value: Double,
        min: Double = 0.0,
        max: Double = 100.0,
        label: String? = null,
        color: String? = null,
    ) {
        require(value.isFinite() && min.isFinite() && max.isFinite()) {
            "slider bounds must be finite numbers (value=$value, min=$min, max=$max)"
        }
        val props = JSONObject().put("value", value).put("min", min).put("max", max)
        label?.let { props.put("label", screenText("slider label", it, ScreenBounds.MAX_STRING_LENGTH)) }
        color?.let { props.put("color", hexColor("slider color", it)) }
        leaf("slider", props)
    }

    /** An on/off pill, drawn **static** at [value]. Read-only, for the same reason [slider] is. */
    fun switch(
        value: Boolean,
        label: String? = null,
        color: String? = null,
    ) {
        val props = JSONObject().put("value", value)
        label?.let { props.put("label", screenText("switch label", it, ScreenBounds.MAX_STRING_LENGTH)) }
        color?.let { props.put("color", hexColor("switch color", it)) }
        leaf("switch", props)
    }

    /**
     * A live figure GameCore reads and fills in. [stat] is one of [OverlayStats].
     *
     * In this phase a screen's stat draws **absent** (an em dash): the live stat feed for screens
     * lands with the measurement phase. It is validated and laid out now so the screen is complete
     * when the feed arrives, and its [OverlayStats.scopeFor] scope will then need to be granted —
     * a missing grant is not malformed JSON, so no codec will catch it for you.
     */
    fun stat(stat: String, label: String? = null, size: Int? = null, color: String? = null) {
        require(stat in OverlayStats.ALL) {
            "unknown stat \"$stat\"; expected one of ${OverlayStats.ALL.joinToString()}"
        }
        val props = JSONObject().put("stat", stat)
        label?.let { props.put("label", screenText("stat label", it, ScreenBounds.MAX_STRING_LENGTH)) }
        size?.let { props.put("size", it) }
        color?.let { props.put("color", hexColor("stat color", it)) }
        leaf("stat", props)
    }

    /** Fixed empty space, in dp. Square, so one node works in a row and a column alike. */
    fun spacer(size: Int? = null) {
        val props = JSONObject()
        size?.let { props.put("size", it) }
        leaf("spacer", props)
    }

    /**
     * One of GameCore's own icons, named from [OverlayIcons]; [size] is dp. A screen ships no
     * artwork — an `image` names a vector already compiled into the app, and an unknown name is
     * refused here rather than drawing a blank placeholder on a device.
     */
    fun image(icon: String, size: Int? = null) {
        require(icon in OverlayIcons.ALL) {
            "unknown icon \"$icon\"; a screen draws one of GameCore's own: ${OverlayIcons.ALL.joinToString()}"
        }
        val props = JSONObject().put("icon", icon)
        size?.let { props.put("size", it) }
        leaf("image", props)
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
        align?.let { props.put("align", alignWord(it, allowedAlign)) }
        padding?.let { props.put("padding", it) }
        return props
    }

    private fun alignWord(raw: String, allowed: Set<String>): String {
        val word = raw.trim().lowercase()
        require(word in allowed) {
            "align is \"$raw\"; this node's axis allows ${allowed.joinToString()}"
        }
        return word
    }

    private fun container(type: String, props: JSONObject, block: ScreenNodeBuilder.() -> Unit) {
        nodes.put(ScreenNodeBuilder(type, props).apply(block).toJson())
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
 * One container node being filled in. Only [ScreenNodeScope] creates these, so a node's type is
 * always one of the twelve the host accepts on a screen.
 */
@PluginDsl
class ScreenNodeBuilder internal constructor(
    private val type: String,
    private val props: JSONObject,
) : ScreenNodeScope() {

    /** `props` and `children` are both omitted when empty, which is how the host reads them. */
    internal fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        if (props.length() > 0) put("props", props)
        if (nodes.length() > 0) put("children", nodes)
    }
}

// ---------------------------------------------------------------------------------- prop encoding

/**
 * The cross-axis alignment words the host recognises per axis, redeclared here because the pill
 * builder keeps its own copies file-private. A word from the wrong axis is a layout the host would
 * silently pin to the start, so it is refused at the call that wrote it.
 */
private val SCREEN_HORIZONTAL_ALIGN = setOf("start", "left", "center", "centre", "middle", "end", "right")
private val SCREEN_VERTICAL_ALIGN = setOf("top", "start", "center", "centre", "middle", "bottom", "end")

/**
 * A string prop the host will sanitise, length- and character-checked against the full-screen cap
 * ([ScreenBounds.MAX_STRING_LENGTH], far wider than an overlay's) and returned unchanged.
 *
 * A leading `@` is doubled — the format reads a single leading `@` as a reference to a declared
 * action, and this phase dispatches none, so a label an author wrote is kept literal. Reuses the
 * pill builder's [plainText], the one place the host's `TextSanitizer` rule is mirrored.
 */
private fun screenText(field: String, raw: String, max: Int): String {
    val checked = plainText(field, raw, max)
    return if (checked.startsWith("@")) "@$checked" else checked
}

/**
 * Walks a finished screen tree for the two bounds a node cannot check as it is built: total node
 * count and depth. Grid width, string length and colour grammar are checked at the call that
 * introduces them. Depth counts the root as 0, matching the host's validator, so a tree of
 * [ScreenBounds.MAX_DEPTH] + 1 levels is the deepest legal one.
 */
private fun checkScreenTree(screenId: String, root: JSONObject) {
    var count = 0

    fun walk(node: JSONObject, depth: Int) {
        count++
        require(count <= ScreenBounds.MAX_NODES) {
            "screen \"$screenId\" is capped at ${ScreenBounds.MAX_NODES} nodes"
        }
        require(depth <= ScreenBounds.MAX_DEPTH) {
            "screen \"$screenId\" is capped at depth ${ScreenBounds.MAX_DEPTH}"
        }
        val children = node.optJSONArray("children") ?: return
        require(children.length() <= ScreenBounds.MAX_CHILDREN) {
            "\"${node.optString("type")}\" in screen \"$screenId\" has ${children.length()} " +
                "children; a screen allows ${ScreenBounds.MAX_CHILDREN}"
        }
        for (i in 0 until children.length()) walk(children.getJSONObject(i), depth + 1)
    }

    walk(root, 0)
}



