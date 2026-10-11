package com.gamecore.core.plugin.settings

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Parses and validates `settings.json`, the typed-settings section of a `.gcplugin`.
 *
 * This is the section's trust boundary, built exactly like [com.gamecore.core.plugin.theme.PluginThemeCodec]:
 * a strict additive allowlist. A key the schema does not know, a type that is not one of the five, a
 * default of the wrong type or out of its bounds, a duplicate id, an id that is not a valid storage key,
 * or more settings than the cap — any one rejects the whole file, as a typed [Result.Rejected] carrying a
 * reason the install screen and SDK can show verbatim. Nothing is silently dropped or coerced here; the
 * runtime surface does the coercing later, against the trusted [PluginSettingsSchema] this produces.
 *
 * Pure `org.json` and the settings models — no Android — so every rejection is a JVM unit test.
 */
object PluginSettingsCodec {

    /** The settings schema versions. v1 omits the `schema` key entirely, like a v1 theme. */
    const val SCHEMA_V1 = 1
    const val SCHEMA_CURRENT = 1

    const val MAX_SETTINGS = 64
    const val MAX_ID_LENGTH = 64
    const val MAX_ENUM_OPTIONS = 32

    /** Caps a string default and each enum option, matched to the SDK's `SettingBounds.MAX_STRING_DEFAULT`. */
    const val MAX_STRING_LENGTH = 256

    private val KNOWN_ROOT_KEYS = setOf("schema", "settings")

    private const val TYPE_BOOL = "bool"
    private const val TYPE_INT = "int"
    private const val TYPE_FLOAT = "float"
    private const val TYPE_ENUM = "enum"
    private const val TYPE_STRING = "string"
    private val KNOWN_TYPES = setOf(TYPE_BOOL, TYPE_INT, TYPE_FLOAT, TYPE_ENUM, TYPE_STRING)

    /** The id grammar, identical to the SDK's `SettingBounds.ID_PATTERN`; a parsing id is always qualifiable. */
    private val ID_GRAMMAR = Regex("^[a-z0-9][a-z0-9._-]{0,${MAX_ID_LENGTH - 1}}$")

    sealed interface Result {
        data class Parsed(val schema: PluginSettingsSchema) : Result
        data class Rejected(val reason: Reason, val detail: String = "") : Result
    }

    /** Why a settings file was refused. Each maps to a line the UI and SDK can show. */
    enum class Reason {
        EMPTY, MALFORMED, BAD_SCHEMA, UNKNOWN_KEY, MISSING_FIELD, UNKNOWN_TYPE,
        BAD_ID, DUPLICATE_ID, BAD_DEFAULT, BAD_BOUNDS, BAD_OPTIONS, TOO_MANY_ENTRIES,
    }

    fun decode(raw: String?): Result {
        if (raw.isNullOrBlank()) return Result.Rejected(Reason.EMPTY)
        val root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return Result.Rejected(Reason.MALFORMED, e.message.orEmpty())
        }

        val schema = root.optInt("schema", SCHEMA_V1)
        if (schema < SCHEMA_V1 || schema > SCHEMA_CURRENT) {
            return Result.Rejected(Reason.BAD_SCHEMA, "schema $schema is not in $SCHEMA_V1..$SCHEMA_CURRENT")
        }

        root.keys().asSequence().firstOrNull { it !in KNOWN_ROOT_KEYS }?.let {
            return Result.Rejected(Reason.UNKNOWN_KEY, "unknown key \"$it\"")
        }

        if (!root.has("settings")) return Result.Rejected(Reason.MISSING_FIELD, "missing \"settings\"")
        val array = root.optJSONArray("settings")
            ?: return Result.Rejected(Reason.MALFORMED, "settings must be an array")
        if (array.length() == 0) return Result.Rejected(Reason.MISSING_FIELD, "settings is empty")
        if (array.length() > MAX_SETTINGS) {
            return Result.Rejected(Reason.TOO_MANY_ENTRIES, "too many settings")
        }

