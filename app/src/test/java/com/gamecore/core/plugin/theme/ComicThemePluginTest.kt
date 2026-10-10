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
 * Validates the authored deliverable `dist/comic/comic.gcplugin` through the host's own
 * trust boundary — the real [PluginPackageLoader], [PluginManifestCodec] and [PluginThemeCodec],
 * plus [ContrastMath] on the decoded palette. This is the strongest proof available off-device:
 * the exact bytes the install flow would open are accepted, and the SHA-256 reported is the
 * canonical one the loader pins.
 *
 * JUnit4 idiom (`:app` module): `assertEquals(message, expected, actual)` — the message is FIRST.
 *
 * The artifact is produced by the SDK test `ComicGameCorePackageTest`; run
 * `:gamecore-plugin-sdk:test` before (or alongside) this so the file is on disk.
 */
class ComicThemePluginTest {

    // :app unit tests run with the module dir as cwd, so dist/ is one level up.
    private val artifact = File("../dist/comic/comic.gcplugin")

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

        val sidecar = File("../dist/comic/comic.gcplugin.sha256")
        if (sidecar.isFile) {
            assertTrue(
                "loader sha matches the sidecar the packager wrote",
                sidecar.readText().startsWith(plugin.sha256),
            )
        }
        println("COMIC_LOADER_SHA256=${plugin.sha256}")
    }

    @Test
    fun manifest_is_runnable_and_requests_only_change_ui() {
        val m = loadedOrFail().manifest
        assertEquals("id", "com.dreamucxe.gamecore.comic", m.id)
        assertEquals("name", "Comic GameCore", m.name)
        assertEquals("version", 1, m.version)
        assertEquals("versionName", "1.0.0", m.versionName)
        assertEquals("api", 1, m.api)
        assertEquals("minHostVersion", 28, m.minHostVersion)
        assertEquals("developer", "Dream", m.developer)
        assertEquals("changelog", "Initial release.", m.changelog)

        assertTrue("compatible with this build (api 1 / host 28)", m.compatibility.isRunnable)

        assertEquals("exactly one permission", 1, m.permissions.size)
        assertEquals("the one permission is CHANGE_UI", PluginPermission.CHANGE_UI, m.permissions[0].permission)
        assertTrue("CHANGE_UI carries no scopes", m.permissions[0].scopes.isEmpty())
        assertTrue("requests CHANGE_UI", m.requests(PluginPermission.CHANGE_UI))

        assertEquals("declares exactly one section", setOf(PluginSection.THEME), m.declares)
    }

    @Test
    fun theme_decodes_to_the_comic_palette() {
        val bytes = loadedOrFail().entry("theme.json")
            ?: run { fail("package carries no theme.json"); error("unreachable") }
        val theme = when (val r = PluginThemeCodec.decode(String(bytes))) {
            is PluginThemeCodec.Result.Parsed -> r.theme
            is PluginThemeCodec.Result.Rejected -> {
                fail("theme rejected: ${r.reason} ${r.detail}"); error("unreachable")
            }
        }
        assertEquals("name", "Comic GameCore", theme.name)
        assertTrue("light theme (dark=false)", !theme.dark)
        assertEquals("primary comic red (opaque ARGB)", 0xFFD00000.toInt(), theme.primary)
        assertEquals("onPrimary white", 0xFFFFFFFF.toInt(), theme.onPrimary)
        assertEquals("background paper", 0xFFFFF8E7.toInt(), theme.background)
        assertEquals("surface white", 0xFFFFFFFF.toInt(), theme.surface)
        assertEquals("onSurface ink", 0xFF111111.toInt(), theme.onSurface)
        assertEquals("accent comic blue", 0xFF1D4ED8.toInt(), theme.accent)
        assertEquals("corner radius", 8, theme.cornerRadiusDp)
        assertEquals("font", PluginTheme.Font.ROUNDED, theme.font)
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
            println("COMIC_CONTRAST %-24s = %.2f:1".format(label, ratio))
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

        // A `halftone` theme token (the brief's halftone dot texture) is not in the theme grammar.
        val badTheme = JSONObject(String(plugin.entry("theme.json")!!)).put("halftone", "#1D4ED8")
        val tr = PluginThemeCodec.decode(badTheme.toString())
        assertTrue("halftone must be rejected, not ignored", tr is PluginThemeCodec.Result.Rejected)
        assertEquals(
            "halftone rejects as UNKNOWN_KEY",
            PluginThemeCodec.Reason.UNKNOWN_KEY,
            (tr as PluginThemeCodec.Result.Rejected).reason,
        )
    }
}
