package com.gamecore.plugin.sdk

import com.gamecore.plugin.sdk.templates.benchmarkSuite
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Produces the deliverable `dist/benchmark/benchmark.gcplugin` from [benchmarkSuite] and proves the
 * authored bytes match the brief and the host's packaging limits — then flattens the package into
 * GameCore's bundled plugin assets.
 *
 * Benchmark Suite is a three-section plugin — a `pill`, two DSL `widgets` plus one hand-emitted
 * live-chart widget, and `screens` — so it reaches `api == 3` (the widgets and screens sections are
 * each API-3) while keeping `minHostVersion == 28` (the SDK default; nothing here needs a newer host).
 * Both are asserted below, as is the honest shape of the plugin: the pill draws **live stat nodes and
 * no chart** (the overlay audit refuses a live chart series), while the one chart that names a live
 * series rides as a **dashboard** widget, where the host validates and honours it.
 *
 * kotlin.test idiom: `assertEquals(expected, actual, message?)` — the message is LAST.
 */
class BenchmarkSuitePackageTest {

    private val def = benchmarkSuite()

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "app").isDirectory && File(dir, "gamecore-plugin-sdk").isDirectory) return dir
            dir = dir.parentFile
        }
        error("repo root not found from ${File("").absolutePath}")
    }

    /** Every `type` in a node tree, root first — used to prove the pill carries no chart node. */
    private fun typesIn(node: JSONObject): List<String> {
        val children = node.optJSONArray("children")
        return buildList {
            add(node.getString("type"))
            if (children != null) {
                for (i in 0 until children.length()) addAll(typesIn(children.getJSONObject(i)))
            }
        }
    }

    /** Every live `stat` id a node tree names, root first — proves which readings a section draws. */
    private fun statIdsIn(node: JSONObject): List<String> {
        val children = node.optJSONArray("children")
        return buildList {
            if (node.optString("type") == "stat") {
                node.optJSONObject("props")?.optString("stat")
                    ?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }
            if (children != null) {
                for (i in 0 until children.length()) addAll(statIdsIn(children.getJSONObject(i)))
            }
        }
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
        assertEquals("com.dreamucxe.gamecore.benchmark", m.getString("id"), "id")
        assertEquals("Benchmark Suite", m.getString("name"), "name")
        assertEquals(1, m.getInt("version"), "version")
        assertEquals("1.0.0", m.getString("versionName"), "versionName")
        assertEquals(3, m.getInt("api"), "api is 3 — widgets{} and screens{} are each API-3 sections")
        assertEquals(28, m.getInt("minHostVersion"), "minHostVersion stays the SDK default (28)")
        assertEquals("Dream", m.getString("developer"), "developer")
        assertEquals("Initial release.", m.getString("changelog"), "changelog")

        // Four permissions: one gate per section, plus the scoped read behind every live figure.
        val perms = m.getJSONArray("permissions")
        assertEquals(4, perms.length(), "exactly four permissions")
        val byId = (0 until perms.length())
            .map { perms.getJSONObject(it) }
            .associateBy { it.getString("id") }
        assertEquals(
            setOf("create_overlay", "add_dashboard_widgets", "read_approved_data", "change_ui"),
            byId.keys,
            "the four permissions, by id",
        )

        val read = byId.getValue("read_approved_data")
        val scopes = read.getJSONArray("scopes")
        assertEquals(
            setOf("device_stats", "thermals"),
            (0 until scopes.length()).map { scopes.getString(it) }.toSet(),
            "read_approved_data is scoped to device_stats (fps/cpu/gpu) and thermals (temp)",
        )
        // The three section-gating grants carry no scopes — scopes are meaningful only for the read.
        for (id in listOf("create_overlay", "add_dashboard_widgets", "change_ui")) {
            assertTrue(!byId.getValue(id).has("scopes"), "$id carries no scopes")
        }

        val declares = m.getJSONArray("declares")
        assertEquals(
            listOf("pill", "widgets", "screens"),
            (0 until declares.length()).map { declares.getString(it) },
            "declares pill, widgets and screens, in author order",
        )
    }

    @Test
    fun pill_is_live_stat_nodes_and_not_a_chart() {
        val p = JSONObject(String(def.sectionFiles.getValue("pill.json")))
        assertEquals(Sdk.PILL_FORMAT, p.getString("format"), "pill envelope format matches the SDK tag")
        assertEquals(Sdk.PILL_VERSION, p.getInt("version"), "pill version")
        assertEquals("bench-telemetry", p.getString("id"), "pill id")

        val content = p.getJSONObject("content")
        // The four readings the brief names, drawn as live stat nodes — device_stats covers
        // fps/cpu/gpu and thermals covers temp, both requested in the manifest above.
        assertEquals(
            setOf("fps", "cpu", "gpu", "temp"),
            statIdsIn(content).toSet(),
            "pill draws fps/cpu/gpu/temp as live stat nodes",
        )
        // No chart node: the host's overlay audit refuses a live chart series, so a telemetry pill
        // is stat nodes, never a chart. (The live chart lives in a dashboard widget instead.)
        assertTrue("chart" !in typesIn(content), "pill carries no chart node")
    }

    @Test
    fun live_chart_widget_names_a_stat_and_ships_a_fallback_series() {
        val w = JSONObject(String(def.sectionFiles.getValue("widgets/bench_trend.json")))
        assertEquals(Sdk.WIDGET_FORMAT, w.getString("format"), "widget envelope format matches the SDK tag")
        assertEquals(Sdk.WIDGET_VERSION, w.getInt("version"), "widget version")
        assertEquals("bench_trend", w.getString("id"), "widget id")
        assertEquals(WidgetSizes.FULL, w.getString("size"), "widget size is full")

        val node = w.getJSONObject("content")
        assertEquals("chart", node.getString("type"), "the widget root is a chart")
        val props = node.getJSONObject("props")
        // A live series the DSL's literal-only chart() cannot author: the dashboard codec validates
        // this `stat` against ModuleStat and folds it into the widget's scopes.
        assertEquals("fps", props.getString("stat"), "the chart series is the live fps feed")
        // The literal series is the fallback a host with no live resolver draws.
        assertTrue(props.getString("values").isNotBlank(), "the chart ships a literal fallback series")
    }

    @Test
    fun authored_widgets_split_live_readings_from_plain_text() {
        val snapshot = JSONObject(String(def.sectionFiles.getValue("widgets/bench_snapshot.json")))
        assertEquals("bench_snapshot", snapshot.getString("id"), "snapshot widget id")
        assertEquals(
            setOf("fps", "cpu", "gpu", "temp"),
            statIdsIn(snapshot.getJSONObject("content")).toSet(),
            "the snapshot widget draws the four live readings",
        )

        val about = JSONObject(String(def.sectionFiles.getValue("widgets/bench_about.json")))
        assertEquals("bench_about", about.getString("id"), "about widget id")
        assertTrue(
            statIdsIn(about.getJSONObject("content")).isEmpty(),
            "the about widget is plain text — no stat node, so it needs no data scope",
        )
    }

    @Test
    fun packages_the_deliverable_within_host_limits() {
        val outDir = File(repoRoot(), "dist/benchmark").apply { mkdirs() }
        val out = File(outDir, "benchmark.gcplugin")
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
                "pill.json",
                "widgets/bench_snapshot.json",
                "widgets/bench_about.json",
                "widgets/bench_trend.json",
                "screens/screens.json",
            ),
            ordered,
            "manifest first, then the sections in author order",
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
        File(outDir, "benchmark.gcplugin.sha256").writeText("$sha  benchmark.gcplugin\n")
        println("BENCHMARK_GCPLUGIN_SHA256=$sha")
        println("BENCHMARK_GCPLUGIN_PATH=${out.absolutePath}")
        println("BENCHMARK_GCPLUGIN_BYTES=${bytes.size}")
        assertEquals(64, sha.length, "sha-256 hex length")

        // Flatten into GameCore's bundled plugin assets so the host ships this plugin by default and
        // BenchmarkSuiteBundledTest can read it back through the production codecs. The live-chart
        // widget is the single bundled widget representative (bench_trend -> widgets.json); the two
        // DSL-authored widgets are not bundled, matching how the host loads one widget file per plugin.
        val assetsDir = File(repoRoot(), "app/src/main/assets/plugins/benchmark").apply { mkdirs() }
        File(assetsDir, "manifest.json").writeBytes(def.manifestJson)
        def.sectionFiles.forEach { (path, bytes) ->
            val flat = when {
                path == "pill.json" -> "pill.json"
                path == "screens/screens.json" -> "screens.json"
                path == "widgets/bench_trend.json" -> "widgets.json"
                path.startsWith("widgets/") -> null
                else -> path
            } ?: return@forEach
            File(assetsDir, flat).writeBytes(bytes)
        }
    }
}
