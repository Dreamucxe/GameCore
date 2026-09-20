package com.gamecore.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes the Aim Lab session summaries and the derived personal records.
 *
 * Observed lists are `Flow`; one-shot reads and every write are `suspend` — the same split every DAO in
 * this project follows. Records are upserted by their unique (mode, difficulty, weapon, metric) key, which
 * is what lets the repository ask "is this a new record" with one insert-or-replace rather than a read.
 */
@Dao
interface AimLabSessionDao {

    @Query("SELECT * FROM aimlab_sessions ORDER BY started_at DESC")
    fun observeSessions(): Flow<List<AimLabSessionEntity>>

    @Query("SELECT COUNT(*) FROM aimlab_sessions")
    fun observeSessionCount(): Flow<Int>

    @Query("SELECT * FROM aimlab_sessions ORDER BY started_at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AimLabSessionEntity>

    @Query("SELECT * FROM aimlab_sessions WHERE mode = :mode ORDER BY started_at DESC")
    suspend fun forMode(mode: String): List<AimLabSessionEntity>

    @Query("SELECT * FROM aimlab_sessions WHERE id = :id LIMIT 1")
    suspend fun session(id: Long): AimLabSessionEntity?

    @Query("SELECT * FROM aimlab_sessions")
    suspend fun allSessions(): List<AimLabSessionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: AimLabSessionEntity): Long

    @Query("DELETE FROM aimlab_sessions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM aimlab_sessions")
    suspend fun clearSessions()

    // ---- records ----

    @Query("SELECT * FROM aimlab_records")
    fun observeRecords(): Flow<List<AimLabRecordEntity>>

    @Query(
        "SELECT * FROM aimlab_records WHERE mode = :mode AND difficulty = :difficulty " +
            "AND metric = :metric AND scoring_version = :scoringVersion " +
            "AND (weapon_name IS :weapon OR weapon_name = :weapon) LIMIT 1",
    )
    suspend fun record(
        mode: String,
        difficulty: String,
        weapon: String?,
        metric: String,
        scoringVersion: Int,
    ): AimLabRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecord(record: AimLabRecordEntity)

    @Query("DELETE FROM aimlab_records")
    suspend fun clearRecords()

    /** Clears sessions and the records derived from them together, so history never half-clears. */
    @Transaction
    suspend fun clearAll() {
        clearSessions()
        clearRecords()
    }
}

/** Reads and writes training weapons. Flat rows; REPLACE serves both create (id 0) and edit. */
@Dao
interface AimLabWeaponDao {

    @Query("SELECT * FROM aimlab_weapons ORDER BY is_built_in DESC, name COLLATE NOCASE ASC")
    fun observeWeapons(): Flow<List<AimLabWeaponEntity>>

    @Query("SELECT * FROM aimlab_weapons WHERE id = :id LIMIT 1")
    suspend fun weapon(id: Long): AimLabWeaponEntity?

    @Query("SELECT COUNT(*) FROM aimlab_weapons")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(weapon: AimLabWeaponEntity): Long

    @Query("DELETE FROM aimlab_weapons WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Reads and writes sensitivity profiles. */
@Dao
interface AimLabSensitivityDao {

    @Query("SELECT * FROM aimlab_sensitivities ORDER BY name COLLATE NOCASE ASC")
    fun observeSensitivities(): Flow<List<AimLabSensitivityEntity>>

    @Query("SELECT * FROM aimlab_sensitivities WHERE id = :id LIMIT 1")
    suspend fun sensitivity(id: Long): AimLabSensitivityEntity?

    @Query("SELECT COUNT(*) FROM aimlab_sensitivities")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: AimLabSensitivityEntity): Long

    @Query("DELETE FROM aimlab_sensitivities WHERE id = :id")
    suspend fun delete(id: Long)
}

/**
 * Reads and writes control layouts and their controls across two tables.
 *
 * [save] mirrors `HudLayoutDao.save` exactly: insert the layout, resolve its id, delete its existing
 * controls, reinsert the new set remapped to the resolved id. Wholesale replace, in one transaction, with
 * no foreign-key cascade to depend on.
 */
@Dao
interface AimLabLayoutDao {

    @Query("SELECT * FROM aimlab_layouts ORDER BY updated_at DESC")
    fun observeLayouts(): Flow<List<AimLabLayoutEntity>>

    /**
     * The list, editor and training screen all read a layout with its controls through this one relation
     * query, so the "N of M controls enabled" count and the drawn overlay come from the same real rows
     * (§bug-fix). Room fills [AimLabLayoutWithControls.controls] with every control row for each layout in
     * one query, across both orientations.
     */
    @Transaction
    @Query("SELECT * FROM aimlab_layouts ORDER BY updated_at DESC")
    fun observeLayoutsWithControls(): Flow<List<AimLabLayoutWithControls>>

    @Transaction
    @Query("SELECT * FROM aimlab_layouts WHERE id = :id LIMIT 1")
    suspend fun layoutWithControls(id: Long): AimLabLayoutWithControls?

    @Transaction
    @Query("SELECT * FROM aimlab_layouts")
    suspend fun allLayoutsWithControls(): List<AimLabLayoutWithControls>

    @Query("SELECT * FROM aimlab_layouts WHERE id = :id LIMIT 1")
    suspend fun layout(id: Long): AimLabLayoutEntity?

    @Query("SELECT * FROM aimlab_controls WHERE layout_id = :layoutId")
    suspend fun controls(layoutId: Long): List<AimLabControlEntity>

    @Query("SELECT * FROM aimlab_layouts")
    suspend fun allLayouts(): List<AimLabLayoutEntity>

    @Query("SELECT COUNT(*) FROM aimlab_layouts")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLayout(layout: AimLabLayoutEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertControls(controls: List<AimLabControlEntity>)

    @Query("DELETE FROM aimlab_controls WHERE layout_id = :layoutId")
    suspend fun deleteControls(layoutId: Long)

    @Query("DELETE FROM aimlab_layouts WHERE id = :id")
    suspend fun deleteLayout(id: Long)

    @Transaction
    suspend fun save(layout: AimLabLayoutEntity, controls: List<AimLabControlEntity>): Long {
        val id = insertLayout(layout)
        val resolved = if (layout.id == 0L) id else layout.id
        deleteControls(resolved)
        if (controls.isNotEmpty()) {
            insertControls(controls.map { it.copy(layoutId = resolved) })
        }
        return resolved
    }

    @Transaction
    suspend fun deleteWithControls(id: Long) {
        deleteControls(id)
        deleteLayout(id)
    }
}
