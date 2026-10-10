package com.gamecore.core.plugin.overlay

import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.plugin.ui.ComponentNode
import com.gamecore.core.plugin.ui.ComponentType
import com.gamecore.core.plugin.ui.PluginNodeCodec
import com.gamecore.core.plugin.ui.PluginScreen
import com.gamecore.core.plugin.ui.PropValue
import com.gamecore.core.plugin.ui.ScreenValidator
import org.json.JSONException
import org.json.JSONObject

/**
 * Parses a plugin's `pill.json` into a trusted [PluginOverlayModule], or refuses it with a
 * reason.
 *
 * This is the trust boundary for the only thing a plugin can put over a running game, so it is
 * the strictest codec in the platform. Everything downstream — the registry, the service, the
 * renderer — treats a parsed module as safe to draw in a window the user cannot touch and may not
 * be looking at, which means every way that could go wrong has to be closed here rather than
 * noticed there.
 *
 * The guard chain runs cheapest and most structural first: the envelope (format, version, key
 * set), then the scalar fields, then the node tree, then the tree's content, then its size. Each
 * stage can only be reached by a document that passed the previous one, so a hostile file is
 * refused by the cheapest check that catches it and nothing expensive runs on input that was
 * never going to be accepted.
 *
 * What is **rejected**, and why each is a rejection rather than a repair:
 *
 *  - **Interactive node types** (`BUTTON`, `SLIDER`, `SWITCH`, `SCROLL`). A module is rendered in
 *    a window that does not receive touches, so a node that could want a touch is a promise the
 *    system cannot keep — it would draw a control that silently does nothing, which is worse than
 *    no control. Enforcing it in the codec rather than the renderer means the guarantee holds for
 *    any renderer, and a reviewer checks one list: [PluginOverlayModule.ALLOWED_TYPES].
 *  - **Action references.** Same reasoning, one step further: there is no dispatch path from an
 *    untouchable window, so an `@action` prop could never fire. Accepting and ignoring it would
 *    leave an author debugging a button that is not there.
 *  - **Unknown stat ids.** A module's data appetite has to be statically knowable for the service
 *    to check it against the user's grants, so a reading outside [ModuleStat] is refused rather
 *    than rendered blank. A typo in a stat id must not become an unreadable badge.
 *  - **An unknown size id**, a malformed module id, an unknown key, a wrong format or version:
 *    the same doctrine as `manifest.json`. A file built for a newer format is refused whole
 *    rather than loaded with the parts this build happens to recognise.
 *
 * What is **clamped**, because the mistake is the author's and the fix is obvious: the anchor onto
 * the screen, and the opacity into [PluginOverlayModule.MIN_OPACITY_PERCENT]`..`
 * [PluginOverlayModule.MAX_OPACITY_PERCENT].
 *
 * What is **sanitised**: the title and every string prop, through [TextSanitizer], so a module
 * cannot put a bidi override or a stack of combining marks over the game — the one place on the
 * screen where text drawn outside its own box lands on top of something the user needs to see.
 *
 * Pure `org.json` plus the plugin models. No `android.*` and nothing from the overlay layer, so
 * every rule above is a JVM unit test and the service does the window translation at its own edge.
 */
object PluginOverlayCodec {

    /** The envelope tag. A `pill.json` whose `format` is not exactly this is not ours. */
    const val FORMAT = "gamecore.plugin.pill"

    /** The only module format version this build understands. */
    const val VERSION = 1

    /** The exact set of keys a `pill.json` may carry. Any other key is a rejection. */
    private val KNOWN_KEYS = setOf(
        "format", "version", "id", "title", "anchor", "size", "opacity", "content",
    )

    private val KNOWN_ANCHOR_KEYS = setOf("x", "y")

    /**
     * A module id is validated, not sanitised.
     *
     * The id is a registry key and part of the identity the user sees when they turn one module
     * off, so it has to be a stable, comparable token: lower-case, no whitespace, no Unicode that
     * could make two different ids look identical in a list. A string that is not one of those is
     * not an id in need of cleaning — it is a value that must not be used as a key. Same
     * reasoning as `TextSanitizer.validatePackageName`, with a slug grammar instead of a package
     * one because a module id is local to its plugin.
     */
    private val MODULE_ID = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

