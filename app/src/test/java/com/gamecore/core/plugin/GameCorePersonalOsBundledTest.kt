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
 * Part M gate for "GameCore Personal OS": the bundled subset is a real set of packages, not
 * hand-waved JSON. Each file the host loads on first run — manifest, theme, screens, settings and
 * the representative widget — must pass the SAME production codec a third-party download goes
 * through, so a typo in a bundled asset fails this build rather than shipping a broken default.
 *
 * The one agreement nothing else checks is a widget's data appetite against its own manifest: the
 * registry drops a widget whose scopes the user was never offered, silently, by the card simply
 * not appearing. Checked as a subset, since the manifest may legitimately request a scope for a
 * screen the widget does not use; the direction that breaks is the widget needing more than asked.
 */
class GameCorePersonalOsBundledTest {

    private val assets = File("src/main/assets/plugins/personalos")

    private fun read(path: String): String {
        val f = File(assets, path)
        assertTrue("missing bundled asset: ${f.path}", f.exists())
        return f.readText()
    }

    @Test
    fun `manifest parses with the right id, declares and permissions`() {
        val m = PluginManifestCodec.decode(read("manifest.json"))
        assertTrue("manifest did not parse: $m", m is PluginManifestCodec.Result.Parsed)
        m as PluginManifestCodec.Result.Parsed

        assertEquals("com.dreamucxe.gamecore.personalos", m.manifest.id)
        assertTrue(m.manifest.declares.contains(PluginSection.THEME))
        assertTrue(m.manifest.declares.contains(PluginSection.SCREENS))
        assertTrue(m.manifest.declares.contains(PluginSection.WIDGETS))
        assertTrue(m.manifest.declares.contains(PluginSection.SETTINGS))

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
        assertTrue(PluginThemeCodec.decode(read("theme.json")) is PluginThemeCodec.Result.Parsed)
        assertTrue(PluginScreensCodec.decode(read("screens.json")) is PluginScreensCodec.Result.Parsed)
        assertTrue(PluginSettingsCodec.decode(read("settings.json")) is PluginSettingsCodec.Result.Parsed)
    }

    @Test
    fun `dashboard widget asks only for scopes its manifest requests`() {
        val decoded = PluginDashboardCodec.decode(read("widgets.json"))
        assertTrue("widgets.json did not parse: $decoded", decoded is PluginDashboardCodec.Result.Parsed)
        decoded as PluginDashboardCodec.Result.Parsed

        val m = PluginManifestCodec.decode(read("manifest.json")) as PluginManifestCodec.Result.Parsed
        // Declaring the section and holding the permission are both prerequisites the registry checks
        // before it ever reads the file, so a widget that parses but is missing either is still dead.
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
