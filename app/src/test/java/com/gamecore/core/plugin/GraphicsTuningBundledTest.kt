package com.gamecore.core.plugin

import com.gamecore.core.plugin.content.AutomationEngine
import com.gamecore.core.plugin.content.RulesCodec
import com.gamecore.core.plugin.dashboard.PluginDashboardCodec
import com.gamecore.core.plugin.overlay.PluginOverlayCodec
import com.gamecore.core.plugin.settings.PluginSettingsCodec
import com.gamecore.core.plugin.ui.ComponentType
import com.gamecore.core.plugin.ui.PluginScreensCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled "Graphics Tuning" plugin is a real package, not a hand-waved one.
 *
 * Its flat asset files are the ones the SDK's `GraphicsTuningPackageTest` packages and then flattens
 * into this directory under the exact names the host's bundled loader reads (a section whose entry
 * prefix is a directory, like `screens/`, ships as `screens.json`; one whose prefix is already a file
 * name, like `pill.json`, ships unchanged; and the one representative `widgets/<id>.json` ships as
 * `widgets.json`). These tests push those same files through the production host codecs — the path a
 * sideloaded `.gcplugin` also takes — so a typo in a bundled JSON fails the build rather than shipping
 * a dead default. A declared section whose flat file is missing makes the bundled plugin vanish
 * silently, which is the failure these tests exist to make loud.
 *
 * The load-bearing assertions are the two subset checks, on the overlay module and on the dashboard
 * chart. A module or a chart naming a live feed whose data scope the `manifest.json` never requests
 * parses perfectly and then renders empty, because the user is never offered the grant.
 * `requiredScopes` is derived from the content tree, so this is the real gate the registry applies and
 * nothing else in the pipeline catches a mismatch.
 */
class GraphicsTuningBundledTest {

    private val assets = File("src/main/assets/plugins/graphics")

    private fun read(path: String): String {
        val f = File(assets, path)
        assertTrue("missing bundled asset: ${f.path} (GraphicsTuningPackageTest generates it)", f.exists())
        return f.readText()
    }

    private fun manifest(): PluginManifest {
        val m = PluginManifestCodec.decode(read("manifest.json"))
        assertTrue("manifest.json did not parse: $m", m is PluginManifestCodec.Result.Parsed)
        return (m as PluginManifestCodec.Result.Parsed).manifest
    }

    /** The scopes the manifest requests on `read_approved_data`. */
    private fun requestedScopes(): Set<String> = manifest().permissions
        .firstOrNull { it.permission == PluginPermission.READ_APPROVED_DATA }
        ?.scopes
        .orEmpty()

    @Test
    fun `manifest parses and declares the five sections with their seven permissions`() {
        val manifest = manifest()
        assertEquals("com.dreamucxe.gamecore.graphics", manifest.id)
        assertEquals(3, manifest.api)
        assertEquals(28, manifest.minHostVersion)
        assertTrue(manifest.declares.contains(PluginSection.SCREENS))
        assertTrue(manifest.declares.contains(PluginSection.PILL))
        assertTrue(manifest.declares.contains(PluginSection.WIDGETS))
        assertTrue(manifest.declares.contains(PluginSection.SETTINGS))
        assertTrue(manifest.declares.contains(PluginSection.RULES))

        val perms = manifest.permissions.map { it.permission }.toSet()
        assertEquals(
            setOf(
                PluginPermission.CHANGE_UI,
                PluginPermission.CREATE_OVERLAY,
                PluginPermission.ADD_DASHBOARD_WIDGETS,
                PluginPermission.READ_APPROVED_DATA,
                PluginPermission.STORE_DATA,
                PluginPermission.RUN_AUTOMATIONS,
                PluginPermission.SYSTEM_ACTIONS,
            ),
            perms,
        )
        assertEquals(setOf("device_stats", "thermals"), requestedScopes())
    }

    @Test
    fun `screens parse into the guide the texture explainer and the limits`() {
        val decoded = PluginScreensCodec.decode(read("screens.json"))
        assertTrue("screens.json did not parse: $decoded", decoded is PluginScreensCodec.Result.Parsed)
        decoded as PluginScreensCodec.Result.Parsed
        assertEquals(listOf("tuning", "textures", "limits"), decoded.screens.map { it.id })
    }

    @Test
    fun `the overlay probe asks only for scopes its manifest requests`() {
        val decoded = PluginOverlayCodec.decode(read("pill.json"))
        assertTrue("pill.json did not parse: $decoded", decoded is PluginOverlayCodec.Result.Parsed)
        decoded as PluginOverlayCodec.Result.Parsed
        assertEquals("graphics-probe", decoded.module.id)

        // Declaring the section and holding the permission are both prerequisites the registry checks
        // before it ever reads the file, so a module that parses but is missing either is still dead.
        val manifest = manifest()
        assertTrue(manifest.declares.contains(PluginSection.PILL))
        assertTrue(manifest.permissions.any { it.permission == PluginPermission.CREATE_OVERLAY })

        assertEquals(
            "the module needs data scopes the manifest never requests",
            emptySet<String>(),
            decoded.module.requiredScopes - requestedScopes(),
        )
        // The probe's whole purpose is the A/B loop, so it must actually surface both apertures.
        assertTrue(
            "the probe should read device_stats for frame rate and GPU load",
            decoded.module.requiredScopes.contains("device_stats"),
        )
        assertTrue(
            "the probe should read thermals for temperature",
            decoded.module.requiredScopes.contains("thermals"),
        )
    }

    @Test
    fun `settings parse into the five typed preferences`() {
        val decoded = PluginSettingsCodec.decode(read("settings.json"))
        assertTrue("settings.json did not parse: $decoded", decoded is PluginSettingsCodec.Result.Parsed)
        decoded as PluginSettingsCodec.Result.Parsed
        assertEquals(
            listOf("target_fps", "warn_temp_c", "device_tier", "guide_detail", "show_trend_widget"),
            decoded.schema.definitions.map { it.id },
        )
    }

    @Test
    fun `rules decode and validate as unattended broker automations`() {
        val decoded = RulesCodec.decode(read("rules.json"))
        assertTrue("rules.json did not parse: $decoded", decoded is RulesCodec.Result.Parsed)
        decoded as RulesCodec.Result.Parsed
        assertEquals(
            listOf("launch_boost", "sustain_fps", "trim_system_animations"),
            decoded.rules.map { it.id },
        )

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
        assertEquals("graphics_trend", decoded.widget.id)
        assertEquals(ComponentType.CHART, decoded.widget.content.type)

        assertEquals(
            "the chart reads a scope the manifest never requests",
            emptySet<String>(),
            decoded.widget.requiredScopes - requestedScopes(),
        )
        // The live series is the frame-rate feed, so the derived appetite surfaces device_stats.
        assertTrue(
            "the chart's live series should surface the device_stats scope",
            decoded.widget.requiredScopes.contains("device_stats"),
        )
    }
}