    // ------------------------------------------------------------------------------------ result

    /** Why a `pill.json` was refused. Each maps to a line the install screen and SDK can show. */
    enum class Reason {
        EMPTY,
        MALFORMED,
        WRONG_FORMAT,
        BAD_VERSION,
        UNKNOWN_KEY,
        MISSING_FIELD,
        BAD_ID,
        BAD_SIZE,

        /**
         * The `anchor` is present but is not an object — an array, a string, a number.
         *
         * Separate from [BAD_SIZE], which is the *size id* being unknown, and separate from
         * [MALFORMED], which it used to share: a file whose anchor is `[0.5, 0.08]` is perfectly
         * valid JSON, and telling its author "that file isn't valid JSON" sends them to look at
         * the one thing that is not wrong with it. An out-of-range anchor is still clamped, not
         * refused; this is only for an anchor that has no x and y to read.
         */
        BAD_ANCHOR,
        BAD_NODE,
        INTERACTIVE_NODE,
        ACTION_REF,
        BAD_STAT,

        /**
         * The tree failed [ScreenValidator] — too many nodes, too deep, too many children, a
         * leaf carrying children, a grid too wide, a string too long. One reason rather than six
         * because the validator owns those rules and reports its own, verbatim, in the detail.
         */
        BAD_TREE,
    }

    sealed interface Result {
        data class Parsed(val module: PluginOverlayModule) : Result
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

        // The version is read before the key set, because an unknown key means two different
        // things either side of it: in a version-1 file it is a mistake, and in a file from the
        // future it is a field this build cannot honour. Reporting the version is the clearer
        // rejection, and it is the one the author can act on.
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
        if (!MODULE_ID.matches(id)) {
            return Result.Rejected(Reason.BAD_ID, "\"$id\" is not a module id (a-z, 0-9, . _ -)")
        }

        // A module the user cannot name is a module they cannot find to switch off, so a title
        // that sanitises away to nothing falls back to the id rather than being refused.
        val sanitisedTitle = TextSanitizer.sanitizeName(
            root.optString("title"), PluginOverlayModule.MAX_TITLE_LENGTH,
        )
        val title = sanitisedTitle.ifEmpty { id }

        val size = if (root.has("size")) {
            ModuleSize.fromId(root.optString("size"))
                ?: return Result.Rejected(
                    Reason.BAD_SIZE,
                    "\"${root.optString("size")}\" is not one of " +
                        ModuleSize.entries.joinToString(", ") { it.id },
                )
        } else {
            // Absent means the smallest box, not the largest: the default must be the one that
            // covers least of the game.
            ModuleSize.SMALL
        }

        val anchor = when (val a = parseAnchor(root)) {
            is AnchorParse.Ok -> a.anchor
            is AnchorParse.Err -> return a.rejected
        }

        val opacity = root.optInt("opacity", PluginOverlayModule.MAX_OPACITY_PERCENT)

        val contentJson = root.optJSONObject("content")
            ?: return Result.Rejected(Reason.BAD_NODE, "content is not an object")
        val parsed = when (val node = PluginNodeCodec.decodeNode(contentJson)) {
            is PluginNodeCodec.Result.Ok -> node.node
            is PluginNodeCodec.Result.Rejected -> return Result.Rejected(Reason.BAD_NODE, node.reason)
        }

        // The content audit runs before the validator, and in this order deliberately: it is the
        // audit that sanitises string props, and the validator's string-length cap must apply to
        // the text that will actually be drawn rather than to the raw input — the same ordering
        // TextSanitizer itself uses internally.
        val content = when (val a = audit(parsed, depth = 0)) {
            is Audit.Ok -> a.node
            is Audit.Err -> return a.rejected
        }

        val module = PluginOverlayModule(
            id = id,
            title = title,
            anchor = anchor,
            size = size,
            opacityPercent = opacity,
            content = content,
        ).normalised()

        // The existing validator, parameterised down — not a second one. It owns leaf arity, grid
        // columns, depth, node count and string length, and it is the only place that does.
        val verdict = PluginOverlayModule.validator()
            .validate(PluginScreen(id = module.id, title = module.title, root = module.content))
        if (verdict is ScreenValidator.Result.Invalid) {
            return Result.Rejected(Reason.BAD_TREE, verdict.reasons.joinToString("; "))
        }

