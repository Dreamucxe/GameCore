package com.gamecore.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes saved controller profiles.
 *
 * Observed lists are `Flow`; one-shot reads and every write are `suspend`, matching the rest of the
 * database. `REPLACE` serves both create (id 0) and edit, so an editor does not need two statements.
 * Profiles are ordered by most-recent edit, because the one a user just changed is the one they are most
 * likely to compare next.
 */
@Dao
interface ControllerProfileDao {

    @Query("SELECT * FROM controller_profiles ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<ControllerProfileEntity>>

    @Query(
        "SELECT * FROM controller_profiles WHERE device_signature = :signature " +
            "ORDER BY updated_at DESC",
    )
    fun observeForDevice(signature: String): Flow<List<ControllerProfileEntity>>

    @Query("SELECT * FROM controller_profiles ORDER BY updated_at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ControllerProfileEntity>

    @Query("SELECT * FROM controller_profiles WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): ControllerProfileEntity?

    @Query("SELECT * FROM controller_profiles")
    suspend fun all(): List<ControllerProfileEntity>

    @Query("SELECT COUNT(*) FROM controller_profiles")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ControllerProfileEntity): Long

    @Query("DELETE FROM controller_profiles WHERE id = :id")
    suspend fun delete(id: Long)
}

/**
 * Reads and writes drift-run summaries.
 *
 * The limit on [observeForDevice] is deliberate: a history pane is a comparison aid, not an archive of
 * every run since install. Older rows remain in the table and can still be exported or counted; the screen
 * simply does not render hundreds of them at once.
 */
@Dao
interface ControllerTestResultDao {

    @Query(
        "SELECT * FROM controller_test_results WHERE device_signature = :signature " +
            "ORDER BY started_at DESC LIMIT :limit",
    )
    fun observeForDevice(signature: String, limit: Int): Flow<List<ControllerTestResultEntity>>

    @Query("SELECT * FROM controller_test_results ORDER BY started_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ControllerTestResultEntity>>

    @Query(
        "SELECT * FROM controller_test_results WHERE device_signature = :signature " +
            "ORDER BY started_at DESC LIMIT 1",
    )
    suspend fun latestForDevice(signature: String): ControllerTestResultEntity?

    @Query("SELECT * FROM controller_test_results WHERE device_signature = :signature ORDER BY started_at DESC")
    suspend fun allForDevice(signature: String): List<ControllerTestResultEntity>

    @Query("SELECT COUNT(*) FROM controller_test_results")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(result: ControllerTestResultEntity): Long

    @Query("DELETE FROM controller_test_results WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM controller_test_results WHERE device_signature = :signature")
    suspend fun clearForDevice(signature: String)

    @Query("DELETE FROM controller_test_results")
    suspend fun clearAll()
}
