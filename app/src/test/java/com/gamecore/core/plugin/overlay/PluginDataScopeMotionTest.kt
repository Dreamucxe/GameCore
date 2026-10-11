package com.gamecore.core.plugin.overlay

import com.gamecore.core.plugin.DataScope
import com.gamecore.core.plugin.DenyReason
import com.gamecore.core.plugin.PermissionDecision
import com.gamecore.core.plugin.PluginGrant
import com.gamecore.core.plugin.PluginManifest
import com.gamecore.core.plugin.PluginManifestCodec
import com.gamecore.core.plugin.PluginPermission
import com.gamecore.core.plugin.PluginPermissionGate
import com.gamecore.core.plugin.PluginRuntimeState
import com.gamecore.core.plugin.RequestedPermission
import com.gamecore.core.sensors.MotionReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Part P gate: the `motion` data scope reaches a plugin through exactly the same approved-read
 * path as every other stat, and nowhere else.
 *
 * The two motion stats are ordinary [ModuleStat] rows whose scope is `motion`, so the codec, the
 * `requiredScopes` walk and the permission gate carry them with no new code — this proves they do.
 * It also pins the host feed strings (`rotation_rate`, `movement`) to the [MotionReading] ids and
 * to the `DataScope.MOTION` id, because a drift between any two of those is the quiet failure the
 * platform note warns about: a stat that loads and draws blank.
 */
class PluginDataScopeMotionTest {

    private val gate = PluginPermissionGate()

    private fun pill(body: String): String =
        """{"format":"gamecore.plugin.pill","version":1,$body}"""

    private fun parsed(json: String): PluginOverlayModule {
        val r = PluginOverlayCodec.decode(json)
        assertTrue("expected Parsed, got $r", r is PluginOverlayCodec.Result.Parsed)
        return (r as PluginOverlayCodec.Result.Parsed).module
    }

    // ------------------------------------------------------------------------------ the ids

    @Test
    fun `the motion stats are real stats whose scope is the motion aperture`() {
        assertEquals(8, ModuleStat.entries.size)
        val rotation = ModuleStat.fromId("rotation_rate")
        val movement = ModuleStat.fromId("movement")
        assertNotNull(rotation)
        assertNotNull(movement)
        assertEquals(DataScope.MOTION.id, rotation!!.scope)
        assertEquals(DataScope.MOTION.id, movement!!.scope)
        assertEquals(DataScope.MOTION, DataScope.fromId(rotation.scope))
    }

    @Test
    fun `the host feed strings match the MotionReading ids exactly`() {
        assertEquals(MotionReading.ROTATION_RATE.id, ModuleStat.fromId("rotation_rate")!!.id)
        assertEquals(MotionReading.MOVEMENT.id, ModuleStat.fromId("movement")!!.id)
        assertEquals("rotation_rate", MotionReading.ROTATION_RATE.id)
        assertEquals("movement", MotionReading.MOVEMENT.id)
        assertEquals("motion", DataScope.MOTION.id)
    }

    // ------------------------------------------------------------------------------ requiredScopes

    @Test
    fun `a module drawing a motion stat needs exactly the motion scope`() {
        val module = parsed(
            pill(""""id":"rot","content":{"type":"stat","props":{"stat":"rotation_rate"}}"""),
        )
        assertEquals(setOf("motion"), module.requiredScopes)
        assertTrue(module.requiredStats.any { it.id == "rotation_rate" })
    }

    @Test
    fun `a module mixing a device stat and a motion stat needs both apertures`() {
        val module = parsed(
            pill(
                """"id":"mix","content":{"type":"row","children":[""" +
                    """{"type":"stat","props":{"stat":"fps"}},""" +
                    """{"type":"stat","props":{"stat":"movement"}}]}""",
            ),
        )
        assertEquals(setOf("device_stats", "motion"), module.requiredScopes)
    }

    // ------------------------------------------------------------------------------ the gate

    @Test
    fun `the motion scope gates exactly like device_stats and thermals`() {
        val manifest = PluginManifest(
            id = "com.example.motion",
            name = "Motion",
            version = 1,
            versionName = "1.0",
            api = 3,
            minHostVersion = 1,
            developer = "Tester",
            description = "",
            permissions = listOf(
                RequestedPermission(PluginPermission.READ_APPROVED_DATA, setOf("motion")),
            ),
            declares = emptySet(),
            changelog = "",
        )
        val granted = PluginRuntimeState.of(
            manifest,
            PluginGrant("com.example.motion", mapOf(PluginPermission.READ_APPROVED_DATA to setOf("motion"))),
            true, false, false,
        )
        assertTrue(gate.check(granted, PluginPermission.READ_APPROVED_DATA, "motion").isAllowed)

        val withheld = PluginRuntimeState.of(
            manifest,
            PluginGrant("com.example.motion", mapOf(PluginPermission.READ_APPROVED_DATA to emptySet())),
            true, false, false,
        )
        val denied = gate.check(withheld, PluginPermission.READ_APPROVED_DATA, "motion")
        assertEquals(DenyReason.SCOPE_WITHHELD, (denied as PermissionDecision.Deny).reason)
    }

    // ------------------------------------------------------------------------------ the manifest

    @Test
    fun `a manifest may request read_approved_data scoped to motion`() {
        val raw = """{"format":"gamecore.plugin","id":"com.d.motion","name":"M","version":1,""" +
            """"api":3,"minHostVersion":1,""" +
            """"permissions":[{"id":"read_approved_data","scopes":["motion"]}]}"""
        val result = PluginManifestCodec.decode(raw)
        assertTrue("expected Parsed, got $result", result is PluginManifestCodec.Result.Parsed)
        val read = (result as PluginManifestCodec.Result.Parsed).manifest
            .permissions.first { it.permission == PluginPermission.READ_APPROVED_DATA }
        assertTrue("motion" in read.scopes)
    }
}
