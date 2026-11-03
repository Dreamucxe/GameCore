package com.gamecore.studio.core

import com.gamecore.studio.model.GlassDraft
import com.gamecore.studio.model.GlitchDraft
import com.gamecore.studio.model.PluginDraft
import com.gamecore.studio.model.ThemeDraft
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The importer's contract: read what the composer writes, refuse what the studio cannot
 * preserve, and never lose a field without saying so.
 *
 * The fixtures are built with `ZipOutputStream`, so the tests exercise a real archive
 * rather than a map that only resembles one. The duplicate-name case needs a stream-only
 * zip because `ZipOutputStream` itself refuses to write two entries with the same name;
 * [StreamOnlyZipOutputStream] leaves the central directory off, which is exactly the shape
 * a hostile writer can produce and what `ZipInputStream` must still detect.
 */
class PluginImporterTest {

    private val draft = PluginDraft(
        id = "com.example.glasswork",
        name = "Glasswork",
        version = 7,
        versionName = "2.3.1",
        developer = "Nova Studio",
        description = "A theme with every supported field set.",
        changelog = "2.3.1 — added the glass and glitch blocks.",
        api = PluginDraft.CURRENT_API,
        minHostVersion = PluginDraft.DEFAULT_MIN_HOST_VERSION,
        permissions = setOf("change_ui", "store_data", "run_automations"),
        theme = ThemeDraft(
            name = "Glasswork",
            schema = ThemeDraft.CURRENT_SCHEMA,
            dark = false,
            amoled = true,
            primary = 0x80336699L,
            onPrimary = 0xFF102030L,
            background = 0xFF0A0B0CL,
            surface = 0xFF1A1B1CL,
            onSurface = 0xFFF1F2F3L,
            accent = 0xFF44CC88L,
            secondary = 0xFF556677L,
            tertiary = 0xFF778899L,
            surfaceVariant = 0xFF223344L,
            onSurfaceVariant = 0xFFCCBBAAL,
            outline = 0xFF445566L,
            error = 0xFFCC2233L,
            onError = 0xFF330011L,
            cornerRadiusDp = 17,
            uiScale = 1.15f,
            font = "inter",
            typographyWeight = "bold",
            typographyScale = 1.08f,
            glass = GlassDraft(
                intensity = 0.71f,
                transparency = 0.39f,
                gloss = 0.44f,
                blurRadiusDp = 18,
                sheen = false,
                sheenPeriodMs = 5100,
                spring = "gentle",
                hardwareReactive = true,
            ),
            glitch = GlitchDraft(
                intensity = 0.62f,
                slices = 5,
                sliceOffsetDp = 21,
                ghostOffsetDp = 7,
                fractureLines = false,
                noise = true,
                microJitter = false,
                hardwareReactive = false,
                burstMinMs = 240,
                burstMaxMs = 1800,
            ),
        ),
    )

    // ------------------------------------------------------------------------------ round trip

    @Test
    fun `a composed package imports with every supported field intact`() {
        val success = success(PluginComposer.packageBytes(draft))

        assertEquals(draft, success.draft)
        assertTrue(
            "a clean package produced warnings: ${success.report.warnings}",
            success.report.warnings.isEmpty(),
        )
        assertTrue(success.report.ignoredManifestKeys.isEmpty())
        assertTrue(success.report.ignoredThemeKeys.isEmpty())
        assertTrue(success.report.ignoredSections.isEmpty())
    }

    @Test
    fun `a colour with alpha survives the round trip`() {
        val alpha = draft.copy(theme = draft.theme.copy(primary = 0x7F123456L))
        val success = success(PluginComposer.packageBytes(alpha))

        assertEquals(0x7F123456L, success.draft.theme.primary)
        assertTrue(
            "the composer did not write the alpha form: ${PluginComposer.themeJson(alpha)}",
            PluginComposer.themeJson(alpha).contains("\"primary\": \"#7F123456\""),
        )
    }

    @Test
    fun `an unknown permission id is kept so the draft can explain it`() {
        val manifest = JSONObject(PluginComposer.manifestJson(draft))
        manifest.put(
            "permissions",
            JSONArray().put(JSONObject().put("id", "future_permission")),
        )
        val success = success(
            zipOfText(
                listOf(
                    "manifest.json" to manifest.toString(),
                    "theme.json" to PluginComposer.themeJson(draft),
                ),
            ),
        )

        assertEquals(setOf("future_permission"), success.draft.permissions)
        assertTrue(
            "draft.problems() did not report the unknown permission",
            success.draft.problems().any {
                it.field == "permissions" && it.message.contains("future_permission")
            },
        )
    }

