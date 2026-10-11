package com.gamecore.core.plugin.dashboard

import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.plugin.overlay.ModuleStat
import com.gamecore.core.plugin.ui.ComponentNode
import com.gamecore.core.plugin.ui.ComponentType
import com.gamecore.core.plugin.ui.PluginNodeCodec
import com.gamecore.core.plugin.ui.PluginScreen
import com.gamecore.core.plugin.ui.PropValue
import com.gamecore.core.plugin.ui.ScreenValidator
import org.json.JSONException
import org.json.JSONObject

/**
 * Parses a plugin's `widget.json` into a trusted [PluginDashboardWidget], or refuses it with a
 * reason.
 *
 * This is the trust boundary for a panel a plugin places on the GameCore dashboard, and it is the
 * overlay codec [com.gamecore.core.plugin.overlay.PluginOverlayCodec] cloned onto the home surface:
 * the same guard chain, cheapest and most structural first — the envelope (format, version, key
 * set), then the scalar fields, then the node tree, then the tree's content, then its size. Each
 * stage can only be reached by a document that passed the previous one, so a hostile file is
 * refused by the cheapest check that catches it and nothing expensive runs on input that was never
 * going to be accepted.
 *
 * What is **rejected**, and why each is a rejection rather than a repair:
 *
 *  - **Interactive node types** (`BUTTON`, `SLIDER`, `SWITCH`). A widget is drawn read-only and
 *    dispatches nothing, so a node that could want to dispatch is a promise the system cannot keep
 *    — it would draw a control that silently does nothing, which is worse than no control. `SCROLL`
 *    is *not* in this set, the one place this codec and the overlay's differ on types: the
 *    dashboard is scrolled with a finger, so a scrolling container is legitimate here. The one list
 *    a reviewer checks is [PluginDashboardWidget.ALLOWED_TYPES].
 *  - **Action references.** Same reasoning, one step further: there is no dispatch path on a
 *    read-only widget, so an `@action` prop could never fire. Accepting and ignoring it would leave
 *    an author debugging a button that is not there.
 *  - **A live chart series.** A `chart` may carry its own literal numbers and may also name a live
 *    host feed in `stat`/`stat2`, validated against the [ModuleStat] vocabulary exactly as a `stat`
 *    node is — an id outside it is refused rather than rendered blank. This is where the dashboard
 *    parts company with the overlay codec, which refuses a live series outright: the dashboard host
 *    resolves a chart's live feed where an overlay window cannot. A widget's data-appetite walk
 *    ([PluginDashboardWidget.requiredStats]) folds a chart's series ids too, so a live series reaches
 *    [PluginDashboardWidget.requiredScopes] and the registry gates the whole widget on it. The two
 *    sides are **one change**: relaxing this check without the appetite-walk fold would let a widget
 *    name a feed no granted scope covers, and the registry prefilter could not refuse it.
 *  - **Unknown stat ids.** A widget's data appetite has to be statically knowable for the registry
 *    to check it against the user's grants, so a reading outside [ModuleStat] is refused rather than
 *    rendered blank. A typo in a stat id must not become an unreadable tile.
 *  - **An unknown size id**, a malformed widget id, an unknown key, a wrong format or version: the
 *    same doctrine as `manifest.json`. A file built for a newer format is refused whole rather than
 *    loaded with the parts this build happens to recognise.
 *
 * What is **clamped**, because the mistake is the author's and the fix is obvious: the title to
 * [PluginDashboardWidget.MAX_TITLE_LENGTH], by [PluginDashboardWidget.normalised].
 *
 * What is **sanitised**: the title and every string prop, through [TextSanitizer], so a widget
 * cannot smuggle a bidi override or a stack of combining marks onto the dashboard.
 *
 * Pure `org.json` plus the plugin models. No `android.*` and nothing from the dashboard UI layer,
 * so every rule above is a JVM unit test and the host does the composable translation at its edge.
 */
object PluginDashboardCodec {

    /** The envelope tag. A `widget.json` whose `format` is not exactly this is not ours. */
    const val FORMAT = "gamecore.plugin.widget"

    /** The only widget format version this build understands. */
    const val VERSION = 1

    /**
     * How many widgets the dashboard shows at once, across every installed plugin.
     *
     * Thirty-two, the platform's house number for "how many of these may exist" — the same ceiling
     * [com.gamecore.core.plugin.ui.PluginScreensCodec.MAX_SCREENS] puts on a screens file. A
     * dashboard is a scrolling list rather than the overlay's three fixed slots, so the cap is the
     * list cap and not the overlay's [PluginDashboardWidget]-less three. The registry enforces it,
     * dropping extras in registry order and saying so; this codec parses one widget per file and
     * carries the number so the registry and the SDK read it from one place.
     */
    const val MAX_WIDGETS = 32

