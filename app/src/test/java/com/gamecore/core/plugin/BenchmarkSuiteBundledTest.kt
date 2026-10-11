package com.gamecore.core.plugin

import com.gamecore.core.plugin.dashboard.PluginDashboardCodec
import com.gamecore.core.plugin.overlay.ModuleStat
import com.gamecore.core.plugin.overlay.PluginOverlayCodec
import com.gamecore.core.plugin.ui.ComponentType
import com.gamecore.core.plugin.ui.PluginScreensCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled "Benchmark Suite" plugin is a real package, not a hand-waved one.
 *
 * This is the same gate [BundledPluginsTest] and [AutomationHistoryBundledTest] hold the other
 * bundled plugins to, applied to the platform's read-only telemetry visualizer and all three of its
 * sections: the asset files are read from disk and run through the production [PluginManifestCodec],
 * [PluginOverlayCodec], [PluginScreensCodec] and [PluginDashboardCodec], so a typo in a bundled JSON
 * fails the build rather than shipping a broken default. (The SDK's `BenchmarkSuitePackageTest`
 * regenerates these same files from the authoring DSL; this test proves whatever is on disk loads.)
 *
 * It also pins the plugin's honest shape through the host's own eyes: the pill decodes as live stat
 * nodes needing exactly device_stats + thermals, and the one chart that names a live series decodes
 * as a *dashboard* widget whose fps feed resolves to the device_stats scope — the overlay codec would
 * refuse that live series, which is why it rides on the dashboard surface instead.
 */
class BenchmarkSuiteBundledTest {

    private val assets = File("src/main/assets/plugins")

    private fun read(path: String): String {
        val f = File(assets, path)
        assertTrue("missing bundled asset: ${f.path}", f.exists())
        return f.readText()
    }

    @Test
    fun `manifest parses as the api-3 benchmark plugin`() {
        val decoded = PluginManifestCodec.decode(read("benchmark/manifest.json"))
        assertTrue("manifest did not parse: $decoded", decoded is PluginManifestCodec.Result.Parsed)
        decoded as PluginManifestCodec.Result.Parsed
        val m = decoded.manifest

        assertEquals("com.dreamucxe.gamecore.benchmark", m.id)
        // api 3 is carried by the widgets{} and screens{} sections; the pill draws no chart or motion
        // reading, so it does not bump the api on its own.
        assertEquals(3, m.api)

        // A three-section plugin: pill + widgets + screens, each named in declares.
        assertTrue("declares the pill section", m.declares.contains(PluginSection.PILL))
        assertTrue("declares the widgets section", m.declares.contains(PluginSection.WIDGETS))
        assertTrue("declares the screens section", m.declares.contains(PluginSection.SCREENS))

        // One grant gates each section, plus the scoped read behind every live figure.
        assertTrue(
            "requests create_overlay",
            m.permissions.any { it.permission == PluginPermission.CREATE_OVERLAY },
        )
        assertTrue(
            "requests add_dashboard_widgets",
            m.permissions.any { it.permission == PluginPermission.ADD_DASHBOARD_WIDGETS },
        )
        assertTrue(
            "requests change_ui",
            m.permissions.any { it.permission == PluginPermission.CHANGE_UI },
        )
        val read = m.permissions.firstOrNull { it.permission == PluginPermission.READ_APPROVED_DATA }
        assertTrue("requests read_approved_data", read != null)
        // Scoped to exactly the two apertures the live figures read: device_stats (fps/cpu/gpu) and
        // thermals (temp).
        assertTrue(
            "read_approved_data is scoped to device_stats (DataScope.DEVICE_STATS)",
            read!!.scopes.contains(DataScope.DEVICE_STATS.id),
        )
        assertTrue(
            "read_approved_data is scoped to thermals (DataScope.THERMALS)",
            read.scopes.contains(DataScope.THERMALS.id),
        )
    }

    @Test
    fun `pill decodes as live stat nodes needing device_stats and thermals`() {
        val decoded = PluginOverlayCodec.decode(read("benchmark/pill.json"))
        assertTrue("pill did not parse: $decoded", decoded is PluginOverlayCodec.Result.Parsed)
        decoded as PluginOverlayCodec.Result.Parsed

        // The pill draws fps/cpu/gpu (device_stats) and temp (thermals) as live stat nodes, so the
        // host folds exactly those two scopes out of it — the subset invariant the registry gates on,
        // and proof the overlay codec accepted the stat nodes (it would have refused a live chart).
        assertEquals(
            setOf(DataScope.DEVICE_STATS.id, DataScope.THERMALS.id),
            decoded.module.requiredScopes,
        )
    }

    @Test
    fun `screens parse through the host screens codec`() {
        val decoded = PluginScreensCodec.decode(read("benchmark/screens.json"))
        assertTrue("screens did not parse: $decoded", decoded is PluginScreensCodec.Result.Parsed)
        decoded as PluginScreensCodec.Result.Parsed
        assertEquals(
            listOf("visualizer", "about"),
            decoded.screens.map { it.id },
        )
    }

    @Test
    fun `bundled widget decodes as the live fps-trend chart`() {
        // The single bundled widget representative is the live-chart one the DSL cannot author: a
        // chart whose series names a live stat. The dashboard codec validates that `stat` against
        // ModuleStat and folds it into the widget's scopes — the opposite of the overlay audit.
        val decoded = PluginDashboardCodec.decode(read("benchmark/widgets.json"))
        assertTrue("widget did not parse: $decoded", decoded is PluginDashboardCodec.Result.Parsed)
        decoded as PluginDashboardCodec.Result.Parsed
        val widget = decoded.widget

        assertEquals("bench_trend", widget.id)
        assertEquals(ComponentType.CHART, widget.content.type)
        // The live series resolves to the fps feed, and so to exactly the device_stats scope.
        assertTrue("chart series is the live fps feed", widget.requiredStats.contains(ModuleStat.FPS))
        assertEquals(setOf(DataScope.DEVICE_STATS.id), widget.requiredScopes)
    }
}
