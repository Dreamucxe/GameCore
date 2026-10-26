package com.gamecore.core.controller

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * One normalized reading of a stick at rest, taken from a fresh motion event.
 *
 * [timestampMillis] is the sampler's own monotonic clock, not the platform's event timestamp. The two are
 * not comparable, and the only question this series answers is how the readings changed over the run.
 */
data class DriftSample(
    val timestampMillis: Long,
    val x: Float,
    val y: Float,
)

/** What a drift run concluded. Absence of a verdict is represented by [NOT_ENOUGH_DATA], never by STABLE. */
enum class DriftVerdict(val label: String) {
    STABLE("Stable"),
    DRIFT("Offset detected"),
    UNSTABLE("Unstable"),
    MOVED("Stick moved"),
    NOT_ENOUGH_DATA("Not enough data"),
    ;

    /** Whether this verdict is a measurement of rest rather than a refusal to diagnose. */
    val isConclusive: Boolean get() = this == STABLE || this == DRIFT || this == UNSTABLE
}

/**
 * The tolerance a drift run is judged against.
 *
 * [offset] is a normalized stick radius; [jitter] is the RMS radial deviation from the run's own mean.
 * [minSamples] and [minCoverage] make a short or stalled run inconclusive rather than lucky: a controller
 * that sent four events is not evidence of anything, and a run that lost input focus halfway through is not
 * a ten-second test.
 */
data class DriftThresholds(
    val offset: Float = 0.08f,
    val jitter: Float = 0.02f,
    val minSamples: Int = 20,
    val minCoverage: Float = 0.80f,
    val movedRadius: Float = 0.50f,
) {
    fun sanitized(): DriftThresholds = copy(
        offset = offset.coerceIn(0f, 0.95f),
        jitter = jitter.coerceIn(0f, 1f),
        minSamples = minSamples.coerceIn(1, 100_000),
        minCoverage = minCoverage.coerceIn(0f, 1f),
        movedRadius = movedRadius.coerceIn(0.1f, 2f),
    )
}

/**
 * A finished drift analysis. Every figure is derived from the samples in the run; none is estimated from
 * the device name, its vendor, or how a previous controller behaved.
 *
 * [effectiveOffset] is the threshold actually applied. It is never smaller than the device's own declared
 * `flat` radius ([declaredFlat]), because reporting a stick as drifting while it sits inside the dead zone
 * its driver already applies would be a false diagnosis. [effectiveJitter] is likewise floored by the
 * driver's declared `fuzz`, which is the platform's own statement of how precisely it can read the axis.
 */
data class DriftResult(
    val verdict: DriftVerdict,
    val sampleCount: Int,
    val spanMillis: Long,
    val offsetX: Float,
    val offsetY: Float,
    val offsetMagnitude: Float,
    val jitter: Float,
    val stabilityPercent: Float,
    val peakMagnitude: Float,
    val effectiveOffset: Float,
    val effectiveJitter: Float,
    val declaredFlat: Float,
    val declaredFuzz: Float,
    val configuredOffset: Float,
    val configuredJitter: Float,
    val detail: String,
) {
    val isConclusive: Boolean get() = verdict.isConclusive
}

/**
 * The drift arithmetic, kept pure so its thresholds are testable without a controller or a screen.
 *
 * The rules, in order:
 *
 *  1. **Enough data.** Fewer than [DriftThresholds.minSamples] readings, or a span shorter than
 *     `requestedDurationMillis * minCoverage`, is [DriftVerdict.NOT_ENOUGH_DATA]. A stalled run must not
 *     come back green.
 *  2. **The stick was moved.** If more than a token number of samples left a [DriftThresholds.movedRadius]
 *     circle, the run is [DriftVerdict.MOVED] and no drift figure is offered. A deliberate push and a
 *     drifting stick produce the same mean; calling that drift would be the most misleading answer the lab
 *     could give. One isolated spike is tolerated as a dropped sample, not treated as a push.
 *  3. **Offset first, then jitter.** A resting mean outside the tolerance is [DriftVerdict.DRIFT] even if
 *     the readings are quiet; quiet jitter with no offset is [DriftVerdict.UNSTABLE]. Only a run that passes
 *     both is [DriftVerdict.STABLE].
 *
 * The mean is taken over every accepted sample, and jitter is the RMS radial distance from that mean rather
 * than per-axis variance: a stick can wander in a circle with stable per-axis averages, and per-axis figures
 * would call that calm.
 */
object StickDriftAnalyzer {

    /** One movement impulse is a dropped sample; a sustained push is a moved stick. */
    private const val MIN_MOVED_SAMPLES = 2

