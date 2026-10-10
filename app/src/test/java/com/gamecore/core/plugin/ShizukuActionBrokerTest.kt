package com.gamecore.core.plugin

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import com.gamecore.core.shizuku.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Part E gate: the Shizuku Action Broker. The security core of the run. These tests prove a
 * plugin cannot invent an action, cannot inject a command, cannot exceed its grant, cannot
 * run without the elevated shell, is rate-limited, is clamped rather than passed through, and
 * that auto-revert captures a prior reading. A fake shell records exactly what argv it was
 * asked to run so template-injection resistance is checked against the real command, not a
 * mock's say-so.
 */
class ShizukuActionBrokerTest {

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
    private fun okValue(v: String) = ShellResult(0, v, "", AccessLevel.SHIZUKU)

    private fun broker(
        shell: ElevatedShell,
        t: () -> Long = { 0L },
        perWindow: Int = 20,
    ) = ShizukuActionBroker(
        shell = shell,
        gate = PluginPermissionGate(),
        rateLimiter = PluginRateLimiter(now = t),
        auditLog = PluginAuditLog(),
        now = t,
        actionsPerWindow = perWindow,
        windowMs = 10_000L,
    )

    private fun advancedState(): PluginRuntimeState {
        val m = PluginManifest(
            id = "com.example.perf", name = "Perf", version = 1, versionName = "1.0",
            api = 1, minHostVersion = 1, developer = "T", description = "",
            permissions = listOf(RequestedPermission(PluginPermission.SYSTEM_ACTIONS, emptySet())),
            declares = emptySet(), changelog = "",
        )
        val g = PluginGrant("com.example.perf", mapOf(PluginPermission.SYSTEM_ACTIONS to emptySet()), advancedAccess = true)
        return PluginRuntimeState.of(m, g, enabled = true, suspended = false, systemKillSwitch = false)
    }

    // ---------------------------------------------------------------- the registry is closed

    @Test
    fun `an unknown action id is rejected and never touches the shell`() = runBlocking {
        val shell = FakeShell { ok() }
        val r = broker(shell).run(advancedState(), ActionRequest("rm_rf_everything", "1"))
        assertEquals(BrokerReject.UNKNOWN_ACTION, (r as BrokerResult.Rejected).reason)
        assertTrue(shell.executed.isEmpty())
    }

    // ---------------------------------------------------------------- template injection

    @Test
    fun `a value carrying shell metacharacters is rejected by the form, not concatenated`() = runBlocking {
        val shell = FakeShell { ok() }
        val r = broker(shell).run(advancedState(), ActionRequest("set_peak_refresh_rate", "120; rm -rf /"))
        assertEquals(BrokerReject.INVALID_VALUE, (r as BrokerResult.Rejected).reason)
        assertTrue(shell.executed.isEmpty())
    }

    @Test
    fun `a valid value is applied as an argv element with the fixed settings template`() = runBlocking {
        val shell = FakeShell { ok() }
        val r = broker(shell).run(advancedState(), ActionRequest("set_peak_refresh_rate", "120"))
        assertTrue(r is BrokerResult.Applied)
        // The command's argv is GameCore's `settings put system peak_refresh_rate 120` shape:
        // the plugin value appears only as its own element, never spliced into another token.
        val argv = shell.executed.single().argv
        assertTrue(argv.contains("120"))
        assertTrue(argv.none { it.contains(";") || it.contains("rm") })
        assertTrue(argv.contains("peak_refresh_rate"))
    }

    // ---------------------------------------------------------------- permission gating

    @Test
    fun `without advanced access the broker refuses even a valid request`() = runBlocking {
        val shell = FakeShell { ok() }
        val m = PluginManifest(
            id = "p", name = "P", version = 1, versionName = "1.0", api = 1, minHostVersion = 1,
            developer = "T", description = "",
            permissions = listOf(RequestedPermission(PluginPermission.SYSTEM_ACTIONS, emptySet())),
            declares = emptySet(), changelog = "",
        )
        val g = PluginGrant("p", mapOf(PluginPermission.SYSTEM_ACTIONS to emptySet()), advancedAccess = false)
        val state = PluginRuntimeState.of(m, g, true, false, false)
        val r = broker(shell).run(state, ActionRequest("set_peak_refresh_rate", "120"))
        assertEquals(BrokerReject.PERMISSION_DENIED, (r as BrokerResult.Rejected).reason)
        assertTrue(shell.executed.isEmpty())
    }

