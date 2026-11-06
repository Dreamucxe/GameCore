package com.gamecore.core.system

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.common.Precision
import com.gamecore.core.model.GpuClockSample
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads how fast the GPU is running, when the kernel exposes it. It is the clock frequency —
 * the megahertz number tools like DevCheck show — and is not named by the GPU model string the
 * platform never sends (see `SmartDeviceScanner` for why that one stays unknown). This reader
 * answers it from the same truck every driver engineer and every hardware-forum tool uses:
 * `cur_freq` from the Linux **devfreq** subsystem, the generic kernel interface a GPU dynamic
 * voltage-and-frequency-scaling (DVFS) driver registers itself with.
 *
 * There is no platform API for a GPU clock, so — as with [GpuReader] for load — the only source
 * is a vendor sysfs node. Devfreq is the one shape modern kernels converge on:
 *
 *  * A Mali, Exynos, Samsung or PowerVR GPU appears as a device under `/sys/class/devfreq/` named
 *    after its memory-mapped address with the GPU's family in the name (`13000000.mali`,
 *    `14611000.gpufreq`, `17000000.gpu`). This reader enumerates that directory and keeps only the
 *    entries whose names say they are a GPU, so a CPU devfreq device that happens to sit next to
 *    it is never mistaken for the graphics processor.
 *  * An Adreno GPU exposes its devfreq device one level down, at
 *    `/sys/class/kgsl/kgsl-3d0/devfreq/`. That path is fixed on every Snapdragon kernel, so it is
 *    always probed, first, before the wildcard enumeration.
 *
 * Neither path is guessed from a chipset table. Both are discovered against the live filesystem
 * and skipped when the node is not there, the same way [GpuReader]'s Mali platform entry is
 * resolved rather than assumed. An Adreno `gpu_clock` node is deliberately **not** used: it holds
 * cumulative counters, not a frequency, and presenting its raw ticks as megahertz would be the
 * fabricated-figure behaviour this codebase exists to avoid.
 *
 * **Units.** Devfreq reports its frequencies in **hertz** (`cur_freq`, `max_freq`,
 * `available_frequencies` — e.g. `520000000` for 520 MHz). A handful of vendor nodes report
 * megahertz directly. Both are normalised to megahertz: a raw value at or above 10,000 is treated
 * as a hertz figure (every real GPU clock, in hertz, is at least 10^7, and no node reports a
 * megahertz clock of 10,000 or more), and anything below that is accepted only if it is already a
 * plausible megahertz figure. The plausible band is 10 MHz at the bottom — no shipping GPU idles
 * below about 25 MHz, and a reported 0 is the classic "driver answered with nothing" — to 6000
 * MHz at the top. A value outside the band is rejected as unreadable rather than shown: a screen
 * must never render "0 MHz" because the driver returned an empty string.
 *
 * **Restriction.** As with [GpuReader], tiers are decided structurally: a value file whose parent
 * device directory exists but cannot be read is present-but-restricted — readable through an
 * elevated (Shizuku) shell, or reported as needing one. Devfreq attributes are usually
 * world-readable, so the direct read is the common case and Shizuku is the fallback; a device
 * with no GPU devfreq device at all reports not-present.
 *
 * The injectable [sysRoot] makes the probing testable on the JVM against fixture directories,
 * exactly as [GpuReader] does for `/sys`.
 */
