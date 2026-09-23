package com.gamecore.domain.thermal

import com.gamecore.core.model.ThermalClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [ThermalDownshiftMachine] — the pure decision behind thermal auto-downshift (§B).
 *
 * Time is a plain `Long`; temperatures are tenths of a degree. The interesting cases are the ones the
 * spec is emphatic about: never faster than the minimum interval even when the temperature oscillates,
 * never below the floor, never an upshift on a missing or critical reading, and a hard latch to paused
 * when something outside the machine moves the rate.
 */
class ThermalDownshiftMachineTest {

    private val ladder = listOf(120f, 90f, 60f)

    // limit 60.0°C, hysteresis 5°C, sustain hot 30s, cool 90s, min interval 60s, floor 60.
    private fun config(
        floor: Float = 60f,
        limitDeci: Int? = 600,
        statusFloor: ThermalClass? = null,
    ) = ThermalDownshiftConfig(
        enabled = true,
        temperatureLimitDeciCelsius = limitDeci,
        thermalStatusFloor = statusFloor,
        floorRateHz = floor,
    )

    private fun start() = ThermalDownshiftState.initial(120f)

    // --- downshift needs sustain + interval ---------------------------------------------------------

    @Test
    fun `hot but not yet sustained does not downshift`() {
        val (_, decision) = ThermalDownshiftMachine.evaluate(
            start(), config(), nowMillis = 10_000L, temperatureDeciCelsius = 650,
            thermalClass = ThermalClass.HOT, observedRateHz = 120f, supportedRatesAtOrAboveFloor = ladder,
        )
        assertEquals(ThermalDownshiftDecision.NoChange, decision)
    }

    @Test
    fun `hot sustained for thirty seconds downshifts one step`() {
        var s = start()
        // First hot tick starts the timer at t=0.
        s = ThermalDownshiftMachine.evaluate(s, config(), 0L, 650, ThermalClass.HOT, 120f, ladder).first
        // t=30s: sustained, no prior change so interval is satisfied → 120 → 90.
        val (state, decision) = ThermalDownshiftMachine.evaluate(
            s, config(), 30_000L, 650, ThermalClass.HOT, 120f, ladder,
        )
        assertTrue(decision is ThermalDownshiftDecision.SetRate)
        assertEquals(90f, (decision as ThermalDownshiftDecision.SetRate).targetHz)
        assertEquals(90f, state.currentRateHz)
    }

    @Test
    fun `a second downshift waits the minimum interval, not just the sustain`() {
        var s = start()
        s = ThermalDownshiftMachine.evaluate(s, config(), 0L, 650, ThermalClass.HOT, 120f, ladder).first
        s = ThermalDownshiftMachine.evaluate(s, config(), 30_000L, 650, ThermalClass.HOT, 120f, ladder).first
        assertEquals(90f, s.currentRateHz)
        // 30s of sustain has passed again by t=60s, but the min interval since the last change (at 30s) has
        // NOT (only 30s). No second step yet — observed follows our set to 90.
        val (mid, d1) = ThermalDownshiftMachine.evaluate(s, config(), 60_000L, 650, ThermalClass.HOT, 90f, ladder)
        assertEquals(ThermalDownshiftDecision.NoChange, d1)
        // t=91s: 61s since the change and 61s hot → 90 → 60.
        val (_, d2) = ThermalDownshiftMachine.evaluate(mid, config(), 91_000L, 650, ThermalClass.HOT, 90f, ladder)
        assertTrue(d2 is ThermalDownshiftDecision.SetRate)
        assertEquals(60f, (d2 as ThermalDownshiftDecision.SetRate).targetHz)
    }

    @Test
    fun `never steps below the floor`() {
        // Floor 90 → ladder usable is [120,90]; from 90 there is no lower rung.
        var s = ThermalDownshiftState.initial(90f)
        s = ThermalDownshiftMachine.evaluate(s, config(floor = 90f), 0L, 700, ThermalClass.HOT, 90f, ladder).first
        val (_, decision) = ThermalDownshiftMachine.evaluate(
            s, config(floor = 90f), 40_000L, 700, ThermalClass.HOT, 90f, ladder,
        )
        assertEquals(ThermalDownshiftDecision.NoChange, decision)
    }

    // --- oscillation must not thrash ---------------------------------------------------------------

