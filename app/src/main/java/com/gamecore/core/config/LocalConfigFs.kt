package com.gamecore.core.config

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * The production [AtomicFs]: [AtomicWriter]'s window onto GameCore's *own* storage.
 *
 * This is deliberately not the privileged shell. A config file's bytes are copied out of the game's
 * sandbox into GameCore's external files directory by an `ElevatedShell` `cp`, and only there — inside
 * an app-private tree this process can read and write with ordinary `java.io` — are they staged, read
 * back and atomically committed. The shell never carries file bytes (its stdout is capped and it takes
 * no stdin); it moves whole files between two paths. So the atomic-write discipline runs against local
 * disk, and the shell is what ferries the finished file back across the boundary.
 *
 * [move] is a real atomic rename via [Files.move] with [StandardCopyOption.ATOMIC_MOVE] rather than a
 * copy-then-delete: [AtomicWriter] leans on the rename being the single indivisible commit point, and a
 * non-atomic move would reopen the exact window — a half-written target — the writer exists to close.
 * `Files` is available from API 26, which is this app's `minSdk`. A failure throws, which is precisely
 * what [AtomicWriter] reads as [AtomicWriteFailure.MOVE_FAILED]; returning a boolean would swallow it.
 */
class LocalConfigFs : AtomicFs {

    override fun writeFile(path: String, bytes: ByteArray) {
        val file = File(path)
        // The sibling temp AtomicWriter stages into shares the target's directory; create it once here
        // so the first write to a fresh backup/staging subtree does not fail for a missing parent.
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    override fun readFile(path: String): ByteArray = File(path).readBytes()

    override fun move(from: String, to: String) {
        Files.move(Paths.get(from), Paths.get(to), StandardCopyOption.ATOMIC_MOVE)
    }

    override fun exists(path: String): Boolean = File(path).exists()

    override fun delete(path: String) {
        File(path).delete()
    }
}
