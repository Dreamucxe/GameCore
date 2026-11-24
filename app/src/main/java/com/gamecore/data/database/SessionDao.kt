package com.gamecore.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Sessions and their samples.
 *
 * The aggregate queries here are the reason this is SQL rather than in-memory folding. A user
 * with a few hundred sessions loading every row and its samples to render a summary card would
 * be reading tens of thousands of rows to produce six numbers; SQLite computes them in the
 * database file and returns one row.
 *
 * `AVG` over a nullable column skips NULLs, which is exactly the behaviour the honesty rule
 * needs: a session whose CPU was never readable contributes nothing to the CPU average instead
 * of dragging it toward zero.
 */
@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions WHERE ended_at IS NOT NULL ORDER BY started_at DESC")
    fun observeFinished(): Flow<List<SessionEntity>>

    @Query(
        """
        SELECT * FROM sessions
        WHERE ended_at IS NOT NULL AND package_name = :packageName
        ORDER BY started_at DESC
        """,
    )
    fun observeForPackage(packageName: String): Flow<List<SessionEntity>>

    /**
     * The same rows [observeFinished] emits, read once.
     *
     * The CSV export needs every finished session exactly once and has no interest in later
     * changes, and collecting a `Flow` to take its first value would leave the subscription's
     * lifetime up to whoever cancelled first.
     */
    @Query("SELECT * FROM sessions WHERE ended_at IS NOT NULL ORDER BY started_at DESC")
    suspend fun finished(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE ended_at IS NULL ORDER BY started_at DESC")
    suspend fun unfinished(): List<SessionEntity>

    @Query("SELECT DISTINCT package_name FROM sessions ORDER BY package_name ASC")
    suspend fun recordedPackages(): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("DELETE FROM session_samples WHERE session_id = :sessionId")
    suspend fun deleteSamples(sessionId: Long)

    @Query("DELETE FROM session_events WHERE session_id = :sessionId")
    suspend fun deleteEvents(sessionId: Long)

    @Transaction
    suspend fun deleteWithSamples(id: Long) {
        deleteSamples(id)
        deleteEvents(id)
        deleteSession(id)
    }

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()

    @Query("DELETE FROM session_samples")
    suspend fun deleteAllSamples()

    @Query("DELETE FROM session_events")
    suspend fun deleteAllEvents()

    @Transaction
    suspend fun clearHistory() {
        deleteAllSamples()
        deleteAllEvents()
        deleteAllSessions()
    }

    // -------------------------------------------------------------------- samples

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSample(sample: SessionSampleEntity)

    /**
     * Batched insert, used by the session tracker.
     *
     * Samples are buffered in memory for a few seconds and written together rather than one
     * transaction per sample: an encrypted database pays a page re-encryption per commit, and a
     * commit every two seconds for two hours is measurable in battery.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSamples(samples: List<SessionSampleEntity>)

    @Query("SELECT * FROM session_samples WHERE session_id = :sessionId ORDER BY elapsed_millis ASC")
    suspend fun samples(sessionId: Long): List<SessionSampleEntity>

    @Query("SELECT COUNT(*) FROM session_samples WHERE session_id = :sessionId")
    suspend fun sampleCount(sessionId: Long): Int

    // -------------------------------------------------------------------- events

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: SessionEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvents(events: List<SessionEventEntity>)

    /**
     * One session's whole timeline, oldest first.
     *
     * The order is by elapsed offset rather than by id. They agree for rows this build wrote, but a
     * backup restored from another device can carry events whose insertion order is not their
     * session order, and a timeline that draws in insertion order would be wrong on exactly the
     * sessions the migration feature exists for.
     */
    @Query(
        "SELECT * FROM session_events WHERE session_id = :sessionId " +
            "ORDER BY elapsed_millis ASC, id ASC",
    )
    suspend fun events(sessionId: Long): List<SessionEventEntity>

    // ----------------------------------------------------------------- aggregates

    @Query("SELECT COUNT(*) FROM sessions WHERE ended_at IS NOT NULL")
    fun observeSessionCount(): Flow<Int>

    /** The same count as a one-shot, for the statistics summary that reads six figures at once. */
    @Query("SELECT COUNT(*) FROM sessions WHERE ended_at IS NOT NULL")
    suspend fun finishedCount(): Int

    @Query(
        """
        SELECT COALESCE(SUM(ended_at - started_at), 0) FROM sessions
        WHERE ended_at IS NOT NULL
        """,
    )
    suspend fun totalPlayTimeMillis(): Long

    @Query(
        """
        SELECT COALESCE(MAX(ended_at - started_at), 0) FROM sessions
        WHERE ended_at IS NOT NULL
        """,
    )
    suspend fun longestSessionMillis(): Long

    @Query("SELECT MAX(peak_temperature) FROM sessions WHERE ended_at IS NOT NULL")
    suspend fun peakTemperatureDeciCelsius(): Int?

    /**
     * The game with the most total time, as one row.
     *
     * `LIMIT 1` after a grouped sum rather than loading every package's total, because the
     * history screen shows one name.
     */
    @Query(
        """
        SELECT package_name AS packageName,
               game_label AS gameLabel,
               SUM(ended_at - started_at) AS totalMillis
        FROM sessions
        WHERE ended_at IS NOT NULL
        GROUP BY package_name
        ORDER BY totalMillis DESC
        LIMIT 1
        """,
    )
    suspend fun mostPlayed(): PackagePlaytime?

    /**
     * Mean battery drain per hour across sessions where the figure is meaningful.
     *
     * The filter is the model's own rule made explicit in SQL: no charger, and at least five
     * minutes long. Sessions that fail it are excluded rather than included with a wild rate,
     * which is what `AVG` over every row would produce.
     */
    @Query(
        """
        SELECT AVG(
            (battery_start - battery_end) * 3600000.0 / (ended_at - started_at)
        )
        FROM sessions
        WHERE ended_at IS NOT NULL
          AND battery_end IS NOT NULL
          AND was_charging = 0
          AND battery_start >= battery_end
          AND (ended_at - started_at) >= :minMillis
        """,
    )
    suspend fun averageDrainPercentPerHour(minMillis: Long): Float?

    // ------------------------------------------------------------------ session trends

    /**
     * One package's sessions on one local day, bounded by both ends of the period.
     *
     * `strftime(..., 'localtime')` groups by the user's calendar day rather than by UTC, which is what
     * "yesterday" means on the screen. The boundaries themselves are computed in Java time by
     * [com.gamecore.core.model.SessionTrends.window]; SQLite only names the day, so a daylight-saving
     * transition does not stretch or shorten a bucket.
     *
     * The weighted sums keep the numerator and its weight separate. A package-day row can then be folded
     * together with another package's row for the same day without averaging two averages and letting a
     * ten-minute session count as much as a three-hour one.
     *
     * The drain case repeats [com.gamecore.core.model.BatteryDrain]'s rules in SQL for the same reason
     * [averageDrainPercentPerHour] does: a session the model would refuse to quote a rate for must not
     * enter the trend either. A session whose battery rose is excluded as well — it did not measure
     * drain, and counting it as `0.0` would drag the average toward a rate nobody observed. The `CASE`
     * yields NULL for excluded rows, which `SUM` and `COUNT(expression)` skip, so a session with no
     * measurable rate can still contribute its other measured columns.
     */
    @Query(
        """
        SELECT strftime('%Y-%m-%d', started_at / 1000, 'unixepoch', 'localtime') AS day,
               package_name AS packageName,
               COUNT(*) AS sessionCount,
               SUM(ended_at - started_at) AS playTimeMillis,
               SUM(
                   CASE WHEN avg_frame_rate IS NOT NULL
                        THEN avg_frame_rate * (ended_at - started_at)
                   END
               ) AS frameRateWeightedSum,
               SUM(
                   CASE WHEN avg_frame_rate IS NOT NULL
                        THEN ended_at - started_at
                   END
               ) AS frameRateWeightMillis,
               COUNT(CASE WHEN avg_frame_rate IS NOT NULL THEN 1 END) AS frameRateSessionCount,
               SUM(
                   CASE WHEN avg_temperature IS NOT NULL
                        THEN avg_temperature * (ended_at - started_at)
                   END
               ) AS temperatureWeightedSum,
               SUM(
                   CASE WHEN avg_temperature IS NOT NULL
                        THEN ended_at - started_at
                   END
               ) AS temperatureWeightMillis,
               COUNT(CASE WHEN avg_temperature IS NOT NULL THEN 1 END) AS temperatureSessionCount,
               SUM(
                   CASE WHEN battery_end IS NOT NULL
                         AND was_charging = 0
                         AND battery_start >= battery_end
                         AND (ended_at - started_at) >= :minDrainMillis
                        THEN (battery_start - battery_end) * 3600000.0 / (ended_at - started_at)
                   END
               ) AS drainRateSum,
               COUNT(
                   CASE WHEN battery_end IS NOT NULL
                         AND was_charging = 0
                         AND battery_start >= battery_end
                         AND (ended_at - started_at) >= :minDrainMillis
                        THEN 1 END
               ) AS drainSessionCount,
               SUM(CASE WHEN was_charging = 1 THEN 1 ELSE 0 END) AS chargingSessionCount
        FROM sessions
        WHERE ended_at IS NOT NULL
          AND started_at >= :since
          AND started_at < :until
          AND (:packageName IS NULL OR package_name = :packageName)
        GROUP BY day, package_name
        ORDER BY day ASC, package_name ASC
        """,
    )
    suspend fun trendAggregates(
        since: Long,
        until: Long,
        packageName: String?,
        minDrainMillis: Long,
    ): List<SessionDayAggregate>

    /**
     * The best average frame rate in the period, or null when no session measured one.
     *
     * `avg_frame_rate IS NOT NULL` is the whole honesty rule: a session where the game exposed no frame
     * timing has no average, and must not win or lose a comparison with a zero. The tie-break prefers the
     * longer session, because its average rests on more samples.
     */
    @Query(
        """
        SELECT id AS id,
               package_name AS packageName,
               game_label AS gameLabel,
               started_at AS startedAtMillis,
               (ended_at - started_at) AS durationMillis,
               avg_frame_rate AS averageFrameRate
        FROM sessions
        WHERE ended_at IS NOT NULL
          AND started_at >= :since
          AND started_at < :until
          AND avg_frame_rate IS NOT NULL
          AND (:packageName IS NULL OR package_name = :packageName)
        ORDER BY avg_frame_rate DESC, (ended_at - started_at) DESC, id ASC
        LIMIT 1
        """,
    )
    suspend fun bestFrameRateSession(
        since: Long,
        until: Long,
        packageName: String?,
    ): TrendSessionProjection?

    @Query(
        """
        SELECT id AS id,
               package_name AS packageName,
               game_label AS gameLabel,
               started_at AS startedAtMillis,
               (ended_at - started_at) AS durationMillis,
               avg_frame_rate AS averageFrameRate
        FROM sessions
        WHERE ended_at IS NOT NULL
          AND started_at >= :since
          AND started_at < :until
          AND avg_frame_rate IS NOT NULL
          AND (:packageName IS NULL OR package_name = :packageName)
        ORDER BY avg_frame_rate ASC, (ended_at - started_at) DESC, id ASC
        LIMIT 1
        """,
    )
    suspend fun worstFrameRateSession(
        since: Long,
        until: Long,
        packageName: String?,
    ): TrendSessionProjection?

    // ------------------------------------------------------------ bounded intelligence reads

    /**
     * The newest finished sessions, at most [limit].
     *
     * This is the bounded replacement for the intelligence path's `snapshot()`. The recommendation rules
     * read at most twenty sessions, so asking SQLite for twenty instead of loading every row the user has
     * ever recorded changes the answer not at all and changes the cost from history-sized to constant.
     */
    @Query(
        """
        SELECT * FROM sessions
        WHERE ended_at IS NOT NULL
          AND (:packageName IS NULL OR package_name = :packageName)
        ORDER BY started_at DESC
        LIMIT :limit
        """,
    )
    suspend fun recentFinished(packageName: String?, limit: Int): List<SessionEntity>

    /**
     * Sessions that started inside a window, newest first.
     *
     * Benchmark attribution only needs sessions after the baseline run, and it can only confirm a profile
     * change from a session that started after the change was applied. Bounding by `started_at` is
     * therefore both smaller and more correct than loading all history and filtering in Kotlin. The limit
     * is a guard against a pathological window, not the primary boundary.
     */
    @Query(
        """
        SELECT * FROM sessions
        WHERE started_at >= :since
          AND started_at <= :until
          AND (:packageName IS NULL OR package_name = :packageName)
        ORDER BY started_at DESC
        LIMIT :limit
        """,
    )
    suspend fun startedBetween(
        since: Long,
        until: Long,
        packageName: String?,
        limit: Int,
    ): List<SessionEntity>
}

