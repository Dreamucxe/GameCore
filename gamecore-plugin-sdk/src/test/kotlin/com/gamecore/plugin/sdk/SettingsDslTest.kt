package com.gamecore.plugin.sdk

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 2 gate (SDK side): the `settings { }` DSL emits a validated `settings.json`, declares the
 * section, and bumps the manifest api to the 3.x level — while a plugin that declares no settings stays
 * byte-identical to one built before the feature existed.
 */
class SettingsDslTest {

    private fun settingsOf(def: PluginDefinition): JSONObject =
        JSONObject(String(def.sectionFiles.getValue("settings.json")))

    private fun manifestOf(def: PluginDefinition): JSONObject =
        JSONObject(String(def.manifestJson))

    private fun build(block: SettingsBuilder.() -> Unit): PluginDefinition =
        plugin("com.example.s", "S") { settings(block) }

    @Test
    fun `a plugin without settings carries no settings file and stays api 1`() {
        val def = plugin("com.example.none", "None") { }
        assertFalse(def.sectionFiles.containsKey("settings.json"))
        assertEquals(Sdk.API, manifestOf(def).getInt("api"))
    }

    @Test
    fun `settings emits the section, declares it, and bumps api to the 3x level`() {
        val def = build { bool("haptics", default = true) }
        assertTrue(def.sectionFiles.containsKey("settings.json"))
        val manifest = manifestOf(def)
        assertEquals(Sdk.API_V3, manifest.getInt("api"))
        val declares = manifest.getJSONArray("declares")
        assertEquals("settings", declares.getString(0))
        // v1 settings omit the schema key, exactly as a v1 theme does.
        assertFalse(settingsOf(def).has("schema"))
    }

    @Test
    fun `each type serialises with its own shape`() {
        val def = build {
            bool("haptics", default = true)
            int("hint", default = 120, min = 60, max = 240)
            float("gamma", default = 1.0, min = 0.5, max = 2.0)
            enum("units", options = listOf("metric", "imperial"), default = "metric")
            string("label", default = "hi", maxLength = 32)
        }
        val arr = settingsOf(def).getJSONArray("settings")
        assertEquals(5, arr.length())

        val b = arr.getJSONObject(0)
        assertEquals("haptics", b.getString("id"))
        assertEquals("bool", b.getString("type"))
        assertTrue(b.getBoolean("default"))

        val i = arr.getJSONObject(1)
        assertEquals("int", i.getString("type"))
        assertEquals(120, i.getInt("default"))
        assertEquals(60, i.getInt("min"))
        assertEquals(240, i.getInt("max"))

        val f = arr.getJSONObject(2)
        assertEquals("float", f.getString("type"))
        assertEquals(1.0, f.getDouble("default"), 0.0001)

        val e = arr.getJSONObject(3)
        assertEquals("enum", e.getString("type"))
        assertEquals("metric", e.getString("default"))
        assertEquals(2, e.getJSONArray("options").length())

        val s = arr.getJSONObject(4)
        assertEquals("string", s.getString("type"))
        assertEquals("hi", s.getString("default"))
        assertEquals(32, s.getInt("maxLength"))
    }

    @Test
    fun `an int default outside its bounds is refused`() {
        assertFailsWith<IllegalArgumentException> { build { int("x", default = 5, min = 10, max = 20) } }
    }

    @Test
    fun `min greater than max is refused`() {
        assertFailsWith<IllegalArgumentException> { build { int("x", default = 10, min = 20, max = 10) } }
    }

    @Test
    fun `a non-finite float default is refused`() {
        assertFailsWith<IllegalArgumentException> { build { float("x", default = Double.NaN) } }
    }

    @Test
    fun `an enum default not among the options is refused`() {
        assertFailsWith<IllegalArgumentException> {
            build { enum("x", options = listOf("a", "b"), default = "c") }
        }
    }

    @Test
    fun `an empty enum option set is refused`() {
        assertFailsWith<IllegalArgumentException> { build { enum("x", options = emptyList(), default = "a") } }
    }

    @Test
    fun `a duplicate id is refused`() {
        assertFailsWith<IllegalArgumentException> {
            build { bool("dup", default = true); bool("dup", default = false) }
        }
    }

    @Test
    fun `an uppercase id is refused rather than lowercased`() {
        assertFailsWith<IllegalArgumentException> { build { bool("Haptics", default = true) } }
    }

    @Test
    fun `an id with an empty dotted segment is refused`() {
        assertFailsWith<IllegalArgumentException> { build { bool("a..b", default = true) } }
    }

    @Test
    fun `a string default longer than its maxLength is refused`() {
        assertFailsWith<IllegalArgumentException> { build { string("x", default = "abcdef", maxLength = 3) } }
    }

    @Test
    fun `a maxLength outside the cap is refused`() {
        assertFailsWith<IllegalArgumentException> { build { string("x", default = "", maxLength = 9999) } }
    }

    @Test
    fun `an empty settings block is refused`() {
        assertFailsWith<IllegalArgumentException> { build { } }
    }

    @Test
    fun `more settings than the cap is refused`() {
        assertFailsWith<IllegalArgumentException> {
            build { repeat(SettingBounds.MAX_SETTINGS + 1) { i -> bool("s$i", default = true) } }
        }
    }
}
