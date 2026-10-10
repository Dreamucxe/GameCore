package com.gamecore.core.plugin.ui

import com.gamecore.core.plugin.theme.PluginThemeCodec
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Turns untrusted JSON into a [ComponentNode] tree, and a tree back into JSON.
 *
 * [ScreenValidator] is the gate that decides whether a tree may be *rendered*; this is the gate
 * that decides whether a tree may be *built at all*. The distinction matters because the
 * validator can only run once a tree exists, and building a tree from a hostile document is
 * itself an unbounded operation: the parse is recursive, so a document nested ten thousand deep
 * overflows the stack long before any validator sees a node, and a document with a million
 * siblings exhausts the heap while the validator is still waiting its turn. So this codec
 * carries its own ceilings — [MAX_PARSE_DEPTH], [MAX_PARSE_NODES], [MAX_PROPS_PER_NODE] and
 * [MAX_CHILDREN_PER_NODE] — set deliberately a little *above* [ScreenValidator]'s defaults. That
 * gap is the point: for every tree a screen is actually allowed to have, the validator is the
 * thing that reports a size problem, with its specific per-rule reason, and these ceilings only
 * catch the documents that are too large to safely construct in the first place.
 *
 * What this codec does *not* do is duplicate the validator. Leaf-with-children, grid column
 * counts and string lengths are shape rules about a finished tree, and there is exactly one
 * place that owns them. A node that parses here is well-*formed*, not yet approved.
 *
 * Three further rules are enforced here because they are properties of the encoding rather than
 * of the tree:
 *
 *  - **An unknown `type` is a rejection, not a skipped node.** Silently dropping a node a newer
 *    API understands would render a screen that looks like it worked while quietly missing part
 *    of itself, which is a worse outcome than refusing the file.
 *  - **Props are primitives.** A nested object, an array, or a JSON `null` in `props` is refused,
 *    because [PropValue] exists precisely to make "arbitrary object" unrepresentable downstream,
 *    and a codec that coerced such a value into a string would be reintroducing it by the back
 *    door. Non-finite numbers go the same way: `1e999` parses to `Infinity`, and an infinite
 *    size or gap propagates into layout arithmetic that never recovers.
 *  - **Unknown node keys are a rejection.** A node is exactly `type`/`props`/`children`; the
 *    forward-compatibility mechanism for the format is the manifest's `api` field, as it is for
 *    `manifest.json`, not per-node key tolerance.
 *
 * Prop *keys*, by contrast, are deliberately open: each component type reads the names it knows
 * and ignores the rest, so a closed key set here would have to enumerate every component's
 * styling vocabulary and would break on every addition. Keys are bounded in count and length
 * instead — it is the *values* that must be constrained in kind.
 *
 * Pure `org.json` plus the UI models, no `android.*`, so every rule above is a JVM unit test.
 */
object PluginNodeCodec {

    // ------------------------------------------------------------------------------------ sigils

    /** Prefix marking a string prop as a reference to a declared action id. */
    private const val ACTION_SIGIL = "@"

    /**
     * Prefix marking the remainder of a string as literal text, exempt from every other sigil.
     *
     * Two string shapes — a leading `@` and a valid `#` hex colour — are read as something other
     * than text, so a label that genuinely begins with one needs a way out. Doubling the sigil
     * is that way out, and it is also what makes [encodeNode] total: any [PropValue.Str] whose
     * value would otherwise be re-read as an action reference or a colour is emitted with this
     * prefix, so the round trip is exact for every string rather than for most of them.
     */
    private const val LITERAL_SIGIL = "@@"

    /** The hex-colour sigil. Only a `#`-prefixed string is ever a candidate colour. */
    private const val COLOR_SIGIL = "#"

    // ------------------------------------------------------------------------------------ bounds

    /**
     * The hard ceiling on parse recursion.
     *
     * Above [ScreenValidator]'s default `maxDepth` of 24 so that a legal screen is never refused
     * here instead of there, and far below the depth at which a JVM stack frame budget is in any
     * danger — the cost of a frame in [decodeAt] is a handful of locals. A document deeper than
     * this is refused before the recursion that would build it, which is the only order in which
     * a depth guard actually protects anything.
     */
    const val MAX_PARSE_DEPTH = 32

    /**
     * The total number of nodes a single document may construct.
     *
     * [MAX_CHILDREN_PER_NODE] bounds breadth at one level only; nothing stops a document from
     * being 100 children wide at every one of 32 levels, which is a number of nodes no device
     * will survive allocating. This is the budget that makes the product of depth and breadth
     * finite. Set just above the validator's default `maxNodes` of 500 for the same reason as
     * the depth ceiling.
     */
    const val MAX_PARSE_NODES = 512

    /** A ceiling on the prop bag of one node, so a scan of it cannot be made unbounded. */
    const val MAX_PROPS_PER_NODE = 64

    /**
     * A ceiling on one node's child list, above the validator's default `maxChildren` of 100.
     * A list longer than this is refused before any of it is read.
     */
    const val MAX_CHILDREN_PER_NODE = 128

