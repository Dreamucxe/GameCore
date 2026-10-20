package com.gamecore.plugin.sdk

import com.gamecore.plugin.sdk.templates.chromecoreGameCore
import com.gamecore.plugin.sdk.templates.cybercityGameCore
import com.gamecore.plugin.sdk.templates.cybercoreGameCore
import com.gamecore.plugin.sdk.templates.cybersigilismGameCore
import com.gamecore.plugin.sdk.templates.retroFuturismGameCore
import com.gamecore.plugin.sdk.templates.robotCoreGameCore
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Packages the six concept themes from the moodboards — Cybercity, Cybercore, Robot Core, Chromecore,
 * Retro Futurism and Cybersigilism — into `dist/<slug>/<slug>.gcplugin`, and proves each one against
 * the host's theme contract before it ships.
 *
 * The checks are the same for all six because the promise is the same: a schema-2 theme with exactly
 * the keys the host knows, one `change_ui` grant, `api` raised to 2, and every text/fill pair a user
 * actually reads clearing WCAG AA. The palettes differ; the contract does not.
 */
class ConceptThemesPackageTest {

    private data class Case(
        val slug: String,
        val id: String,
        val name: String,
        val dark: Boolean,
        val amoled: Boolean = false,
        val def: PluginDefinition,
    )

