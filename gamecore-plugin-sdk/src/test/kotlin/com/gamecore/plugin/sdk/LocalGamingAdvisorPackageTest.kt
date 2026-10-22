package com.gamecore.plugin.sdk

import com.gamecore.plugin.sdk.templates.localGamingAdvisor
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Produces the deliverable `dist/advisor/advisor.gcplugin` from [localGamingAdvisor] and proves the
 * authored bytes match the brief and the host's packaging limits, then flattens the packaged section
 * files into the bundled assets the app ships under `app/src/main/assets/plugins/advisor/`.
 *
 * The advisor is a data-only plugin across four sections — screens, widgets, settings and rules. The
 * `screens { }`, `widgets { }` and `settings { }` blocks each raise the manifest api to [Sdk.API_V3]
 * (3); `rules { }` and the hand-emitted `section(...)` raise nothing, so the final api is 3, asserted
 * below. `icon {}` raises `minHostVersion` to 37 (the 3.15.0 icon host), so the manifest and the features it needs can
 * never disagree.
 *
 * The one widget the DSL cannot build is the live chart: `OverlayNodeScope.chart` is literal-only, so
 * a chart naming a live host feed is emitted by hand through `section("widgets/advisor_trend.json")`.
 * That file is asserted to carry BOTH a live series (`stat`) and a literal fallback (`values`).
 *
 * The flatten at the end mirrors the bundled-vs-sideloaded asymmetry the host's loader keeps: the APK
 * copy ships one `widgets.json` (the representative live chart), where the `.gcplugin` carries the
 * whole `widgets/` directory (the chart plus the two DSL stat tiles).
 *
 * kotlin.test idiom: `assertEquals(expected, actual, message?)` — the message is LAST.
 */
class LocalGamingAdvisorPackageTest {

