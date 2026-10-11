package com.gamecore.plugin.sdk

import com.gamecore.plugin.sdk.templates.dashboardComposer
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Produces the deliverable `dist/dashboardcomposer/dashboardcomposer.gcplugin` from
 * [dashboardComposer] and proves the authored bytes match the brief and the host's packaging
 * limits — then flattens the ONE representative card into GameCore's bundled plugin assets.
 *
 * The deck is a data-only `widgets` section: eight read-only cards the host draws with its own
 * Compose renderer. The `widgets { }` section raises the manifest api to [Sdk.API_V3] (3) and
 * declares the section, both asserted below; `minHostVersion` stays the SDK default (28), so the
 * manifest and the feature it needs can never disagree.
 *
 * The flatten at the end is where the bundled-vs-sideloaded asymmetry is made real: the host's
 * bundled-asset path reads exactly one `widgets.json` per plugin, where the sideloaded zip reads
 * the whole `widgets/` directory. So the APK copy ships the single representative card and the
 * `.gcplugin` carries the full deck. [REP_WIDGET_ID] is that card — `vitals-grid`, the one tile
 * whose own appetite is the whole requested scope union.
 *
 * kotlin.test idiom: `assertEquals(expected, actual, message?)` — the message is LAST.
 */
class DashboardComposerPackageTest {

