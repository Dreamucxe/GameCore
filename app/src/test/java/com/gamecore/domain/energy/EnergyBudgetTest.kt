package com.gamecore.domain.energy

import com.gamecore.core.model.GameSession
import com.gamecore.core.model.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The energy budget rules, tested with constructed sessions — no device, no database.
 *
 * Each case is a rule from [EnergyBudget] expressed as input -> output. The floors and the median
 * are the product; changing one is a spec change.
 */
class EnergyBudgetTest {

    private fun session(
        id: Long,
        startPercent: Int,
        endPercent: Int?,
        wasCharging: Boolean,
        durationMinutes: Long,
    ): GameSession {
        // `drain()` is derived, not stored: BatteryDrain's percentPerHour is computed from the two
        // levels and the elapsed time, and is null when the session was charging or too short. The
        // test therefore only supplies the fields GameSession actually carries.
        return GameSession(
            id = id,
            packageName = "com.test.game",
            gameLabel = "Test Game",
            startedAtMillis = System.currentTimeMillis() - durationMinutes * 60_000L,
            endedAtMillis = System.currentTimeMillis(),
            batteryStartPercent = startPercent,
            batteryEndPercent = endPercent,
            wasCharging = wasCharging,
            stopReason = StopReason.STOPPED_BY_USER,
        )
    }

    @Test
    fun `no sessions returns NoSessions`() {
        val result = EnergyBudget.estimate(emptyList(), 80)

        assertTrue(result is EnergyEstimate.NoSessions)
    }

    @Test
    fun `one usable session returns NotEnoughData with the count`() {
        val sessions = listOf(session(1L, 100, 90, false, 30))
        val result = EnergyBudget.estimate(sessions, 80)

        assertTrue(result is EnergyEstimate.NotEnoughData)
        assertTrue((result as EnergyEstimate.NotEnoughData).reason.contains("1 more session"))
    }

    @Test
    fun `two usable sessions but only 10 minutes total returns NotEnoughData with minutes`() {
        val sessions = listOf(
            session(1L, 100, 95, false, 5),
            session(2L, 95, 90, false, 5),
        )
        val result = EnergyBudget.estimate(sessions, 80)

        assertTrue(result is EnergyEstimate.NotEnoughData)
        assertTrue((result as EnergyEstimate.NotEnoughData).reason.contains("10 more minutes"))
    }

    @Test
    fun `two usable sessions and 30 minutes returns HasData with median rate`() {
        // Rates: 10%/hr and 20%/hr -> median 15%/hr
        val sessions = listOf(
            session(1L, 100, 95, false, 30), // 5% in 30m = 10%/hr
            session(2L, 95, 85, false, 30),  // 10% in 30m = 20%/hr
        )
        val result = EnergyBudget.estimate(sessions, 100)

        assertTrue(result is EnergyEstimate.HasData)
        val data = result as EnergyEstimate.HasData
        assertEquals(15f, data.medianPercentPerHour, 0.001f)
        assertEquals(2, data.usableSessions)
        assertEquals(60L, data.totalMeasuredMinutes)
        assertEquals(400L, data.projectedMinutes!!) // 100 / 15 * 60 = 400
    }

    @Test
    fun `charging session is excluded even with a plausible delta`() {
        val sessions = listOf(
            session(1L, 50, 60, true, 30), // charging -> no drain
            session(2L, 100, 90, false, 30),
        )
        val result = EnergyBudget.estimate(sessions, 80)

        assertTrue(result is EnergyEstimate.NotEnoughData)
        assertTrue((result as EnergyEstimate.NotEnoughData).reason.contains("1 more session"))
    }

    @Test
    fun `short session is excluded`() {
        val sessions = listOf(
            session(1L, 100, 99, false, 2), // under 5 min
            session(2L, 99, 95, false, 30),
            session(3L, 95, 90, false, 30),
        )
        val result = EnergyBudget.estimate(sessions, 80)

        // The 2-minute session has no drain, so only 2 usable -> meets MIN_USABLE_SESSIONS
        // Total minutes = 60 -> meets MIN_MEASURED_MINUTES
        assertTrue(result is EnergyEstimate.HasData)
    }

    @Test
    fun `missing end level is excluded`() {
        val sessions = listOf(
            session(1L, 100, null, false, 30),
            session(2L, 100, 90, false, 30),
            session(3L, 90, 80, false, 30),
        )
        val result = EnergyBudget.estimate(sessions, 80)

        assertTrue(result is EnergyEstimate.HasData)
        val data = result as EnergyEstimate.HasData
        assertEquals(2, data.usableSessions)
    }

    @Test
    fun `median is chosen over a skewed outlier`() {
        // Rates: 10, 12, 100 -> median 12, mean would be ~40. The engine uses median.
        val sessions = listOf(
            session(1L, 100, 95, false, 30),  // 10
            session(2L, 95, 89, false, 30),   // 12
            session(3L, 89, 39, false, 30),   // 100
        )
        val result = EnergyBudget.estimate(sessions, 100)

        assertTrue(result is EnergyEstimate.HasData)
        val data = result as EnergyEstimate.HasData
        assertEquals(12f, data.medianPercentPerHour, 0.001f)
    }

    @Test
    fun `projection at 50% is half of projection at 100%`() {
        val sessions = listOf(
            session(1L, 100, 95, false, 30),  // 10%/hr
            session(2L, 95, 85, false, 30),   // 20%/hr -> median 15
        )
        val r100 = EnergyBudget.estimate(sessions, 100) as EnergyEstimate.HasData
        val r50 = EnergyBudget.estimate(sessions, 50) as EnergyEstimate.HasData

        assertEquals(r100.projectedMinutes!! / 2, r50.projectedMinutes!!)
    }

    @Test
    fun `null current level returns HasData with null projection`() {
        val sessions = listOf(
            session(1L, 100, 95, false, 30),
            session(2L, 95, 85, false, 30),
        )
        val result = EnergyBudget.estimate(sessions, null)

        assertTrue(result is EnergyEstimate.HasData)
        val data = result as EnergyEstimate.HasData
        assertNull(data.projectedMinutes)
    }

    @Test
    fun `explainsRefusal returns the same reason`() {
        val sessions = listOf(session(1L, 100, 90, false, 30))
        val reason = EnergyBudget.explainsRefusal(sessions, 80)

        assertNotNull(reason)
        assertEquals((EnergyBudget.estimate(sessions, 80) as EnergyEstimate.NotEnoughData).reason, reason!!)
    }

    @Test
    fun `explainsRefusal returns NoSessions text when empty`() {
        val reason = EnergyBudget.explainsRefusal(emptyList(), 80)
        assertEquals("No sessions recorded for this game yet.", reason)
    }

    @Test
    fun `explainsRefusal returns null when there is a projection`() {
        val sessions = listOf(
            session(1L, 100, 95, false, 30),
            session(2L, 95, 85, false, 30),
        )
        val reason = EnergyBudget.explainsRefusal(sessions, 80)
        assertNull(reason)
    }
}