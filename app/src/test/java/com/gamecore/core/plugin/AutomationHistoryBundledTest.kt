package com.gamecore.core.plugin

import com.gamecore.core.plugin.ui.PluginScreensCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled "Automation History" plugin is a real package, not a hand-waved one.
 *
 * This is the same gate [BundledPluginsTest] holds the theme/pill/rules plugins to, applied to the
 * platform's one api-4 plugin and its declarative screens: the asset files are read from disk and
 * run through the production [PluginManifestCodec] and [PluginScreensCodec], so a typo in a bundled
 * JSON fails the build rather than shipping a broken default. (The SDK's
 * `AutomationHistoryPackageTest` regenerates these same files from the authoring DSL; this test
 * proves whatever is on disk still loads.)
 */
class AutomationHistoryBundledTest {

    private val assets = File("src/main/assets/plugins")

    private fun read(path: String): String {
        val f = File(assets, path)
        assertTrue("missing bundled asset: ${f.path}", f.exists())
        return f.readText()
    }

    @Test
    fun `manifest parses as the api-4 automation-history plugin`() {
        val decoded = PluginManifestCodec.decode(read("automationhistory/manifest.json"))
        assertTrue("manifest did not parse: $decoded", decoded is PluginManifestCodec.Result.Parsed)
        decoded as PluginManifestCodec.Result.Parsed
        val m = decoded.manifest

        assertEquals("com.dreamucxe.gamecore.automationhistory", m.id)
        // api 4 is the whole reason this plugin exists: the automation_history read scope is an
        // API-4 meaning, so an older host rejects the package as TooNew rather than loading it.
        assertEquals(4, m.api)
        assertTrue("declares the screens section", m.declares.contains(PluginSection.SCREENS))

        // change_ui gates the screens; read_approved_data{automation_history} unlocks the host trail.
        assertTrue(
            "requests change_ui",
            m.permissions.any { it.permission == PluginPermission.CHANGE_UI },
        )
        val read = m.permissions.firstOrNull { it.permission == PluginPermission.READ_APPROVED_DATA }
        assertTrue("requests read_approved_data", read != null)
        assertTrue(
            "read_approved_data is scoped to automation_history (DataScope.AUTOMATION_HISTORY)",
            read!!.scopes.contains(DataScope.AUTOMATION_HISTORY.id),
        )
    }

    @Test
    fun `screens parse through the host screens codec`() {
        val decoded = PluginScreensCodec.decode(read("automationhistory/screens.json"))
        assertTrue("screens did not parse: $decoded", decoded is PluginScreensCodec.Result.Parsed)
        decoded as PluginScreensCodec.Result.Parsed
        assertEquals(
            listOf("overview", "how-it-fills", "about"),
            decoded.screens.map { it.id },
        )
    }
}
