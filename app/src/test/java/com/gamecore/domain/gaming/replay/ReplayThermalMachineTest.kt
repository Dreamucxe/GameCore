package com.gamecore.domain.gaming.replay

import com.gamecore.core.model.ThermalClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [ReplayThermalMachine] — auto pause/resume on heat. Fake time is a plain `Long`; the load-bearing
 * cases are the ones the spec is emphatic about: a pause needs sustained CRITICAL, a resume waits the min
 * interval and not merely the cool sustain, and a device flapping across the line does not thrash.
 */
class ReplayThermalMachineTest {

    // 5s critical sustain, 15s cool sustain, 30s min interval.
    private val cfg = ReplayThermalConfig()

    private fun eval(s: ReplayThermalState, cls: ThermalClass, now: Long) =
        ReplayThermalMachine.evaluate(s, cfg, cls, now)

    private fun start() = ReplayThermalState()

    @Test
    fun `critical not yet sustained does not pause`() {
        var s = start()
        s = eval(s, ThermalClass.CRITICAL, 0L).first
        val (state, d) = eval(s, ThermalClass.CRITICAL, 4_000L)
        assertEquals(ReplayThermalDecision.NoChange, d)
        assertTrue(!state.paused)
    }

    @Test
    fun `critical sustained five seconds pauses`() {
        var s = start()
        s = eval(s, ThermalClass.CRITICAL, 0L).first
        val (state, d) = eval(s, ThermalClass.CRITICAL, 5_000L)
        assertTrue(d is ReplayThermalDecision.Pause)
        assertTrue(state.paused)
        assertEquals(5_000L, state.lastChangeMillis)
    }

    @Test
    fun `a resume waits the min interval, not just the cool sustain`() {
        var s = start()
        s = eval(s, ThermalClass.CRITICAL, 0L).first
        s = eval(s, ThermalClass.CRITICAL, 5_000L).first     // pause at t=5000
        assertTrue(s.paused)
        s = eval(s, ThermalClass.HOT, 6_000L).first          // cool timer starts at 6000
        // t=21000: 15s of cool is met, but only 16s since the pause (< 30s interval) → no resume yet.
        val (mid, d1) = eval(s, ThermalClass.HOT, 21_000L)
        assertEquals(ReplayThermalDecision.NoChange, d1)
        assertTrue(mid.paused)
        // t=35001: 29s cool and 30.001s since the pause → resume.
        val (state, d2) = eval(mid, ThermalClass.HOT, 35_001L)
        assertTrue(d2 is ReplayThermalDecision.Resume)
        assertTrue(!state.paused)
        assertEquals(35_001L, state.lastChangeMillis)
    }

    @Test
    fun `a brief critical spike shorter than the sustain never pauses`() {
        var s = start()
        var changes = 0
        // Alternate CRITICAL / HOT every 2s: critical never holds 5s in a row.
        for (i in 0 until 40) {
            val cls = if (i % 2 == 0) ThermalClass.CRITICAL else ThermalClass.HOT
            val (next, d) = eval(s, cls, i * 2_000L)
            if (d !is ReplayThermalDecision.NoChange) changes++
            s = next
        }
        assertEquals(0, changes)
        assertTrue(!s.paused)
    }

    @Test
    fun `once paused a brief cool dip shorter than the cool sustain never resumes`() {
        var s = start()
        s = eval(s, ThermalClass.CRITICAL, 0L).first
        s = eval(s, ThermalClass.CRITICAL, 5_000L).first
        assertTrue(s.paused)
        var changes = 0
        // Oscillate past the interval, but cool never holds 15s in a row → never resumes.
        for (i in 0 until 60) {
            val cls = if (i % 2 == 0) ThermalClass.HOT else ThermalClass.CRITICAL
            val (next, d) = eval(s, cls, 6_000L + i * 2_000L)
            if (d !is ReplayThermalDecision.NoChange) changes++
            s = next
        }
        assertEquals(0, changes)
        assertTrue(s.paused)
    }

    @Test
    fun `does not pause twice while already paused`() {
        var s = start()
        s = eval(s, ThermalClass.CRITICAL, 0L).first
        s = eval(s, ThermalClass.CRITICAL, 5_000L).first
        assertTrue(s.paused)
        // Far later, still critical and past the interval — but already paused, so nothing to do.
        val (state, d) = eval(s, ThermalClass.CRITICAL, 100_000L)
        assertEquals(ReplayThermalDecision.NoChange, d)
        assertTrue(state.paused)
    }

    @Test
    fun `does not resume when it was never paused`() {
        var s = start()
        s = eval(s, ThermalClass.OK, 0L).first
        val (state, d) = eval(s, ThermalClass.OK, 100_000L)
        assertEquals(ReplayThermalDecision.NoChange, d)
        assertTrue(!state.paused)
    }

    @Test
    fun `a pause after a resume also waits the min interval`() {
        var s = start()
        s = eval(s, ThermalClass.CRITICAL, 0L).first
        s = eval(s, ThermalClass.CRITICAL, 5_000L).first     // pause @5000
        s = eval(s, ThermalClass.HOT, 6_000L).first
        s = eval(s, ThermalClass.HOT, 35_001L).first          // resume @35001
        assertTrue(!s.paused)
        s = eval(s, ThermalClass.CRITICAL, 35_002L).first     // critical timer restarts
        // t=41000: 6s sustained but only ~6s since the resume (< 30s interval) → no pause.
        val (mid, d1) = eval(s, ThermalClass.CRITICAL, 41_000L)
        assertEquals(ReplayThermalDecision.NoChange, d1)
        // t=66000: still sustained and 30.999s since the resume → pause again.
        val (state, d2) = eval(mid, ThermalClass.CRITICAL, 66_000L)
        assertTrue(d2 is ReplayThermalDecision.Pause)
        assertTrue(state.paused)
    }

    @Test
    fun `unavailable counts as below critical and can resume a paused buffer`() {
        var s = start()
        s = eval(s, ThermalClass.CRITICAL, 0L).first
        s = eval(s, ThermalClass.CRITICAL, 5_000L).first
        assertTrue(s.paused)
        s = eval(s, ThermalClass.UNAVAILABLE, 6_000L).first   // unavailable is treated as the cool side
        val (state, d) = eval(s, ThermalClass.UNAVAILABLE, 40_000L)
        assertTrue(d is ReplayThermalDecision.Resume)
        assertTrue(!state.paused)
    }

    @Test
    fun `reason strings describe the action, not a heat claim`() {
        var s = start()
        s = eval(s, ThermalClass.CRITICAL, 0L).first
        val (_, d) = eval(s, ThermalClass.CRITICAL, 5_000L)
        val reason = (d as ReplayThermalDecision.Pause).reason.lowercase()
        assertTrue(reason.contains("replay buffer"))
        assertTrue(!reason.contains("cools the") && !reason.contains("reduces heat"))
    }

    @Test
    fun `the clocked wrapper reads time from the injected clock`() {
        val clock = FakeReplayClock(0L)
        val machine = ClockedReplayThermalMachine(clock)
        var s = ReplayThermalState()
        s = machine.evaluate(s, ThermalClass.CRITICAL).first  // t=0
        assertTrue(!s.paused)
        clock.now = 5_000L
        val (state, d) = machine.evaluate(s, ThermalClass.CRITICAL) // t=5000
        assertTrue(d is ReplayThermalDecision.Pause)
        assertTrue(state.paused)
    }
}
