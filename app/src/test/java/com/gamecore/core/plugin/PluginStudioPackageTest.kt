package com.gamecore.core.plugin

import com.gamecore.core.plugin.theme.PluginTheme
import com.gamecore.core.plugin.theme.PluginThemeCodec
import com.gamecore.studio.core.PluginComposer
import com.gamecore.studio.model.PluginDraft
import com.gamecore.studio.model.ThemeDraft
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * The cross-check: what the Plugin Maker writes is what GameCore accepts.
 *
 * The studio is a separate app and cannot import GameCore's codecs, so it writes the
 * package format from the documented rules and restates the host's validation rules in
 * its own draft model. Two copies of a rule can drift, and a drift here is silent: the
 * maker would export a package the phone then refuses, and the user would be told the
 * plugin platform is broken.
 *
 * This file is the other half of that arrangement. It takes a draft, composes the
 * package with the studio's own composer, and decodes the bytes with the **host's real**
 * `PluginManifestCodec` and `PluginThemeCodec`. Nothing is transcribed here: the host's
 * codecs are the authority, and this test is what proves the studio agrees with them.
 *
 * A real `org.json` is on the test classpath (see libs.versions.toml), so the JSON is
 * parsed here exactly as it is parsed on the device. The zip is read with
 * `java.util.zip.ZipInputStream` rather than with the host's loader, because the point
 * is to check the *contents* the composer produced, not to exercise the loader.
 *
 * Plain JUnit, no emulator.
 */
class PluginStudioPackageTest {

    /** A draft with something in every field, so a dropped field would show. */
    private val draft = PluginDraft(
        id = "com.example.mytheme",
        name = "Studio Theme",
        version = 3,
        versionName = "1.2.0",
        developer = "Plugin Maker",
        description = "A theme written by the Plugin Maker.",
        changelog = "1.2.0 — colours changed.",
        api = PluginDraft.CURRENT_API,
        minHostVersion = PluginDraft.DEFAULT_MIN_HOST_VERSION,
        permissions = setOf("change_ui", "store_data"),
        theme = ThemeDraft(
            name = "Studio Theme",
            schema = ThemeDraft.CURRENT_SCHEMA,
            primary = 0xFF8A4CFFL,
            onPrimary = 0xFF0A2413L,
            background = 0xFF141012L,
            surface = 0xFF242021L,
            onSurface = 0xFFE7EFF6L,
            accent = 0xFFC0D42BL,
            cornerRadiusDp = 12,
            font = ThemeDraft.FONT_ROUNDED,
            typographyWeight = "bold",
        ),
    )

    @Test
    fun `a package composed by the studio is accepted by the host's manifest codec`() {
        val manifest = decodeManifest(entries(PluginComposer.packageBytes(draft)))

        assertEquals(draft.id, manifest.id)
        assertEquals(draft.name, manifest.name)
        assertEquals(draft.version, manifest.version)
        assertEquals(draft.versionName, manifest.versionName)
        assertEquals(draft.api, manifest.api)
        assertEquals(draft.minHostVersion, manifest.minHostVersion)
        assertEquals(draft.developer, manifest.developer)
        assertEquals(draft.description, manifest.description)
        assertEquals(draft.changelog, manifest.changelog)

        val expected = draft.permissions.mapNotNull { PluginPermission.fromId(it) }.toSet()
        assertEquals("a requested permission did not resolve", draft.permissions.size, expected.size)
        assertEquals(expected, manifest.permissions.map { it.permission }.toSet())

        assertEquals("the package must declare exactly the theme", setOf(PluginSection.THEME), manifest.declares)
        assertEquals(Compatibility.Supported, manifest.compatibility)
    }

    @Test
    fun `a package composed by the studio is accepted by the host's theme codec`() {
        val theme = decodeTheme(entries(PluginComposer.packageBytes(draft)))

        assertEquals(draft.theme.name, theme.name)
        assertEquals(draft.theme.dark, theme.dark)
        assertEquals(opaque(draft.theme.primary), theme.primary)
        assertEquals(opaque(draft.theme.onPrimary), theme.onPrimary)
        assertEquals(opaque(draft.theme.background), theme.background)
        assertEquals(opaque(draft.theme.surface), theme.surface)
        assertEquals(opaque(draft.theme.onSurface), theme.onSurface)
        assertEquals(opaque(draft.theme.accent), theme.accent)
        assertEquals(draft.theme.cornerRadiusDp, theme.cornerRadiusDp)
        assertEquals(draft.theme.uiScale, theme.uiScale, 0.0001f)
        assertEquals(PluginTheme.Font.fromId(draft.theme.font), theme.font)
        assertEquals(draft.theme.schema, theme.schema)
    }

