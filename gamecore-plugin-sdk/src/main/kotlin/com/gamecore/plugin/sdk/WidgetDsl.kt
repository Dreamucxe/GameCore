package com.gamecore.plugin.sdk

import org.json.JSONObject

/**
 * The authoring surface for the `widgets` section: one or more bounded, read-only cards GameCore
 * draws on its home dashboard.
 *
 * A widget is a small tree of nodes plus an id, a title and a size — data, not code. Each widget emits
 * its own `widgets/<id>.json` file; the host interprets it with its own Compose renderer, exactly as it
 * interprets a theme, an overlay module or a screen. Nothing written here executes.
 *
 * **A widget carries the overlay component set, not the screen's.** [WidgetBuilder] is built on
 * [OverlayNodeScope] — the same scope an overlay module uses — so the nine read-only components
 * (`column`, `row`, `grid`, `card`, `text`, `stat`, `spacer`, `image`, `chart`) are the whole of what a
 * widget may use. The interactive `button`, `slider` and `switch`, and the `scroll` container, are
 * *absent, not refused*: there is no function to call, the same way an overlay module cannot write one.
 * A dashboard card reports a figure; the surface it reports on takes no touch a widget could wire up, so
 * a control is not something an author can lay out here and have quietly drawn inert.
 *
 * ```kotlin
 * widgets {
 *     widget("fps-trend", "Frame rate", WidgetSizes.FULL) {
 *         column(gap = 8, padding = 12) {
 *             text("Last ticks", size = 12)
 *             chart(values = listOf(58, 60, 59, 60, 61), min = 0, max = 90)
 *         }
 *     }
 * }
 * ```
 */
@PluginDsl
class WidgetsBuilder {

    /** Finished widget files, keyed by package path `widgets/<id>.json`, in author order. */
    private val widgets = linkedMapOf<String, JSONObject>()

    /**
     * One widget: an [id] the host registers it under, an optional [title] drawn as its card header, a
     * [size] from [WidgetSizes], and exactly one root node added in [block].
     *
     * The [id] is both the registry key and the file name (`widgets/<id>.json`), so it is held to the
     * platform's id grammar ([OverlayBounds.ID_PATTERN]) in [WidgetBuilder.toJson], and a duplicate is
     * refused here: two widgets under one id would write the same file twice and leave a dashboard entry
     * with no answer to which card it names.
     */
    fun widget(
        id: String,
        title: String = "",
        size: String = WidgetSizes.MEDIUM,
        block: WidgetBuilder.() -> Unit,
    ) {
        val json = WidgetBuilder().apply {
            this.id = id
            this.title = title
            this.size = size
            block()
        }.toJson()

        val path = "widgets/$id.json"
        require(!widgets.containsKey(path)) {
            "two widgets share the id \"$id\"; a widget's id is its file name and its registry key, so a " +
                "duplicate would overwrite the first widget's file and make a dashboard entry ambiguous"
        }
        require(widgets.size < WidgetBounds.MAX_WIDGETS) {
            "a plugin registers at most ${WidgetBounds.MAX_WIDGETS} dashboard widgets"
        }
        widgets[path] = json
    }

    /**
     * The widget files this section emits, keyed by their package path in author order. A section with
     * no widgets is refused rather than written empty, mirroring [ScreensBuilder]: the host rejects a
     * package whose `declares` names a section it then finds no file for.
     */
    internal fun toFiles(): Map<String, JSONObject> {
        require(widgets.isNotEmpty()) { "a widgets section needs at least one widget" }
        return widgets
    }
}

/**
 * One dashboard widget being filled in. Built on [OverlayNodeScope] so it carries exactly the overlay
 * component set, and its single root node is added the same way any other node is — by calling a
 * component function directly in the `widget(...) { }` block. Exactly one root is allowed, because the
 * file carries one id and the host registers one card per widget.
 *
 * Only [WidgetsBuilder] creates one, setting [id], [title] and [size] from the `widget(...)` call before
 * the block runs; they are fields here, mirroring [PillBuilder], so a widget's validation reads the same
 * way an overlay module's does.
 */
@PluginDsl
class WidgetBuilder internal constructor() : OverlayNodeScope() {

    /** The widget's id — its key in the host's dashboard registry and its file name. Required. */
    var id: String = ""

    /** The card header GameCore draws above the widget. Optional; omitted from the file when blank. */
    var title: String = ""

    /** One of [WidgetSizes]. */
    var size: String = WidgetSizes.MEDIUM

    internal fun toJson(): JSONObject {
        require(id.isNotBlank()) { "a dashboard widget needs an id" }
        require(OverlayBounds.ID_PATTERN.matches(id)) {
            "widget id is \"$id\"; an id is lowercase letters, digits, dot, dash or underscore, " +
                "starts with a letter or digit, and is at most ${OverlayBounds.MAX_ID_LENGTH} " +
                "characters — the host lowercases and trims before matching and refuses the rest, " +
                "so any other spelling is renamed or dropped on load"
        }
        require(size in WidgetSizes.ALL) {
            "unknown widget size \"$size\"; expected one of ${WidgetSizes.ALL.joinToString()}"
        }
        require(nodes.length() == 1) {
            "a dashboard widget has exactly one root node, found ${nodes.length()}"
        }

        val content = nodes.getJSONObject(0)
        checkWidgetTree(content)

        return JSONObject().apply {
            put("format", Sdk.WIDGET_FORMAT)
            put("version", Sdk.WIDGET_VERSION)
            put("id", id)
            if (title.isNotEmpty()) {
                put("title", plainText("title", title, OverlayBounds.MAX_TITLE_LENGTH))
            }
            put("size", size)
            put("content", content)
        }
    }

    /**
     * Walks the finished tree for the two bounds a node cannot check as it is built — total node count
     * and depth — against the overlay caps a widget shares (see [WidgetBounds]). Grid width, string
     * length and colour grammar are checked at the call that introduces them, by the [OverlayNodeScope]
     * this builder is. Depth counts the root as 0, matching the host's validator.
     */
    private fun checkWidgetTree(root: JSONObject) {
        var count = 0

        fun walk(node: JSONObject, depth: Int) {
            count++
            require(count <= OverlayBounds.MAX_NODES) {
                "a dashboard widget is capped at ${OverlayBounds.MAX_NODES} nodes; " +
                    "a card this large is a screen, not a readout"
            }
            require(depth <= OverlayBounds.MAX_DEPTH) {
                "a dashboard widget is capped at depth ${OverlayBounds.MAX_DEPTH}"
            }
            val children = node.optJSONArray("children") ?: return
            require(children.length() <= OverlayBounds.MAX_CHILDREN) {
                "\"${node.optString("type")}\" has ${children.length()} children; " +
                    "a dashboard widget allows ${OverlayBounds.MAX_CHILDREN}"
            }
            for (i in 0 until children.length()) walk(children.getJSONObject(i), depth + 1)
        }

        walk(root, 0)
    }
}
