package com.gamecore.plugin.sdk

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a plugin's `settings.json`: a flat, ordered list of typed settings a plugin keeps in its own
 * isolated, host-encrypted store.
 *
 * A setting is a *declaration*, not a value: it names an id, a type, a default, and whatever bounds the
 * type needs. The host parses this into a trusted schema and then validates every get/set against it —
 * clamping a number out of range, truncating a string over its cap, falling back to the default when a
 * stored string no longer parses. Writing a value at runtime is a separate act behind
 * [Permissions.STORE_DATA]; this section only describes the shape.
 *
 * **The id doubles as the storage key.** The host confines each key to the plugin's own namespace and
 * refuses one that is not a simple dotted name, so this builder holds ids to the same grammar at build
 * time — an id that parses here is one the host can always read and write, never a value that installs
 * and then cannot be stored.
 *
 * Every bound is **refused** here rather than left for the host to clamp, the same bargain the theme and
 * overlay builders strike: a default the host quietly moved into range is one the author never saw.
 *
 * ```kotlin
 * settings {
 *     bool("haptics", default = true)
 *     int("refreshHint", default = 120, min = 60, max = 240)
 *     enum("units", options = listOf("metric", "imperial"), default = "metric")
 *     string("label", default = "", maxLength = 64)
 * }
 * ```
 */
@PluginDsl
class SettingsBuilder {
    private val settings = JSONArray()
    private val ids = linkedSetOf<String>()

    /** A boolean setting. */
    fun bool(id: String, default: Boolean) {
        put(id, SettingTypes.BOOL) { put("default", default) }
    }

    /** An integer setting, optionally bounded. The host clamps a stored value to [min]..[max]. */
    fun int(id: String, default: Int, min: Int? = null, max: Int? = null) {
        if (min != null && max != null) {
            require(min <= max) { "setting \"$id\": min ($min) is greater than max ($max)" }
        }
        require(min == null || default >= min) { "setting \"$id\": default $default is below min $min" }
        require(max == null || default <= max) { "setting \"$id\": default $default is above max $max" }
        put(id, SettingTypes.INT) {
            put("default", default)
            if (min != null) put("min", min)
            if (max != null) put("max", max)
        }
    }

    /** A floating-point setting, optionally bounded. The host clamps a stored value to [min]..[max]. */
    fun float(id: String, default: Double, min: Double? = null, max: Double? = null) {
        require(default.isFinite()) { "setting \"$id\": default $default is not a finite number" }
        require(min == null || min.isFinite()) { "setting \"$id\": min $min is not finite" }
        require(max == null || max.isFinite()) { "setting \"$id\": max $max is not finite" }
        if (min != null && max != null) {
            require(min <= max) { "setting \"$id\": min ($min) is greater than max ($max)" }
        }
        require(min == null || default >= min) { "setting \"$id\": default $default is below min $min" }
        require(max == null || default <= max) { "setting \"$id\": default $default is above max $max" }
        put(id, SettingTypes.FLOAT) {
            put("default", default)
            if (min != null) put("min", min)
            if (max != null) put("max", max)
        }
    }

    /**
     * An enum setting: a closed set of string [options] and a [default] that must be one of them. The
     * host rejects a stored value that is not in the set and falls back to the default.
     */
    fun enum(id: String, options: List<String>, default: String) {
        require(options.isNotEmpty()) { "setting \"$id\": an enum needs at least one option" }
        require(options.size <= SettingBounds.MAX_ENUM_OPTIONS) {
            "setting \"$id\": ${options.size} options; the cap is ${SettingBounds.MAX_ENUM_OPTIONS}"
        }
        val seen = linkedSetOf<String>()
        options.forEach { option ->
            plainText("setting \"$id\" option", option, SettingBounds.MAX_STRING_DEFAULT)
            require(option.isNotBlank()) { "setting \"$id\": an enum option is blank" }
            require(seen.add(option)) { "setting \"$id\": duplicate enum option \"$option\"" }
        }
        require(default in options) {
            "setting \"$id\": default \"$default\" is not one of ${options.joinToString()}"
        }
        put(id, SettingTypes.ENUM) {
            put("default", default)
            put("options", JSONArray(options))
        }
    }

    /** A free-text setting. The host truncates a stored value to [maxLength] rather than rejecting it. */
    fun string(id: String, default: String = "", maxLength: Int = SettingBounds.MAX_STRING_DEFAULT) {
        require(maxLength in 1..SettingBounds.MAX_STRING_DEFAULT) {
            "setting \"$id\": maxLength $maxLength is outside 1..${SettingBounds.MAX_STRING_DEFAULT}"
        }
        plainText("setting \"$id\" default", default, maxLength)
        put(id, SettingTypes.STRING) {
            put("default", default)
            put("maxLength", maxLength)
        }
    }

    private fun put(rawId: String, type: String, extra: JSONObject.() -> Unit) {
        val id = settingId(rawId)
        require(ids.add(id)) {
            "two settings share the id \"$id\"; the id is also the storage key, so a duplicate would " +
                "overwrite its twin's value"
        }
        require(settings.length() < SettingBounds.MAX_SETTINGS) {
            "a settings section holds at most ${SettingBounds.MAX_SETTINGS} settings"
        }
        settings.put(
            JSONObject().apply {
                put("id", id)
                put("type", type)
                extra()
            },
        )
    }

    /**
     * Validates a setting id to the host's storage-key grammar: lowercase, starting with a letter or
     * digit, no empty dotted segment that a dotted-key store could read as traversal. Refused, not
     * rewritten — an id is the one string that must read back the same from the registry.
     */
    private fun settingId(raw: String): String {
        val id = plainText("setting id", raw.trim(), SettingBounds.MAX_ID_LENGTH)
        require(SettingBounds.ID_PATTERN.matches(id)) {
            "setting id \"$raw\" must match ${SettingBounds.ID_PATTERN.pattern} — lowercase, and a key the " +
                "host's namespace will accept"
        }
        require(id.split('.').none { it.isEmpty() }) {
            "setting id \"$raw\" has an empty dotted segment; the host reads \"..\" or a trailing \".\" as traversal"
        }
        return id
    }

    internal fun toJson(): JSONObject {
        require(settings.length() > 0) { "a settings section needs at least one setting" }
        return JSONObject().put("settings", settings)
    }
}
