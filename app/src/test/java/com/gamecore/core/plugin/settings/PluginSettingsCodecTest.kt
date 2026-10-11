package com.gamecore.core.plugin.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 gate (host side): the strict additive-allowlist codec that turns an untrusted `settings.json`
 * into a trusted [PluginSettingsSchema], or a typed rejection. Mirrors `PluginThemeCodecTest`.
 */
class PluginSettingsCodecTest {

    private fun decode(json: String) = PluginSettingsCodec.decode(json)

    private fun reasonOf(json: String): PluginSettingsCodec.Reason =
        (decode(json) as PluginSettingsCodec.Result.Rejected).reason

    private fun parse(json: String): PluginSettingsSchema =
        (decode(json) as PluginSettingsCodec.Result.Parsed).schema

    private val valid = """
        {
          "settings": [
            {"id": "haptics", "type": "bool", "default": true},
            {"id": "hint", "type": "int", "default": 120, "min": 60, "max": 240},
            {"id": "gamma", "type": "float", "default": 1.0, "min": 0.5, "max": 2.0},
            {"id": "units", "type": "enum", "default": "metric", "options": ["metric", "imperial"]},
            {"id": "label", "type": "string", "default": "hi", "maxLength": 32}
          ]
        }
    """.trimIndent()

    @Test
    fun `a well-formed file of every type parses`() {
        val schema = parse(valid)
        assertEquals(PluginSettingsCodec.SCHEMA_V1, schema.schema)
        assertEquals(5, schema.size)
        assertTrue(schema.byId("haptics") is SettingDef.BoolDef)
        assertEquals(SettingValue.IntValue(120), schema.byId("hint")!!.default)
        assertEquals(SettingValue.FloatValue(1.0), schema.byId("gamma")!!.default)
        assertEquals(SettingValue.EnumValue("metric"), schema.byId("units")!!.default)
        assertEquals(SettingValue.StringValue("hi"), schema.byId("label")!!.default)
        assertNull(schema.byId("nope"))
    }

    @Test
    fun `empty and malformed are rejected`() {
        assertEquals(PluginSettingsCodec.Reason.EMPTY, reasonOf(""))
        assertEquals(PluginSettingsCodec.Reason.MALFORMED, reasonOf("{ not json"))
    }

    @Test
    fun `an out-of-range schema is rejected`() {
        assertEquals(
            PluginSettingsCodec.Reason.BAD_SCHEMA,
            reasonOf("""{"schema": 2, "settings": [{"id":"a","type":"bool","default":true}]}"""),
        )
    }

    @Test
    fun `an unknown root key is rejected`() {
        assertEquals(
            PluginSettingsCodec.Reason.UNKNOWN_KEY,
            reasonOf("""{"settings": [], "extra": 1}"""),
        )
    }

    @Test
    fun `missing, empty, and non-array settings are rejected`() {
        assertEquals(PluginSettingsCodec.Reason.MISSING_FIELD, reasonOf("""{"schema": 1}"""))
        assertEquals(PluginSettingsCodec.Reason.MISSING_FIELD, reasonOf("""{"settings": []}"""))
        assertEquals(PluginSettingsCodec.Reason.MALFORMED, reasonOf("""{"settings": "nope"}"""))
    }

    @Test
    fun `more settings than the cap is rejected`() {
        val entries = (0..PluginSettingsCodec.MAX_SETTINGS).joinToString(",") {
            """{"id":"s$it","type":"bool","default":true}"""
        }
        assertEquals(PluginSettingsCodec.Reason.TOO_MANY_ENTRIES, reasonOf("""{"settings":[$entries]}"""))
    }

    @Test
    fun `a setting that is not an object is rejected`() {
        assertEquals(PluginSettingsCodec.Reason.MALFORMED, reasonOf("""{"settings": [1]}"""))
    }

    @Test
    fun `missing type and unknown type are rejected`() {
        assertEquals(PluginSettingsCodec.Reason.MISSING_FIELD, reasonOf("""{"settings":[{"id":"a","default":true}]}"""))
        assertEquals(
            PluginSettingsCodec.Reason.UNKNOWN_TYPE,
            reasonOf("""{"settings":[{"id":"a","type":"colour","default":"#fff"}]}"""),
        )
    }

    @Test
    fun `an unknown key within a setting is rejected`() {
        assertEquals(
            PluginSettingsCodec.Reason.UNKNOWN_KEY,
            reasonOf("""{"settings":[{"id":"a","type":"bool","default":true,"min":1}]}"""),
        )
    }

