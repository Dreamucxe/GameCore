package com.gamecore.studio.core

import com.gamecore.studio.model.BrokerActionId
import com.gamecore.studio.model.CreatorCategory
import com.gamecore.studio.model.CreatorSections
import com.gamecore.studio.model.FloatSetting
import com.gamecore.studio.model.GlassDraft
import com.gamecore.studio.model.NodeDraft
import com.gamecore.studio.model.Operator
import com.gamecore.studio.model.OverlayDraft
import com.gamecore.studio.model.PluginDraft
import com.gamecore.studio.model.RuleCondition
import com.gamecore.studio.model.RuleDraft
import com.gamecore.studio.model.RuleVariable
import com.gamecore.studio.model.ScreenDraft
import com.gamecore.studio.model.StatId
import com.gamecore.studio.model.Trigger
import com.gamecore.studio.model.WidgetDraft
import com.gamecore.studio.model.WidgetSize
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** End-to-end producer/storage/import coverage for the six sections Studio can preserve. */
class MultiSectionRoundTripTest {

    private val rich = PluginDraft(
        id = "com.example.creator",
        name = "Creator package",
        version = 2,
        versionName = "2.0.0",
        developer = "Studio",
        description = "Every runtime-backed creator section.",
        permissions = setOf("change_ui"),
        permissionScopes = mapOf("read_approved_data" to setOf("games")),
        creatorCategory = CreatorCategory.COMBINED,
        theme = com.gamecore.studio.model.ThemeDraft(
            name = "Creator package",
            glass = GlassDraft(intensitySetting = "glass_level"),
        ),
        sections = CreatorSections(
            screens = listOf(
                ScreenDraft(
                    id = "overview",
                    title = "Overview",
                    icon = "insights",
                    root = NodeDraft.column(
                        listOf(NodeDraft.text("Live"), NodeDraft.stat(StatId.FPS)),
                    ),
                ),
            ),
            overlay = OverlayDraft(
                id = "battery-badge",
                title = "Battery",
                content = NodeDraft.row(listOf(NodeDraft.stat(StatId.BATTERY))),
            ),
            settings = listOf(
                FloatSetting(
                    id = "glass_level",
                    default = 0.7,
                    min = 0.0,
                    max = 1.0,
                    label = "Glass level",
                    description = "Studio project metadata",
                ),
            ),
            widgets = listOf(
                WidgetDraft(
                    id = "cpu-card",
                    title = "CPU",
                    size = WidgetSize.LARGE,
                    content = NodeDraft.card(listOf(NodeDraft.stat(StatId.CPU))),
                ),
            ),
            rules = listOf(
                RuleDraft(
                    id = "cool-down",
                    trigger = Trigger.THERMAL_HIGH,
                    conditions = listOf(RuleCondition(RuleVariable.TEMP, Operator.GTE, 45.0)),
                    actionIds = listOf(BrokerActionId.SET_LOW_POWER),
                ),
            ),
        ),
    )

    @Test
    fun `rich draft is valid and emits every supported entry in stable order`() {
        assertTrue("unexpected draft problems: ${rich.problems()}", rich.problems().isEmpty())
        assertEquals(
            listOf(
                "manifest.json",
                "theme.json",
                "pill.json",
                "screens/screens.json",
                "rules/rules.json",
                "settings.json",
                "widgets/cpu-card.json",
            ),
            PluginComposer.entryNames(rich),
        )

        val one = PluginComposer.packageBytes(rich)
        val two = PluginComposer.packageBytes(rich)
        assertArrayEquals(one, two)
    }

    @Test
    fun `manifest derives content permissions and scopes but never claims a grant`() {
        val json = org.json.JSONObject(PluginComposer.manifestJson(rich))
        val declared = json.getJSONArray("declares")
        assertEquals(
            setOf("theme", "pill", "screens", "rules", "settings", "widgets"),
            (0 until declared.length()).map { declared.getString(it) }.toSet(),
        )

        val permissions = json.getJSONArray("permissions")
        val byId = (0 until permissions.length())
            .map { permissions.getJSONObject(it) }
            .associateBy { it.getString("id") }
        assertTrue("change_ui" in byId)
        assertTrue("create_overlay" in byId)
        assertTrue("add_dashboard_widgets" in byId)
        assertTrue("store_data" in byId)
        assertTrue("run_automations" in byId)
        assertTrue("system_actions" in byId)
        val scopes = byId.getValue("read_approved_data").getJSONArray("scopes")
        assertEquals(
            setOf("games", "device_stats", "thermals"),
            (0 until scopes.length()).map { scopes.getString(it) }.toSet(),
        )
        assertFalse(json.has("granted"))
        assertFalse(json.has("enabled"))
    }

    @Test
    fun `studio storage round trips every section including studio-only metadata`() {
        val restored = DraftCodec.decode(DraftCodec.encode(rich))
        assertEquals(rich, restored)
    }

    @Test
    fun `composed rich package imports without dropping runtime-backed data`() {
        val outcome = PluginImporter.import(PluginComposer.packageBytes(rich))
        assertTrue("import failed: $outcome", outcome is ImportOutcome.Success)
        val imported = (outcome as ImportOutcome.Success).draft

        assertEquals(rich.id, imported.id)
        assertEquals(rich.sections.screens, imported.sections.screens)
        assertEquals(rich.sections.overlay, imported.sections.overlay)
        assertEquals(rich.sections.widgets, imported.sections.widgets)
        assertEquals(rich.sections.rules, imported.sections.rules)
        assertEquals("glass_level", imported.theme.glass?.intensitySetting)
        assertEquals(setOf("games", "device_stats", "thermals"), imported.effectivePermissionScopes()["read_approved_data"])

        // Labels/descriptions are Studio-project metadata and are intentionally not package fields.
        val importedSetting = imported.sections.settings.single() as FloatSetting
        assertEquals("", importedSetting.label)
        assertEquals("", importedSetting.description)
    }

    @Test
    fun `unsupported asset is refused instead of being lost on re-export`() {
        val entries = PluginImporter.readEntries(PluginComposer.packageBytes(rich))
            .map { it.key to it.value }
            .toMutableList()
            .also { it += "assets/logo.png" to byteArrayOf(1, 2, 3) }
        val outcome = PluginImporter.import(zip(entries))
        assertTrue(outcome is ImportOutcome.Failure)
        val message = (outcome as ImportOutcome.Failure).message
        assertTrue(message, message.contains("assets/logo.png"))
        assertTrue(message, message.contains("data loss"))
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { archive ->
            entries.forEach { (name, bytes) ->
                archive.putNextEntry(ZipEntry(name))
                archive.write(bytes)
                archive.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
