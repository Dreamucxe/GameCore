package com.gamecore.domain.power

import com.gamecore.core.model.ThermalClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [BatterySaverOverrideMachine] — the pure decision behind "Keep full performance" (§D).
 *
 * Fake time is a plain `Long`. Every rule the spec makes about a device that does not cooperate — the
 * system re-enabling the saver, a write that does not take, a critical device, the re-apply interval —
 * is a test here, because none of them is observable on the JVM any other way and all of them are the
 * difference between putting the device back the way the user had it and leaving it stuck.
 */
class BatterySaverOverrideMachineTest {

    private val on = BatterySaverOverrideConfig(enabled = true)
    private val off = BatterySaverOverrideConfig(enabled = false)
    private val cool = ThermalClass.OK

    // --- session start -----------------------------------------------------------------------------

    @Test
    fun `disabled profile does nothing`() {
        val (state, action) = BatterySaverOverrideMachine.onSessionStart(
            BatterySaverOverrideState.initial(), off, 0L, saverCurrentlyOn = true,
            shizukuAvailable = true, thermalClass = cool,
        )
        assertEquals(OverrideStatus.INACTIVE, state.status)
        assertEquals(BatterySaverAction.DoNothing, action)
    }

    @Test
    fun `no shizuku is unavailable, not a failure`() {
        val (state, action) = BatterySaverOverrideMachine.onSessionStart(
            BatterySaverOverrideState.initial(), on, 0L, saverCurrentlyOn = true,
            shizukuAvailable = false, thermalClass = cool,
        )
        assertEquals(OverrideStatus.UNAVAILABLE, state.status)
        assertEquals(BatterySaverAction.DoNothing, action)
    }

    @Test
    fun `critical thermal blocks the override even with the toggle on`() {
        val (state, action) = BatterySaverOverrideMachine.onSessionStart(
            BatterySaverOverrideState.initial(), on, 0L, saverCurrentlyOn = true,
            shizukuAvailable = true, thermalClass = ThermalClass.CRITICAL,
        )
        assertEquals(OverrideStatus.BLOCKED_THERMAL, state.status)
        assertEquals(BatterySaverAction.DoNothing, action)
        // Nothing captured: there is no obligation to restore what we never touched.
        assertEquals(null, state.originalSaverOn)
    }

    @Test
    fun `saver already off captures off and does nothing extra`() {
        val (state, action) = BatterySaverOverrideMachine.onSessionStart(
            BatterySaverOverrideState.initial(), on, 0L, saverCurrentlyOn = false,
            shizukuAvailable = true, thermalClass = cool,
        )
        assertEquals(false, state.originalSaverOn)
        assertFalse(state.overrideApplied)
        assertEquals(OverrideStatus.INACTIVE, state.status)
        assertEquals(BatterySaverAction.DoNothing, action)
    }

    @Test
    fun `saver on is captured and disabled`() {
        val (state, action) = BatterySaverOverrideMachine.onSessionStart(
            BatterySaverOverrideState.initial(), on, 1_000L, saverCurrentlyOn = true,
            shizukuAvailable = true, thermalClass = cool,
        )
        assertEquals(true, state.originalSaverOn)
        assertTrue(state.overrideApplied)
        assertEquals(1_000L, state.lastApplyMillis)
        assertEquals(OverrideStatus.OVERRIDDEN, state.status)
        assertTrue(action is BatterySaverAction.CaptureAndDisable)
    }

    // --- read-back ---------------------------------------------------------------------------------

    @Test
    fun `readback with saver still on is an honest mismatch`() {
        val started = BatterySaverOverrideMachine.onSessionStart(
            BatterySaverOverrideState.initial(), on, 0L, true, true, cool,
        ).first
        val after = BatterySaverOverrideMachine.onReadback(started, saverStillOn = true)
        assertEquals(OverrideStatus.READBACK_MISMATCH, after.status)
    }

    @Test
    fun `readback with saver off confirms overridden`() {
        val started = BatterySaverOverrideMachine.onSessionStart(
            BatterySaverOverrideState.initial(), on, 0L, true, true, cool,
        ).first
        val after = BatterySaverOverrideMachine.onReadback(started, saverStillOn = false)
        assertEquals(OverrideStatus.OVERRIDDEN, after.status)
    }

    // --- poll: don't fight the system --------------------------------------------------------------

    @Test
    fun `system re-enabling the saver latches paused and does not reverse it`() {
        val started = BatterySaverOverrideMachine.onSessionStart(
            BatterySaverOverrideState.initial(), on, 0L, true, true, cool,
        ).first
        val (state, action) = BatterySaverOverrideMachine.onPoll(
            started, on, 5_000L, saverCurrentlyOn = true, thermalClass = cool,
        )
        assertTrue(state.systemReenabled)
        assertEquals(OverrideStatus.PAUSED_SYSTEM_REENABLED, state.status)
        assertEquals(BatterySaverAction.DoNothing, action)
    }

