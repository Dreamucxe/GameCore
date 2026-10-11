package com.gamecore.core.plugin.content

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.plugin.ActionRequest
import com.gamecore.core.plugin.BrokerAction
import com.gamecore.core.plugin.PluginAuditLog
import com.gamecore.core.plugin.PluginPermissionGate
import com.gamecore.core.plugin.PluginRateLimiter
import com.gamecore.core.plugin.ShizukuActionBroker
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import com.gamecore.core.shizuku.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host-owned default value for every automatable action. These run each default through the
 * *real* broker's pure [ShizukuActionBroker.plan] step — the same validate-and-clamp the live
 * path uses — and assert it plans as [ShizukuActionBroker.Plan.Ready] with no clamp fired, so
 * the proof that "every value is valid as-is" is against the broker itself, not a restatement of
 * the form rules. Iterating [BrokerAction.entries] makes the coverage exhaustive: a new action
 * added to the registry is tested here the moment it exists.
 */
class AutomationActionDefaultsTest {

    /** The planner is pure and never consults the shell, so this one only has to exist. */
    private class UnusedShell : ElevatedShell {
        override val accessLevel = AccessLevel.SHIZUKU
        override suspend fun isAvailable() = true
        override suspend fun execute(command: ShellCommand, timeoutMillis: Long): ShellResult =
            ShellResult(0, "", "", AccessLevel.SHIZUKU)
    }

    private fun broker() = ShizukuActionBroker(
        shell = UnusedShell(),
        gate = PluginPermissionGate(),
        rateLimiter = PluginRateLimiter(now = { 0L }),
        auditLog = PluginAuditLog(),
        now = { 0L },
    )

    @Test
    fun `every registered action has a default the broker plans without rejecting`() {
        val broker = broker()
        for (action in BrokerAction.entries) {
            val value = AutomationActionDefaults.valueFor(action)
            val plan = broker.plan(ActionRequest(action.id, value))
            assertTrue(
                "valueFor(${action.id}) = '$value' did not plan Ready: $plan",
                plan is ShizukuActionBroker.Plan.Ready,
            )
        }
    }

    @Test
    fun `every default is honoured exactly as given, never clamped`() {
        val broker = broker()
        for (action in BrokerAction.entries) {
            val value = AutomationActionDefaults.valueFor(action)
            val plan = broker.plan(ActionRequest(action.id, value)) as ShizukuActionBroker.Plan.Ready
            assertFalse("valueFor(${action.id}) was clamped", plan.wasClamped)
            assertEquals("valueFor(${action.id}) was rewritten", value, plan.appliedValue)
        }
    }

    @Test
    fun `the chosen defaults are the documented, performance-oriented values`() {
        assertEquals("120", AutomationActionDefaults.valueFor(BrokerAction.SET_PEAK_REFRESH_RATE))
        assertEquals("120", AutomationActionDefaults.valueFor(BrokerAction.SET_MIN_REFRESH_RATE))
        assertEquals("0", AutomationActionDefaults.valueFor(BrokerAction.SET_WINDOW_ANIMATION_SCALE))
        assertEquals("0", AutomationActionDefaults.valueFor(BrokerAction.SET_TRANSITION_ANIMATION_SCALE))
        assertEquals("0", AutomationActionDefaults.valueFor(BrokerAction.SET_ANIMATOR_DURATION_SCALE))
        assertEquals("1", AutomationActionDefaults.valueFor(BrokerAction.SET_LOW_POWER))
        assertEquals("200", AutomationActionDefaults.valueFor(BrokerAction.SET_SCREEN_BRIGHTNESS))
    }
}