    // ------------------------------------------------------------------------------ refused sections

    @Test
    fun `a package with an extra section is refused and the section is named`() {
        val entries = composedEntries(draft).toMutableMap()
        entries["pill.json"] = "{\"modules\":[]}".toByteArray(Charsets.UTF_8)

        val failure = failure(zipOfBytes(entries))

        assertTrue("the failure did not name pill.json: ${failure.message}", failure.message.contains("pill.json"))
        assertTrue(
            "the failure did not explain the data loss: ${failure.message}",
            failure.message.contains("data loss"),
        )
    }

    // ------------------------------------------------------------------------------ bad containers

    @Test
    fun `malformed manifest JSON is refused`() {
        val failure = failure(
            zipOfText(
                listOf(
                    "manifest.json" to "{\"format\": ",
                    "theme.json" to PluginComposer.themeJson(draft),
                ),
            ),
        )

        assertTrue(failure.message, failure.message.contains("manifest.json"))
        assertTrue(failure.message, failure.message.contains("not valid JSON"))
    }

    @Test
    fun `an oversized entry is refused before it is parsed`() {
        val bomb = ByteArray(PluginImporter.MAX_ENTRY_BYTES + 1)
        val failure = failure(
            zipOfBytes(
                listOf(
                    "manifest.json" to bomb,
                    "theme.json" to PluginComposer.themeJson(draft).toByteArray(Charsets.UTF_8),
                ),
            ),
        )

        assertTrue(failure.message, failure.message.contains("manifest.json"))
        assertTrue(failure.message, failure.message.contains("larger than"))
    }

    @Test
    fun `a package that expands past the total cap is refused`() {
        val half = ByteArray(PluginImporter.MAX_ENTRY_BYTES)
        val failure = failure(
            zipOfBytes(
                listOf(
                    "manifest.json" to half,
                    "theme.json" to half,
                    "one-more-byte" to byteArrayOf(1),
                ),
            ),
        )

        assertTrue(failure.message, failure.message.contains("more than 4 MiB"))
    }

    @Test
    fun `duplicate entry names are refused`() {
        val manifest = PluginComposer.manifestJson(draft).toByteArray(Charsets.UTF_8)
        val theme = PluginComposer.themeJson(draft).toByteArray(Charsets.UTF_8)
        val failure = failure(
            streamOnlyZip(
                listOf(
                    "manifest.json" to manifest,
                    "manifest.json" to manifest,
                    "theme.json" to theme,
                ),
            ),
        )

        assertTrue(failure.message, failure.message.contains("more than once"))
        assertTrue(failure.message, failure.message.contains("manifest.json"))
    }

    @Test
    fun `traversal and absolute entry paths are refused`() {
        val manifest = PluginComposer.manifestJson(draft)
        val theme = PluginComposer.themeJson(draft)

        val traversal = failure(
            zipOfText(listOf("../manifest.json" to manifest, "theme.json" to theme)),
        )
        assertTrue(traversal.message, traversal.message.contains("unsafe path"))

        val absolute = failure(
            zipOfText(listOf("/manifest.json" to manifest, "theme.json" to theme)),
        )
        assertTrue(absolute.message, absolute.message.contains("unsafe path"))
    }

    // ------------------------------------------------------------------------------ bad documents

    @Test
    fun `a theme schema newer than the studio is refused`() {
        val theme = JSONObject(PluginComposer.themeJson(draft)).put("schema", 5)
        val failure = failure(
            zipOfText(
                listOf(
                    "manifest.json" to PluginComposer.manifestJson(draft),
                    "theme.json" to theme.toString(),
                ),
            ),
        )

        assertTrue(failure.message, failure.message.contains("schema 5"))
        assertTrue(failure.message, failure.message.contains("supports"))
    }

    @Test
    fun `a manifest with the wrong format is refused`() {
        val manifest = JSONObject(PluginComposer.manifestJson(draft)).put("format", "other.plugin")
        val failure = failure(
            zipOfText(
                listOf(
                    "manifest.json" to manifest.toString(),
                    "theme.json" to PluginComposer.themeJson(draft),
                ),
            ),
        )

        assertTrue(failure.message, failure.message.contains("not a GameCore plugin package"))
        assertTrue(failure.message, failure.message.contains("other.plugin"))
    }

