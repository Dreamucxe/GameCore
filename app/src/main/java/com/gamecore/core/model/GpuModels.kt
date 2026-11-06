package com.gamecore.core.model

/**
 * A GPU load measurement, expressed as the percentage of the GPU's capacity in use.
 *
 * Like [FrameRateSample], this type only ever exists when a real figure was read: its
 * absence is carried by the [com.gamecore.core.common.Observed] wrapped around it, never
 * by a sentinel value inside it. There is deliberately no "unknown" [GpuLoadSample] and
 * no default constructor — a device that does not expose GPU busy-ness at all, or exposes
 * it only to a privileged reader, yields `Observed.Restricted` or `Observed.Failed`, so a
 * screen can never render a fabricated `0f` that reads as "the GPU is idle" when the truth
 * is "we could not read it". That distinction is the whole point of the reader above this
 * type: an idle GPU and an unreadable one look identical as a number and must not.
 *
 * [loadPercent] is a real 0..100 figure. The reader clamps a delta-derived ratio into that
 * range and rejects an instantaneous node whose value falls outside it rather than
 * inventing a plausible-looking number out of a garbage reading.
 */
data class GpuLoadSample(val loadPercent: Float)

/**
 * A GPU clock measurement, in megahertz.
 *
 * Like [GpuLoadSample], this type only ever exists for a figure that was actually read: a device
 * whose kernel does not expose a GPU frequency — or exposes it only to a privileged reader — yields
 * [Observed.Restricted] or [Observed.NotPresent] around it, never a sample pretending to be a
 * frequency. The clock is not a load percentage and is not named by the renderer string the
 * platform never sends; it is the number DevCheck-style tools read out of the devfreq sysfs the
 * kernel builds for the GPU, and this type is GameCore's honest version of that same number.
 *
 * [currentMhz] is the frequency the GPU was running at when the node was read. [maxMhz] is the
 * frequency the node itself reports as its ceiling (its `max_freq`), null when the node carried no
 * ceiling of its own. [availableMhz] is the node's full frequency table in megahertz, sorted
 * ascending, empty when the kernel did not publish one. [nodeLabel] is the sysfs path the reading
 * came from, so a screen can say *where* the number came from instead of just printing it.
 */
data class GpuClockSample(
    val currentMhz: Float,
    val maxMhz: Float?,
    val availableMhz: List<Float>,
    val nodeLabel: String,
)
