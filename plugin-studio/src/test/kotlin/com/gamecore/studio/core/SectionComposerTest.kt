package com.gamecore.studio.core

import com.gamecore.studio.model.BoolSetting
import com.gamecore.studio.model.BrokerActionId
import com.gamecore.studio.model.EnumSetting
import com.gamecore.studio.model.FloatSetting
import com.gamecore.studio.model.IntSetting
import com.gamecore.studio.model.NodeDraft
import com.gamecore.studio.model.NodeKind
import com.gamecore.studio.model.NodeValue
import com.gamecore.studio.model.Operator
import com.gamecore.studio.model.OverlayDraft
import com.gamecore.studio.model.OverlaySize
import com.gamecore.studio.model.RuleCondition
import com.gamecore.studio.model.RuleDraft
import com.gamecore.studio.model.RuleVariable
import com.gamecore.studio.model.ScreenDraft
import com.gamecore.studio.model.SettingDraft
import com.gamecore.studio.model.StatId
import com.gamecore.studio.model.StringSetting
import com.gamecore.studio.model.Trigger
import com.gamecore.studio.model.WidgetDraft
import com.gamecore.studio.model.WidgetSize
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Producer-side tests for every host section envelope and primitive node encoding. */
class SectionComposerTest {

    @Test
    fun `screens writer uses the host envelope canonical ids and stable field order`() {
        val screen = ScreenDraft(
            id = " Overview ",
            title = "Overview",
            icon = " Insights ",
            root = NodeDraft.text("Ready"),
        )

        val raw = SectionComposer.screensJson(listOf(screen))
        val root = JSONObject(raw)
        val encoded = root.getJSONArray("screens").getJSONObject(0)

        assertEquals("gamecore.plugin.screens", root.getString("format"))
        assertEquals(1, root.getInt("version"))
        assertEquals("overview", encoded.getString("id"))
        assertEquals("insights", encoded.getString("icon"))
        assertEquals("text", encoded.getJSONObject("content").getString("type"))
        assertEquals(
            "{\"format\":\"gamecore.plugin.screens\",\"version\":1," +
                "\"screens\":[{\"id\":\"overview\",\"title\":\"Overview\"," +
                "\"icon\":\"insights\",\"content\":{\"type\":\"text\"," +
                "\"props\":{\"text\":\"Ready\"}}}]}",
            raw,
        )
    }

    @Test
    fun `overlay writer matches pill version one envelope`() {
        val overlay = OverlayDraft(
            id = " FPS-Badge ",
            title = "Frame rate",
            xFraction = 0.25,
            yFraction = 0.75,
            size = OverlaySize.LARGE,
            opacity = 64,
            content = NodeDraft.chart(values = "58,60,59"),
        )

        val root = JSONObject(SectionComposer.overlayJson(overlay))

        assertEquals(
            setOf("format", "version", "id", "title", "anchor", "size", "opacity", "content"),
            root.keySet(),
        )
        assertEquals("gamecore.plugin.pill", root.getString("format"))
        assertEquals("fps-badge", root.getString("id"))
        assertEquals(0.25, root.getJSONObject("anchor").getDouble("x"), 0.0)
        assertEquals(0.75, root.getJSONObject("anchor").getDouble("y"), 0.0)
        assertEquals("large", root.getString("size"))
        assertEquals(64, root.getInt("opacity"))
        assertEquals("58,60,59", root.getJSONObject("content").getJSONObject("props").getString("values"))
    }

    @Test
    fun `settings writer emits every schema-one type but omits studio labels and descriptions`() {
        val settings: List<SettingDraft> = listOf(
            BoolSetting("haptics", true, label = "Visible only in Studio", description = "Never package me"),
            IntSetting("refresh", 120, min = 60, max = 240, label = "Refresh label"),
            FloatSetting("gamma", 1.0, min = 0.5, max = 2.0, description = "Gamma description"),
            EnumSetting("units", listOf("metric", "imperial"), "metric", label = "Units label"),
            StringSetting("nickname", "Player", 32, description = "Nickname description"),
        )

        val raw = SectionComposer.settingsJson(settings)
        val root = JSONObject(raw)
        val array = root.getJSONArray("settings")

        assertEquals(setOf("settings"), root.keySet())
        assertFalse(root.has("schema"))
        assertEquals(listOf("bool", "int", "float", "enum", "string"), (0 until array.length()).map {
            array.getJSONObject(it).getString("type")
        })
        assertEquals(120L, array.getJSONObject(1).getLong("default"))
        assertEquals(60L, array.getJSONObject(1).getLong("min"))
        assertEquals(2.0, array.getJSONObject(2).getDouble("max"), 0.0)
        assertEquals("imperial", array.getJSONObject(3).getJSONArray("options").getString(1))
        assertEquals(32, array.getJSONObject(4).getInt("maxLength"))
        for (index in 0 until array.length()) {
            assertFalse(array.getJSONObject(index).has("label"))
            assertFalse(array.getJSONObject(index).has("description"))
        }
        listOf("Visible only in Studio", "Never package me", "Refresh label", "Gamma description").forEach {
            assertFalse("studio-only text leaked into settings JSON: $it", raw.contains(it))
        }
    }

