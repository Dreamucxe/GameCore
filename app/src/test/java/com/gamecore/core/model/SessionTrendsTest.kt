package com.gamecore.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic contract behind 5.10's Session Trends.
 *
 * These tests seed package-day rows rather than mocking a repository. That is the exact shape the bounded
 * Room query returns, and it keeps the date, weighting, empty-state and delta rules executable on the JVM
 * without pretending a local test exercised Android's SQLite implementation.
 */
class SessionTrendsTest {

    @Test
    fun `seven day window uses local midnights across daylight saving time`() {
        val zone = ZoneId.of("America/New_York")
        val now = Instant.parse("2026-03-09T16:00:00Z").toEpochMilli()

        val window = SessionTrends.window(SessionTrendRange.WEEK, now, zone)

        assertEquals(LocalDate.of(2026, 3, 3), window.firstDay)
        assertEquals(LocalDate.of(2026, 3, 9), window.lastDay)
        assertEquals(7, window.days.size)
        assertEquals(
            LocalDate.of(2026, 3, 3).atStartOfDay(zone).toInstant().toEpochMilli(),
            window.startMillis,
        )
        assertEquals(
            LocalDate.of(2026, 3, 10).atStartOfDay(zone).toInstant().toEpochMilli(),
            window.endMillis,
        )
        assertEquals(
            LocalDate.of(2026, 2, 24).atStartOfDay(zone).toInstant().toEpochMilli(),
            window.previousStartMillis,
        )
        assertEquals(window.startMillis, window.previousEndMillis)
        // The spring-forward week is 167 hours. A fixed 7*24-hour subtraction would be one hour wrong.
        assertEquals(167L, (window.endMillis - window.startMillis) / 3_600_000L)
    }

    @Test
    fun `seeded package rows are weighted by measured duration rather than averaged as averages`() {
        val day = LocalDate.of(2026, 11, 20)
        val rows = listOf(
            row(
                day = day,
                packageName = "game.long",
                sessions = 2,
                playMillis = 60 * MINUTE,
                fps = 60.0,
                fpsWeightMillis = 60 * MINUTE,
                temperatureDeciCelsius = 400.0,
                temperatureWeightMillis = 60 * MINUTE,
                drainRates = listOf(4.0, 6.0),
            ),
            row(
                day = day,
                packageName = "game.short",
                sessions = 1,
                playMillis = 30 * MINUTE,
                fps = 30.0,
                fpsWeightMillis = 30 * MINUTE,
                temperatureDeciCelsius = 460.0,
                temperatureWeightMillis = 30 * MINUTE,
                drainRates = listOf(8.0),
            ),
        )

        val period = SessionTrends.period(rows)

        assertEquals(3, period.sessionCount)
        assertEquals(90 * MINUTE, period.playTimeMillis)
        assertEquals(50.0, period.averageFrameRate!!, 0.0001)
        assertEquals(420.0, period.averageTemperatureDeciCelsius!!, 0.0001)
        assertEquals(6.0, period.averageDrainPercentPerHour!!, 0.0001)
        assertEquals(3, period.drainSessionCount)
    }

    @Test
    fun `an empty range produces every calendar day without fabricating measured zeroes`() {
        val window = utcWindow(SessionTrendRange.WEEK)

        val report = SessionTrends.build(
            range = SessionTrendRange.WEEK,
            window = window,
            currentRows = emptyList(),
            previousRows = emptyList(),
            best = null,
            worst = null,
        )

        assertFalse(report.hasData)
        assertEquals(7, report.days.size)
        assertTrue(report.series(SessionTrendMetric.FRAME_RATE).all { it.value == null })
        assertTrue(report.series(SessionTrendMetric.TEMPERATURE).all { it.value == null })
        assertTrue(report.series(SessionTrendMetric.BATTERY_DRAIN).all { it.value == null })
        // An empty day really has zero play time; unlike a sensor reading, that is not missing data.
        assertTrue(report.series(SessionTrendMetric.PLAY_TIME).all { it.value == 0.0 })
        assertNull(report.best)
        assertNull(report.worst)
    }

    @Test
    fun `days at the start and end of the range stay in their exact buckets`() {
        val window = utcWindow(SessionTrendRange.WEEK)
        val first = row(
            day = window.firstDay,
            packageName = "game.first",
            sessions = 1,
            playMillis = 10 * MINUTE,
            fps = 40.0,
            fpsWeightMillis = 10 * MINUTE,
        )
        val last = row(
            day = window.lastDay,
            packageName = "game.last",
            sessions = 1,
            playMillis = 20 * MINUTE,
            fps = 60.0,
            fpsWeightMillis = 20 * MINUTE,
        )

        val days = SessionTrends.days(window, listOf(first, last))

        assertEquals(1, days.first().sessionCount)
        assertEquals(40.0, days.first().averageFrameRate!!, 0.0001)
        assertTrue(days.drop(1).dropLast(1).all { it.sessionCount == 0 })
        assertEquals(1, days.last().sessionCount)
        assertEquals(60.0, days.last().averageFrameRate!!, 0.0001)
    }

