package com.gamecore.plugin.sdk

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Part P gate (SDK mirror): the two motion stats are part of the overlay vocabulary, read through
 * the `motion` scope, and a module that uses one bumps its manifest api to [Sdk.API_V3] the same
 * per-use way a schema-2 theme bumps to 2 — while a module that uses none is byte-for-byte what it
 * was before motion existed.
 *
 * The decoupling of [RuleVariables] from [OverlayStats] is pinned here too: a motion id is a valid
 * overlay stat but NOT a valid rule variable, because the automation feed does not publish motion
 * and an unknown rule variable is the SDK's quietest failure — it installs and never fires.
 */
class PluginMotionReadingsTest {

    private fun manifestOf(def: PluginDefinition): JSONObject =
        JSONObject(String(def.manifestJson))

    // ------------------------------------------------------------------------------ the mirror

    @Test
    fun `the motion stat ids are the exact strings the host publishes`() {
        assertEquals("rotation_rate", OverlayStats.ROTATION_RATE)
        assertEquals("movement", OverlayStats.MOVEMENT)
        assertEquals(setOf("rotation_rate", "movement"), OverlayStats.MOTION_READINGS)
        assertEquals("motion", DataScopes.MOTION)
    }

    @Test
    fun `the motion stats join the overlay vocabulary and read through the motion scope`() {
        assertEquals(8, OverlayStats.ALL.size)
        assertTrue(OverlayStats.ROTATION_RATE in OverlayStats.ALL)
        assertTrue(OverlayStats.MOVEMENT in OverlayStats.ALL)
        assertEquals(DataScopes.MOTION, OverlayStats.scopeFor(OverlayStats.ROTATION_RATE))
        assertEquals(DataScopes.MOTION, OverlayStats.scopeFor(OverlayStats.MOVEMENT))
        // The device-stat and thermal scopes are untouched.
        assertEquals(DataScopes.DEVICE_STATS, OverlayStats.scopeFor(OverlayStats.FPS))
        assertEquals(DataScopes.THERMALS, OverlayStats.scopeFor(OverlayStats.TEMP))
    }

    // ------------------------------------------------------------------------------ per-use api bump

    @Test
    fun `a module that draws a motion stat bumps the manifest api to v3`() {
        val def = plugin("com.example.motion", "Motion") {
            request(Permissions.CREATE_OVERLAY)
            request(Permissions.READ_APPROVED_DATA, setOf(DataScopes.MOTION))
            pill {
                id = "rotation"
                title = "Rotation"
                row(gap = 6, align = "center") {
                    text("ROT")
                    stat(OverlayStats.ROTATION_RATE, size = 14)
                }
            }
        }
        assertEquals(Sdk.API_V3, manifestOf(def).getInt("api"))
    }

    @Test
    fun `a module that draws only device stats stays at the base api`() {
        val def = plugin("com.example.fps", "Fps") {
            request(Permissions.CREATE_OVERLAY)
            request(Permissions.READ_APPROVED_DATA, setOf(DataScopes.DEVICE_STATS))
            pill {
                id = "fps"
                row(gap = 6, align = "center") {
                    text("FPS")
                    stat(OverlayStats.FPS, size = 14)
                }
            }
        }
        assertEquals(Sdk.API, manifestOf(def).getInt("api"))
    }

    @Test
    fun `the motion stats are accepted by the stat node builder`() {
        OverlayStats.MOTION_READINGS.forEach { known ->
            plugin("com.example.m", "M") { pill { id = "m"; stat(known) } }
        }
    }

    // ------------------------------------------------------------------------------ rule-variable guard

    @Test
    fun `motion is a stat but not a rule variable`() {
        assertEquals(setOf("fps", "cpu", "gpu", "ram", "battery", "temp"), RuleVariables.ALL)
        assertEquals(6, RuleVariables.ALL.size)
        assertFalse(OverlayStats.ROTATION_RATE in RuleVariables.ALL)
        assertFalse(OverlayStats.MOVEMENT in RuleVariables.ALL)
    }

    @Test
    fun `a rule condition on a motion variable is refused at build time`() {
        // It would install and never fire on the host — the automation feed publishes no motion —
        // so the SDK refuses it rather than ship a rule that silently protects nothing.
        assertFailsWith<IllegalArgumentException> {
            plugin("com.example.r", "R") {
                rules {
                    rule(
                        "spin",
                        Triggers.GAME_LAUNCHED,
                        actions = listOf(Actions.SET_LOW_POWER),
                        conditions = listOf(
                            RulesBuilder.Condition(OverlayStats.ROTATION_RATE, RuleOperators.GT, 100.0),
                        ),
                    )
                }
            }
        }
    }
}
