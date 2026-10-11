package com.gamecore.core.plugin

import com.gamecore.core.plugin.ui.ComponentType
import com.gamecore.core.plugin.ui.PluginScreensCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The whole on-host path for a declarative screen, proven end to end without a device: a `.gcplugin`
 * that declares `screens` and carries one or more `screens/<id>.json` files is loaded by [PluginPackageLoader],
 * its section files are found under the section prefix, and each decodes through [PluginScreensCodec]
 * into trusted [com.gamecore.core.plugin.ui.PluginScreen]s. That is exactly the sequence the registry's
 * on-demand `readScreens` performs — walk `pathsUnder(SCREENS)`, decode each, accumulate — so a green
 * run here is a green run for the delivery plumbing, both the bundled single-file shape and the
 * sideloaded many-file shape, through the existing `sectionEntries` machinery with no special-casing.
 *
 * Pure JVM: the loader and the codec are both Android-free, so the trust boundary is testable here.
 */
class ScreensPluginPackageTest {

    // ---------------------------------------------------------------- fixtures

    private fun manifestJson(declares: List<String> = listOf("screens")): String {
        val declaresJson = declares.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        return """
            {
              "format": "gamecore.plugin",
              "id": "com.example.screens",
              "name": "Screens Example",
              "version": 1,
              "versionName": "1.0",
              "api": 1,
              "minHostVersion": 1,
              "developer": "Tester",
              "description": "Ships a declarative screen.",
              "permissions": ["change_ui"],
              "declares": $declaresJson,
              "changelog": "first"
            }
        """.trimIndent()
    }

    private fun screensJson(vararg ids: String): String {
        val screens = ids.joinToString(",") { id ->
            """{"id":"$id","title":"${id.replaceFirstChar { it.uppercase() }}",""" +
                """"content":{"type":"text","props":{"text":"Hello $id"}}}"""
        }
        return """{"format":"gamecore.plugin.screens","version":1,"screens":[$screens]}"""
    }

    private fun zipOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            for ((name, data) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun loaded(result: PluginPackageLoader.Result): LoadedPlugin {
        assertTrue("expected Loaded but was $result", result is PluginPackageLoader.Result.Loaded)
        return (result as PluginPackageLoader.Result.Loaded).plugin
    }

    /** Mirrors what the registry's readScreens does with a loaded package's section files. */
    private fun readScreens(plugin: LoadedPlugin): List<String> {
        val seen = LinkedHashSet<String>()
        for (path in plugin.pathsUnder(PluginSection.SCREENS)) {
            val decoded = PluginScreensCodec.decode(String(plugin.entry(path)!!, Charsets.UTF_8))
            if (decoded is PluginScreensCodec.Result.Parsed) {
                decoded.screens.forEach { if (seen.add(it.id)) Unit }
            }
        }
        return seen.toList()
    }

    // ---------------------------------------------------------------- the bundled single-file shape

    @Test
    fun `a package with one screens file loads and declares the section`() {
        val zip = zipOf(
            PluginPackageLoader.MANIFEST_ENTRY to bytes(manifestJson()),
            "screens/screens.json" to bytes(screensJson("overview")),
        )
        val plugin = loaded(PluginPackageLoader.load(zip))
        assertTrue(PluginSection.SCREENS in plugin.manifest.declares)
        assertEquals(listOf("screens/screens.json"), plugin.pathsUnder(PluginSection.SCREENS))
    }

    @Test
    fun `one file may carry several screens`() {
        val zip = zipOf(
            PluginPackageLoader.MANIFEST_ENTRY to bytes(manifestJson()),
            "screens/screens.json" to bytes(screensJson("overview", "details", "about")),
        )
        val plugin = loaded(PluginPackageLoader.load(zip))
        assertEquals(listOf("overview", "details", "about"), readScreens(plugin))
    }

    @Test
    fun `the carried screen decodes into a validated node tree`() {
        val zip = zipOf(
            PluginPackageLoader.MANIFEST_ENTRY to bytes(manifestJson()),
            "screens/screens.json" to bytes(screensJson("overview")),
        )
        val plugin = loaded(PluginPackageLoader.load(zip))
        val decoded = PluginScreensCodec.decode(
            String(plugin.entry("screens/screens.json")!!, Charsets.UTF_8),
        )
        assertTrue(decoded is PluginScreensCodec.Result.Parsed)
        val screen = (decoded as PluginScreensCodec.Result.Parsed).screens.single()
        assertEquals("overview", screen.id)
        assertEquals(ComponentType.TEXT, screen.root.type)
    }

    // ---------------------------------------------------------------- the sideloaded many-file shape

    @Test
    fun `many screens files under the section are all found and decoded`() {
        val zip = zipOf(
            PluginPackageLoader.MANIFEST_ENTRY to bytes(manifestJson()),
            "screens/a.json" to bytes(screensJson("home")),
            "screens/b.json" to bytes(screensJson("stats")),
        )
        val plugin = loaded(PluginPackageLoader.load(zip))
        assertEquals(
            listOf("screens/a.json", "screens/b.json"),
            plugin.pathsUnder(PluginSection.SCREENS).sorted(),
        )
        assertEquals(listOf("home", "stats"), readScreens(plugin).sorted())
    }

    // ---------------------------------------------------------------- the loader still guards

    @Test
    fun `a screens file that is not declared is rejected`() {
        val zip = zipOf(
            PluginPackageLoader.MANIFEST_ENTRY to bytes(manifestJson(declares = emptyList())),
            "screens/screens.json" to bytes(screensJson("overview")),
        )
        val result = PluginPackageLoader.load(zip)
        assertTrue("expected Rejected but was $result", result is PluginPackageLoader.Result.Rejected)
    }

    @Test
    fun `declaring screens with no screens file is rejected`() {
        val zip = zipOf(
            PluginPackageLoader.MANIFEST_ENTRY to bytes(manifestJson()),
        )
        val result = PluginPackageLoader.load(zip)
        assertTrue("expected Rejected but was $result", result is PluginPackageLoader.Result.Rejected)
    }
}