    private val cases = listOf(
        Case("cybercity", "com.dreamucxe.gamecore.cybercity", "Cybercity GameCore", dark = false, def = cybercityGameCore()),
        Case("cybercore", "com.dreamucxe.gamecore.cybercore", "Cybercore GameCore", dark = true, def = cybercoreGameCore()),
        Case("robotcore", "com.dreamucxe.gamecore.robotcore", "Robot Core GameCore", dark = false, def = robotCoreGameCore()),
        Case("chromecore", "com.dreamucxe.gamecore.chromecore", "Chromecore GameCore", dark = true, amoled = true, def = chromecoreGameCore()),
        Case("retrofuturism", "com.dreamucxe.gamecore.retrofuturism", "Retro Futurism GameCore", dark = false, def = retroFuturismGameCore()),
        Case("cybersigilism", "com.dreamucxe.gamecore.cybersigilism", "Cybersigilism GameCore", dark = true, amoled = true, def = cybersigilismGameCore()),
    )

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "app").isDirectory && File(dir, "gamecore-plugin-sdk").isDirectory) return dir
            dir = dir.parentFile
        }
        error("repo root not found from ${File("").absolutePath}")
    }

    private fun themeOf(def: PluginDefinition): JSONObject =
        JSONObject(String(def.sectionFiles.getValue("theme.json")))

    @Test
    fun every_manifest_matches_the_theme_contract() {
        cases.forEach { case ->
            val m = JSONObject(String(case.def.manifestJson))
            assertEquals(case.id, m.getString("id"), "${case.slug} id")
            assertEquals(case.name, m.getString("name"), "${case.slug} name")
            assertEquals("gamecore.plugin", m.getString("format"), "${case.slug} format")
            assertEquals(1, m.getInt("version"), "${case.slug} version")
            assertEquals("1.0.0", m.getString("versionName"), "${case.slug} versionName")
            assertEquals(28, m.getInt("minHostVersion"), "${case.slug} minHostVersion")
            assertEquals(
                2, m.getInt("api"),
                "${case.slug} is schema 2, so its api must be 2 — a v1 host rejects a v2 theme",
            )

            val perms = m.getJSONArray("permissions")
            assertEquals(1, perms.length(), "${case.slug} asks for exactly one permission")
            assertEquals("change_ui", perms.getJSONObject(0).getString("id"), "${case.slug} permission")
            assertTrue(!perms.getJSONObject(0).has("scopes"), "${case.slug} change_ui carries no scopes")

            val declares = m.getJSONArray("declares")
            assertEquals(1, declares.length(), "${case.slug} declares exactly one section")
            assertEquals("theme", declares.getString(0), "${case.slug} declares theme")
        }
    }

    @Test
    fun every_theme_emits_the_v2_key_set_and_respects_bounds() {
        cases.forEach { case ->
            val t = themeOf(case.def)
            val expectedKeys = setOf(
                "name", "dark", "primary", "onPrimary", "background", "surface", "onSurface",
                "accent", "cornerRadiusDp", "font", "uiScale", "schema", "roles", "typography",
            ) + (if (case.amoled) setOf("amoled") else emptySet())
            assertEquals(
                expectedKeys,
                t.keys().asSequence().toSet(),
                "${case.slug} emits exactly the host's known theme keys",
            )
            assertEquals(case.amoled, t.optBoolean("amoled", false), "${case.slug} amoled flag")
            assertEquals(case.name, t.getString("name"), "${case.slug} theme name")
            assertEquals(case.dark, t.getBoolean("dark"), "${case.slug} dark flag")
            assertEquals(2, t.getInt("schema"), "${case.slug} schema")
            assertTrue(t.getString("font") in ThemeFonts.ALL, "${case.slug} font")

            val corner = t.getInt("cornerRadiusDp")
            assertTrue(
                corner in ThemeBounds.MIN_CORNER_DP..ThemeBounds.MAX_CORNER_DP,
                "${case.slug} corner $corner out of bounds",
            )
            val ui = t.getDouble("uiScale")
            assertTrue(ui in ThemeBounds.MIN_UI_SCALE..ThemeBounds.MAX_UI_SCALE, "${case.slug} uiScale")

            val type = t.getJSONObject("typography")
            // `scale` is only emitted when it differs from the default, so its absence means 1.0.
            val scale = if (type.has("scale")) type.getDouble("scale") else ThemeBounds.DEFAULT_TYPE_SCALE
            assertTrue(scale in ThemeBounds.MIN_TYPE_SCALE..ThemeBounds.MAX_TYPE_SCALE, "${case.slug} type scale")
            assertTrue(type.getString("weight") in ThemeWeights.ALL, "${case.slug} type weight")

            // Every role this set uses must be present, so no palette quietly falls back to a
            // derived colour the author never checked.
            val roles = t.getJSONObject("roles")
            listOf(
                ThemeRoles.SECONDARY, ThemeRoles.TERTIARY, ThemeRoles.SURFACE_VARIANT,
                ThemeRoles.ON_SURFACE_VARIANT, ThemeRoles.OUTLINE, ThemeRoles.ERROR, ThemeRoles.ON_ERROR,
            ).forEach { role ->
                assertTrue(roles.has(role), "${case.slug} sets roles.$role")
            }
        }
    }

    @Test
    fun every_text_pair_clears_wcag_aa() {
        cases.forEach { case ->
            val t = themeOf(case.def)
            val roles = t.getJSONObject("roles")
            val flat = listOf(
                Triple("onSurface/background", t.getString("onSurface"), t.getString("background")),
                Triple("onSurface/surface", t.getString("onSurface"), t.getString("surface")),
                Triple("onPrimary/primary", t.getString("onPrimary"), t.getString("primary")),
                Triple(
                    "onError/error",
                    roles.getString(ThemeRoles.ON_ERROR),
                    roles.getString(ThemeRoles.ERROR),
                ),
                Triple(
                    "onSurfaceVariant/surface",
                    roles.getString(ThemeRoles.ON_SURFACE_VARIANT),
                    t.getString("surface"),
                ),
            )
            flat.forEach { (label, fg, bg) ->
                val ratio = contrast(fg, bg)
                assertTrue(
                    ratio >= 4.5,
                    "${case.slug} $label is %.2f:1, below WCAG AA 4.5:1".format(ratio),
                )
            }
        }
    }

    @Test
    fun packages_all_six_deliverables_within_host_limits() {
        cases.forEach { case ->
            val outDir = File(repoRoot(), "dist/${case.slug}").apply { mkdirs() }
            val out = File(outDir, "${case.slug}.gcplugin")
            PluginPackager.writeTo(out, case.def)
            assertTrue(out.isFile, "${case.slug} artifact at ${out.absolutePath}")

            val bytes = out.readBytes()
            assertTrue(bytes.size <= 5 * 1024 * 1024, "${case.slug} package <= 5MiB")

            val ordered = ArrayList<String>()
            ZipInputStream(out.inputStream()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    ordered += e.name
                    zin.closeEntry()
                }
            }
            assertEquals(listOf("manifest.json", "theme.json"), ordered, "${case.slug} entry order")

            ZipFile(out).use { zip ->
                zip.entries().asSequence().forEach { e ->
                    assertTrue(e.size <= 2 * 1024 * 1024, "${case.slug} entry ${e.name} <= 2MiB")
                    File(outDir, e.name).writeBytes(zip.getInputStream(e).readBytes())
                }
            }

            val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            File(outDir, "${case.slug}.gcplugin.sha256").writeText("$sha  ${case.slug}.gcplugin\n")
            println("CONCEPT_GCPLUGIN=${case.slug} sha256=$sha bytes=${bytes.size}")
        }
    }

    // ----------------------------------------------------------------------------------- WCAG maths

    private fun contrast(a: String, b: String): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun luminance(hex: String): Double {
        val h = hex.trim().removePrefix("#")
        val r = channel(h.substring(0, 2).toInt(16) / 255.0)
        val g = channel(h.substring(2, 4).toInt(16) / 255.0)
        val b = channel(h.substring(4, 6).toInt(16) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun channel(c: Double): Double =
        if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}