    fun analyze(
        samples: List<DriftSample>,
        requestedDurationMillis: Long,
        thresholds: DriftThresholds = DriftThresholds(),
        declaredFlat: Float = 0f,
        declaredFuzz: Float = 0f,
    ): DriftResult {
        val safe = thresholds.sanitized()
        val usable = samples.filter { it.x.isFinite() && it.y.isFinite() }
        val ordered = usable.sortedBy { it.timestampMillis }
        val flat = declaredFlat.coerceIn(0f, 1f)
        val fuzz = declaredFuzz.coerceIn(0f, 1f)
        val effectiveOffset = maxOf(safe.offset, flat)
        val effectiveJitter = maxOf(safe.jitter, fuzz)

        if (ordered.isEmpty()) {
            return empty(
                verdict = DriftVerdict.NOT_ENOUGH_DATA,
                thresholds = safe,
                effectiveOffset = effectiveOffset,
                effectiveJitter = effectiveJitter,
                flat = flat,
                fuzz = fuzz,
                detail = "No fresh stick readings arrived. Leave the stick untouched and run the test again.",
            )
        }

        val span = (ordered.last().timestampMillis - ordered.first().timestampMillis).coerceAtLeast(0L)
        val count = ordered.size
        val meanX = ordered.sumOf { it.x.toDouble() }.toFloat() / count
        val meanY = ordered.sumOf { it.y.toDouble() }.toFloat() / count
        val offset = hypot(meanX, meanY)

        var squared = 0.0
        var peak = 0f
        var inside = 0
        var outsideMoved = 0
        for (sample in ordered) {
            val dx = sample.x - meanX
            val dy = sample.y - meanY
            squared += (dx * dx + dy * dy).toDouble()
            val radius = hypot(sample.x, sample.y)
            if (radius > peak) peak = radius
            if (radius <= effectiveOffset) inside++
            if (radius > safe.movedRadius) outsideMoved++
        }
        val jitter = sqrt(squared / count).toFloat()
        val stability = inside.toFloat() / count * 100f

        val requiredSpan = (requestedDurationMillis.coerceAtLeast(0L) * safe.minCoverage).toLong()
        if (count < safe.minSamples || span < requiredSpan) {
            return DriftResult(
                verdict = DriftVerdict.NOT_ENOUGH_DATA,
                sampleCount = count,
                spanMillis = span,
                offsetX = meanX,
                offsetY = meanY,
                offsetMagnitude = offset,
                jitter = jitter,
                stabilityPercent = stability,
                peakMagnitude = peak,
                effectiveOffset = effectiveOffset,
                effectiveJitter = effectiveJitter,
                declaredFlat = flat,
                declaredFuzz = fuzz,
                configuredOffset = safe.offset,
                configuredJitter = safe.jitter,
                detail = "Only $count readings over ${span / 1000.0} s. A run needs both fresh events and " +
                    "most of the requested duration before it can say anything.",
            )
        }

        if (outsideMoved >= maxOf(MIN_MOVED_SAMPLES, count / 100)) {
            return DriftResult(
                verdict = DriftVerdict.MOVED,
                sampleCount = count,
                spanMillis = span,
                offsetX = meanX,
                offsetY = meanY,
                offsetMagnitude = offset,
                jitter = jitter,
                stabilityPercent = stability,
                peakMagnitude = peak,
                effectiveOffset = effectiveOffset,
                effectiveJitter = effectiveJitter,
                declaredFlat = flat,
                declaredFuzz = fuzz,
                configuredOffset = safe.offset,
                configuredJitter = safe.jitter,
                detail = "The stick left the resting area during the run (peak ${format(peak)}). Resting " +
                    "drift cannot be separated from a deliberate push, so this run proves nothing.",
            )
        }

        val verdict = when {
            offset > effectiveOffset && jitter > effectiveJitter -> DriftVerdict.DRIFT
            offset > effectiveOffset -> DriftVerdict.DRIFT
            jitter > effectiveJitter -> DriftVerdict.UNSTABLE
            else -> DriftVerdict.STABLE
        }
        val detail = when (verdict) {
            DriftVerdict.STABLE ->
                "Resting offset ${format(offset)} and jitter ${format(jitter)} are inside the tolerance " +
                    "applied (${format(effectiveOffset)} / ${format(effectiveJitter)})."
            DriftVerdict.DRIFT ->
                "Resting offset ${format(offset)} exceeds ${format(effectiveOffset)}" +
                    if (jitter > effectiveJitter) {
                        ", and jitter ${format(jitter)} also exceeds ${format(effectiveJitter)}."
                    } else {
                        "."
                    }
            DriftVerdict.UNSTABLE ->
                "The centre holds (offset ${format(offset)}), but jitter ${format(jitter)} exceeds " +
                    "${format(effectiveJitter)}."
            else -> "No verdict."
        }

        return DriftResult(
            verdict = verdict,
            sampleCount = count,
            spanMillis = span,
            offsetX = meanX,
            offsetY = meanY,
            offsetMagnitude = offset,
            jitter = jitter,
            stabilityPercent = stability,
            peakMagnitude = peak,
            effectiveOffset = effectiveOffset,
            effectiveJitter = effectiveJitter,
            declaredFlat = flat,
            declaredFuzz = fuzz,
            configuredOffset = safe.offset,
            configuredJitter = safe.jitter,
            detail = detail,
        )
    }

    /**
     * A figure for the screen. Three decimals is below what any controller reports usefully, and keeping
     * the sign on an offset component is the difference between "pull left" and "pull right".
     */
    fun format(value: Float): String = String.format(java.util.Locale.US, "%.3f", value)

    private fun empty(
        verdict: DriftVerdict,
        thresholds: DriftThresholds,
        effectiveOffset: Float,
        effectiveJitter: Float,
        flat: Float,
        fuzz: Float,
        detail: String,
    ) = DriftResult(
        verdict = verdict,
        sampleCount = 0,
        spanMillis = 0L,
        offsetX = 0f,
        offsetY = 0f,
        offsetMagnitude = 0f,
        jitter = 0f,
        stabilityPercent = 0f,
        peakMagnitude = 0f,
        effectiveOffset = effectiveOffset,
        effectiveJitter = effectiveJitter,
        declaredFlat = flat,
        declaredFuzz = fuzz,
        configuredOffset = thresholds.offset,
        configuredJitter = thresholds.jitter,
        detail = detail,
    )
}
