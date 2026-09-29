package com.gamecore.core.system

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.common.Precision
import com.gamecore.core.model.GpuLoadSample
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads how busy the GPU is, when the kernel exposes it, and says so honestly when it does not.
 *
 * There is no platform API for GPU utilisation — nothing in `android.*` returns it — so the only
 * source is a vendor sysfs node, and which node (if any) exists is entirely SoC-dependent. This
 * reader probes the handful of nodes that real kernels use, in order of how directly they answer the
 * question, and keeps the first one that yields a plausible reading. Everything it cannot read leaves
 * as [Observed.Restricted] or [Observed.Failed]; a value outside 0..100 or a node that is present but
 * unreadable never becomes a fabricated `0f`.
 *
 * Two shapes of node exist and are handled differently:
 *
 *  * An **instantaneous percentage** — Adreno's `gpu_busy_percentage`, a generic `gpu_busy`, or a
 *    Mali `utilization` — is a number the driver already computed. It is [Precision.EXACT].
 *  * Adreno's **`gpubusy`** is a pair of cumulative counters (busy, total). A single read means
 *    nothing; utilisation is the ratio of their *deltas* between two reads, so the first read can only
 *    honestly return [Observed.awaitingSample] and the figure appears on the next tick. It is
 *    [Precision.SAMPLED].
 *
 * Restriction is decided without relying on catching `EACCES`, because an SELinux denial surfaces
 * inconsistently across vendor kernels. Instead the tier is structural: if a candidate's value file
 * cannot be read but its **parent node directory exists**, the GPU node family is present and the read
 * is merely restricted — reachable through an elevated (Shizuku) shell, or reported as needing one. If
 * no candidate's parent directory exists at all, the device simply does not report GPU load. A
 * [SecurityException] is caught as a belt-and-braces restricted signal on top of that.
 *
 * The injectable [sysRoot] is what makes the probing and the tiering testable on the JVM against
 * fixture directories, exactly as [ProcFsReader] does for `/proc` and `/sys`. All file work is blocking
 * I/O confined to [io].
 */
