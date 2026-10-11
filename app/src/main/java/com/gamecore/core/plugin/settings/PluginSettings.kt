package com.gamecore.core.plugin.settings

import com.gamecore.core.plugin.ApiResult
import com.gamecore.core.plugin.PluginApiFacade
import com.gamecore.core.plugin.PluginGrantStore
import com.gamecore.core.plugin.PluginNamespace
import com.gamecore.core.plugin.PluginPermission
import com.gamecore.core.plugin.PluginRuntimeState

/**
 * The typed read/write surface for a plugin's own settings.
 *
 * A plugin is data: it never calls this. The host does, on the plugin's behalf, when it renders the
 * plugin's settings UI or applies a value the plugin declared. Every call is funnelled through
 * [PluginApiFacade.call], so the four guarantees — permission gate, rate limit, clamp, audit — apply in
 * one place and no new call site can skip them. The permission is always [PluginPermission.STORE_DATA],
 * a LOW-tier, unscoped grant; isolation does not rest on it but on [PluginNamespace.qualify], which
 * confines every key to the plugin's own namespace whatever the grant says.
 *
 * The store holds only strings. This surface is where the typed [SettingValue]s a caller passes meet the
 * flat string store, mediated by the setting's [SettingDef]:
 *
 *  - [get] reads the raw string, decodes and coerces it against the definition, and falls back to the
 *    definition's default whenever the key is absent, no longer parses, or no longer fits its bounds —
 *    a read never fails on stale data, it degrades to the default.
 *  - [set] coerces the caller's value first and *rejects* a value of the wrong type (an [ApiResult.Error]
 *    from the facade), because a bad write is a host bug worth surfacing, not something to paper over;
 *    an in-range-but-out-of-bounds number is clamped and stored, never refused.
 *
 * The schema is the trusted [PluginSettingsSchema] the codec produced at load; the caller holds it and
 * passes it in, so this surface carries no per-plugin state and is safe to share as a singleton.
 */
class PluginSettings(
    private val facade: PluginApiFacade,
    private val store: PluginGrantStore,
    private val readLimit: Int = DEFAULT_READ_LIMIT,
    private val writeLimit: Int = DEFAULT_WRITE_LIMIT,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {

    /**
     * The current value of setting [id] for [state]'s plugin, or the definition's default when the key
     * is absent or unreadable. Denied/limited calls return the matching [ApiResult]; an unknown [id] is
     * an [ApiResult.Error].
     */
    fun get(state: PluginRuntimeState, schema: PluginSettingsSchema, id: String): ApiResult<SettingValue> =
        facade.call(state, OP_READ, PluginPermission.STORE_DATA, scope = null, rateLimit = readLimit, windowMs = windowMs) {
            val def = schema.byId(id) ?: throw IllegalArgumentException("unknown setting \"$id\"")
            val qualified = qualify(state, def)
            val stored = store.readValue(qualified)
            val decoded = stored?.let { def.decode(it) }?.let { def.coerce(it) }
            decoded ?: def.default
        }

    /**
     * Store [value] for setting [id], returning the value actually stored after coercion (a clamped
     * number, a truncated string). A value of the wrong type for the setting, or an unknown [id], is an
     * [ApiResult.Error]; a denied or rate-limited call returns the matching [ApiResult].
     */
    fun set(
        state: PluginRuntimeState,
        schema: PluginSettingsSchema,
        id: String,
        value: SettingValue,
    ): ApiResult<SettingValue> =
        facade.call(state, OP_WRITE, PluginPermission.STORE_DATA, scope = null, rateLimit = writeLimit, windowMs = windowMs) {
            val def = schema.byId(id) ?: throw IllegalArgumentException("unknown setting \"$id\"")
            val coerced = def.coerce(value)
                ?: throw IllegalArgumentException("value does not match the type of setting \"$id\"")
            val encoded = def.encode(coerced)
                ?: throw IllegalStateException("could not encode value for setting \"$id\"")
            val qualified = qualify(state, def)
            store.writeValue(qualified, encoded)
            coerced
        }

    /**
     * The fully-qualified storage key for [def] in [state]'s namespace. The codec already held the id to
     * a grammar [PluginNamespace.qualify] accepts, so a null here is a contract violation, not a plugin
     * input error — it becomes an [ApiResult.Error] rather than a silent miss.
     */
    private fun qualify(state: PluginRuntimeState, def: SettingDef): String =
        PluginNamespace.qualify(state.grant.pluginId, def.id)
            ?: throw IllegalStateException("setting id \"${def.id}\" is not a storable key")

    companion object {
        const val OP_READ = "settings.read"
        const val OP_WRITE = "settings.write"

        const val DEFAULT_READ_LIMIT = 120
        const val DEFAULT_WRITE_LIMIT = 60
        const val DEFAULT_WINDOW_MS = 10_000L
    }
}
