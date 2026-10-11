package com.gamecore.plugin.sdk

import com.gamecore.plugin.sdk.templates.sensitivityLabPro
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Produces the deliverable `dist/sensitivitylab/sensitivitylab.gcplugin` from [sensitivityLabPro]
 * and proves the authored bytes match the brief and the host's packaging limits, then flattens the
 * packaged section files into the bundled assets the app ships under
 * `app/src/main/assets/plugins/sensitivitylab/`.
 *
 * kotlin.test idiom: `assertEquals(expected, actual, message?)` — the message is LAST.
 */
class SensitivityLabProPackageTest {

    private val def = sensitivityLabPro()

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "app").isDirectory && File(dir, "gamecore-plugin-sdk").isDirectory) return dir
            dir = dir.parentFile
        }
        error("repo root not found from ${File("").absolutePath}")
    }

    /** Collects every `stat` id named anywhere in a pill content tree. */
    private fun statIds(node: JSONObject): Set<String> {
        val out = linkedSetOf<String>()
        fun walk(n: JSONObject) {
            if (n.optString("type") == "stat") {
                n.optJSONObject("props")?.optString("stat")?.takeIf { it.isNotEmpty() }?.let { out += it }
            }
            n.optJSONArray("children")?.let { arr ->
                for (i in 0 until arr.length()) walk(arr.getJSONObject(i))
            }
        }
        walk(node)
        return out
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
        assertEquals("com.dreamucxe.gamecore.sensitivitylab", m.getString("id"), "id")
        assertEquals("Sensitivity Lab Pro", m.getString("name"), "name")
        assertEquals(1, m.getInt("version"), "version")
        assertEquals("1.0.0", m.getString("versionName"), "versionName")
        assertEquals(3, m.getInt("api"), "api auto-bumps to 3 (settings + screens + a motion stat)")
        assertEquals(28, m.getInt("minHostVersion"), "minHostVersion keeps the SDK default")
        assertEquals("Dream", m.getString("developer"), "developer")
        assertEquals("Initial release.", m.getString("changelog"), "changelog")

        val perms = m.getJSONArray("permissions")
        val byId = (0 until perms.length()).associate {
            val p = perms.getJSONObject(it); p.getString("id") to p
        }
        assertEquals(
            setOf("create_overlay", "read_approved_data", "change_ui", "store_data"),
            byId.keys,
            "the four permissions the three sections need",
        )
        assertTrue(!byId.getValue("create_overlay").has("scopes"), "create_overlay has no scopes")
        assertTrue(!byId.getValue("change_ui").has("scopes"), "change_ui has no scopes")
        assertTrue(!byId.getValue("store_data").has("scopes"), "store_data has no scopes")
        val scopes = byId.getValue("read_approved_data").getJSONArray("scopes")
        assertEquals(
            setOf("motion", "device_stats"),
            (0 until scopes.length()).map { scopes.getString(it) }.toSet(),
            "read_approved_data requests exactly the two apertures the pill reads",
        )

        val declares = m.getJSONArray("declares")
        assertEquals(
            listOf("pill", "screens", "settings"),
            (0 until declares.length()).map { declares.getString(it) },
            "declares pill, screens, settings in call order",
        )
    }

    @Test
    fun pill_shows_live_motion_stats() {
        val p = JSONObject(String(def.sectionFiles.getValue("pill.json")))
        assertEquals("gamecore.plugin.pill", p.getString("format"), "pill format")
        assertEquals(1, p.getInt("version"), "pill version")
        assertEquals("sensitivity.monitor", p.getString("id"), "pill id")
        assertEquals("large", p.getString("size"), "pill size")
        assertEquals(85, p.getInt("opacity"), "pill opacity default")
        assertEquals(
            setOf("rotation_rate", "movement", "fps"),
            statIds(p.getJSONObject("content")),
            "the pill shows the two motion readings and the frame rate",
        )
    }

    @Test
    fun screens_are_two_reference_screens() {
        val s = JSONObject(String(def.sectionFiles.getValue("screens/screens.json")))
        assertEquals("gamecore.plugin.screens", s.getString("format"), "screens format")
        assertEquals(1, s.getInt("version"), "screens version")
        val screens = s.getJSONArray("screens")
        assertEquals(
            listOf("presets", "guide"),
            (0 until screens.length()).map { screens.getJSONObject(it).getString("id") },
            "two reference screens, presets then guide",
        )
    }

    @Test
    fun settings_declare_typed_controls() {
        val s = JSONObject(String(def.sectionFiles.getValue("settings.json")))
        val arr = s.getJSONArray("settings")
        val types = (0 until arr.length()).associate {
            val o = arr.getJSONObject(it); o.getString("id") to o.getString("type")
        }
        assertEquals(
            mapOf(
                "preset" to "enum",
                "dpi" to "int",
                "multiplier" to "float",
                "ads_multiplier" to "float",
                "invert_y" to "bool",
            ),
            types,
            "the five typed settings and their types",
        )
    }

    @Test
    fun packages_the_deliverable_and_flattens_it_into_bundled_assets() {
        val outDir = File(repoRoot(), "dist/sensitivitylab").apply { mkdirs() }
        val out = File(outDir, "sensitivitylab.gcplugin")
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
            listOf("manifest.json", "pill.json", "screens/screens.json", "settings.json"),
            ordered,
            "manifest first, then the sections in declared order",
        )

        ZipFile(out).use { zip ->
            zip.entries().asSequence().forEach { e ->
                assertTrue(e.size <= 2 * 1024 * 1024, "entry ${e.name} <= 2MiB")
                val dest = File(outDir, e.name)
                dest.parentFile?.mkdirs()
                dest.writeBytes(zip.getInputStream(e).readBytes())
            }
        }

        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        File(outDir, "sensitivitylab.gcplugin.sha256").writeText("$sha  sensitivitylab.gcplugin\n")
        println("SENSITIVITYLAB_GCPLUGIN_SHA256=$sha")
        println("SENSITIVITYLAB_GCPLUGIN_PATH=${out.absolutePath}")
        println("SENSITIVITYLAB_GCPLUGIN_BYTES=${bytes.size}")
        assertEquals(64, sha.length, "sha-256 hex length")

        // Flatten the packaged section files into the bundled assets dir, named exactly as the
        // host's bundled loader expects (a section whose entry prefix is a directory ships flat as
        // "<section>.json"), so the same host codecs load the bundled copy.
        val assetsDir = File(repoRoot(), "app/src/main/assets/plugins/sensitivitylab").apply { mkdirs() }
        File(assetsDir, "manifest.json").writeBytes(def.manifestJson)
        def.sectionFiles.forEach { (path, bytes) ->
            val flat = when {
                path == "screens/screens.json" -> "screens.json"
                path == "rules/rules.json" -> "rules.json"
                path.startsWith("widgets/") -> null   // no widgets here
                else -> path                            // pill.json / settings.json
            } ?: return@forEach
            File(assetsDir, flat).writeBytes(bytes)
        }
    }


}