    @Test
    fun `temperature oscillating around the limit does not change faster than the interval`() {
        var s = start()
        var changes = 0
        // 40 ticks, 2s apart, temperature alternating just over / just under the limit.
        for (i in 0 until 40) {
            val t = i * 2_000L
            val temp = if (i % 2 == 0) 610 else 590
            val cls = if (i % 2 == 0) ThermalClass.HOT else ThermalClass.WARM
            val (next, d) = ThermalDownshiftMachine.evaluate(s, config(), t, temp, cls, s.currentRateHz, ladder)
            if (d is ThermalDownshiftDecision.SetRate) changes++
            s = next
        }
        // Over 80s, with a 60s interval and a 30s sustain that keeps resetting as it dips, at most a couple
        // of changes — never one per hot tick.
        assertTrue("changed $changes times, expected <= 2", changes <= 2)
    }

    // --- upshift on cool -----------------------------------------------------------------------------

    @Test
    fun `cool below limit minus hysteresis for the sustain time upshifts one step`() {
        // Start already downshifted to 60, last change long ago.
        var s = ThermalDownshiftState.initial(60f).copy(lastSetRateHz = 60f, lastChangeMillis = 0L)
        // limit 600, hysteresis 50 → cool means <= 550. Cool from t=100s.
        s = ThermalDownshiftMachine.evaluate(s, config(), 100_000L, 500, ThermalClass.OK, 60f, ladder).first
        // t=191s: 91s cool and 191s since the last change → 60 → 90.
        val (state, decision) = ThermalDownshiftMachine.evaluate(
            s, config(), 191_000L, 500, ThermalClass.OK, 60f, ladder,
        )
        assertTrue(decision is ThermalDownshiftDecision.SetRate)
        assertEquals(90f, (decision as ThermalDownshiftDecision.SetRate).targetHz)
    }

    @Test
    fun `within the hysteresis band neither downshifts nor upshifts`() {
        // 58.0°C: below the 60.0 limit (not hot) but above 55.0 (not cool). Hold.
        var s = ThermalDownshiftState.initial(60f).copy(lastSetRateHz = 60f, lastChangeMillis = 0L)
        s = ThermalDownshiftMachine.evaluate(s, config(), 100_000L, 580, ThermalClass.WARM, 60f, ladder).first
        val (_, decision) = ThermalDownshiftMachine.evaluate(
            s, config(), 300_000L, 580, ThermalClass.WARM, 60f, ladder,
        )
        assertEquals(ThermalDownshiftDecision.NoChange, decision)
    }

    // --- missing readings & critical ---------------------------------------------------------------

    @Test
    fun `missing readings never upshift and eventually report unavailable`() {
        var s = ThermalDownshiftState.initial(60f).copy(lastSetRateHz = 60f, lastChangeMillis = 0L)
        var last = s
        for (i in 0 until ThermalDownshiftConfig.MISS_LIMIT) {
            val (next, d) = ThermalDownshiftMachine.evaluate(
                s, config(), (i + 1) * 200_000L, null, ThermalClass.UNAVAILABLE, null, ladder,
            )
            assertEquals(ThermalDownshiftDecision.NoChange, d)
            s = next; last = next
        }
        assertEquals(DownshiftStatus.UNAVAILABLE, last.status)
        assertEquals(60f, last.currentRateHz) // never rose
    }

    @Test
    fun `critical never causes an upshift`() {
        // Even with a cool timer already running, a critical tick forces the hot path — no upshift.
        var s = ThermalDownshiftState.initial(60f).copy(
            lastSetRateHz = 60f, lastChangeMillis = 0L, coolSinceMillis = 0L,
        )
        val (state, decision) = ThermalDownshiftMachine.evaluate(
            s, config(), 200_000L, temperatureDeciCelsius = null,
            thermalClass = ThermalClass.CRITICAL, observedRateHz = 60f, supportedRatesAtOrAboveFloor = ladder,
        )
        // At the floor already, so no downshift either — but crucially not an upshift.
        assertEquals(ThermalDownshiftDecision.NoChange, decision)
        assertEquals(60f, state.currentRateHz)
    }

    // --- ladders -----------------------------------------------------------------------------------

    @Test
    fun `two-rung ladder steps once`() {
        val two = listOf(120f, 60f)
        var s = start()
        s = ThermalDownshiftMachine.evaluate(s, config(), 0L, 650, ThermalClass.HOT, 120f, two).first
        val (state, decision) = ThermalDownshiftMachine.evaluate(s, config(), 30_000L, 650, ThermalClass.HOT, 120f, two)
        assertEquals(60f, (decision as ThermalDownshiftDecision.SetRate).targetHz)
        assertEquals(60f, state.currentRateHz)
    }