    @Test
    fun `glass, glitch and the roles survive the host's real codecs`() {
        val rich = draft.copy(
            theme = draft.theme.copy(
                schema = 4,
                glass = com.gamecore.studio.model.GlassDraft(intensity = 0.65f, blurRadiusDp = 20),
                glitch = com.gamecore.studio.model.GlitchDraft(intensity = 0.4f, slices = 4),
            ),
        )
        val theme = decodeTheme(entries(PluginComposer.packageBytes(rich)))

        assertEquals(4, theme.schema)
        assertNotNull("the host dropped glass", theme.glass)
        assertEquals(0.65f, theme.glass!!.intensity, 0.0001f)
        assertEquals(20, theme.glass!!.blurRadiusDp)
        assertNotNull("the host dropped glitch", theme.glitch)
        assertEquals(4, theme.glitch!!.slices)
        assertNotNull("the host dropped the roles", theme.roles)
        assertEquals(opaque(rich.theme.error), theme.roles!!.error)
        assertNotNull("the host dropped typography", theme.typography)
    }

    @Test
    fun `the package holds exactly the manifest and the theme`() {
        val entries = entries(PluginComposer.packageBytes(draft))
        assertEquals(setOf("manifest.json", "theme.json"), entries.keys)
    }

    @Test
    fun `the studio writes the manifest keys the host requires, and declares the theme`() {
        val manifest = JSONObject(entries(PluginComposer.packageBytes(draft)).getValue("manifest.json"))
        assertEquals("gamecore.plugin", manifest.getString("format"))
        assertEquals(draft.id, manifest.getString("id"))
        assertEquals(draft.name, manifest.getString("name"))
        assertEquals(draft.version, manifest.getInt("version"))
        assertEquals(draft.api, manifest.getInt("api"))
        assertEquals(draft.minHostVersion, manifest.getInt("minHostVersion"))

        val declares = manifest.getJSONArray("declares")
        assertEquals(1, declares.length())
        assertEquals("theme", declares.getString(0))
    }

    @Test
    fun `the studio writes the six required colours as RRGGBB strings`() {
        val theme = JSONObject(entries(PluginComposer.packageBytes(draft)).getValue("theme.json"))
        listOf("primary", "onPrimary", "background", "surface", "onSurface", "accent").forEach { key ->
            val value = theme.getString(key)
            assertTrue("\"$key\" was $value, not a #RRGGBB string", value.matches(Regex("^#[0-9A-Fa-f]{6}$")))
        }
    }

    @Test
    fun `a draft with an invalid id is reported by the studio and refused by the host`() {
        val draft = PluginDraft(id = "no dots")
        val problems = draft.problems()
        assertFalse("the studio accepted a malformed id", draft.isValid)
        assertTrue("the studio did not name the id field: $problems", problems.any { it.field == "id" })

        // And the host would refuse the same id, which is the whole point of the studio
        // reporting it: a rule that only one of the two sides knows is a rule that
        // eventually ships a file the phone rejects.
        val manifest = PluginManifestCodec.decode(
            """{"format":"gamecore.plugin","id":"no dots","name":"X","version":1,"api":1,"minHostVersion":50}""",
        )
        assertTrue("the host accepted a malformed id: $manifest", manifest is PluginManifestCodec.Result.Rejected)
        assertEquals(
            PluginManifestCodec.Reason.BAD_ID,
            (manifest as PluginManifestCodec.Result.Rejected).reason,
        )
    }

    @Test
    fun `every permission the studio offers is one the host knows`() {
        PluginDraft.KNOWN_PERMISSIONS.forEach { permission ->
            assertNotNull(
                "the host does not know the permission \"${permission.id}\"",
                PluginPermission.fromId(permission.id),
            )
        }
    }

    @Test
    fun `the studio writes the api and the host version this build implements`() {
        assertEquals(PluginApi.CURRENT, PluginDraft.CURRENT_API)
        assertEquals(PluginApi.MINIMUM_SUPPORTED, PluginDraft.MINIMUM_API)
        assertEquals(PluginApi.HOST_VERSION_CODE, PluginDraft.DEFAULT_MIN_HOST_VERSION)
    }

    // ------------------------------------------------------------------------------ helpers

    /** The zip's entries, in order, each read as UTF-8 text. */
    private fun entries(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    private fun decodeManifest(entries: Map<String, String>): PluginManifest {
        val result = PluginManifestCodec.decode(entries.getValue("manifest.json"))
        assertTrue("the host rejected the studio's manifest: $result", result is PluginManifestCodec.Result.Parsed)
        return (result as PluginManifestCodec.Result.Parsed).manifest
    }

    private fun decodeTheme(entries: Map<String, String>): PluginTheme {
        val result = PluginThemeCodec.decode(entries.getValue("theme.json"))
        assertTrue("the host rejected the studio's theme: $result", result is PluginThemeCodec.Result.Parsed)
        return (result as PluginThemeCodec.Result.Parsed).theme
    }

    /**
     * The colour as the host holds it: opaque ARGB.
     *
     * The studio writes `#RRGGBB` — the alpha channel is dropped on purpose — and the
     * host forces a colour given without an alpha to be opaque.
     */
    private fun opaque(argb: Long): Int = 0xFF000000.toInt() or ((argb and 0xFFFFFFL).toInt())
}
