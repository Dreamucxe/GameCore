package com.gamecore.plugin.sdk

import com.gamecore.plugin.sdk.templates.graphicsTuning
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Produces the deliverable `dist/graphics/graphics.gcplugin` from [graphicsTuning] and proves the
 * authored bytes match the brief and the host's packaging limits, then flattens the packaged section
 * files into the bundled assets the app ships under `app/src/main/assets/plugins/graphics/`.
 *
 * Graphics Tuning is a data-only plugin across five sections — screens, pill, widgets, settings and
 * rules. The `screens { }`, `widgets { }` and `settings { }` blocks each raise the manifest api to
 * [Sdk.API_V3] (3); `pill { }` raises nothing here because the module draws neither a chart nor a
 * motion reading, and `rules { }` and the hand-emitted `section(...)` raise nothing either — so the
 * final api is 3, asserted below. `minHostVersion` keeps the SDK default (28), so the manifest and the
 * features it needs can never disagree.
 *
 * Two assertions carry more than their length. The first is the **scope subset**: every `stat` the
 * module and the tiles name must ride a [DataScopes] aperture the manifest actually requests, because
 * a module naming an ungranted scope parses perfectly and then renders nothing — the user is never
 * offered the grant, and no codec catches it. The second is that this plugin **names no action outside
 * the broker's own vocabulary**, which is what keeps a graphics *guide* from quietly implying it can
 * reach a game's renderer: the only things it changes are refresh-rate bounds, system animation scales
 * and low power, every one of them an id from [Actions].
 *
 * kotlin.test idiom: `assertEquals(expected, actual, message?)` — the message is LAST.
 */
class GraphicsTuningPackageTest {

