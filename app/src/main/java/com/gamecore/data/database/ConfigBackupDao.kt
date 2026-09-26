package com.gamecore.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The config-backup ledger.
 *
 * The one rule worth stating is the same one [RestorePointDao] enforces: the first copy of a file
 * wins and is never replaced. [insertIfAbsent] is `OnConflictStrategy.IGNORE`, and the conflict it
 * ignores is on the unique `backup_path` — so writing an original whose deterministic path already
 * exists is a no-op rather than an overwrite, at the database, where two coroutines cannot interleave
 * a read-then-write. Checkpoints carry a unique (timestamped) path, so they always insert.
 *
 * Every read is scoped by the parts that name a file or an app; there is no unfiltered "all backups"
 * query, because there is no screen that wants one and a table this can grow without bound should not
 * offer a way to pull all of it into memory.
 */
@Dao
interface ConfigBackupDao {

    /** Every backup for one game, newest first — what the per-game backup list observes. */
    @Query(
        "SELECT * FROM config_backups WHERE package_name = :packageName AND user_id = :userId " +
            "ORDER BY created_at DESC",
    )
    fun observeForApp(packageName: String, userId: Int): Flow<List<ConfigBackupEntity>>

    /** Every backup of one specific file, newest first. */
    @Query(
        "SELECT * FROM config_backups WHERE package_name = :packageName AND user_id = :userId " +
            "AND relative_path = :relativePath ORDER BY created_at DESC",
    )
    suspend fun forFile(packageName: String, userId: Int, relativePath: String): List<ConfigBackupEntity>

    /**
     * The one original for a file, if it has ever been backed up.
     *
     * [kind] is passed by the caller as [com.gamecore.core.model.ConfigBackupKind.ORIGINAL]`.name`
     * rather than hard-coded here, so a rename of the constant is a compile error at the call site
     * instead of a query that silently matches nothing.
     */
    @Query(
        "SELECT * FROM config_backups WHERE package_name = :packageName AND user_id = :userId " +
            "AND relative_path = :relativePath AND kind = :kind LIMIT 1",
    )
    suspend fun original(
        packageName: String,
        userId: Int,
        relativePath: String,
        kind: String,
    ): ConfigBackupEntity?

    @Query("SELECT * FROM config_backups WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): ConfigBackupEntity?

    @Query("SELECT COUNT(*) FROM config_backups WHERE package_name = :packageName AND user_id = :userId")
    suspend fun countForApp(packageName: String, userId: Int): Int

    /**
     * Inserts a backup unless one already occupies its path.
     *
     * Returns the new rowid, or -1 when the insert was ignored because [ConfigBackupEntity.backupPath]
     * was already taken — which is exactly how a caller learns the original was already there without a
     * separate read.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(backup: ConfigBackupEntity): Long

    /** Removes one backup row. The stored file on disk is the caller's to delete alongside it. */
    @Query("DELETE FROM config_backups WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Drops every backup row for one game.
     *
     * The explicit "forget this game's backups" path, and the reason there is no cascade from a
     * profile delete: this happens only when the user asks for it, never as a side effect of tidying
     * up something else.
     */
    @Query("DELETE FROM config_backups WHERE package_name = :packageName AND user_id = :userId")
    suspend fun deleteForApp(packageName: String, userId: Int)
}
