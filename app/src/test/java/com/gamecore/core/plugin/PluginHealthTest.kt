package com.gamecore.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The health verdict is a pure reading of registry state plus the audit ring. These tests pin the
 * order of authority: a quarantine is not outvoted by a quiet audit, and a plugin the user turned off
 * is never reported as a fault.
 */
class PluginHealthTest {

    private fun plugin(
        id: String = "com.example.plugin",
        enabled: Boolean = true,
        suspended: Boolean = false,
        quarantined: Boolean = false,
        api: Int = 1,
        minHost: Int = 27,
        permissions: List<PluginPermission> = emptyList(),
    ) = InstalledPlugin(
        manifest = PluginManifest(
            id = id,
            name = "Example",
            version = 1,
            versionName = "1.0",
            api = api,
            minHostVersion = minHost,
            developer = "Dev",
            description = "A plugin",
            permissions = permissions.map { RequestedPermission(it) },
            declares = emptySet(),
            changelog = "",
        ),
        sha256 = "a".repeat(64),
        origin = PluginOrigin.SIDELOADED,
        enabled = enabled,
        suspended = suspended,
        grant = PluginGrant.none(id),
        quarantined = quarantined,
    )

    private fun entry(
        outcome: AuditEntry.Outcome,
        at: Long = 1_700_000_000_000L,
        pluginId: String = "com.example.plugin",
    ) = AuditEntry(
        timestamp = at,
        pluginId = pluginId,
        operation = "overlay.show",
        outcome = outcome,
        detail = "",
    )

    @Test
    fun `a quarantine outranks a quiet audit`() {
        val health = PluginHealth.assess(plugin(quarantined = true), emptyList())

        assertEquals(PluginHealthState.QUARANTINED, health.state)
        assertTrue(health.state.isProblem)
    }

    @Test
    fun `an unsupported plugin is reported before its enabled state`() {
        val health = PluginHealth.assess(plugin(api = 999, enabled = false), emptyList())

        assertEquals(PluginHealthState.UNSUPPORTED, health.state)
        assertTrue(health.state.isProblem)
    }

    @Test
    fun `a suspension is a problem even when the user has it switched off`() {
        val health = PluginHealth.assess(plugin(suspended = true, enabled = false), emptyList())

        assertEquals(PluginHealthState.SUSPENDED, health.state)
        assertTrue(health.state.isProblem)
    }

    @Test
    fun `a plugin the user switched off is not a health problem`() {
        val health = PluginHealth.assess(plugin(enabled = false), emptyList())

        assertEquals(PluginHealthState.OFF, health.state)
        assertFalse(health.state.isProblem)
    }

    @Test
    fun `the global switch pauses rather than faults an enabled plugin`() {
        val health = PluginHealth.assess(plugin(), emptyList(), killSwitchOn = true)

        assertEquals(PluginHealthState.PAUSED, health.state)
        assertFalse(health.state.isProblem)
    }

    @Test
    fun `a successful audit is healthy`() {
        val health = PluginHealth.assess(
            plugin(),
            listOf(entry(AuditEntry.Outcome.ALLOWED), entry(AuditEntry.Outcome.CLAMPED)),
        )

        assertEquals(PluginHealthState.HEALTHY, health.state)
        assertEquals(1, health.allowedCalls)
        assertTrue(health.hasActivity)
    }

    @Test
    fun `one refusal is the gate working, three is attention`() {
        val one = PluginHealth.assess(plugin(), listOf(entry(AuditEntry.Outcome.DENIED)))
        val three = PluginHealth.assess(
            plugin(),
            listOf(
                entry(AuditEntry.Outcome.DENIED, at = 1),
                entry(AuditEntry.Outcome.DENIED, at = 2),
                entry(AuditEntry.Outcome.DENIED, at = 3),
            ),
        )

        assertEquals(PluginHealthState.HEALTHY, one.state)
        assertEquals(PluginHealthState.ATTENTION, three.state)
        assertEquals(3, three.refusedCalls)
        assertTrue(three.state.isProblem)
    }

    @Test
    fun `a failed call is attention at the first occurrence`() {
        val health = PluginHealth.assess(plugin(), listOf(entry(AuditEntry.Outcome.ERROR)))

        assertEquals(PluginHealthState.ATTENTION, health.state)
        assertEquals(1, health.failedCalls)
    }

    @Test
    fun `a rate-limited call is attention and wins over a plain error`() {
        val health = PluginHealth.assess(
            plugin(),
            listOf(
                entry(AuditEntry.Outcome.ERROR),
                entry(AuditEntry.Outcome.RATE_LIMITED, at = 2),
            ),
        )

        assertEquals(PluginHealthState.ATTENTION, health.state)
        assertEquals(1, health.rateLimitedCalls)
        assertTrue(health.summary.contains("call limit"))
    }

    @Test
    fun `the most recent audit entry is the last activity`() {
        val health = PluginHealth.assess(
            plugin(),
            listOf(
                entry(AuditEntry.Outcome.ALLOWED, at = 10),
                entry(AuditEntry.Outcome.DENIED, at = 20),
            ),
        )

        assertEquals(20L, health.lastActivityMillis)
        assertTrue(health.lastActivityLabel?.contains("refused") == true)
    }

    @Test
    fun `a summary counts problems without counting choices`() {
        val summary = PluginHealth.summarize(
            listOf(
                PluginHealth.assess(plugin(id = "a"), emptyList()),
                PluginHealth.assess(plugin(id = "b"), listOf(entry(AuditEntry.Outcome.ERROR))),
                PluginHealth.assess(plugin(id = "c", enabled = false), emptyList()),
                PluginHealth.assess(plugin(id = "d", quarantined = true), emptyList()),
            ),
        )

        assertEquals(4, summary.total)
        assertEquals(1, summary.healthy)
        assertEquals(1, summary.attention)
        assertEquals(1, summary.off)
        assertEquals(1, summary.quarantined)
        assertEquals(2, summary.problemCount)
        assertTrue(summary.headline.contains("2 of 4"))
    }

    @Test
    fun `a kill switch summary says paused rather than unhealthy`() {
        val summary = PluginHealth.summarize(
            listOf(
                PluginHealth.assess(plugin(id = "a"), emptyList(), killSwitchOn = true),
                PluginHealth.assess(plugin(id = "b"), emptyList(), killSwitchOn = true),
            ),
        )

        assertEquals(2, summary.paused)
        assertEquals(0, summary.problemCount)
        assertTrue(summary.headline.contains("paused"))
    }

    @Test
    fun `an empty install has an honest headline`() {
        val summary = PluginHealth.summarize(emptyList())

        assertTrue(summary.isEmpty)
        assertEquals("No plugins installed", summary.headline)
    }
}
