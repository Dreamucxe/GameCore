package com.gamecore.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Part A — the `.gcplugin` manifest schema and the versioned API, proved on the JVM.
 *
 * These tests are the schema's teeth. The format's promise is that a [PluginManifest]
 * handed downstream is trustworthy, and that promise is only as good as the set of
 * things [PluginManifestCodec] refuses. So most of this file is refusals: a manifest
 * that is not ours, one with a key from a newer API, one asking for a reserved or
 * invented permission, one scoping a permission that has no scopes. Each must come
 * back as a typed [PluginManifestCodec.Result.Rejected] with the right reason — never
 * a throw, never a silently-accepted bad value.
 *
 * A real `org.json` is on the test classpath (see libs.versions.toml), so parsing
 * here means what it means on the device.
 */
class PluginManifestCodecTest {

    private fun manifest(body: String): String = """{"format":"gamecore.plugin",$body}"""

    private val minimal = manifest(
        """"id":"com.dev.theme","name":"Neon","version":1,"api":1,"minHostVersion":27""",
    )

    private fun decode(json: String) = PluginManifestCodec.decode(json)

    private fun rejection(json: String): PluginManifestCodec.Reason {
        val r = decode(json)
        assertTrue("expected rejection, got $r", r is PluginManifestCodec.Result.Rejected)
        return (r as PluginManifestCodec.Result.Rejected).reason
    }

    private fun parsed(json: String): PluginManifest {
        val r = decode(json)
        assertTrue("expected parse, got $r", r is PluginManifestCodec.Result.Parsed)
        return (r as PluginManifestCodec.Result.Parsed).manifest
    }

    // ------------------------------------------------------------------------------ the happy path

    @Test
    fun `a minimal well-formed manifest parses`() {
        val m = parsed(minimal)
        assertEquals("com.dev.theme", m.id)
        assertEquals("Neon", m.name)
        assertEquals(1, m.version)
        assertEquals(1, m.api)
        assertEquals(27, m.minHostVersion)
        assertTrue(m.permissions.isEmpty())
        assertTrue(m.declares.isEmpty())
    }

    @Test
    fun `permissions resolve to known enum cases with scopes`() {
        val m = parsed(
            manifest(
                """"id":"com.dev.x","name":"X","version":2,"api":1,"minHostVersion":27,""" +
                    """"permissions":[{"id":"read_approved_data","scopes":["sessions","thermals"]},{"id":"change_ui"}]""",
            ),
        )
        assertTrue(m.requests(PluginPermission.READ_APPROVED_DATA))
        assertTrue(m.requests(PluginPermission.CHANGE_UI))
        val read = m.permissions.first { it.permission == PluginPermission.READ_APPROVED_DATA }
        assertEquals(setOf("sessions", "thermals"), read.scopes)
        assertEquals(RiskTier.LOW, m.highestTier)
    }

    @Test
    fun `a bare string permission is accepted as an unscoped grant`() {
        val m = parsed(
            manifest(""""id":"com.dev.x","name":"X","version":1,"api":1,"minHostVersion":27,"permissions":["change_ui"]"""),
        )
        assertTrue(m.requests(PluginPermission.CHANGE_UI))
    }

    @Test
    fun `highest tier reflects the riskiest permission`() {
        val m = parsed(
            manifest(
                """"id":"com.dev.x","name":"X","version":1,"api":1,"minHostVersion":27,"permissions":["change_ui","system_actions"]""",
            ),
        )
        assertEquals(RiskTier.HIGH, m.highestTier)
    }

    // ------------------------------------------------------------------------------ refusals

    @Test
    fun `empty input is rejected as empty`() {
        assertEquals(PluginManifestCodec.Reason.EMPTY, rejection(""))
        assertEquals(PluginManifestCodec.Reason.EMPTY, rejection("   "))
    }

    @Test
    fun `non-json is rejected as malformed, not thrown`() {
        assertEquals(PluginManifestCodec.Reason.MALFORMED, rejection("{not json"))
    }

    @Test
    fun `a wrong format tag is refused`() {
        assertEquals(
            PluginManifestCodec.Reason.WRONG_FORMAT,
            rejection("""{"format":"something.else","id":"com.d.x","name":"X","version":1,"api":1,"minHostVersion":27}"""),
        )
    }

