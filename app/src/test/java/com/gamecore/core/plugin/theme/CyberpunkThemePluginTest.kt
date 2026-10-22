package com.gamecore.core.plugin.theme

import com.gamecore.core.common.ContrastMath
import com.gamecore.core.plugin.PluginManifestCodec
import com.gamecore.core.plugin.PluginPackageLoader
import com.gamecore.core.plugin.PluginPermission
import com.gamecore.core.plugin.PluginSection
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Validates the authored deliverable `dist/cyberpunk/cyberpunk.gcplugin` through the host's own
 * trust boundary — the real [PluginPackageLoader], [PluginManifestCodec] and [PluginThemeCodec],
 * plus [ContrastMath] on the decoded palette. This is the strongest proof available off-device:
 * the exact bytes the install flow would open are accepted, and the SHA-256 reported is the
 * canonical one the loader pins.
 *
 * JUnit4 idiom (`:app` module): `assertEquals(message, expected, actual)` — the message is FIRST.
 *
 * The artifact is produced by the SDK test `CyberpunkGameCorePackageTest`; run
 * `:gamecore-plugin-sdk:test` before (or alongside) this so the file is on disk.
 */
class CyberpunkThemePluginTest {

    // :app unit tests run with the module dir as cwd, so dist/ is one level up.
    private val artifact = File("../dist/cyberpunk/cyberpunk.gcplugin")

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

    @Test
    fun host_loader_accepts_the_package_and_pins_a_sha256() {
        val plugin = loadedOrFail()
        assertEquals("sha-256 is 64 lowercase hex chars", 64, plugin.sha256.length)
        assertTrue("sha-256 is hex", plugin.sha256.matches(Regex("^[0-9a-f]{64}$")))

        val sidecar = File("../dist/cyberpunk/cyberpunk.gcplugin.sha256")
        if (sidecar.isFile) {
            assertTrue(
                "loader sha matches the sidecar the packager wrote",
                sidecar.readText().startsWith(plugin.sha256),
            )
        }
        println("CYBERPUNK_LOADER_SHA256=${plugin.sha256}")
    }

    @Test
    fun manifest_is_runnable_and_requests_only_change_ui() {
        val m = loadedOrFail().manifest
        assertEquals("id", "com.dreamucxe.gamecore.cyberpunk", m.id)
        assertEquals("name", "Cyberpunk GameCore", m.name)
        assertEquals("version", 2, m.version)
        assertEquals("versionName", "1.1.0", m.versionName)
        assertEquals("api", 1, m.api)
        // The icon section needs the 3.15.0 host (versionCode 37).
        assertEquals("minHostVersion", 37, m.minHostVersion)
        assertEquals("developer", "Dream", m.developer)
        assertEquals("changelog", "Initial release.", m.changelog)

        assertTrue("compatible with this build (api 1 / host 28)", m.compatibility.isRunnable)

        assertEquals("two permissions", 2, m.permissions.size)
        assertEquals("change_ui first", PluginPermission.CHANGE_UI, m.permissions[0].permission)
        assertTrue("CHANGE_UI carries no scopes", m.permissions[0].scopes.isEmpty())
        assertEquals("change_icon second", PluginPermission.CHANGE_ICON, m.permissions[1].permission)
        assertTrue("CHANGE_ICON carries no scopes", m.permissions[1].scopes.isEmpty())
        assertTrue("requests CHANGE_UI", m.requests(PluginPermission.CHANGE_UI))
        assertTrue("requests CHANGE_ICON", m.requests(PluginPermission.CHANGE_ICON))

        assertEquals("declares the theme and the icon", setOf(PluginSection.THEME, PluginSection.ICON), m.declares)
    }

    @Test
    fun theme_decodes_to_the_cyberpunk_palette() {
        val bytes = loadedOrFail().entry("theme.json")
            ?: run { fail("package carries no theme.json"); error("unreachable") }
        val theme = when (val r = PluginThemeCodec.decode(String(bytes))) {
            is PluginThemeCodec.Result.Parsed -> r.theme
            is PluginThemeCodec.Result.Rejected -> {
                fail("theme rejected: ${r.reason} ${r.detail}"); error("unreachable")
            }
        }
        assertEquals("name", "Cyberpunk GameCore", theme.name)
        assertTrue("dark", theme.dark)
        assertEquals("primary cyan (opaque ARGB)", 0xFF00F0FF.toInt(), theme.primary)
        assertEquals("onPrimary ink", 0xFF0A0A12.toInt(), theme.onPrimary)
        assertEquals("background", 0xFF0A0A12.toInt(), theme.background)
        assertEquals("surface", 0xFF12121F.toInt(), theme.surface)
        assertEquals("onSurface text", 0xFFE6F1FF.toInt(), theme.onSurface)
        assertEquals("accent magenta", 0xFFFF2BD6.toInt(), theme.accent)
        assertEquals("corner radius", 2, theme.cornerRadiusDp)
        assertEquals("font", PluginTheme.Font.MONO, theme.font)
        assertEquals("uiScale", 1.0f, theme.uiScale, 1e-6f)
    }

    @Test
    fun body_text_pairs_clear_wcag_aa() {
        val bytes = loadedOrFail().entry("theme.json")!!
        val theme = (PluginThemeCodec.decode(String(bytes)) as PluginThemeCodec.Result.Parsed).theme

        val pairs = listOf(
            Triple("onSurface / surface", theme.onSurface, theme.surface),
            Triple("onSurface / background", theme.onSurface, theme.background),
            Triple("onPrimary / primary", theme.onPrimary, theme.primary),
        )
        for ((label, fg, bg) in pairs) {
            val ratio = ContrastMath.contrastRatio(fg, bg)
            println("CYBERPUNK_CONTRAST %-24s = %.2f:1".format(label, ratio))
            assertTrue(
                "$label is ${"%.2f".format(ratio)}:1, below the ${ContrastMath.BODY_TEXT_MIN}:1 body-text bar",
                ratio >= ContrastMath.BODY_TEXT_MIN,
            )
        }
    }

    @Test
    fun the_unsupported_keys_we_omitted_would_be_rejected() {
        val plugin = loadedOrFail()

        // A `category` key (the brief's `category: ui_theme`) is not in the manifest grammar.
        val badManifest = JSONObject(String(plugin.entry("manifest.json")!!)).put("category", "ui_theme")
        val mr = PluginManifestCodec.decode(badManifest.toString())
        assertTrue("category must be rejected, not ignored", mr is PluginManifestCodec.Result.Rejected)
        assertEquals(
            "category rejects as UNKNOWN_KEY",
            PluginManifestCodec.Reason.UNKNOWN_KEY,
            (mr as PluginManifestCodec.Result.Rejected).reason,
        )

        // A `glow` theme token (the brief's neon glow) is not in the theme grammar.
        val badTheme = JSONObject(String(plugin.entry("theme.json")!!)).put("glow", "#00F0FF")
        val tr = PluginThemeCodec.decode(badTheme.toString())
        assertTrue("glow must be rejected, not ignored", tr is PluginThemeCodec.Result.Rejected)
        assertEquals(
            "glow rejects as UNKNOWN_KEY",
            PluginThemeCodec.Reason.UNKNOWN_KEY,
            (tr as PluginThemeCodec.Result.Rejected).reason,
        )
    }
}
