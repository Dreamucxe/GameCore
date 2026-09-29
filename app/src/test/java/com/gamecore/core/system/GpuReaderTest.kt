package com.gamecore.core.system

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.common.Precision
import com.gamecore.core.common.RestrictionReason
import com.gamecore.core.common.isAwaitingSample
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import com.gamecore.core.shizuku.ShellResult
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [GpuReader]'s probing and honesty, exercised against real fixture directories under a
 * [TemporaryFolder] used as its `/sys` root, with a fake [ElevatedShell]. The point is the tiering the
 * reader must get right on a JVM without a device: an instantaneous percentage node reads exactly; the
 * Adreno `gpubusy` pair awaits its second sample and then reports the delta ratio; a value outside
 * 0..100 is rejected rather than shown; a node whose family is present but whose value is unreadable is
 * restricted — needing Shizuku, or read through it when available — and a device with no GPU node at
 * all reports "not present". No case ever fabricates a `0f`.
 */
class GpuReaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sysFile(subpath: String): File = File(tempFolder.root, subpath)

    /** Creates a value node with [content], parent directories and all. */
    private fun writeNode(subpath: String, content: String) {
        val file = sysFile(subpath)
        file.parentFile!!.mkdirs()
        file.writeText(content)
    }

    /** Creates a directory (a node's parent) without any value file inside it. */
    private fun makeDir(subpath: String) {
        sysFile(subpath).mkdirs()
    }

    @Test
    fun `an instantaneous percentage node reads as an exact value`() = runTest {
        writeNode("class/kgsl/kgsl-3d0/gpu_busy_percentage", "37")
        val reader = GpuReader(UnconfinedTestDispatcher(testScheduler), FakeShell(), tempFolder.root)

        val result = reader.read()

        assertTrue("was $result", result is Observed.Value)
        result as Observed.Value
        // The driver already computed the figure, so it is read verbatim, not sampled.
        assertEquals(37f, result.value.loadPercent, 0f)
        assertEquals(DataSource.SYS_FS, result.source)
        assertEquals(Precision.EXACT, result.precision)
    }

    @Test
    fun `the gpubusy pair awaits a second sample then reports the delta ratio`() = runTest {
        // Only the counter pair exists; the percentage node above it in the same directory is absent,
        // so gpubusy is the node the reader settles on.
        val busy = sysFile("class/kgsl/kgsl-3d0/gpubusy")
        busy.parentFile!!.mkdirs()
        busy.writeText("100 200")
        val reader = GpuReader(UnconfinedTestDispatcher(testScheduler), FakeShell(), tempFolder.root)

        val first = reader.read()
        // A single read of cumulative counters means nothing yet — it is honestly an awaited sample.
        assertTrue("first read should await a second sample, was $first", first.isAwaitingSample)

        busy.writeText("150 300") // +50 busy over +100 total = 50%
        val second = reader.read()

        assertTrue("was $second", second is Observed.Value)
        second as Observed.Value
        assertEquals(50f, second.value.loadPercent, 0f)
        assertEquals(DataSource.SYS_FS, second.source)
        assertEquals(Precision.SAMPLED, second.precision)
    }

    @Test
    fun `an out-of-range percentage is never surfaced as a value`() = runTest {
        // 150 is not a real percentage. The node is present but its value is unusable, and with no
        // elevated shell the honest answer is that reading it needs one — not a fabricated figure.
        writeNode("class/kgsl/kgsl-3d0/gpu_busy_percentage", "150")
        val reader =
            GpuReader(UnconfinedTestDispatcher(testScheduler), FakeShell(available = false), tempFolder.root)

        val result = reader.read()

        assertFalse("a garbage 150 must never be shown, was $result", result is Observed.Value)
        assertTrue("was $result", result is Observed.Restricted)
        assertEquals(
            RestrictionReason.REQUIRES_ELEVATED_ACCESS,
            (result as Observed.Restricted).reason,
        )
    }

    @Test
    fun `a present node with no readable value and no shell needs elevation`() = runTest {
        // The node family's directory exists but holds no value file, and no elevated shell is available.
        makeDir("class/kgsl/kgsl-3d0")
        val reader =
            GpuReader(UnconfinedTestDispatcher(testScheduler), FakeShell(available = false), tempFolder.root)

        val result = reader.read()

        assertTrue("was $result", result is Observed.Restricted)
        assertEquals(
            RestrictionReason.REQUIRES_ELEVATED_ACCESS,
            (result as Observed.Restricted).reason,
        )
    }

    @Test
    fun `a present node read through an available shell reports a shizuku value`() = runTest {
        // The node family is present but has no directly readable value, and this time an elevated shell
        // is available and answers with a plausible percentage.
        makeDir("class/kgsl/kgsl-3d0")
        val shell = FakeShell(
            available = true,
            response = ShellResult(exitCode = 0, stdout = "63", stderr = "", accessLevel = AccessLevel.SHIZUKU),
        )
        val reader = GpuReader(UnconfinedTestDispatcher(testScheduler), shell, tempFolder.root)

        val result = reader.read()

        assertTrue("was $result", result is Observed.Value)
        result as Observed.Value
        assertEquals(63f, result.value.loadPercent, 0f)
        assertEquals(DataSource.SHELL_SHIZUKU, result.source)
        // The read went through the path-validated GPU command, never a raw `cat`.
        assertTrue(
            "expected a ReadGpuBusyNode, ran ${shell.executed}",
            shell.executed.any { it is ShellCommand.ReadGpuBusyNode },
        )
    }

    @Test
    fun `a device with no gpu node at all reports not present`() = runTest {
        // The `/sys` root exists but holds none of the probed GPU node directories.
        val reader = GpuReader(UnconfinedTestDispatcher(testScheduler), FakeShell(), tempFolder.root)

        val result = reader.read()

        assertTrue("was $result", result is Observed.Restricted)
        assertEquals(
            RestrictionReason.NOT_PRESENT_ON_DEVICE,
            (result as Observed.Restricted).reason,
        )
    }
}

/**
 * A fake [ElevatedShell] that hands back a canned [ShellResult] for any command and records what it ran,
 * so a test can drive the restricted-but-present tier both ways — no shell, and a shell that answers —
 * without a device. It mirrors the shape of the config lister's own fake: the three interface members
 * plus an `executed` log, with no file plumbing this reader does not need.
 */
private class FakeShell(
    var available: Boolean = true,
    var response: ShellResult = ShellResult(exitCode = 0, stdout = "", stderr = "", accessLevel = AccessLevel.SHIZUKU),
) : ElevatedShell {

    val executed = mutableListOf<ShellCommand>()

    override val accessLevel = AccessLevel.SHIZUKU

    override suspend fun isAvailable(): Boolean = available

    override suspend fun execute(command: ShellCommand, timeoutMillis: Long): ShellResult {
        executed += command
        return response
    }
}
