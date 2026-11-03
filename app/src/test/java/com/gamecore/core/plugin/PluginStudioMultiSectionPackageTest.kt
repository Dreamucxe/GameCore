package com.gamecore.core.plugin

import com.gamecore.core.plugin.content.RulesCodec
import com.gamecore.core.plugin.dashboard.PluginDashboardCodec
import com.gamecore.core.plugin.overlay.PluginOverlayCodec
import com.gamecore.core.plugin.settings.PluginSettingsCodec
import com.gamecore.core.plugin.theme.PluginThemeCodec
import com.gamecore.core.plugin.ui.PluginScreensCodec
import com.gamecore.studio.core.PluginComposer
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The host-authority cross-check for every section the visual creator exports. */
class PluginStudioMultiSectionPackageTest {

    private val draft = PluginDraft(
        id = "com.example.fullcreator",
        name = "Full creator",
        creatorCategory = CreatorCategory.COMBINED,
        theme = com.gamecore.studio.model.ThemeDraft(
            name = "Full creator",
            glass = GlassDraft(intensitySetting = "glass_level"),
        ),
        sections = CreatorSections(
            screens = listOf(
                ScreenDraft(
                    id = "overview",
                    title = "Overview",
                    icon = "insights",
                    root = NodeDraft.column(listOf(NodeDraft.stat(StatId.FPS), NodeDraft.text("Ready"))),
                ),
            ),
            overlay = OverlayDraft(
                id = "battery-badge",
                title = "Battery",
                content = NodeDraft.row(listOf(NodeDraft.stat(StatId.BATTERY))),
            ),
            settings = listOf(FloatSetting("glass_level", 0.7, 0.0, 1.0)),
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
    fun `host loader and every real section codec accept Studio output`() {
        assertTrue("invalid Studio draft: ${draft.problems()}", draft.problems().isEmpty())
        val loaded = when (val result = PluginPackageLoader.load(PluginComposer.packageBytes(draft))) {
            is PluginPackageLoader.Result.Loaded -> result.plugin
            is PluginPackageLoader.Result.Rejected -> error("host loader rejected Studio package: $result")
        }

        assertEquals(
            setOf(
                PluginSection.THEME,
                PluginSection.PILL,
                PluginSection.SCREENS,
                PluginSection.RULES,
                PluginSection.SETTINGS,
                PluginSection.WIDGETS,
            ),
            loaded.manifest.declares,
        )
        assertEquals(PluginDraft.DEFAULT_MIN_HOST_VERSION, loaded.manifest.minHostVersion)
        assertEquals(Compatibility.Supported, loaded.manifest.compatibility)

        val requested = loaded.manifest.permissions.associateBy { it.permission }
        assertTrue(PluginPermission.CHANGE_UI in requested)
        assertTrue(PluginPermission.CREATE_OVERLAY in requested)
        assertTrue(PluginPermission.ADD_DASHBOARD_WIDGETS in requested)
        assertTrue(PluginPermission.STORE_DATA in requested)
        assertTrue(PluginPermission.RUN_AUTOMATIONS in requested)
        assertTrue(PluginPermission.SYSTEM_ACTIONS in requested)
        assertEquals(
            setOf("device_stats", "thermals"),
            requested.getValue(PluginPermission.READ_APPROVED_DATA).scopes,
        )

        val theme = PluginThemeCodec.decode(text(loaded, "theme.json"))
        assertTrue("theme rejected: $theme", theme is PluginThemeCodec.Result.Parsed)
        assertEquals(
            "glass_level",
            (theme as PluginThemeCodec.Result.Parsed).theme.glass?.intensitySetting,
        )

        val overlay = PluginOverlayCodec.decode(text(loaded, "pill.json"))
        assertTrue("overlay rejected: $overlay", overlay is PluginOverlayCodec.Result.Parsed)
        assertEquals(setOf("thermals"), (overlay as PluginOverlayCodec.Result.Parsed).module.requiredScopes)

        val screens = PluginScreensCodec.decode(text(loaded, "screens/screens.json"))
        assertTrue("screens rejected: $screens", screens is PluginScreensCodec.Result.Parsed)
        assertEquals("overview", (screens as PluginScreensCodec.Result.Parsed).screens.single().id)

        val settings = PluginSettingsCodec.decode(text(loaded, "settings.json"))
        assertTrue("settings rejected: $settings", settings is PluginSettingsCodec.Result.Parsed)
        assertNotNull((settings as PluginSettingsCodec.Result.Parsed).schema.byId("glass_level"))

        val widget = PluginDashboardCodec.decode(text(loaded, "widgets/cpu-card.json"))
        assertTrue("widget rejected: $widget", widget is PluginDashboardCodec.Result.Parsed)
        assertEquals(setOf("device_stats"), (widget as PluginDashboardCodec.Result.Parsed).widget.requiredScopes)

        val rules = RulesCodec.decode(text(loaded, "rules/rules.json"))
        assertTrue("rules rejected: $rules", rules is RulesCodec.Result.Parsed)
        assertEquals("set_low_power", (rules as RulesCodec.Result.Parsed).rules.single().actionIds.single())
    }

    private fun text(plugin: LoadedPlugin, path: String): String =
        plugin.entries.getValue(path).toString(Charsets.UTF_8)
}
