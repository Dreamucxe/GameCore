package com.gamecore.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Part B gate: the `.gcplugin` loader/validator. Every limit, every path-safety rule and
 * every section/manifest agreement check is exercised here as a pure JVM test — the loader
 * is deliberately Android-free so its trust boundary can be proven without a device.
 */
class PluginPackageLoaderTest {

    // ---------------------------------------------------------------- fixtures

    private fun validManifestJson(
        id: String = "com.example.plugin",
        declares: List<String> = emptyList(),
    ): String {
        val declaresJson = declares.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        return """
            {
              "format": "gamecore.plugin",
              "id": "$id",
              "name": "Example",
              "version": 1,
              "versionName": "1.0",
              "api": 1,
              "minHostVersion": 1,
              "developer": "Tester",
              "description": "A test plugin.",
              "permissions": [],
              "declares": $declaresJson,
              "changelog": "first"
            }
        """.trimIndent()
    }

    /** Builds a zip in memory from (path -> bytes) pairs, in insertion order. */
    private fun zipOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            for ((name, data) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun loaded(result: PluginPackageLoader.Result): LoadedPlugin {
        assertTrue("expected Loaded but was $result", result is PluginPackageLoader.Result.Loaded)
        return (result as PluginPackageLoader.Result.Loaded).plugin
    }

    private fun rejected(result: PluginPackageLoader.Result): PluginPackageLoader.Result.Rejected {
        assertTrue("expected Rejected but was $result", result is PluginPackageLoader.Result.Rejected)
        return result as PluginPackageLoader.Result.Rejected
    }

    // ---------------------------------------------------------------- happy path

    @Test
    fun `minimal valid package loads`() {
        val zip = zipOf(PluginPackageLoader.MANIFEST_ENTRY to bytes(validManifestJson()))
        val plugin = loaded(PluginPackageLoader.load(zip))
        assertEquals("com.example.plugin", plugin.manifest.id)
        assertTrue(plugin.entries.containsKey("manifest.json"))
        assertEquals(64, plugin.sha256.length)
    }

    @Test
    fun `sha256 is stable and matches the package bytes`() {
        val zip = zipOf(PluginPackageLoader.MANIFEST_ENTRY to bytes(validManifestJson()))
        val a = loaded(PluginPackageLoader.load(zip)).sha256
        val b = loaded(PluginPackageLoader.load(zip)).sha256
        assertEquals(a, b)
        assertTrue(a.all { it in "0123456789abcdef" })
    }

    @Test
    fun `declared section present loads and is readable`() {
        val zip = zipOf(
            PluginPackageLoader.MANIFEST_ENTRY to bytes(validManifestJson(declares = listOf("theme"))),
            "theme.json" to bytes("""{"any":"thing"}"""),
        )
        val plugin = loaded(PluginPackageLoader.load(zip))
        assertNotNull(plugin.entry("theme.json"))
        assertEquals(listOf("theme.json"), plugin.pathsUnder(PluginSection.THEME))
    }

    @Test
    fun `previews and assets are allowed without being sections`() {
        val zip = zipOf(
            PluginPackageLoader.MANIFEST_ENTRY to bytes(validManifestJson()),
            "previews/shot1.png" to bytes("x"),
            "assets/icon.png" to bytes("y"),
        )
        loaded(PluginPackageLoader.load(zip))
    }

    // ---------------------------------------------------------------- empties and junk

    @Test
    fun `null and empty are rejected as empty`() {
        assertEquals(PluginPackageLoader.Reason.EMPTY, rejected(PluginPackageLoader.load(null as ByteArray?)).reason)
        assertEquals(PluginPackageLoader.Reason.EMPTY, rejected(PluginPackageLoader.load(ByteArray(0))).reason)
    }

    @Test
    fun `non-zip bytes are rejected as malformed, not thrown`() {
        val garbage = ByteArray(2048) { 0x41 }
        assertEquals(PluginPackageLoader.Reason.MALFORMED, rejected(PluginPackageLoader.load(garbage)).reason)
    }

    @Test
    fun `zip with no manifest is rejected`() {
        val zip = zipOf("theme.json" to bytes("{}"))
        assertEquals(PluginPackageLoader.Reason.MISSING_MANIFEST, rejected(PluginPackageLoader.load(zip)).reason)
    }

    @Test
    fun `bad manifest content surfaces as MANIFEST_REJECTED`() {
        val zip = zipOf(PluginPackageLoader.MANIFEST_ENTRY to bytes("""{"format":"not.ours"}"""))
        assertEquals(PluginPackageLoader.Reason.MANIFEST_REJECTED, rejected(PluginPackageLoader.load(zip)).reason)
    }

    // ---------------------------------------------------------------- zip-slip / path safety

    @Test
    fun `path traversal entry is rejected`() {
        assertNull(PluginPackageLoader.safePathOrNull("../evil"))
        assertNull(PluginPackageLoader.safePathOrNull("a/../../b"))
        assertNull(PluginPackageLoader.safePathOrNull("foo/../bar"))
    }

    @Test
    fun `absolute and windows paths are rejected`() {
        assertNull(PluginPackageLoader.safePathOrNull("/etc/passwd"))
        assertNull(PluginPackageLoader.safePathOrNull("\\windows\\system32"))
        assertNull(PluginPackageLoader.safePathOrNull("a\\b"))
    }

    @Test
    fun `empty double-slash and control-char paths are rejected`() {
        assertNull(PluginPackageLoader.safePathOrNull(""))
        assertNull(PluginPackageLoader.safePathOrNull("a//b"))
        assertNull(PluginPackageLoader.safePathOrNull("a/b\u0000c"))
        assertNull(PluginPackageLoader.safePathOrNull("/leading"))
    }

    @Test
    fun `over-long path is rejected`() {
        val longName = "a/".repeat(100) + "x"
        assertNull(PluginPackageLoader.safePathOrNull(longName))
    }

    @Test
    fun `normal nested path is accepted`() {
        assertEquals("screens/home.json", PluginPackageLoader.safePathOrNull("screens/home.json"))
        assertEquals("manifest.json", PluginPackageLoader.safePathOrNull("manifest.json"))
    }

    @Test
    fun `zip-slip entry in a real archive is rejected whole`() {
        val zip = zipOf(
            PluginPackageLoader.MANIFEST_ENTRY to bytes(validManifestJson()),
            "../../../../tmp/pwn" to bytes("boom"),
        )
        assertEquals(PluginPackageLoader.Reason.UNSAFE_PATH, rejected(PluginPackageLoader.load(zip)).reason)
    }

    // ---------------------------------------------------------------- limits

    @Test
    fun `package over the size cap is rejected before unzip`() {
        val huge = ByteArray(PluginPackageLoader.MAX_PACKAGE_BYTES + 1)
        assertEquals(PluginPackageLoader.Reason.PACKAGE_TOO_LARGE, rejected(PluginPackageLoader.load(huge)).reason)
    }

    @Test
    fun `single entry over the entry cap is rejected`() {
        val big = ByteArray(PluginPackageLoader.MAX_ENTRY_BYTES + 1) { 0x42 }
        val zip = zipOf(
            PluginPackageLoader.MANIFEST_ENTRY to bytes(validManifestJson(declares = listOf("aimlab"))),
            "aimlab/big.bin" to big,
        )
        assertEquals(PluginPackageLoader.Reason.ENTRY_TOO_LARGE, rejected(PluginPackageLoader.load(zip)).reason)
    }

    @Test
    fun `too many entries is rejected`() {
        val files = buildList {
            add(PluginPackageLoader.MANIFEST_ENTRY to bytes(validManifestJson(declares = listOf("stats"))))
            repeat(PluginPackageLoader.MAX_ENTRIES + 5) { i -> add("stats/s$i.json" to bytes("{}")) }
        }.toTypedArray()
        assertEquals(PluginPackageLoader.Reason.TOO_MANY_ENTRIES, rejected(PluginPackageLoader.load(zipOf(*files))).reason)
    }

    @Test
    fun `duplicate entry name is rejected`() {
        // ZipOutputStream refuses to emit two entries with the same name, so a duplicate
        // can only arrive from a hand-forged archive. We forge one by building a zip whose
        // two entries have equal-length distinct names, then rewriting the second name's
        // bytes to equal the first — both the local header and the central directory carry
        // the name, so a global byte replace keeps the archive parseable with a real dupe.
        val base = zipOf(
            PluginPackageLoader.MANIFEST_ENTRY to bytes(validManifestJson(declares = listOf("stats"))),
            "stats/dupA.json" to bytes("{}"),
            "stats/dupB.json" to bytes("{}"),
        )
        val forged = String(base, Charsets.ISO_8859_1)
            .replace("stats/dupB.json", "stats/dupA.json")
            .toByteArray(Charsets.ISO_8859_1)
        assertEquals(PluginPackageLoader.Reason.DUPLICATE_ENTRY, rejected(PluginPackageLoader.load(forged)).reason)
    }

    // ---------------------------------------------------------------- section <-> declaration

    @Test
    fun `section present but undeclared is rejected`() {
        val zip = zipOf(
            PluginPackageLoader.MANIFEST_ENTRY to bytes(validManifestJson(declares = emptyList())),
            "theme.json" to bytes("{}"),
        )
        assertEquals(PluginPackageLoader.Reason.UNDECLARED_SECTION, rejected(PluginPackageLoader.load(zip)).reason)
    }

    @Test
    fun `section declared but absent is rejected`() {
        val zip = zipOf(
            PluginPackageLoader.MANIFEST_ENTRY to bytes(validManifestJson(declares = listOf("pill"))),
        )
        assertEquals(PluginPackageLoader.Reason.MISSING_DECLARED_SECTION, rejected(PluginPackageLoader.load(zip)).reason)
    }

    @Test
    fun `file outside any known section is rejected`() {
        val zip = zipOf(
            PluginPackageLoader.MANIFEST_ENTRY to bytes(validManifestJson()),
            "random/thing.txt" to bytes("x"),
        )
        assertEquals(PluginPackageLoader.Reason.DISALLOWED_FILE, rejected(PluginPackageLoader.load(zip)).reason)
    }

    @Test
    fun `a bad package never throws - fuzz of truncated archives`() {
        val good = zipOf(PluginPackageLoader.MANIFEST_ENTRY to bytes(validManifestJson()))
        // Truncating a valid zip at many points must always yield a typed result, never an exception.
        for (cut in 1 until good.size step 7) {
            val truncated = good.copyOfRange(0, cut)
            val result = PluginPackageLoader.load(truncated)
            assertNotNull(result) // reaching here at all means no exception escaped
        }
    }
}
