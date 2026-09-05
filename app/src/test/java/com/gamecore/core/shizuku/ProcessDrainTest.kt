package com.gamecore.core.shizuku

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch

/**
 * That an elevated command always ends.
 *
 * This is the whole of the bug reported as "the screen resolution section just keeps loading until u
 * quit the app and open again". Draining a process's pipes to the end is the only safe order — a
 * `waitFor` on a process nobody has read from deadlocks against a full pipe buffer — but a read on a
 * pipe whose writer is alive and silent blocks until that writer closes it, and neither a timeout
 * checked afterwards nor coroutine cancellation can interrupt a native read. One such command held
 * the shell's execution lock, and every elevated reading in the app queued behind it until GameCore
 * was force-stopped.
 *
 * So the fake process here is the one that caused it: a process that says nothing, closes nothing,
 * and ignores `destroy` — a wedged binder, which is exactly the case where the tidy way out is not
 * available. The drain has to end anyway, and the caller has to get an answer, because the answer is
 * what releases the lock.
 *
 * The timeouts on the tests are deliberate. A regression here is a hang rather than a wrong value, and
 * a hang that fails is worth a great deal more than one that has to be noticed.
 */
class ProcessDrainTest {

    @Test
    fun `a process that answers is drained whole`() = runBlocking {
        val process = FakeProcess(talking("1080x2400"), talking(""))
        assertEquals(Drained("1080x2400", ""), process.drainWithin(DRAIN_BUDGET, Dispatchers.IO))
    }

    /** Both pipes, not one each: the budget is for the command, and it has two of them to read. */
    @Test
    fun `stderr is drained as well as stdout`() = runBlocking {
        val process = FakeProcess(talking("out"), talking("wm: no display"))
        assertEquals(
            Drained("out", "wm: no display"),
            process.drainWithin(DRAIN_BUDGET, Dispatchers.IO),
        )
    }

    @Test(timeout = TEST_TIMEOUT)
    fun `a process that stops talking without closing its pipes is abandoned`() = runBlocking {
        val process = FakeProcess(SilentStream(), SilentStream())
        assertNull(process.drainWithin(DRAIN_BUDGET, Dispatchers.IO))
    }

    /**
     * The mechanism, asserted directly rather than through its effect.
     *
     * Closing the descriptor is what lets go of the thread blocked on it — Android signals blocked
     * threads on close — and it is the half that has to work on its own, because this fake's `destroy`
     * does nothing, which is what a binder that has stopped answering amounts to.
     */
    @Test(timeout = TEST_TIMEOUT)
    fun `abandoning a drain closes the descriptors the read was blocked on`() = runBlocking {
        val out = SilentStream()
        val err = SilentStream()
        val process = FakeProcess(out, err)
        process.drainWithin(DRAIN_BUDGET, Dispatchers.IO)
        assertTrue(out.wasClosed)
        assertTrue(err.wasClosed)
        assertTrue(process.destroyCount > 0)
    }

    /** One silent pipe is enough, and it is the second one here — the budget covers the pair. */
    @Test(timeout = TEST_TIMEOUT)
    fun `a process that answers on stdout and hangs on stderr is abandoned`() = runBlocking {
        val process = FakeProcess(talking("1080x2400"), SilentStream())
        assertNull(process.drainWithin(DRAIN_BUDGET, Dispatchers.IO))
    }

    /**
     * The watchdog is cancelled by a drain that ended on its own, so the ordinary command — every
     * command, on every device where this works — is not killed a fraction of a second after it
     * answered.
     */
    @Test
    fun `a drain that ends on its own does not destroy the process`() = runBlocking {
        val process = FakeProcess(talking("1080x2400"), talking(""))
        process.drainWithin(DRAIN_BUDGET, Dispatchers.IO)
        Thread.sleep(DRAIN_BUDGET * 2)
        assertEquals(0, process.destroyCount)
    }

    // ----------------------------------------------------------------------------- the fakes

    /** A pipe that says its piece and reaches the end, like every command that works. */
    private fun talking(text: String): InputStream = ByteArrayInputStream(text.toByteArray())

    /** A pipe with a live writer that has nothing to say and will not hang up. */
    private class SilentStream : InputStream() {
        private val closed = CountDownLatch(1)

        val wasClosed: Boolean get() = closed.count == 0L

        override fun read(): Int = blockUntilClosed()

        override fun read(b: ByteArray, off: Int, len: Int): Int = blockUntilClosed()

        override fun close() = closed.countDown()

        /** What a real descriptor does to a pending read when it is closed from another thread. */
        private fun blockUntilClosed(): Nothing {
            closed.await()
            throw IOException("Stream closed")
        }
    }

    /**
     * A [Process] with the binder taken out. `destroy` counts the attempt and does nothing else,
     * which is deliberately the unhelpful case: a fake that closed its own pipes on destroy would let
     * the drain end for a reason this fix is not allowed to depend on.
     */
    private class FakeProcess(
        private val out: InputStream,
        private val err: InputStream,
    ) : Process() {

        var destroyCount = 0
            private set

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = out

        override fun getErrorStream(): InputStream = err

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy() {
            destroyCount++
        }
    }

    private companion object {
        /** Short, because every one of these tests spends it for real. */
        const val DRAIN_BUDGET = 150L

        /** A regression here is a hang, so it is failed rather than waited out. */
        const val TEST_TIMEOUT = 10_000L
    }
}