    @Test
    fun `once paused by the system it stays paused for the session`() {
        var state = BatterySaverOverrideMachine.onSessionStart(
            BatterySaverOverrideState.initial(), on, 0L, true, true, cool,
        ).first
        state = BatterySaverOverrideMachine.onPoll(state, on, 5_000L, true, cool).first
        // A later poll where the saver reads off again must not un-pause on its own.
        val (later, action) = BatterySaverOverrideMachine.onPoll(state, on, 9_000L, false, cool)
        assertEquals(OverrideStatus.PAUSED_SYSTEM_REENABLED, later.status)
        assertEquals(BatterySaverAction.DoNothing, action)
    }

    @Test
    fun `critical thermal during a poll pauses without reversing`() {
        val started = BatterySaverOverrideMachine.onSessionStart(
            BatterySaverOverrideState.initial(), on, 0L, true, true, cool,
        ).first
        val (state, action) = BatterySaverOverrideMachine.onPoll(
            started, on, 5_000L, saverCurrentlyOn = false, thermalClass = ThermalClass.CRITICAL,
        )
        assertEquals(OverrideStatus.BLOCKED_THERMAL, state.status)
        assertEquals(BatterySaverAction.DoNothing, action)
    }

    @Test
    fun `a quiet poll holds overridden with no write`() {
        val started = BatterySaverOverrideMachine.onSessionStart(
            BatterySaverOverrideState.initial(), on, 0L, true, true, cool,
        ).first
        val (state, action) = BatterySaverOverrideMachine.onPoll(started, on, 5_000L, false, cool)
        assertEquals(OverrideStatus.OVERRIDDEN, state.status)
        assertEquals(BatterySaverAction.DoNothing, action)
    }

    // --- user re-enable + the interval gate --------------------------------------------------------

    @Test
    fun `user re-enable after the interval re-applies`() {
        var state = BatterySaverOverrideMachine.onSessionStart(
            BatterySaverOverrideState.initial(), on, 0L, true, true, cool,
        ).first
        state = BatterySaverOverrideMachine.onPoll(state, on, 5_000L, true, cool).first
        val (reapplied, action) = BatterySaverOverrideMachine.userReenabled(
            state, on, nowMillis = 70_000L, saverCurrentlyOn = true, thermalClass = cool,
        )
        assertFalse(reapplied.systemReenabled)
        assertEquals(OverrideStatus.OVERRIDDEN, reapplied.status)
        assertTrue(action is BatterySaverAction.CaptureAndDisable)
    }

    @Test
    fun `user re-enable inside the interval clears the latch but holds the write`() {
        var state = BatterySaverOverrideMachine.onSessionStart(
            BatterySaverOverrideState.initial(), on, 1_000L, true, true, cool,
        ).first
        state = BatterySaverOverrideMachine.onPoll(state, on, 5_000L, true, cool).first
        val (result, action) = BatterySaverOverrideMachine.userReenabled(
            state, on, nowMillis = 20_000L, saverCurrentlyOn = true, thermalClass = cool,
        )
        assertFalse(result.systemReenabled)
        assertEquals(BatterySaverAction.DoNothing, action)
    }

    @Test
    fun `user re-enable when the saver is already off just resumes overridden`() {
        var state = BatterySaverOverrideMachine.onSessionStart(
            BatterySaverOverrideState.initial(), on, 0L, true, true, cool,
        ).first
        state = BatterySaverOverrideMachine.onPoll(state, on, 5_000L, true, cool).first
        val (result, action) = BatterySaverOverrideMachine.userReenabled(
            state, on, nowMillis = 70_000L, saverCurrentlyOn = false, thermalClass = cool,
        )
        assertEquals(OverrideStatus.OVERRIDDEN, result.status)
        assertEquals(BatterySaverAction.DoNothing, action)
    }

    // --- session end / restore ---------------------------------------------------------------------

    @Test
    fun `restore puts a real saver back on`() {
        val started = BatterySaverOverrideMachine.onSessionStart(
            BatterySaverOverrideState.initial(), on, 0L, true, true, cool,
        ).first
        val (ended, action) = BatterySaverOverrideMachine.onSessionEnd(started)
        assertEquals(BatterySaverAction.Restore(toOn = true, reason = (action as BatterySaverAction.Restore).reason), action)
        assertFalse(ended.overrideApplied)
    }

    @Test
    fun `restore is a no-op when the saver was already off`() {
        val started = BatterySaverOverrideMachine.onSessionStart(
            BatterySaverOverrideState.initial(), on, 0L, saverCurrentlyOn = false, shizukuAvailable = true, thermalClass = cool,
        ).first
        val (_, action) = BatterySaverOverrideMachine.onSessionEnd(started)
        assertEquals(BatterySaverAction.DoNothing, action)
    }

    @Test
    fun `restore is a no-op when nothing was ever captured`() {
        val (_, action) = BatterySaverOverrideMachine.onSessionEnd(BatterySaverOverrideState.initial())
        assertEquals(BatterySaverAction.DoNothing, action)
    }

    @Test
    fun `restore is idempotent`() {
        val started = BatterySaverOverrideMachine.onSessionStart(
            BatterySaverOverrideState.initial(), on, 0L, true, true, cool,
        ).first
        val (ended, first) = BatterySaverOverrideMachine.onSessionEnd(started)
        assertTrue(first is BatterySaverAction.Restore)
        val (_, second) = BatterySaverOverrideMachine.onSessionEnd(ended)
        assertEquals(BatterySaverAction.DoNothing, second)
    }
}