    private val def = graphicsTuning()

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "app").isDirectory && File(dir, "gamecore-plugin-sdk").isDirectory) return dir
            dir = dir.parentFile
        }
        error("repo root not found from ${File("").absolutePath}")
    }

    /** The section files the five sections emit, in author (call) order. */
    private val sectionPaths = listOf(
        "screens/screens.json",
        "pill.json",
        "widgets/graphics_fps.json",
        "widgets/graphics_load.json",
        "widgets/graphics_trend.json",
        "settings.json",
        "rules/rules.json",
    )

    /** The scopes the manifest requests on `read_approved_data`. */
    private val requestedScopes = setOf(DataScopes.DEVICE_STATS, DataScopes.THERMALS)

    private fun section(path: String): JSONObject =
        JSONObject(String(def.sectionFiles.getValue(path)))

    /** Every `stat` id named anywhere in a node tree, walked iteratively. */
    private fun statsIn(root: JSONObject): Set<String> {
        val found = linkedSetOf<String>()
        val pending = ArrayDeque<JSONObject>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            val props = node.optJSONObject("props")
            if (props != null) {
                // A `stat` leaf and a live chart both carry the feed id under "stat".
                props.optString("stat").takeIf { it.isNotEmpty() }?.let { found += it }
            }
            val children = node.optJSONArray("children") ?: continue
            for (i in 0 until children.length()) pending.addLast(children.getJSONObject(i))
        }
        return found
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
        assertEquals(Sdk.FORMAT, m.getString("format"), "format matches the SDK tag")
        assertEquals("gamecore.plugin", m.getString("format"), "format")
        assertEquals("com.dreamucxe.gamecore.graphics", m.getString("id"), "id")
        assertEquals("Graphics Tuning", m.getString("name"), "name")
        assertEquals(1, m.getInt("version"), "version")
        assertEquals("1.0.0", m.getString("versionName"), "versionName")
        assertEquals(Sdk.API_V3, m.getInt("api"), "api is 3 — screens + widgets + settings are 3.x host features")
        assertEquals(3, m.getInt("api"), "api is literally 3, so the bump is not an alias drifting")
        assertEquals(
            Sdk.HOST_VERSION_CODE, m.getInt("minHostVersion"),
            "minHostVersion keeps the SDK default (28); the pill, the rules and the hand-emitted " +
                "chart raise nothing",
        )
        assertEquals(28, m.getInt("minHostVersion"), "minHostVersion is literally 28")
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
                "change_ui", "create_overlay", "add_dashboard_widgets", "read_approved_data",
                "store_data", "run_automations", "system_actions",
            ),
            byId.keys,
            "the seven permissions the five sections and the three rules need",
        )
        for (unscoped in listOf(
            "change_ui", "create_overlay", "add_dashboard_widgets", "store_data",
            "run_automations", "system_actions",
        )) {
            assertTrue(!byId.getValue(unscoped).has("scopes"), "$unscoped carries no scopes")
        }
        val scopes = byId.getValue("read_approved_data").getJSONArray("scopes")
        assertEquals(
            requestedScopes,
            (0 until scopes.length()).map { scopes.getString(it) }.toSet(),
            "read_approved_data opens exactly device_stats and thermals",
        )

        val declares = m.getJSONArray("declares")
        assertEquals(
            listOf("screens", "pill", "widgets", "settings", "rules"),
            (0 until declares.length()).map { declares.getString(it) },
            "declares the five sections in call order (section()'s widgets dedupes)",
        )
    }

    @Test
    fun emits_exactly_the_five_sections_files() {
        assertEquals(
            sectionPaths.toSet(),
            def.sectionFiles.keys.toSet(),
            "the five sections emit exactly their seven files (three widget files, one of them hand-emitted)",
        )
    }

    @Test
    fun the_three_screens_are_the_guide_the_probe_and_the_boundary() {
        val screens = section("screens/screens.json").getJSONArray("screens")
        assertEquals(3, screens.length(), "three screens")
        assertEquals(
            listOf("tuning", "textures", "limits"),
            (0 until screens.length()).map { screens.getJSONObject(it).getString("id") },
            "the guide, the texture explainer and the honest-limits screen, in that order",
        )
        for (i in 0 until screens.length()) {
            val screen = screens.getJSONObject(i)
            val id = screen.getString("id")
            assertTrue(screen.getString("title").isNotBlank(), "$id carries a title")
            val stats = statsIn(screen.getJSONObject("content"))
            for (stat in stats) {
                assertTrue(stat in OverlayStats.ALL, "screen $id names the known stat \"$stat\"")
                assertEquals(
                    emptySet<String>(),
                    setOfNotNull(OverlayStats.scopeFor(stat)) - requestedScopes,
                    "screen $id reads \"$stat\" through a scope the manifest requests",
                )
            }
        }
    }

    @Test
    fun the_module_reads_only_scopes_the_manifest_requests() {
        val pill = section("pill.json")
        assertEquals(Sdk.PILL_FORMAT, pill.getString("format"), "module format matches the SDK tag")
        assertEquals(Sdk.PILL_VERSION, pill.getInt("version"), "module version")
        assertEquals("graphics-probe", pill.getString("id"), "module id")
        assertEquals(OverlaySizes.MEDIUM, pill.getString("size"), "the probe is a medium module")

        // The load-bearing check: a module naming a stat whose scope the manifest never requests
        // parses perfectly and then renders nothing, because the user is never offered the grant.
        val stats = statsIn(pill.getJSONObject("content"))
        assertEquals(
            setOf(OverlayStats.FPS, OverlayStats.GPU, OverlayStats.TEMP),
            stats,
            "the probe draws frame rate, GPU load and temperature",
        )
        val needed = stats.mapNotNull { OverlayStats.scopeFor(it) }.toSet()
        assertEquals(
            emptySet<String>(),
            needed - requestedScopes,
            "the module needs data scopes the manifest never requests",
        )
    }

    @Test
    fun dsl_widgets_are_read_only_stat_tiles() {
        for (id in listOf("graphics_fps", "graphics_load")) {
            val w = section("widgets/$id.json")
            assertEquals(Sdk.WIDGET_FORMAT, w.getString("format"), "$id format matches the SDK tag")
            assertEquals(Sdk.WIDGET_VERSION, w.getInt("version"), "$id version")
            assertEquals(id, w.getString("id"), "$id id matches its file name")
            assertTrue(w.getString("size") in WidgetSizes.ALL, "$id size is a known span")
            assertTrue(w.has("content"), "$id carries a single content root")
            val needed = statsIn(w.getJSONObject("content")).mapNotNull { OverlayStats.scopeFor(it) }.toSet()
            assertEquals(emptySet<String>(), needed - requestedScopes, "$id reads only requested scopes")
        }
    }

    @Test
    fun the_trend_widget_is_a_live_chart_with_a_literal_fallback() {
        // The one widget the DSL cannot build: a chart naming a live host feed. Authored by hand
        // through section(), it carries BOTH a live series (stat) and a literal fallback (values), so
        // the host plots the feed when device_stats is granted and the sample series otherwise.
        val w = section("widgets/graphics_trend.json")
        assertEquals(Sdk.WIDGET_FORMAT, w.getString("format"), "trend widget format matches the SDK tag")
        assertEquals("gamecore.plugin.widget", w.getString("format"), "trend widget format")
        assertEquals(Sdk.WIDGET_VERSION, w.getInt("version"), "trend widget version")
        assertEquals("graphics_trend", w.getString("id"), "trend widget id")
        assertEquals(WidgetSizes.FULL, w.getString("size"), "the trend chart spans the full row")

        val content = w.getJSONObject("content")
        assertEquals("chart", content.getString("type"), "the trend widget's root is a chart node")
        val props = content.getJSONObject("props")
        assertEquals(ChartKinds.LINE, props.getString("kind"), "a line chart")
        assertEquals(OverlayStats.FPS, props.getString("stat"), "the live series names the frame-rate feed")
        assertEquals(
            DataScopes.DEVICE_STATS, OverlayStats.scopeFor(props.getString("stat")),
            "fps rides the device_stats aperture, which the manifest requests",
        )
        assertTrue(props.has("values"), "the chart also carries a literal fallback series")
        assertTrue(props.getString("values").isNotEmpty(), "the fallback series is non-empty")
        assertTrue(
            props.getString("values").length <= ChartBounds.MAX_VALUE_STRING_LENGTH,
            "the hand-emitted series fits the host's tightest string cap " +
                "(${ChartBounds.MAX_VALUE_STRING_LENGTH}); the DSL's bounds check does not run here",
        )
    }

    @Test
    fun settings_are_the_preferences_the_guidance_is_phrased_against() {
        val defs = section("settings.json").getJSONArray("settings")
        assertEquals(
            listOf("target_fps", "warn_temp_c", "device_tier", "guide_detail", "show_trend_widget"),
            (0 until defs.length()).map { defs.getJSONObject(it).getString("id") },
            "five typed settings in author order",
        )
        for (i in 0 until defs.length()) {
            val d = defs.getJSONObject(i)
            assertTrue(
                d.getString("type") in SettingTypes.ALL,
                "${d.getString("id")} declares a known setting type",
            )
        }
    }

    @Test
    fun rules_name_only_the_brokers_own_actions() {
        val rules = section("rules/rules.json").getJSONArray("rules")
        assertEquals(3, rules.length(), "three unattended rules")
        val byId = (0 until rules.length())
            .map { rules.getJSONObject(it) }
            .associateBy { it.getString("id") }
        assertEquals(
            setOf("launch_boost", "sustain_fps", "trim_system_animations"),
            byId.keys,
            "the launch boost, the thermal sustain and the animation trim",
        )
        for ((id, rule) in byId) {
            assertTrue(rule.getString("trigger") in Triggers.ALL, "$id names a known trigger")
            val actions = rule.getJSONArray("actions")
            assertTrue(
                actions.length() in 1..RuleBounds.MAX_ACTIONS_PER_RULE,
                "$id names 1..${RuleBounds.MAX_ACTIONS_PER_RULE} actions",
            )
            // The whole honesty of a "graphics" plugin rests here: there is no action outside the
            // broker's vocabulary, so nothing in this package can reach a game's renderer.
            for (i in 0 until actions.length()) {
                assertTrue(
                    actions.getString(i) in Actions.ALL,
                    "$id action \"${actions.getString(i)}\" is a known broker action",
                )
            }
        }
        assertEquals("GAME_LAUNCHED", byId.getValue("launch_boost").getString("trigger"), "the boost fires on launch")
        assertTrue(
            !byId.getValue("launch_boost").has("conditions"),
            "the launch boost is unconditional, like the Performance Pack's own launch rule",
        )
        assertEquals("THERMAL_HIGH", byId.getValue("sustain_fps").getString("trigger"), "sustain fires on the thermal trigger")
        assertEquals("SESSION_STARTED", byId.getValue("trim_system_animations").getString("trigger"), "the trim fires on session start")
    }

    @Test
    fun packages_the_deliverable_and_flattens_the_bundled_assets() {
        val outDir = File(repoRoot(), "dist/graphics").apply { mkdirs() }
        val out = File(outDir, "graphics.gcplugin")
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
            listOf("manifest.json") + sectionPaths,
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
        File(outDir, "graphics.gcplugin.sha256").writeText("$sha  graphics.gcplugin\n")
        println("GRAPHICS_GCPLUGIN_SHA256=$sha")
        println("GRAPHICS_GCPLUGIN_PATH=${out.absolutePath}")
        println("GRAPHICS_GCPLUGIN_BYTES=${bytes.size}")
        assertEquals(64, sha.length, "sha-256 hex length")

        // Flatten into GameCore's bundled plugin assets. The host's bundled loader reads one file per
        // declared section, named from that section's entry prefix — a prefix that is a directory
        // (`screens/`, `rules/`, `widgets/`) ships as `<section>.json`, and a prefix that is already a
        // file name (`pill.json`, `settings.json`) ships unchanged. A declared section whose flat file
        // is missing makes the whole bundled plugin vanish silently, so every one of the five is
        // written here, and exactly one widgets.json travels in the APK (the representative live
        // chart) where the .gcplugin carries the whole widgets/ directory.
        val assetsDir = File(repoRoot(), "app/src/main/assets/plugins/graphics").apply { mkdirs() }
        File(assetsDir, "manifest.json").writeBytes(def.manifestJson)
        val rep = "widgets/graphics_trend.json"
        val flattened = linkedSetOf<String>()
        def.sectionFiles.forEach { (path, sectionBytes) ->
            val flat = when {
                path == "screens/screens.json" -> "screens.json"
                path == "rules/rules.json" -> "rules.json"
                path.startsWith("widgets/") -> if (path == rep) "widgets.json" else null
                else -> path                 // pill.json, settings.json
            } ?: return@forEach
            File(assetsDir, flat).writeBytes(sectionBytes)
            flattened += flat
        }
        assertEquals(
            setOf("screens.json", "pill.json", "widgets.json", "settings.json", "rules.json"),
            flattened,
            "one flat file per declared section — a missing one makes the bundled plugin vanish silently",
        )
    }
}