        return Result.Parsed(module)
    }

    // ------------------------------------------------------------------------------------ anchor

    private sealed interface AnchorParse {
        data class Ok(val anchor: ModuleAnchor) : AnchorParse
        data class Err(val rejected: Result.Rejected) : AnchorParse
    }

    /**
     * Reads the anchor, defaulting to the top-centre strip.
     *
     * The *shape* is strict — an `anchor` that is not an object, or carries a key that is not `x`
     * or `y`, is a rejection, because it means the author believes they positioned the module
     * somewhere this codec is not reading. The *values* are not: they are clamped by
     * [PluginOverlayModule.normalised] once the whole module exists.
     */
    private fun parseAnchor(root: JSONObject): AnchorParse {
        if (!root.has("anchor")) return AnchorParse.Ok(ModuleAnchor.TOP_CENTER)
        val obj = root.optJSONObject("anchor")
            ?: return AnchorParse.Err(Result.Rejected(Reason.BAD_ANCHOR, "anchor is not an object"))
        obj.keys().asSequence().firstOrNull { it !in KNOWN_ANCHOR_KEYS }?.let {
            return AnchorParse.Err(Result.Rejected(Reason.UNKNOWN_KEY, "unknown anchor key \"$it\""))
        }
        val x = obj.optDouble("x", ModuleAnchor.DEFAULT_X.toDouble()).toFloat()
        val y = obj.optDouble("y", ModuleAnchor.DEFAULT_Y.toDouble()).toFloat()
        return AnchorParse.Ok(ModuleAnchor(x, y))
    }

    // ------------------------------------------------------------------------------------ content

    private sealed interface Audit {
        data class Ok(val node: ComponentNode) : Audit
        data class Err(val rejected: Result.Rejected) : Audit
    }

    /**
     * Walks a parsed tree, refusing anything an overlay module may not contain and sanitising the
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
                Result.Rejected(Reason.BAD_TREE, "content nested deeper than ${PluginNodeCodec.MAX_PARSE_DEPTH}"),
            )
        }

        if (node.type !in PluginOverlayModule.ALLOWED_TYPES) {
            return Audit.Err(
                Result.Rejected(
                    Reason.INTERACTIVE_NODE,
                    "\"${node.type.id}\" cannot appear in an overlay module: the window takes no touches",
                ),
            )
        }

        val props = LinkedHashMap<String, PropValue>(node.props.size)
        for ((key, value) in node.props) {
            when (value) {
                is PropValue.ActionRef -> return Audit.Err(
                    Result.Rejected(
                        Reason.ACTION_REF,
                        "prop \"$key\" references action \"${value.id}\"; a module cannot dispatch one",
                    ),
                )
                is PropValue.Str -> props[key] = PropValue.Str(
                    TextSanitizer.sanitizeName(value.value, PluginOverlayModule.MAX_STRING_LENGTH),
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
     * Emits a module as `pill.json`, for the SDK and for the round-trip test.
     *
     * The module is normalised first, so the SDK cannot write a file the loader would then have
     * to correct — an author who sets an anchor off-screen sees the corrected value in the file
     * they shipped rather than discovering it on a device.
     *
     * `decode(encode(m))` returns `m.normalised()` for any module whose id matches the id grammar
     * and whose title and string props are already sanitised, which is true of every module that
     * came from [decode]. A module with a blank title is the one documented difference: it comes
     * back labelled with its id, because that substitution is what makes it findable in Settings.
     */
    fun encode(module: PluginOverlayModule): String {
        val m = module.normalised()
        val json = JSONObject()
        json.put("format", FORMAT)
        json.put("version", VERSION)
        json.put("id", m.id)
        json.put("title", m.title)
        json.put(
            "anchor",
            JSONObject()
                .put("x", m.anchor.xFraction.toDouble())
                .put("y", m.anchor.yFraction.toDouble()),
        )
        json.put("size", m.size.id)
        json.put("opacity", m.opacityPercent)
        json.put("content", PluginNodeCodec.encodeNode(m.content))
        return json.toString()
    }
}
