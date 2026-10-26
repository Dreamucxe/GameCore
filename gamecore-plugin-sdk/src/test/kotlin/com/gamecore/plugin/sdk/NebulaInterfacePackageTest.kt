package com.gamecore.plugin.sdk

import com.gamecore.plugin.sdk.templates.nebulaInterfaceSuite
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Produces the deliverable `dist/nebula/nebula.gcplugin` from [nebulaInterfaceSuite], proves the
 * authored bytes match the brief and the host's packaging limits, and refreshes the bundled copy
 * under `app/src/main/assets/plugins/nebula/`.
 *
 * The bundled copy is a *subset* by construction, exactly as the host's bundled-plugin loader
 * reads it: `screens/screens.json` is flattened to `screens.json`, and the package's five widget
 * files become one representative `widgets.json` (`command`). The `.gcplugin` carries the whole
 * suite. This asymmetry is a host limitation, not a missing widget: the bundled path reads one
 * widget file per plugin, while a sideloaded package can carry many.
 */
class NebulaInterfacePackageTest {

    private val def = nebulaInterfaceSuite()

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
        assertEquals("com.dreamucxe.gamecore.nebula", m.getString("id"), "id")
        assertEquals("Nebula Interface Suite", m.getString("name"), "name")
        assertEquals(1, m.getInt("version"), "version")
        assertEquals("1.0.0", m.getString("versionName"), "versionName")
        assertEquals(Sdk.API_V4, m.getInt("api"), "the schema-4 glass theme raises api to 4")
        assertEquals(
            Sdk.HOST_VERSION_CODE_GLASS,
            m.getInt("minHostVersion"),
            "the glass block raises minHostVersion to the glass host",
        )
        assertEquals("Dream", m.getString("developer"), "developer")
        assertTrue(m.getString("changelog").startsWith("1.0.0"), "changelog names the release")

        // No third-party marks: the suite is inspired by a design language, not affiliated with a
        // storefront. "Orion" is deliberately absent from the package identity and user-facing text.
        for (field in listOf("id", "name", "description")) {
            val value = m.getString(field).lowercase()
            for (mark in listOf("orion", "apple", "ios", "samsung", "oneplus")) {
                assertTrue(mark !in value, "$field must not carry the mark \"$mark\" (is \"$value\")")
            }
        }

        val perms = m.getJSONArray("permissions")
        assertEquals(
            listOf("change_ui", "add_dashboard_widgets", "store_data", "read_approved_data", "change_icon"),
            (0 until perms.length()).map { perms.getJSONObject(it).getString("id") },
            "permissions in request order",
        )
        val scopes = perms.getJSONObject(3).getJSONArray("scopes").let { array ->
            (0 until array.length()).map { array.getString(it) }.toSet()
        }
        assertEquals(setOf("device_stats", "thermals"), scopes, "the exact union every stat needs")

