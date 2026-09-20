package com.gamecore.aimlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single / burst / auto firing cadence, magazine and reload of [FireControl] (fire-mode feature).
 *
 * Time is passed in as nanos so the cadence is exact and deterministic — no sleeping. The interval is the
 * weapon's fire-rate period; the test steps the clock across it and asserts exactly how many rounds a
 * press, a hold and a release each produce in every mode.
 */
class FireControlTest {

    private val interval = 100_000_000L // 100 ms/round → 600 rpm

    private fun weapon(mode: FireMode, burst: Int = 3, mag: Int = 30, reloadMs: Long = 1_000L) =
        Weapon(name = "w", fireRateRpm = 600, magazineSize = mag, reloadMillis = reloadMs,
            fireMode = mode, burstCount = burst)

    @Test
    fun `single fires exactly one round per press however long it is held`() {
        val fc = FireControl().apply { configure(weapon(FireMode.SINGLE), 0L) }
        fc.onTriggerDown()
        var fired = 0
        // Hold across ten intervals: SINGLE must still be one round.
        for (t in 0..10) fired += fc.tick(t * interval)
        assertEquals(1, fired)
        // Releasing and pressing again fires a second.
        fc.onTriggerUp()
        fc.onTriggerDown()
        fired += fc.tick(11 * interval)
        assertEquals(2, fired)
    }

    @Test
    fun `burst fires exactly its burst count per press, spaced by the interval`() {
        val fc = FireControl().apply { configure(weapon(FireMode.BURST, burst = 3), 0L) }
        fc.onTriggerDown()
        // Within the first interval only the first round is out.
        assertEquals(1, fc.tick(0L))
        assertEquals(0, fc.tick(interval / 2))
        assertEquals(1, fc.tick(interval))
        assertEquals(1, fc.tick(2 * interval))
        // The burst of 3 is spent; more ticks with the trigger still down fire nothing.
        assertEquals(0, fc.tick(3 * interval))
        assertEquals(0, fc.tick(10 * interval))
        // Release and press again → another burst.
        fc.onTriggerUp()
        fc.onTriggerDown()
        assertEquals(1, fc.tick(11 * interval))
    }

    @Test
    fun `auto keeps firing while held, one round per interval`() {
        val fc = FireControl().apply { configure(weapon(FireMode.AUTO), 0L) }
        fc.onTriggerDown()
        var fired = 0
        for (t in 0..5) fired += fc.tick(t * interval)
        assertEquals(6, fired) // one per interval across 0..5
        // Releasing stops it.
        fc.onTriggerUp()
        assertEquals(0, fc.tick(6 * interval))
    }

    @Test
    fun `a tick spanning several intervals does not swallow auto rounds`() {
        val fc = FireControl().apply { configure(weapon(FireMode.AUTO), 0L) }
        fc.onTriggerDown()
        fc.tick(0L) // first round
        // A long frame: 3 intervals elapse in one tick → 3 more rounds.
        assertEquals(3, fc.tick(3 * interval))
    }

    @Test
    fun `firing the magazine dry starts a reload and refills after it`() {
        val fc = FireControl().apply { configure(weapon(FireMode.AUTO, mag = 3, reloadMs = 1_000L), 0L) }
        fc.onTriggerDown()
        var fired = 0
        for (t in 0..5) fired += fc.tick(t * interval)
        assertEquals(3, fired) // only three rounds in the magazine
        assertTrue(fc.isReloading)
        assertEquals(0, fc.ammoRemaining)
        // Before the reload completes, nothing fires.
        assertEquals(0, fc.tick(6 * interval))
        // Release the trigger so the refilled magazine is not instantly emptied again by held auto fire.
        fc.onTriggerUp()
        // After the 1s reload elapses, the next tick refills the magazine and stops reloading.
        val afterReload = 6 * interval + 1_000_000_000L
        fc.tick(afterReload)
        assertEquals("reload did not refill", 3, fc.ammoRemaining)
        assertTrue("still reloading after the reload window", !fc.isReloading)
    }

    @Test
    fun `burstCount is clamped by weapon normalisation`() {
        assertEquals(Weapon.MAX_BURST, weapon(FireMode.BURST, burst = 99).normalised().burstCount)
        assertEquals(Weapon.MIN_BURST, weapon(FireMode.BURST, burst = 0).normalised().burstCount)
    }
}
