package com.gamecore.core.plugin.settings

/**
 * A plugin's typed settings schema, already validated by [PluginSettingsCodec].
 *
 * A [PluginSettingsSchema] is the trust-boundary output for the `settings` section: a list of typed
 * [SettingDef]s the rest of the host may rely on, each carrying a well-formed id, an in-range default,
 * and whatever bounds its type needs. The codec is the only thing that constructs one from untrusted
 * JSON; everything downstream (the typed get/set surface in `PluginSettings`) treats it as safe.
 *
 * The id of a setting doubles as the key the plugin's value is stored under, so the codec constrains it
 * to a grammar [com.gamecore.core.plugin.PluginNamespace.qualify] will always accept — a setting that
 * parses can always be read and written.
 *
 * Pure Kotlin, no Android, so every rule here is a JVM unit test.
 */
data class PluginSettingsSchema(
    val schema: Int,
    val definitions: List<SettingDef>,
) {
    private val byId: Map<String, SettingDef> = definitions.associateBy { it.id }

    /** The definition with this id, or null — a get/set for an unknown id has nothing to validate. */
    fun byId(id: String): SettingDef? = byId[id]

    val size: Int get() = definitions.size
}

/**
 * One typed value a plugin can store. The store itself holds only strings; this is the typed shape the
 * surface hands a caller and takes back, and [SettingDef] is what converts between the two.
 */
sealed interface SettingValue {
    data class BoolValue(val value: Boolean) : SettingValue
    data class IntValue(val value: Long) : SettingValue
    data class FloatValue(val value: Double) : SettingValue
    data class EnumValue(val value: String) : SettingValue
    data class StringValue(val value: String) : SettingValue
}

/**
 * A single typed setting definition.
 *
 * Each type knows three operations, and the split matters because reads and writes fail differently:
 *
 *  - [decode] parses a raw stored string back to a typed value, or null when the string predates a
 *    type change and no longer parses.
 *  - [coerce] bounds a typed value to the definition (clamp numerics, check enum membership, truncate a
 *    string), or null when the value is the wrong type entirely. A read falls back to [default] on
 *    null; a write rejects on null.
 *  - [encode] renders a typed value to the one string the store holds, or null on a type mismatch.
 *
 * [default] is the value a read returns when the key is absent or unreadable; the codec guarantees it is
 * already in range.
 */
sealed interface SettingDef {
    val id: String
    val default: SettingValue

    fun decode(raw: String): SettingValue?
    fun coerce(value: SettingValue): SettingValue?
    fun encode(value: SettingValue): String?

    data class BoolDef(override val id: String, val defaultValue: Boolean) : SettingDef {
        override val default: SettingValue get() = SettingValue.BoolValue(defaultValue)
        override fun decode(raw: String): SettingValue? = when (raw) {
            "true" -> SettingValue.BoolValue(true)
            "false" -> SettingValue.BoolValue(false)
            else -> null
        }
        override fun coerce(value: SettingValue): SettingValue? =
            (value as? SettingValue.BoolValue)
        override fun encode(value: SettingValue): String? =
            (value as? SettingValue.BoolValue)?.value?.toString()
    }

    data class IntDef(
        override val id: String,
        val defaultValue: Long,
        val min: Long?,
        val max: Long?,
    ) : SettingDef {
        override val default: SettingValue get() = SettingValue.IntValue(defaultValue)
        override fun decode(raw: String): SettingValue? =
            raw.toLongOrNull()?.let { SettingValue.IntValue(it) }
        override fun coerce(value: SettingValue): SettingValue? {
            val v = (value as? SettingValue.IntValue)?.value ?: return null
            var clamped = v
            if (min != null && clamped < min) clamped = min
            if (max != null && clamped > max) clamped = max
            return SettingValue.IntValue(clamped)
        }
        override fun encode(value: SettingValue): String? =
            (value as? SettingValue.IntValue)?.value?.toString()
    }

    data class FloatDef(
        override val id: String,
        val defaultValue: Double,
        val min: Double?,
        val max: Double?,
    ) : SettingDef {
        override val default: SettingValue get() = SettingValue.FloatValue(defaultValue)
        override fun decode(raw: String): SettingValue? =
            raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { SettingValue.FloatValue(it) }
        override fun coerce(value: SettingValue): SettingValue? {
            val v = (value as? SettingValue.FloatValue)?.value ?: return null
            if (!v.isFinite()) return null
            var clamped = v
            if (min != null && clamped < min) clamped = min
            if (max != null && clamped > max) clamped = max
            return SettingValue.FloatValue(clamped)
        }
        override fun encode(value: SettingValue): String? =
            (value as? SettingValue.FloatValue)?.value?.toString()
    }

    data class EnumDef(
        override val id: String,
        val options: List<String>,
        val defaultValue: String,
    ) : SettingDef {
        override val default: SettingValue get() = SettingValue.EnumValue(defaultValue)
        override fun decode(raw: String): SettingValue? = SettingValue.EnumValue(raw)
        override fun coerce(value: SettingValue): SettingValue? {
            val v = (value as? SettingValue.EnumValue)?.value ?: return null
            return if (v in options) SettingValue.EnumValue(v) else null
        }
        override fun encode(value: SettingValue): String? =
            (value as? SettingValue.EnumValue)?.value?.takeIf { it in options }
    }

    data class StringDef(
        override val id: String,
        val defaultValue: String,
        val maxLength: Int,
    ) : SettingDef {
        override val default: SettingValue get() = SettingValue.StringValue(defaultValue)
        override fun decode(raw: String): SettingValue? = SettingValue.StringValue(raw)
        override fun coerce(value: SettingValue): SettingValue? {
            val v = (value as? SettingValue.StringValue)?.value ?: return null
            return SettingValue.StringValue(if (v.length > maxLength) v.take(maxLength) else v)
        }
        override fun encode(value: SettingValue): String? =
            (value as? SettingValue.StringValue)?.value?.let { if (it.length > maxLength) it.take(maxLength) else it }
    }
}
