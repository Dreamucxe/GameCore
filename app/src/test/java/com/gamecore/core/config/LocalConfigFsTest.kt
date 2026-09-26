package com.gamecore.core.config

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * [LocalConfigFs] is the production [AtomicFs] — the durable-disk half every guarantee in [AtomicWriter]
 * ultimately rests on. These tests exercise its five methods against real files in a [TemporaryFolder] and
 * pin the two contract points the writer above it cannot see for itself: [LocalConfigFs.writeFile] must
 * create a missing parent so the first stage into a fresh backup subtree does not fail, and
 * [LocalConfigFs.move] must *throw* on a bad rename rather than return false — that thrown failure is exactly
 * what the writer reads as [AtomicWriteFailure.MOVE_FAILED]. A move that silently no-op'd on failure, or a
 * read that returned empty instead of throwing, would let a broken commit masquerade as success.
 */
class LocalConfigFsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val fs = LocalConfigFs()

    /** Absolute path for [relative] under the temp root, so every case works on real on-disk files. */
    private fun path(relative: String): String = File(tempFolder.root, relative).path

    @Test
    fun `writeFile creates missing parent directories then writes the bytes`() {
        val target = path("staging/backups/config.bin")
        fs.writeFile(target, "hello".toByteArray())
        assertTrue("parent subtree should have been created", File(target).exists())
        assertArrayEquals("hello".toByteArray(), fs.readFile(target))
    }

    @Test
    fun `writeFile overwrites an existing file's contents`() {
        val target = path("config.bin")
        fs.writeFile(target, "first".toByteArray())
        fs.writeFile(target, "second".toByteArray())
        assertArrayEquals("second".toByteArray(), fs.readFile(target))
    }

    @Test
    fun `readFile throws when the path does not exist`() {
        // Broad type only: FileNotFoundException is an IOException, and the writer only cares that it throws.
        assertThrows(IOException::class.java) { fs.readFile(path("nope.bin")) }
    }

    @Test
    fun `move renames leaving no source and the original bytes at the destination`() {
        val from = path("from.bin")
        val to = path("to.bin")
        fs.writeFile(from, "payload".toByteArray())
        fs.move(from, to)
        assertFalse(fs.exists(from))
        assertArrayEquals("payload".toByteArray(), fs.readFile(to))
    }

    @Test
    fun `move onto an existing destination replaces it with the source bytes`() {
        val from = path("from.bin")
        val to = path("to.bin")
        fs.writeFile(from, "new".toByteArray())
        fs.writeFile(to, "stale".toByteArray())
        fs.move(from, to)
        assertArrayEquals("new".toByteArray(), fs.readFile(to))
        assertFalse(fs.exists(from))
    }

    @Test
    fun `move throws when the source file does not exist`() {
        // The atomic rename must surface a failed commit; swallowing it would break AtomicWriter's MOVE_FAILED.
        assertThrows(IOException::class.java) { fs.move(path("ghost.bin"), path("to.bin")) }
    }

    @Test
    fun `exists reflects whether a file was written`() {
        val target = path("config.bin")
        assertFalse(fs.exists(target))
        fs.writeFile(target, "hello".toByteArray())
        assertTrue(fs.exists(target))
    }

    @Test
    fun `delete removes an existing file and is a no-op on a missing path`() {
        val target = path("config.bin")
        fs.writeFile(target, "hello".toByteArray())
        fs.delete(target)
        assertFalse(fs.exists(target))
        // Deleting the now-absent path must not throw — best-effort cleanup leans on this.
        fs.delete(target)
    }
}
