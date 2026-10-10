package com.gamecore.core.plugin

import com.gamecore.core.plugin.content.AutomationEngine
import com.gamecore.core.plugin.content.AutomationRule
import com.gamecore.core.plugin.content.RulesCodec
import com.gamecore.core.plugin.overlay.PluginOverlayCodec
import com.gamecore.core.plugin.theme.PluginThemeCodec
import org.junit.Assert.assertEquals
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
}