        val defs = ArrayList<SettingDef>(array.length())
        val seen = HashSet<String>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
                ?: return Result.Rejected(Reason.MALFORMED, "setting $i is not an object")
            when (val parsed = parseSetting(obj, i, seen)) {
                is SettingParse.Ok -> defs += parsed.def
                is SettingParse.Err -> return parsed.rejected
            }
        }
        return Result.Parsed(PluginSettingsSchema(schema, defs))
    }

    private sealed interface SettingParse {
        data class Ok(val def: SettingDef) : SettingParse
        data class Err(val rejected: Result.Rejected) : SettingParse
    }

    private fun parseSetting(obj: JSONObject, index: Int, seen: MutableSet<String>): SettingParse {
        if (!obj.has("type")) return err(Reason.MISSING_FIELD, "setting $index: missing \"type\"")
        val type = obj.optString("type").trim()
        if (type !in KNOWN_TYPES) return err(Reason.UNKNOWN_TYPE, "unknown type \"$type\"")

        val allowed = allowedKeys(type)
        obj.keys().asSequence().firstOrNull { it !in allowed }?.let {
            return err(Reason.UNKNOWN_KEY, "setting $index: unknown key \"$it\"")
        }

        if (!obj.has("id")) return err(Reason.MISSING_FIELD, "setting $index: missing \"id\"")
        val id = obj.optString("id").trim()
        if (!isValidId(id)) return err(Reason.BAD_ID, "setting $index: bad id \"$id\"")
        if (!seen.add(id)) return err(Reason.DUPLICATE_ID, "duplicate setting id \"$id\"")

        if (!obj.has("default")) return err(Reason.MISSING_FIELD, "setting \"$id\": missing \"default\"")
        val default = obj.get("default")

        return when (type) {
            TYPE_BOOL -> parseBool(id, default)
            TYPE_INT -> parseInt(id, obj, default)
            TYPE_FLOAT -> parseFloat(id, obj, default)
            TYPE_ENUM -> parseEnum(id, obj, default)
            TYPE_STRING -> parseString(id, obj, default)
            else -> err(Reason.UNKNOWN_TYPE, "unknown type \"$type\"")
        }
    }

    private fun allowedKeys(type: String): Set<String> = when (type) {
        TYPE_INT, TYPE_FLOAT -> setOf("id", "type", "default", "min", "max")
        TYPE_ENUM -> setOf("id", "type", "default", "options")
        TYPE_STRING -> setOf("id", "type", "default", "maxLength")
        else -> setOf("id", "type", "default")
    }

    private fun parseBool(id: String, default: Any): SettingParse {
        if (default !is Boolean) return err(Reason.BAD_DEFAULT, "setting \"$id\": default must be true or false")
        return ok(SettingDef.BoolDef(id, default))
    }

    private fun parseInt(id: String, obj: JSONObject, default: Any): SettingParse {
        val d = asLong(default) ?: return err(Reason.BAD_DEFAULT, "setting \"$id\": default must be an integer")
        val min = if (obj.has("min")) {
            asLong(obj.get("min")) ?: return err(Reason.BAD_BOUNDS, "setting \"$id\": min must be an integer")
        } else null
        val max = if (obj.has("max")) {
            asLong(obj.get("max")) ?: return err(Reason.BAD_BOUNDS, "setting \"$id\": max must be an integer")
        } else null
        if (min != null && max != null && min > max) return err(Reason.BAD_BOUNDS, "setting \"$id\": min > max")
        if (min != null && d < min) return err(Reason.BAD_DEFAULT, "setting \"$id\": default below min")
        if (max != null && d > max) return err(Reason.BAD_DEFAULT, "setting \"$id\": default above max")
        return ok(SettingDef.IntDef(id, d, min, max))
    }

    private fun parseFloat(id: String, obj: JSONObject, default: Any): SettingParse {
        val d = asDouble(default) ?: return err(Reason.BAD_DEFAULT, "setting \"$id\": default must be a number")
        if (!d.isFinite()) return err(Reason.BAD_DEFAULT, "setting \"$id\": default is not finite")
        val min = if (obj.has("min")) {
            asDouble(obj.get("min"))?.takeIf { it.isFinite() }
                ?: return err(Reason.BAD_BOUNDS, "setting \"$id\": min must be a finite number")
        } else null
        val max = if (obj.has("max")) {
            asDouble(obj.get("max"))?.takeIf { it.isFinite() }
                ?: return err(Reason.BAD_BOUNDS, "setting \"$id\": max must be a finite number")
        } else null
        if (min != null && max != null && min > max) return err(Reason.BAD_BOUNDS, "setting \"$id\": min > max")
        if (min != null && d < min) return err(Reason.BAD_DEFAULT, "setting \"$id\": default below min")
        if (max != null && d > max) return err(Reason.BAD_DEFAULT, "setting \"$id\": default above max")
        return ok(SettingDef.FloatDef(id, d, min, max))
    }

    private fun parseEnum(id: String, obj: JSONObject, default: Any): SettingParse {
        if (default !is String) return err(Reason.BAD_DEFAULT, "setting \"$id\": default must be a string")
        val optionsArray = obj.optJSONArray("options")
            ?: return err(Reason.BAD_OPTIONS, "setting \"$id\": options must be an array")
        if (optionsArray.length() == 0) return err(Reason.BAD_OPTIONS, "setting \"$id\": options is empty")
        if (optionsArray.length() > MAX_ENUM_OPTIONS) return err(Reason.BAD_OPTIONS, "setting \"$id\": too many options")
        val options = ArrayList<String>(optionsArray.length())
        for (i in 0 until optionsArray.length()) {
            val opt = optionsArray.get(i)
            if (opt !is String) return err(Reason.BAD_OPTIONS, "setting \"$id\": an option is not a string")
            if (opt.isBlank()) return err(Reason.BAD_OPTIONS, "setting \"$id\": an option is blank")
            if (opt.length > MAX_STRING_LENGTH) return err(Reason.BAD_OPTIONS, "setting \"$id\": an option is too long")
            if (opt in options) return err(Reason.BAD_OPTIONS, "setting \"$id\": duplicate option \"$opt\"")
            options += opt
        }
        if (default !in options) return err(Reason.BAD_DEFAULT, "setting \"$id\": default \"$default\" is not an option")
        return ok(SettingDef.EnumDef(id, options, default))
    }

    private fun parseString(id: String, obj: JSONObject, default: Any): SettingParse {
        if (default !is String) return err(Reason.BAD_DEFAULT, "setting \"$id\": default must be a string")
        val maxLength = if (obj.has("maxLength")) {
            asLong(obj.get("maxLength"))?.toInt()
                ?: return err(Reason.BAD_BOUNDS, "setting \"$id\": maxLength must be an integer")
        } else {
            MAX_STRING_LENGTH
        }
        if (maxLength < 1 || maxLength > MAX_STRING_LENGTH) {
            return err(Reason.BAD_BOUNDS, "setting \"$id\": maxLength $maxLength is outside 1..$MAX_STRING_LENGTH")
        }
        if (default.length > maxLength) return err(Reason.BAD_DEFAULT, "setting \"$id\": default longer than maxLength")
        return ok(SettingDef.StringDef(id, default, maxLength))
    }

    private fun isValidId(id: String): Boolean =
        id.length <= MAX_ID_LENGTH && ID_GRAMMAR.matches(id) && id.split('.').none { it.isEmpty() }

    private fun asLong(v: Any?): Long? = when (v) {
        is Int -> v.toLong()
        is Long -> v
        else -> null
    }

    /**
     * A JSON number as a Double, or null for anything that is not a number.
     *
     * Matches [Number] rather than the three Kotlin types `org.json` happens to emit on one classpath:
     * a decimal literal comes back as `java.lang.Double` under Android's `org.json` but as `BigDecimal`
     * under the JVM `org.json:json` the unit tests run against, and a hard `is Double` silently rejected
     * the latter. This is the same coercion `optDouble` applies in [com.gamecore.core.plugin.theme.PluginThemeCodec]
     * and [com.gamecore.core.plugin.overlay.PluginOverlayCodec]; a `String`, `Boolean` or container is not a
     * [Number], so every wrong-typed default still rejects.
     */
    private fun asDouble(v: Any?): Double? = (v as? Number)?.toDouble()

    private fun ok(def: SettingDef): SettingParse = SettingParse.Ok(def)
    private fun err(reason: Reason, detail: String): SettingParse = SettingParse.Err(Result.Rejected(reason, detail))
}
