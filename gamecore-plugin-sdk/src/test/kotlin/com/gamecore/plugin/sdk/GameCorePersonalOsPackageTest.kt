package com.gamecore.plugin.sdk

import com.gamecore.plugin.sdk.templates.gameCorePersonalOs
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Produces the deliverable `dist/personalos/personalos.gcplugin` from [gameCorePersonalOs] and
 * proves the authored bytes match the brief and the host's packaging limits, then flattens the
 * representative subset into the bundled `app/src/main/assets/plugins/personalos` directory.
 *
 * kotlin.test idiom: `assertEquals(expected, actual, message?)` — the message is LAST.
 */
class GameCorePersonalOsPackageTest {

    private val def = gameCorePersonalOs()

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
        assertEquals("com.dreamucxe.gamecore.personalos", m.getString("id"), "id")
        assertEquals("GameCore Personal OS", m.getString("name"), "name")
        assertEquals(1, m.getInt("version"), "version")
        assertEquals("1.0.0", m.getString("versionName"), "versionName")
        assertEquals(3, m.getInt("api"), "api is V3 — screens/widgets/settings raise it")
        assertEquals(28, m.getInt("minHostVersion"), "minHostVersion")
        assertEquals("Dream", m.getString("developer"), "developer")
        assertEquals("Initial release.", m.getString("changelog"), "changelog")

        // Four permissions, keyed by id; READ_APPROVED_DATA carries exactly {device_stats, thermals}.
        val perms = m.getJSONArray("permissions")
        val byId = (0 until perms.length()).associate { i ->
            val p = perms.getJSONObject(i)
            p.getString("id") to p
        }
        assertEquals(
            setOf("change_ui", "add_dashboard_widgets", "store_data", "read_approved_data"),
            byId.keys,
            "the four requested permissions",
        )
        assertTrue(!byId.getValue("change_ui").has("scopes"), "change_ui carries no scopes")
        assertTrue(!byId.getValue("store_data").has("scopes"), "store_data carries no scopes")
        assertTrue(
            !byId.getValue("add_dashboard_widgets").has("scopes"),
            "add_dashboard_widgets carries no scopes",
        )
        val scopes = byId.getValue("read_approved_data").getJSONArray("scopes")
        assertEquals(
            setOf("device_stats", "thermals"),
            (0 until scopes.length()).map { scopes.getString(it) }.toSet(),
            "read_approved_data scopes are the union of the live stats' scopes",
        )

