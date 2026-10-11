package com.gamecore.plugin.sdk

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Part K gate (schema 2): the DSL emits v2 theme keys only when a v2 feature is used, and bumps api. */
class PluginThemeV2Test {

    private fun themeOf(def: PluginDefinition): JSONObject =
        JSONObject(String(def.sectionFiles.getValue("theme.json")))

    private fun manifestOf(def: PluginDefinition): JSONObject =
        JSONObject(String(def.manifestJson))

    private fun build(block: ThemeBuilder.() -> Unit = {}): PluginDefinition =
        plugin("com.example.t", "T") { theme(block) }

    @Test
    fun `a v1 theme emits no schema key and stays api 1`() {
        val def = build()
        val t = themeOf(def)
        assertFalse(t.has("schema"))
        assertFalse(t.has("amoled"))
        assertFalse(t.has("roles"))
        assertFalse(t.has("typography"))
        assertEquals(1, manifestOf(def).getInt("api"))
    }

    @Test
    fun `a theme that uses a v2 feature is schema 2 and bumps api to 2`() {
        val def = plugin("com.example.v2", "V2") {
            theme {
                amoled = true
                roles { secondary = "#AA00FF"; error = "#B00020"; onError = "#FFFFFF" }
                typography { scale = 1.2; weight = ThemeWeights.BOLD }
            }
        }
        val t = themeOf(def)
        assertEquals(ThemeSchema.V2, t.getInt("schema"))
        assertTrue(t.getBoolean("amoled"))
        assertEquals("#aa00ff", t.getJSONObject("roles").getString("secondary").lowercase())
        assertEquals("#b00020", t.getJSONObject("roles").getString("error").lowercase())
        assertEquals(1.2, t.getJSONObject("typography").getDouble("scale"), 0.0001)
        assertEquals("bold", t.getJSONObject("typography").getString("weight"))
        assertEquals(2, manifestOf(def).getInt("api"))
    }

    @Test
    fun `an amoled-only theme is schema 2 but carries no roles or typography`() {
        val def = build { amoled = true }
        val t = themeOf(def)
        assertEquals(ThemeSchema.V2, t.getInt("schema"))
        assertTrue(t.getBoolean("amoled"))
        assertFalse(t.has("roles"))
        assertFalse(t.has("typography"))
        assertEquals(2, manifestOf(def).getInt("api"))
    }

    @Test
    fun `roles emits only the roles that were set`() {
        val roles = themeOf(build { roles { outline = "#334455" } }).getJSONObject("roles")
        assertEquals(setOf("outline"), roles.keys().asSequence().toSet())
    }

    @Test
    fun `a weight with no scale emits typography with only the weight`() {
        val typo = themeOf(build { typography { weight = ThemeWeights.MEDIUM } }).getJSONObject("typography")
        assertEquals(setOf("weight"), typo.keys().asSequence().toSet())
        assertEquals("medium", typo.getString("weight"))
    }

    @Test
    fun `a bad role colour is refused at build time`() {
        assertFailsWith<IllegalArgumentException> { build { roles { secondary = "blue" } } }
        assertFailsWith<IllegalArgumentException> { build { roles { error = "#FFF" } } }
    }

    @Test
    fun `an unknown weight is refused at build time`() {
        assertFailsWith<IllegalArgumentException> { build { typography { weight = "heavy" } } }
    }

    @Test
    fun `a type scale outside the host clamp is refused at build time`() {
        assertFailsWith<IllegalArgumentException> { build { typography { scale = 2.0 } } }
        assertFailsWith<IllegalArgumentException> { build { typography { scale = 0.5 } } }
        build { typography { scale = ThemeBounds.MIN_TYPE_SCALE } }
        build { typography { scale = ThemeBounds.MAX_TYPE_SCALE } }
    }

    @Test
    fun `the eleven v1 keys are still present on a v2 theme`() {
        val t = themeOf(build { amoled = true })
        val v1Keys = setOf(
            "name", "dark", "primary", "onPrimary", "background", "surface",
            "onSurface", "accent", "cornerRadiusDp", "font", "uiScale",
        )
        assertTrue(t.keys().asSequence().toSet().containsAll(v1Keys))
    }
}
