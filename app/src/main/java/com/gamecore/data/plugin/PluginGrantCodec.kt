package com.gamecore.data.plugin

import com.gamecore.core.plugin.PluginGrant
import com.gamecore.core.plugin.PluginNamespace
import com.gamecore.core.plugin.PluginPermission
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Serialises a [PluginGrant] to and from the one string an encrypted preference entry can hold, and
 * decides which storage keys and values a plugin is allowed to write.
 *
 * This exists apart from [SecurePluginGrantStore] because of where the risk actually sits. The store
 * is a handful of `getString`/`putString` calls around `EncryptedSharedPreferences`, which needs a
 * Keystore and therefore a device; the *meaning* of a stored grant is the authority every plugin API
 * call is checked against, and that must be provable on the JVM. Splitting them means every rule
 * below is a unit test (`PluginGrantCodecTest`) rather than something only observable by installing a
 * plugin on a phone and hoping.
 *
 * Five decisions, each with a reason:
 *
 *  1. **An unknown permission id is dropped, not rejected.** A grant written by a build that had a
 *     permission this one no longer ships must not resurrect it, and must not invalidate the rest of
 *     the record either — refusing the whole grant would silently revoke consents the user did give,
 *     which is a worse outcome than forgetting one that no longer exists.
 *  2. **Malformed JSON decodes to an empty grant, never an exception.** The file is app-private and
 *     encrypted, so a value that will not parse means a half-written entry or a format change, not an
 *     attack — and the only safe reading of "I cannot tell what you granted" is "nothing".
 *  3. **Scopes are stored lowercase and trimmed**, because [PluginGrant.hasScope] compares against
 *     `scope.trim().lowercase()`. A grant stored with `"Sessions"` would read back as granted and then
 *     fail every scoped check, which is the worst kind of permission bug: one that looks allowed in
 *     the UI and denies at the call.
 *  4. **Key acceptance goes through [PluginNamespace.qualify]** rather than a second copy of the key
 *     grammar. `PluginNamespace.KEY_GRAMMAR` is private, and a restated regex is a drift waiting to
 *     happen — the one that matters would be the one here, loosened by a typo, long after the gate's
 *     own tests still pass.
 *  5. **Two ceilings exist at all** ([MAX_VALUE_LENGTH], [MAX_KEYS_PER_PLUGIN]) because a plugin's
 *     stored values are the one part of the platform where a plugin chooses how many bytes the host
 *     writes. Without a cap, a plugin that writes in a loop costs the user storage and costs every
 *     later read a larger file to decrypt.
 *
 * Pure Kotlin and `org.json` — no Android — so nothing here needs a device.
 */
internal object PluginGrantCodec {

    /** Longest value a plugin may store under one key. Fits a serialised layout, not a file. */
    const val MAX_VALUE_LENGTH = 4096

    /** How many keys one plugin may hold. Bounds the encrypted file against a write loop. */
    const val MAX_KEYS_PER_PLUGIN = 256

    private const val KEY_PERMISSIONS = "permissions"
    private const val KEY_ADVANCED = "advancedAccess"
    private const val EMPTY_GRANT_JSON = "{}"

    /**
     * Matches [PluginManifestCodec][com.gamecore.core.plugin.PluginManifestCodec]'s own scope cap, so
     * a stored grant can never carry more scopes than a manifest was allowed to request.
     */
    private const val MAX_SCOPES_PER_PERMISSION = 32

    /**
     * The id length cap the manifest codec applies. Restated as a number only because that constant is
     * private there; a qualified key built from a longer id is not one this app wrote.
     */
    private const val MAX_PLUGIN_ID_LENGTH = 128

    /** A plugin id is a package name, so it always has at least two dot-separated segments. */
    private const val MIN_ID_SEGMENTS = 2

    private const val PROBE_ID = "probe"

    /**
     * The shared prefix of every namespaced key (`plugin.`), derived from [PluginNamespace] rather
     * than written out again, so the two cannot drift if the namespace scheme ever changes.
     */
    private val NAMESPACE_ROOT: String = PluginNamespace.of(PROBE_ID).removeSuffix("$PROBE_ID.")

    // ------------------------------------------------------------------------------------ grants

    /**
     * The grant as one JSON object: permission id to its scope array, plus the advanced opt-in.
     *
     * Ids rather than enum names, because an enum constant can be renamed in a refactor while the id
     * is part of the plugin format and cannot — a stored grant has to survive the former.
     */
    fun encode(grant: PluginGrant): String = runCatching {
        val permissions = JSONObject()
        for ((permission, scopes) in grant.granted) {
            val array = JSONArray()
            scopes.take(MAX_SCOPES_PER_PERMISSION).forEach { array.put(it.trim().lowercase()) }
            permissions.put(permission.id, array)
        }
        JSONObject()
            .put(KEY_PERMISSIONS, permissions)
            .put(KEY_ADVANCED, grant.advancedAccess)
            .toString()
    }.getOrDefault(EMPTY_GRANT_JSON)
    // Unreachable with these inputs — the keys are enum ids and the values are strings. If org.json
    // ever refuses one anyway, an empty grant is the failure to have: it costs the user a consent
    // they can give again, where a thrown exception would take the write path down with it.