    @Test
    fun `a missing or bad id is rejected`() {
        assertEquals(PluginSettingsCodec.Reason.MISSING_FIELD, reasonOf("""{"settings":[{"type":"bool","default":true}]}"""))
        assertEquals(
            PluginSettingsCodec.Reason.BAD_ID,
            reasonOf("""{"settings":[{"id":"Haptics","type":"bool","default":true}]}"""),
        )
        assertEquals(
            PluginSettingsCodec.Reason.BAD_ID,
            reasonOf("""{"settings":[{"id":"a..b","type":"bool","default":true}]}"""),
        )
    }

    @Test
    fun `a duplicate id is rejected`() {
        assertEquals(
            PluginSettingsCodec.Reason.DUPLICATE_ID,
            reasonOf(
                """{"settings":[{"id":"a","type":"bool","default":true},{"id":"a","type":"bool","default":false}]}""",
            ),
        )
    }

    @Test
    fun `a missing default and a wrong-typed default are rejected`() {
        assertEquals(PluginSettingsCodec.Reason.MISSING_FIELD, reasonOf("""{"settings":[{"id":"a","type":"bool"}]}"""))
        assertEquals(
            PluginSettingsCodec.Reason.BAD_DEFAULT,
            reasonOf("""{"settings":[{"id":"a","type":"bool","default":1}]}"""),
        )
        assertEquals(
            PluginSettingsCodec.Reason.BAD_DEFAULT,
            reasonOf("""{"settings":[{"id":"a","type":"int","default":"5"}]}"""),
        )
    }

    @Test
    fun `int bounds are enforced`() {
        assertEquals(
            PluginSettingsCodec.Reason.BAD_DEFAULT,
            reasonOf("""{"settings":[{"id":"a","type":"int","default":5,"min":10,"max":20}]}"""),
        )
        assertEquals(
            PluginSettingsCodec.Reason.BAD_BOUNDS,
            reasonOf("""{"settings":[{"id":"a","type":"int","default":5,"min":20,"max":10}]}"""),
        )
        assertEquals(
            PluginSettingsCodec.Reason.BAD_BOUNDS,
            reasonOf("""{"settings":[{"id":"a","type":"int","default":5,"min":"3"}]}"""),
        )
    }

    @Test
    fun `enum options are validated and the default must be among them`() {
        assertEquals(
            PluginSettingsCodec.Reason.BAD_DEFAULT,
            reasonOf("""{"settings":[{"id":"a","type":"enum","default":"c","options":["a","b"]}]}"""),
        )
        assertEquals(
            PluginSettingsCodec.Reason.BAD_OPTIONS,
            reasonOf("""{"settings":[{"id":"a","type":"enum","default":"a"}]}"""),
        )
        assertEquals(
            PluginSettingsCodec.Reason.BAD_OPTIONS,
            reasonOf("""{"settings":[{"id":"a","type":"enum","default":"a","options":["a",1]}]}"""),
        )
    }

    @Test
    fun `string maxLength and default length are enforced`() {
        assertEquals(
            PluginSettingsCodec.Reason.BAD_BOUNDS,
            reasonOf("""{"settings":[{"id":"a","type":"string","default":"","maxLength":9999}]}"""),
        )
        assertEquals(
            PluginSettingsCodec.Reason.BAD_DEFAULT,
            reasonOf("""{"settings":[{"id":"a","type":"string","default":"abcdef","maxLength":3}]}"""),
        )
    }

    @Test
    fun `the trusted model coerces, clamps, and falls back as declared`() {
        val schema = parse(valid)
        val hint = schema.byId("hint")!!
        // clamp above max
        assertEquals(SettingValue.IntValue(240), hint.coerce(SettingValue.IntValue(10_000)))
        // decode a parseable stored string
        assertEquals(SettingValue.IntValue(90), hint.decode("90"))
        // a stale, no-longer-parseable string decodes to null (surface falls back to default)
        assertNull(hint.decode("not-a-number"))
        // type mismatch coerces to null (a write rejects)
        assertNull(hint.coerce(SettingValue.StringValue("x")))

        val units = schema.byId("units")!!
        assertNull(units.coerce(SettingValue.EnumValue("furlongs")))
        assertEquals(SettingValue.EnumValue("imperial"), units.coerce(SettingValue.EnumValue("imperial")))

        val label = schema.byId("label")!!
        val long = "x".repeat(40)
        assertEquals(SettingValue.StringValue("x".repeat(32)), label.coerce(SettingValue.StringValue(long)))
        assertEquals("hi", label.encode(SettingValue.StringValue("hi"))!!)
    }
}