        val declares = m.getJSONArray("declares")
        assertEquals(
            listOf("theme", "settings", "screens", "widgets", "icon"),
            (0 until declares.length()).map { declares.getString(it) },
            "declares every section the package carries, in author order",
        )
    }

    @Test
    fun theme_matches_the_palette_and_material() {
        val t = JSONObject(String(def.sectionFiles.getValue("theme.json")))
        assertEquals(
            setOf(
                "schema", "name", "dark", "primary", "onPrimary", "background", "surface",
                "onSurface", "accent", "cornerRadiusDp", "font", "uiScale", "amoled", "roles",
                "typography", "effects",
            ),
            t.keys().asSequence().toSet(),
            "theme emits the v1 palette, the schema-2 extras and the schema-4 effects block",
        )
        assertEquals(4, t.getInt("schema"), "schema 4")
        assertEquals("Nebula Interface Suite", t.getString("name"), "theme name")
        assertTrue(t.getBoolean("dark"), "dark theme")
        assertTrue(t.getBoolean("amoled"), "AMOLED base")
        assertEquals("#C9B8FF", t.getString("primary"), "primary orchid")
        assertEquals("#1A1033", t.getString("onPrimary"), "deep ink on orchid")
        assertEquals("#000000", t.getString("background"), "true-black page")
        assertEquals("#111118", t.getString("surface"), "near-black glass surface")
        assertEquals("#F7F3FF", t.getString("onSurface"), "lavender-white ink")
        assertEquals("#FF9DCE", t.getString("accent"), "rose accent")
        assertEquals(28, t.getInt("cornerRadiusDp"), "large rounded cards")
        assertEquals("rounded", t.getString("font"), "rounded bundled face")
        assertEquals(1.0, t.getDouble("uiScale"), 1e-9, "uiScale")

        val roles = t.getJSONObject("roles")
        assertEquals(
            setOf("secondary", "tertiary", "surfaceVariant", "onSurfaceVariant", "outline", "error", "onError"),
            roles.keys().asSequence().toSet(),
            "all seven semantic roles are pinned",
        )
        assertEquals("#8B7CFF", roles.getString("secondary"), "secondary iris")
        assertEquals("#7DD3FC", roles.getString("tertiary"), "tertiary sky")
        assertEquals("#FF6B8A", roles.getString("error"), "error rose")
        assertEquals("#2A0710", roles.getString("onError"), "error ink")
        assertEquals("medium", t.getJSONObject("typography").getString("weight"), "medium type weight")

        val effects = t.getJSONObject("effects")
        assertEquals(setOf("glass"), effects.keys().asSequence().toSet(), "effects carries glass alone")
        val g = effects.getJSONObject("glass")
        assertEquals(
            setOf(
                "intensity", "transparency", "gloss", "blurRadiusDp", "sheen", "sheenPeriodMs",
                "spring", "hardwareReactive", "intensitySetting",
            ),
            g.keys().asSequence().toSet(),
            "glass emits exactly the host's known glass keys",
        )
        assertEquals(1.0, g.getDouble("intensity"), 1e-9, "authored intensity is the ceiling")
        assertEquals(0.58, g.getDouble("transparency"), 1e-9, "transparency")
        assertEquals(0.60, g.getDouble("gloss"), 1e-9, "gloss")
        assertEquals(18, g.getInt("blurRadiusDp"), "blur")
        assertTrue(g.getBoolean("sheen"), "sheen")
        assertEquals(7200, g.getInt("sheenPeriodMs"), "slow sheen")
        assertEquals(GlassSprings.GENTLE, g.getString("spring"), "gentle settle")
        assertTrue(!g.getBoolean("hardwareReactive"), "material is not thinned by device load")
        assertEquals("glass_intensity", g.getString("intensitySetting"), "the Control Center setting")
    }

    @Test
    fun the_one_setting_is_the_glass_control() {
        val settings = JSONObject(String(def.sectionFiles.getValue("settings.json")))
            .getJSONArray("settings")
        assertEquals(1, settings.length(), "the suite declares exactly one live setting")
        val s = settings.getJSONObject(0)
        assertEquals("glass_intensity", s.getString("id"), "id")
        assertEquals(SettingTypes.FLOAT, s.getString("type"), "float")
        assertEquals(0.72, s.getDouble("default"), 1e-9, "the 72% starting point")
        assertEquals(0.0, s.getDouble("min"), 1e-9, "min")
        assertEquals(1.0, s.getDouble("max"), 1e-9, "max")
    }

    @Test
    fun screens_are_seven_detailed_references() {
        val s = JSONObject(String(def.sectionFiles.getValue("screens/screens.json")))
        assertEquals(Sdk.SCREENS_FORMAT, s.getString("format"), "screens format")
        assertEquals(Sdk.SCREENS_VERSION, s.getInt("version"), "screens version")
        val arr = s.getJSONArray("screens")
        assertEquals(7, arr.length(), "seven screens")
        assertEquals(
            listOf("control", "look", "motion", "dock", "type", "performance", "notes"),
            (0 until arr.length()).map { arr.getJSONObject(it).getString("id") },
            "screen ids, in authored order",
        )
        assertEquals(
            listOf("Control Center", "Look & Feel", "Motion Lab", "Dock & Density", "Type & Palette", "Performance Modes", "Design Notes"),
            (0 until arr.length()).map { arr.getJSONObject(it).getString("title") },
            "screen titles",
        )
        var totalNodes = 0
        for (i in 0 until arr.length()) {
            val screen = arr.getJSONObject(i)
            val count = countNodes(screen.getJSONObject("content"))
            assertTrue(count <= 500, "screen ${screen.getString("id")} has $count nodes, cap 500")
            totalNodes += count
        }
        assertTrue(totalNodes >= 250, "the suite is detailed: $totalNodes screen nodes")
    }

    @Test
    fun widgets_are_five_bounded_cards() {
        val widgetEntries = def.sectionFiles.keys.filter { it.startsWith("widgets/") }.sorted()
        assertEquals(
            listOf(
                "widgets/command.json",
                "widgets/dock.json",
                "widgets/motion.json",
                "widgets/palette.json",
                "widgets/vitals.json",
            ),
            widgetEntries,
            "five widget files",
        )
        val sizes = mutableMapOf<String, String>()
        for (path in widgetEntries) {
            val w = JSONObject(String(def.sectionFiles.getValue(path)))
            assertEquals(Sdk.WIDGET_FORMAT, w.getString("format"), "$path format")
            assertEquals(Sdk.WIDGET_VERSION, w.getInt("version"), "$path version")
            val id = w.getString("id")
            sizes[id] = w.getString("size")
            val count = countNodes(w.getJSONObject("content"))
            assertTrue(count <= 40, "$path has $count nodes, cap 40")
        }
        assertEquals(
            mapOf(
                "command" to WidgetSizes.FULL,
                "dock" to WidgetSizes.FULL,
                "motion" to WidgetSizes.MEDIUM,
                "palette" to WidgetSizes.SMALL,
                "vitals" to WidgetSizes.MEDIUM,
            ),
            sizes,
            "widget sizes",
        )
    }

    @Test
    fun package_contains_every_section_and_stays_within_host_limits() {
        val outDir = File(repoRoot(), "dist/nebula").apply { mkdirs() }
        val out = File(outDir, "nebula.gcplugin")
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
                "settings.json",
                "screens/screens.json",
                "widgets/command.json",
                "widgets/vitals.json",
                "widgets/palette.json",
                "widgets/motion.json",
                "widgets/dock.json",
                "icon.json",
            ),
            ordered,
            "manifest first, then theme, settings, screens, widgets and the icon",
        )

        ZipFile(out).use { zip ->
            zip.entries().asSequence().forEach { e ->
                assertTrue(e.size <= 2 * 1024 * 1024, "entry ${e.name} <= 2MiB")
                File(outDir, e.name).apply { parentFile?.mkdirs() }
                    .writeBytes(zip.getInputStream(e).readBytes())
            }
        }

        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        File(outDir, "nebula.gcplugin.sha256").writeText("$sha  nebula.gcplugin\n")
        println("NEBULA_GCPLUGIN_SHA256=$sha")
        println("NEBULA_GCPLUGIN_PATH=${out.absolutePath}")
        println("NEBULA_GCPLUGIN_BYTES=${bytes.size}")
        assertEquals(64, sha.length, "sha-256 hex length")

        // Refresh the bundled subset the host ships. The host reads one widget file per bundled
        // plugin, so `command` is the representative card; all five travel in the .gcplugin.
        val assetDir = File(repoRoot(), "app/src/main/assets/plugins/nebula").apply { mkdirs() }
        ZipFile(out).use { zip ->
            for (name in listOf("manifest.json", "theme.json", "settings.json", "icon.json")) {
                File(assetDir, name).writeBytes(zip.getInputStream(zip.getEntry(name)).readBytes())
            }
            File(assetDir, "screens.json").writeBytes(
                zip.getInputStream(zip.getEntry("screens/screens.json")).readBytes(),
            )
            File(assetDir, "widgets.json").writeBytes(
                zip.getInputStream(zip.getEntry("widgets/command.json")).readBytes(),
            )
        }
        assertTrue(File(assetDir, "widgets.json").isFile, "bundled representative widget exists")
        assertTrue(!File(assetDir, "screens").exists(), "bundled screens are flattened, not nested")
    }

    private fun countNodes(root: JSONObject): Int {
        var count = 0
        fun walk(node: JSONObject) {
            count++
            val children = node.optJSONArray("children") ?: return
            for (i in 0 until children.length()) walk(children.getJSONObject(i))
        }
        walk(root)
        return count
    }
}
