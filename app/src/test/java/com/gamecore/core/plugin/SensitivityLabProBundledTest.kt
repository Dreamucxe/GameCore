package com.gamecore.core.plugin

import com.gamecore.core.plugin.overlay.PluginOverlayCodec
import com.gamecore.core.plugin.settings.PluginSettingsCodec
import com.gamecore.core.plugin.ui.PluginScreensCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled "Sensitivity Lab Pro" plugin is a real package, not a hand-waved one.
 *
 * Its flat asset files are the ones the SDK's `SensitivityLabProPackageTest` packages and then
 * flattens into this directory under the exact names the host's bundled loader reads (a section
 * whose entry prefix is a directory, like `screens/`, ships as `screens.json`). These tests push
 * those same files through the production host codecs — the path a sideloaded `.gcplugin` also
 * takes — so a typo in a bundled JSON fails the build rather than shipping a dead default.
 *
 * The load-bearing assertion is the subset check: a `pill.json` naming a stat whose data scope the
 * `manifest.json` never requests parses perfectly and then never shows, because the user is never
 * offered the grant. Nothing else in the pipeline catches that, so it is checked here.
 */
class SensitivityLabProBundledTest {

    private val assets = File("src/main/assets/plugins/sensitivitylab")

    private fun read(path: String): String {
        val f = File(assets, path)
        assertTrue("missing bundled asset: ${f.path} (SensitivityLabProPackageTest generates it)", f.exists())
        return f.readText()
    }

    private fun manifest(): PluginManifest {
        val m = PluginManifestCodec.decode(read("manifest.json"))
        assertTrue("manifest.json did not parse: $m", m is PluginManifestCodec.Result.Parsed)
        return (m as PluginManifestCodec.Result.Parsed).manifest
    }

    @Test
    fun `manifest parses and declares the three sections with their permissions`() {
        val manifest = manifest()
        assertEquals("com.dreamucxe.gamecore.sensitivitylab", manifest.id)
        assertEquals(3, manifest.api)
        assertTrue(manifest.declares.contains(PluginSection.PILL))
        assertTrue(manifest.declares.contains(PluginSection.SCREENS))
        assertTrue(manifest.declares.contains(PluginSection.SETTINGS))

        val perms = manifest.permissions.map { it.permission }
        assertTrue(perms.contains(PluginPermission.CREATE_OVERLAY))
        assertTrue(perms.contains(PluginPermission.READ_APPROVED_DATA))
        assertTrue(perms.contains(PluginPermission.CHANGE_UI))
        assertTrue(perms.contains(PluginPermission.STORE_DATA))
    }

    @Test
    fun `pill overlay module asks only for scopes its manifest requests`() {
        val decoded = PluginOverlayCodec.decode(read("pill.json"))
        assertTrue("pill.json did not parse: $decoded", decoded is PluginOverlayCodec.Result.Parsed)
        decoded as PluginOverlayCodec.Result.Parsed

        val manifest = manifest()
        assertTrue(manifest.declares.contains(PluginSection.PILL))
        assertTrue(manifest.permissions.any { it.permission == PluginPermission.CREATE_OVERLAY })

        val requested = manifest.permissions
            .firstOrNull { it.permission == PluginPermission.READ_APPROVED_DATA }
            ?.scopes
            .orEmpty()
        assertEquals(
            "the module needs data scopes the manifest never requests",
            emptySet<String>(),
            decoded.module.requiredScopes - requested,
        )
    }

    @Test
    fun `screens parse into the two reference screens`() {
        val decoded = PluginScreensCodec.decode(read("screens.json"))
        assertTrue("screens.json did not parse: $decoded", decoded is PluginScreensCodec.Result.Parsed)
        decoded as PluginScreensCodec.Result.Parsed
        assertEquals(listOf("presets", "guide"), decoded.screens.map { it.id })
    }

    @Test
    fun `settings parse into the five typed controls`() {
        val decoded = PluginSettingsCodec.decode(read("settings.json"))
        assertTrue("settings.json did not parse: $decoded", decoded is PluginSettingsCodec.Result.Parsed)
        decoded as PluginSettingsCodec.Result.Parsed
        assertEquals(
            listOf("preset", "dpi", "multiplier", "ads_multiplier", "invert_y"),
            decoded.schema.definitions.map { it.id },
        )
    }
}
