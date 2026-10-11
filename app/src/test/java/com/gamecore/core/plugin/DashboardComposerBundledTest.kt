package com.gamecore.core.plugin

import com.gamecore.core.plugin.dashboard.PluginDashboardCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled "Dashboard Composer" plugin is a real package, not a hand-waved one.
 *
 * This is the same gate [BundledPluginsTest] holds the theme/pill/rules plugins to, applied to the
 * platform's dashboard-widgets plugin: the asset files are read from disk and run through the
 * production [PluginManifestCodec] and [PluginDashboardCodec], so a typo in a bundled JSON fails the
 * build rather than shipping a broken default. (The SDK's `DashboardComposerPackageTest` regenerates
 * these same files from the authoring DSL; this test proves whatever is on disk still loads.)
 *
 * The bundled copy carries ONE representative card. The host's bundled-asset path reads exactly one
 * `widgets.json` per plugin (PluginRegistryImpl.bundledEntryName), where the sideloaded `.gcplugin`
 * reads the whole `widgets/` directory — so the APK ships `vitals-grid`, the one tile whose own
 * appetite is the entire requested scope union, and the rest of the deck travels only in the package.
 * That asymmetry is intentional and documented, not a gap.
 */
class DashboardComposerBundledTest {

    private val assets = File("src/main/assets/plugins")

    private fun read(path: String): String {
        val f = File(assets, path)
        assertTrue("missing bundled asset: ${f.path}", f.exists())
        return f.readText()
    }

    @Test
    fun `manifest parses as the dashboard-composer plugin`() {
        val decoded = PluginManifestCodec.decode(read("dashboardcomposer/manifest.json"))
        assertTrue("manifest did not parse: $decoded", decoded is PluginManifestCodec.Result.Parsed)
        decoded as PluginManifestCodec.Result.Parsed
        val m = decoded.manifest

        assertEquals("com.dreamucxe.gamecore.dashboardcomposer", m.id)
        // api 3 is the widgets section's doing: a dashboard widget is a 3.x host feature, so the
        // section raises the manifest to 3 and an older host rejects the package rather than loading
        // a card it has no renderer for.
        assertEquals(3, m.api)
        assertTrue("declares the widgets section", m.declares.contains(PluginSection.WIDGETS))

        // add_dashboard_widgets registers the cards; read_approved_data opens the two apertures the
        // live tiles read — the union of every stat node's scope across the deck.
        assertTrue(
            "requests add_dashboard_widgets",
            m.permissions.any { it.permission == PluginPermission.ADD_DASHBOARD_WIDGETS },
        )
        val read = m.permissions.firstOrNull { it.permission == PluginPermission.READ_APPROVED_DATA }
        assertTrue("requests read_approved_data", read != null)
        assertEquals(
            "read_approved_data opens exactly device_stats and thermals",
            setOf(DataScope.DEVICE_STATS.id, DataScope.THERMALS.id),
            read!!.scopes,
        )
    }

    @Test
    fun `the representative widget parses and asks only for scopes the manifest requests`() {
        val decoded = PluginDashboardCodec.decode(read("dashboardcomposer/widgets.json"))
        assertTrue("widget did not parse: $decoded", decoded is PluginDashboardCodec.Result.Parsed)
        decoded as PluginDashboardCodec.Result.Parsed
        val widget = decoded.widget

        // The bundled card is the representative vitals-grid, not just any tile from the deck.
        assertEquals("vitals-grid", widget.id)

        // The subset check the registry itself runs: a widget whose readings the manifest never
        // requested would render empty on a device, so the host refuses it. Proven here against the
        // production codecs so a drift between the card and its grants fails the build.
        val manifest = PluginManifestCodec.decode(read("dashboardcomposer/manifest.json"))
            as PluginManifestCodec.Result.Parsed
        assertTrue(
            "declares the widgets section",
            manifest.manifest.declares.contains(PluginSection.WIDGETS),
        )
        assertTrue(
            "requests add_dashboard_widgets",
            manifest.manifest.permissions.any { it.permission == PluginPermission.ADD_DASHBOARD_WIDGETS },
        )
        val requested = manifest.manifest.permissions
            .firstOrNull { it.permission == PluginPermission.READ_APPROVED_DATA }
            ?.scopes
            .orEmpty()
        assertEquals(
            "the representative card needs data scopes the manifest never requests",
            emptySet<String>(),
            widget.requiredScopes - requested,
        )

        // And the check is tight, not vacuous: the representative needs BOTH apertures on its own —
        // cpu/gpu/ram ride device_stats, temp rides thermals — so it is the whole requested union.
        assertEquals(setOf("device_stats", "thermals"), widget.requiredScopes)
    }
}