    @Test
    fun `delta math judges each metric in the direction that is actually better`() {
        val current = listOf(
            row(
                day = LocalDate.of(2026, 11, 20),
                sessions = 2,
                playMillis = 120 * MINUTE,
                fps = 60.0,
                fpsWeightMillis = 120 * MINUTE,
                temperatureDeciCelsius = 400.0,
                temperatureWeightMillis = 120 * MINUTE,
                drainRates = listOf(5.0, 5.0),
            ),
        )
        val previous = listOf(
            row(
                day = LocalDate.of(2026, 11, 13),
                sessions = 2,
                playMillis = 60 * MINUTE,
                fps = 55.0,
                fpsWeightMillis = 60 * MINUTE,
                temperatureDeciCelsius = 420.0,
                temperatureWeightMillis = 60 * MINUTE,
                drainRates = listOf(4.0, 4.0),
            ),
        )
        val report = SessionTrends.build(
            range = SessionTrendRange.WEEK,
            window = utcWindow(SessionTrendRange.WEEK),
            currentRows = current,
            previousRows = previous,
            best = null,
            worst = null,
        )

        val fps = report.summary(SessionTrendMetric.FRAME_RATE)
        assertEquals(5.0, fps.absoluteDelta!!, 0.0001)
        assertEquals(SessionTrendDirection.UP, fps.direction)
        assertEquals(true, fps.isImprovement)
        assertTrue(fps.deltaText!!.contains("better"))

        val temperature = report.summary(SessionTrendMetric.TEMPERATURE)
        assertEquals(-2.0, temperature.absoluteDelta!!, 0.0001)
        assertEquals(SessionTrendDirection.DOWN, temperature.direction)
        assertEquals(true, temperature.isImprovement)
        assertTrue(temperature.deltaText!!.contains("better"))

        val drain = report.summary(SessionTrendMetric.BATTERY_DRAIN)
        assertEquals(1.0, drain.absoluteDelta!!, 0.0001)
        assertEquals(SessionTrendDirection.UP, drain.direction)
        assertEquals(false, drain.isImprovement)
        assertTrue(drain.deltaText!!.contains("worse"))

        val time = report.summary(SessionTrendMetric.PLAY_TIME)
        assertEquals(60.0, time.absoluteDelta!!, 0.0001)
        assertEquals(SessionTrendDirection.UP, time.direction)
        assertNull(time.isImprovement)
        assertTrue(time.deltaText!!.contains("vs previous"))
    }

    @Test
    fun `a measured current period does not invent a delta when the previous period lacks that metric`() {
        val report = SessionTrends.build(
            range = SessionTrendRange.WEEK,
            window = utcWindow(SessionTrendRange.WEEK),
            currentRows = listOf(
                row(
                    day = LocalDate.of(2026, 11, 20),
                    sessions = 1,
                    playMillis = 30 * MINUTE,
                    fps = 60.0,
                    fpsWeightMillis = 30 * MINUTE,
                ),
            ),
            previousRows = listOf(
                row(
                    day = LocalDate.of(2026, 11, 13),
                    sessions = 1,
                    playMillis = 30 * MINUTE,
                ),
            ),
            best = null,
            worst = null,
        )

        val fps = report.summary(SessionTrendMetric.FRAME_RATE)
        assertEquals("60.0 fps", fps.valueText)
        assertNull(fps.absoluteDelta)
        assertEquals(SessionTrendDirection.UNKNOWN, fps.direction)
        assertNull(fps.deltaText)
        assertTrue(fps.detail.contains("no previous", ignoreCase = true))
    }

    @Test
    fun `chart summary names the high low dates and coverage without relying on the line`() {
        val window = utcWindow(SessionTrendRange.WEEK)
        val report = SessionTrends.build(
            range = SessionTrendRange.WEEK,
            window = window,
            currentRows = listOf(
                row(
                    day = window.firstDay,
                    sessions = 1,
                    playMillis = 20 * MINUTE,
                    fps = 40.0,
                    fpsWeightMillis = 20 * MINUTE,
                ),
                row(
                    day = window.lastDay,
                    sessions = 1,
                    playMillis = 20 * MINUTE,
                    fps = 60.0,
                    fpsWeightMillis = 20 * MINUTE,
                ),
            ),
            previousRows = emptyList(),
            best = null,
            worst = null,
        )

        val spoken = report.chartSummary(SessionTrendMetric.FRAME_RATE)

        assertTrue(spoken.contains("Highest 60.0 fps"))
        assertTrue(spoken.contains("lowest 40.0 fps"))
        assertTrue(spoken.contains("2 of 7 days have a reading"))
    }

