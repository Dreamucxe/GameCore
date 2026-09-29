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
