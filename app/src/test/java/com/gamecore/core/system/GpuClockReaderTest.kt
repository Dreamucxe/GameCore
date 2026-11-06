package com.gamecore.core.system

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.common.Precision
import com.gamecore.core.common.RestrictionReason
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
 * [GpuClockReader]'s probing and honesty, exercised against real fixture directories under a
 * [TemporaryFolder] used as its `/sys` root, with a fake [ElevatedShell]. The point is the tiering
 * the reader must get right on a JVM without a device: a devfreq `cur_freq` in hertz normalises to
 * megahertz exactly; the fixed Adreno devfreq path answers before the wildcard enumeration; CPU
 * devfreq devices are never mistaken for the GPU; an implausible figure is refused rather than
 * shown as "0 MHz"; a present-but-unreadable devfreq device needs Shizuku — or reads through it
 * when available — and a device with no GPU devfreq device at all reports not-present. No case
 * ever fabricates a figure.
 */
class GpuClockReaderTest {

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

    private fun reader(shell: FakeClockShell = FakeClockShell()) =
        GpuClockReader(UnconfinedTestDispatcher(), shell, tempFolder.root)

    @Test
    fun `a devfreq cur_freq in hertz reads as megahertz with ceiling and table`() = runTest {
        writeNode("class/devfreq/7c450000.gpu/cur_freq", "520000000")
        writeNode("class/devfreq/7c450000.gpu/max_freq", "1100000000")
        writeNode(
            "class/devfreq/7c450000.gpu/available_frequencies",
            "260000000 520000000 780000000 1100000000",
        )
        val reader = reader()

        val result = reader.read()

        assertTrue("was $result", result is Observed.Value)
        result as Observed.Value
        assertEquals(520f, result.value.currentMhz, 0f)
        assertEquals(1100f, result.value.maxMhz!!, 0f)
        assertEquals(listOf(260f, 520f, 780f, 1100f), result.value.availableMhz)
        assertEquals("/sys/class/devfreq/7c450000.gpu", result.value.nodeLabel)
        assertEquals(DataSource.SYS_FS, result.source)
        assertEquals(Precision.EXACT, result.precision)
    }

    @Test
    fun `a node reporting megahertz directly is not divided again`() = runTest {
        writeNode("class/devfreq/gpufreq/cur_freq", "720")
        val reader = reader()

        val result = reader.read()

        assertTrue("was $result", result is Observed.Value)
        result as Observed.Value
        assertEquals(720f, result.value.currentMhz, 0f)
        assertEquals(null, result.value.maxMhz)
        assertTrue(result.value.availableMhz.isEmpty())
    }

    @Test
    fun `the fixed adreno devfreq path answers before the wildcard enumeration`() = runTest {
        writeNode("class/kgsl/kgsl-3d0/devfreq/cur_freq", "640000000")
        writeNode("class/devfreq/13000000.mali/cur_freq", "800000000")
        val reader = reader()

        val result = reader.read()

        assertTrue("was $result", result is Observed.Value)
        result as Observed.Value
        assertEquals(640f, result.value.currentMhz, 0f)
        assertEquals("/sys/class/kgsl/kgsl-3d0/devfreq", result.value.nodeLabel)
    }

    @Test
    fun `cpu devfreq devices are never mistaken for the gpu`() = runTest {
        writeNode("class/devfreq/5a100000.cpu/cur_freq", "2800000000")
        writeNode("class/devfreq/5a100000.mem/cur_freq", "4096000")
        val reader = reader()

        val result = reader.read()

        assertTrue("was $result", result is Observed.Restricted)
        assertEquals(
            RestrictionReason.NOT_PRESENT_ON_DEVICE,
            (result as Observed.Restricted).reason,
        )
    }

    @Test
    fun `a zero reported cur_freq is never shown as zero megahertz`() = runTest {
        // A driver that answered with nothing reports 0. It must read as unreadable, not as a
        // GPU that happens to be stopped — an idle GPU still has a real floor clock.
        writeNode("class/devfreq/7c450000.gpu/cur_freq", "0")
        val reader = reader(FakeClockShell(available = false))

        val result = reader.read()

        assertFalse("a garbage 0 must never be shown, was $result", result is Observed.Value)
        assertTrue("was $result", result is Observed.Restricted)
    }

    @Test
    fun `a gpubusy counter node is not a clock node and is ignored`() = runTest {
        // Adreno's gpubusy pair is a load counter, not a frequency. Only the devfreq shape is probed,
        // so a device exposing counters but no devfreq clock reports not-present.
        writeNode("class/kgsl/kgsl-3d0/gpubusy", "100 200")
        val reader = reader()

        val result = reader.read()

        assertTrue("was $result", result is Observed.Restricted)
        assertEquals(
            RestrictionReason.NOT_PRESENT_ON_DEVICE,
            (result as Observed.Restricted).reason,
        )
    }

    @Test
    fun `a present devfreq device with no readable value and no shell needs elevation`() = runTest {
        makeDir("class/devfreq/7c450000.gpu")
        val reader = reader(FakeClockShell(available = false))

        val result = reader.read()

        assertTrue("was $result", result is Observed.Restricted)
        assertEquals(
            RestrictionReason.REQUIRES_ELEVATED_ACCESS,
            (result as Observed.Restricted).reason,
        )
    }

    @Test
    fun `a present device read through an available shell reports a shizuku clock`() = runTest {
        makeDir("class/devfreq/7c450000.gpu")
        val shell = FakeClockShell(
            available = true,
            response = ShellResult(exitCode = 0, stdout = "840000000", stderr = "", accessLevel = AccessLevel.SHIZUKU),
        )
        val reader = reader(shell)

        val result = reader.read()

        assertTrue("was $result", result is Observed.Value)
        result as Observed.Value
        assertEquals(840f, result.value.currentMhz, 0f)
        assertEquals(null, result.value.maxMhz)
        assertTrue(result.value.availableMhz.isEmpty())
        assertEquals(DataSource.SHELL_SHIZUKU, result.source)
        // The read went through the path-validated GPU command, never a raw `cat`.
        assertTrue(
            "expected a ReadGpuClockNode, ran ${shell.executed}",
            shell.executed.any { it is ShellCommand.ReadGpuClockNode },
        )
    }

    @Test
    fun `a device with no gpu clock node at all reports not present`() = runTest {
        val reader = reader()

        val result = reader.read()

        assertTrue("was $result", result is Observed.Restricted)
        assertEquals(
            RestrictionReason.NOT_PRESENT_ON_DEVICE,
            (result as Observed.Restricted).reason,
        )
    }
}

/**
 * A fake [ElevatedShell] that hands back a canned [ShellResult] for any command and records what it
 * ran, mirroring [GpuReaderTest]'s own fake (renamed — the load reader's file-private fake shares this package): it drives the restricted-but-present tier both ways —
 * no shell, and a shell that answers — without a device.
 */
private class FakeClockShell(
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
