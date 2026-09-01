package com.gamecore.core.system

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.common.Precision
import com.gamecore.core.model.CpuTimes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns cumulative jiffy counters into utilisation percentages.
 *
 * CPU usage is not a value any Android API returns. It exists only as the difference
 * between two readings of a monotonically increasing counter, and that has three
 * consequences this class exists to handle honestly:
 *
 *  1. **The first sample cannot produce a percentage.** There is nothing to subtract
 *     from. It returns [Observed.awaitingSample] — a restriction reason of its own, so
 *     the UI can show "Measuring…" rather than the "not available on this device"
 *     wording that belongs to a real limitation. This is the one improvement on the
 *     ProcessLens original, which reused `NOT_PRESENT_ON_DEVICE` for the case and
 *     therefore could not tell a device that will never report CPU usage apart from
 *     one that is about to.
 *  2. **The interval matters**, so every figure is marked [Precision.SAMPLED].
 *  3. **Counters reset** — on a wrapped counter, on a kernel that renumbers cores when
 *     a cluster is hotplugged, or when the source switches between `/proc` and the
 *     shell mid-session. A backwards delta drops the baseline and says so instead of
 *     being clamped to zero, which would report a wrong number silently.
 *
 * Not thread-safe by design: one sampler belongs to one sampling loop. The monitoring
 * layer owns exactly one and drives it from a single coroutine.
 */
@Singleton
class CpuSampler @Inject constructor() {

    private var previousSystem: CpuTimes? = null
    private var previousPerCore: List<CpuTimes>? = null

    /**
     * System-wide utilisation from two `/proc/stat` readings.
     *
     * Takes the [Observed] rather than a bare [CpuTimes] so that a restriction on the
     * underlying read — an SELinux denial, which is the common case — propagates with
     * its own explanation instead of being flattened into "waiting for a sample".
     */
    fun sampleSystem(current: Observed<CpuTimes>): Observed<Float> {
        val now = when (current) {
            is Observed.Value -> current.value
            is Observed.Restricted -> return current
            is Observed.Failed -> return current
        }

        val previous = previousSystem
        previousSystem = now

        if (previous == null) {
            return Observed.awaitingSample(
                "CPU usage is a rate, not an instant value. It appears on the next tick.",
            )
        }
        if (now.total < previous.total) {
            return Observed.Failed("CPU counters went backwards; the baseline was reset")
        }
        return now.utilisationSince(previous)
            ?.let { Observed.of(it, DataSource.PROC_FS, Precision.SAMPLED) }
            ?: Observed.awaitingSample("The counters did not advance between samples.")
    }

    /**
     * Per-core utilisation.
     *
     * A core that was parked for the whole interval yields null. Those become 0% only
     * when at least one core produced a real figure — a wholly unreadable set stays
     * unavailable rather than rendering as a row of zero-height bars, which is what a
     * genuinely idle CPU looks like.
     *
     * A changed core count means the kernel hotplugged a cluster between samples; the
     * per-core arrays no longer line up, so the baseline is replaced rather than zipped
     * against a list of a different length.
     */
    fun samplePerCore(current: Observed<List<CpuTimes>>): Observed<List<Float>> {
        val now = when (current) {
            is Observed.Value -> current.value
            is Observed.Restricted -> return current
            is Observed.Failed -> return current
        }

        val previous = previousPerCore
        previousPerCore = now

        if (previous == null || previous.size != now.size) {
            return Observed.awaitingSample()
        }
        val values = now.zip(previous).map { (nowCore, previousCore) ->
            nowCore.utilisationSince(previousCore)
        }
        if (values.all { it == null }) {
            return Observed.awaitingSample("No core advanced its counters between samples.")
        }
        return Observed.of(values.map { it ?: 0f }, DataSource.PROC_FS, Precision.SAMPLED)
    }

    /**
     * Drops the baselines.
     *
     * Called when the sampling interval changes, when the reading source switches
     * between `/proc` and the elevated shell, and when sampling is paused — in each
     * case the next delta would span an interval that is not the one it is about to be
     * divided by, and a stale baseline is how a paused-and-resumed monitor reports 400%
     * CPU for one tick.
     */
    fun reset() {
        previousSystem = null
        previousPerCore = null
    }

    /** True once a percentage can actually be produced. Drives the "Measuring…" state. */
    val hasBaseline: Boolean get() = previousSystem != null
}

/**
 * Creates [CpuSampler] instances.
 *
 * A sampler holds baselines, so it cannot be shared between readers that obtain their
 * counters by different routes: the difference between a jiffy count read from `/proc`
 * and one read back through a shell spans whatever the gap between the two reads was,
 * not the sampling interval. The composite reader gives the elevated reader its own.
 */
@Singleton
class CpuSamplerFactory @Inject constructor() {
    fun create(): CpuSampler = CpuSampler()
}
