package com.gamecore.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gamecore.core.model.BatteryDrain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SQLite proof for 5.10's bounded session reads.
 *
 * The JVM tests own the date and delta arithmetic. These instrumented tests own what only Room and
 * SQLite can prove: both time boundaries, package/day grouping, the drain predicate, null-frame
 * exclusion, and the limits that replaced Smart Intelligence's unbounded `snapshot()` reads.
 *
 * They are kept under androidTest because this project deliberately has no Robolectric dependency.
 * Compiling them catches projection/query drift on the build host; executing them requires a device or
 * emulator and must not be inferred from compilation.
 */
@RunWith(AndroidJUnit4::class)
class SessionTrendDaoTest {

    private lateinit var database: GameCoreDatabase
    private lateinit var dao: SessionDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, GameCoreDatabase::class.java).build()
        dao = database.sessions()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun trendAggregates_areBoundedAtBothEnds_andGroupedByPackage() = runTest {
        val since = 1_800_000_000_000L
        val until = since + DAY
        dao.insert(session("game.a", since, durationMillis = 20 * MINUTE, fps = 60f))
        dao.insert(session("game.a", since + HOUR, durationMillis = 10 * MINUTE, fps = 30f))
        dao.insert(session("game.b", since + 2 * HOUR, durationMillis = 15 * MINUTE, fps = 45f))
        // The lower bound is inclusive; one millisecond before it is not.
        dao.insert(session("before", since - 1, durationMillis = 10 * MINUTE, fps = 120f))
        // The upper bound is exclusive, so adjacent periods cannot count the same session twice.
        dao.insert(session("after", until, durationMillis = 10 * MINUTE, fps = 120f))
        // A running row is not history and does not enter a trend.
        dao.insert(session("running", since + 3 * HOUR, durationMillis = null, fps = 90f))

        val all = dao.trendAggregates(
            since = since,
            until = until,
            packageName = null,
            minDrainMillis = BatteryDrain.MIN_RELIABLE_MILLIS,
        )

        assertEquals(setOf("game.a", "game.b"), all.map { it.packageName }.toSet())
        val gameA = all.single { it.packageName == "game.a" }
        assertEquals(2, gameA.sessionCount)
        assertEquals(30 * MINUTE, gameA.playTimeMillis)
        assertEquals(2, gameA.frameRateSessionCount)
        assertEquals(
            50.0,
            gameA.frameRateWeightedSum!! / gameA.frameRateWeightMillis!!,
            0.0001,
        )

        val filtered = dao.trendAggregates(
            since = since,
            until = until,
            packageName = "game.b",
            minDrainMillis = BatteryDrain.MIN_RELIABLE_MILLIS,
        )
        assertEquals(listOf("game.b"), filtered.map { it.packageName })
    }

    @Test
    fun trendDrain_usesTheSameReliabilityRulesAsBatteryDrain() = runTest {
        val since = 1_800_000_000_000L
        dao.insert(
            session(
                packageName = "game",
                startedAt = since,
                durationMillis = 10 * MINUTE,
                batteryStart = 100,
                batteryEnd = 98,
            ),
        )
        // Too short, even though extrapolating it would produce a dramatic number.
        dao.insert(
            session(
                packageName = "game",
                startedAt = since + HOUR,
                durationMillis = 4 * MINUTE,
                batteryStart = 100,
                batteryEnd = 99,
            ),
        )
        // A charger makes a rate incomparable.
        dao.insert(
            session(
                packageName = "game",
                startedAt = since + 2 * HOUR,
                durationMillis = 10 * MINUTE,
                batteryStart = 100,
                batteryEnd = 98,
                charging = true,
            ),
        )
        // Battery increasing is not drain and must not become a negative rate.
        dao.insert(
            session(
                packageName = "game",
                startedAt = since + 3 * HOUR,
                durationMillis = 10 * MINUTE,
                batteryStart = 90,
                batteryEnd = 92,
            ),
        )

        val row = dao.trendAggregates(
            since = since,
            until = since + DAY,
            packageName = "game",
            minDrainMillis = BatteryDrain.MIN_RELIABLE_MILLIS,
        ).single()

        assertEquals(1, row.drainSessionCount)
        assertEquals(12.0, row.drainRateSum!!, 0.0001)
        assertEquals(1, row.chargingSessionCount)
    }

    @Test
    fun bestAndWorst_ignoreSessionsWithoutFrameTiming() = runTest {
        val since = 1_800_000_000_000L
        dao.insert(session("unmeasured", since, durationMillis = 30 * MINUTE, fps = null))
        val worstId = dao.insert(
            session("slow", since + HOUR, durationMillis = 30 * MINUTE, fps = 30f),
        )
        val bestId = dao.insert(
            session("fast", since + 2 * HOUR, durationMillis = 30 * MINUTE, fps = 60f),
        )

        val best = dao.bestFrameRateSession(since, since + DAY, null)
        val worst = dao.worstFrameRateSession(since, since + DAY, null)

        assertEquals(bestId, best?.id)
        assertEquals(60f, best!!.averageFrameRate, 0f)
        assertEquals(worstId, worst?.id)
        assertEquals(30f, worst!!.averageFrameRate, 0f)
        assertNull(dao.bestFrameRateSession(since, since + DAY, "unmeasured"))
    }

    @Test
    fun intelligenceReads_areBoundedByPackageWindowAndLimit() = runTest {
        val since = 1_800_000_000_000L
        dao.insert(session("game.a", since, durationMillis = 10 * MINUTE))
        val middle = dao.insert(session("game.a", since + HOUR, durationMillis = 10 * MINUTE))
        val newest = dao.insert(session("game.a", since + 2 * HOUR, durationMillis = 10 * MINUTE))
        dao.insert(session("game.b", since + 3 * HOUR, durationMillis = 10 * MINUTE))
        // recentFinished excludes open rows, while startedBetween deliberately keeps them because an
        // active session can confirm that a profile change has taken effect.
        val running = dao.insert(session("game.a", since + 4 * HOUR, durationMillis = null))

        val recent = dao.recentFinished(packageName = "game.a", limit = 2)
        assertEquals(listOf(newest, middle), recent.map { it.id })

        val window = dao.startedBetween(
            since = since + 2 * HOUR,
            until = since + 4 * HOUR,
            packageName = "game.a",
            limit = 10,
        )
        assertEquals(listOf(running, newest), window.map { it.id })
    }

    private fun session(
        packageName: String,
        startedAt: Long,
        durationMillis: Long?,
        fps: Float? = null,
        temperature: Int? = 400,
        batteryStart: Int = 100,
        batteryEnd: Int? = 98,
        charging: Boolean = false,
    ) = SessionEntity(
        id = 0L,
        packageName = packageName,
        gameLabel = packageName,
        startedAtMillis = startedAt,
        endedAtMillis = durationMillis?.let(startedAt::plus),
        batteryStartPercent = batteryStart,
        batteryEndPercent = batteryEnd,
        wasCharging = charging,
        averageCpuPercent = null,
        peakCpuPercent = null,
        averageMemoryPercent = null,
        peakMemoryPercent = null,
        averageTemperatureDeciCelsius = temperature,
        peakTemperatureDeciCelsius = temperature,
        averageRefreshRate = 60f,
        averageFrameRate = fps,
        averageLatencyMillis = null,
        profileApplied = false,
        sampleCount = 100,
    )

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