    /**
     * The grant stored for [pluginId], or an empty one.
     *
     * [pluginId] comes from the registry's own record, never from the stored string, so a tampered or
     * stale entry cannot claim to be another plugin's grant.
     */
    fun decode(pluginId: String, raw: String?): PluginGrant {
        if (raw.isNullOrBlank()) return PluginGrant.none(pluginId)

        val root = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            return PluginGrant.none(pluginId)
        }

        val granted = LinkedHashMap<PluginPermission, Set<String>>()
        val permissions = root.optJSONObject(KEY_PERMISSIONS)
        if (permissions != null) {
            // Bounded by the enum, not by the file: an unknown id is skipped and a repeated known one
            // overwrites, so the map cannot grow past PluginPermission.entries.size however long the
            // stored object is.
            for (id in permissions.keys().asSequence().toList()) {
                val permission = PluginPermission.fromId(id) ?: continue
                granted[permission] = decodeScopes(permissions.optJSONArray(id))
            }
        }

        return PluginGrant(
            pluginId = pluginId,
            granted = granted,
            advancedAccess = root.optBoolean(KEY_ADVANCED, false),
        )
    }

    private fun decodeScopes(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        val out = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            if (out.size >= MAX_SCOPES_PER_PERMISSION) break
            val scope = array.optString(i).trim().lowercase()
            if (scope.isNotEmpty()) out += scope
        }
        return out
    }

    // ------------------------------------------------------------------------------------ keys

    /**
     * May [pluginId] read or write the fully-qualified [key]?
     *
     * Two conditions, and both have to hold: the key is inside this plugin's namespace
     * ([PluginNamespace.belongsTo]), and it is a key this plugin could legitimately have produced —
     * checked by stripping the namespace and putting the remainder back through
     * [PluginNamespace.qualify], which applies the private key grammar and
     * [PluginNamespace.MAX_KEY_LENGTH] for us. Requiring the round-trip to land on the exact same
     * string is what rules out a key that merely starts with the right prefix.
     *
     * The near miss this is really for: plugin `a.bc` and a key inside `a.b`'s namespace. The trailing
     * dot [PluginNamespace.of] puts on the prefix is what separates them, and this returns false in
     * both directions.
     */
    fun isAcceptableKey(pluginId: String, key: String): Boolean {
        if (!PluginNamespace.belongsTo(pluginId, key)) return false
        val bare = key.removePrefix(PluginNamespace.of(pluginId))
        return PluginNamespace.qualify(pluginId, bare) == key
    }

    /**
     * The budget group a qualified key counts against, or null when it is not a well-formed namespaced
     * key at all.
     *
     * [PluginGrantStore][com.gamecore.core.plugin.PluginGrantStore]'s value methods are handed a key
     * that is already qualified and *not* the plugin id, so the store cannot attribute a key to a
     * plugin by asking. It can still bound it: a plugin id is a package name and therefore has at
     * least two segments, so the first two segments after the namespace root are contained in every
     * namespace the key could belong to. Grouping by them is the coarsest rule that can never
     * under-count one plugin's own keys — and it is no coarser than [PluginNamespace]'s prefix
     * isolation already is, since a plugin `a.b` and a plugin `a.b.c` share a key space there too.
     *
     * The qualify round-trip against that candidate id is also the shape check: it rejects a key that
     * is not under the root, has an empty or dotted-traversal segment, or carries a character the key
     * grammar does not admit. For a plugin with a deeper id the length cap then lands on a slightly
     * longer remainder than its own key — that only tightens the limit, so it is left alone.
     */
    fun budgetGroupOf(qualifiedKey: String): String? {
        if (!qualifiedKey.startsWith(NAMESPACE_ROOT)) return null
        val segments = qualifiedKey.removePrefix(NAMESPACE_ROOT).split('.')
        if (segments.size <= MIN_ID_SEGMENTS) return null
        val candidateId = segments.take(MIN_ID_SEGMENTS).joinToString(".")
        if (candidateId.length > MAX_PLUGIN_ID_LENGTH) return null
        val bare = segments.drop(MIN_ID_SEGMENTS).joinToString(".")
        if (PluginNamespace.qualify(candidateId, bare) != qualifiedKey) return null
        return PluginNamespace.of(candidateId)
    }

    // ------------------------------------------------------------------------------------ values

    /**
     * Is [value] small enough to store?
     *
     * Length only. The *content* stays untrusted after this returns true — a stored value is plugin
     * text like any other and still goes through
     * [TextSanitizer][com.gamecore.core.common.TextSanitizer] before it is drawn. This cap is about
     * what the host writes to disk, not about what the host renders.
     */
    fun isAcceptableValue(value: String): Boolean = value.length <= MAX_VALUE_LENGTH
}
