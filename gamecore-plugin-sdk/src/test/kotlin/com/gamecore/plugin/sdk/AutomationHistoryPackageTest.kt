package com.gamecore.plugin.sdk

import com.gamecore.plugin.sdk.templates.automationHistory
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Produces the deliverable `dist/automationhistory/automationhistory.gcplugin` from
 * [automationHistory] and proves the authored bytes match the brief and the host's packaging
 * limits — then flattens the package into GameCore's bundled plugin assets.
 *
 * This is the platform's one api-4 plugin: the `automation_history` read scope is an API-4 meaning
 * that no section bumps for you, so the manifest must carry `api == 4` and `minHostVersion == 29`
 * by the template's own hand. Both are asserted below.
 *
 * kotlin.test idiom: `assertEquals(expected, actual, message?)` — the message is LAST.
 */
class AutomationHistoryPackageTest {

    private val def = automationHistory()

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "app").isDirectory && File(dir, "gamecore-plugin-sdk").isDirectory) return dir
            dir = dir.parentFile
        }
        error("repo root not found from ${File("").absolutePath}")
    }

    /** Every `type` in a node tree, root first — used to prove the screens are a static explainer. */
    private fun typesIn(node: JSONObject): List<String> {
        val children = node.optJSONArray("children")
        return buildList {
            add(node.getString("type"))
            if (children != null) {
                for (i in 0 until children.length()) addAll(typesIn(children.getJSONObject(i)))
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
        assertEquals("com.dreamucxe.gamecore.automationhistory", m.getString("id"), "id")
        assertEquals("Automation History", m.getString("name"), "name")
        assertEquals(1, m.getInt("version"), "version")
        assertEquals("1.0.0", m.getString("versionName"), "versionName")
        assertEquals(4, m.getInt("api"), "api is 4 — the automation_history scope is an API-4 meaning")
        assertEquals(29, m.getInt("minHostVersion"), "minHostVersion is 29 — the scope requires host 29")
        assertEquals("Dream", m.getString("developer"), "developer")
        assertEquals("Initial release.", m.getString("changelog"), "changelog")

        // Two permissions: the scoped automation-history read, and change_ui for the screens.
        val perms = m.getJSONArray("permissions")
        assertEquals(2, perms.length(), "exactly two permissions")
        val byId = (0 until perms.length())
            .map { perms.getJSONObject(it) }
            .associateBy { it.getString("id") }

        val read = byId.getValue("read_approved_data")
        val scopes = read.getJSONArray("scopes")
        assertEquals(
            setOf("automation_history"),
            (0 until scopes.length()).map { scopes.getString(it) }.toSet(),
            "read_approved_data is scoped to automation_history",
        )

        val changeUi = byId.getValue("change_ui")
        assertTrue(!changeUi.has("scopes"), "change_ui carries no scopes")

        val declares = m.getJSONArray("declares")
        assertEquals(1, declares.length(), "declares exactly one section")
        assertEquals("screens", declares.getString(0), "declares screens")
    }

    @Test
    fun screens_are_a_static_explainer_set() {
        val s = JSONObject(String(def.sectionFiles.getValue("screens/screens.json")))
        assertEquals("gamecore.plugin.screens", s.getString("format"), "screens envelope format")
        assertEquals(Sdk.SCREENS_FORMAT, s.getString("format"), "screens format matches the SDK tag")
        assertEquals(Sdk.SCREENS_VERSION, s.getInt("version"), "screens version")

        val screens = s.getJSONArray("screens")
        assertEquals(3, screens.length(), "three explainer screens")
        val ids = (0 until screens.length()).map { screens.getJSONObject(it).getString("id") }
        assertEquals(listOf("overview", "how-it-fills", "about"), ids, "screen ids, in author order")

        // Every screen has a title and a single card root, and the whole set uses only the four
        // static component types. No `stat` (which draws absent and would need a data scope we
        // never request) and no inert control — "static explainer" proven here, not just claimed.
        val allowed = setOf("card", "grid", "text", "image")
        for (i in 0 until screens.length()) {
            val screen = screens.getJSONObject(i)
            val id = screen.getString("id")
            assertTrue(screen.getString("title").isNotBlank(), "screen $id has a title")
            val content = screen.getJSONObject("content")
            assertEquals("card", content.getString("type"), "screen $id has a single card root")
            assertEquals(
                emptySet<String>(),
                typesIn(content).toSet() - allowed,
                "screen $id uses only card/grid/text/image",
            )
        }
    }

    @Test
    fun packages_the_deliverable_within_host_limits() {
        val outDir = File(repoRoot(), "dist/automationhistory").apply { mkdirs() }
        val out = File(outDir, "automationhistory.gcplugin")
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
            listOf("manifest.json", "screens/screens.json"),
            ordered,
            "manifest first, then the screens section",
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
        File(outDir, "automationhistory.gcplugin.sha256").writeText("$sha  automationhistory.gcplugin\n")
        println("AUTOMATIONHISTORY_GCPLUGIN_SHA256=$sha")
        println("AUTOMATIONHISTORY_GCPLUGIN_PATH=${out.absolutePath}")
        println("AUTOMATIONHISTORY_GCPLUGIN_BYTES=${bytes.size}")
        assertEquals(64, sha.length, "sha-256 hex length")

        // Flatten into GameCore's bundled plugin assets so the host ships this plugin by default and
        // AutomationHistoryBundledTest can read it back through the production codecs.
        val assetsDir = File(repoRoot(), "app/src/main/assets/plugins/automationhistory").apply { mkdirs() }
        File(assetsDir, "manifest.json").writeBytes(def.manifestJson)
        def.sectionFiles.forEach { (path, bytes) ->
            val flat = when {
                path == "screens/screens.json" -> "screens.json"
                path == "rules/rules.json" -> "rules.json"
                path.startsWith("widgets/") -> null
                else -> path
            } ?: return@forEach
            File(assetsDir, flat).writeBytes(bytes)
        }
    }
}
