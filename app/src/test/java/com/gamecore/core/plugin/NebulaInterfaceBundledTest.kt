package com.gamecore.core.plugin

import com.gamecore.core.plugin.dashboard.PluginDashboardCodec
import com.gamecore.core.plugin.settings.PluginSettingsCodec
import com.gamecore.core.plugin.theme.PluginThemeCodec
import com.gamecore.core.plugin.ui.PluginScreensCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Part M gate for "Nebula Interface Suite": the bundled subset is a real package, not hand-waved
 * JSON. The manifest, theme, screens, settings and the representative widget are read from the
 * actual assets and run through the *same* production codecs a sideloaded `.gcplugin` download
 * goes through.
 *
 * The `.gcplugin` carries five widgets and seven screens; the bundled host path reads one
 * `widgets.json` and one flat `screens.json`, so `command` is the representative card here and
 * the package test is where the full set is checked. The data-appetite check is a subset check
 * for the reason [GameCorePersonalOsBundledTest] documents: a manifest may legitimately request
 * a scope for a screen the representative widget does not use; the direction that breaks is the
 * widget needing more than the manifest asked for.
 */
class NebulaInterfaceBundledTest {

    private val assets = File("src/main/assets/plugins/nebula")

    private fun read(path: String): String {
        val f = File(assets, path)
        assertTrue("missing bundled asset: ${f.path}", f.exists())
        return f.readText()
    }

    @Test
    fun `manifest parses with the right identity, declares and permissions`() {
        val m = PluginManifestCodec.decode(read("manifest.json"))
        assertTrue("manifest did not parse: $m", m is PluginManifestCodec.Result.Parsed)
        m as PluginManifestCodec.Result.Parsed

        assertEquals("com.dreamucxe.gamecore.nebula", m.manifest.id)
        assertEquals("Nebula Interface Suite", m.manifest.name)
        assertEquals(4, m.manifest.api)
        assertEquals(38, m.manifest.minHostVersion)
        assertTrue(m.manifest.declares.contains(PluginSection.THEME))
        assertTrue(m.manifest.declares.contains(PluginSection.SCREENS))
        assertTrue(m.manifest.declares.contains(PluginSection.WIDGETS))
        assertTrue(m.manifest.declares.contains(PluginSection.SETTINGS))
        assertTrue(m.manifest.declares.contains(PluginSection.ICON))

        val perms = m.manifest.permissions.map { it.permission }.toSet()
        assertEquals(
            setOf(
                PluginPermission.CHANGE_UI,
                PluginPermission.ADD_DASHBOARD_WIDGETS,
                PluginPermission.STORE_DATA,
                PluginPermission.READ_APPROVED_DATA,
                PluginPermission.CHANGE_ICON,
            ),
            perms,
        )
        val scopes = m.manifest.permissions
            .firstOrNull { it.permission == PluginPermission.READ_APPROVED_DATA }
            ?.scopes
            .orEmpty()
        assertEquals(setOf("device_stats", "thermals"), scopes)
    }

    @Test
    fun `theme, screens and settings sections parse`() {
        val theme = PluginThemeCodec.decode(read("theme.json"))
        assertTrue("theme did not parse: $theme", theme is PluginThemeCodec.Result.Parsed)
        theme as PluginThemeCodec.Result.Parsed
        assertEquals(4, theme.theme.schema)
        assertEquals("glass_intensity", theme.theme.glass?.intensitySetting)

        val screens = PluginScreensCodec.decode(read("screens.json"))
        assertTrue("screens did not parse: $screens", screens is PluginScreensCodec.Result.Parsed)
        screens as PluginScreensCodec.Result.Parsed
        assertEquals(7, screens.screens.size)
        assertEquals(
            listOf("control", "look", "motion", "dock", "type", "performance", "notes"),
            screens.screens.map { it.id },
        )

        val settings = PluginSettingsCodec.decode(read("settings.json"))
        assertTrue("settings did not parse: $settings", settings is PluginSettingsCodec.Result.Parsed)
        settings as PluginSettingsCodec.Result.Parsed
        assertEquals(1, settings.schema.definitions.size)
        assertEquals("glass_intensity", settings.schema.definitions.single().id)
    }

    @Test
    fun `representative widget asks only for scopes its manifest requests`() {
        val decoded = PluginDashboardCodec.decode(read("widgets.json"))
        assertTrue("widgets.json did not parse: $decoded", decoded is PluginDashboardCodec.Result.Parsed)
        decoded as PluginDashboardCodec.Result.Parsed
        assertEquals("command", decoded.widget.id)

        val m = PluginManifestCodec.decode(read("manifest.json")) as PluginManifestCodec.Result.Parsed
        assertTrue(m.manifest.declares.contains(PluginSection.WIDGETS))
        assertTrue(m.manifest.permissions.any { it.permission == PluginPermission.ADD_DASHBOARD_WIDGETS })

        val requested = m.manifest.permissions
            .firstOrNull { it.permission == PluginPermission.READ_APPROVED_DATA }
            ?.scopes
            .orEmpty()
        assertEquals(
            "the widget needs data scopes the manifest never requests",
            emptySet<String>(),
            decoded.widget.requiredScopes - requested,
        )
    }
}
