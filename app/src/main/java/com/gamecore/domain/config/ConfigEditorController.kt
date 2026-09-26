package com.gamecore.domain.config

import com.gamecore.BuildConfig
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.config.AtomicWriteResult
import com.gamecore.core.config.ConfigFileInspector
import com.gamecore.core.config.ConfigParser
import com.gamecore.core.config.ConfigWorkspace
import com.gamecore.core.config.FileInspection
import com.gamecore.core.config.UpdateDrift
import com.gamecore.core.config.UpdateDriftDetector
import com.gamecore.core.config.ViewOnlyReason
import com.gamecore.core.model.ConfigBackup
import com.gamecore.core.model.ConfigBackupKind
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import com.gamecore.data.repository.ConfigBackupRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The config editor's coordinator: it turns "show me this file" and "save these bytes" into the
 * privileged copies, the local atomic write and the ledger entries that make either safe.
 *
 * The shape is dictated by one hard constraint — file bytes never travel through the shell. The shell
 * moves whole files between two paths; it cannot hand back the contents of one. So a file is read by
 * copying it out of the game's sandbox into GameCore's own storage ([ConfigWorkspace]) and reading it
 * there, and it is written by staging the new bytes locally, copying them back in as a sibling temp,
 * and committing with an atomic rename inside the sandbox. Every step that can fail returns before the
 * rename, so the live file is only ever replaced whole or left exactly as it was (§A7).
 *
 * The save's first move, before anything can overwrite the file, is to secure its untouched original
 * (§A6): the original is copied to a durable, out-of-sandbox backup and recorded once, first-write-wins,
 * so no later edit can promote an already-edited file to "original". If that backup cannot be taken the
 * save refuses rather than proceed unprotected. Restoring from a backup, and user-taken checkpoints,
 * build on this in a later step.
 */
