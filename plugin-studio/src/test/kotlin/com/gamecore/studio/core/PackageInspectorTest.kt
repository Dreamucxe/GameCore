package com.gamecore.studio.core

import com.gamecore.studio.model.PluginDraft
import com.gamecore.studio.model.ThemeDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The inspector's contract: describe the bytes the user would export, hash exactly those
 * bytes, and never throw on a file it cannot read.
 *
 * The inspector deliberately stops at the container. It says an entry is missing or too
 * large; it does not claim to have checked the manifest or theme against GameCore's codecs.
 * That check belongs to `:app`'s `PluginStudioPackageTest`, which runs the real decoders.
 */
class PackageInspectorTest {

    private val draft = PluginDraft(
        id = "com.example.inspected",
        name = "Inspected",
        version = 2,
        versionName = "1.1.0",
        permissions = setOf("change_ui"),
        theme = ThemeDraft(name = "Inspected", cornerRadiusDp = 14),
    )

    @Test
    fun `entries and hash are stable for the same draft`() {
        val bytes = PluginComposer.packageBytes(draft)
        val fromBytes = PackageInspector.inspect(bytes)
        val fromDraft = PackageInspector.inspect(draft)

        assertEquals(fromBytes.entries, fromDraft.entries)
        assertEquals(listOf("manifest.json", "theme.json"), fromBytes.entries.map { it.name })
        assertEquals(
            fromBytes.entries.sumOf { it.bytes },
            fromBytes.totalBytes,
        )
        assertEquals(fromBytes.sha256, fromDraft.sha256)
        assertEquals(PluginComposer.sha256(bytes), fromBytes.sha256)
        assertEquals(PluginComposer.manifestJson(draft), fromBytes.manifestJson)
        assertEquals(PluginComposer.themeJson(draft), fromBytes.themeJson)
        assertTrue("a composed package reported ${fromBytes.issues}", fromBytes.issues.isEmpty())
    }

    @Test
    fun `a package without a manifest reports the missing entry`() {
        val bytes = zipOfText("theme.json" to PluginComposer.themeJson(draft))
        val inspection = PackageInspector.inspect(bytes)

        assertEquals(listOf("theme.json"), inspection.entries.map { it.name })
        assertEquals("", inspection.manifestJson)
        assertEquals(PluginComposer.themeJson(draft), inspection.themeJson)
        assertTrue(
            "issues did not name manifest.json: ${inspection.issues}",
            inspection.issues.any { it.contains("manifest.json") },
        )
    }

    @Test
    fun `garbage bytes do not throw`() {
        val garbage = "this is not a zip archive".toByteArray(Charsets.UTF_8)
        val inspection = PackageInspector.inspect(garbage)

        assertTrue(inspection.issues.isNotEmpty())
        assertTrue(inspection.entries.isEmpty())
        assertEquals("", inspection.manifestJson)
        assertEquals("", inspection.themeJson)
        assertEquals(PluginComposer.sha256(garbage), inspection.sha256)
    }

    @Test
    fun `an entry over the cap is reported as an issue`() {
        val bytes = zipOfBytes(
            "manifest.json" to ByteArray(PluginImporter.MAX_ENTRY_BYTES + 1),
            "theme.json" to PluginComposer.themeJson(draft).toByteArray(Charsets.UTF_8),
        )
        val inspection = PackageInspector.inspect(bytes)

        assertTrue(
            "issues did not mention the oversized entry: ${inspection.issues}",
            inspection.issues.any { it.contains("manifest.json") && it.contains("larger than") },
        )
        assertEquals(PluginComposer.sha256(bytes), inspection.sha256)
    }

    private fun zipOfText(vararg entries: Pair<String, String>): ByteArray =
        zipOfBytes(*entries.map { (name, text) -> name to text.toByteArray(Charsets.UTF_8) }.toTypedArray())

    private fun zipOfBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
