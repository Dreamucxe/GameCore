package com.gamecore.plugin.sdk

import com.gamecore.plugin.sdk.templates.nothingOsGaming
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Produces the deliverable `dist/nothingos/nothingos.gcplugin` from [nothingOsGaming], proves the
 * authored bytes match the brief and the host's packaging limits, and then flattens those same bytes
 * into `app/src/main/assets/plugins/nothingos/` so GameCore ships this plugin as a bundled default
 * (where `NothingOsGamingBundledTest` re-validates it through the host's own codecs).
 *
 * kotlin.test idiom: `assertEquals(expected, actual, message?)` — the message is LAST.
 */
class NothingOsGamingPackageTest {

    private val def = nothingOsGaming()

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
        assertEquals("com.dreamucxe.gamecore.nothingos", m.getString("id"), "id")
        assertEquals("Nothing OS Gaming", m.getString("name"), "name")
        assertEquals(2, m.getInt("version"), "version")
        assertEquals("1.1.0", m.getString("versionName"), "versionName")
        assertEquals(2, m.getInt("api"), "api is 2, reached automatically by the amoled schema-2 theme")
        assertEquals(Sdk.HOST_VERSION_CODE_ICON, m.getInt("minHostVersion"), "minHostVersion is the icon host (37)")
        assertEquals("Dream", m.getString("developer"), "developer")
        assertEquals("Initial release.", m.getString("changelog"), "changelog")

        val perms = m.getJSONArray("permissions")
        assertEquals(4, perms.length(), "exactly four permissions")
        assertEquals(
            setOf("change_ui", "run_automations", "system_actions", "change_icon"),
            (0 until perms.length()).map { perms.getJSONObject(it).getString("id") }.toSet(),
            "the four permissions the brief requires",
        )
        (0 until perms.length()).forEach { i ->
            assertTrue(!perms.getJSONObject(i).has("scopes"), "none of these permissions carries scopes")
        }

        val declares = m.getJSONArray("declares")
        assertEquals(
            listOf("theme", "rules", "icon"),
            (0 until declares.length()).map { declares.getString(it) },
            "declares theme then rules then the icon, in insertion order",
        )
    }

    @Test
    fun theme_matches_the_palette() {
        val t = JSONObject(String(def.sectionFiles.getValue("theme.json")))
        assertEquals(
            setOf(
                "name", "dark", "primary", "onPrimary", "background", "surface",
                "onSurface", "accent", "cornerRadiusDp", "font", "uiScale",
                "schema", "amoled", "roles", "typography",
            ),
            t.keys().asSequence().toSet(),
            "theme emits the eleven v1 keys plus the four schema-2 extras",
        )
        assertEquals("Nothing OS Gaming", t.getString("name"), "theme name")
        assertTrue(t.getBoolean("dark"), "dark")
        assertEquals("#EDEDED", t.getString("primary"), "primary near-white")
        assertEquals("#0A0A0A", t.getString("onPrimary"), "onPrimary ink")
        assertEquals("#000000", t.getString("background"), "true-black background")
        assertEquals("#0A0A0A", t.getString("surface"), "surface")
        assertEquals("#F2F2F2", t.getString("onSurface"), "onSurface text")
        assertEquals("#D71921", t.getString("accent"), "accent is the Nothing red")
        assertEquals(4, t.getInt("cornerRadiusDp"), "sharp corner radius")
        assertEquals("mono", t.getString("font"), "font")
        assertEquals(1.0, t.getDouble("uiScale"), 1e-9, "uiScale")

        assertEquals(2, t.getInt("schema"), "schema 2")
        assertTrue(t.getBoolean("amoled"), "amoled true-black base")
        val roles = t.getJSONObject("roles")
        assertEquals(
            setOf("secondary", "tertiary", "surfaceVariant", "onSurfaceVariant", "outline", "error", "onError"),
            roles.keys().asSequence().toSet(),
            "the seven monochrome roles are set",
        )
        assertEquals("#C8C8C8", roles.getString("secondary"), "secondary stays monochrome")
        assertEquals("#D71921", roles.getString("error"), "error reuses the Nothing red")
        assertEquals("#FFFFFF", roles.getString("onError"), "white ink on the red")
        val typo = t.getJSONObject("typography")
        assertEquals(setOf("weight"), typo.keys().asSequence().toSet(), "only a global weight, no scale")
        assertEquals("medium", typo.getString("weight"), "one even technical weight")
    }

    @Test
    fun rules_match_the_brief() {
        val rules = JSONObject(String(def.sectionFiles.getValue("rules/rules.json"))).getJSONArray("rules")
        assertEquals(2, rules.length(), "two rules")

        val launch = rules.getJSONObject(0)
        assertEquals("game_launch_boost", launch.getString("id"), "first rule id")
        assertEquals("GAME_LAUNCHED", launch.getString("trigger"), "trigger uppercased by the DSL")
        assertEquals(
            listOf(
                "set_peak_refresh_rate", "set_window_animation_scale",
                "set_transition_animation_scale", "set_animator_duration_scale",
            ),
            launch.getJSONArray("actions").let { a -> (0 until a.length()).map { a.getString(it) } },
            "the launch-boost actions, all real broker ids copied from performance/rules.json",
        )
        assertTrue(!launch.has("conditions"), "the launch rule fires with no conditions")

        val battery = rules.getJSONObject(1)
        assertEquals("battery_saver", battery.getString("id"), "second rule id")
        assertEquals("BATTERY_LOW", battery.getString("trigger"), "trigger")
        assertEquals(
            listOf("set_low_power"),
            battery.getJSONArray("actions").let { a -> (0 until a.length()).map { a.getString(it) } },
            "low-power action",
        )
        val cond = battery.getJSONArray("conditions").getJSONObject(0)
        assertEquals("battery", cond.getString("variable"), "condition variable named from RuleVariables")
        assertEquals("LTE", cond.getString("op"), "operator uppercased")
        assertEquals(15.0, cond.getDouble("value"), 1e-9, "battery <= 15")
    }

    @Test
    fun packages_the_deliverable_within_host_limits() {
        val outDir = File(repoRoot(), "dist/nothingos").apply { mkdirs() }
        val out = File(outDir, "nothingos.gcplugin")
        PluginPackager.writeTo(out, def)
        assertTrue(out.isFile, "artifact written to ${out.absolutePath}")

        val pkgBytes = out.readBytes()
        assertTrue(pkgBytes.size <= 5 * 1024 * 1024, "package <= 5MiB (is ${pkgBytes.size} bytes)")

        val ordered = ArrayList<String>()
        ZipInputStream(out.inputStream()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                ordered += e.name
                zin.closeEntry()
            }
        }
        assertEquals(
            listOf("manifest.json", "theme.json", "rules/rules.json", "icon.json"),
            ordered,
            "manifest first, then theme, then rules — insertion order",
        )

        ZipFile(out).use { zip ->
            zip.entries().asSequence().forEach { e ->
                assertTrue(e.size <= 2 * 1024 * 1024, "entry ${e.name} <= 2MiB")
                File(outDir, e.name).apply { parentFile?.mkdirs() }.writeBytes(zip.getInputStream(e).readBytes())
            }
        }

        val sha = MessageDigest.getInstance("SHA-256").digest(pkgBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        File(outDir, "nothingos.gcplugin.sha256").writeText("$sha  nothingos.gcplugin\n")
        println("NOTHINGOS_GCPLUGIN_SHA256=$sha")
        println("NOTHINGOS_GCPLUGIN_PATH=${out.absolutePath}")
        println("NOTHINGOS_GCPLUGIN_BYTES=${pkgBytes.size}")
        assertEquals(64, sha.length, "sha-256 hex length")

        // Flatten the same authored bytes into GameCore's bundled plugin assets, so the host ships
        // this plugin as a default and NothingOsGamingBundledTest validates it through the real codecs.
        val assetsDir = File(repoRoot(), "app/src/main/assets/plugins/nothingos").apply { mkdirs() }
        File(assetsDir, "manifest.json").writeBytes(def.manifestJson)
        def.sectionFiles.forEach { (path, bytes) ->
            val flat = when {
                path == "screens/screens.json" -> "screens.json"
                path == "rules/rules.json" -> "rules.json"
                path.startsWith("widgets/") -> null   // no widgets in this plugin
                else -> path                            // theme.json
            } ?: return@forEach
            File(assetsDir, flat).writeBytes(bytes)
        }
    }
}
