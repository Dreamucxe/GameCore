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
        assertEquals(3, m.getInt("version"), "version")
        assertEquals("1.2.0", m.getString("versionName"), "versionName")
        // The api is raised by the section the plugin declares, not chosen: a schema-4 theme is
        // readable only by a host that knows schema 4, so the builder lifts this from Sdk.API.
        assertEquals(Sdk.API_V4, m.getInt("api"), "api is the schema-4 level")
        // The glass block raises this on its own — see ThemeBuilder.schemaVersion(). Pinned to the
        // glass host rather than to Sdk.HOST_VERSION_CODE, because a host below this one parses
        // `effects.glass` as an unknown key and refuses the whole theme.
        assertEquals(
            Sdk.HOST_VERSION_CODE_GLASS,
            m.getInt("minHostVersion"),
            "minHostVersion is the glass host (${Sdk.HOST_VERSION_CODE_GLASS})",
        )
        assertEquals("Dream", m.getString("developer"), "developer")
        assertTrue(
            m.getString("changelog").startsWith("1.2.0"),
            "changelog names the release it belongs to",
        )

        // Legal guard: the id and name must not carry Apple's marks (only "inspired by iOS").
        for (field in listOf("id", "name")) {
            val v = m.getString(field).lowercase()
            for (mark in listOf("ios", "apple", "iphone")) {
                assertTrue(mark !in v, "$field must not contain \"$mark\" (is \"${m.getString(field)}\")")
            }
        }

        // Three permissions: the theme repaint, the launcher icon, and the isolated store the
        // Control Center's intensity value lives in.
        val perms = m.getJSONArray("permissions")
        assertEquals(
            listOf("change_ui", "change_icon", "store_data"),
            (0 until perms.length()).map { perms.getJSONObject(it).getString("id") },
            "change_ui, change_icon then store_data, in request order",
        )
        for (i in 0 until perms.length()) {
            assertTrue(!perms.getJSONObject(i).has("scopes"), "neither permission carries scopes")
        }

        val declares = m.getJSONArray("declares")
        assertEquals(
            listOf("theme", "settings", "icon"),
            (0 until declares.length()).map { declares.getString(it) },
            "declares theme, then settings, then the icon section",
        )
    }

    @Test
    fun theme_matches_the_palette() {
        val t = JSONObject(String(def.sectionFiles.getValue("theme.json")))
        assertEquals(
            setOf(
                "schema", "name", "dark", "primary", "onPrimary", "background", "surface",
                "onSurface", "accent", "cornerRadiusDp", "font", "uiScale", "effects",
            ),
            t.keys().asSequence().toSet(),
            "theme emits exactly the host's known theme keys",
        )
        assertEquals(4, t.getInt("schema"), "schema 4 — the glass material needs it")
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

    /**
     * The block this plugin exists for. Every field is asserted, because each one is a *different*
     * claim about the material: drop `transparency` and the theme is a flat tint again, drop `gloss`
     * and it is translucency with no rim, drop the spring and a panel snaps instead of settling.
     */
    @Test
    fun theme_asks_for_the_glass_material() {
        val t = JSONObject(String(def.sectionFiles.getValue("theme.json")))
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
        assertEquals(0.7, g.getDouble("intensity"), 1e-9, "intensity")
        assertEquals(0.62, g.getDouble("transparency"), 1e-9, "transparency")
        assertEquals(0.55, g.getDouble("gloss"), 1e-9, "gloss")
        assertEquals(16, g.getInt("blurRadiusDp"), "blurRadiusDp")
        assertTrue(g.getBoolean("sheen"), "sheen")
        assertEquals(9000, g.getInt("sheenPeriodMs"), "sheenPeriodMs")
        assertEquals(GlassSprings.SNAPPY, g.getString("spring"), "spring")
        // Off on purpose: glass must not thin itself out on a warm device. See the template's header.
        assertTrue(!g.getBoolean("hardwareReactive"), "hardwareReactive is off")
        assertEquals("glass_intensity", g.getString("intensitySetting"), "intensitySetting")
        for (key in listOf("intensity", "transparency", "gloss")) {
            val v = g.getDouble(key)
            assertTrue(v in 0.0..1.0, "$key is a 0..1 degree (is $v)")
        }
    }

    /** The control the material is scaled by, which is what makes it a Control Center and not a look. */
    @Test
    fun settings_declare_the_intensity_the_glass_block_drives() {
        // The section is a list of definitions, not a map keyed by id.
        val settings = JSONObject(String(def.sectionFiles.getValue("settings.json")))
            .getJSONArray("settings")
        assertEquals(1, settings.length(), "the plugin declares exactly one setting")
        val intensity = settings.getJSONObject(0)
        assertEquals("glass_intensity", intensity.getString("id"), "id")
        assertEquals(SettingTypes.FLOAT, intensity.getString("type"), "type")
        assertEquals(0.7, intensity.getDouble("default"), 1e-9, "default — the theme's own intensity")
        assertEquals(0.0, intensity.getDouble("min"), 1e-9, "min")
        assertEquals(1.0, intensity.getDouble("max"), 1e-9, "max")
    }

    /**
     * The cross-check that makes the pair above safe: the id the material is scaled by is a setting
     * this plugin actually declares, and it is a number.
     *
     * Both failure modes are silent in the host — it finds no control, or finds one it cannot read as
     * a value — and both look to the user exactly like a theme that ignores its own Control Center.
     * So the refusal has to happen here, in the author's build. `plugin {}` builds its bytes eagerly,
     * which is why the throw lands on the call and not on [`PluginDefinition.manifestJson`].
     */
    @Test
    fun an_intensitySetting_that_names_nothing_refuses_to_build() {
        val error = runCatching {
            plugin(id = "com.dreamucxe.gamecore.probe", name = "Probe") {
                theme {
                    name = "Probe"
                    glass { intensitySetting = "not_declared" }
                }
                settings { float("something_else", default = 0.5) }
            }
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException, "a nameless control is refused (got $error)")
        assertTrue(
            error!!.message!!.contains("not_declared"),
            "the message names the id that could not be resolved",
        )
    }

    /** A boolean is a setting, but it is not an intensity: the host could not read it as a value. */
    @Test
    fun an_intensitySetting_that_is_not_a_number_refuses_to_build() {
        val error = runCatching {
            plugin(id = "com.dreamucxe.gamecore.probe2", name = "Probe") {
                theme {
                    name = "Probe"
                    glass { intensitySetting = "on" }
                }
                settings { bool("on", default = true) }
            }
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException, "a non-numeric control is refused (got $error)")
        assertTrue(
            error!!.message!!.contains("on"),
            "the message names the id that could not be scaled from",
        )
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
            listOf("manifest.json", "theme.json", "settings.json", "icon.json"),
            ordered,
            "manifest first, then theme, settings and the icon section",
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