    /** The exact set of keys a `widget.json` may carry. Any other key is a rejection. */
    private val KNOWN_KEYS = setOf("format", "version", "id", "title", "size", "content")

    /**
     * A widget id is validated, not sanitised: it is a registry key and part of the identity the
     * user sees when they turn one widget off, so it has to be a stable, comparable token. Same slug
     * grammar as the overlay module id and the screen id — lower-case, no whitespace, no Unicode
     * that could make two different ids look identical in a list.
     */
    private val WIDGET_ID = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

    // ------------------------------------------------------------------------------------ result

    /** Why a `widget.json` was refused. Each maps to a line the install screen and SDK can show. */
    enum class Reason {
        EMPTY,
        MALFORMED,
        WRONG_FORMAT,
        BAD_VERSION,
        UNKNOWN_KEY,
        MISSING_FIELD,
        BAD_ID,
        BAD_SIZE,
        BAD_NODE,
        INTERACTIVE_NODE,
        ACTION_REF,
        BAD_STAT,

        /**
         * The tree failed [ScreenValidator] — too many nodes, too deep, too many children, a leaf
         * carrying children, a grid too wide, a string too long. One reason rather than six because
         * the validator owns those rules and reports its own, verbatim, in the detail.
         */
        BAD_TREE,
    }

    sealed interface Result {
        data class Parsed(val widget: PluginDashboardWidget) : Result
        data class Rejected(val reason: Reason, val detail: String = "") : Result
    }

    // ------------------------------------------------------------------------------------ decode

    /** Never throws: a [JSONException] anywhere in the parse becomes [Reason.MALFORMED]. */
    fun decode(raw: String?): Result {
        if (raw.isNullOrBlank()) return Result.Rejected(Reason.EMPTY)
        val root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return Result.Rejected(Reason.MALFORMED, e.message.orEmpty())
        }

        if (root.optString("format") != FORMAT) {
            return Result.Rejected(Reason.WRONG_FORMAT, "format must be \"$FORMAT\"")
        }

        // The version is read before the key set, for the same reason the overlay codec does it: an
        // unknown key in a version-1 file is a mistake, but in a file from the future it is a field
        // this build cannot honour, and the version is the rejection the author can act on.
        if (!root.has("version")) return Result.Rejected(Reason.MISSING_FIELD, "missing \"version\"")
        val version = root.optInt("version", -1)
        if (version != VERSION) {
            return Result.Rejected(Reason.BAD_VERSION, "version must be $VERSION, got $version")
        }

        root.keys().asSequence().firstOrNull { it !in KNOWN_KEYS }?.let {
            return Result.Rejected(Reason.UNKNOWN_KEY, "unknown key \"$it\"")
        }

        for (required in listOf("id", "content")) {
            if (!root.has(required)) {
                return Result.Rejected(Reason.MISSING_FIELD, "missing \"$required\"")
            }
        }

        val id = root.optString("id").trim().lowercase()
        if (!WIDGET_ID.matches(id)) {
            return Result.Rejected(Reason.BAD_ID, "\"$id\" is not a widget id (a-z, 0-9, . _ -)")
        }

        // A widget the user cannot name is a widget they cannot find to switch off, so a title that
        // sanitises away to nothing falls back to the id rather than being refused.
        val sanitisedTitle = TextSanitizer.sanitizeName(
            root.optString("title"), PluginDashboardWidget.MAX_TITLE_LENGTH,
        )
        val title = sanitisedTitle.ifEmpty { id }

        val size = if (root.has("size")) {
            WidgetSize.fromId(root.optString("size"))
                ?: return Result.Rejected(
                    Reason.BAD_SIZE,
                    "\"${root.optString("size")}\" is not one of " +
                        WidgetSize.ALL.joinToString(", ") { it.id },
                )
        } else {
            // Absent means the ordinary tile. The overlay module defaults to its smallest size
            // because the thing it covers is a game and least coverage is safest; a dashboard widget
            // covers nothing the user is watching, so the sensible default is a usable tile.
            WidgetSize.MEDIUM
        }

        val contentJson = root.optJSONObject("content")
            ?: return Result.Rejected(Reason.BAD_NODE, "content is not an object")
        val parsed = when (val node = PluginNodeCodec.decodeNode(contentJson)) {
            is PluginNodeCodec.Result.Ok -> node.node
            is PluginNodeCodec.Result.Rejected -> return Result.Rejected(Reason.BAD_NODE, node.reason)
        }

        // The content audit runs before the validator, and in this order deliberately: it is the
        // audit that sanitises string props, and the validator's string-length cap must apply to the
        // text that will actually be drawn rather than to the raw input.
        val content = when (val a = audit(parsed, depth = 0)) {
            is Audit.Ok -> a.node
            is Audit.Err -> return a.rejected
        }

        val widget = PluginDashboardWidget(id = id, title = title, size = size, content = content)
            .normalised()