    private val def = localGamingAdvisor()

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "app").isDirectory && File(dir, "gamecore-plugin-sdk").isDirectory) return dir
            dir = dir.parentFile
        }
        error("repo root not found from ${File("").absolutePath}")
    }
    /** The section files the four sections emit, in author (call) order. */
    private val sectionPaths = listOf(
        "screens/screens.json",
        "widgets/advisor_fps.json",
        "widgets/advisor_load.json",
        "widgets/advisor_trend.json",
        "settings.json",
        "rules/rules.json",
    )

    private fun section(path: String): JSONObject =
        JSONObject(String(def.sectionFiles.getValue(path)))

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
        assertEquals(Sdk.FORMAT, m.getString("format"), "format matches the SDK tag")
        assertEquals("gamecore.plugin", m.getString("format"), "format")
        assertEquals("com.dreamucxe.gamecore.advisor", m.getString("id"), "id")
        assertEquals("Local Gaming Advisor", m.getString("name"), "name")
        assertEquals(2, m.getInt("version"), "version")
        assertEquals("1.1.0", m.getString("versionName"), "versionName")
        assertEquals(Sdk.API_V3, m.getInt("api"), "api is 3 — screens + widgets + settings are 3.x host features")
        assertEquals(3, m.getInt("api"), "api is literally 3, so the bump is not an alias drifting")
        assertEquals(
            Sdk.HOST_VERSION_CODE_ICON, m.getInt("minHostVersion"),
            "the icon section needs the 3.15.0 host (37)",
        )
        assertEquals(Sdk.HOST_VERSION_CODE_ICON, m.getInt("minHostVersion"), "minHostVersion is the icon host (37)")
        assertEquals("Dream", m.getString("developer"), "developer")
        assertEquals("Initial release.", m.getString("changelog"), "changelog")

        // Seven permissions. Only READ_APPROVED_DATA carries scopes; the other six carry none.
        val perms = m.getJSONArray("permissions")
        assertEquals(7, perms.length(), "exactly seven permissions")
        val byId = (0 until perms.length())
            .map { perms.getJSONObject(it) }
            .associateBy { it.getString("id") }
        assertEquals(
            setOf(
                "change_ui", "add_dashboard_widgets", "read_approved_data",
                "store_data", "run_automations", "system_actions", "change_icon",
            ),
            byId.keys,
            "the seven permissions the four sections, the cooldown rule and the icon need",
        )
        for (unscoped in listOf(
            "change_ui", "add_dashboard_widgets", "store_data", "run_automations", "system_actions",
            "change_icon",
        )) {
            assertTrue(!byId.getValue(unscoped).has("scopes"), "$unscoped carries no scopes")
        }
        val scopes = byId.getValue("read_approved_data").getJSONArray("scopes")
        assertEquals(
            setOf(DataScopes.DEVICE_STATS, DataScopes.THERMALS),
            (0 until scopes.length()).map { scopes.getString(it) }.toSet(),
            "read_approved_data opens exactly device_stats and thermals",
        )

        val declares = m.getJSONArray("declares")
        assertEquals(
            listOf("screens", "widgets", "settings", "rules", "icon"),
            (0 until declares.length()).map { declares.getString(it) },
            "declares screens, widgets, settings, rules in call order (section()'s widgets dedupes), then the icon",
        )
    }
    @Test
    fun emits_exactly_the_four_sections_files() {
        assertEquals(
            sectionPaths.toSet() + "icon.json",
            def.sectionFiles.keys.toSet(),
            "the four sections emit exactly their six files (two DSL stat tiles + one hand-emitted chart) plus icon.json",
        )
    }

    @Test
    fun dsl_widgets_are_read_only_stat_tiles() {
        for (id in listOf("advisor_fps", "advisor_load")) {
            val w = section("widgets/$id.json")
            assertEquals(Sdk.WIDGET_FORMAT, w.getString("format"), "$id format matches the SDK tag")
            assertEquals(Sdk.WIDGET_VERSION, w.getInt("version"), "$id version")
            assertEquals(id, w.getString("id"), "$id id matches its file name")
            assertTrue(w.getString("size") in WidgetSizes.ALL, "$id size is a known span")
            assertTrue(w.has("content"), "$id carries a single content root")
        }
    }
    @Test
    fun the_trend_widget_is_a_live_chart_with_a_literal_fallback() {
        // The one widget the DSL cannot build: a chart naming a live host feed. Authored by hand
        // through section(), it carries BOTH a live series (stat) and a literal fallback (values), so
        // the host plots the feed when thermals is granted and the sample series otherwise.
        val w = section("widgets/advisor_trend.json")
        assertEquals(Sdk.WIDGET_FORMAT, w.getString("format"), "trend widget format matches the SDK tag")
        assertEquals("gamecore.plugin.widget", w.getString("format"), "trend widget format")
        assertEquals(Sdk.WIDGET_VERSION, w.getInt("version"), "trend widget version")
        assertEquals("advisor_trend", w.getString("id"), "trend widget id")
        assertEquals(WidgetSizes.FULL, w.getString("size"), "the trend chart spans the full row")

        val content = w.getJSONObject("content")
        assertEquals("chart", content.getString("type"), "the trend widget's root is a chart node")
        val props = content.getJSONObject("props")
        assertEquals(ChartKinds.LINE, props.getString("kind"), "a line chart")
        assertEquals(OverlayStats.TEMP, props.getString("stat"), "the live series names the temperature feed")
        assertEquals(
            DataScopes.THERMALS, OverlayStats.scopeFor(props.getString("stat")),
            "temp rides the thermals aperture, which the manifest requests",
        )
        assertTrue(props.has("values"), "the chart also carries a literal fallback series")
        assertTrue(props.getString("values").isNotEmpty(), "the fallback series is non-empty")
    }

    @Test
    fun rules_are_unattended_broker_automations() {
        val rules = section("rules/rules.json").getJSONArray("rules")
        assertEquals(2, rules.length(), "two unattended rules")
        val byId = (0 until rules.length())
            .map { rules.getJSONObject(it) }
            .associateBy { it.getString("id") }
        assertEquals(setOf("thermal_cooldown", "battery_saver"), byId.keys, "the cooldown and the saver")
        for ((id, rule) in byId) {
            assertTrue(rule.getString("trigger") in Triggers.ALL, "$id names a known trigger")
            val actions = rule.getJSONArray("actions")
            assertTrue(
                actions.length() in 1..RuleBounds.MAX_ACTIONS_PER_RULE,
                "$id names 1..${RuleBounds.MAX_ACTIONS_PER_RULE} actions",
            )
            for (i in 0 until actions.length()) {
                assertTrue(actions.getString(i) in Actions.ALL, "$id action \"${actions.getString(i)}\" is a known broker action")
            }
        }
        // Trigger and action ids are copied verbatim from the broker's own vocabulary — the same ids
        // the Performance Pack's thermal and battery rules name.
        assertEquals("THERMAL_HIGH", byId.getValue("thermal_cooldown").getString("trigger"), "cooldown fires on the thermal trigger")
        assertEquals("BATTERY_LOW", byId.getValue("battery_saver").getString("trigger"), "the saver fires on the battery trigger")
    }
    @Test
    fun packages_the_deliverable_and_flattens_the_bundled_assets() {
        val outDir = File(repoRoot(), "dist/advisor").apply { mkdirs() }
        val out = File(outDir, "advisor.gcplugin")
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
            listOf("manifest.json") + sectionPaths + "icon.json",
            ordered,
            "manifest first, then each section file in author order",
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
        File(outDir, "advisor.gcplugin.sha256").writeText("$sha  advisor.gcplugin\n")
        println("ADVISOR_GCPLUGIN_SHA256=$sha")
        println("ADVISOR_GCPLUGIN_PATH=${out.absolutePath}")
        println("ADVISOR_GCPLUGIN_BYTES=${bytes.size}")
        assertEquals(64, sha.length, "sha-256 hex length")
        // Flatten into GameCore's bundled plugin assets. The host's bundled-asset path reads one file
        // per section named "<section>.json" (a section whose entry prefix is a directory ships flat),
        // and exactly one widgets.json per plugin where the sideloaded zip reads the whole widgets/
        // directory — so the APK copy ships the single representative live chart (the one the bundled
        // test reads back through the production dashboard codec) while the two stat tiles travel only
        // in the .gcplugin. That asymmetry is intentional and documented, not a gap.
        val assetsDir = File(repoRoot(), "app/src/main/assets/plugins/advisor").apply { mkdirs() }
        File(assetsDir, "manifest.json").writeBytes(def.manifestJson)
        val rep = "widgets/advisor_trend.json"
        def.sectionFiles.forEach { (path, sectionBytes) ->
            val flat = when {
                path == "screens/screens.json" -> "screens.json"
                path == "rules/rules.json" -> "rules.json"
                path.startsWith("widgets/") -> if (path == rep) "widgets.json" else null
                else -> path                 // settings.json
            } ?: return@forEach
            File(assetsDir, flat).writeBytes(sectionBytes)
        }
    }
}