@Singleton
class GpuReader(
    private val io: CoroutineDispatcher,
    private val elevatedShell: ElevatedShell,
    private val sysRoot: File,
) {

    /**
     * The production wiring. The `File` root is not a Hilt binding, so — as in [ProcFsReader] — the
     * injected constructor supplies the real `/sys` and delegates to the primary one the tests use.
     */
    @Inject
    constructor(
        @IoDispatcher io: CoroutineDispatcher,
        elevatedShell: ElevatedShell,
    ) : this(io, elevatedShell, File("/sys"))

    /** The `gpubusy` delta baseline, held between samples. Null until the first read of that node. */
    private var previousBusyTotal: Pair<Long, Long>? = null

    /** The node settled on once one answered plausibly, so later ticks read it directly. */
    private var chosen: Candidate? = null

    /**
     * The current GPU load, or the reason it is absent.
     *
     * Once a node has been chosen, only that node is re-read; until then every candidate is probed in
     * order and the first plausible one wins. A restricted-but-present node is read through the
     * elevated shell when one is available, and otherwise reported as needing elevation.
     */
    suspend fun read(): Observed<GpuLoadSample> = withContext(io) {
        chosen?.let { return@withContext resolve(it) }

        var restricted: Candidate? = null
        for (candidate in candidates()) {
            when (val probe = probeDirect(candidate)) {
                is Probe.Ok -> {
                    chosen = candidate
                    return@withContext Observed.of(probe.sample, DataSource.SYS_FS, probe.precision)
                }
                Probe.Awaiting -> {
                    chosen = candidate
                    return@withContext Observed.awaitingSample(AWAITING_REASON)
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

    /** Drops the `gpubusy` delta baseline so the next read of that node awaits a fresh second sample. */
    fun resetSampling() {
        previousBusyTotal = null
    }

    // ------------------------------------------------------------------ per-node read

    /** Reads one already-chosen node: a direct value, an awaited delta, or the restricted fallback. */
    private suspend fun resolve(candidate: Candidate): Observed<GpuLoadSample> =
        when (val probe = probeDirect(candidate)) {
            is Probe.Ok -> Observed.of(probe.sample, DataSource.SYS_FS, probe.precision)
            Probe.Awaiting -> Observed.awaitingSample(AWAITING_REASON)
            Probe.Restricted -> resolveRestricted(candidate)
            Probe.Absent -> Observed.notPresent(NOT_PRESENT_REASON)
        }

    /**
     * Attempts a direct sysfs read, deciding the tier without leaning on the shape of an `EACCES`.
     *
     * A value that reads and parses in range is [Probe.Ok]. Anything else — a missing file, an empty
     * or garbage value, an out-of-range percentage, a read that threw — falls to [restrictedOrAbsent],
     * which distinguishes "the node family is present but this is not readable" from "nothing here at
     * all" purely by whether the parent directory exists.
     */
    private fun probeDirect(candidate: Candidate): Probe {
        val text = try {
            val file = candidate.valueFile
            if (!file.exists()) null else file.readText().trim().ifEmpty { null }
        } catch (error: SecurityException) {
            // Belt-and-braces: an explicit sandbox refusal is a restriction the shell may lift.
            return Probe.Restricted
        } catch (error: Throwable) {
            null
        }

        if (text != null) {
            val probe = when (candidate.kind) {
                GpuNodeKind.PERCENTAGE ->
                    parsePercent(text)?.let { Probe.Ok(GpuLoadSample(it), Precision.EXACT) }
                GpuNodeKind.GPUBUSY_PAIR -> parseGpuBusyDelta(text)
            }
            if (probe != null) return probe
        }
        return restrictedOrAbsent(candidate)
    }

    /** Present-but-unreadable (parent directory exists) is [Probe.Restricted]; otherwise [Probe.Absent]. */
    private fun restrictedOrAbsent(candidate: Candidate): Probe {
        val parent = candidate.valueFile.parentFile
        val present = try {
            parent != null && parent.isDirectory
        } catch (error: Throwable) {
            false
        }
        return if (present) Probe.Restricted else Probe.Absent
    }

    // --------------------------------------------------------------------- parsing

    /** The first token of an instantaneous node as a 0..100 percentage, or null if it is neither. */
    private fun parsePercent(text: String): Float? {
        val token = text.split(WHITESPACE).firstOrNull()?.removeSuffix("%")?.trim()
        val value = token?.toFloatOrNull() ?: return null
        return if (value in 0f..100f) value else null
    }

    /**
     * The utilisation implied by two `gpubusy` reads, or the awaited-first-sample state.
     *
     * `gpubusy` is `busy total`, both cumulative. The first read has no baseline to subtract, so it
     * stores one and returns [Probe.Awaiting]; a subsequent read divides the busy delta by the total
     * delta. A non-positive total delta (paused, wrapped, or read twice in the same interval) is not a
     * real 0% — it is another awaited sample. Unparseable text returns null so the caller can treat the
     * node as unreadable rather than as reporting a figure.
     */
    private fun parseGpuBusyDelta(text: String): Probe? {
        val tokens = text.split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.size < 2) return null
        val busy = tokens[0].toLongOrNull() ?: return null
        val total = tokens[1].toLongOrNull() ?: return null

        val previous = previousBusyTotal
        previousBusyTotal = busy to total
        if (previous == null) return Probe.Awaiting

        val deltaTotal = total - previous.second
        if (deltaTotal <= 0L) return Probe.Awaiting
        val deltaBusy = busy - previous.first
        val util = (deltaBusy.toFloat() / deltaTotal.toFloat() * 100f).coerceIn(0f, 100f)
        return Probe.Ok(GpuLoadSample(util), Precision.SAMPLED)
    }

    // -------------------------------------------------------------- elevated fallback

    /**
     * Re-reads a present-but-restricted node through the elevated shell, or reports it needs one.
     *
     * The shell always addresses the canonical `/sys` path rather than the injected [sysRoot] one: on a
     * device [sysRoot] *is* `/sys`, and the elevated shell reads the real filesystem regardless of the
     * root this reader was given for testing. The command is built through [ShellCommand.ReadGpuBusyNode],
     * whose factory path-validates it, so an unexpected path yields the honest "needs elevation" answer
     * rather than an unchecked `cat`.
     */
    private suspend fun resolveRestricted(candidate: Candidate): Observed<GpuLoadSample> {
        if (!elevatedShell.isAvailable()) {
            return Observed.needsElevation(NEEDS_ELEVATION_REASON)
        }
        val command = ShellCommand.ReadGpuBusyNode.of(candidate.canonicalPath)
            ?: return Observed.needsElevation(NEEDS_ELEVATION_REASON)

        val result = elevatedShell.execute(command)
        val output = result.singleValue()
            ?: return Observed.Failed(
                "The GPU load node could not be read through the elevated shell.",
                result.failureReason().ifBlank { null },
            )
        val parsed = parseShellSample(output, candidate.kind)
            ?: return Observed.Failed("The elevated shell returned an unreadable GPU load value.")
        return Observed.of(parsed.first, DataSource.SHELL_SHIZUKU, parsed.second)
    }

    /** Parses shell stdout the same way as a direct read, keyed by the node's shape. */
    private fun parseShellSample(text: String, kind: GpuNodeKind): Pair<GpuLoadSample, Precision>? =
        when (kind) {
            GpuNodeKind.PERCENTAGE ->
                parsePercent(text)?.let { GpuLoadSample(it) to Precision.EXACT }
            GpuNodeKind.GPUBUSY_PAIR -> {
                val tokens = text.split(WHITESPACE).filter { it.isNotEmpty() }
                val busy = tokens.getOrNull(0)?.toLongOrNull()
                val total = tokens.getOrNull(1)?.toLongOrNull()
                if (busy != null && total != null && total > 0L) {
                    val util = (busy.toFloat() / total.toFloat() * 100f).coerceIn(0f, 100f)
                    GpuLoadSample(util) to Precision.SAMPLED
                } else {
                    null
                }
            }
        }

    // ------------------------------------------------------------------- candidates

    /**
     * The nodes real kernels use, in the order the most direct answer comes first.
     *
     * The Mali platform entry is a wildcard — the directory under `devices/platform` is named after the
     * SoC's memory-mapped address (`13000000.mali`, say) — so it is resolved against the filesystem and
     * omitted when no such directory is present, rather than guessed at.
     */
    private fun candidates(): List<Candidate> {
        val out = ArrayList<Candidate>(5)
        out += candidate("class/kgsl/kgsl-3d0/gpu_busy_percentage", GpuNodeKind.PERCENTAGE)
        out += candidate("class/kgsl/kgsl-3d0/gpubusy", GpuNodeKind.GPUBUSY_PAIR)
        out += candidate("kernel/gpu/gpu_busy", GpuNodeKind.PERCENTAGE)
        maliPlatformUtilizationSubpath()?.let { out += candidate(it, GpuNodeKind.PERCENTAGE) }
        out += candidate("class/misc/mali0/device/utilization", GpuNodeKind.PERCENTAGE)
        return out
    }

    private fun candidate(subpath: String, kind: GpuNodeKind): Candidate =
        Candidate(File(sysRoot, subpath), "/sys/$subpath", kind)

    private fun maliPlatformUtilizationSubpath(): String? {
        val dir = try {
            File(sysRoot, "devices/platform").listFiles()
                ?.firstOrNull { it.isDirectory && it.name.contains("mali", ignoreCase = true) }
        } catch (error: Throwable) {
            null
        } ?: return null
        return "devices/platform/${dir.name}/utilization"
    }

    // ------------------------------------------------------------------------ types

    /** A node to probe: where it is on this root, its canonical `/sys` path for the shell, and its shape. */
    private class Candidate(
        val valueFile: File,
        val canonicalPath: String,
        val kind: GpuNodeKind,
    )

    /** The two shapes of GPU-load node, which parse and report differently. */
    private enum class GpuNodeKind { PERCENTAGE, GPUBUSY_PAIR }

    /** The outcome of a direct sysfs probe of one candidate. */
    private sealed interface Probe {
        data class Ok(val sample: GpuLoadSample, val precision: Precision) : Probe
        data object Awaiting : Probe
        data object Restricted : Probe
        data object Absent : Probe
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")

        const val AWAITING_REASON =
            "GPU load from this node is a rate between two samples. It appears on the next tick."
        const val NEEDS_ELEVATION_REASON =
            "Reading GPU load requires elevated (Shizuku) access on this device."
        const val NOT_PRESENT_REASON = "This device does not report GPU load."
    }
}
