package com.gamecore.core.plugin.theme

import com.gamecore.core.common.ContrastMath
import com.gamecore.core.plugin.PluginManifestCodec
import com.gamecore.core.plugin.PluginPackageLoader
import com.gamecore.core.plugin.PluginPermission
import com.gamecore.core.plugin.PluginSection
import com.gamecore.core.plugin.icon.AppIconSlot
import com.gamecore.core.plugin.icon.PluginIconCodec
import com.gamecore.ui.theme.resolvedGlitch
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Validates the authored deliverable `dist/trueglitch/trueglitch.gcplugin` through the host's own
 * trust boundary — the real [PluginPackageLoader], [PluginManifestCodec] and [PluginThemeCodec],
 * plus [ContrastMath] on the decoded palette.
 *
 * JUnit4 idiom (`:app` module): `assertEquals(message, expected, actual)` — the message is FIRST.
 *
 * The artifact is produced by the SDK test `TrueGlitchGameCorePackageTest`; run
 * `:gamecore-plugin-sdk:test` before (or alongside) this so the file is on disk.
 */
class TrueGlitchThemePluginTest {

    // :app unit tests run with the module dir as cwd, so dist/ is one level up.
    private val artifact = File("../dist/trueglitch/trueglitch.gcplugin")

    private fun loadedOrFail(): com.gamecore.core.plugin.LoadedPlugin {
        assertTrue(
            "deliverable missing at ${artifact.absolutePath} — run :gamecore-plugin-sdk:test first",
            artifact.isFile,
        )
        return when (val r = PluginPackageLoader.load(artifact.readBytes())) {
            is PluginPackageLoader.Result.Loaded -> r.plugin
            is PluginPackageLoader.Result.Rejected -> {
                fail("host loader rejected the deliverable: ${r.reason} ${r.detail}")
                error("unreachable")
            }
        }
    }

    private fun themeOrFail(): PluginTheme {
        val bytes = loadedOrFail().entry("theme.json")
            ?: run { fail("package carries no theme.json"); error("unreachable") }
        return when (val r = PluginThemeCodec.decode(String(bytes))) {
            is PluginThemeCodec.Result.Parsed -> r.theme
            is PluginThemeCodec.Result.Rejected -> {
                fail("theme rejected: ${r.reason} ${r.detail}"); error("unreachable")
            }
        }
    }

    @Test
    fun host_loader_accepts_the_package_and_pins_a_sha256() {
        val plugin = loadedOrFail()
        assertEquals("sha-256 is 64 lowercase hex chars", 64, plugin.sha256.length)
        assertTrue("sha-256 is hex", plugin.sha256.matches(Regex("^[0-9a-f]{64}$")))

        val sidecar = File("../dist/trueglitch/trueglitch.gcplugin.sha256")
        if (sidecar.isFile) {
            assertTrue(
                "loader sha matches the sidecar the packager wrote",
                sidecar.readText().startsWith(plugin.sha256),
            )
        }
        println("TRUEGLITCH_LOADER_SHA256=${plugin.sha256}")
    }

    @Test
    fun manifest_is_runnable_and_requests_only_what_the_theme_and_icon_need() {
        val m = loadedOrFail().manifest
        assertEquals("id", "com.dreamucxe.gamecore.trueglitch", m.id)
        assertEquals("name", "True Glitch", m.name)
        assertEquals("api", 3, m.api)
        assertEquals("minHostVersion", 37, m.minHostVersion)
        assertEquals("compatible with this build (api 3 / host 37)", true, m.compatibility.isRunnable)

        assertEquals("exactly two permissions", 2, m.permissions.size)
        assertEquals(
            "repaint and icon, and nothing else",
            setOf(PluginPermission.CHANGE_UI, PluginPermission.CHANGE_ICON),
            m.permissions.map { it.permission }.toSet(),
        )
        assertEquals("declares theme and icon", setOf(PluginSection.THEME, PluginSection.ICON), m.declares)
    }

