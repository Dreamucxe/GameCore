package com.gamecore.core.system

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.common.valueOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `taskset -ap`, parsed as the value that goes into the restore ledger.
 *
 * The stakes here are not a wrong number on a screen. This parse is what GameCore records as the mask a
 * game's process was found on, and the recorded mask is written back to that process when the game exits
 * — so a fabricated reading is not a display bug, it is GameCore putting a game on cores it was never on
 * and reporting that it restored them.
 *
 * Which is why every test below that feeds this something odd asserts [Observed.Failed] rather than a
 * lenient reading. The one property worth more than the others: output with no matching line is a failure
 * even when the command exited zero, because `taskset` prints nothing for a pid that has gone and an
 * empty parse read as "not restricted" would be the app inventing the most reassuring answer available.
 */
class CpuAffinityParserTest {

    private fun parse(text: String) = CpuAffinityParser.parse(text)

    @Test
    fun `one line per thread reads as one mask per thread`() {
        val output = """
            pid 4021's current affinity mask: ff
            pid 4088's current affinity mask: ff
            pid 4091's current affinity mask: f0
        """.trimIndent()
        val state = requireNotNull(parse(output).valueOrNull)
        assertEquals(3, state.threadCount)
        assertEquals(mapOf(4021 to 0xff, 4088 to 0xff, 4091 to 0xf0), state.byThread)
        assertEquals(setOf(0xff, 0xf0), state.masks)
        // The threads disagree, so there is no single value — which is the case this type exists for.
        assertNull(state.uniformMask)
        assertEquals(0xff, state.mainThreadMask(4021))
    }

    @Test
    fun `the reading names the shell it came from`() {
        val observed = parse("pid 4021's current affinity mask: ff")
        assertEquals(DataSource.SHELL_SHIZUKU, (observed as Observed.Value).source)
    }

    @Test
    fun `a process whose threads all share a mask is matched all-or-nothing`() {
        val output = """
            pid 4021's current affinity mask: f0
            pid 4022's current affinity mask: f0
        """.trimIndent()
        val state = requireNotNull(parse(output).valueOrNull)
        assertEquals(0xf0, state.uniformMask)
        assertTrue(state.matches(0xf0))
        assertFalse(state.matches(0xff))
    }

    @Test
    fun `the word between the pid and the mask carries no meaning and is not matched for`() {
        // `current` becomes `new` on the lines printed after a write, and a release is free to choose a
        // third adjective. Pinning the parse to one of them would break it on the next.
        val written = """
            pid 4021's new affinity mask: f0
            pid 4022's previous affinity mask: ff
        """.trimIndent()
        val state = requireNotNull(parse(written).valueOrNull)
        assertEquals(mapOf(4021 to 0xf0, 4022 to 0xff), state.byThread)
    }

    @Test
    fun `an 0x prefix, capital hex and extra spacing are all the same mask`() {
        val output = """
            pid 4021's current affinity mask:  0xFF
            pid 4022's current affinity mask: 0xff
            pid 4023's current  affinity   mask: FF
        """.trimIndent()
        val state = requireNotNull(parse(output).valueOrNull)
        assertEquals(setOf(0xff), state.masks)
        assertEquals(3, state.threadCount)
    }

    @Test
    fun `a wide mask reads as the cores it names`() {
        // A 32-core mask has the top bit set, which is the case a signed parse gets wrong.
        val state = requireNotNull(parse("pid 4021's current affinity mask: ffffffff").valueOrNull)
        assertEquals(-1, state.uniformMask)
        assertTrue(state.matches(-1))
    }

    @Test
    fun `output with no mask line is a failure even though the command succeeded`() {
        // `taskset` prints nothing at all for a pid that has gone, and exits zero doing it.
        assertTrue(parse("") is Observed.Failed)
        assertTrue(parse("\n\n") is Observed.Failed)
        assertTrue(parse("taskset: failed to get pid 4021's affinity") is Observed.Failed)
        val failure = parse("") as Observed.Failed
        assertTrue(failure.detail.contains("taskset"))
    }

    @Test
    fun `an affinity list is not a mask and is refused`() {
        // What `taskset -c` prints. No command in this app passes `-c`, so a list arriving here means
        // the output is not from the command that was run — and `0-3,7` read as a mask would be 0.
        assertTrue(parse("pid 4021's current affinity list: 0-3,7") is Observed.Failed)
    }

    @Test
    fun `a mask of zero is refused rather than read as no restriction`() {
        // A process allowed to run on no core does not run, so a zero is a parse that went wrong.
        assertTrue(parse("pid 4021's current affinity mask: 0") is Observed.Failed)
        assertTrue(parse("pid 4021's current affinity mask: 0x0") is Observed.Failed)
        // And one bad line among good ones drops that line rather than the reading.
        val mixed = """
            pid 4021's current affinity mask: 0
            pid 4022's current affinity mask: ff
        """.trimIndent()
        val state = requireNotNull(parse(mixed).valueOrNull)
        assertEquals(mapOf(4022 to 0xff), state.byThread)
    }

    @Test
    fun `a mask too wide to be one is dropped, not truncated`() {
        // Nine hex digits is not a longer mask. Truncating it would produce a plausible value for a
        // line that has already proved it is not the output this parser reads.
        assertTrue(parse("pid 4021's current affinity mask: 1ffffffff") is Observed.Failed)
    }

    @Test
    fun `a thread id of zero is not a thread`() {
        assertTrue(parse("pid 0's current affinity mask: ff") is Observed.Failed)
    }

    @Test
    fun `a repeated thread keeps the last line printed for it`() {
        // `taskset -ap <mask> <pid>` prints the old mask and then the new one for each thread. The last
        // line is the state after the write, which is what a verification is asking about.
        val output = """
            pid 4021's current affinity mask: ff
            pid 4021's new affinity mask: f0
        """.trimIndent()
        val state = requireNotNull(parse(output).valueOrNull)
        assertEquals(1, state.threadCount)
        assertEquals(0xf0, state.uniformMask)
    }

    @Test
    fun `unrelated output around the lines does not stop them being read`() {
        val noisy = """
            WARNING: linker: unused DT entry
            pid 4021's current affinity mask: ff
            sched_setaffinity: Operation not permitted
        """.trimIndent()
        val state = requireNotNull(parse(noisy).valueOrNull)
        assertEquals(mapOf(4021 to 0xff), state.byThread)
    }
}
