package com.gamecore.domain.config

import com.gamecore.core.config.ParsedConfig
import com.gamecore.core.config.UpdateDrift
import com.gamecore.core.config.ViewOnlyReason

/**
 * One game config file the editor acts on — the same three parts the privileged shell turns into a
 * validated path. A [ConfigTarget] is never a raw string: the package, the emulated-storage user and
 * the path relative to the game's `files` directory travel together so no call site can pair a path
 * with the wrong game.
 */
data class ConfigTarget(
    val packageName: String,
    val userId: Int,
    val relativePath: String,
)

/** Where a config open or save gave up, for an honest message rather than a bare failure. */
enum class ConfigOpStage {
    /** The path the parts named was refused by the shell's validator — never reached the device. */
    PATH_REJECTED,
    STAT,
    COPY_OUT,
    BACKUP,
    STAGE_EDIT,
    COPY_IN,
    VERIFY,
    COMMIT,
}

/** The outcome of opening a config file for viewing or editing. */
sealed interface ConfigOpenResult {

    /** A UTF-8 text file within the size limit: its text, a parse of it, and what it was on disk. */
    data class Editable(
        val text: String,
        val parsed: ParsedConfig,
        val sizeBytes: Long,
        val sha256: String,
        /**
         * Whether the game was updated since this file's original was first backed up (§A9). Compared
         * from the recorded original's `sourceLastUpdateTime` against the game's current install time,
         * so it defaults to [UpdateDrift.NO_BASELINE] — no original recorded, or no current time known,
         * which is the safe "cannot judge, so do not warn" direction rather than a false alarm.
         */
        val updateDrift: UpdateDrift = UpdateDrift.NO_BASELINE,
    ) : ConfigOpenResult

    /** Readable but not editable — binary, or larger than the editor will load. */
    data class ViewOnly(val reason: ViewOnlyReason, val sizeBytes: Long) : ConfigOpenResult

    /** No such file, or it is not a regular file (a directory or a symlink). */
    object NotFound : ConfigOpenResult

    /** The elevated shell is not available; the editor cannot reach the game's sandbox without it. */
    object RequiresShizuku : ConfigOpenResult

    data class Failed(val stage: ConfigOpStage, val detail: String) : ConfigOpenResult
}

/** The outcome of writing edited bytes back to a config file. */
sealed interface ConfigSaveResult {

    /** The file now holds the new content, committed by an atomic rename inside the sandbox. */
    data class Saved(val sizeBytes: Long, val sha256: String) : ConfigSaveResult

    /** The file to edit does not exist — the editor edits files, it does not create them. */
    object NotFound : ConfigSaveResult

    object RequiresShizuku : ConfigSaveResult

    /**
     * The save stopped before it could overwrite anything. Because the original is backed up first and
     * every failure returns before the commit rename, the live file is exactly as it was.
     */
    data class Failed(val stage: ConfigOpStage, val detail: String) : ConfigSaveResult
}

/**
 * The outcome of restoring a config file from one of its backups. Like a save, restore commits by an
 * atomic rename in the sandbox, and every failure returns before that rename — so the live file is
 * only ever replaced whole with the backed-up bytes or left exactly as it was.
 */
sealed interface ConfigRestoreResult {

    /** The live file now holds the backed-up bytes, committed by an atomic rename inside the sandbox. */
    data class Restored(val sizeBytes: Long, val sha256: String?) : ConfigRestoreResult

    /** No backup exists for the given id — nothing to restore from. */
    object NotFound : ConfigRestoreResult

    object RequiresShizuku : ConfigRestoreResult

    /** Stopped before the commit rename, so the live file is exactly as it was. */
    data class Failed(val stage: ConfigOpStage, val detail: String) : ConfigRestoreResult
}

/**
 * The outcome of taking a user checkpoint of a config file — a copy the user asked to keep, distinct
 * from the untouched original. A checkpoint never overwrites or displaces the original.
 */
sealed interface ConfigCheckpointResult {

    /** The snapshot was copied aside and recorded; [label] is what was stored after sanitising. */
    data class Captured(
        val backupId: Long,
        val label: String?,
        val sizeBytes: Long,
        val sha256: String,
    ) : ConfigCheckpointResult

    /** The file to snapshot does not exist — a checkpoint snapshots an existing file, it does not create one. */
    object NotFound : ConfigCheckpointResult

    object RequiresShizuku : ConfigCheckpointResult

    data class Failed(val stage: ConfigOpStage, val detail: String) : ConfigCheckpointResult
}