        // The existing validator, parameterised down — not a second one. It owns leaf arity, grid
        // columns, depth, node count and string length, and it is the only place that does.
        val verdict = PluginDashboardWidget.validator()
            .validate(PluginScreen(id = widget.id, title = widget.title, root = widget.content))
        if (verdict is ScreenValidator.Result.Invalid) {
            return Result.Rejected(Reason.BAD_TREE, verdict.reasons.joinToString("; "))
        }

        return Result.Parsed(widget)
    }

    // ------------------------------------------------------------------------------------ content

    private sealed interface Audit {
        data class Ok(val node: ComponentNode) : Audit
        data class Err(val rejected: Result.Rejected) : Audit
    }

    /**
     * Walks a parsed tree, refusing anything a dashboard widget may not contain and sanitising the
     * text in what remains.
     *
     * Recursion is safe here in a way it would not be in a parser: this tree came from
     * [PluginNodeCodec], which refuses a document deeper than its own ceiling before building
     * anything, so the depth is already bounded. The guard below is therefore unreachable in
     * practice and kept anyway, because the cost is one comparison and the alternative is a walk
     * whose safety depends on a bound enforced in another file.
     */
    private fun audit(node: ComponentNode, depth: Int): Audit {
        if (depth > PluginNodeCodec.MAX_PARSE_DEPTH) {
            return Audit.Err(
                Result.Rejected(
                    Reason.BAD_TREE,
                    "content nested deeper than ${PluginNodeCodec.MAX_PARSE_DEPTH}",
                ),
            )
        }

        if (node.type !in PluginDashboardWidget.ALLOWED_TYPES) {
            return Audit.Err(
                Result.Rejected(
                    Reason.INTERACTIVE_NODE,
                    "\"${node.type.id}\" cannot appear in a dashboard widget: a widget is read-only " +
                        "and dispatches nothing",
                ),
            )
        }

        val props = LinkedHashMap<String, PropValue>(node.props.size)
        for ((key, value) in node.props) {
            when (value) {
                is PropValue.ActionRef -> return Audit.Err(
                    Result.Rejected(
                        Reason.ACTION_REF,
                        "prop \"$key\" references action \"${value.id}\"; a widget cannot dispatch one",
                    ),
                )
                is PropValue.Str -> props[key] = PropValue.Str(
                    TextSanitizer.sanitizeName(value.value, PluginDashboardWidget.MAX_STRING_LENGTH),
                )
                else -> props[key] = value
            }
        }

        if (node.type == ComponentType.STAT) {
            val raw = (props[ModuleStat.PROP] as? PropValue.Str)?.value
                ?: return Audit.Err(
                    Result.Rejected(Reason.BAD_STAT, "a stat node has no \"${ModuleStat.PROP}\" prop"),
                )
            if (ModuleStat.fromId(raw) == null) {
                return Audit.Err(
                    Result.Rejected(
                        Reason.BAD_STAT,
                        "unknown stat \"$raw\"; one of " + ModuleStat.entries.joinToString(", ") { it.id },
                    ),
                )
            }
        }

        if (node.type == ComponentType.CHART) {
            for (key in PluginDashboardWidget.CHART_LIVE_SERIES_PROPS) {
                val raw = (props[key] as? PropValue.Str)?.value?.trim()
                if (raw.isNullOrEmpty()) continue // no live series in this slot: a literal chart, unchanged
                if (ModuleStat.fromId(raw) == null) {
                    return Audit.Err(
                        Result.Rejected(
                            Reason.BAD_STAT,
                            "a dashboard chart names an unknown live series \"$raw\" in \"$key\"; " +
                                "one of " + ModuleStat.entries.joinToString(", ") { it.id },
                        ),
                    )
                }
            }
        }

        val children = ArrayList<ComponentNode>(node.children.size)
        for (child in node.children) {
            when (val a = audit(child, depth + 1)) {
                is Audit.Ok -> children += a.node
                is Audit.Err -> return a
            }
        }

        return Audit.Ok(ComponentNode(node.type, props, children))
    }

    // ------------------------------------------------------------------------------------ encode

    /**
     * Emits a widget as `widget.json`, for the SDK and for the round-trip test.
     *
     * The widget is normalised first, so the SDK cannot write a file the loader would then correct
     * — an author who sets an over-long title sees the clamped value in the file they shipped
     * rather than discovering it on a device.
     *
     * `decode(encode(w))` returns `w.normalised()` for any widget whose id matches the id grammar
     * and whose title and string props are already sanitised, which is true of every widget that
     * came from [decode]. A widget with a blank title is the one documented difference: it comes
     * back labelled with its id, because that substitution is what makes it findable in Settings.
     */
    fun encode(widget: PluginDashboardWidget): String {
        val w = widget.normalised()
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("id", w.id)
            .put("title", w.title)
            .put("size", w.size.id)
            .put("content", PluginNodeCodec.encodeNode(w.content))
            .toString()
    }
}

