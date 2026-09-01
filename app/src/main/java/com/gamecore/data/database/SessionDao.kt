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

    @Transaction
    suspend fun deleteWithSamples(id: Long) {
        deleteSamples(id)
        deleteSession(id)
    }

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()

    @Query("DELETE FROM session_samples")
    suspend fun deleteAllSamples()

    @Transaction
    suspend fun clearHistory() {
        deleteAllSamples()
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
}

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