    @Test
    fun `conditions banner needs repeated evidence and names device and usage changes`() {
        val previous = SessionTrendPeriod(
            sessionCount = 4,
            playTimeMillis = 4 * 30 * MINUTE,
            averageFrameRate = 55.0,
            frameRateSessionCount = 4,
            averageTemperatureDeciCelsius = 400.0,
            temperatureSessionCount = 4,
            averageDrainPercentPerHour = 4.0,
            drainSessionCount = 4,
            chargingSessionCount = 0,
        )
        val current = SessionTrendPeriod(
            sessionCount = 8,
            playTimeMillis = 8 * 45 * MINUTE,
            averageFrameRate = 55.0,
            frameRateSessionCount = 8,
            averageTemperatureDeciCelsius = 430.0,
            temperatureSessionCount = 8,
            averageDrainPercentPerHour = 5.5,
            drainSessionCount = 4,
            chargingSessionCount = 4,
        )

        val changes = SessionTrends.conditionChanges(current, previous, SessionTrendRange.WEEK)

        assertTrue(changes.any { it.contains("temperature") })
        assertTrue(changes.any { it.contains("3.0°C") })
        assertTrue(changes.any { it.contains("battery drain") })
        assertTrue(changes.any { it.contains("charging") })
        assertTrue(changes.any { it.contains("longer") })
        assertTrue(changes.any { it.contains("frequency") })
    }

    @Test
    fun `one session in either period is not enough to claim conditions changed`() {
        val sparse = SessionTrendPeriod(
            sessionCount = 1,
            playTimeMillis = 5 * MINUTE,
            averageFrameRate = 20.0,
            frameRateSessionCount = 1,
            averageTemperatureDeciCelsius = 900.0,
            temperatureSessionCount = 1,
            averageDrainPercentPerHour = 20.0,
            drainSessionCount = 1,
            chargingSessionCount = 1,
        )
        val ordinary = sparse.copy(
            sessionCount = 10,
            playTimeMillis = 10 * 60 * MINUTE,
            averageTemperatureDeciCelsius = 350.0,
            averageDrainPercentPerHour = 2.0,
            chargingSessionCount = 0,
        )

        assertTrue(
            SessionTrends.conditionChanges(sparse, ordinary, SessionTrendRange.WEEK).isEmpty(),
        )
        assertTrue(
            SessionTrends.conditionChanges(ordinary, sparse, SessionTrendRange.WEEK).isEmpty(),
        )
    }

    private fun utcWindow(range: SessionTrendRange): SessionTrendWindow = SessionTrends.window(
        range = range,
        nowMillis = Instant.parse("2026-11-20T12:00:00Z").toEpochMilli(),
        zone = ZoneId.of("UTC"),
    )

    private fun row(
        day: LocalDate,
        packageName: String = "game.one",
        sessions: Int,
        playMillis: Long,
        fps: Double? = null,
        fpsWeightMillis: Long? = null,
        temperatureDeciCelsius: Double? = null,
        temperatureWeightMillis: Long? = null,
        drainRates: List<Double> = emptyList(),
        chargingSessions: Int = 0,
    ): SessionTrendAggregate = SessionTrendAggregate(
        day = day,
        packageName = packageName,
        sessionCount = sessions,
        playTimeMillis = playMillis,
        frameRateWeightedSum = if (fps != null && fpsWeightMillis != null) {
            fps * fpsWeightMillis
        } else {
            null
        },
        frameRateWeightMillis = fpsWeightMillis,
        frameRateSessionCount = if (fps != null) sessions else 0,
        temperatureWeightedSum = if (
            temperatureDeciCelsius != null && temperatureWeightMillis != null
        ) {
            temperatureDeciCelsius * temperatureWeightMillis
        } else {
            null
        },
        temperatureWeightMillis = temperatureWeightMillis,
        temperatureSessionCount = if (temperatureDeciCelsius != null) sessions else 0,
        drainRateSum = drainRates.takeIf { it.isNotEmpty() }?.sum(),
        drainSessionCount = drainRates.size,
        chargingSessionCount = chargingSessions,
    )

    private companion object {
        const val MINUTE = 60_000L
    }
}
