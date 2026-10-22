package com.gamecore.plugin.sdk

import com.gamecore.plugin.sdk.templates.trueGlitchGameCore
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Produces the deliverable `dist/trueglitch/trueglitch.gcplugin` from [trueGlitchGameCore] and
 * proves the authored bytes match the brief and the host's packaging limits.
 *
 * kotlin.test idiom: `assertEquals(expected, actual, message?)` — the message is LAST.
 */
class TrueGlitchGameCorePackageTest {

    private val def = trueGlitchGameCore()

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "app").isDirectory && File(dir, "gamecore-plugin-sdk").isDirectory) return dir
            dir = dir.parentFile
        }
        error("repo root not found from ${File("").absolutePath}")
    }

    @Test
    fun manifest_matches_the_brief() {
        val m = JSONObject(String(def.manifestJson))
        assertEquals(
            setOf(
                "format", "id", "name", "version", "versionName", "api", "minHostVersion",
                "developer", "description", "changelog", "permissions", "declares",
            ),
            m.keys().asSequence().toSet(),
            "manifest emits exactly the host's known keys",
        )
        assertEquals("gamecore.plugin", m.getString("format"), "format")
        assertEquals("com.dreamucxe.gamecore.trueglitch", m.getString("id"), "id")
        assertEquals("True Glitch", m.getString("name"), "name")
        assertEquals(2, m.getInt("version"), "version")
        assertEquals("1.1.0", m.getString("versionName"), "versionName")
        assertEquals(3, m.getInt("api"), "api is raised to the theme schema that asked for motion")
        assertEquals(37, m.getInt("minHostVersion"), "effects needs 36 and the icon section needs 37")
        assertEquals("Dream", m.getString("developer"), "developer")
        assertEquals("Initial release.", m.getString("changelog"), "changelog")

        val perms = m.getJSONArray("permissions")
        assertEquals(2, perms.length(), "exactly two permissions")
        assertEquals(
            listOf("change_ui", "change_icon"),
            (0 until perms.length()).map { perms.getJSONObject(it).getString("id") },
            "repaints the app and asks for the launcher icon",
        )

        val declares = m.getJSONArray("declares")
        assertEquals(
            listOf("theme", "icon"),
            (0 until declares.length()).map { declares.getString(it) },
            "declares theme and icon",
        )
    }

    @Test
    fun icon_section_asks_for_the_glitched_slot() {
        val icon = JSONObject(String(def.sectionFiles.getValue("icon.json")))
        assertEquals(setOf("slot"), icon.keys().asSequence().toSet(), "a slot and nothing else")
        assertEquals("glitch", icon.getString("slot"), "the glitched icon GameCore already ships")
    }

    @Test
    fun theme_carries_the_schema_three_surface() {
        val t = JSONObject(String(def.sectionFiles.getValue("theme.json")))
        assertEquals(
            setOf(
                "name", "dark", "primary", "onPrimary", "background", "surface",
                "onSurface", "accent", "cornerRadiusDp", "font", "uiScale",
                "schema", "roles", "effects",
            ),
            t.keys().asSequence().toSet(),
            "theme emits exactly the v3 keys this theme used",
        )
        assertEquals(3, t.getInt("schema"), "schema 3")
        assertEquals("True Glitch", t.getString("name"), "theme name")
        assertTrue(t.getBoolean("dark"), "dark")
        assertEquals("#00F0FF", t.getString("primary"), "primary cyan")
        assertEquals("#050505", t.getString("background"), "background, flattened opaque")
        assertEquals("#0A0A0C", t.getString("surface"), "surface")
        assertEquals("#FF0055", t.getString("accent"), "accent red")
        assertEquals("mono", t.getString("font"), "font")
    }

    @Test
    fun glitch_block_matches_the_brief() {
        val g = JSONObject(String(def.sectionFiles.getValue("theme.json")))
            .getJSONObject("effects")
            .getJSONObject("glitch")
        assertEquals(
            setOf(
                "intensity", "slices", "sliceOffsetDp", "ghostOffsetDp", "fractureLines",
                "noise", "microJitter", "hardwareReactive", "burstMinMs", "burstMaxMs",
            ),
            g.keys().asSequence().toSet(),
            "every glitch key the host parses, and no others",
        )
        assertEquals(0.85, g.getDouble("intensity"), 1e-9, "intensity")
        assertEquals(3, g.getInt("slices"), "three bands, per the brief")
        assertEquals(24, g.getInt("sliceOffsetDp"), "the brief's 24 px maximum tear, in dp")
        assertEquals(6, g.getInt("ghostOffsetDp"), "ghost offset")
        assertEquals(150, g.getInt("burstMinMs"), "the brief's 150 ms floor")
        assertEquals(800, g.getInt("burstMaxMs"), "the brief's 800 ms ceiling")
        assertTrue(g.getBoolean("fractureLines"), "fracture lines")
        assertTrue(g.getBoolean("noise"), "block noise")
        assertTrue(g.getBoolean("microJitter"), "micro-jitter")
        assertTrue(g.getBoolean("hardwareReactive"), "hardware scaling")
    }

    @Test
    fun packages_the_deliverable_within_host_limits() {
        val outDir = File(repoRoot(), "dist/trueglitch").apply { mkdirs() }
        val out = File(outDir, "trueglitch.gcplugin")
        PluginPackager.writeTo(out, def)
        assertTrue(out.isFile, "artifact written to ${out.absolutePath}")

        val bytes = out.readBytes()
        assertTrue(bytes.size <= 5 * 1024 * 1024, "package <= 5MiB (is ${bytes.size} bytes)")

        val ordered = ArrayList<String>()
        ZipInputStream(out.inputStream()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                ordered += e.name
                zin.closeEntry()
            }
        }
        assertEquals(
            listOf("manifest.json", "theme.json", "icon.json"),
            ordered,
            "manifest first, then theme, then the icon section",
        )

        ZipFile(out).use { zip ->
            zip.entries().asSequence().forEach { e ->
                assertTrue(e.size <= 2 * 1024 * 1024, "entry ${e.name} <= 2MiB")
                File(outDir, e.name).writeBytes(zip.getInputStream(e).readBytes())
            }
        }

        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        File(outDir, "trueglitch.gcplugin.sha256").writeText("$sha  trueglitch.gcplugin\n")
        println("TRUEGLITCH_GCPLUGIN_SHA256=$sha")
        println("TRUEGLITCH_GCPLUGIN_PATH=${out.absolutePath}")
        println("TRUEGLITCH_GCPLUGIN_BYTES=${bytes.size}")
        assertEquals(64, sha.length, "sha-256 hex length")
    }
}