    @Test
    fun icon_section_decodes_to_the_glitched_slot() {
        // Through the host's real codec: the package's icon.json is only worth anything if the
        // build that ships AppIconSlot reads it as the same slot.
        val raw = loadedOrFail().entry("icon.json")
            ?: run { fail("package carries no icon.json"); error("unreachable") }
        val spec = when (val r = PluginIconCodec.decode(String(raw))) {
            is PluginIconCodec.Result.Parsed -> r.spec
            is PluginIconCodec.Result.Rejected -> {
                fail("icon rejected: ${r.reason} ${r.detail}"); error("unreachable")
            }
        }
        assertEquals("slot", AppIconSlot.GLITCH, spec.slot)
        assertEquals("no artwork in this package", false, spec.hasArtwork)
        assertNull("no label override", spec.label)
    }

    @Test
    fun theme_decodes_to_the_true_glitch_palette() {
        val theme = themeOrFail()
        assertEquals("schema", PluginTheme.SCHEMA_V3, theme.schema)
        assertEquals("name", "True Glitch", theme.name)
        assertTrue("dark", theme.dark)
        assertEquals("primary cyan", 0xFF00F0FF.toInt(), theme.primary)
        assertEquals("background flattened opaque", 0xFF050505.toInt(), theme.background)
        assertEquals("surface", 0xFF0A0A0C.toInt(), theme.surface)
        assertEquals("accent red", 0xFFFF0055.toInt(), theme.accent)
        assertEquals("font", PluginTheme.Font.MONO, theme.font)
        assertEquals("corner radius", 2, theme.cornerRadiusDp)
        assertNotNull("carries a glitch block", theme.glitch)
    }

    @Test
    fun glitch_block_decodes_to_the_briefed_motion() {
        // The bridge is the renderer's input, so assert through it rather than through the raw model:
        // what matters is that a real package produces a real, clamped request.
        val glitch = themeOrFail().resolvedGlitch()
        assertNotNull("resolved glitch is non-null", glitch)
        val g = glitch!!
        assertEquals("intensity", 0.85f, g.intensity, 1e-6f)
        assertEquals("slices", 3, g.slices)
        assertEquals("slice offset", 24, g.sliceOffsetDp)
        assertEquals("ghost offset", 6, g.ghostOffsetDp)
        assertEquals("burst floor", 150, g.burstMinMs)
        assertEquals("burst ceiling", 800, g.burstMaxMs)
        assertTrue("fracture lines", g.fractureLines)
        assertTrue("noise", g.noise)
        assertTrue("micro jitter", g.microJitter)
        assertTrue("hardware reactive", g.hardwareReactive)
    }

    @Test
    fun body_text_pairs_clear_wcag_aa() {
        val theme = themeOrFail()
        val pairs = listOf(
            Triple("onSurface / surface", theme.onSurface, theme.surface),
            Triple("onSurface / background", theme.onSurface, theme.background),
            Triple("onPrimary / primary", theme.onPrimary, theme.primary),
        )
        for ((label, fg, bg) in pairs) {
            val ratio = ContrastMath.contrastRatio(fg, bg)
            println("TRUEGLITCH_CONTRAST %-24s = %.2f:1".format(label, ratio))
            assertTrue(
                "$label is ${"%.2f".format(ratio)}:1, below the ${ContrastMath.BODY_TEXT_MIN}:1 body-text bar",
                ratio >= ContrastMath.BODY_TEXT_MIN,
            )
        }
    }

    @Test
    fun a_theme_that_did_not_declare_schema_three_cannot_use_effects() {
        // The entire point of the schema bump: an older-schema theme that reaches for `effects` is
        // rejected, not rendered as a plain palette with the motion silently dropped.
        val raw = JSONObject(String(loadedOrFail().entry("theme.json")!!))
        raw.put("schema", 2)
        val r = PluginThemeCodec.decode(raw.toString())
        assertTrue("schema-2 theme with effects must reject", r is PluginThemeCodec.Result.Rejected)
        assertEquals(
            "rejects as UNKNOWN_KEY",
            PluginThemeCodec.Reason.UNKNOWN_KEY,
            (r as PluginThemeCodec.Result.Rejected).reason,
        )
        assertNull("no theme produced", r.themeOrNull())
    }

    private fun PluginThemeCodec.Result.themeOrNull(): PluginTheme? =
        (this as? PluginThemeCodec.Result.Parsed)?.theme
}
