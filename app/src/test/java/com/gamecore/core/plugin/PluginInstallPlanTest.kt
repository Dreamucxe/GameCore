package com.gamecore.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Part I gate: the install/preview plan — risk grouping and the unsupported-install block. */
class PluginInstallPlanTest {

    private fun manifest(
        api: Int = 1,
        minHost: Int = 27,
        perms: List<PluginPermission> = emptyList(),
    ) = PluginManifest(
        id = "com.example.p", name = "P", version = 1, versionName = "1.0",
        api = api, minHostVersion = minHost, developer = "Dev", description = "d",
        permissions = perms.map { RequestedPermission(it) },
        declares = setOf(PluginSection.THEME), changelog = "c",
    )

    @Test
    fun `permissions are grouped by tier with HIGH first`() {
        val plan = PluginInstallPlan.from(
            manifest(perms = listOf(
                PluginPermission.CHANGE_UI,          // LOW
                PluginPermission.CREATE_OVERLAY,     // MEDIUM
                PluginPermission.SYSTEM_ACTIONS,     // HIGH
            )),
            sha256 = "a".repeat(64),
        )
        assertEquals(RiskTier.HIGH, plan.permissionsByTier.keys.first())
        assertEquals(RiskTier.HIGH, plan.highestTier)
        assertTrue(plan.requestsAdvancedAccess)
    }

    @Test
    fun `a supported plugin can install`() {
        val plan = PluginInstallPlan.from(manifest(), "a".repeat(64))
        assertTrue(plan.canInstall)
    }

    @Test
    fun `a plugin needing a newer host cannot install`() {
        val plan = PluginInstallPlan.from(manifest(minHost = 9999), "a".repeat(64))
        assertFalse(plan.canInstall)
    }

    @Test
    fun `a plugin targeting a newer api cannot install`() {
        val plan = PluginInstallPlan.from(manifest(api = PluginApi.CURRENT + 1), "a".repeat(64))
        assertFalse(plan.canInstall)
    }

    @Test
    fun `a plugin without advanced permissions is not flagged advanced`() {
        val plan = PluginInstallPlan.from(manifest(perms = listOf(PluginPermission.CHANGE_UI)), "a".repeat(64))
        assertFalse(plan.requestsAdvancedAccess)
    }
}