@Singleton
class GpuClockReader(
    private val io: CoroutineDispatcher,
    private val elevatedShell: ElevatedShell,
    private val sysRoot: File,
) {

    /**
     * The production wiring. The `File` root is not a Hilt binding, so — as in [GpuReader] — the
     * injected constructor supplies the real `/sys` and delegates to the primary one the tests use.
     */
    @Inject
    constructor(
        @IoDispatcher io: CoroutineDispatcher,
        elevatedShell: ElevatedShell,
    ) : this(io, elevatedShell, File("/sys"))

    /** The node settled on once one answered plausibly, so later ticks read it directly. */
    private var chosen: Candidate? = null

    /**
     * The current GPU clock, its ceiling and its frequency table, or the reason it is absent.
     *
     * Once a node has been chosen, only that node is re-read; until then every candidate is probed
     * in order and the first plausible one wins. A restricted-but-present node is read through the
     * elevated shell when one is available, and otherwise reported as needing elevation.
     */
    suspend fun read(): Observed<GpuClockSample> = withContext(io) {
        chosen?.let { return@withContext resolve(it) }

        var restricted: Candidate? = null
        for (candidate in candidates()) {
            when (val probe = probeDirect(candidate)) {
                is Probe.Ok -> {
                    chosen = candidate
                    return@withContext Observed.of(probe.sample, DataSource.SYS_FS, Precision.EXACT)
                }
                // Remember the first present-but-unreadable node, but keep probing: a later candidate
                // that reads directly is a better answer than falling back to the shell for this one.
                Probe.Restricted -> if (restricted == null) restricted = candidate
                Probe.Absent -> Unit
            }
        }

        restricted?.let {
            chosen = it
            return@withContext resolveRestricted(it)
        }
        Observed.notPresent(NOT_PRESENT_REASON)
    }

    // ------------------------------------------------------------------ per-node read

    /** Reads one already-chosen node: a direct value, or the restricted fallback. */
    private suspend fun resolve(candidate: Candidate): Observed<GpuClockSample> =
        when (val probe = probeDirect(candidate)) {
            is Probe.Ok -> Observed.of(probe.sample, DataSource.SYS_FS, Precision.EXACT)
            Probe.Restricted -> resolveRestricted(candidate)
            Probe.Absent -> Observed.notPresent(NOT_PRESENT_REASON)
        }

    /**
     * Attempts a direct sysfs read, deciding the tier without leaning on the shape of an `EACCES`.
     *
     * A `cur_freq` that reads and parses into a plausible megahertz figure is [Probe.Ok], enriched
     * with the `max_freq` ceiling and the `available_frequencies` table when those siblings are
     * readable. Anything else — a missing file, an empty or garbage value, an out-of-band figure,
     * a read that threw — falls to [restrictedOrAbsent], which distinguishes "the devfreq device is
     * present but this is not readable" from "no GPU devfreq device at all" purely by whether the
     * device's parent directory exists.
     */
    private fun probeDirect(candidate: Candidate): Probe {
        val text = readFile(candidate.curFreqFile)
            ?: return restrictedOrAbsent(candidate)

        val currentMhz = parseClockMhz(text) ?: return restrictedOrAbsent(candidate)
        val maxMhz = readFile(candidate.maxFreqFile)?.let { parseClockMhz(it) }
        val availableMhz = readFile(candidate.availableFreqFile)?.let { parseClockListMhz(it) }
            ?: emptyList()
        return Probe.Ok(
            GpuClockSample(
                currentMhz = currentMhz,
                maxMhz = maxMhz,
                availableMhz = availableMhz,
                nodeLabel = candidate.nodeLabel,
            ),
        )
    }

    /** Present-but-unreadable (parent directory exists) is [Probe.Restricted]; otherwise [Probe.Absent]. */
    private fun restrictedOrAbsent(candidate: Candidate): Probe {
        val present = try {
            candidate.deviceDirectory.isDirectory
        } catch (error: Throwable) {
            false
        }
        return if (present) Probe.Restricted else Probe.Absent
    }

    // --------------------------------------------------------------------- parsing

    /**
     * The first token of [text] as megahertz, or null if it is not a plausible GPU clock.
     *
     * Devfreq reports hertz, a few vendor nodes report megahertz; both are normalised here (see the
     * class comment). A raw value in the hertz range that normalises below the plausible floor is
     * rejected, as is any raw value below it — a refused value is unreadable, never a "0 MHz".
     */
    internal fun parseClockMhz(text: String): Float? {
        val token = text.split(WHITESPACE).firstOrNull() ?: return null
        val raw = token.toLongOrNull() ?: return null
        val mhz = if (raw >= HERTZ_THRESHOLD) raw / 1_000_000f else raw.toFloat()
        return if (mhz in MIN_PLAUSIBLE_MHZ..MAX_PLAUSIBLE_MHZ) mhz else null
    }

    /** Every token of [text] as megahertz, deduplicated and sorted ascending; garbage tokens dropped. */
    internal fun parseClockListMhz(text: String): List<Float> =
        text.split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .mapNotNull { token ->
                token.toLongOrNull()?.let { raw ->
                    val mhz = if (raw >= HERTZ_THRESHOLD) raw / 1_000_000f else raw.toFloat()
                    if (mhz in MIN_PLAUSIBLE_MHZ..MAX_PLAUSIBLE_MHZ) mhz else null
                }
            }
            .distinct()
            .sorted()

    private fun readFile(file: File): String? = try {
        if (!file.exists()) null else file.readText().trim().ifEmpty { null }
    } catch (error: SecurityException) {
        null
    } catch (error: Throwable) {
        null
    }

    // -------------------------------------------------------------- elevated fallback

    /**
     * Re-reads a present-but-restricted node through the elevated shell, or reports it needs one.
     *
     * The shell always addresses the canonical `/sys` path rather than the injected [sysRoot] one:
     * on a device [sysRoot] *is* `/sys`, and the elevated shell reads the real filesystem
     * regardless of the root this reader was given for testing. The ceiling and the frequency
     * table are not also fetched through the shell — the read answers the current clock, and a
     * fallback that invented the rest of the table would be the fabrication this code avoids — so a
     * shell-read sample carries the current clock and nothing more.
     */
    private suspend fun resolveRestricted(candidate: Candidate): Observed<GpuClockSample> {
        if (!elevatedShell.isAvailable()) {
            return Observed.needsElevation(NEEDS_ELEVATION_REASON)
        }
        val command = ShellCommand.ReadGpuClockNode.of(candidate.canonicalCurFreqPath)
            ?: return Observed.needsElevation(NEEDS_ELEVATION_REASON)

        val result = elevatedShell.execute(command)
        val output = result.singleValue()
            ?: return Observed.Failed(
                "The GPU clock node could not be read through the elevated shell.",
                result.failureReason().ifBlank { null },
            )
        val currentMhz = parseClockMhz(output)
            ?: return Observed.Failed("The elevated shell returned an unreadable GPU clock value.")
        return Observed.of(
            GpuClockSample(
                currentMhz = currentMhz,
                maxMhz = null,
                availableMhz = emptyList(),
                nodeLabel = candidate.nodeLabel,
            ),
            DataSource.SHELL_SHIZUKU,
            Precision.EXACT,
        )
    }

    // ------------------------------------------------------------------- candidates

    /**
     * The GPU devfreq devices real kernels expose, in the order the most direct answer comes first.
     *
     * Adreno's fixed devfreq path leads because it exists or not under exactly that name. The
     * wildcard devices under `class/devfreq` follow, resolved against the filesystem rather than
     * guessed at — their names carry the SoC's memory map, so no list of them could be complete.
     * Only names that mark a GPU are kept; everything else under that directory is a CPU, memory
     * or display devfreq device, and a name table would spend forever mis-parsing it.
     */
    private fun candidates(): List<Candidate> {
        val out = ArrayList<Candidate>(4)
        out += candidate("class/kgsl/kgsl-3d0/devfreq")
        for (device in devfreqGpuDevices()) {
            out += candidate("class/devfreq/${device.name}")
        }
        return out
    }

    private fun candidate(devfreqSubpath: String): Candidate = Candidate(
        deviceDirectory = File(sysRoot, devfreqSubpath),
        nodeLabel = "/sys/$devfreqSubpath",
    )

    /** The `class/devfreq` devices whose names mark them as GPUs, sorted for a stable probe order. */
    private fun devfreqGpuDevices(): List<File> {
        val dir = File(sysRoot, "class/devfreq")
        val children = try {
            dir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        } catch (error: Throwable) {
            return emptyList()
        }
        return children
            .filter { GPU_DEVICE_NAME.containsMatchIn(it.name) }
            .sortedBy { it.name }
    }

    // ------------------------------------------------------------------------ types

    /** A GPU devfreq device to probe: where its directory is, and the label screens report. */
    private class Candidate(
        val deviceDirectory: File,
        val nodeLabel: String,
    ) {
        val curFreqFile: File get() = File(deviceDirectory, "cur_freq")
        val maxFreqFile: File get() = File(deviceDirectory, "max_freq")
        val availableFreqFile: File get() = File(deviceDirectory, "available_frequencies")
        val canonicalCurFreqPath: String get() = "$nodeLabel/cur_freq"
    }

    /** The outcome of a direct sysfs probe of one candidate. */
    private sealed interface Probe {
        data class Ok(val sample: GpuClockSample) : Probe
        data object Restricted : Probe
        data object Absent : Probe
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")

        /** Matches the family names in real devfreq device names: `13000000.mali`, `14611000.gpufreq`,
         * `17000000.gpu`, `7c4...gpu`, `kgsl-3d0`, a PowerVR `gpufreq`. CPU and memory devices never match. */
        val GPU_DEVICE_NAME = Regex("gpu|mali|kgsl|powervr|v3d", RegexOption.IGNORE_CASE)

        /** A raw value at or above this is hertz; below it, a node reporting megahertz directly. */
        const val HERTZ_THRESHOLD = 10_000L

        /** The plausible band for a GPU clock, in megahertz. See the class comment. */
        const val MIN_PLAUSIBLE_MHZ = 10f
        const val MAX_PLAUSIBLE_MHZ = 6000f

        const val NEEDS_ELEVATION_REASON =
            "Reading the GPU clock requires elevated (Shizuku) access on this device."
        const val NOT_PRESENT_REASON = "This device does not expose a GPU clock frequency."
    }
}
