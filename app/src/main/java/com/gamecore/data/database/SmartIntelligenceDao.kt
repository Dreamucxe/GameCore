package com.gamecore.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Benchmarks and learning records.
 *
 * The benchmark queries are all ordered by `started_at DESC`, because every screen that reads them
 * asks the same question: what happened most recently on this device? The learning table is small
 * enough to read whole; its rows are counted by [com.gamecore.domain.intelligence.LearningEngine],
 * not filtered in SQL, so the rules and the storage cannot disagree about what counts.
 */
@Dao
interface SmartIntelligenceDao {

    // ------------------------------------------------------------------------------ benchmarks

    @Query("SELECT * FROM smart_benchmarks ORDER BY started_at DESC LIMIT :limit")
    fun observeBenchmarks(limit: Int): Flow<List<SmartBenchmarkEntity>>

    @Query(
        "SELECT * FROM smart_benchmarks WHERE game_package = :packageName " +
            "ORDER BY started_at DESC LIMIT :limit",
    )
    fun observeBenchmarksFor(packageName: String, limit: Int): Flow<List<SmartBenchmarkEntity>>

    @Query("SELECT * FROM smart_benchmarks ORDER BY started_at DESC LIMIT :limit")
    suspend fun recentBenchmarks(limit: Int): List<SmartBenchmarkEntity>

    @Query("SELECT * FROM smart_benchmarks WHERE id = :id LIMIT 1")
    suspend fun benchmark(id: Long): SmartBenchmarkEntity?

    /** `IS` rather than `=` so a NULL package (a device-only run) is found by a NULL argument. */
    @Query(
        "SELECT * FROM smart_benchmarks WHERE game_package IS :gamePackage " +
            "ORDER BY started_at DESC LIMIT 1",
    )
    suspend fun latestBenchmark(gamePackage: String?): SmartBenchmarkEntity?

    /**
     * The most recent run of the same profile, which is what a comparison baseline is.
     *
     * Same package and same profile: two runs of different profiles are not the same test, and
     * [BenchmarkMath.compare] would refuse them anyway. This query exists so the applier can point
     * at the run it means rather than calling the newest run the baseline when it is not one.
     */
    @Query(
        "SELECT * FROM smart_benchmarks WHERE game_package IS :gamePackage AND profile = :profile " +
            "ORDER BY started_at DESC LIMIT 1",
    )
    suspend fun latestBenchmarkFor(gamePackage: String?, profile: String): SmartBenchmarkEntity?

    @Query("SELECT COUNT(*) FROM smart_benchmarks")
    suspend fun benchmarkCount(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBenchmark(benchmark: SmartBenchmarkEntity): Long

    @Query("DELETE FROM smart_benchmarks WHERE id = :id")
    suspend fun deleteBenchmark(id: Long)

    @Query("DELETE FROM smart_benchmarks")
    suspend fun clearBenchmarks()

    // -------------------------------------------------------------------------------- learning

    @Query("SELECT * FROM smart_learning_records ORDER BY created_at DESC")
    fun observeLearning(): Flow<List<SmartLearningRecordEntity>>

    @Query("SELECT * FROM smart_learning_records ORDER BY created_at DESC")
    suspend fun allLearning(): List<SmartLearningRecordEntity>

    @Query(
        "SELECT * FROM smart_learning_records WHERE action_key = :actionKey " +
            "ORDER BY created_at DESC",
    )
    suspend fun learningFor(actionKey: String): List<SmartLearningRecordEntity>

    @Query("SELECT COUNT(*) FROM smart_learning_records")
    suspend fun learningCount(): Int

    /**
     * The last action the user applied for this game before a benchmark started.
     *
     * This is what lets a comparison be attributed to a real change instead of to whatever happens
     * to be at the top of the log. `IS` handles the device-only NULL package; the timestamp is the
     * candidate run's start, so an action applied *after* the capture cannot claim its result.
     */
    @Query(
        "SELECT * FROM smart_learning_records WHERE decision = 'APPLIED' " +
            "AND game_package IS :gamePackage AND created_at >= :afterMillis " +
            "AND created_at <= :atMillis " +
            "ORDER BY created_at DESC LIMIT 1",
    )
    suspend fun latestAppliedBetween(
        gamePackage: String?,
        afterMillis: Long,
        atMillis: Long,
    ): SmartLearningRecordEntity?

    @Query(
        "SELECT created_at FROM smart_learning_records WHERE decision = 'GENERATED' " +
            "AND action_key = :actionKey AND game_package IS :gamePackage AND goal = :goal " +
            "ORDER BY created_at DESC LIMIT 1",
    )
    suspend fun latestGeneratedAt(
        actionKey: String,
        gamePackage: String?,
        goal: String,
    ): Long?

    /** One stored comparison outcome per benchmark run; re-inspecting a run cannot double-count it. */
    @Query(
        "SELECT * FROM smart_learning_records WHERE source_run_id = :runId " +
            "AND decision = 'TESTED' LIMIT 1",
    )
    suspend fun outcomeForRun(runId: Long): SmartLearningRecordEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLearning(record: SmartLearningRecordEntity): Long

    @Query("DELETE FROM smart_learning_records WHERE id = :id")
    suspend fun deleteLearning(id: Long)

    @Query("DELETE FROM smart_learning_records")
    suspend fun clearLearning()

    /** Both Intelligence histories are one reset operation: either both clear or neither does. */
    @Transaction
    suspend fun clearAllIntelligenceData() {
        clearBenchmarks()
        clearLearning()
    }
}