    /** Prop names are matched against known component vocabulary; a longer one is not a name. */
    const val MAX_PROP_KEY_LENGTH = 64

    /** A node carries exactly these keys. Anything else is a rejection, never ignored. */
    private val KNOWN_NODE_KEYS = setOf("type", "props", "children")

    // ------------------------------------------------------------------------------------ result

    sealed interface Result {
        data class Ok(val node: ComponentNode) : Result

        /** Carries a human reason, verbatim, for the install screen and the developer tools. */
        data class Rejected(val reason: String) : Result
    }

    // ------------------------------------------------------------------------------------ decode

    /**
     * Decodes one node and, recursively, its subtree.
     *
     * Never throws: a [JSONException] from anywhere in the walk becomes a [Result.Rejected], so
     * a caller can treat this as total and has no error path to forget.
     */
    fun decodeNode(json: JSONObject): Result =
        try {
            decodeAt(json, depth = 0, budget = Budget())
        } catch (e: JSONException) {
            Result.Rejected("malformed node: ${e.message.orEmpty()}")
        }

    /**
     * Convenience overload for raw text, as every other codec in the platform takes it — the SDK
     * and the tests hold a string, and wrapping `JSONObject(raw)` at each call site is exactly
     * the throw this codec exists to absorb.
     */
    fun decodeNode(raw: String?): Result {
        if (raw.isNullOrBlank()) return Result.Rejected("node JSON is empty")
        val root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return Result.Rejected("malformed node JSON: ${e.message.orEmpty()}")
        }
        return decodeNode(root)
    }

    /** The one mutable thing in the walk: nodes built so far, shared by every recursion level. */
    private class Budget {
        var nodes = 0
    }

    private fun decodeAt(json: JSONObject, depth: Int, budget: Budget): Result {
        if (depth > MAX_PARSE_DEPTH) {
            return Result.Rejected("node tree is deeper than $MAX_PARSE_DEPTH")
        }
        budget.nodes++
        if (budget.nodes > MAX_PARSE_NODES) {
            return Result.Rejected("node tree has more than $MAX_PARSE_NODES nodes")
        }

        json.keys().asSequence().firstOrNull { it !in KNOWN_NODE_KEYS }?.let {
            return Result.Rejected("unknown node key \"$it\"")
        }

        val rawType = json.optString("type")
        val type = ComponentType.fromId(rawType)
            ?: return Result.Rejected("unknown component type \"$rawType\"")

        val props = when (val p = decodeProps(json, type)) {
            is PropsParse.Ok -> p.props
            is PropsParse.Err -> return p.rejected
        }

        val children = when (val c = decodeChildren(json, type, depth, budget)) {
            is ChildParse.Ok -> c.children
            is ChildParse.Err -> return c.rejected
        }

        return Result.Ok(ComponentNode(type, props, children))
    }

    // ------------------------------------------------------------------------------------ props

    private sealed interface PropsParse {
        data class Ok(val props: Map<String, PropValue>) : PropsParse
        data class Err(val rejected: Result.Rejected) : PropsParse
    }

    private fun decodeProps(json: JSONObject, type: ComponentType): PropsParse {
        if (!json.has("props")) return PropsParse.Ok(emptyMap())
        val obj = json.optJSONObject("props")
            ?: return PropsParse.Err(Result.Rejected("${type.id} props is not an object"))
        if (obj.length() > MAX_PROPS_PER_NODE) {
            return PropsParse.Err(Result.Rejected("${type.id} has more than $MAX_PROPS_PER_NODE props"))
        }

        val out = LinkedHashMap<String, PropValue>(obj.length())
        for (key in obj.keys()) {
            if (key.isBlank()) {
                return PropsParse.Err(Result.Rejected("${type.id} has a blank prop name"))
            }
            if (key.length > MAX_PROP_KEY_LENGTH) {
                return PropsParse.Err(Result.Rejected("${type.id} prop name longer than $MAX_PROP_KEY_LENGTH"))
            }
            val raw = obj.opt(key)
            // Called out separately from the catch-all below so the author of a `1e999` sees why
            // a number was refused rather than being told it is not a number.
            if (raw is Number && !raw.toDouble().isFinite()) {
                return PropsParse.Err(
                    Result.Rejected("${type.id} prop \"$key\" is not a finite number"),
                )
            }
            val value = decodePropValue(raw)
                ?: return PropsParse.Err(
                    Result.Rejected("${type.id} prop \"$key\" is not a string, number or boolean"),
                )
            out[key] = value
        }
        return PropsParse.Ok(out)
    }

    /**
     * Maps one JSON value onto a [PropValue], or null if it is not a primitive this format
     * carries.
     *
     * The order of the branches is the grammar: booleans and numbers are decided by their JSON
     * type and cannot be confused with anything, and only strings are ambiguous. Among strings
     * the literal sigil is tested *before* the action sigil, because `@@x` starts with `@` too
     * and the escape has to win or it would not be an escape.
     */
    private fun decodePropValue(raw: Any?): PropValue? = when {
        // An explicit JSON null is a rejection rather than an absent prop: the author wrote
        // something, and guessing which default they meant is how a theme ends up invisible.
        raw == null || raw === JSONObject.NULL -> null
        raw is Boolean -> PropValue.Bool(raw)
        raw is Number -> raw.toDouble().takeIf { it.isFinite() }?.let { PropValue.Num(it) }
        raw is String -> decodeStringProp(raw)
        // JSONObject, JSONArray, anything else: not a primitive, and not coerced into one.
        else -> null
    }

    private fun decodeStringProp(raw: String): PropValue = when {
        raw.startsWith(LITERAL_SIGIL) -> PropValue.Str(raw.substring(LITERAL_SIGIL.length))
        raw.startsWith(ACTION_SIGIL) -> PropValue.ActionRef(raw.substring(ACTION_SIGIL.length))
        // A `#` string that is not a valid colour stays text. A label may legitimately begin
        // with a hash ("#1 player"), so refusing it would reject correct content, and defaulting
        // it to a colour would paint with a typo. The colour grammar is the theme codec's, not a
        // second one written here, so the two cannot drift apart.
        raw.startsWith(COLOR_SIGIL) ->
            PluginThemeCodec.parseColor(raw)?.let { PropValue.Color(it) } ?: PropValue.Str(raw)
        else -> PropValue.Str(raw)
    }

    // ------------------------------------------------------------------------------------ children

    private sealed interface ChildParse {
        data class Ok(val children: List<ComponentNode>) : ChildParse
        data class Err(val rejected: Result.Rejected) : ChildParse
    }

    private fun decodeChildren(
        json: JSONObject,
        type: ComponentType,
        depth: Int,
        budget: Budget,
    ): ChildParse {
        if (!json.has("children")) return ChildParse.Ok(emptyList())
        val arr = json.optJSONArray("children")
            ?: return ChildParse.Err(Result.Rejected("${type.id} children is not an array"))
        if (arr.length() > MAX_CHILDREN_PER_NODE) {
            return ChildParse.Err(
                Result.Rejected("${type.id} has more than $MAX_CHILDREN_PER_NODE children"),
            )
        }

        val out = ArrayList<ComponentNode>(arr.length())
        for (i in 0 until arr.length()) {
            val child = arr.optJSONObject(i)
                ?: return ChildParse.Err(Result.Rejected("child $i of ${type.id} is not an object"))
            when (val r = decodeAt(child, depth + 1, budget)) {
                is Result.Ok -> out += r.node
                is Result.Rejected -> return ChildParse.Err(r)
            }
        }
        return ChildParse.Ok(out)
    }

    // ------------------------------------------------------------------------------------ encode

    /**
     * Emits a node as JSON, for the SDK and for the round-trip test.
     *
     * `decodeNode(encodeNode(n))` returns a tree equal to `n` for every tree of allowed types
     * and prop kinds: numbers survive because a JSON number round-trips through `Double`,
     * colours because they are always written with all eight hex digits rather than the six-digit
     * form the decoder would have to assume an alpha for, and strings because any value that
     * would be re-read as a sigil is escaped with [LITERAL_SIGIL] on the way out.
     *
     * The single unrepresentable value is a [PropValue.ActionRef] whose id itself begins with
     * `@`. The decoder can never produce one — such an input is read as an escaped literal — so
     * no tree that came from a plugin contains one, and a hand-built tree that does is a caller
     * inventing an id no action registry will ever hold.
     *
     * `props` and `children` are omitted when empty rather than written as `{}` and `[]`, which
     * matches the format's statement that both are optional and keeps a leaf node one line long.
     */
    fun encodeNode(node: ComponentNode): JSONObject {
        val json = JSONObject()
        json.put("type", node.type.id)

        if (node.props.isNotEmpty()) {
            val props = JSONObject()
            node.props.forEach { (key, value) -> props.put(key, encodePropValue(value)) }
            json.put("props", props)
        }

        if (node.children.isNotEmpty()) {
            val children = JSONArray()
            node.children.forEach { children.put(encodeNode(it)) }
            json.put("children", children)
        }
        return json
    }

    /**
     * One prop value as a JSON-safe primitive.
     *
     * A non-finite [PropValue.Num] becomes `0.0`: `org.json` throws on `NaN` and `Infinity`, and
     * an encoder that threw would hand the SDK a failure mode it cannot sensibly handle. Such a
     * value cannot have come from [decodeNode], which refuses non-finite numbers outright.
     */
    private fun encodePropValue(value: PropValue): Any = when (value) {
        is PropValue.Bool -> value.value
        is PropValue.Num -> if (value.value.isFinite()) value.value else 0.0
        is PropValue.Color -> COLOR_SIGIL + "%08X".format(value.argb)
        is PropValue.ActionRef -> ACTION_SIGIL + value.id
        is PropValue.Str -> escapeStr(value.value)
    }

    /** Escapes a string whose own text would otherwise be read as a colour or an action. */
    private fun escapeStr(value: String): String =
        if (value.startsWith(ACTION_SIGIL) || isColorLiteral(value)) LITERAL_SIGIL + value else value

    private fun isColorLiteral(value: String): Boolean =
        value.startsWith(COLOR_SIGIL) && PluginThemeCodec.parseColor(value) != null
}
