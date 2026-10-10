package com.gamecore.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Part C gate: the permission enforcement layer. Proves that every API call is checked, that
 * a grant can never exceed a request, that the kill states deny everything, that the advanced
 * opt-in actually gates SYSTEM_ACTIONS, that scopes are enforced, and that per-plugin
 * namespacing confines keys.
 */
class PluginPermissionGateTest {

    private val gate = PluginPermissionGate()

    private fun manifest(vararg perms: Pair<PluginPermission, Set<String>>): PluginManifest =
        PluginManifest(
            id = "com.example.plugin",
            name = "Example",
            version = 1,
            versionName = "1.0",
            api = 1,
            minHostVersion = 1,
            developer = "Tester",
            description = "",
            permissions = perms.map { RequestedPermission(it.first, it.second) },
            declares = emptySet(),
            changelog = "",
        )

    private fun state(
        manifest: PluginManifest,
        grant: PluginGrant,
        enabled: Boolean = true,
        suspended: Boolean = false,
        kill: Boolean = false,
    ) = PluginRuntimeState.of(manifest, grant, enabled, suspended, kill)

    // ---------------------------------------------------------------- happy path

    @Test
    fun `granted permission is allowed`() {
        val m = manifest(PluginPermission.CHANGE_UI to emptySet())
        val g = PluginGrant("com.example.plugin", mapOf(PluginPermission.CHANGE_UI to emptySet()))
        assertTrue(gate.check(state(m, g), PluginPermission.CHANGE_UI).isAllowed)
    }

    // ---------------------------------------------------------------- the core denials

    @Test
    fun `permission the manifest never requested is denied as NOT_REQUESTED`() {
        val m = manifest(PluginPermission.CHANGE_UI to emptySet())
        // A grant map that lists an un-requested permission must still be refused.
        val g = PluginGrant("com.example.plugin", mapOf(PluginPermission.SYSTEM_ACTIONS to emptySet()))
        val d = gate.check(state(m, g), PluginPermission.SYSTEM_ACTIONS)
        assertEquals(DenyReason.NOT_REQUESTED, (d as PermissionDecision.Deny).reason)
    }

    @Test
    fun `requested but ungranted permission is denied as NOT_GRANTED`() {
        val m = manifest(PluginPermission.ADD_PILL_MODULES to emptySet())
        val g = PluginGrant.none("com.example.plugin")
        val d = gate.check(state(m, g), PluginPermission.ADD_PILL_MODULES)
        assertEquals(DenyReason.NOT_GRANTED, (d as PermissionDecision.Deny).reason)
    }

    @Test
    fun `disabled plugin is denied everything`() {
        val m = manifest(PluginPermission.CHANGE_UI to emptySet())
        val g = PluginGrant("com.example.plugin", mapOf(PluginPermission.CHANGE_UI to emptySet()))
        val d = gate.check(state(m, g, enabled = false), PluginPermission.CHANGE_UI)
        assertEquals(DenyReason.PLUGIN_DISABLED, (d as PermissionDecision.Deny).reason)
    }

    @Test
    fun `suspended plugin is denied everything`() {
        val m = manifest(PluginPermission.CHANGE_UI to emptySet())
        val g = PluginGrant("com.example.plugin", mapOf(PluginPermission.CHANGE_UI to emptySet()))
        val d = gate.check(state(m, g, suspended = true), PluginPermission.CHANGE_UI)
        assertEquals(DenyReason.PLUGIN_SUSPENDED, (d as PermissionDecision.Deny).reason)
    }

    @Test
    fun `kill switch denies even a fully granted plugin`() {
        val m = manifest(PluginPermission.CHANGE_UI to emptySet())
        val g = PluginGrant("com.example.plugin", mapOf(PluginPermission.CHANGE_UI to emptySet()))
        val d = gate.check(state(m, g, kill = true), PluginPermission.CHANGE_UI)
        assertEquals(DenyReason.SYSTEM_KILL_SWITCH, (d as PermissionDecision.Deny).reason)
    }

    // ---------------------------------------------------------------- advanced gate

    @Test
    fun `system actions granted without advanced access is denied`() {
        val m = manifest(PluginPermission.SYSTEM_ACTIONS to emptySet())
        val g = PluginGrant(
            "com.example.plugin",
            mapOf(PluginPermission.SYSTEM_ACTIONS to emptySet()),
            advancedAccess = false,
        )
        val d = gate.check(state(m, g), PluginPermission.SYSTEM_ACTIONS)
        assertEquals(DenyReason.ADVANCED_NOT_ENABLED, (d as PermissionDecision.Deny).reason)
    }

    @Test
    fun `system actions with advanced access is allowed`() {
        val m = manifest(PluginPermission.SYSTEM_ACTIONS to emptySet())
        val g = PluginGrant(
            "com.example.plugin",
            mapOf(PluginPermission.SYSTEM_ACTIONS to emptySet()),
            advancedAccess = true,
        )
        assertTrue(gate.check(state(m, g), PluginPermission.SYSTEM_ACTIONS).isAllowed)
    }

    // ---------------------------------------------------------------- scopes

    @Test
    fun `scoped read allows a granted scope and denies a withheld one`() {
        val m = manifest(PluginPermission.READ_APPROVED_DATA to setOf("sessions", "thermals"))
        val g = PluginGrant(
            "com.example.plugin",
            mapOf(PluginPermission.READ_APPROVED_DATA to setOf("sessions")),
        )
        val s = state(m, g)
        assertTrue(gate.check(s, PluginPermission.READ_APPROVED_DATA, "sessions").isAllowed)
        val d = gate.check(s, PluginPermission.READ_APPROVED_DATA, "thermals")
        assertEquals(DenyReason.SCOPE_WITHHELD, (d as PermissionDecision.Deny).reason)
    }

    // ---------------------------------------------------------------- namespacing

    @Test
    fun `namespace qualifies a simple key under the plugin prefix`() {
        val q = PluginNamespace.qualify("com.example.plugin", "theme_mode")
        assertEquals("plugin.com.example.plugin.theme_mode", q)
        assertTrue(PluginNamespace.belongsTo("com.example.plugin", q!!))
    }

    @Test
    fun `namespace rejects traversal-shaped and illegal keys`() {
        assertNull(PluginNamespace.qualify("com.example.plugin", "../other"))
        assertNull(PluginNamespace.qualify("com.example.plugin", "a/b"))
        assertNull(PluginNamespace.qualify("com.example.plugin", ""))
        assertNull(PluginNamespace.qualify("com.example.plugin", "a..b"))
        assertNull(PluginNamespace.qualify("com.example.plugin", "x".repeat(200)))
    }

    @Test
    fun `one plugin's qualified key does not belong to another`() {
        val q = PluginNamespace.qualify("com.a.one", "k")!!
        assertFalse(PluginNamespace.belongsTo("com.b.two", q))
    }
}
