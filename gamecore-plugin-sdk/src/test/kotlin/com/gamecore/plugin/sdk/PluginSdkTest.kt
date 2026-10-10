package com.gamecore.plugin.sdk

import com.gamecore.plugin.sdk.templates.Templates
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/** Part K gate: the DSL emits host-shaped JSON and the packager writes a safe zip. */
class PluginSdkTest {

    @Test
    fun `the DSL emits a manifest with the host's keys`() {
        val def = Templates.minimal()
        val m = JSONObject(String(def.manifestJson))
        assertEquals("gamecore.plugin", m.getString("format"))
        assertEquals("com.example.minimal", m.getString("id"))
        assertEquals(1, m.getInt("api"))
        assertEquals(Sdk.HOST_VERSION_CODE, m.getInt("minHostVersion"))
        assertTrue(m.getJSONArray("declares").toList().contains("theme"))
        assertTrue(def.sectionFiles.containsKey("theme.json"))
    }

    @Test
    fun `scoped permissions serialise with their scopes`() {
        val m = JSONObject(String(Templates.cyberpunk().manifestJson))
        val read = (0 until m.getJSONArray("permissions").length())
            .map { m.getJSONArray("permissions").getJSONObject(it) }
            .first { it.getString("id") == Permissions.READ_APPROVED_DATA }
        val scopes = read.getJSONArray("scopes").toList().map { it.toString() }
        assertTrue(scopes.contains(DataScopes.DEVICE_STATS))
        assertTrue(scopes.contains(DataScopes.THERMALS))
    }

    @Test
    fun `rules carry real action ids`() {
        val def = Templates.cyberpunk()
        val rules = JSONObject(String(def.sectionFiles.getValue("rules/rules.json"))).getJSONArray("rules")
        val boost = rules.getJSONObject(0)
        assertEquals(Triggers.GAME_LAUNCHED, boost.getString("trigger"))
        assertEquals(Actions.SET_PEAK_REFRESH_RATE, boost.getJSONArray("actions").getString(0))
    }

    @Test
    fun `the packager writes a readable gcplugin zip with manifest at root`() {
        val out = File.createTempFile("minimal", ".gcplugin")
        PluginPackager.writeTo(out, Templates.minimal())
        val names = mutableListOf<String>()
        ZipInputStream(out.inputStream()).use { zin ->
            var e = zin.nextEntry
            while (e != null) { names += e.name; e = zin.nextEntry }
        }
        assertTrue(names.contains("manifest.json"))
        assertTrue(names.contains("theme.json"))
        out.delete()
    }

