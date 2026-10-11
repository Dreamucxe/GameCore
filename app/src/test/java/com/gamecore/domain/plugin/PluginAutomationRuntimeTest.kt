package com.gamecore.domain.plugin

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.plugin.ActionRequest
import com.gamecore.core.plugin.ActivePluginAutomation
import com.gamecore.core.plugin.AuditEntry
import com.gamecore.core.plugin.BrokerAction
import com.gamecore.core.plugin.BrokerReject
import com.gamecore.core.plugin.BrokerResult
import com.gamecore.core.plugin.PluginAuditLog
import com.gamecore.core.plugin.PluginGrant
import com.gamecore.core.plugin.PluginManifest
import com.gamecore.core.plugin.PluginPermission
import com.gamecore.core.plugin.PluginPermissionGate
import com.gamecore.core.plugin.PluginRateLimiter
import com.gamecore.core.plugin.PluginRuntimeState
import com.gamecore.core.plugin.RequestedPermission
import com.gamecore.core.plugin.ShizukuActionBroker
import com.gamecore.core.plugin.content.AutomationEngine
import com.gamecore.core.plugin.content.AutomationRule
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import com.gamecore.core.shizuku.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WF-7 gate: the plugin automation runtime. These tests prove the decision half in isolation —
 * which actions fire for a (trigger, rules, variables) and, just as importantly, which do not —
 * and that the dispatch half cannot escape the broker: it hands the broker a confirmer that
 * refuses every HIGH-tier action, and a plugin without the SYSTEM_ACTIONS advanced opt-in is
 * denied by the real gate before anything touches the shell.
 *
 * The broker fakes mirror ShizukuActionBrokerTest's: a real [ShizukuActionBroker] built with a
 * recording [FakeShell], so the security is checked against the real gate and real limiter rather
 * than a mock's say-so. The pure [PluginAutomationRuntime.plan] and the seam-based
 * [PluginAutomationRuntime.dispatch] are both static, so none of this needs the heavy
 * PerformanceMonitor / GamingCoordinator the full runtime holds.
 */
class PluginAutomationRuntimeTest {

    // ---- fakes, mirroring ShizukuActionBrokerTest ----

    /** Records every command it is given and returns a scripted result. */
    private class FakeShell(
        var available: Boolean = true,
        private val onExecute: (ShellCommand) -> ShellResult,
    ) : ElevatedShell {
        val executed = mutableListOf<ShellCommand>()
        override val accessLevel = AccessLevel.SHIZUKU
        override suspend fun isAvailable() = available
        override suspend fun execute(command: ShellCommand, timeoutMillis: Long): ShellResult {
            executed += command
            return onExecute(command)
        }
    }

    private fun ok() = ShellResult(0, "", "", AccessLevel.SHIZUKU)

    private fun broker(shell: ElevatedShell) = ShizukuActionBroker(
        shell = shell,
        gate = PluginPermissionGate(),
        rateLimiter = PluginRateLimiter(now = { 0L }),
        auditLog = PluginAuditLog(),
        now = { 0L },
    )

    private fun manifest() = PluginManifest(
        id = "com.example.perf", name = "Perf", version = 1, versionName = "1.0",
        api = 1, minHostVersion = 1, developer = "T", description = "",
        permissions = listOf(RequestedPermission(PluginPermission.SYSTEM_ACTIONS, emptySet())),
        declares = emptySet(), changelog = "",
    )

    /** A plugin granted SYSTEM_ACTIONS *with* the advanced opt-in — allowed to act. */
    private fun advancedState(): PluginRuntimeState {
        val g = PluginGrant(
            "com.example.perf",
            mapOf(PluginPermission.SYSTEM_ACTIONS to emptySet()),
            advancedAccess = true,
        )
        return PluginRuntimeState.of(manifest(), g, enabled = true, suspended = false, systemKillSwitch = false)
    }

    /** The same plugin granted SYSTEM_ACTIONS but *without* the advanced opt-in — denied by the gate. */
    private fun withoutAdvancedState(): PluginRuntimeState {
        val g = PluginGrant(
            "com.example.perf",
            mapOf(PluginPermission.SYSTEM_ACTIONS to emptySet()),
            advancedAccess = false,
        )
        return PluginRuntimeState.of(manifest(), g, enabled = true, suspended = false, systemKillSwitch = false)
    }

    private fun ruleOf(
        id: String,
        trigger: AutomationRule.Trigger,
        actionIds: List<String>,
        conditions: List<AutomationRule.Condition> = emptyList(),
    ) = AutomationRule(id = id, trigger = trigger, conditions = conditions, actionIds = actionIds)

    private fun automation(state: PluginRuntimeState, rules: List<AutomationRule>) =
        ActivePluginAutomation(
            pluginId = state.grant.pluginId,
            pluginName = "Perf",
            state = state,
            rules = rules,
        )

    private val engine = AutomationEngine()

    // ---- the pure plan(): which actions fire, and which do not ----

    @Test
    fun `plan fires a rule whose trigger matches and whose conditions hold`() {
        val rule = ruleOf(
            id = "r1",
            trigger = AutomationRule.Trigger.THERMAL_HIGH,
            actionIds = listOf("set_peak_refresh_rate"),
            conditions = listOf(AutomationRule.Condition("thermal", AutomationRule.Condition.Op.GTE, 80.0)),
        )
        val decisions = PluginAutomationRuntime.plan(
            engine,
            AutomationRule.Trigger.THERMAL_HIGH,
            listOf(automation(advancedState(), listOf(rule))),
            mapOf("thermal" to 85.0),
        )
        assertEquals(1, decisions.size)
        assertEquals(BrokerAction.SET_PEAK_REFRESH_RATE, decisions.single().action)
        assertEquals("com.example.perf", decisions.single().pluginId)
    }

