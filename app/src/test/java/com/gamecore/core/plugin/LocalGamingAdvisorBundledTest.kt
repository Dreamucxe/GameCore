package com.gamecore.core.plugin

import com.gamecore.core.plugin.content.AutomationEngine
import com.gamecore.core.plugin.content.RulesCodec
import com.gamecore.core.plugin.dashboard.PluginDashboardCodec
import com.gamecore.core.plugin.settings.PluginSettingsCodec
import com.gamecore.core.plugin.ui.ComponentType
import com.gamecore.core.plugin.ui.PluginScreensCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled "Local Gaming Advisor" plugin is a real package, not a hand-waved one.
 *
 * Its flat asset files are the ones the SDK's `LocalGamingAdvisorPackageTest` packages and then
 * flattens into this directory under the exact names the host's bundled loader reads (a section
 * whose entry prefix is a directory, like `screens/`, ships as `screens.json`, and the one
 * representative `widgets/<id>.json` ships as `widgets.json`). These tests push those same files
 * through the production host codecs — the path a sideloaded `.gcplugin` also takes — so a typo in
 * a bundled JSON fails the build rather than shipping a dead default.
 *
 * The load-bearing assertion is the subset check: a `widgets.json` whose chart names a live feed
 * whose data scope the `manifest.json` never requests parses perfectly and then renders empty,
 * because the user is never offered the grant. `requiredScopes` is derived from the content tree
 * (it folds the chart's live series), so this is the real gate the registry applies, and nothing
 * else in the pipeline catches a mismatch — so it is checked here.
 */
class LocalGamingAdvisorBundledTest {

    private val assets = File("src/main/assets/plugins/advisor")

    private fun read(path: String): String {
        val f = File(assets, path)
        assertTrue("missing bundled asset: ${f.path} (LocalGamingAdvisorPackageTest generates it)", f.exists())
        return f.readText()
    }

    private fun manifest(): PluginManifest {
        val m = PluginManifestCodec.decode(read("manifest.json"))
        assertTrue("manifest.json did not parse: $m", m is PluginManifestCodec.Result.Parsed)
        return (m as PluginManifestCodec.Result.Parsed).manifest
    }

    @Test
    fun `manifest parses and declares the four sections plus the icon with their seven permissions`() {
        val manifest = manifest()
        assertEquals("com.dreamucxe.gamecore.advisor", manifest.id)
        assertEquals(3, manifest.api)
        assertEquals(37, manifest.minHostVersion) // the icon section needs the 3.15.0 host
        assertTrue(manifest.declares.contains(PluginSection.SCREENS))
        assertTrue(manifest.declares.contains(PluginSection.WIDGETS))
        assertTrue(manifest.declares.contains(PluginSection.SETTINGS))
        assertTrue(manifest.declares.contains(PluginSection.RULES))

        val perms = manifest.permissions.map { it.permission }.toSet()
        assertEquals(
            setOf(
                PluginPermission.CHANGE_UI,
                PluginPermission.ADD_DASHBOARD_WIDGETS,
                PluginPermission.READ_APPROVED_DATA,
                PluginPermission.STORE_DATA,
                PluginPermission.RUN_AUTOMATIONS,
                PluginPermission.SYSTEM_ACTIONS,
                PluginPermission.CHANGE_ICON,
            ),
            perms,
        )
        val read = manifest.permissions.first { it.permission == PluginPermission.READ_APPROVED_DATA }
        assertEquals(setOf("device_stats", "thermals"), read.scopes)
    }

    @Test
    fun `screens parse into the advice and reference screens`() {
        val decoded = PluginScreensCodec.decode(read("screens.json"))
        assertTrue("screens.json did not parse: $decoded", decoded is PluginScreensCodec.Result.Parsed)
        decoded as PluginScreensCodec.Result.Parsed
        assertEquals(listOf("advice", "reference"), decoded.screens.map { it.id })
    }

    @Test
    fun `settings parse into the six typed thresholds`() {
        val decoded = PluginSettingsCodec.decode(read("settings.json"))
        assertTrue("settings.json did not parse: $decoded", decoded is PluginSettingsCodec.Result.Parsed)
        decoded as PluginSettingsCodec.Result.Parsed
        assertEquals(
            listOf("warn_temp_c", "critical_temp_c", "target_fps", "trend_smoothing", "advice_detail", "show_trend_widget"),
            decoded.schema.definitions.map { it.id },
        )
    }

    @Test
    fun `rules decode and validate as unattended broker automations`() {
        val decoded = RulesCodec.decode(read("rules.json"))
        assertTrue("rules.json did not parse: $decoded", decoded is RulesCodec.Result.Parsed)
        decoded as RulesCodec.Result.Parsed
        assertEquals(listOf("thermal_cooldown", "battery_saver"), decoded.rules.map { it.id })

        val engine = AutomationEngine()
        decoded.rules.forEach { rule ->
            assertTrue(
                "rule ${rule.id} does not validate: ${engine.validate(rule)}",
                engine.validate(rule) is AutomationEngine.Validation.Valid,
            )
        }
    }

    @Test
    fun `the dashboard chart exposes a live series within the requested scopes`() {
        val decoded = PluginDashboardCodec.decode(read("widgets.json"))
        assertTrue("widgets.json did not parse: $decoded", decoded is PluginDashboardCodec.Result.Parsed)
        decoded as PluginDashboardCodec.Result.Parsed

        // The bundled widget is the live-chart tile, and its root is a chart naming a live host feed.
        assertEquals("advisor_trend", decoded.widget.id)
        assertEquals(ComponentType.CHART, decoded.widget.content.type)

        // requiredScopes is derived from the content tree and folds the chart's live series, so it is
        // the real gate the registry applies: a chart naming a scope the manifest never requests would
        // render empty on a device, which no codec catches.
        val requested = manifest().permissions
            .firstOrNull { it.permission == PluginPermission.READ_APPROVED_DATA }
            ?.scopes
            .orEmpty()
        assertEquals(
            "the chart reads a scope the manifest never requests",
            emptySet<String>(),
            decoded.widget.requiredScopes - requested,
        )
        // The live series is the temperature feed, so the derived appetite surfaces the thermals scope.
        assertTrue(
            "the chart's live series should surface the thermals scope",
            decoded.widget.requiredScopes.contains("thermals"),
        )
    }
}
