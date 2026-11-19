package com.gamecore.core.plugin

import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.plugin.content.AutomationEngine
import com.gamecore.core.plugin.content.AutomationRule
import com.gamecore.core.plugin.content.RulesCodec
import com.gamecore.core.plugin.dashboard.PluginDashboardCodec
import com.gamecore.core.plugin.icon.PluginIconCodec
import com.gamecore.core.plugin.overlay.PluginOverlayCodec
import com.gamecore.core.plugin.settings.PluginSettingsCodec
import com.gamecore.core.plugin.theme.PluginThemeCodec
import com.gamecore.core.plugin.theme.glassIntensityScale
import com.gamecore.core.plugin.ui.PluginScreensCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Part M gate: the bundled plugins are real packages, not hand-waved ones.
 *
 * Each bundled plugin's manifest, theme and rules must pass the *same* codecs a third-party
 * download goes through — if "builds == loads" is the promise of the format, the plugins
 * GameCore itself ships have to honour it first. These tests read the actual asset files from
 * disk and run them through the production codecs, so a typo in a bundled JSON fails the build
 * rather than shipping a broken default.
 */
class BundledPluginsTest {

    private val assets = File("src/main/assets/plugins")

    private fun read(path: String): String {
        val f = File(assets, path)
        assertTrue("missing bundled asset: ${f.path}", f.exists())
        return f.readText()
    }

    @Test
    fun `minimal manifest and theme parse`() {
        val m = PluginManifestCodec.decode(read("minimal/manifest.json"))
        assertTrue(m is PluginManifestCodec.Result.Parsed)
        m as PluginManifestCodec.Result.Parsed
        assertEquals("com.gamecore.plugin.minimal", m.manifest.id)
        assertTrue(m.manifest.declares.contains(PluginSection.THEME))

        val t = PluginThemeCodec.decode(read("minimal/theme.json"))
        assertTrue(t is PluginThemeCodec.Result.Parsed)
    }

    @Test
    fun `pixel manifest and theme parse`() {
        assertTrue(PluginManifestCodec.decode(read("pixel/manifest.json")) is PluginManifestCodec.Result.Parsed)
        assertTrue(PluginThemeCodec.decode(read("pixel/theme.json")) is PluginThemeCodec.Result.Parsed)
    }

    @Test
    fun `performance manifest parses and declares the broker permissions`() {
        val m = PluginManifestCodec.decode(read("performance/manifest.json")) as PluginManifestCodec.Result.Parsed
        val perms = m.manifest.permissions.map { it.permission }
        assertTrue(perms.contains(PluginPermission.SYSTEM_ACTIONS))
        assertTrue(perms.contains(PluginPermission.RUN_AUTOMATIONS))
    }

