package com.gamecore.plugin.sdk

import com.gamecore.plugin.sdk.templates.redMagicGameCore
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Produces the deliverable `dist/redmagic/redmagic.gcplugin` from [redMagicGameCore] and proves the
 * authored bytes match the brief and the host's packaging limits.
 *
 * kotlin.test idiom: `assertEquals(expected, actual, message?)` — the message is LAST.
 */
class RedMagicGameCorePackageTest {

    private val def = redMagicGameCore()

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
        assertEquals("com.dreamucxe.gamecore.redmagic", m.getString("id"), "id")
        assertEquals("RedMagic GameCore", m.getString("name"), "name")
        assertEquals(1, m.getInt("version"), "version")
        assertEquals("1.0.0", m.getString("versionName"), "versionName")
        assertEquals(1, m.getInt("api"), "api — a pure v1 theme bumps nothing")
        assertEquals(28, m.getInt("minHostVersion"), "minHostVersion")
        assertEquals("Dream", m.getString("developer"), "developer")
        assertEquals("Initial release.", m.getString("changelog"), "changelog")

        val perms = m.getJSONArray("permissions")
        assertEquals(1, perms.length(), "exactly one permission")
        val p = perms.getJSONObject(0)
        assertEquals("change_ui", p.getString("id"), "the one permission is change_ui")
        assertTrue(!p.has("scopes"), "change_ui carries no scopes")

        val declares = m.getJSONArray("declares")
        assertEquals(1, declares.length(), "declares exactly one section")
        assertEquals("theme", declares.getString(0), "declares theme")
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
            "theme emits exactly the host's known theme keys (a pure v1 theme, no schema-2 extras)",
        )
        assertEquals("RedMagic GameCore", t.getString("name"), "theme name")
        assertTrue(t.getBoolean("dark"), "dark")
        assertEquals("#E8142D", t.getString("primary"), "primary red")
        assertEquals("#FFFFFF", t.getString("onPrimary"), "onPrimary white ink")
        assertEquals("#08070A", t.getString("background"), "background near-black")
        assertEquals("#161620", t.getString("surface"), "surface")
        assertEquals("#ECEDF2", t.getString("onSurface"), "onSurface text")
        assertEquals("#E8142D", t.getString("accent"), "accent red")
        assertEquals(3, t.getInt("cornerRadiusDp"), "corner radius — closest the format gets to a cut corner")
        assertEquals("mono", t.getString("font"), "font")
        assertEquals(1.0, t.getDouble("uiScale"), 1e-9, "uiScale")
    }

    @Test
    fun packages_the_deliverable_within_host_limits() {
        val outDir = File(repoRoot(), "dist/redmagic").apply { mkdirs() }
        val out = File(outDir, "redmagic.gcplugin")
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
        assertEquals(listOf("manifest.json", "theme.json"), ordered, "manifest first, then theme")

        ZipFile(out).use { zip ->
            zip.entries().asSequence().forEach { e ->
                assertTrue(e.size <= 2 * 1024 * 1024, "entry ${e.name} <= 2MiB")
                File(outDir, e.name).writeBytes(zip.getInputStream(e).readBytes())
            }
        }

        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        File(outDir, "redmagic.gcplugin.sha256").writeText("$sha  redmagic.gcplugin\n")
        println("REDMAGIC_GCPLUGIN_SHA256=$sha")
        println("REDMAGIC_GCPLUGIN_PATH=${out.absolutePath}")
        println("REDMAGIC_GCPLUGIN_BYTES=${bytes.size}")
        assertEquals(64, sha.length, "sha-256 hex length")
    }
}
