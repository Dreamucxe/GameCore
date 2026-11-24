package com.gamecore.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The set planner is the safety boundary for per-game plugin sets: it may switch a plugin on, never
 * grant it anything, and never overrule a state the user or the supervisor chose.
 */
class PluginSetPlannerTest {

    private fun plugin(
        id: String,
        enabled: Boolean = false,
        suspended: Boolean = false,
        quarantined: Boolean = false,
        api: Int = 1,
        permissions: List<PluginPermission> = emptyList(),
        granted: Set<PluginPermission> = emptySet(),
        advancedAccess: Boolean = false,
    ) = InstalledPlugin(
        manifest = PluginManifest(
            id = id,
            name = id.substringAfterLast('.'),
            version = 1,
            versionName = "1.0",
            api = api,
            minHostVersion = 27,
            developer = "Dev",
            description = "",
            permissions = permissions.map { RequestedPermission(it) },
            declares = emptySet(),
            changelog = "",
        ),
        sha256 = "a".repeat(64),
        origin = PluginOrigin.SIDELOADED,
        enabled = enabled,
        suspended = suspended,
        grant = PluginGrant(
            pluginId = id,
            granted = granted.associateWith { emptySet<String>() },
            advancedAccess = advancedAccess,
        ),
        quarantined = quarantined,
    )

    private fun group(vararg ids: String) = PluginGroup(
        id = PluginGrouping.CUSTOM_PREFIX + "set",
        name = "FPS set",
        pluginIds = ids.toSet(),
        automatic = false,
        gamePackage = "com.example.game",
        gameLabel = "Example Game",
    )

    @Test
    fun `a disabled plugin with no permissions is safe to switch on`() {
        val plan = PluginSetPlanner.plan(group("a", "b"), listOf(plugin("a"), plugin("b", enabled = true)))

        assertEquals(listOf("a"), plan.toEnable)
        assertEquals(listOf("b"), plan.alreadyOn)
        assertEquals(null, plan.skipped.firstOrNull())
        assertTrue(plan.hasWork)
    }

    @Test
    fun `an ungranted permission is skipped rather than half-started`() {
        val plan = PluginSetPlanner.plan(
            group("a"),
            listOf(plugin("a", permissions = listOf(PluginPermission.CHANGE_UI))),
        )

        assertTrue(plan.toEnable.isEmpty())
        assertEquals(PluginSetSkipReason.UNGRANTED, plan.skipped.single().reason)
    }

    @Test
    fun `a granted permission permits the switch-on`() {
        val plan = PluginSetPlanner.plan(
            group("a"),
            listOf(
                plugin(
                    id = "a",
                    permissions = listOf(PluginPermission.CHANGE_UI),
                    granted = setOf(PluginPermission.CHANGE_UI),
                ),
            ),
        )

        assertEquals(listOf("a"), plan.toEnable)
    }

    @Test
    fun `advanced system actions need their separate opt-in`() {
        val plan = PluginSetPlanner.plan(
            group("a"),
            listOf(
                plugin(
                    id = "a",
                    permissions = listOf(PluginPermission.SYSTEM_ACTIONS),
                    granted = setOf(PluginPermission.SYSTEM_ACTIONS),
                    advancedAccess = false,
                ),
            ),
        )

        assertEquals(PluginSetSkipReason.UNGRANTED, plan.skipped.single().reason)
    }

    @Test
    fun `quarantine suspension incompatibility and missing all block`() {
        val plan = PluginSetPlanner.plan(
            group("a", "b", "c", "d"),
            listOf(
                plugin("a", quarantined = true),
                plugin("b", suspended = true),
                plugin("c", api = 999),
            ),
        )

        assertEquals(
            listOf(
                PluginSetSkipReason.QUARANTINED,
                PluginSetSkipReason.SUSPENDED,
                PluginSetSkipReason.UNSUPPORTED,
                PluginSetSkipReason.MISSING,
            ),
            plan.skipped.map { it.reason },
        )
        assertFalse(plan.hasWork)
        assertTrue(plan.skipped.all { it.reason.isSafetyBlock || it.reason == PluginSetSkipReason.MISSING })
    }

    @Test
    fun `the global switch pauses the whole plan`() {
        val plan = PluginSetPlanner.plan(
            group("a"),
            listOf(plugin("a")),
            killSwitchOn = true,
        )

        assertEquals(PluginSetSkipReason.GLOBAL_SWITCH, plan.skipped.single().reason)
    }

    @Test
    fun `a group with an enabled plugin needs no action`() {
        val plan = PluginSetPlanner.plan(group("a"), listOf(plugin("a", enabled = true)))

        assertFalse(plan.hasWork)
        assertTrue(plan.summary.contains("already on"))
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun `the plan is sorted so the same set always produces the same calls`() {
        val plan = PluginSetPlanner.plan(
            group("z", "a", "m"),
            listOf(plugin("z"), plugin("a"), plugin("m")),
        )

        assertEquals(listOf("a", "m", "z"), plan.toEnable)
    }
}
