package com.gamecore.plugin.sdk

import com.gamecore.plugin.sdk.templates.liquidGlassGameCore
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Produces the deliverable `dist/liquidglass/liquidglass.gcplugin` from [liquidGlassGameCore] and
 * proves the authored bytes match the brief and the host's packaging limits.
 *
 * kotlin.test idiom: `assertEquals(expected, actual, message?)` — the message is LAST.
 */
class LiquidGlassGameCorePackageTest {

    private val def = liquidGlassGameCore()

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
        assertEquals("com.dreamucxe.gamecore.liquidglass", m.getString("id"), "id")
        assertEquals("Liquid Glass GameCore", m.getString("name"), "name")
        assertEquals(2, m.getInt("version"), "version")
        assertEquals("1.1.0", m.getString("versionName"), "versionName")
        assertEquals(1, m.getInt("api"), "api")
        assertEquals(Sdk.HOST_VERSION_CODE_ICON, m.getInt("minHostVersion"), "minHostVersion is the icon host (37)")
        assertEquals("Dream", m.getString("developer"), "developer")
        assertEquals("Initial release.", m.getString("changelog"), "changelog")

        // Legal guard: the id and name must not carry Apple's marks (only "inspired by iOS").
        for (field in listOf("id", "name")) {
            val v = m.getString(field).lowercase()
            for (mark in listOf("ios", "apple", "iphone")) {
                assertTrue(mark !in v, "$field must not contain \"$mark\" (is \"${m.getString(field)}\")")
            }
        }

        // Two permissions: the theme repaint, then the launcher icon.
        val perms = m.getJSONArray("permissions")
        assertEquals(
            listOf("change_ui", "change_icon"),
            (0 until perms.length()).map { perms.getJSONObject(it).getString("id") },
            "change_ui then change_icon, in request order",
        )
        for (i in 0 until perms.length()) {
            assertTrue(!perms.getJSONObject(i).has("scopes"), "neither permission carries scopes")
        }

        val declares = m.getJSONArray("declares")
        assertEquals(
            listOf("theme", "icon"),
            (0 until declares.length()).map { declares.getString(it) },
            "declares theme, then the icon section",
        )
    }

    @Test
    fun theme_matches_the_palette() {
        val t = JSONObject(String(def.sectionFiles.getValue("theme.json")))
        assertEquals(
            setOf(
                "name", "dark", "primary", "onPrimary", "background", "surface",
                "onSurface", "accent", "cornerRadiusDp", "font", "uiScale",
            ),
            t.keys().asSequence().toSet(),
            "theme emits exactly the host's known theme keys",
        )
        assertEquals("Liquid Glass GameCore", t.getString("name"), "theme name")
        assertTrue(t.getBoolean("dark"), "dark theme (dark=true)")
        assertEquals("#0A6FE0", t.getString("primary"), "primary system blue (AA-darkened)")
        assertEquals("#FFFFFF", t.getString("onPrimary"), "onPrimary white")
        assertEquals("#000000", t.getString("background"), "background true black")
        assertEquals("#1C1C1E", t.getString("surface"), "surface grouped-card near-black")
        assertEquals("#FFFFFF", t.getString("onSurface"), "onSurface white ink")
        assertEquals("#5E5CE6", t.getString("accent"), "accent indigo")
        assertEquals(20, t.getInt("cornerRadiusDp"), "corner radius")
        assertEquals("rounded", t.getString("font"), "font")
        assertEquals(1.0, t.getDouble("uiScale"), 1e-9, "uiScale")
    }

    @Test
    fun packages_the_deliverable_within_host_limits() {
        val outDir = File(repoRoot(), "dist/liquidglass").apply { mkdirs() }
        val out = File(outDir, "liquidglass.gcplugin")
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
        File(outDir, "liquidglass.gcplugin.sha256").writeText("$sha  liquidglass.gcplugin\n")
        println("LIQUIDGLASS_GCPLUGIN_SHA256=$sha")
        println("LIQUIDGLASS_GCPLUGIN_PATH=${out.absolutePath}")
        println("LIQUIDGLASS_GCPLUGIN_BYTES=${bytes.size}")
        assertEquals(64, sha.length, "sha-256 hex length")
    }
}
