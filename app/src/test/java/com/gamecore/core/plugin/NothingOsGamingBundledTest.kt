package com.gamecore.core.plugin

import com.gamecore.core.plugin.content.AutomationEngine
import com.gamecore.core.plugin.content.AutomationRule
import com.gamecore.core.plugin.content.RulesCodec
import com.gamecore.core.plugin.theme.PluginTheme
import com.gamecore.core.plugin.theme.PluginThemeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled "Nothing OS Gaming" plugin is a real package, not a hand-waved one.
 *
 * Its asset files are produced by `NothingOsGamingPackageTest` in the SDK module (which packages the
 * `.gcplugin` and flattens the same bytes into `src/main/assets/plugins/nothingos/`), so this test
 * runs those files back through the *same* production codecs a third-party download goes through. If
 * "builds == loads" is the promise of the format, the plugins GameCore itself ships have to honour it
 * first — a typo in a bundled JSON fails the build here rather than shipping a broken default.
 *
 * Mirrors `BundledPluginsTest`: read the actual asset from disk, decode through the real codec, assert
 * `Parsed`, and run every rule through the host's own `AutomationEngine`.
 */
class NothingOsGamingBundledTest {

    private val assets = File("src/main/assets/plugins")

    private fun read(path: String): String {
        val f = File(assets, path)
        assertTrue("missing bundled asset: ${f.path}", f.exists())
        return f.readText()
    }

    @Test
    fun `manifest parses, declares theme and rules, and holds its three permissions`() {
        val decoded = PluginManifestCodec.decode(read("nothingos/manifest.json"))
        assertTrue("manifest did not parse: $decoded", decoded is PluginManifestCodec.Result.Parsed)
        decoded as PluginManifestCodec.Result.Parsed

        assertEquals("com.dreamucxe.gamecore.nothingos", decoded.manifest.id)
        assertTrue("declares theme", decoded.manifest.declares.contains(PluginSection.THEME))
        assertTrue("declares rules", decoded.manifest.declares.contains(PluginSection.RULES))

        val perms = decoded.manifest.permissions.map { it.permission }
        assertTrue("requests change_ui", perms.contains(PluginPermission.CHANGE_UI))
        assertTrue("requests run_automations", perms.contains(PluginPermission.RUN_AUTOMATIONS))
        assertTrue("requests system_actions", perms.contains(PluginPermission.SYSTEM_ACTIONS))
    }

    @Test
    fun `theme parses as a schema-2 amoled theme carrying the Nothing red accent`() {
        val decoded = PluginThemeCodec.decode(read("nothingos/theme.json"))
        assertTrue("theme did not parse: $decoded", decoded is PluginThemeCodec.Result.Parsed)
        decoded as PluginThemeCodec.Result.Parsed

        assertEquals(PluginTheme.SCHEMA_V2, decoded.theme.schema)
        assertTrue("amoled true-black base", decoded.theme.amoled)
        // The accent survives the codec as opaque ARGB — the Nothing red #D71921.
        assertEquals(0xFFD71921.toInt(), decoded.theme.accent)
    }

    @Test
    fun `rules parse and every rule validates and fires through the automation engine`() {
        val decoded = RulesCodec.decode(read("nothingos/rules.json"))
        assertTrue("rules did not parse: $decoded", decoded is RulesCodec.Result.Parsed)
        decoded as RulesCodec.Result.Parsed

        val engine = AutomationEngine()
        decoded.rules.forEach { assertTrue(engine.validate(it) is AutomationEngine.Validation.Valid) }

        // The launch rule has no conditions, so it fires on its trigger alone.
        val launch = decoded.rules.first { it.trigger == AutomationRule.Trigger.GAME_LAUNCHED }
        assertTrue(engine.evaluate(launch, AutomationRule.Trigger.GAME_LAUNCHED, emptyMap()).isNotEmpty())

        // The battery rule is gated on battery <= 15: it fires at 10, stays silent at 50.
        val battery = decoded.rules.first { it.trigger == AutomationRule.Trigger.BATTERY_LOW }
        assertTrue(engine.evaluate(battery, AutomationRule.Trigger.BATTERY_LOW, mapOf("battery" to 10.0)).isNotEmpty())
        assertTrue(engine.evaluate(battery, AutomationRule.Trigger.BATTERY_LOW, mapOf("battery" to 50.0)).isEmpty())
    }
}
