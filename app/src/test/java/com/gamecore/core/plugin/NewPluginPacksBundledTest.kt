package com.gamecore.core.plugin

import com.gamecore.core.plugin.content.AutomationEngine
import com.gamecore.core.plugin.content.RulesCodec
import com.gamecore.core.plugin.dashboard.PluginDashboardCodec
import com.gamecore.core.plugin.icon.PluginIconCodec
import com.gamecore.core.plugin.overlay.PluginOverlayCodec
import com.gamecore.core.plugin.theme.PluginThemeCodec
import com.gamecore.core.plugin.ui.PluginScreensCodec
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled copies of the ten new plugin packs, read through the same production codecs a
 * sideloaded `.gcplugin` goes through.
 *
 * The SDK package test proves the authored bytes; this test proves the bytes the APK actually
 * ships. That distinction matters for the widget packs: the host's bundled loader reads one
 * `widgets.json`, while the package carries many `widgets/<id>.json` files, so the bundled
 * representative is a different artifact from the one in `dist/`. Both must decode.
 */
class NewPluginPacksBundledTest {

    private val assets = File("src/main/assets/plugins")

    private data class Pack(
        val slug: String,
        val declares: Set<PluginSection>,
    )

    private val packs = listOf(
        Pack("nightshift", setOf(PluginSection.THEME, PluginSection.SCREENS, PluginSection.ICON)),
        Pack("colorblindsafe", setOf(PluginSection.THEME, PluginSection.SCREENS, PluginSection.ICON)),
        Pack("papercut", setOf(PluginSection.THEME, PluginSection.SCREENS, PluginSection.ICON)),
        Pack("minimalvitals", setOf(PluginSection.WIDGETS, PluginSection.ICON)),
        Pack("thermaldesk", setOf(PluginSection.WIDGETS, PluginSection.ICON)),
        Pack("batterybadge", setOf(PluginSection.PILL, PluginSection.ICON)),
        Pack("cooldownladder", setOf(PluginSection.RULES, PluginSection.SCREENS, PluginSection.ICON)),
        Pack("exitrestore", setOf(PluginSection.RULES, PluginSection.SCREENS, PluginSection.ICON)),
        Pack("chargerboost", setOf(PluginSection.RULES, PluginSection.SCREENS, PluginSection.ICON)),
        Pack("statdictionary", setOf(PluginSection.SCREENS, PluginSection.ICON)),
    )

    private fun read(slug: String, name: String): String {
        val f = File(assets, "$slug/$name")
        assertTrue("missing bundled asset: ${f.path}", f.exists())
        return f.readText()
    }

    @Test
    fun `all ten bundled manifests decode and declare the expected sections`() {
        packs.forEach { pack ->
            val parsed = PluginManifestCodec.decode(read(pack.slug, "manifest.json"))
            assertTrue("${pack.slug} manifest did not parse: $parsed", parsed is PluginManifestCodec.Result.Parsed)
            parsed as PluginManifestCodec.Result.Parsed
            assertEquals("${pack.slug} declares", pack.declares, parsed.manifest.declares)
            assertEquals(
                "${pack.slug} developer",
                "Dream",
                parsed.manifest.developer,
            )
            assertTrue("${pack.slug} description", parsed.manifest.description.isNotBlank())
            assertTrue("${pack.slug} icon host", parsed.manifest.minHostVersion >= 37)
        }
    }

    @Test
    fun `every bundled section decodes through the production codec`() {
        packs.forEach { pack ->
            val parsed = PluginManifestCodec.decode(read(pack.slug, "manifest.json")) as PluginManifestCodec.Result.Parsed
            val manifest = parsed.manifest
            val requested = manifest.permissions
                .firstOrNull { it.permission == PluginPermission.READ_APPROVED_DATA }
                ?.scopes
                .orEmpty()

            manifest.declares.forEach { section ->
                val file = when (section) {
                    PluginSection.THEME -> "theme.json"
                    PluginSection.PILL -> "pill.json"
                    PluginSection.SCREENS -> "screens.json"
                    PluginSection.RULES -> "rules.json"
                    PluginSection.WIDGETS -> "widgets.json"
                    PluginSection.ICON -> "icon.json"
                    PluginSection.SETTINGS -> "settings.json"
                    else -> null
                } ?: return@forEach
                val raw = read(pack.slug, file)
                when (section) {
                    PluginSection.THEME -> assertTrue(
                        "${pack.slug} theme did not parse: ${PluginThemeCodec.decode(raw)}",
                        PluginThemeCodec.decode(raw) is PluginThemeCodec.Result.Parsed,
                    )
                    PluginSection.PILL -> {
                        val decoded = PluginOverlayCodec.decode(raw)
                        assertTrue("${pack.slug} pill did not parse: $decoded", decoded is PluginOverlayCodec.Result.Parsed)
                        decoded as PluginOverlayCodec.Result.Parsed
                        assertEquals(
                            "${pack.slug} pill needs a scope the manifest does not request",
                            emptySet<String>(),
                            decoded.module.requiredScopes - requested,
                        )
                    }
                    PluginSection.SCREENS -> assertTrue(
                        "${pack.slug} screens did not parse: ${PluginScreensCodec.decode(raw)}",
                        PluginScreensCodec.decode(raw) is PluginScreensCodec.Result.Parsed,
                    )
                    PluginSection.RULES -> {
                        val decoded = RulesCodec.decode(raw)
                        assertTrue("${pack.slug} rules did not parse: $decoded", decoded is RulesCodec.Result.Parsed)
                        decoded as RulesCodec.Result.Parsed
                        val engine = AutomationEngine()
                        decoded.rules.forEach { rule ->
                            assertTrue(
                                "${pack.slug}/${rule.id} did not validate",
                                engine.validate(rule) is AutomationEngine.Validation.Valid,
                            )
                        }
                    }
                    PluginSection.WIDGETS -> {
                        val decoded = PluginDashboardCodec.decode(raw)
                        assertTrue("${pack.slug} widget did not parse: $decoded", decoded is PluginDashboardCodec.Result.Parsed)
                        decoded as PluginDashboardCodec.Result.Parsed
                        assertEquals(
                            "${pack.slug} widget needs a scope the manifest does not request",
                            emptySet<String>(),
                            decoded.widget.requiredScopes - requested,
                        )
                    }
                    PluginSection.ICON -> assertTrue(
                        "${pack.slug} icon did not parse: ${PluginIconCodec.decode(raw)}",
                        PluginIconCodec.decode(raw) is PluginIconCodec.Result.Parsed,
                    )
                    PluginSection.SETTINGS -> assertTrue(
                        "${pack.slug} unexpectedly ships settings",
                        false,
                    )
                    else -> Unit
                }
            }
        }
    }

    @Test
    fun `the bundled widget representatives are the ones the packs intend`() {
        val minimal = PluginDashboardCodec.decode(read("minimalvitals", "widgets.json"))
            as PluginDashboardCodec.Result.Parsed
        assertEquals("vitals_snapshot", minimal.widget.id)
        assertTrue(minimal.widget.requiredStats.map { it.id }.containsAll(listOf("cpu", "ram", "battery", "temp")))

        val thermal = PluginDashboardCodec.decode(read("thermaldesk", "widgets.json"))
            as PluginDashboardCodec.Result.Parsed
        assertEquals("thermal_trend", thermal.widget.id)
        assertTrue(thermal.widget.requiredStats.map { it.id }.contains("temp"))
    }
}