    @Test
    fun `an unknown top-level key is rejected, never ignored`() {
        assertEquals(
            PluginManifestCodec.Reason.UNKNOWN_KEY,
            rejection(manifest(""""id":"com.d.x","name":"X","version":1,"api":1,"minHostVersion":27,"futureField":true""")),
        )
    }

    @Test
    fun `a reserved permission is refused with the reserved reason`() {
        assertEquals(
            PluginManifestCodec.Reason.RESERVED_PERMISSION,
            rejection(manifest(""""id":"com.d.x","name":"X","version":1,"api":1,"minHostVersion":27,"permissions":["network"]""")),
        )
        assertEquals(
            PluginManifestCodec.Reason.RESERVED_PERMISSION,
            rejection(manifest(""""id":"com.d.x","name":"X","version":1,"api":1,"minHostVersion":27,"permissions":["external_app"]""")),
        )
    }

    @Test
    fun `an invented permission is refused as unknown`() {
        assertEquals(
            PluginManifestCodec.Reason.UNKNOWN_PERMISSION,
            rejection(manifest(""""id":"com.d.x","name":"X","version":1,"api":1,"minHostVersion":27,"permissions":["do_anything"]""")),
        )
    }

    @Test
    fun `scopes on an unscoped permission are refused`() {
        assertEquals(
            PluginManifestCodec.Reason.BAD_SCOPE,
            rejection(manifest(""""id":"com.d.x","name":"X","version":1,"api":1,"minHostVersion":27,"permissions":[{"id":"change_ui","scopes":["sessions"]}]""")),
        )
    }

    @Test
    fun `an unknown data scope is refused`() {
        assertEquals(
            PluginManifestCodec.Reason.BAD_SCOPE,
            rejection(manifest(""""id":"com.d.x","name":"X","version":1,"api":1,"minHostVersion":27,"permissions":[{"id":"read_approved_data","scopes":["other_apps"]}]""")),
        )
    }

    @Test
    fun `an unknown declared section is refused`() {
        assertEquals(
            PluginManifestCodec.Reason.UNKNOWN_SECTION,
            rejection(manifest(""""id":"com.d.x","name":"X","version":1,"api":1,"minHostVersion":27,"declares":["wallhack"]""")),
        )
    }

    @Test
    fun `a non-package id is refused`() {
        assertEquals(
            PluginManifestCodec.Reason.BAD_ID,
            rejection(manifest(""""id":"not a package","name":"X","version":1,"api":1,"minHostVersion":27""")),
        )
    }

    @Test
    fun `a zero or negative version is refused`() {
        assertEquals(
            PluginManifestCodec.Reason.BAD_VERSION,
            rejection(manifest(""""id":"com.d.x","name":"X","version":0,"api":1,"minHostVersion":27""")),
        )
    }

    @Test
    fun `a missing required field is refused`() {
        assertEquals(
            PluginManifestCodec.Reason.MISSING_FIELD,
            rejection("""{"format":"gamecore.plugin","id":"com.d.x","name":"X","version":1,"api":1}"""),
        )
    }

    // ------------------------------------------------------------------------------ the API matrix

    @Test
    fun `a plugin for a newer API is TooNew`() {
        assertEquals(Compatibility.TooNew, PluginApi.compatibility(PluginApi.CURRENT + 1, 27))
    }

    @Test
    fun `a plugin needing a newer host is HostTooOld`() {
        assertEquals(Compatibility.HostTooOld, PluginApi.compatibility(1, PluginApi.HOST_VERSION_CODE + 1))
    }

    @Test
    fun `a current-API plugin for this host is Supported`() {
        assertEquals(Compatibility.Supported, PluginApi.compatibility(PluginApi.CURRENT, PluginApi.HOST_VERSION_CODE))
        assertTrue(PluginApi.isSupported(1, 27))
    }

    @Test
    fun `the API-too-new check takes precedence over the host check`() {
        // Both would fail; the API gap is reported.
        assertEquals(Compatibility.TooNew, PluginApi.compatibility(PluginApi.CURRENT + 1, PluginApi.HOST_VERSION_CODE + 5))
    }

    @Test
    fun `no compatibility verdict except Supported is runnable`() {
        assertTrue(Compatibility.Supported.isRunnable)
        assertFalse(Compatibility.TooNew.isRunnable)
        assertFalse(Compatibility.TooOld.isRunnable)
        assertFalse(Compatibility.HostTooOld.isRunnable)
    }
}