        val declares = m.getJSONArray("declares")
        assertEquals(
            setOf("theme", "screens", "widgets", "settings"),
            (0 until declares.length()).map { declares.getString(it) }.toSet(),
            "declares the four authored sections",
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
            "theme emits exactly the host's known v1 theme keys",
        )
        assertEquals("GameCore Personal OS", t.getString("name"), "theme name")
        assertTrue(t.getBoolean("dark"), "dark theme")
        assertEquals("#6AA6FF", t.getString("primary"), "primary soft blue")
        assertEquals("#0B1324", t.getString("onPrimary"), "onPrimary navy ink")
        assertEquals("#0E1116", t.getString("background"), "background slate")
        assertEquals("#171B22", t.getString("surface"), "surface panel")
        assertEquals("#E8ECF2", t.getString("onSurface"), "onSurface body text")
        assertEquals("#8B7CFF", t.getString("accent"), "accent violet")
        assertEquals(20, t.getInt("cornerRadiusDp"), "corner radius")
        assertEquals("rounded", t.getString("font"), "font")
        assertEquals(1.0, t.getDouble("uiScale"), 1e-9, "uiScale")
    }

    @Test
    fun screens_are_a_well_formed_section() {
        val s = JSONObject(String(def.sectionFiles.getValue("screens/screens.json")))
        assertEquals("gamecore.plugin.screens", s.getString("format"), "screens format tag")
        assertEquals(1, s.getInt("version"), "screens version")
        val screens = s.getJSONArray("screens")
        assertEquals(2, screens.length(), "two home screens")
        val ids = (0 until screens.length()).map { screens.getJSONObject(it).getString("id") }
        assertEquals(listOf("home", "system"), ids, "screen ids, in author order")
        screens.getJSONObject(0).let {
            assertEquals("Home", it.getString("title"), "home title")
            assertTrue(it.has("content"), "a screen carries one content root")
        }
    }

    @Test
    fun settings_are_a_well_formed_v1_section() {
        val s = JSONObject(String(def.sectionFiles.getValue("settings.json")))
        assertTrue(!s.has("schema"), "a v1 settings file omits the schema key")
        val list = s.getJSONArray("settings")
        assertEquals(6, list.length(), "six typed settings")
        val byId = (0 until list.length()).associate { i ->
            val o = list.getJSONObject(i)
            o.getString("id") to o.getString("type")
        }
        assertEquals(
            mapOf(
                "home_layout" to "enum",
                "show_vitals" to "bool",
                "show_thermals" to "bool",
                "widget_columns" to "int",
                "temperature_units" to "enum",
                "greeting" to "string",
            ),
            byId,
            "each setting id maps to its authored type",
        )
    }

    @Test
    fun every_widget_file_is_a_valid_envelope() {
        val widgetFiles = def.sectionFiles.filterKeys { it.startsWith("widgets/") }
        assertEquals(
            setOf(
                "widgets/vitals.json", "widgets/thermals.json",
                "widgets/welcome.json", "widgets/trend.json",
            ),
            widgetFiles.keys,
            "one file per authored widget, under widgets/<id>.json",
        )
        widgetFiles.forEach { (path, bytes) ->
            val w = JSONObject(String(bytes))
            assertEquals("gamecore.plugin.widget", w.getString("format"), "$path format tag")
            assertEquals(1, w.getInt("version"), "$path version")
            assertEquals(path, "widgets/${w.getString("id")}.json", "$path id matches its file name")
            assertTrue(w.getString("size") in setOf("small", "medium", "large", "full"), "$path size")
            assertTrue(w.has("content"), "$path carries one content root")
        }
        // The representative widget flattened into the bundled widgets.json spans the full row.
        val rep = JSONObject(String(def.sectionFiles.getValue("widgets/vitals.json")))
        assertEquals("vitals", rep.getString("id"), "representative widget id")
        assertEquals("full", rep.getString("size"), "representative widget is full-width")
    }

    @Test
    fun packages_the_deliverable_within_host_limits() {
        val outDir = File(repoRoot(), "dist/personalos").apply { mkdirs() }
        val out = File(outDir, "personalos.gcplugin")
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
            listOf(
                "manifest.json",
                "theme.json",
                "screens/screens.json",
                "widgets/vitals.json",
                "widgets/thermals.json",
                "widgets/welcome.json",
                "widgets/trend.json",
                "settings.json",
            ),
            ordered,
            "manifest first, then sections in author order",
        )

        ZipFile(out).use { zip ->
            zip.entries().asSequence().forEach { e ->
                assertTrue(e.size <= 2 * 1024 * 1024, "entry ${e.name} <= 2MiB")
                if (!e.isDirectory) {
                    File(outDir, e.name).apply { parentFile?.mkdirs() }
                        .writeBytes(zip.getInputStream(e).readBytes())
                }
            }
        }

        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        File(outDir, "personalos.gcplugin.sha256").writeText("$sha  personalos.gcplugin\n")
        println("PERSONALOS_GCPLUGIN_SHA256=$sha")
        println("PERSONALOS_GCPLUGIN_PATH=${out.absolutePath}")
        println("PERSONALOS_GCPLUGIN_BYTES=${bytes.size}")
        assertEquals(64, sha.length, "sha-256 hex length")

        // Flatten the representative subset into the bundled assets the host loads on first run:
        // manifest.json, theme.json, settings.json, screens.json and the one representative widget
        // as widgets.json. The other widget files stay in the .gcplugin only.
        val assetsDir = File(repoRoot(), "app/src/main/assets/plugins/personalos").apply { mkdirs() }
        File(assetsDir, "manifest.json").writeBytes(def.manifestJson)
        val repPath = "widgets/vitals.json"
        def.sectionFiles.forEach { (path, sectionBytes) ->
            val flat = when {
                path == "screens/screens.json" -> "screens.json"
                path == "rules/rules.json" -> "rules.json"
                path.startsWith("widgets/") -> if (path == repPath) "widgets.json" else null
                else -> path
            } ?: return@forEach
            File(assetsDir, flat).writeBytes(sectionBytes)
        }
    }
}
