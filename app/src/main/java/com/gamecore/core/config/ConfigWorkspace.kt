package com.gamecore.core.config

import java.io.File
import java.util.UUID

/**
 * GameCore's own end of the config editor: where files are staged, and where originals are kept.
 *
 * Every privileged copy has one end inside GameCore's external files directory (`ElevatedShell`'s
 * config `cp` enforces that — one side must be GameCore's own package). This is that side, expressed
 * once so the controller never hand-builds a path. Two subtrees live under it:
 *
 * - `configstaging/<uuid>/` — transient. A file copied out of a game for reading, or an edited file
 *   waiting to be copied back, lands here under a per-operation id so two edits cannot collide, and is
 *   deleted when the operation ends.
 * - `configbackups/<pkg>/<userId>/<relativePath>.orig` — durable. The one untouched original of a
 *   file, kept out of the game's sandbox so an uninstall or a wipe of the game cannot take the safety
 *   net with it. The path is deterministic per file, which is what lets the ledger's unique-path index
 *   make "never overwritten" a database invariant.
 *
 * Paths handed back are relative to the files directory, because that is exactly the `relativePath` the
 * shell's path builder expects for the GameCore side of a copy. The same string therefore names both
 * the local [File] this class reads and writes and the shell endpoint the controller copies to — they
 * cannot drift apart. [resolve] runs every local path through a [ConfigPathGuard] rooted at the files
 * directory: the parts are already validated where they come from, so a rejection here is a bug, and
 * failing loudly is the right answer to one.
 */
class ConfigWorkspace(
    filesRoot: File,
    /** The user id GameCore itself runs as — the GameCore side of every copy, not the game's. */
    val ownUserId: Int,
) {
    private val root: File = filesRoot
    private val guard = ConfigPathGuard(filesRoot.canonicalPath)
    private val writer = AtomicWriter(LocalConfigFs())

    /** A fresh, unique staging path under the files directory. Its parent is created on first write. */
    fun newStagingPath(): String = "$STAGING_DIR/${UUID.randomUUID()}/$STAGING_LEAF"

    /** The deterministic path of a file's untouched original, unique per game/user/file. */
    fun backupPath(packageName: String, userId: Int, relativePath: String): String =
        "$BACKUP_DIR/$packageName/$userId/$relativePath$ORIGINAL_SUFFIX"

    /**
     * A unique, timestamped path for a user checkpoint — a sibling of the file's deterministic
     * [backupPath] `.orig`. The capture millis plus a random token guarantee the ledger's unique
     * `backup_path` index is never hit even by two checkpoints of one file taken in the same
     * millisecond, and every segment stays well inside the shell path builder's length limits.
     */
    fun checkpointPath(packageName: String, userId: Int, relativePath: String, createdAtMillis: Long): String =
        "$BACKUP_DIR/$packageName/$userId/$relativePath.$createdAtMillis-${UUID.randomUUID()}$CHECKPOINT_SUFFIX"

    /**
     * The files-relative form of an absolute path this workspace produced — the inverse of [resolve].
     * A restore recovers the relative path a stored [backupPath] began as, so the same string can drive
     * the shell copy and the local [exists]/[read] again. Null when the absolute path is not under this
     * workspace's root, which a caller reads as a rejected (foreign or corrupt) backup path.
     */
    fun relativize(absoluteUnderRoot: String): String? {
        val prefix = root.canonicalPath + File.separator
        return absoluteUnderRoot.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
    }

    /** Delete a durable backup file — a checkpoint whose ledger row could not be written, or one being forgotten. */
    fun deleteFile(relativeUnderFiles: String) {
        File(resolve(relativeUnderFiles)).delete()
    }

    /** Absolute local path for a files-relative path, verified to sit inside the files directory. */
    fun resolve(relativeUnderFiles: String): String {
        val candidate = File(root, relativeUnderFiles).path
        return when (val verdict = guard.resolve(candidate)) {
            is PathVerdict.Allowed -> verdict.canonicalPath
            is PathVerdict.Rejected ->
                throw SecurityException("Config workspace path escaped root: ${verdict.reason}")
        }
    }

    /** Create the parent directory of a files-relative path so a shell `cp` into it has somewhere to land. */
    fun ensureParent(relativeUnderFiles: String) {
        File(resolve(relativeUnderFiles)).parentFile?.mkdirs()
    }

    /** Atomically write bytes to a files-relative path (stage → read back → rename). */
    fun writeAtomic(relativeUnderFiles: String, bytes: ByteArray): AtomicWriteResult {
        ensureParent(relativeUnderFiles)
        return writer.write(resolve(relativeUnderFiles), bytes)
    }

    fun read(relativeUnderFiles: String): ByteArray = File(resolve(relativeUnderFiles)).readBytes()

    fun exists(relativeUnderFiles: String): Boolean = File(resolve(relativeUnderFiles)).exists()

    /** Delete a staged file and, if it empties, the per-operation directory it lived in. */
    fun discardStaging(relativeUnderFiles: String) {
        val file = File(resolve(relativeUnderFiles))
        file.delete()
        file.parentFile?.takeIf { it.parentFile?.name == STAGING_DIR }?.delete()
    }

    private companion object {
        const val STAGING_DIR = "configstaging"
        const val STAGING_LEAF = "f"
        const val BACKUP_DIR = "configbackups"
        const val ORIGINAL_SUFFIX = ".orig"
        const val CHECKPOINT_SUFFIX = ".chkpt"
    }
}