    /**
     * The one agreement nothing else checks: a bundled module's appetite against its own manifest.
     *
     * `pill.json` names stats; `manifest.json` names the data scopes those stats come from; and the
     * registry drops a module whose scopes the user has not granted. If the manifest never *asked* for
     * a scope the module needs, the user is never offered the grant, so the module can never be shown
     * — and it fails silently, by the module simply not appearing, with no rejection anywhere to read.
     * Both halves parse perfectly on their own, which is exactly why this needs its own assertion.
     *
     * Checked as a subset rather than an equality: a plugin may legitimately ask for a scope for a
     * reason other than its overlay (a screen, a stats panel), and the direction that breaks is the
     * module needing more than the manifest requested.
     */
    @Test
    fun `performance overlay module asks only for scopes its manifest requests`() {
        val decoded = PluginOverlayCodec.decode(read("performance/pill.json"))
        assertTrue("pill.json did not parse: $decoded", decoded is PluginOverlayCodec.Result.Parsed)
        decoded as PluginOverlayCodec.Result.Parsed

        val m = PluginManifestCodec.decode(read("performance/manifest.json")) as PluginManifestCodec.Result.Parsed
        // Declaring the section and holding the permission are both prerequisites the registry checks
        // before it ever reads the file, so a module that parses but is missing either is still dead.
        assertTrue(m.manifest.declares.contains(PluginSection.PILL))
        assertTrue(m.manifest.permissions.any { it.permission == PluginPermission.CREATE_OVERLAY })

        val requested = m.manifest.permissions
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
    fun `performance rules parse and reference only real broker actions`() {
        val r = RulesCodec.decode(read("performance/rules.json"))
        assertTrue(r is RulesCodec.Result.Parsed)
        r as RulesCodec.Result.Parsed
        val engine = AutomationEngine()
        r.rules.forEach { assertTrue(engine.validate(it) is AutomationEngine.Validation.Valid) }
        // The launch rule fires with no conditions.
        val launch = r.rules.first { it.trigger == AutomationRule.Trigger.GAME_LAUNCHED }
        assertTrue(engine.evaluate(launch, AutomationRule.Trigger.GAME_LAUNCHED, emptyMap()).isNotEmpty())
    }

    /**
     * The 5.7 gate: every bundled pack is a package the loader would accept.
     *
     * The registry's bundled reader returns null for a declared section it cannot find and null for a
     * manifest it cannot parse, and a null plugin simply does not appear — there is no error the user
     * could act on. That failure mode is exactly what makes this test necessary when the library goes
     * from 23 hand-written packs to 268: a typo or a missing directory in one of them would ship as a
     * silently missing theme, not a build failure.
     *
     * It mirrors the loader's own rules rather than calling it: `PluginRegistryImpl` needs an Android
     * `AssetManager`, and the point is to check the files on disk before an APK exists.
     */
    @Test
    fun `every bundled plugin is a package the loader would accept`() {
        val directories = assets.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue("no bundled plugins found at ${assets.absolutePath}", directories.size >= 268)

        val ids = mutableSetOf<String>()
        var themes = 0
        for (directory in directories) {
            val where = directory.name
            val manifestFile = File(directory, "manifest.json")
            assertTrue("$where has no manifest.json", manifestFile.isFile)
            val parsed = PluginManifestCodec.decode(manifestFile.readText())
            assertTrue("$where manifest rejected: $parsed", parsed is PluginManifestCodec.Result.Parsed)
            val manifest = (parsed as PluginManifestCodec.Result.Parsed).manifest
            assertNotNull("$where has an invalid package id", TextSanitizer.validatePackageName(manifest.id))
            assertTrue("duplicate bundled plugin id ${manifest.id}", ids.add(manifest.id))

            for (section in manifest.declares) {
                val files = sectionFiles(directory, section)
                assertTrue("$where declares ${section.id} but has no ${section.id} file", files.isNotEmpty())
                when (section) {
                    PluginSection.THEME -> {
                        themes++
                        val theme = PluginThemeCodec.decode(files.first().readText())
                        assertTrue("$where theme rejected: $theme", theme is PluginThemeCodec.Result.Parsed)
                        val setting = (theme as PluginThemeCodec.Result.Parsed).theme.glass?.intensitySetting
                        if (setting != null) {
                            val schema = PluginSettingsCodec.decode(
                                sectionFiles(directory, PluginSection.SETTINGS).firstOrNull()?.readText(),
                            )
                            assertTrue(
                                "$where names glass setting \"$setting\" but its settings do not parse",
                                schema is PluginSettingsCodec.Result.Parsed,
                            )
                            val def = (schema as PluginSettingsCodec.Result.Parsed).schema.byId(setting)
                            assertNotNull("$where names glass setting \"$setting\" which it does not declare", def)
                            assertNotNull(
                                "$where glass setting \"$setting\" is not a 0..1 numeric control",
                                def!!.glassIntensityScale(),
                            )
                        }
                    }
                    PluginSection.SCREENS -> files.forEach {
                        assertTrue(
                            "$where screens rejected in ${it.name}: ${PluginScreensCodec.decode(it.readText())}",
                            PluginScreensCodec.decode(it.readText()) is PluginScreensCodec.Result.Parsed,
                        )
                    }
                    PluginSection.RULES -> files.forEach {
                        assertTrue(
                            "$where rules rejected in ${it.name}: ${RulesCodec.decode(it.readText())}",
                            RulesCodec.decode(it.readText()) is RulesCodec.Result.Parsed,
                        )
                    }
                    PluginSection.WIDGETS -> files.forEach {
                        assertTrue(
                            "$where widget rejected in ${it.name}: ${PluginDashboardCodec.decode(it.readText())}",
                            PluginDashboardCodec.decode(it.readText()) is PluginDashboardCodec.Result.Parsed,
                        )
                    }
                    PluginSection.SETTINGS -> files.forEach {
                        assertTrue(
                            "$where settings rejected: ${PluginSettingsCodec.decode(it.readText())}",
                            PluginSettingsCodec.decode(it.readText()) is PluginSettingsCodec.Result.Parsed,
                        )
                    }
                    PluginSection.ICON -> files.forEach {
                        assertTrue(
                            "$where icon rejected: ${PluginIconCodec.decode(it.readText())}",
                            PluginIconCodec.decode(it.readText()) is PluginIconCodec.Result.Parsed,
                        )
                    }
                    PluginSection.PILL -> files.forEach {
                        assertTrue(
                            "$where pill rejected: ${PluginOverlayCodec.decode(it.readText())}",
                            PluginOverlayCodec.decode(it.readText()) is PluginOverlayCodec.Result.Parsed,
                        )
                    }
                    // No bundled pack declares these; a future one must add its codec to this gate
                    // rather than slip past it.
                    else -> assertNull("$where declares unhandled section ${section.id}", section)
                }
            }
        }
        assertTrue("the bundled library should carry the full theme collection, found $themes", themes >= 254)
    }

    /**
     * The two layouts the loader accepts, resolved the same way [sectionFiles] does it here.
     *
     * A directory wins when it exists; the flat `<section>.json` is the fallback that keeps every pack
     * written before 5.7 loading unchanged.
     */
    private fun sectionFiles(directory: File, section: PluginSection): List<File> {
        val prefix = section.entryPrefix
        val nested = File(directory, prefix.trimEnd('/'))
        val inDirectory = nested.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedBy { it.name }
            .orEmpty()
        if (inDirectory.isNotEmpty()) return inDirectory
        val flatName = if (prefix.endsWith("/")) prefix.trimEnd('/') + ".json" else prefix
        val flat = File(directory, flatName)
        return if (flat.isFile) listOf(flat) else emptyList()
    }
}
