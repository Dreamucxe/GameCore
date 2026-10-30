package com.gamecore.core.plugin.theme

import com.gamecore.core.plugin.LoadedPlugin
import com.gamecore.core.plugin.PluginApi
import com.gamecore.core.plugin.PluginPackageLoader
import com.gamecore.core.plugin.PluginPermission
import com.gamecore.core.plugin.PluginSection
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The nine Liquid Glass font packages in `dist/`, validated through the host's own loader.
 *
 * The 3.8.0 release turns these data-only packages into whole-app font themes: each carries the same
 * schema-4 glass theme as `dist/liquidglass` and changes only the `font` id, so the host must bundle
 * all nine faces and accept `minHostVersion` 46. This test proves the exact bytes the install flow
 * would open are accepted, that the floor is the 3.8.0 host, and that each package decodes to the
 * face it names — the package itself ships no font file.
 *
 * JUnit4 idiom (`:app` module): `assertEquals(message, expected, actual)` — the message is FIRST.
 */
class LiquidGlassFontThemePackagesTest {

    /** A package directory, the manifest id suffix and the face the theme must resolve to. */
    private data class FontPack(
        val dir: String,
        val manifestSuffix: String,
        val font: PluginTheme.Font,
    )

    private val packs = listOf(
        FontPack("cinzel", "cinzel", PluginTheme.Font.CINZEL),
        FontPack("comic-neue", "comic_neue", PluginTheme.Font.COMIC_NEUE),
        FontPack("inter", "inter", PluginTheme.Font.INTER),
        FontPack("jetbrains-mono", "jetbrains_mono", PluginTheme.Font.JETBRAINS_MONO),
        FontPack("minecraft", "minecraft", PluginTheme.Font.MINECRAFT),
        FontPack("orbitron", "orbitron", PluginTheme.Font.ORBITRON),
        FontPack("press-start-2p", "press_start_2p", PluginTheme.Font.PRESS_START_2P),
        FontPack("rubik", "rubik", PluginTheme.Font.RUBIK),
        FontPack("vt323", "vt323", PluginTheme.Font.VT323),
    )

    private fun artifact(pack: FontPack): File = File(
        "../dist/liquidglass-font-${pack.dir}/liquidglass-font-${pack.dir}.gcplugin",
    )

    private fun loadedOrFail(pack: FontPack): LoadedPlugin {
        val file = artifact(pack)
        assertTrue("deliverable missing at ${file.absolutePath}", file.isFile)
        return when (val result = PluginPackageLoader.load(file.readBytes())) {
            is PluginPackageLoader.Result.Loaded -> result.plugin
            is PluginPackageLoader.Result.Rejected -> {
                fail("host loader rejected ${file.name}: ${result.reason} ${result.detail}")
                error("unreachable")
            }
        }
    }

    @Test
    fun `all nine packages load and declare the 3_8 host floor`() {
        assertEquals("nine Liquid Glass font packages", 9, packs.size)
        for (pack in packs) {
            val manifest = loadedOrFail(pack).manifest
            assertEquals(
                "${pack.dir} id",
                "com.dreamucxe.gamecore.liquidglass.font.${pack.manifestSuffix}",
                manifest.id,
            )
            assertEquals("${pack.dir} version", 1, manifest.version)
            assertEquals("${pack.dir} versionName", "1.0.0", manifest.versionName)
            assertEquals("${pack.dir} api", PluginApi.CURRENT, manifest.api)
            assertEquals("${pack.dir} minHostVersion", 46, manifest.minHostVersion)
            assertTrue("${pack.dir} must run on this build", manifest.compatibility.isRunnable)
            assertEquals(
                "${pack.dir} declares theme and settings only",
                setOf(PluginSection.THEME, PluginSection.SETTINGS),
                manifest.declares,
            )
            assertEquals(
                "${pack.dir} permissions",
                listOf(PluginPermission.CHANGE_UI, PluginPermission.STORE_DATA),
                manifest.permissions.map { it.permission },
            )
        }
    }

    @Test
    fun `each package decodes to a glass theme naming its own bundled face`() {
        val fonts = packs.map { pack ->
            val bytes = loadedOrFail(pack).entry("theme.json")
            assertNotNull("${pack.dir} carries theme.json", bytes)
            val theme = when (val result = PluginThemeCodec.decode(String(bytes!!))) {
                is PluginThemeCodec.Result.Parsed -> result.theme
                is PluginThemeCodec.Result.Rejected -> {
                    fail("${pack.dir} theme rejected: ${result.reason} ${result.detail}")
                    error("unreachable")
                }
            }

            assertEquals("${pack.dir} schema", PluginTheme.SCHEMA_V4, theme.schema)
            assertEquals("${pack.dir} font", pack.font, theme.font)
            assertEquals("${pack.dir} font id", pack.font.id, theme.font.id)
            assertTrue("${pack.dir} theme name", theme.name.startsWith("Liquid Glass"))
            assertTrue("${pack.dir} dark theme", theme.dark)
            assertEquals("${pack.dir} primary", 0xFF0A6FE0.toInt(), theme.primary)

            val glass = theme.glass
            assertNotNull("${pack.dir} glass block", glass)
            assertEquals("${pack.dir} glass intensity", 0.7f, glass!!.intensity, 1e-6f)
            assertEquals("${pack.dir} glass setting", "glass_intensity", glass.intensitySetting)

            theme.font
        }

        assertEquals(
            "each package names a different bundled face",
            packs.size,
            fonts.distinct().size,
        )
    }

    @Test
    fun `each package pins the sidecar the packager wrote`() {
        for (pack in packs) {
            val plugin = loadedOrFail(pack)
            assertEquals("${pack.dir} sha length", 64, plugin.sha256.length)
            val sidecar = File(artifact(pack).parentFile, artifact(pack).name + ".sha256")
            assertTrue("${pack.dir} sidecar missing", sidecar.isFile)
            assertTrue(
                "${pack.dir} loader sha matches the sidecar",
                sidecar.readText().startsWith(plugin.sha256),
            )
        }
    }
}