@Singleton
class ConfigEditorController @Inject constructor(
    private val shell: ElevatedShell,
    private val workspace: ConfigWorkspace,
    private val backups: ConfigBackupRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** True when the elevated shell is reachable — the editor's precondition. */
    suspend fun isAvailable(): Boolean = withContext(io) { shell.isAvailable() }

    /**
     * Copy [target] out of its sandbox and inspect it. A UTF-8 text file within the size limit comes
     * back [ConfigOpenResult.Editable] with a parse ready for the UI; a binary or over-limit file comes
     * back [ConfigOpenResult.ViewOnly]. The staged copy is always cleaned up.
     *
     * [currentLastUpdateTime] is the game's `PackageInfo.lastUpdateTime` as the caller reads it now; it
     * is compared against the timestamp recorded when this file's original was first backed up to fill
     * [ConfigOpenResult.Editable.updateDrift] (§A9). Null — the caller could not read it — cannot raise
     * a warning, which is the safe direction: an unknown install time is not treated as a change.
     */
    suspend fun open(
        target: ConfigTarget,
        currentLastUpdateTime: Long? = null,
    ): ConfigOpenResult = withContext(io) {
        if (!shell.isAvailable()) return@withContext ConfigOpenResult.RequiresShizuku

        val statCmd = ShellCommand.statConfigFile(target.packageName, target.userId, target.relativePath)
            ?: return@withContext ConfigOpenResult.Failed(ConfigOpStage.PATH_REJECTED, "path rejected")
        val stat = shell.execute(statCmd)
        // A failed stat is ambiguous: the file is genuinely gone, or the shell vanished between the
        // availability gate above and this call (its binder died, so execute() came back a bare failure).
        // Re-consult the shell — a still-live shell means the file is really missing (NotFound); a shell
        // that is now gone means we could not look, which is RequiresShizuku, not a false "no longer exists".
        if (!stat.isSuccess) {
            return@withContext if (shell.isAvailable()) ConfigOpenResult.NotFound
            else ConfigOpenResult.RequiresShizuku
        }
        val parsedStat = parseStat(stat.stdout)
            ?: return@withContext ConfigOpenResult.Failed(ConfigOpStage.STAT, "unreadable stat: ${stat.stdout.take(64)}")
        val (size, type) = parsedStat
        if (!type.contains("regular")) return@withContext ConfigOpenResult.NotFound
        if (size > ConfigFileInspector.MAX_EDITABLE_BYTES) {
            return@withContext ConfigOpenResult.ViewOnly(ViewOnlyReason.TOO_LARGE, size)
        }

        val staging = workspace.newStagingPath()
        try {
            workspace.ensureParent(staging)
            val copyOut = ShellCommand.copyConfigFile(
                target.packageName, target.userId, target.relativePath,
                BuildConfig.APPLICATION_ID, workspace.ownUserId, staging,
            ) ?: return@withContext ConfigOpenResult.Failed(ConfigOpStage.PATH_REJECTED, "copy path rejected")
            val copied = shell.execute(copyOut)
            if (!copied.isSuccess) {
                return@withContext ConfigOpenResult.Failed(ConfigOpStage.COPY_OUT, copied.failureReason())
            }
            val bytes = workspace.read(staging)
            when (val inspection = ConfigFileInspector.inspect(bytes)) {
                is FileInspection.Editable -> ConfigOpenResult.Editable(
                    text = inspection.text,
                    parsed = ConfigParser.parse(inspection.text),
                    sizeBytes = bytes.size.toLong(),
                    sha256 = sha256(bytes),
                    updateDrift = driftFor(target, currentLastUpdateTime),
                )
                is FileInspection.ViewOnly -> ConfigOpenResult.ViewOnly(inspection.reason, bytes.size.toLong())
            }
        } catch (t: Throwable) {
            ConfigOpenResult.Failed(ConfigOpStage.COPY_OUT, t.message ?: t::class.java.simpleName)
        } finally {
            runCatching { workspace.discardStaging(staging) }
        }
    }

    /**
     * Write [newContent] back to [target] atomically, and only after the untouched original is safe.
     * [sourceLastUpdateTime] is the game's `lastUpdateTime` at this moment; it is stored alongside the
     * original so a later update-drift check has a baseline, and null is honest when it is unknown.
     *
     * The order is load-bearing: stat first (a missing file is [ConfigSaveResult.NotFound] — the editor
     * edits, it does not create), then back up the original, then stage-and-verify the new bytes, and
     * only last the atomic rename that commits them. Every failure before the rename returns with the
     * live file untouched; a failure after copy-in also sweeps the sandbox temp it left behind.
     */
    suspend fun save(
        target: ConfigTarget,
        newContent: ByteArray,
        sourceLastUpdateTime: Long? = null,
    ): ConfigSaveResult = withContext(io) {
        if (!shell.isAvailable()) return@withContext ConfigSaveResult.RequiresShizuku

        val statCmd = ShellCommand.statConfigFile(target.packageName, target.userId, target.relativePath)
            ?: return@withContext ConfigSaveResult.Failed(ConfigOpStage.PATH_REJECTED, "path rejected")
        val stat = shell.execute(statCmd)
        // As in open(): a failed stat with a still-live shell is a genuinely missing file (NotFound), but a
        // shell that vanished mid-save means we could not check — RequiresShizuku, so the caller re-prompts
        // rather than telling the user their file disappeared when it is really the elevated shell that did.
        if (!stat.isSuccess) {
            return@withContext if (shell.isAvailable()) ConfigSaveResult.NotFound
            else ConfigSaveResult.RequiresShizuku
        }
        val type = parseStat(stat.stdout)?.second
        if (type == null || !type.contains("regular")) return@withContext ConfigSaveResult.NotFound

        ensureOriginalBackedUp(target, sourceLastUpdateTime)?.let { return@withContext it }

        val editStaging = workspace.newStagingPath()
        val tmpRelative = "${target.relativePath}$SANDBOX_TEMP_SUFFIX"
        try {
            when (val staged = workspace.writeAtomic(editStaging, newContent)) {
                is AtomicWriteResult.Failed ->
                    return@withContext ConfigSaveResult.Failed(ConfigOpStage.STAGE_EDIT, staged.reason.name)
                AtomicWriteResult.Success -> Unit
            }
            val copyIn = ShellCommand.copyConfigFile(
                BuildConfig.APPLICATION_ID, workspace.ownUserId, editStaging,
                target.packageName, target.userId, tmpRelative,
            ) ?: return@withContext ConfigSaveResult.Failed(ConfigOpStage.PATH_REJECTED, "temp path rejected")
            val copied = shell.execute(copyIn)
            if (!copied.isSuccess) {
                cleanSandboxTemp(target, tmpRelative)
                return@withContext ConfigSaveResult.Failed(ConfigOpStage.COPY_IN, copied.failureReason())
            }
            val verifyCmd = ShellCommand.statConfigFile(target.packageName, target.userId, tmpRelative)
            val stagedSize = verifyCmd?.let { shell.execute(it) }
                ?.takeIf { it.isSuccess }
                ?.let { parseStat(it.stdout)?.first }
            if (stagedSize != newContent.size.toLong()) {
                cleanSandboxTemp(target, tmpRelative)
                return@withContext ConfigSaveResult.Failed(
                    ConfigOpStage.VERIFY, "staged $stagedSize of ${newContent.size} bytes",
                )
            }
            val moveCmd = ShellCommand.moveConfigFile(
                target.packageName, target.userId, tmpRelative, target.relativePath,
            )
            if (moveCmd == null) {
                cleanSandboxTemp(target, tmpRelative)
                return@withContext ConfigSaveResult.Failed(ConfigOpStage.PATH_REJECTED, "move path rejected")
            }
            val moved = shell.execute(moveCmd)
            if (!moved.isSuccess) {
                cleanSandboxTemp(target, tmpRelative)
                return@withContext ConfigSaveResult.Failed(ConfigOpStage.COMMIT, moved.failureReason())
            }
            ConfigSaveResult.Saved(newContent.size.toLong(), sha256(newContent))
        } catch (t: Throwable) {
            cleanSandboxTemp(target, tmpRelative)
            ConfigSaveResult.Failed(ConfigOpStage.COMMIT, t.message ?: t::class.java.simpleName)
        } finally {
            runCatching { workspace.discardStaging(editStaging) }
        }
    }

    /**
     * Put a backed-up copy of [target] back as the live file. The source is the durable backup named by
     * [backupId]; its bytes are copied into a sandbox sibling temp, size-verified against the recorded
     * backup, and committed with an atomic rename — the same commit discipline as [save], so a failure
     * anywhere before the rename leaves the live file exactly as it was and sweeps the temp. Unlike a
     * save, a missing live file is not [ConfigRestoreResult.NotFound]: restoring over a config a bad edit
     * destroyed is the safety net's whole purpose; only a missing *backup* is NotFound.
     */
    suspend fun restore(target: ConfigTarget, backupId: Long): ConfigRestoreResult = withContext(io) {
        if (!shell.isAvailable()) return@withContext ConfigRestoreResult.RequiresShizuku

        val backup = backups.byId(backupId) ?: return@withContext ConfigRestoreResult.NotFound
        // Never restore one file's copy onto another: the id must name a backup of this very target.
        if (backup.packageName != target.packageName ||
            backup.userId != target.userId ||
            backup.relativePath != target.relativePath
        ) {
            return@withContext ConfigRestoreResult.Failed(
                ConfigOpStage.PATH_REJECTED, "backup does not match target",
            )
        }
        val backupRel = workspace.relativize(backup.backupPath)
            ?: return@withContext ConfigRestoreResult.Failed(
                ConfigOpStage.PATH_REJECTED, "backup path outside workspace",
            )
        if (!workspace.exists(backupRel)) {
            return@withContext ConfigRestoreResult.Failed(ConfigOpStage.BACKUP, "backup file missing")
        }

        val tmpRelative = "${target.relativePath}$SANDBOX_TEMP_SUFFIX"
        try {
            val copyIn = ShellCommand.copyConfigFile(
                BuildConfig.APPLICATION_ID, workspace.ownUserId, backupRel,
                target.packageName, target.userId, tmpRelative,
            ) ?: return@withContext ConfigRestoreResult.Failed(ConfigOpStage.PATH_REJECTED, "temp path rejected")
            val copied = shell.execute(copyIn)
            if (!copied.isSuccess) {
                cleanSandboxTemp(target, tmpRelative)
                return@withContext ConfigRestoreResult.Failed(ConfigOpStage.COPY_IN, copied.failureReason())
            }
            val verifyCmd = ShellCommand.statConfigFile(target.packageName, target.userId, tmpRelative)
            val stagedSize = verifyCmd?.let { shell.execute(it) }
                ?.takeIf { it.isSuccess }
                ?.let { parseStat(it.stdout)?.first }
            if (stagedSize != backup.sizeBytes) {
                cleanSandboxTemp(target, tmpRelative)
                return@withContext ConfigRestoreResult.Failed(
                    ConfigOpStage.VERIFY, "staged $stagedSize of ${backup.sizeBytes} bytes",
                )
            }
            val moveCmd = ShellCommand.moveConfigFile(
                target.packageName, target.userId, tmpRelative, target.relativePath,
            )
            if (moveCmd == null) {
                cleanSandboxTemp(target, tmpRelative)
                return@withContext ConfigRestoreResult.Failed(ConfigOpStage.PATH_REJECTED, "move path rejected")
            }
            val moved = shell.execute(moveCmd)
            if (!moved.isSuccess) {
                cleanSandboxTemp(target, tmpRelative)
                return@withContext ConfigRestoreResult.Failed(ConfigOpStage.COMMIT, moved.failureReason())
            }
            ConfigRestoreResult.Restored(backup.sizeBytes, backup.sha256)
        } catch (t: Throwable) {
            cleanSandboxTemp(target, tmpRelative)
            ConfigRestoreResult.Failed(ConfigOpStage.COMMIT, t.message ?: t::class.java.simpleName)
        }
    }

    /**
     * Take a user checkpoint of [target] — a labelled copy kept alongside, never in place of, the
     * untouched original. It stats the file first ([ConfigCheckpointResult.NotFound] for a missing one,
     * because a checkpoint snapshots an existing file rather than creating one), copies it out to a
     * durable, uniquely-named `.chkpt` under GameCore's storage, and records a [ConfigBackupKind.CHECKPOINT]
     * row. [label] is passed raw and sanitised on the way into the ledger; [sourceLastUpdateTime] mirrors
     * [save] so a checkpoint carries the same drift baseline. Checkpoints do not touch the original.
     */
    suspend fun checkpoint(
        target: ConfigTarget,
        label: String?,
        sourceLastUpdateTime: Long? = null,
    ): ConfigCheckpointResult = withContext(io) {
        if (!shell.isAvailable()) return@withContext ConfigCheckpointResult.RequiresShizuku

        val statCmd = ShellCommand.statConfigFile(target.packageName, target.userId, target.relativePath)
            ?: return@withContext ConfigCheckpointResult.Failed(ConfigOpStage.PATH_REJECTED, "path rejected")
        val stat = shell.execute(statCmd)
        // As in open()/save(): disambiguate a missing file from a shell that vanished before the stat ran,
        // so a checkpoint of an existing file is never reported as NotFound merely because Shizuku dropped.
        if (!stat.isSuccess) {
            return@withContext if (shell.isAvailable()) ConfigCheckpointResult.NotFound
            else ConfigCheckpointResult.RequiresShizuku
        }
        val type = parseStat(stat.stdout)?.second
        if (type == null || !type.contains("regular")) return@withContext ConfigCheckpointResult.NotFound

        val nowMillis = System.currentTimeMillis()
        val checkpointRel = workspace.checkpointPath(
            target.packageName, target.userId, target.relativePath, nowMillis,
        )
        val outStaging = workspace.newStagingPath()
        try {
            workspace.ensureParent(outStaging)
            val copyOut = ShellCommand.copyConfigFile(
                target.packageName, target.userId, target.relativePath,
                BuildConfig.APPLICATION_ID, workspace.ownUserId, outStaging,
            ) ?: return@withContext ConfigCheckpointResult.Failed(ConfigOpStage.PATH_REJECTED, "copy path rejected")
            val copied = shell.execute(copyOut)
            if (!copied.isSuccess) {
                return@withContext ConfigCheckpointResult.Failed(ConfigOpStage.COPY_OUT, copied.failureReason())
            }
            val bytes = workspace.read(outStaging)
            when (val written = workspace.writeAtomic(checkpointRel, bytes)) {
                is AtomicWriteResult.Failed ->
                    return@withContext ConfigCheckpointResult.Failed(ConfigOpStage.BACKUP, written.reason.name)
                AtomicWriteResult.Success -> Unit
            }
            val digest = sha256(bytes)
            val recorded = backups.record(
                ConfigBackup(
                    id = ConfigBackup.UNSAVED_ID,
                    packageName = target.packageName,
                    userId = target.userId,
                    relativePath = target.relativePath,
                    backupPath = workspace.resolve(checkpointRel),
                    kind = ConfigBackupKind.CHECKPOINT,
                    label = label,
                    sizeBytes = bytes.size.toLong(),
                    sha256 = digest,
                    sourceLastUpdateTime = sourceLastUpdateTime,
                    createdAtMillis = nowMillis,
                ),
            )
            if (recorded == null) {
                // The unique-path collision is practically impossible for a timestamped, tokened name; if
                // it somehow happens the durable copy has no ledger row, so sweep it rather than orphan it.
                workspace.deleteFile(checkpointRel)
                return@withContext ConfigCheckpointResult.Failed(
                    ConfigOpStage.BACKUP, "could not record the checkpoint",
                )
            }
            ConfigCheckpointResult.Captured(
                backupId = recorded.id,
                label = recorded.label,
                sizeBytes = bytes.size.toLong(),
                sha256 = digest,
            )
        } catch (t: Throwable) {
            ConfigCheckpointResult.Failed(ConfigOpStage.BACKUP, t.message ?: t::class.java.simpleName)
        } finally {
            runCatching { workspace.discardStaging(outStaging) }
        }
    }

    /**
     * Every backup of [target], newest first — the untouched original and any user checkpoints — so the
     * editor's Restore can offer a choice of what to put back. A pure ledger read: it lists what has been
     * copied aside and touches neither the shell nor the live file, and it is empty when nothing has been
     * backed up yet (a file has no original until the first save secures one). The repository already reads
     * on the IO dispatcher, so this delegates straight to it rather than switching context a second time.
     */
    suspend fun backups(target: ConfigTarget): List<ConfigBackup> =
        backups.forFile(target.packageName, target.userId, target.relativePath)

    /**
     * The update-drift verdict for [target] (§A9): compare the game's [currentLastUpdateTime] against the
     * `sourceLastUpdateTime` recorded when this file's original was first backed up. A null current time,
     * or no original recorded yet, both reduce to [UpdateDrift.NO_BASELINE] — there is nothing to compare,
     * so nothing to warn about. The baseline is the original's stamp and never a checkpoint's, because the
     * original is the one copy pinned to "before GameCore touched this file".
     */
    private suspend fun driftFor(target: ConfigTarget, currentLastUpdateTime: Long?): UpdateDrift {
        if (currentLastUpdateTime == null) return UpdateDrift.NO_BASELINE
        val baseline = backups
            .originalFor(target.packageName, target.userId, target.relativePath)
            ?.sourceLastUpdateTime
        return UpdateDriftDetector.detect(baseline, currentLastUpdateTime)
    }

    /**
     * Secure the untouched original before a save can overwrite it. Returns null when the original is
     * safe — either just backed up, or already recorded, in which case it is left exactly as it is
     * (first-write-wins). Returns a [ConfigSaveResult.Failed] to abort the save when the original could
     * not be secured, so the caller never overwrites an unprotected file.
     */
    private suspend fun ensureOriginalBackedUp(
        target: ConfigTarget,
        sourceLastUpdateTime: Long?,
    ): ConfigSaveResult? {
        if (backups.originalFor(target.packageName, target.userId, target.relativePath) != null) return null

        val outStaging = workspace.newStagingPath()
        try {
            workspace.ensureParent(outStaging)
            val copyOut = ShellCommand.copyConfigFile(
                target.packageName, target.userId, target.relativePath,
                BuildConfig.APPLICATION_ID, workspace.ownUserId, outStaging,
            ) ?: return ConfigSaveResult.Failed(ConfigOpStage.PATH_REJECTED, "backup copy path rejected")
            val copied = shell.execute(copyOut)
            if (!copied.isSuccess) return ConfigSaveResult.Failed(ConfigOpStage.BACKUP, copied.failureReason())

            val liveBytes = workspace.read(outStaging)
            val backupRelative = workspace.backupPath(target.packageName, target.userId, target.relativePath)
            // First-write-wins on disk, mirroring the ledger: if a `.orig` is already there (a concurrent
            // save, or a previous attempt that crashed after writing the bytes but before recording the
            // row) it is the untouched original and must not be overwritten with anything later. In that
            // case the bytes we record must be the ones already on disk, not the live file we just copied
            // out — the live file may since have been edited (its ledger row forgotten while the `.orig`
            // outlived it), and the row's size/hash must describe what actually sits at backupPath. When
            // no `.orig` exists yet the two are identical, so the common first-save path is unchanged. We
            // still fall through to record() either way, so a crash-orphaned file gets its ledger row.
            val originalBytes = if (workspace.exists(backupRelative)) {
                workspace.read(backupRelative)
            } else {
                when (val written = workspace.writeAtomic(backupRelative, liveBytes)) {
                    is AtomicWriteResult.Failed ->
                        return ConfigSaveResult.Failed(ConfigOpStage.BACKUP, written.reason.name)
                    AtomicWriteResult.Success -> Unit
                }
                liveBytes
            }
            val recorded = backups.record(
                ConfigBackup(
                    id = ConfigBackup.UNSAVED_ID,
                    packageName = target.packageName,
                    userId = target.userId,
                    relativePath = target.relativePath,
                    backupPath = workspace.resolve(backupRelative),
                    kind = ConfigBackupKind.ORIGINAL,
                    label = null,
                    sizeBytes = originalBytes.size.toLong(),
                    sha256 = sha256(originalBytes),
                    sourceLastUpdateTime = sourceLastUpdateTime,
                    createdAtMillis = 0L,
                ),
            )
            return if (recorded == null) {
                ConfigSaveResult.Failed(ConfigOpStage.BACKUP, "could not record the original")
            } else {
                null
            }
        } catch (t: Throwable) {
            return ConfigSaveResult.Failed(ConfigOpStage.BACKUP, t.message ?: t::class.java.simpleName)
        } finally {
            runCatching { workspace.discardStaging(outStaging) }
        }
    }

    /**
     * Best-effort removal of the sandbox sibling temp a save may have left behind. It runs on every
     * failure after copy-in so a doomed save never orphans a `.gc-tmp` next to the live file; the delete
     * is `rm -f`, so an already-absent temp is not an error, and any failure to reach the shell is
     * swallowed — the live file is already safe, and a stray temp is not worth failing the report over.
     */
    private suspend fun cleanSandboxTemp(target: ConfigTarget, tmpRelative: String) {
        ShellCommand.deleteConfigFile(target.packageName, target.userId, tmpRelative)
            ?.let { runCatching { shell.execute(it) } }
    }

    /**
     * Parse one `stat -c "%s|%F"` line into (size, type). The command prints `<bytes>|<description>`,
     * e.g. `1024|regular file`; the description is matched loosely (`contains("regular")`) because it is
     * a human string, not a stable token. Returns null when the line is missing or the size is not a
     * number, which the callers read as an unreadable stat rather than a real file.
     */
    private fun parseStat(stdout: String): Pair<Long, String>? {
        val line = stdout.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val parts = line.split('|', limit = 2)
        if (parts.size != 2) return null
        val size = parts[0].trim().toLongOrNull() ?: return null
        return size to parts[1].trim()
    }

    /** Lowercase hex SHA-256 of [bytes], recorded alongside every backup and returned to the UI. */
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        /** Suffix of the sandbox sibling the new bytes land on before the atomic rename commits them. */
        const val SANDBOX_TEMP_SUFFIX = ".gc-tmp"
    }
}