    @Test
    fun `plan fires nothing when the trigger does not match`() {
        val rule = ruleOf("r1", AutomationRule.Trigger.GAME_LAUNCHED, listOf("set_peak_refresh_rate"))
        val decisions = PluginAutomationRuntime.plan(
            engine,
            AutomationRule.Trigger.GAME_CLOSED,
            listOf(automation(advancedState(), listOf(rule))),
            emptyMap(),
        )
        assertTrue(decisions.isEmpty())
    }
    @Test
    fun `plan fires nothing when a condition reads a variable the feed does not provide`() {
        val rule = ruleOf(
            id = "r1",
            trigger = AutomationRule.Trigger.THERMAL_HIGH,
            actionIds = listOf("set_peak_refresh_rate"),
            conditions = listOf(AutomationRule.Condition("not_a_variable", AutomationRule.Condition.Op.GT, 1.0)),
        )
        val decisions = PluginAutomationRuntime.plan(
            engine,
            AutomationRule.Trigger.THERMAL_HIGH,
            listOf(automation(advancedState(), listOf(rule))),
            mapOf("thermal" to 85.0),
        )
        assertTrue(decisions.isEmpty())
    }

    @Test
    fun `plan drops an action id that does not resolve to a broker action`() {
        // Install-time validation already rejects this; plan() still must not carry a phantom id.
        val rule = ruleOf("r1", AutomationRule.Trigger.GAME_LAUNCHED, listOf("not_an_action"))
        val decisions = PluginAutomationRuntime.plan(
            engine,
            AutomationRule.Trigger.GAME_LAUNCHED,
            listOf(automation(advancedState(), listOf(rule))),
            emptyMap(),
        )
        assertTrue(decisions.isEmpty())
    }
    // ---- dispatch(): the runtime cannot escape the broker ----

    @Test
    fun `dispatch hands the broker a confirmer that refuses every high-risk action`() = runBlocking {
        // No shipped BrokerAction is HIGH tier, so this fake stands in for the broker's HIGH branch:
        // it captures the confirmer the runtime passed and refuses when that confirmer says no, the
        // way the real broker refuses with high_risk_unconfirmed. The runtime must never auto-approve
        // a HIGH action — now, or when one is first added to the registry.
        var captured: (suspend (BrokerAction) -> Boolean)? = null
        val fakeRun: suspend (PluginRuntimeState, ActionRequest, suspend (BrokerAction) -> Boolean) -> BrokerResult =
            { _, _, confirm ->
                captured = confirm
                if (confirm(BrokerAction.SET_SCREEN_BRIGHTNESS)) {
                    BrokerResult.Applied(BrokerAction.SET_SCREEN_BRIGHTNESS, "80", null)
                } else {
                    BrokerResult.Rejected(BrokerReject.PERMISSION_DENIED, "high_risk_unconfirmed")
                }
            }
        val recorded = mutableListOf<Pair<AuditEntry, Long?>>()
        val decision = PluginAutomationRuntime.Decision(
            pluginId = "com.example.perf",
            state = advancedState(),
            action = BrokerAction.SET_PEAK_REFRESH_RATE,
            value = "120",
        )
        val result = PluginAutomationRuntime.dispatch(
            decision = decision,
            now = { 0L },
            run = fakeRun,
            append = { e, d -> recorded += e to d },
        )
        assertTrue(result is BrokerResult.Rejected)
        assertEquals(BrokerReject.PERMISSION_DENIED, (result as BrokerResult.Rejected).reason)
        // The confirmer the runtime handed over refuses every action in the registry, HIGH included.
        for (action in BrokerAction.entries) assertFalse(captured!!(action))
        assertEquals(AuditEntry.Outcome.DENIED, recorded.single().first.outcome)
    }
    @Test
    fun `dispatch denies a plugin without the advanced opt-in before the shell is touched`() = runBlocking {
        val shell = FakeShell { ok() }
        val recorded = mutableListOf<Pair<AuditEntry, Long?>>()
        val decision = PluginAutomationRuntime.Decision(
            pluginId = "com.example.perf",
            state = withoutAdvancedState(),
            action = BrokerAction.SET_PEAK_REFRESH_RATE,
            value = "120",
        )
        val result = PluginAutomationRuntime.dispatch(
            decision = decision,
            now = { 0L },
            run = broker(shell)::run,
            append = { e, d -> recorded += e to d },
        )
        assertEquals(BrokerReject.PERMISSION_DENIED, (result as BrokerResult.Rejected).reason)
        assertTrue(shell.executed.isEmpty())
        assertEquals(AuditEntry.Outcome.DENIED, recorded.single().first.outcome)
    }

    @Test
    fun `dispatch applies a granted low-risk action and records it ALLOWED with a duration`() = runBlocking {
        val shell = FakeShell { ok() }
        val recorded = mutableListOf<Pair<AuditEntry, Long?>>()
        var clock = 0L
        val decision = PluginAutomationRuntime.Decision(
            pluginId = "com.example.perf",
            state = advancedState(),
            action = BrokerAction.SET_PEAK_REFRESH_RATE,
            value = "120",
        )
        val result = PluginAutomationRuntime.dispatch(
            decision = decision,
            now = { clock += 5; clock },
            run = broker(shell)::run,
            append = { e, d -> recorded += e to d },
        )
        assertTrue(result is BrokerResult.Applied)
        assertTrue(shell.executed.single().argv.contains("120"))
        val entry = recorded.single()
        assertEquals(AuditEntry.Outcome.ALLOWED, entry.first.outcome)
        assertEquals("automation:set_peak_refresh_rate", entry.first.operation)
        assertEquals(5L, entry.second)
    }
}