    @Test
    fun `a manifest missing a required field is refused`() {
        val manifest = JSONObject(PluginComposer.manifestJson(draft))
        manifest.remove("minHostVersion")
        val failure = failure(
            zipOfText(
                listOf(
                    "manifest.json" to manifest.toString(),
                    "theme.json" to PluginComposer.themeJson(draft),
                ),
            ),
        )

        assertTrue(failure.message, failure.message.contains("minHostVersion"))
        assertTrue(failure.message, failure.message.contains("missing required field"))
    }

    // ------------------------------------------------------------------------------ unknown keys

    @Test
    fun `unknown theme keys are reported, not hidden`() {
        val entries = composedEntries(draft)
        val theme = JSONObject(String(entries.getValue("theme.json"), Charsets.UTF_8))
            .put("mystery", JSONObject().put("enabled", true))
        theme.getJSONObject("roles").put("wobble", "#123456")
        theme.getJSONObject("typography").put("leading", 1.2)
        theme.getJSONObject("effects").put("sparkle", true)
        theme.getJSONObject("effects").getJSONObject("glass").put("bloom", 3)
        theme.getJSONObject("effects").getJSONObject("glitch").put("trails", true)

        val success = success(
            zipOfText(
                listOf(
                    "manifest.json" to String(entries.getValue("manifest.json"), Charsets.UTF_8),
                    "theme.json" to theme.toString(),
                ),
            ),
        )

        listOf(
            "mystery",
            "roles.wobble",
            "typography.leading",
            "effects.sparkle",
            "effects.glass.bloom",
            "effects.glitch.trails",
        ).forEach { key ->
            assertTrue(
                "\"$key\" was not listed in ${success.report.ignoredThemeKeys}",
                success.report.ignoredThemeKeys.contains(key),
            )
        }
        assertTrue(
            "no warning said the key will not be written back: ${success.report.warnings}",
            success.report.warnings.any {
                it.contains("mystery") && it.contains("will not be written back")
            },
        )
        assertEquals(draft, success.draft)
    }

    @Test
    fun `an unknown manifest key is reported, not hidden`() {
        val manifest = JSONObject(PluginComposer.manifestJson(draft))
            .put("futureHook", true)
        val success = success(
            zipOfText(
                listOf(
                    "manifest.json" to manifest.toString(),
                    "theme.json" to PluginComposer.themeJson(draft),
                ),
            ),
        )

        assertTrue(success.report.ignoredManifestKeys.contains("futureHook"))
        assertTrue(
            success.report.warnings.any {
                it.contains("futureHook") && it.contains("will not be written back")
            },
        )
        assertEquals(draft, success.draft)
    }

    // ------------------------------------------------------------------------------ helpers

    private fun success(bytes: ByteArray): ImportOutcome.Success {
        val outcome = PluginImporter.import(bytes)
        assertTrue("expected a successful import, got $outcome", outcome is ImportOutcome.Success)
        return outcome as ImportOutcome.Success
    }

    private fun failure(bytes: ByteArray): ImportOutcome.Failure {
        val outcome = PluginImporter.import(bytes)
        assertTrue("expected a refusal, got $outcome", outcome is ImportOutcome.Failure)
        return outcome as ImportOutcome.Failure
    }

    private fun composedEntries(draft: PluginDraft): Map<String, ByteArray> =
        PluginImporter.readEntries(PluginComposer.packageBytes(draft))

    private fun zipOfText(entries: List<Pair<String, String>>): ByteArray =
        zipOfBytes(entries.map { (name, text) -> name to text.toByteArray(Charsets.UTF_8) })

    private fun zipOfBytes(entries: Map<String, ByteArray>): ByteArray =
        zipOfBytes(entries.map { (name, bytes) -> name to bytes })

    private fun zipOfBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
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

    /**
     * A sequence of local file header + data records with no central directory.
     *
     * `ZipOutputStream` rejects a duplicate name itself, but a hostile zip can still carry
     * one. Each entry gets its own stream so the duplicate check inside a single writer never
     * sees the first name; `ZipInputStream` walks the local records and the importer has to
     * catch the duplicate.
     */
    private fun streamOnlyZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        entries.forEach { (name, bytes) ->
            val zip = StreamOnlyZipOutputStream(output)
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
            zip.close()
        }
        return output.toByteArray()
    }

    private class StreamOnlyZipOutputStream(output: OutputStream) : ZipOutputStream(output) {
        override fun finish() {
            // Deliberately no central directory: this builds an archive a hostile writer
            // could hand us, not one the JDK's writer would choose to make.
        }
    }
}