    @Test
    fun `kill switch blocks the broker`() = runBlocking {
        val shell = FakeShell { ok() }
        val base = advancedState()
        val killed = base.copy(systemKillSwitch = true)
        val r = broker(shell).run(killed, ActionRequest("set_peak_refresh_rate", "120"))
        assertEquals(BrokerReject.PERMISSION_DENIED, (r as BrokerResult.Rejected).reason)
        assertTrue(shell.executed.isEmpty())
    }

    // ---------------------------------------------------------------- shizuku absent

    @Test
    fun `with the shell unavailable nothing is attempted`() = runBlocking {
        val shell = FakeShell(available = false) { ok() }
        val r = broker(shell).run(advancedState(), ActionRequest("set_peak_refresh_rate", "120"))
        assertEquals(BrokerReject.SHIZUKU_UNAVAILABLE, (r as BrokerResult.Rejected).reason)
        assertTrue(shell.executed.isEmpty())
    }

    // ---------------------------------------------------------------- clamp

    @Test
    fun `an out-of-range numeric value is clamped to the bound and reported`() = runBlocking {
        val shell = FakeShell { ok() }
        val r = broker(shell).run(advancedState(), ActionRequest("set_window_animation_scale", "99"))
        assertTrue(r is BrokerResult.Clamped)
        r as BrokerResult.Clamped
        assertEquals("99", r.requested)
        assertEquals("10.0", r.appliedValue)
        assertTrue(shell.executed.single().argv.contains("10.0"))
    }

    // ---------------------------------------------------------------- auto-revert

    @Test
    fun `an auto-revert action reads the prior value before writing`() = runBlocking {
        // getSetting returns 200; the write succeeds. Applied result carries prior=200.
        val shell = FakeShell { cmd ->
            if (cmd is ShellCommand.GetSetting) okValue("200") else ok()
        }
        val r = broker(shell).run(advancedState(), ActionRequest("set_screen_brightness", "80"))
        assertTrue(r is BrokerResult.Applied)
        assertEquals("200", (r as BrokerResult.Applied).priorValue)
        // Two commands: the read, then the write — in that order.
        assertTrue(shell.executed[0] is ShellCommand.GetSetting)
        assertEquals(2, shell.executed.size)
    }

    @Test
    fun `a non-reverting action does not read first and carries a null prior`() = runBlocking {
        val shell = FakeShell { ok() }
        val r = broker(shell).run(advancedState(), ActionRequest("set_peak_refresh_rate", "120"))
        assertNull((r as BrokerResult.Applied).priorValue)
        assertEquals(1, shell.executed.size)
    }

    // ---------------------------------------------------------------- rate limit

    @Test
    fun `the broker rate-limits a flood of actions`() = runBlocking {
        val shell = FakeShell { ok() }
        val b = broker(shell, perWindow = 3)
        val s = advancedState()
        repeat(3) { assertTrue(b.run(s, ActionRequest("set_peak_refresh_rate", "120")) is BrokerResult.Applied) }
        val r = b.run(s, ActionRequest("set_peak_refresh_rate", "120"))
        assertEquals(BrokerReject.RATE_LIMITED, (r as BrokerResult.Rejected).reason)
    }

    // ---------------------------------------------------------------- execution failure

    @Test
    fun `a shell failure becomes a typed EXECUTION_FAILED, not a throw`() = runBlocking {
        val shell = FakeShell { ShellResult(1, "", "denied", AccessLevel.SHIZUKU) }
        val r = broker(shell).run(advancedState(), ActionRequest("set_peak_refresh_rate", "120"))
        assertEquals(BrokerReject.EXECUTION_FAILED, (r as BrokerResult.Rejected).reason)
    }
}
