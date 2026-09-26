package com.gamecore.core.config

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

/**
 * The atomic-write sequence (spec §A7), exercised against an in-memory filesystem so temp-stage → read-back →
 * atomic-rename → read-back can be driven deterministically, including a forced failure at each stage. The
 * property that matters most is safety: whenever write() reports failure before the commit, the original file
 * must still hold its old bytes. Every failure test below asserts exactly that.
 */
class AtomicWriterTest {

    private val target = "/data/media/0/Android/data/com.game/files/settings.ini"
    private val temp = "$target.gc-tmp"

    private fun bytes(s: String) = s.toByteArray()

    // ---- success ----

    @Test
    fun `a write replaces the target with the new content`() {
        val fs = FakeFs().apply { seed(target, "old") }
        val result = AtomicWriter(fs).write(target, bytes("new"))
        assertEquals(AtomicWriteResult.Success, result)
        assertEquals("new", fs.textAt(target))
    }

    @Test
    fun `a successful write leaves no temp file behind`() {
        val fs = FakeFs().apply { seed(target, "old") }
        AtomicWriter(fs).write(target, bytes("new"))
        assertFalse("temp should be cleaned up", fs.exists(temp))
    }

    @Test
    fun `a write to a path that did not exist creates it`() {
        val fs = FakeFs()
        val result = AtomicWriter(fs).write(target, bytes("fresh"))
        assertEquals(AtomicWriteResult.Success, result)
        assertEquals("fresh", fs.textAt(target))
    }

    @Test
    fun `content is staged into a sibling temp then renamed onto the target`() {
        val fs = FakeFs().apply { seed(target, "old") }
        AtomicWriter(fs).write(target, bytes("new"))
        assertEquals(temp, fs.writes.first())          // the only write went to the sibling temp
        assertEquals(temp to target, fs.moves.single()) // then a single rename onto the target
    }

    @Test
    fun `a stale leftover temp is overwritten rather than blocking the write`() {
        val fs = FakeFs().apply { seed(target, "old"); seed(temp, "garbage from a crash") }
        val result = AtomicWriter(fs).write(target, bytes("new"))
        assertEquals(AtomicWriteResult.Success, result)
        assertEquals("new", fs.textAt(target))
    }

    @Test
    fun `binary content with embedded NUL round-trips through the writer`() {
        val fs = FakeFs()
        val payload = byteArrayOf(0x00, 0x01, 0x7F, 0x00, 0x42)
        val result = AtomicWriter(fs).write(target, payload)
        assertEquals(AtomicWriteResult.Success, result)
        assertArrayEquals(payload, fs.raw(target))
    }

    // ---- failure: original preserved ----

    @Test
    fun `when staging the temp fails the original is untouched`() {
        val fs = FakeFs().apply { seed(target, "old"); failWriteAt += temp }
        val result = AtomicWriter(fs).write(target, bytes("new"))
        assertEquals(AtomicWriteResult.Failed(AtomicWriteFailure.TEMP_WRITE_FAILED), result)
        assertEquals("old", fs.textAt(target))
    }

    @Test
    fun `when the atomic rename fails the original is untouched and no temp is left`() {
        val fs = FakeFs().apply { seed(target, "old"); failMove = true }
        val result = AtomicWriter(fs).write(target, bytes("new"))
        assertEquals(AtomicWriteResult.Failed(AtomicWriteFailure.MOVE_FAILED), result)
        assertEquals("old", fs.textAt(target))
        assertFalse(fs.exists(temp))
    }

    @Test
    fun `a corrupted read-back of the temp aborts before commit with the original intact`() {
        val fs = FakeFs().apply { seed(target, "old"); corruptReadAt += temp }
        val result = AtomicWriter(fs).write(target, bytes("new"))
        assertEquals(AtomicWriteResult.Failed(AtomicWriteFailure.READBACK_MISMATCH), result)
        assertEquals("old", fs.textAt(target))
    }

    @Test
    fun `a failed read-back of the temp aborts before commit with the original intact`() {
        val fs = FakeFs().apply { seed(target, "old"); failReadAt += temp }
        val result = AtomicWriter(fs).write(target, bytes("new"))
        assertEquals(AtomicWriteResult.Failed(AtomicWriteFailure.READBACK_FAILED), result)
        assertEquals("old", fs.textAt(target))
    }

    // ---- failure: after the commit ----

    @Test
    fun `the target is read back after the commit and a mismatch there is reported`() {
        // Corrupt only the target's reads: step 2 reads the temp (passes), and the post-rename read of the
        // target (step 4) returns wrong bytes — proving the final check reads the committed file, not the temp.
        val fs = FakeFs().apply { seed(target, "old"); corruptReadAt += target }
        val result = AtomicWriter(fs).write(target, bytes("new"))
        assertEquals(AtomicWriteResult.Failed(AtomicWriteFailure.READBACK_MISMATCH), result)
    }

    // ---- in-memory filesystem fake ----

    private class FakeFs : AtomicFs {
        private val files = HashMap<String, ByteArray>()
        val writes = mutableListOf<String>()
        val moves = mutableListOf<Pair<String, String>>()
        val failWriteAt = mutableSetOf<String>()
        val failReadAt = mutableSetOf<String>()
        val corruptReadAt = mutableSetOf<String>()
        var failMove = false

        fun seed(path: String, content: String) { files[path] = content.toByteArray() }
        fun textAt(path: String): String? = files[path]?.let { String(it) }
        fun raw(path: String): ByteArray? = files[path]

        override fun writeFile(path: String, bytes: ByteArray) {
            writes += path
            if (path in failWriteAt) throw IOException("write blocked: $path")
            files[path] = bytes.copyOf()
        }

        override fun readFile(path: String): ByteArray {
            if (path in failReadAt) throw IOException("read blocked: $path")
            val stored = files[path] ?: throw IOException("no such file: $path")
            return if (path in corruptReadAt) "CORRUPT".toByteArray() else stored.copyOf()
        }

        override fun move(from: String, to: String) {
            if (failMove) throw IOException("move blocked")
            val stored = files[from] ?: throw IOException("no such file: $from")
            files[to] = stored
            files.remove(from)
            moves += from to to
        }

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun delete(path: String) { files.remove(path) }
    }
}
