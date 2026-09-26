package com.gamecore.data.repository

import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.model.ConfigBackup
import com.gamecore.core.model.ConfigBackupKind
import com.gamecore.data.database.ConfigBackupDao
import com.gamecore.data.database.ConfigBackupMappers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ledger of config files GameCore has copied aside.
 *
 * This is the data half of the config-editor safety net (§A6): it records *that* a file was backed
 * up, never the bytes, which live on disk under `filesDir/configbackups/<pkg>/` and are written by the
 * file layer. The rule that makes it a safety net is in [record]: **the first [ConfigBackupKind.ORIGINAL]
 * recorded for a file wins and is never replaced.** Applying the very first edit to a file copies the
 * untouched original aside; a second edit later must not record the already-edited file as though it
 * were the original, or the user's escape hatch would quietly become a copy of the thing they are
 * trying to escape. `@Insert(OnConflictStrategy.IGNORE)` on the unique backup path is what enforces
 * it — at the database, not in a read-then-write here that two coroutines could interleave — exactly
 * as [RestorePointRepository] enforces its own first-write-wins rule.
 *
 * Checkpoints are the opposite: the user asks for one at a point they like, they carry a label and a
 * unique (timestamped) path, and so they always insert.
 */
@Singleton
class ConfigBackupRepository @Inject constructor(
    private val dao: ConfigBackupDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Every backup for one game, newest first, as a stream for the backup list. */
    fun observeForApp(packageName: String, userId: Int): Flow<List<ConfigBackup>> =
        dao.observeForApp(packageName, userId).map { rows -> rows.map(ConfigBackupMappers::toModel) }

    /** Every backup of one specific file, newest first. */
    suspend fun forFile(packageName: String, userId: Int, relativePath: String): List<ConfigBackup> =
        withContext(io) {
            dao.forFile(packageName, userId, relativePath).map(ConfigBackupMappers::toModel)
        }

    /** The untouched original for a file, or null if none has ever been taken. */
    suspend fun originalFor(packageName: String, userId: Int, relativePath: String): ConfigBackup? =
        withContext(io) {
            dao.original(packageName, userId, relativePath, ConfigBackupKind.ORIGINAL.name)
                ?.let(ConfigBackupMappers::toModel)
        }

    suspend fun byId(id: Long): ConfigBackup? =
        withContext(io) { dao.byId(id)?.let(ConfigBackupMappers::toModel) }

    /** For a per-game "N backups" note. A `COUNT(*)`, not a list. */
    suspend fun countForApp(packageName: String, userId: Int): Int =
        withContext(io) { dao.countForApp(packageName, userId) }

    /**
     * Records a backup, honouring the first-original-wins rule.
     *
     * The bytes must already be on disk at [ConfigBackup.backupPath] before this is called — this
     * writes only the ledger row. Dispatch is on [ConfigBackup.kind]:
     *
     * - [ConfigBackupKind.ORIGINAL]: if an original already exists for the file, it is returned
     *   unchanged and nothing is written — the caller learns the original was already there and knows
     *   not to have overwritten the file on disk. Otherwise the row is inserted. The insert is still
     *   `IGNORE` on the unique path, so a race that slips past the pre-check collapses to the same
     *   outcome: the first copy stands and the second read returns it.
     * - [ConfigBackupKind.CHECKPOINT]: always inserted (a checkpoint's path is unique). A path
     *   collision — practically impossible for a timestamped name — returns null rather than pretending
     *   a row was written.
     *
     * The returned [ConfigBackup] carries the assigned rowid, so a caller can reference it immediately.
     */
    suspend fun record(
        backup: ConfigBackup,
        nowMillis: Long = System.currentTimeMillis(),
    ): ConfigBackup? = withContext(io) {
        val stamped = backup.copy(
            id = ConfigBackup.UNSAVED_ID,
            createdAtMillis = if (backup.createdAtMillis > 0L) backup.createdAtMillis else nowMillis,
        )

        if (stamped.kind == ConfigBackupKind.ORIGINAL) {
            val existing = dao.original(
                stamped.packageName,
                stamped.userId,
                stamped.relativePath,
                ConfigBackupKind.ORIGINAL.name,
            )
            if (existing != null) return@withContext ConfigBackupMappers.toModel(existing)
        }

        val entity = ConfigBackupMappers.toEntity(stamped)
        val rowId = dao.insertIfAbsent(entity)
        when {
            rowId >= 0L -> ConfigBackupMappers.toModel(entity.copy(id = rowId))
            // Ignored on the unique path. For an original that means another writer won the race:
            // return whatever now stands as the original. For a checkpoint it means the path was
            // somehow already taken, which we report as "not recorded" rather than invent a row.
            stamped.kind == ConfigBackupKind.ORIGINAL -> dao.original(
                stamped.packageName,
                stamped.userId,
                stamped.relativePath,
                ConfigBackupKind.ORIGINAL.name,
            )?.let(ConfigBackupMappers::toModel)
            else -> null
        }
    }

    /**
     * Removes a checkpoint's ledger row. Originals are refused.
     *
     * Returns true when a row was removed. An [ConfigBackupKind.ORIGINAL] is never removed here — the
     * whole point of the original is that it survives until the file it protects is gone — so this
     * returns false for one without touching the table. Forgetting an original is only ever the
     * explicit, whole-game [forgetApp]. The stored file on disk is the caller's to delete once the row
     * is gone.
     */
    suspend fun remove(backup: ConfigBackup): Boolean = withContext(io) {
        if (backup.isOriginal) return@withContext false
        dao.delete(backup.id)
        true
    }

    /**
     * Drops every backup row for one game, originals included.
     *
     * The one path that forgets an original, and only ever the user asking for it after being told
     * what it means — never a side effect of deleting a profile or tidying up something else.
     */
    suspend fun forgetApp(packageName: String, userId: Int) = withContext(io) {
        dao.deleteForApp(packageName, userId)
    }
}
