package com.gamecore.domain.trigger

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.Observed
import com.gamecore.core.common.RestrictionReason
import com.gamecore.core.model.ShizukuState
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import com.gamecore.core.shizuku.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PointTapController]'s behaviour, verified against a fake elevated shell whose `available` flag flips.
 * The two things the feature promises are the two things proven here: when the shell is not live nothing
 * is ever injected and the caller is told the live Shizuku reason, and when it is live the exact pixels
 * handed in reach the shell unchanged (there is no coordinate math in this seam to get wrong). A shell
 * that runs and reports a non-zero exit is a [TapResult.Failed] carrying the shell's own reason, kept
 * distinct from the "nothing was attempted" of [TapResult.Unavailable].
 */
class PointTapControllerTest {

    private fun controller(
        shell: ElevatedShell,
        state: ShizukuState = ShizukuState.RUNNING_PERMISSION_GRANTED,
    ) = PointTapController(shell, Dispatchers.Unconfined) { state }

    @Test
    fun `tap injects one input tap carrying the given pixels when the shell is live`() = runBlocking {
        val shell = FakeShell(available = true)
        val result = controller(shell).tap(540, 960)

        assertEquals(TapResult.Success, result)
        assertEquals(1, shell.executed.size)
        // Derive the expected argv from the same factory the controller uses: this proves the coordinates
        // passed straight through rather than pinning the argv shape the integrator will choose.
        assertEquals(checkNotNull(ShellCommand.inputTap(540, 960)).argv, shell.executed.single().argv)
    }

    @Test
    fun `tap returns Unavailable with the live reason and never executes when the shell is down`() =
        runBlocking {
            val shell = FakeShell(available = false)
            val result = controller(shell, ShizukuState.INSTALLED_NOT_RUNNING).tap(540, 960)

            assertTrue("was $result", result is TapResult.Unavailable)
            assertEquals(ShizukuState.INSTALLED_NOT_RUNNING.explanation, (result as TapResult.Unavailable).reason)
            assertTrue("execute must not be reached", shell.executed.isEmpty())
        }

    @Test
    fun `tap maps a shell failure to Failed carrying the shell's own reason`() = runBlocking {
        val shell = FakeShell(available = true, failure = ShellResult.failure("input: not found", AccessLevel.SHIZUKU))
        val result = controller(shell).tap(1, 2)

        assertTrue("was $result", result is TapResult.Failed)
        assertEquals("input: not found", (result as TapResult.Failed).reason)
    }

    @Test
    fun `hold injects one input swipe from the point to itself for the given duration`() = runBlocking {
        val shell = FakeShell(available = true)
        val result = controller(shell).hold(100, 200, 400)

        assertEquals(TapResult.Success, result)
        assertEquals(
            checkNotNull(ShellCommand.inputSwipe(100, 200, 100, 200, 400)).argv,
            shell.executed.single().argv,
        )
    }

    @Test
    fun `hold returns Unavailable and never executes when the shell is down`() = runBlocking {
        val shell = FakeShell(available = false)
        val result = controller(shell, ShizukuState.NOT_INSTALLED).hold(1, 2, 300)

        assertTrue("was $result", result is TapResult.Unavailable)
        assertTrue(shell.executed.isEmpty())
    }

    @Test
    fun `doubleTap sends the tap twice when the shell is live`() = runBlocking {
        val shell = FakeShell(available = true)
        val result = controller(shell).doubleTap(300, 300)

        assertEquals(TapResult.Success, result)
        assertEquals(2, shell.executed.size)
        val expected = checkNotNull(ShellCommand.inputTap(300, 300)).argv
        shell.executed.forEach { assertEquals(expected, it.argv) }
    }

    @Test
    fun `doubleTap stops after the first tap fails and never sends the second`() = runBlocking {
        val shell = FakeShell(available = true, failure = ShellResult.failure("denied", AccessLevel.SHIZUKU))
        val result = controller(shell).doubleTap(10, 20)

        assertTrue("was $result", result is TapResult.Failed)
        assertEquals("denied", (result as TapResult.Failed).reason)
        assertEquals("the second half must not be attempted after the first fails", 1, shell.executed.size)
    }

    @Test
    fun `doubleTap returns Unavailable and never executes when the shell is down`() = runBlocking {
        val shell = FakeShell(available = false)
        val result = controller(shell, ShizukuState.RUNNING_PERMISSION_DENIED).doubleTap(10, 20)

        assertTrue("was $result", result is TapResult.Unavailable)
        assertTrue(shell.executed.isEmpty())
    }

    @Test
    fun `availability is a Shizuku-sourced value when the state is usable`() {
        val observed = controller(FakeShell(available = true), ShizukuState.RUNNING_PERMISSION_GRANTED).availability()
        assertTrue("was $observed", observed is Observed.Value)
    }

    @Test
    fun `availability is Restricted carrying the state explanation when Shizuku is not usable`() {
        val state = ShizukuState.RUNNING_PERMISSION_DENIED
        val observed = controller(FakeShell(available = false), state).availability()

        assertTrue("was $observed", observed is Observed.Restricted)
        observed as Observed.Restricted
        assertEquals(RestrictionReason.REQUIRES_ELEVATED_ACCESS, observed.reason)
        assertEquals(state.explanation, observed.detail)
    }
}

/**
 * A fake [ElevatedShell] that records every command it is asked to run and answers with a fixed result,
 * so a test can prove both what did reach the shell and — via [available] — what deliberately did not.
 */
private class FakeShell(
    private val available: Boolean,
    private val failure: ShellResult? = null,
) : ElevatedShell {

    val executed = mutableListOf<ShellCommand>()

    override val accessLevel = AccessLevel.SHIZUKU

    override suspend fun isAvailable(): Boolean = available

    override suspend fun execute(command: ShellCommand, timeoutMillis: Long): ShellResult {
        executed += command
        return failure ?: ShellResult(0, "", "", accessLevel)
    }
}