    @Test
    fun `the packager refuses an unsafe section path`() {
        val def = plugin("com.example.bad", "Bad") {
            section("../escape.json", declares = "theme", bytes = "{}".toByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            PluginPackager.writeTo(File.createTempFile("bad", ".gcplugin"), def)
        }
    }

    // ------------------------------------------------------------------------------------- theme

    private fun themeOf(definition: PluginDefinition): JSONObject =
        JSONObject(String(definition.sectionFiles.getValue("theme.json")))

    private fun theme(block: ThemeBuilder.() -> Unit): PluginDefinition =
        plugin("com.example.theme", "Theme") { theme(block) }

    @Test
    fun `a theme emits all eleven host keys`() {
        val json = themeOf(Templates.pixel())
        val expected = setOf(
            "name", "dark", "primary", "onPrimary", "background", "surface", "onSurface",
            "accent", "cornerRadiusDp", "font", "uiScale",
        )
        assertEquals(expected, json.keys().asSequence().toSet())
        assertEquals("Pixel", json.getString("name"))
        assertEquals(false, json.getBoolean("dark"))
        assertEquals(28, json.getInt("cornerRadiusDp"))
        assertEquals(ThemeFonts.ROUNDED, json.getString("font"))
        assertEquals(1.0, json.getDouble("uiScale"), 0.0001)
    }

    /**
     * A colour and a font are the theme's two **hard** host failures: the codec rejects the whole
     * file, so the plugin installs, its switch turns on, and the app looks untouched. There is
     * nothing on screen to tell that apart from a theme nobody selected.
     */
    @Test
    fun `an unparseable colour is refused rather than rejected on the device`() {
        assertFailsWith<IllegalArgumentException> { theme { primary = "blue" } }
        assertFailsWith<IllegalArgumentException> { theme { surface = "#FFF" } } // no 3-digit form
        assertFailsWith<IllegalArgumentException> { theme { onSurface = "#GGGGGG" } }
        assertFailsWith<IllegalArgumentException> { theme { accent = "4F8CFF" } } // no leading #
        theme { primary = "#4F8CFF"; accent = "#CC00E5FF" } // 6- and 8-digit are both legal
    }

    @Test
    fun `an unknown font is refused and a known one is normalised`() {
        assertFailsWith<IllegalArgumentException> { theme { font = "comic" } }
        assertFailsWith<IllegalArgumentException> { theme { font = "" } }
        ThemeFonts.ALL.forEach { known ->
            assertEquals(known, themeOf(theme { font = known }).getString("font"))
        }
        // The host trims and lowercases before it resolves, so this would load — as a face the
        // author did not literally write. Emit the id the host will actually register.
        assertEquals(ThemeFonts.MONO, themeOf(theme { font = " MONO " }).getString("font"))
    }

    /**
     * Corner radius and UI scale are the theme's two **clamped** values, which is the quieter
     * failure: the theme loads, and it loads looking like something the author never picked.
     */
    @Test
    fun `a corner radius or ui scale outside the host's clamp is refused`() {
        assertFailsWith<IllegalArgumentException> { theme { cornerRadiusDp = -1 } }
        assertFailsWith<IllegalArgumentException> { theme { cornerRadiusDp = 64 } }
        assertFailsWith<IllegalArgumentException> { theme { uiScale = 0.5 } }
        assertFailsWith<IllegalArgumentException> { theme { uiScale = 2.0 } }
        // The edges are legal.
        theme { cornerRadiusDp = ThemeBounds.MIN_CORNER_DP }
        theme { cornerRadiusDp = ThemeBounds.MAX_CORNER_DP }
        theme { uiScale = ThemeBounds.MIN_UI_SCALE }
        theme { uiScale = ThemeBounds.MAX_UI_SCALE }
    }

    @Test
    fun `a theme name over the host's cap is refused, not truncated`() {
        theme { name = "n".repeat(ThemeBounds.MAX_NAME_LENGTH) }
        assertFailsWith<IllegalArgumentException> {
            theme { name = "n".repeat(ThemeBounds.MAX_NAME_LENGTH + 1) }
        }
        assertFailsWith<IllegalArgumentException> { theme { name = "  " } }
        assertFailsWith<IllegalArgumentException> { theme { name = "Neon‮" } }
    }

    @Test
    fun `every bundled template's theme survives its own validation`() {
        val hex = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
        listOf(Templates.minimal(), Templates.pixel(), Templates.cyberpunk()).forEach { definition ->
            val json = themeOf(definition)
            assertTrue(json.getString("font") in ThemeFonts.ALL)
            assertTrue(
                json.getInt("cornerRadiusDp") in ThemeBounds.MIN_CORNER_DP..ThemeBounds.MAX_CORNER_DP,
            )
            listOf("primary", "onPrimary", "background", "surface", "onSurface", "accent").forEach { key ->
                val value = json.getString(key)
                assertTrue(hex.matches(value), "$key is \"$value\", which the host reads as no colour at all")
            }
        }
    }

    // ------------------------------------------------------------------------------------- rules

    private fun rulesOf(definition: PluginDefinition): JSONObject =
        JSONObject(String(definition.sectionFiles.getValue("rules/rules.json")))

    private fun rules(block: RulesBuilder.() -> Unit): PluginDefinition =
        plugin("com.example.rules", "Rules") { rules(block) }

    private fun oneRule(
        trigger: String = Triggers.GAME_LAUNCHED,
        actions: List<String> = listOf(Actions.SET_PEAK_REFRESH_RATE),
        conditions: List<RulesBuilder.Condition> = emptyList(),
    ): PluginDefinition = rules { rule("r", trigger, actions, conditions) }

    /**
     * The defect this block exists for, and the only silent one of the four names a rule carries.
     *
     * A trigger, an operator and an action id are all resolved by the host, and a bad one rejects
     * the entire rules file. A condition **variable** is not resolved anywhere: the codec copies
     * it through and the engine reads a name the feed does not publish as a condition that is not
     * met. So a thermal rule written against `temp_c` installs, validates, appears in the
     * plugin's rule list, and protects nothing — for as long as the plugin is installed, with no
     * log line and no state that differs from "not hot yet".
     */
    @Test
    fun `a condition variable the stat feed does not publish is refused`() {
        assertFailsWith<IllegalArgumentException> {
            oneRule(conditions = listOf(RulesBuilder.Condition("temp_c", RuleOperators.GTE, 45.0)))
        }
        assertFailsWith<IllegalArgumentException> {
            oneRule(conditions = listOf(RulesBuilder.Condition("framerate", RuleOperators.LT, 30.0)))
        }
        RuleVariables.ALL.forEach { known ->
            oneRule(conditions = listOf(RulesBuilder.Condition(known, RuleOperators.GTE, 1.0)))
        }
    }

    @Test
    fun `the cyberpunk template's thermal rule names a variable the feed publishes`() {
        val cool = rulesOf(Templates.cyberpunk()).getJSONArray("rules").getJSONObject(1)
        assertEquals("cool_down", cool.getString("id"))
        assertEquals(Triggers.THERMAL_HIGH, cool.getString("trigger"))
        val condition = cool.getJSONArray("conditions").getJSONObject(0)
        assertEquals(RuleVariables.TEMP, condition.getString("variable"))
        assertTrue(condition.getString("variable") in RuleVariables.ALL)
        assertEquals(RuleOperators.GTE, condition.getString("op"))
        assertEquals(45.0, condition.getDouble("value"), 0.0001)
    }

    @Test
    fun `an unknown trigger, operator or action is refused`() {
        assertFailsWith<IllegalArgumentException> { oneRule(trigger = "GAME_STARTED") }
        assertFailsWith<IllegalArgumentException> { oneRule(actions = listOf("set_cpu_governor")) }
        assertFailsWith<IllegalArgumentException> { oneRule(actions = listOf("su -c reboot")) }
        assertFailsWith<IllegalArgumentException> {
            oneRule(conditions = listOf(RulesBuilder.Condition(RuleVariables.TEMP, "=>", 45.0)))
        }
        Triggers.ALL.forEach { known -> oneRule(trigger = known) }
        Actions.ALL.forEach { known -> oneRule(actions = listOf(known)) }
    }

    /** The host resolves all four names case-insensitively; emit the spelling it will register. */
    @Test
    fun `trigger, action, operator and variable are emitted in the host's own spelling`() {
        val definition = rules {
            rule(
                "mixed", " game_launched ", listOf(" SET_LOW_POWER "),
                listOf(RulesBuilder.Condition(" TEMP ", " gte ", 45.0)),
            )
        }
        val rule = rulesOf(definition).getJSONArray("rules").getJSONObject(0)
        assertEquals(Triggers.GAME_LAUNCHED, rule.getString("trigger"))
        assertEquals(Actions.SET_LOW_POWER, rule.getJSONArray("actions").getString(0))
        val condition = rule.getJSONArray("conditions").getJSONObject(0)
        assertEquals(RuleVariables.TEMP, condition.getString("variable"))
        assertEquals(RuleOperators.GTE, condition.getString("op"))
    }

    @Test
    fun `a rule with no actions is refused, and so is one over the host's caps`() {
        assertFailsWith<IllegalArgumentException> { oneRule(actions = emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            oneRule(actions = List(RuleBounds.MAX_ACTIONS_PER_RULE + 1) { Actions.SET_LOW_POWER })
        }
        assertFailsWith<IllegalArgumentException> {
            oneRule(
                conditions = List(RuleBounds.MAX_CONDITIONS_PER_RULE + 1) {
                    RulesBuilder.Condition(RuleVariables.TEMP, RuleOperators.GTE, 1.0)
                },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            rules {
                repeat(RuleBounds.MAX_RULES + 1) { i ->
                    rule("r$i", Triggers.GAME_CLOSED, listOf(Actions.SET_LOW_POWER))
                }
            }
        }
        rules {
            repeat(RuleBounds.MAX_RULES) { i ->
                rule("r$i", Triggers.GAME_CLOSED, listOf(Actions.SET_LOW_POWER))
            }
        }
    }

    @Test
    fun `an empty rules section and a duplicate rule id are refused`() {
        assertFailsWith<IllegalArgumentException> { rules { } }
        assertFailsWith<IllegalArgumentException> {
            rules {
                rule("same", Triggers.GAME_LAUNCHED, listOf(Actions.SET_LOW_POWER))
                rule("same", Triggers.GAME_CLOSED, listOf(Actions.SET_LOW_POWER))
            }
        }
    }

    /**
     * A rule names an action and never carries one. This walks the emitted JSON for any key that
     * could hold a command, a settings key or a value of its own — the shape a rules file must
     * not be allowed to grow into, however the DSL changes around it.
     */
    @Test
    fun `no rule carries a command, a key or a value of its own`() {
        val allowedRuleKeys = setOf("id", "trigger", "actions", "conditions")
        val allowedConditionKeys = setOf("variable", "op", "value")
        val emitted = rulesOf(Templates.cyberpunk()).getJSONArray("rules")
        for (i in 0 until emitted.length()) {
            val rule = emitted.getJSONObject(i)
            rule.keys().forEach { key ->
                assertTrue(key in allowedRuleKeys, "a rule emits \"$key\"")
            }
            val actions = rule.getJSONArray("actions")
            for (a in 0 until actions.length()) {
                val action = actions.get(a)
                if (action !is String) fail("an action must be a bare id, found $action")
                assertTrue(action in Actions.ALL, "action \"$action\" is not a broker action")
            }
            rule.optJSONArray("conditions")?.let { conditions ->
                for (c in 0 until conditions.length()) {
                    conditions.getJSONObject(c).keys().forEach { key ->
                        assertTrue(key in allowedConditionKeys, "a condition emits \"$key\"")
                    }
                }
            }
        }
    }
}
