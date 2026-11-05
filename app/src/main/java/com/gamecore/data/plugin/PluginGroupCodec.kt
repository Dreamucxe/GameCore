package com.gamecore.data.plugin

import com.gamecore.core.plugin.PluginGroup
import com.gamecore.core.plugin.PluginGrouping
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Serialises the user's custom plugin groups to and from one encrypted preference entry.
 *
 * Separate from [SecurePluginGrantStore] for the reason [PluginGrantCodec] is separate from it: the
 * store is a handful of preference calls that needs a Keystore and therefore a device, while the
 * *meaning* of a stored group is a rule that can be proven on the JVM. The rules are small but each
 * one prevents a specific bad state:
 *
 *  - **Only custom ids survive a decode.** A value edited by hand could contain an id beginning with
 *    [PluginGrouping.AUTOMATIC_PREFIX]; accepting it would let a stored group claim to be derived from
 *    the registry and outlive the plugin that justified it. Unknown ids are dropped, not repaired.
 *  - **A malformed value decodes to no groups.** The file is app-private and encrypted, so a parse
 *    failure is a half-written entry or a format change, and the safe reading is "the user has no
 *    custom groups" rather than an exception on a screen that lists plugins.
 *  - **Names and members are capped and sanitised.** A group name is user text rendered in a chip, so
 *    it gets the same treatment as every other name; member ids are validated only for length and
 *    shape because the registry intersects them with installed ids on the way out.
 *  - **Order is preserved.** Group chips are a stable row; an `apply()` landing between two reads must
 *    not shuffle the user's groups under their finger.
 *
 * Pure Kotlin plus `org.json`, so every rule here is a JVM test.
 */
internal object PluginGroupCodec {

    private const val KEY_GROUPS = "groups"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_PLUGIN_IDS = "pluginIds"

    /** A plugin id is a package name; the manifest codec's cap is the same 128 characters. */
    private const val MAX_PLUGIN_ID_LENGTH = 128

    /** Refuse an absurdly long raw value before asking org.json to parse it. */
    private const val MAX_RAW_LENGTH = 1024 * 1024

    private const val EMPTY = "{\"groups\":[]}"

    /** All [groups], or an empty document when encoding cannot produce one. */
    fun encode(groups: List<PluginGroup>): String = runCatching {
        val array = JSONArray()
        groups
            .asSequence()
            .filter { it.id.startsWith(PluginGrouping.CUSTOM_PREFIX) }
            .take(PluginGrouping.MAX_CUSTOM_GROUPS)
            .forEach { group ->
                val name = PluginGrouping.normaliseName(group.name) ?: return@forEach
                val members = JSONArray()
                group.pluginIds
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it.length <= MAX_PLUGIN_ID_LENGTH }
                    .distinct()
                    .take(PluginGrouping.MAX_MEMBERS)
                    .forEach { members.put(it) }
                array.put(
                    JSONObject()
                        .put(KEY_ID, group.id)
                        .put(KEY_NAME, name)
                        .put(KEY_PLUGIN_IDS, members),
                )
            }
        JSONObject().put(KEY_GROUPS, array).toString()
    }.getOrDefault(EMPTY)

    /**
     * The custom groups stored in [raw], or empty when the value is absent or unreadable.
     *
     * Duplicate ids are dropped rather than merged: a duplicated group is a malformed document, and
     * silently combining two names would produce a group the user never created. A repeated member id
     * inside one group is harmless and collapses through the set.
     */
    fun decode(raw: String?): List<PluginGroup> {
        if (raw.isNullOrBlank() || raw.length > MAX_RAW_LENGTH) return emptyList()

        val root = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            return emptyList()
        }

        val array = root.optJSONArray(KEY_GROUPS) ?: return emptyList()
        val seen = HashSet<String>()
        val result = ArrayList<PluginGroup>()

        for (i in 0 until array.length()) {
            if (result.size >= PluginGrouping.MAX_CUSTOM_GROUPS) break
            val objectValue = array.optJSONObject(i) ?: continue
            val id = objectValue.optString(KEY_ID).trim()
            if (!id.startsWith(PluginGrouping.CUSTOM_PREFIX)) continue
            if (id.length > MAX_PLUGIN_ID_LENGTH || !seen.add(id)) continue
            val name = PluginGrouping.normaliseName(objectValue.optString(KEY_NAME)) ?: continue

            val pluginIds = LinkedHashSet<String>()
            val members = objectValue.optJSONArray(KEY_PLUGIN_IDS)
            if (members != null) {
                for (j in 0 until members.length()) {
                    if (pluginIds.size >= PluginGrouping.MAX_MEMBERS) break
                    val pluginId = members.optString(j).trim()
                    if (pluginId.isEmpty() || pluginId.length > MAX_PLUGIN_ID_LENGTH) continue
                    pluginIds += pluginId
                }
            }

            result += PluginGroup(
                id = id,
                name = name,
                pluginIds = pluginIds,
                automatic = false,
            )
        }

        return result
    }
}
