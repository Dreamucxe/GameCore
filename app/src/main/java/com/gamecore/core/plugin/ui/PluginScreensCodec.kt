package com.gamecore.core.plugin.ui

import com.gamecore.core.common.TextSanitizer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Parses a plugin's `screens` section into trusted [PluginScreen]s, or refuses it with a reason.
 *
 * A plugin screen is a declarative node tree — data, not code — that the host draws read-only
 * under the global theme on a real navigation route. This is the trust boundary for that surface,
 * so it is as strict as every other codec in the platform: the envelope and each screen are an
 * exact, closed key set, an unknown key at any depth refuses the whole file, and a bad value
 * refuses the whole section. Everything downstream — the registry, the resolver, the renderer —
 * treats a parsed [PluginScreen] as safe to draw.
 *
 * The section file is a list in one file, the same envelope shape as `rules/rules.json`:
 *
 * ```json
 * { "format": "gamecore.plugin.screens", "version": 1,
 *   "screens": [ { "id": "...", "title": "...", "icon": "...", "content": { <node> } } ] }
 * ```
 *
 * That one shape serves both delivery paths uniformly: a bundled plugin carries a single
 * `screens.json` holding N screens, and a sideloaded one may carry many `screens/<id>.json` files each
 * holding N — the registry walks every entry under the section, decodes each with this codec and
 * accumulates, so neither path is special-cased.
 *
 * The guard chain mirrors [com.gamecore.core.plugin.overlay.PluginOverlayCodec] exactly — cheapest
 * and most structural first: `format`, then `version` (read *before* the key set, so a file from a
 * newer format is reported as a version mismatch rather than as an unknown key), then the envelope
 * key set, then each screen. Two deliberate differences from the overlay codec, both because a
 * screen is a touchable, full-size surface rather than an untouchable readout:
 *
 *  - **The full component set is allowed.** There is no audit pass: `BUTTON`, `SLIDER`, `SWITCH`
 *    and `SCROLL`, action-reference props and `STAT` nodes are all accepted. The renderer draws
 *    the interactive four inert and a `STAT` as absent on a read-only screen (Phase 3 dispatches
 *    nothing and feeds no live stat), so they are safe to carry; refusing them here would reject
 *    legitimate screen content the overlay codec only refuses because its window takes no touches.
 *  - **An unknown `icon` resolves to null rather than rejecting.** An icon is pure chrome — see
 *    [BuiltinIcons]. Everything load-bearing still rejects.
 *
 * Pure `org.json` plus the plugin models and [TextSanitizer]: no `android.*`, so every rule above
 * is a JVM unit test, and [decode] never throws — a [JSONException] anywhere becomes a typed
 * [Reason.MALFORMED].
 */
object PluginScreensCodec {

    /** The envelope tag. A screens file whose `format` is not exactly this is not ours. */
    const val FORMAT = "gamecore.plugin.screens"

    /** The only screens-section format version this build understands. */
    const val VERSION = 1

    /** The screen title, matched to where it is drawn: a TopAppBar and a detail-screen row. */
    const val MAX_TITLE_LENGTH = 64

    /** A ceiling on screens in one file, so a pathological file cannot be walked end to end. */
    const val MAX_SCREENS = 32

    /** The exact set of keys the envelope may carry. Any other key is a rejection. */
    private val KNOWN_KEYS = setOf("format", "version", "screens")

    /** The exact set of keys one screen object may carry. Any other key is a rejection. */
    private val KNOWN_SCREEN_KEYS = setOf("id", "title", "icon", "content")

    /**
     * A screen id is validated, not sanitised: it is the registry key the route resolves against
     * and part of what the user sees, so it must be a stable, comparable slug. Same grammar as the
     * overlay module id — lower-case, no whitespace, no Unicode that could make two ids collide.
     */
    private val SCREEN_ID = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

    // ------------------------------------------------------------------------------------ result

    /** Why a screens file was refused. Each maps to a line the install screen and SDK can show. */
    enum class Reason {
        EMPTY,
        MALFORMED,
        WRONG_FORMAT,
        BAD_VERSION,
        UNKNOWN_KEY,
        MISSING_FIELD,
        BAD_ID,
        DUPLICATE_ID,
        BAD_NODE,

        /**
         * A screen's content tree failed [ScreenValidator] — too many nodes, too deep, too many
         * children, a leaf carrying children, a grid too wide, a string too long. One reason
         * rather than six because the validator owns those rules and reports its own, verbatim.
         */
        BAD_TREE,

        /** Too many screens in one file, over [MAX_SCREENS]. */
        TOO_MANY,
    }

    sealed interface Result {
        data class Parsed(val screens: List<PluginScreen>) : Result
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

        // The version is read before the key set, for the same reason the overlay codec does it:
        // an unknown key in a version-1 file is a mistake, but in a file from the future it is a
        // field this build cannot honour, and the version is the rejection the author can act on.
        if (!root.has("version")) return Result.Rejected(Reason.MISSING_FIELD, "missing \"version\"")
        val version = root.optInt("version", -1)
        if (version != VERSION) {
            return Result.Rejected(Reason.BAD_VERSION, "version must be $VERSION, got $version")
        }