    private val def = dashboardComposer()

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "app").isDirectory && File(dir, "gamecore-plugin-sdk").isDirectory) return dir
            dir = dir.parentFile
        }
        error("repo root not found from ${File("").absolutePath}")
    }

    /** The deck's widget ids, in author order — the order the section files and zip entries keep. */
    private val widgetIds = listOf(
        "fps-tile", "vitals-grid", "thermal-tile", "fps-history",
        "load-bars", "welcome-card", "perf-row", "legend-note",
    )

    private fun widgetFile(id: String): JSONObject =
        JSONObject(String(def.sectionFiles.getValue("widgets/$id.json")))

    /** Every `type` in a node tree, root first — used to prove the deck is read-only. */
    private fun typesIn(node: JSONObject): List<String> {
        val children = node.optJSONArray("children")
        return buildList {
            add(node.getString("type"))
            if (children != null) {
                for (i in 0 until children.length()) addAll(typesIn(children.getJSONObject(i)))
            }
        }
    }

    /** Every live stat id a node tree names, root first — the deck's data appetite, SDK-side. */
    private fun statIdsIn(node: JSONObject): List<String> {
        val here = if (node.getString("type") == "stat") {
            listOfNotNull(node.optJSONObject("props")?.optString("stat")?.takeIf { it.isNotEmpty() })
        } else {
            emptyList()
        }
        val children = node.optJSONArray("children") ?: return here
        return here + (0 until children.length()).flatMap { statIdsIn(children.getJSONObject(it)) }
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
        assertEquals("com.dreamucxe.gamecore.dashboardcomposer", m.getString("id"), "id")
        assertEquals("Dashboard Composer", m.getString("name"), "name")
        assertEquals(1, m.getInt("version"), "version")
        assertEquals("1.0.0", m.getString("versionName"), "versionName")
        assertEquals(Sdk.API_V3, m.getInt("api"), "api is 3 — the widgets section is a 3.x host feature")
        assertEquals(3, m.getInt("api"), "api is literally 3, so the bump is not an alias drifting")
        assertEquals(
            Sdk.HOST_VERSION_CODE, m.getInt("minHostVersion"),
            "minHostVersion keeps the SDK default (28); the api bump is the widgets section's job alone",
        )
        assertEquals(28, m.getInt("minHostVersion"), "minHostVersion is literally 28")
        assertEquals("Dream", m.getString("developer"), "developer")
        assertEquals("Initial release.", m.getString("changelog"), "changelog")

        // Two permissions: ADD_DASHBOARD_WIDGETS registers the cards; READ_APPROVED_DATA opens the
        // two apertures the live tiles read — the union of every stat node's scope across the deck.
        val perms = m.getJSONArray("permissions")
        assertEquals(2, perms.length(), "exactly two permissions")
        val byId = (0 until perms.length())
            .map { perms.getJSONObject(it) }
            .associateBy { it.getString("id") }

        val add = byId.getValue("add_dashboard_widgets")
        assertTrue(!add.has("scopes"), "add_dashboard_widgets carries no scopes")

        val read = byId.getValue("read_approved_data")
        val scopes = read.getJSONArray("scopes")
        assertEquals(
            setOf(DataScopes.DEVICE_STATS, DataScopes.THERMALS),
            (0 until scopes.length()).map { scopes.getString(it) }.toSet(),
            "read_approved_data opens exactly the two apertures the deck's live tiles read",
        )

        val declares = m.getJSONArray("declares")
        assertEquals(1, declares.length(), "declares exactly one section")
        assertEquals("widgets", declares.getString(0), "declares widgets")
    }

    @Test
    fun widgets_are_a_varied_read_only_deck() {
        // One widgets/<id>.json per card, and only those — nothing extra on disk, nothing declared
        // with no file behind it.
        assertEquals(
            widgetIds.map { "widgets/$it.json" }.toSet(),
            def.sectionFiles.keys.toSet(),
            "the deck emits exactly its eight widget files",
        )
        assertEquals(8, widgetIds.size, "the deck is eight cards — varied, and well under the cap of 16")
        assertTrue(widgetIds.size <= WidgetBounds.MAX_WIDGETS, "the deck stays within the per-plugin cap")

        val sizes = LinkedHashSet<String>()
        val allTypes = LinkedHashSet<String>()
        for (id in widgetIds) {
            val w = widgetFile(id)
            assertEquals(Sdk.WIDGET_FORMAT, w.getString("format"), "$id format matches the SDK tag")
            assertEquals("gamecore.plugin.widget", w.getString("format"), "$id format")
            assertEquals(Sdk.WIDGET_VERSION, w.getInt("version"), "$id version")
            assertEquals(id, w.getString("id"), "$id id matches its file name")
            assertTrue(w.getString("size") in WidgetSizes.ALL, "$id size is a known span")
            assertTrue(w.has("content"), "$id carries a single content root")
            val root = w.getJSONObject("content")
            assertTrue(root.getString("type").isNotEmpty(), "$id content root is a typed node")
            sizes += w.getString("size")
            allTypes += typesIn(root)
        }

        // Varied: the deck spans every widget size.
        assertEquals(WidgetSizes.ALL, sizes, "the deck spans every widget size (small/medium/large/full)")

        // Read-only by construction: the three interactive leaves and the scroll container cannot
        // appear, because the WidgetBuilder scope has no function to emit them. Asserted anyway, so the
        // read-only contract is proven from the shipped files rather than only from the DSL's shape.
        assertEquals(
            emptySet<String>(),
            allTypes.intersect(setOf("button", "slider", "switch", "scroll")),
            "a widget dispatches nothing, so no interactive node (or scroll) can appear in the deck",
        )
        assertEquals(
            emptySet<String>(),
            allTypes - setOf("column", "row", "grid", "card", "text", "stat", "spacer", "image", "chart"),
            "the deck uses only the nine read-only overlay components",
        )
        // Varied node shapes: live stats, a grid, literal charts, images and plain text all appear.
        assertTrue(
            allTypes.containsAll(setOf("stat", "grid", "chart", "image", "text")),
            "the deck mixes stat tiles, a grid, literal charts, an icon and text cards",
        )
    }

    @Test
    fun the_decks_appetite_is_derived_and_the_representative_needs_both_scopes() {
        // The union of every live stat across the deck, each mapped to the scope it rides behind.
        // This is the SDK-side mirror of the host's PluginDashboardWidget.requiredScopes, and it must
        // equal exactly what the manifest requests — a tile reading a scope the manifest never asked
        // for would render empty on a device, which no codec catches.
        val deckScopes = widgetIds
            .flatMap { statIdsIn(widgetFile(it).getJSONObject("content")) }
            .mapNotNull { OverlayStats.scopeFor(it) }
            .toSet()
        assertEquals(
            setOf(DataScopes.DEVICE_STATS, DataScopes.THERMALS),
            deckScopes,
            "the deck's live tiles read exactly device_stats and thermals — the requested union",
        )

        // The representative card alone exercises BOTH apertures, which is why it is the one the
        // bundled copy ships: cpu/gpu/ram ride device_stats, temp rides thermals.
        val repScopes = statIdsIn(widgetFile(REP_WIDGET_ID).getJSONObject("content"))
            .mapNotNull { OverlayStats.scopeFor(it) }
            .toSet()
        assertEquals(
            setOf(DataScopes.DEVICE_STATS, DataScopes.THERMALS),
            repScopes,
            "the representative $REP_WIDGET_ID needs both requested scopes on its own",
        )
    }

    @Test
    fun packages_the_deliverable_and_flattens_the_representative() {
        val outDir = File(repoRoot(), "dist/dashboardcomposer").apply { mkdirs() }
        val out = File(outDir, "dashboardcomposer.gcplugin")
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
            listOf("manifest.json") + widgetIds.map { "widgets/$it.json" },
            ordered,
            "manifest first, then each widget file in author order",
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
        File(outDir, "dashboardcomposer.gcplugin.sha256").writeText("$sha  dashboardcomposer.gcplugin\n")
        println("DASHBOARDCOMPOSER_GCPLUGIN_SHA256=$sha")
        println("DASHBOARDCOMPOSER_GCPLUGIN_PATH=${out.absolutePath}")
        println("DASHBOARDCOMPOSER_GCPLUGIN_BYTES=${bytes.size}")
        assertEquals(64, sha.length, "sha-256 hex length")

        // Flatten into GameCore's bundled plugin assets. The host's bundled-asset path reads exactly
        // one widgets.json per plugin (PluginRegistryImpl.bundledEntryName), where the sideloaded zip
        // reads the whole widgets/ directory — so the APK copy ships the single representative card and
        // DashboardComposerBundledTest reads it back through the production codec. The rest of the deck
        // travels only in the .gcplugin. That asymmetry is intentional and documented, not a gap.
        val assetsDir = File(repoRoot(), "app/src/main/assets/plugins/dashboardcomposer").apply { mkdirs() }
        File(assetsDir, "manifest.json").writeBytes(def.manifestJson)
        File(assetsDir, "widgets.json").writeBytes(def.sectionFiles.getValue("widgets/$REP_WIDGET_ID.json"))
    }

    companion object {
        /**
         * The one card the bundled APK copy ships. `vitals-grid` is the representative because its own
         * appetite — cpu/gpu/ram on device_stats, temp on thermals — is the whole requested scope union,
         * so the single bundled tile exercises every aperture the manifest asks for.
         */
        private const val REP_WIDGET_ID = "vitals-grid"
    }
}