/** One row of [SessionDao.trendAggregates]. A projection, not an entity. */
data class SessionDayAggregate(
    val day: String,
    val packageName: String,
    val sessionCount: Int,
    val playTimeMillis: Long,
    val frameRateWeightedSum: Double?,
    val frameRateWeightMillis: Long?,
    val frameRateSessionCount: Int,
    val temperatureWeightedSum: Double?,
    val temperatureWeightMillis: Long?,
    val temperatureSessionCount: Int,
    val drainRateSum: Double?,
    val drainSessionCount: Int,
    val chargingSessionCount: Int,
)

/** One row of [SessionDao.bestFrameRateSession] and [SessionDao.worstFrameRateSession]. */
data class TrendSessionProjection(
    val id: Long,
    val packageName: String,
    val gameLabel: String,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val averageFrameRate: Float,
)

/** One row of [SessionDao.mostPlayed]. A projection, not an entity. */
data class PackagePlaytime(
    val packageName: String,
    val gameLabel: String,
    val totalMillis: Long,
)

/**
 * Pending settings restores.
 *
 * Small, rarely-read, and the most consequential table in the database: these rows are what
 * stops a device staying pinned to a setting GameCore wrote before it was killed.
 */
@Dao
interface RestorePointDao {

    @Query("SELECT * FROM restore_points")
    suspend fun all(): List<RestorePointEntity>

    @Query("SELECT COUNT(*) FROM restore_points")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(point: RestorePointEntity)

    @Query("DELETE FROM restore_points WHERE namespace = :namespace AND key = :key")
    suspend fun delete(namespace: String, key: String)

    @Query("DELETE FROM restore_points")
    suspend fun deleteAll()
}
