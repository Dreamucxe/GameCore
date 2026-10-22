package com.gamecore.plugin.sdk

import com.gamecore.plugin.sdk.templates.cyberpunkGameCore
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Produces the deliverable `dist/cyberpunk/cyberpunk.gcplugin` from [cyberpunkGameCore] and
 * proves the authored bytes match the brief and the host's packaging limits.
 *
 * kotlin.test idiom: `assertEquals(expected, actual, message?)` — the message is LAST.
 */
class CyberpunkGameCorePackageTest {

    private val def = cyberpunkGameCore()

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
        assertEquals("com.dreamucxe.gamecore.cyberpunk", m.getString("id"), "id")
        assertEquals("Cyberpunk GameCore", m.getString("name"), "name")
        assertEquals(2, m.getInt("version"), "version")
        assertEquals("1.1.0", m.getString("versionName"), "versionName")
        assertEquals(1, m.getInt("api"), "api")
        assertEquals(37, m.getInt("minHostVersion"), "the icon section needs the 3.15.0 host")
        assertEquals("Dream", m.getString("developer"), "developer")
        assertEquals("Initial release.", m.getString("changelog"), "changelog")

        val perms = m.getJSONArray("permissions")
        assertEquals(2, perms.length(), "exactly two permissions")
        val p = perms.getJSONObject(0)
        assertEquals("change_ui", p.getString("id"), "the first permission is change_ui")
        assertTrue(!p.has("scopes"), "change_ui carries no scopes")
        val iconPerm = perms.getJSONObject(1)
        assertEquals("change_icon", iconPerm.getString("id"), "change_icon is requested last")
        assertTrue(!iconPerm.has("scopes"), "change_icon carries no scopes")

        val declares = m.getJSONArray("declares")
        assertEquals(2, declares.length(), "declares two sections")
        assertEquals("theme", declares.getString(0), "declares theme first")
        assertEquals("icon", declares.getString(1), "declares icon last")
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
        assertEquals("Cyberpunk GameCore", t.getString("name"), "theme name")
        assertTrue(t.getBoolean("dark"), "dark")
        assertEquals("#00F0FF", t.getString("primary"), "primary cyan")
        assertEquals("#0A0A12", t.getString("onPrimary"), "onPrimary ink")
        assertEquals("#0A0A12", t.getString("background"), "background")
        assertEquals("#12121F", t.getString("surface"), "surface")
        assertEquals("#E6F1FF", t.getString("onSurface"), "onSurface text")
        assertEquals("#FF2BD6", t.getString("accent"), "accent magenta")
        assertEquals(2, t.getInt("cornerRadiusDp"), "corner radius")
        assertEquals("mono", t.getString("font"), "font")
        assertEquals(1.0, t.getDouble("uiScale"), 1e-9, "uiScale")
    }

    @Test
    fun packages_the_deliverable_within_host_limits() {
        val outDir = File(repoRoot(), "dist/cyberpunk").apply { mkdirs() }
        val out = File(outDir, "cyberpunk.gcplugin")
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
        assertEquals(listOf("manifest.json", "theme.json", "icon.json"), ordered, "manifest first, then theme, then icon")

        ZipFile(out).use { zip ->
            zip.entries().asSequence().forEach { e ->
                assertTrue(e.size <= 2 * 1024 * 1024, "entry ${e.name} <= 2MiB")
                File(outDir, e.name).writeBytes(zip.getInputStream(e).readBytes())
            }
        }

        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        File(outDir, "cyberpunk.gcplugin.sha256").writeText("$sha  cyberpunk.gcplugin\n")
        println("CYBERPUNK_GCPLUGIN_SHA256=$sha")
        println("CYBERPUNK_GCPLUGIN_PATH=${out.absolutePath}")
        println("CYBERPUNK_GCPLUGIN_BYTES=${bytes.size}")
        assertEquals(64, sha.length, "sha-256 hex length")
    }
}
