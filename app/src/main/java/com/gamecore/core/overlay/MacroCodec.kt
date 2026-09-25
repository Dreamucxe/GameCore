package com.gamecore.core.overlay

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Serializes the macro list (§14) to and from the single JSON string [SecurePreferenceStore] keeps for it,
 * the sibling of the aimlab `ConfigCodec` and of [com.gamecore.core.export.SampleExport].
 *
 * A macro carries free user text (its name) and an ordered list, which is exactly the shape the store's
 * `"|"`-joined-names convention cannot hold: a name may contain the separator, and the order and grouping of
 * a macro's actions are structure a flat string loses. So a macro list is stored as JSON — one array of
 * `{ "id", "name", "actions": [OverlayAction.name, ...] }` — and this is the one place that shape is written
 * down. `org.json` ships inside `android.jar`, so nothing extra reaches the APK; the unit test source set
 * puts a real implementation ahead of the stub so the round-trip and the dropping below are actually parsed.
 *
 * [decode] is **total**: a blank key, a malformed document, an object of the wrong shape, an action name a
 * newer build wrote or an older one has since removed — every one of them degrades to *fewer macros*, never
 * an exception. A corrupt macro store must let the overlay open with no macros rather than not open at all,
 * and the next edit rewrites the key clean. Stricter, reporting validation for a *foreign* file belongs to
 * the config-import layer (§20), which owns the byte cap and the "tell the user why" that an internal
 * self-heal does not want.
 *
 * The honest work — sanitising names, dropping non-replayable and duplicate actions, capping both counts —
 * is [MacroLibrary.normalise]'s, run on everything [decode] parses, so a value that reaches a caller is one
 * the editor and the sheet can trust without re-checking.
 */
object MacroCodec {

    /**
     * A ceiling on how many array entries [decode] will even look at, at both levels. Generous — well past
     * [MacroLibrary.MAX_MACROS] and [MacroLibrary.MAX_ACTIONS], which do the real capping — so it never
     * trims a legitimate store, and present only so a pathological array in a hand-edited or injected key
     * cannot be walked end to end before the caps apply.
     */
    private const val SCAN_LIMIT = 256

    /** Serializes the list faithfully; callers keep it normalised through [MacroLibrary]. Never throws. */
    fun encode(macros: List<Macro>): String {
        val array = JSONArray()
        macros.forEach { macro ->
            array.put(
                JSONObject().apply {
                    put("id", macro.id)
                    put("name", macro.name)
                    put("actions", JSONArray().apply { macro.actions.forEach { put(it.name) } })
                },
            )
        }
        return array.toString()
    }

    /**
     * Parses the stored string back to a normalised macro list, or an empty one for anything it cannot make
     * sense of. See the class note for why this never throws.
     *
     * Stored ids are treated as local handles with no meaning across a load — the editor and the sheet only
     * ever address ids within one loaded list — so a fresh sequential id is assigned in file order. That is
     * what stops a hand-edited or imported file whose macros share an id (or omit it) from collapsing under
     * [MacroLibrary.normalise]'s id de-dupe into a single macro the editor could not tell apart.
     */
    fun decode(json: String?): List<Macro> {
        if (json.isNullOrBlank()) return emptyList()
        val array = try {
            JSONArray(json)
        } catch (_: JSONException) {
            return emptyList()
        }
        val raw = ArrayList<Macro>()
        val scan = minOf(array.length(), SCAN_LIMIT)
        for (i in 0 until scan) {
            val obj = array.optJSONObject(i) ?: continue
            raw += Macro(
                id = raw.size + 1L,
                name = obj.optString("name", ""),
                actions = decodeActions(obj.optJSONArray("actions")),
            )
        }
        return MacroLibrary.normalise(raw)
    }

    /**
     * Resolves an action-name array to real [OverlayAction]s, dropping every name this build does not know
     * through [OverlayAction.of]. Non-replayable actions that resolve fine (e.g. a stored `COLOR`) are left
     * for [Macro.normalised] to drop, so both a stranger and a known-but-barred action fall out — the
     * "unknown-action dropping" §14 asks for, done at the one boundary.
     */
    private fun decodeActions(array: JSONArray?): List<OverlayAction> {
        if (array == null) return emptyList()
        val out = ArrayList<OverlayAction>()
        val scan = minOf(array.length(), SCAN_LIMIT)
        for (i in 0 until scan) {
            OverlayAction.of(array.optString(i, ""))?.let { out += it }
        }
        return out
    }
}