        root.keys().asSequence().firstOrNull { it !in KNOWN_KEYS }?.let {
            return Result.Rejected(Reason.UNKNOWN_KEY, "unknown key \"$it\"")
        }

        if (!root.has("screens")) return Result.Rejected(Reason.MISSING_FIELD, "missing \"screens\"")
        val array = root.optJSONArray("screens")
            ?: return Result.Rejected(Reason.MALFORMED, "\"screens\" is not an array")
        if (array.length() > MAX_SCREENS) {
            return Result.Rejected(Reason.TOO_MANY, "a screens file holds at most $MAX_SCREENS screens")
        }

        // Last-seen does not win inside one file: a duplicate id is a mistake the author should be
        // told about, because the route resolves a screen by id and a file with two "home" screens
        // has no answer to which one a link opens. (Across files the registry keeps first-seen; a
        // collision there is between files, not an author writing the same id twice in one.)
        val seen = LinkedHashSet<String>()
        val out = ArrayList<PluginScreen>(array.length())
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i)
                ?: return Result.Rejected(Reason.MALFORMED, "screen $i is not an object")
            when (val parsed = parseScreen(entry, i, seen)) {
                is ScreenParse.Ok -> {
                    seen += parsed.screen.id
                    out += parsed.screen
                }
                is ScreenParse.Err -> return parsed.rejected
            }
        }
        return Result.Parsed(out)
    }

    // ------------------------------------------------------------------------------------ screen

    private sealed interface ScreenParse {
        data class Ok(val screen: PluginScreen) : ScreenParse
        data class Err(val rejected: Result.Rejected) : ScreenParse
    }

    private fun parseScreen(entry: JSONObject, index: Int, seen: Set<String>): ScreenParse {
        entry.keys().asSequence().firstOrNull { it !in KNOWN_SCREEN_KEYS }?.let {
            return ScreenParse.Err(Result.Rejected(Reason.UNKNOWN_KEY, "screen $index: unknown key \"$it\""))
        }

        for (required in listOf("id", "content")) {
            if (!entry.has(required)) {
                return ScreenParse.Err(Result.Rejected(Reason.MISSING_FIELD, "screen $index: missing \"$required\""))
            }
        }

        val id = entry.optString("id").trim().lowercase()
        if (!SCREEN_ID.matches(id)) {
            return ScreenParse.Err(Result.Rejected(Reason.BAD_ID, "\"$id\" is not a screen id (a-z, 0-9, . _ -)"))
        }
        if (id in seen) {
            return ScreenParse.Err(Result.Rejected(Reason.DUPLICATE_ID, "two screens share the id \"$id\""))
        }

        // A screen the user reaches by its title falling back to its id rather than being refused,
        // the same substitution the overlay codec makes for a module whose title sanitises away.
        val title = TextSanitizer.sanitizeName(entry.optString("title"), MAX_TITLE_LENGTH).ifEmpty { id }

        // Unknown icon → null (chrome, not load-bearing), per BuiltinIcons.resolve.
        val icon = BuiltinIcons.resolve(entry.optString("icon"))

        val contentJson = entry.optJSONObject("content")
            ?: return ScreenParse.Err(Result.Rejected(Reason.BAD_NODE, "screen \"$id\": content is not an object"))
        val root = when (val node = PluginNodeCodec.decodeNode(contentJson)) {
            is PluginNodeCodec.Result.Ok -> node.node
            is PluginNodeCodec.Result.Rejected ->
                return ScreenParse.Err(Result.Rejected(Reason.BAD_NODE, "screen \"$id\": ${node.reason}"))
        }

        val screen = PluginScreen(id = id, title = title, root = root, icon = icon)

        // The existing validator at its defaults — the full-screen caps, not the overlay's tighter
        // ones. It owns leaf arity, grid columns, depth, node count and string length, and the full
        // component set is allowed because no audit runs before it: a screen may hold interactive
        // nodes and stat nodes, which the renderer draws inert and absent respectively in Phase 3.
        val verdict = ScreenValidator().validate(screen)
        if (verdict is ScreenValidator.Result.Invalid) {
            return ScreenParse.Err(
                Result.Rejected(Reason.BAD_TREE, "screen \"$id\": " + verdict.reasons.joinToString("; ")),
            )
        }

        return ScreenParse.Ok(screen)
    }

    // ------------------------------------------------------------------------------------ encode

    /**
     * Emits a list of screens as a section file, for the SDK and for the round-trip test.
     *
     * `decode(encode(list))` returns a list equal to `list` for every screen whose id matches the
     * grammar, whose title is already sanitised, and whose icon is null or a known [BuiltinIcons]
     * name — which is true of every screen that came from [decode]. `icon` is written only when
     * present, so a screen with no icon round-trips to a screen with no icon rather than one whose
     * icon key is an empty string.
     */
    fun encode(screens: List<PluginScreen>): String {
        val array = JSONArray()
        for (screen in screens) {
            val obj = JSONObject()
            obj.put("id", screen.id)
            obj.put("title", screen.title)
            screen.icon?.let { obj.put("icon", it) }
            obj.put("content", PluginNodeCodec.encodeNode(screen.root))
            array.put(obj)
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("screens", array)
            .toString()
    }
}
