package com.gamecore.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The emulator frontend's ROM library.
 *
 * A `Flow` for the list the screen draws, one-shot reads for duplicate checks, and narrow updates for the
 * three things that change after a row is added: the chosen system, the remembered emulator, and the launch
 * counter. No update rewrites the whole row, so a launch cannot accidentally reset a field the caller did
 * not mean to touch.
 *
 * The unique index on `uri` is the duplicate rule, not the repository's map lookup alone: two pickers can
 * return the same document URI in quick succession, and the database is the authority that prevents a
 * second row.
 */
@Dao
interface RomDao {

    @Query("SELECT * FROM rom_library ORDER BY added_at DESC")
    fun observeAll(): Flow<List<RomEntity>>

    @Query("SELECT * FROM rom_library WHERE uri = :uri LIMIT 1")
    suspend fun byUri(uri: String): RomEntity?

    @Query("SELECT * FROM rom_library WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): RomEntity?

    @Query("SELECT COUNT(*) FROM rom_library")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rom: RomEntity): Long

    @Query("UPDATE rom_library SET system = :system, emulator_package = NULL WHERE id = :id")
    suspend fun updateSystem(id: Long, system: String)

    @Query("UPDATE rom_library SET emulator_package = :packageName WHERE id = :id")
    suspend fun updateEmulator(id: Long, packageName: String?)

    @Query("UPDATE rom_library SET last_launched_at = :at, launch_count = launch_count + 1 WHERE id = :id")
    suspend fun recordLaunch(id: Long, at: Long)

    @Query("DELETE FROM rom_library WHERE id = :id")
    suspend fun delete(id: Long)
}
