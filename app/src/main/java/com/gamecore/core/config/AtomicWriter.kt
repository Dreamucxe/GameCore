package com.gamecore.core.config

/**
 * The atomic write half of the config editor (spec §A7): a config file is never edited in place. New content is
 * staged into a sibling temp file, read back to prove the bytes landed, and only then committed with a single
 * atomic rename that either fully replaces the target or leaves it untouched. Every failure *before* the rename
 * leaves the original file exactly as it was; the rename is the commit point.
 *
 * Filesystem access is injected as [AtomicFs] so the sequencing is pure and testable against an in-memory fake —
 * no disk, no privileged shell. Production supplies an [AtomicFs] backed by the elevated shell / java.io.File.
 * The temp file is always a sibling of the target (same directory) so the rename stays within one filesystem and
 * is genuinely atomic — a temp on a different mount would degrade the rename to a non-atomic copy.
 */
class AtomicWriter(private val fs: AtomicFs) {
    /**
     * Write [newContent] to [targetPath] atomically. Returns [AtomicWriteResult.Success] once the target holds
     * the new content and the read-backs agree, or [AtomicWriteResult.Failed] with the stage that gave up. On any
     * failure before the commit the original is intact; a temp left behind is cleaned up best-effort.
     */
    fun write(targetPath: String, newContent: ByteArray): AtomicWriteResult {
        val tempPath = "$targetPath$TEMP_SUFFIX"

        // 1. Stage into the sibling temp. The original target is untouched throughout this step.
        try {
            fs.writeFile(tempPath, newContent)
        } catch (e: Exception) {
            cleanUp(tempPath)
            return AtomicWriteResult.Failed(AtomicWriteFailure.TEMP_WRITE_FAILED)
        }

        // 2. Read the temp back and prove the bytes are correct BEFORE committing. A mismatch or read error here
        //    aborts with the original still in place — nothing has replaced it yet.
        val staged = try {
            fs.readFile(tempPath)
        } catch (e: Exception) {
            cleanUp(tempPath)
            return AtomicWriteResult.Failed(AtomicWriteFailure.READBACK_FAILED)
        }
        if (!staged.contentEquals(newContent)) {
            cleanUp(tempPath)
            return AtomicWriteResult.Failed(AtomicWriteFailure.READBACK_MISMATCH)
        }

        // 3. Commit with the atomic rename. If this throws, the move did not happen and the original stands.
        try {
            fs.move(tempPath, targetPath)
        } catch (e: Exception) {
            cleanUp(tempPath)
            return AtomicWriteResult.Failed(AtomicWriteFailure.MOVE_FAILED)
        }
        // 4. Read the committed target back as a final honest check on the durable state. By now the rename has
        //    succeeded, so a mismatch here is a lying filesystem rather than a recoverable abort.
        val committed = try {
            fs.readFile(targetPath)
        } catch (e: Exception) {
            return AtomicWriteResult.Failed(AtomicWriteFailure.READBACK_FAILED)
        }
        if (!committed.contentEquals(newContent)) {
            return AtomicWriteResult.Failed(AtomicWriteFailure.READBACK_MISMATCH)
        }
        return AtomicWriteResult.Success
    }

    /** Remove a leftover temp if one exists. Best-effort: a stray temp beside an intact original is harmless. */
    private fun cleanUp(tempPath: String) {
        try {
            if (fs.exists(tempPath)) fs.delete(tempPath)
        } catch (e: Exception) {
            // Swallow: cleanup failure must not mask the real outcome, and the original is unaffected.
        }
    }

    private companion object {
        const val TEMP_SUFFIX = ".gc-tmp"
    }
}

/**
 * The minimal filesystem surface [AtomicWriter] needs, injected so the write sequence stays pure and testable.
 * Production backs this with the elevated shell / java.io.File; tests back it with an in-memory map.
 */
interface AtomicFs {
    /** Create or overwrite [path] with [bytes]. */
    fun writeFile(path: String, bytes: ByteArray)

    /** Read [path]'s full contents. Throws if it does not exist or cannot be read. */
    fun readFile(path: String): ByteArray

    /** Atomically rename [from] onto [to], replacing [to] if present. Both paths must be on one filesystem. */
    fun move(from: String, to: String)

    /** True if [path] exists. */
    fun exists(path: String): Boolean

    /** Remove [path]. */
    fun delete(path: String)
}

/** The outcome of [AtomicWriter.write]. */
sealed interface AtomicWriteResult {
    /** The target holds the new content, verified by read-back; any temp file has been cleaned up. */
    object Success : AtomicWriteResult

    /** The write did not complete. The original file is intact unless [reason] is a read-back failure on the
     *  target itself (post-commit), which only a misbehaving filesystem produces. */
    data class Failed(val reason: AtomicWriteFailure) : AtomicWriteResult
}

/** Where an [AtomicWriter.write] gave up. */
enum class AtomicWriteFailure {
    /** Staging the new content into the temp file failed. Original untouched. */
    TEMP_WRITE_FAILED,

    /** Reading a just-written file back threw. */
    READBACK_FAILED,

    /** A read-back returned bytes differing from what was written. */
    READBACK_MISMATCH,

    /** The atomic rename of temp onto target failed. Original untouched. */
    MOVE_FAILED,
}