    @Test
    fun `single-rung ladder is unavailable`() {
        val one = listOf(60f)
        val (state, decision) = ThermalDownshiftMachine.evaluate(
            start(), config(), 30_000L, 650, ThermalClass.HOT, 60f, one,
        )
        assertEquals(DownshiftStatus.UNAVAILABLE, state.status)
        assertEquals(ThermalDownshiftDecision.NoChange, decision)
    }

    // --- external change latches paused ------------------------------------------------------------

    @Test
    fun `an external rate change pauses auto control for the session`() {
        // We set 90 at t=30s; then the panel is observed at 120 (user/game/system moved it).
        var s = start()
        s = ThermalDownshiftMachine.evaluate(s, config(), 0L, 650, ThermalClass.HOT, 120f, ladder).first
        s = ThermalDownshiftMachine.evaluate(s, config(), 30_000L, 650, ThermalClass.HOT, 120f, ladder).first
        assertEquals(90f, s.lastSetRateHz)
        val (paused, decision) = ThermalDownshiftMachine.evaluate(
            s, config(), 95_000L, 650, ThermalClass.HOT, observedRateHz = 120f, supportedRatesAtOrAboveFloor = ladder,
        )
        assertEquals(DownshiftStatus.PAUSED_EXTERNAL, paused.status)
        assertTrue(paused.paused)
        assertEquals(ThermalDownshiftDecision.NoChange, decision)
        // And it stays paused on the next tick, whatever the temperature.
        val (still, d2) = ThermalDownshiftMachine.evaluate(paused, config(), 200_000L, 700, ThermalClass.HOT, 90f, ladder)
        assertEquals(ThermalDownshiftDecision.NoChange, d2)
        assertTrue(still.paused)
    }

    // --- platform-status triggering ----------------------------------------------------------------

    @Test
    fun `status floor triggers a downshift with no temperature limit`() {
        val cfg = config(limitDeci = null, statusFloor = ThermalClass.HOT)
        var s = start()
        s = ThermalDownshiftMachine.evaluate(s, cfg, 0L, null, ThermalClass.HOT, 120f, ladder).first
        val (_, decision) = ThermalDownshiftMachine.evaluate(s, cfg, 30_000L, null, ThermalClass.HOT, 120f, ladder)
        assertEquals(90f, (decision as ThermalDownshiftDecision.SetRate).targetHz)
    }

    // --- read-back failure counting ----------------------------------------------------------------

    @Test
    fun `three consecutive read-back failures latch the machine`() {
        var s = start()
        repeat(ThermalDownshiftConfig.READBACK_FAILURE_LIMIT) {
            s = ThermalDownshiftMachine.recordReadbackResult(s, applied = false)
        }
        assertTrue(s.paused)
        assertEquals(DownshiftStatus.UNAVAILABLE, s.status)
    }

    @Test
    fun `a successful read-back clears the failure counter`() {
        var s = start()
        s = ThermalDownshiftMachine.recordReadbackResult(s, applied = false)
        s = ThermalDownshiftMachine.recordReadbackResult(s, applied = false)
        s = ThermalDownshiftMachine.recordReadbackResult(s, applied = true)
        assertEquals(0, s.readbackFailures)
        s = ThermalDownshiftMachine.recordReadbackResult(s, applied = false)
        assertEquals(1, s.readbackFailures)
        assertTrue(!s.paused)
    }

    // --- disabled / paused short-circuits ----------------------------------------------------------

    @Test
    fun `disabled config never acts`() {
        val (_, decision) = ThermalDownshiftMachine.evaluate(
            start(), config().copy(enabled = false), 30_000L, 900, ThermalClass.CRITICAL, 120f, ladder,
        )
        assertEquals(ThermalDownshiftDecision.NoChange, decision)
    }

    @Test
    fun `reason strings never claim heat was reduced`() {
        var s = start()
        s = ThermalDownshiftMachine.evaluate(s, config(), 0L, 650, ThermalClass.HOT, 120f, ladder).first
        val (_, decision) = ThermalDownshiftMachine.evaluate(s, config(), 30_000L, 650, ThermalClass.HOT, 120f, ladder)
        val reason = (decision as ThermalDownshiftDecision.SetRate).reason.lowercase()
        assertTrue("reason should describe the action, not a heat claim", reason.contains("refresh rate"))
        assertTrue(!reason.contains("cools the") && !reason.contains("reduces heat"))
    }
}
