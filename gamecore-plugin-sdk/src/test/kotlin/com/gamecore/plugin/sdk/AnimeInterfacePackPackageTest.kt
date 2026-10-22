package com.gamecore.plugin.sdk

import com.gamecore.plugin.sdk.templates.animeInterfacePack
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Produces the deliverable `dist/anime/anime.gcplugin` from [animeInterfacePack], proves the
 * authored bytes match the brief and the host's packaging limits, and refreshes the bundled copy
 * under `app/src/main/assets/plugins/anime/` so the host ships exactly the bytes this test signed.
 *
 * kotlin.test idiom: `assertEquals(expected, actual, message?)` — the message is LAST.
 */
class AnimeInterfacePackPackageTest {

    private val def = animeInterfacePack()

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
        assertEquals("com.dreamucxe.gamecore.anime", m.getString("id"), "id")
        assertEquals("Anime Interface Pack", m.getString("name"), "name")
        assertEquals(2, m.getInt("version"), "version")
        assertEquals("1.1.0", m.getString("versionName"), "versionName")
        // The schema-2 theme raises api to 2 and the screens section raises it to 3; the max wins.
        assertEquals(3, m.getInt("api"), "api")
        assertEquals(Sdk.HOST_VERSION_CODE_ICON, m.getInt("minHostVersion"), "minHostVersion is the icon host (37)")
        assertEquals("Dream", m.getString("developer"), "developer")
        assertEquals("Initial release.", m.getString("changelog"), "changelog")

        // Legal guard: neither id nor name may carry a studio, franchise or character mark — "anime"
        // here is a palette and layout language, not licensed content.
        for (field in listOf("id", "name")) {
            val v = m.getString(field).lowercase()
            for (mark in listOf("naruto", "pokemon", "ghibli", "crunchyroll", "bandai", "sanrio")) {
                assertTrue(mark !in v, "$field must not carry the mark \"$mark\" (is \"${m.getString(field)}\")")
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
            listOf("theme", "screens", "icon"),
            (0 until declares.length()).map { declares.getString(it) },
            "declares theme and screens, then the icon section last",
        )
    }

    @Test
    fun theme_matches_the_palette() {
        val t = JSONObject(String(def.sectionFiles.getValue("theme.json")))
        assertEquals(
            setOf(
                "name", "dark", "primary", "onPrimary", "background", "surface",
                "onSurface", "accent", "cornerRadiusDp", "font", "uiScale",
                "schema", "roles", "typography",
            ),
            t.keys().asSequence().toSet(),
            "theme emits the v1 palette plus the schema-2 roles and typography blocks",
        )
        assertEquals("Anime Interface Pack", t.getString("name"), "theme name")
        assertTrue(t.getBoolean("dark"), "dark theme (dark=true)")
        assertEquals("#FF4FA3", t.getString("primary"), "primary hot pink")
        assertEquals("#1A0A14", t.getString("onPrimary"), "onPrimary near-black plum ink")
        assertEquals("#0E0A12", t.getString("background"), "background deep plum-black")
        assertEquals("#241026", t.getString("surface"), "surface raised plum")
        assertEquals("#FFE6F3", t.getString("onSurface"), "onSurface pink-tinted near-white ink")
        assertEquals("#38F0E0", t.getString("accent"), "accent electric cyan")
        assertEquals(24, t.getInt("cornerRadiusDp"), "corner radius (high, within 0..48)")
        assertEquals("rounded", t.getString("font"), "font")
        assertEquals(1.0, t.getDouble("uiScale"), 1e-9, "uiScale")

        assertEquals(2, t.getInt("schema"), "schema-2 theme")
        val roles = t.getJSONObject("roles")
        assertEquals(
            setOf("secondary", "tertiary", "outline"),
            roles.keys().asSequence().toSet(),
            "roles pins exactly the anime triad's secondary, tertiary and outline",
        )
        assertEquals("#38F0E0", roles.getString("secondary"), "role secondary cyan")
        assertEquals("#B16CFF", roles.getString("tertiary"), "role tertiary violet")
        assertEquals("#5A2E52", roles.getString("outline"), "role outline muted pink-violet")
        val typography = t.getJSONObject("typography")
        assertEquals(setOf("weight"), typography.keys().asSequence().toSet(), "typography sets only weight")
        assertEquals("medium", typography.getString("weight"), "poster-like medium weight")
    }

    @Test
    fun screens_match_the_brief() {
        val s = JSONObject(String(def.sectionFiles.getValue("screens/screens.json")))
        assertEquals("gamecore.plugin.screens", s.getString("format"), "screens section format")
        assertEquals(1, s.getInt("version"), "screens section version")
        val arr = s.getJSONArray("screens")
        assertEquals(2, arr.length(), "two read-only reference screens")
        val ids = (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
        assertEquals(listOf("showcase", "profile"), ids, "screen ids, in authored order")
        val titles = (0 until arr.length()).map { arr.getJSONObject(it).getString("title") }
        assertEquals(listOf("Interface Showcase", "Status Panel"), titles, "screen titles")
    }

    @Test
    fun packages_the_deliverable_within_host_limits() {
        val outDir = File(repoRoot(), "dist/anime").apply { mkdirs() }
        val out = File(outDir, "anime.gcplugin")
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
            listOf("manifest.json", "theme.json", "screens/screens.json", "icon.json"),
            ordered,
            "manifest first, then theme, then the screens section file",
        )

        // Extract the loose files beside the package for inspection. The screens entry is nested
        // under screens/, so its parent directory must exist before the write.
        ZipFile(out).use { zip ->
            zip.entries().asSequence().forEach { e ->
                assertTrue(e.size <= 2 * 1024 * 1024, "entry ${e.name} <= 2MiB")
                File(outDir, e.name).apply { parentFile?.mkdirs() }.writeBytes(zip.getInputStream(e).readBytes())
            }
        }

        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        File(outDir, "anime.gcplugin.sha256").writeText("$sha  anime.gcplugin\n")
        println("ANIME_GCPLUGIN_SHA256=$sha")
        println("ANIME_GCPLUGIN_PATH=${out.absolutePath}")
        println("ANIME_GCPLUGIN_BYTES=${bytes.size}")
        assertEquals(64, sha.length, "sha-256 hex length")

        // Refresh the bundled copy the host ships. A bundled plugin is loose files, and a section
        // whose package prefix is a directory (screens/) is carried flat as <section>.json — exactly
        // what PluginRegistryImpl.bundledEntryName() reads back — so each entry is written by its
        // basename: manifest.json, theme.json, and screens/screens.json -> screens.json.
        val assetDir = File(repoRoot(), "app/src/main/assets/plugins/anime").apply { mkdirs() }
        ZipFile(out).use { zip ->
            zip.entries().asSequence().forEach { e ->
                File(assetDir, e.name.substringAfterLast('/')).writeBytes(zip.getInputStream(e).readBytes())
            }
        }
    }
}