    @Test
    fun `widget writer permits scroll and canonical live chart series`() {
        val widget = WidgetDraft(
            id = " Live-History ",
            title = "Live history",
            size = WidgetSize.FULL,
            content = NodeDraft.scroll(
                listOf(
                    NodeDraft(
                        NodeKind.CHART,
                        props = mapOf(
                            "stat" to NodeValue.Text(" FPS "),
                            "stat2" to NodeValue.Text(" TEMP "),
                        ),
                    ),
                ),
            ),
        )

        val root = JSONObject(SectionComposer.widgetJson(widget))
        val content = root.getJSONObject("content")
        val chartProps = content.getJSONArray("children").getJSONObject(0).getJSONObject("props")

        assertEquals("gamecore.plugin.widget", root.getString("format"))
        assertEquals(1, root.getInt("version"))
        assertEquals("live-history", root.getString("id"))
        assertEquals("full", root.getString("size"))
        assertEquals("scroll", content.getString("type"))
        assertEquals("fps", chartProps.getString("stat"))
        assertEquals("temp", chartProps.getString("stat2"))
    }

    @Test
    fun `rules writer matches the host flat-and sequential-action grammar`() {
        val rules = listOf(
            RuleDraft(
                id = " cool-down ",
                trigger = Trigger.THERMAL_HIGH,
                conditions = listOf(
                    RuleCondition(RuleVariable.TEMP, Operator.GTE, 45.0),
                    RuleCondition(RuleVariable.BATTERY, Operator.LTE, 20.0),
                ),
                actionIds = listOf(
                    BrokerActionId.SET_WINDOW_ANIMATION_SCALE,
                    BrokerActionId.SET_LOW_POWER,
                ),
            ),
        )

        val root = JSONObject(SectionComposer.rulesJson(rules))
        val rule = root.getJSONArray("rules").getJSONObject(0)
        val conditions = rule.getJSONArray("conditions")

        assertEquals(setOf("rules"), root.keySet())
        assertEquals(setOf("id", "trigger", "actions", "conditions"), rule.keySet())
        assertEquals("cool-down", rule.getString("id"))
        assertEquals("THERMAL_HIGH", rule.getString("trigger"))
        assertEquals("set_window_animation_scale", rule.getJSONArray("actions").getString(0))
        assertEquals("set_low_power", rule.getJSONArray("actions").getString(1))
        assertEquals(setOf("variable", "op", "value"), conditions.getJSONObject(0).keySet())
        assertEquals("temp", conditions.getJSONObject(0).getString("variable"))
        assertEquals("GTE", conditions.getJSONObject(0).getString("op"))
        assertFalse(rule.has("branch"))
        assertFalse(rule.has("parallel"))
        assertFalse(rule.has("schedule"))
        assertFalse(rule.has("retry"))
    }

    @Test
    fun `node writer emits only primitives and preserves text that resembles host sigils`() {
        val node = NodeDraft.text(
            "@launch",
            props = mapOf(
                "hashLabel" to NodeValue.Text("#112233"),
                "enabled" to NodeValue.Bool(true),
                "amount" to NodeValue.Number(2.5),
                "opaque" to NodeValue.Color(0xFF112233L),
                "alpha" to NodeValue.Color(0x80112233L),
                "codeLooking" to NodeValue.Text("javascript:alert(1)"),
            ),
        )

        val json = SectionComposer.nodeJson(node)
        val props = json.getJSONObject("props")

        // The host removes one leading @@ literal sigil, yielding the authored strings exactly.
        assertEquals("@@@launch", props.getString("text"))
        assertEquals("@@#112233", props.getString("hashLabel"))
        assertEquals("@launch", decodeHostString(props.getString("text")))
        assertEquals("#112233", decodeHostString(props.getString("hashLabel")))
        assertEquals(true, props.getBoolean("enabled"))
        assertEquals(2.5, props.getDouble("amount"), 0.0)
        assertEquals("#112233", props.getString("opaque"))
        assertEquals("#80112233", props.getString("alpha"))
        assertEquals("javascript:alert(1)", props.getString("codeLooking"))
        assertFalse(props.has("action"))
        props.keySet().forEach { key ->
            assertTrue("$key was not a JSON primitive", props.get(key) is String || props.get(key) is Number || props.get(key) is Boolean)
            val value = props.get(key)
            if (value is String && value.startsWith("@")) {
                assertTrue("$key encoded an action reference: $value", value.startsWith("@@"))
            }
        }
    }

    @Test
    fun `node and section output is deterministic across map insertion order`() {
        val firstProps = linkedMapOf<String, NodeValue>(
            "z" to NodeValue.Text("last"),
            "a" to NodeValue.Number(1),
            "m" to NodeValue.Bool(false),
        )
        val secondProps = linkedMapOf<String, NodeValue>(
            "m" to NodeValue.Bool(false),
            "z" to NodeValue.Text("last"),
            "a" to NodeValue.Number(1),
        )
        val first = ScreenDraft("stable", "Stable", root = NodeDraft(NodeKind.CARD, firstProps, listOf(NodeDraft.text("A"))))
        val second = ScreenDraft("stable", "Stable", root = NodeDraft(NodeKind.CARD, secondProps, listOf(NodeDraft.text("A"))))

        val one = SectionComposer.screensJson(listOf(first))
        val two = SectionComposer.screensJson(listOf(second))

        assertEquals(one, two)
        assertEquals(one, SectionComposer.screensJson(listOf(first)))
        assertTrue(one.indexOf("\"a\"") < one.indexOf("\"m\""))
        assertTrue(one.indexOf("\"m\"") < one.indexOf("\"z\""))
    }

    private fun JSONObject.keySet(): Set<String> = keys().asSequence().toSet()

    /** The two string branches relevant to this producer test, copied from the host node codec. */
    private fun decodeHostString(raw: String): String = when {
        raw.startsWith("@@") -> raw.substring(2)
        raw.startsWith("@") -> error("the composer emitted an action reference: $raw")
        else -> raw
    }
}
