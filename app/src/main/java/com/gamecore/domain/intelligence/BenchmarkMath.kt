package com.gamecore.domain.intelligence

import com.gamecore.core.model.BenchmarkComparison
import com.gamecore.core.model.BenchmarkPoint
import com.gamecore.core.model.BenchmarkProfile
import com.gamecore.core.model.BenchmarkRun
import com.gamecore.core.model.BenchmarkSummary
import com.gamecore.core.model.ComparisonVerdict
import com.gamecore.core.model.MetricDelta
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * All of the benchmark arithmetic, in one pure place.
 *
 * The rules that matter are:
 *
 *  * A figure is computed only from points that actually carried it. The average of an empty list
 *    is null, never zero.
 *  * Average FPS is weighted by each measurement window's frame count. A window with 120 real
 *    frames is stronger evidence than one with 10.
 *  * The "1% low" reported for a run is the median of the per-window 1% lows that the platform
 *    produced. It is not recomputed from a pooled interval list the app does not keep.
 *  * A comparison refuses to name a winner unless the two runs are comparable, the difference
 *    clears a practical threshold, and a Welch t-test on the per-window FPS says the difference is
 *    unlikely to be noise. Otherwise it returns INCONCLUSIVE and says why.
 */
object BenchmarkMath {

    /** The platform source every FPS figure comes from. There is no second source. */
    const val FPS_SOURCE = "Android gfxinfo framestats (HWUI)"

    /** Below this many captured points a run is a glance, not a benchmark. */
    const val MIN_SAMPLES = 5

    /** Below this many real frame windows no statistical comparison is attempted. */
    const val MIN_FPS_WINDOWS = 3

    /** A capture shorter than this cannot quote a battery rate. Matches BatteryDrain's own floor. */
    const val MIN_BATTERY_MILLIS = 5 * 60 * 1000L

    /** Two runs more different than this in length are not treated as the same test. */
    const val MAX_DURATION_RATIO = 2.0

    /** An average-FPS change must clear this fraction to be treated as practical. */
    const val MIN_MEANINGFUL_FPS_FRACTION = 0.02f

    /** ...or this many frames per second, whichever is larger. */
    const val MIN_MEANINGFUL_FPS = 1f

    /** A confidence below this is reported as inconclusive rather than as a winner. */
    const val MIN_CONFIDENCE_PERCENT = 90

    /** If the candidate ran this much hotter, the difference is confounded by conditions. */
    const val MAX_TEMPERATURE_DELTA_DECI = 80

    fun summarize(
        points: List<BenchmarkPoint>,
        profile: BenchmarkProfile,
    ): BenchmarkSummary {
        if (points.isEmpty()) return BenchmarkSummary.EMPTY.copy(unavailable = listOf("No readings were captured."))

        val duration = points.maxOf { it.elapsedMillis }
        val cpu = points.mapNotNull { it.cpuPercent }
        val gpu = points.mapNotNull { it.gpuPercent }
        val memory = points.mapNotNull { it.memoryPercent }
        val temperature = points.mapNotNull { it.temperatureDeciCelsius }
        val refresh = points.mapNotNull { it.refreshRateHz }
        val battery = points.mapNotNull { it.batteryPercent?.takeIf { level -> level in 0..100 } }
        val charging = points.any { it.charging == true }
        val frames = points.mapNotNull { it.frameRate }.filter { it.isReliable }

        val unavailable = mutableListOf<String>()
        if (frames.isEmpty()) {
            unavailable += "No game frame timing was measurable during this run."
        }
        if (cpu.isEmpty()) unavailable += "CPU utilisation was not readable."
        if (gpu.isEmpty()) unavailable += "GPU utilisation was not readable."
        if (memory.isEmpty()) unavailable += "Memory utilisation was not readable."
        if (temperature.isEmpty()) unavailable += "No temperature reading was available."
        if (refresh.isEmpty()) unavailable += "The display refresh rate was not readable."
        if (battery.size < 2) {
            unavailable += "Battery level was not captured at both ends of the run."
        } else if (charging) {
            unavailable += "A charger was connected, so a battery drain rate is not quoted."
        } else if (duration < MIN_BATTERY_MILLIS) {
            unavailable += "Battery drain needs at least five minutes to be a rate rather than quantisation."
        }

        val totalFrames = frames.sumOf { it.frameCount }
        val averageFps = if (totalFrames > 0) {
            frames.sumOf { (it.averageFps * it.frameCount).toDouble() }.toFloat() / totalFrames
        } else {
            null
        }
        val onePercent = frames.mapNotNull { it.onePercentLowFps }.sorted()
        val p95 = frames.mapNotNull { it.p95FrameMillis }.sorted()
        val p99 = frames.mapNotNull { it.p99FrameMillis }.sorted()
        val windowFps = frames.map { it.averageFps }

        val batteryStart = battery.firstOrNull()
        val batteryEnd = battery.lastOrNull()
        val batteryRate = if (batteryStart != null &&
            batteryEnd != null &&
            battery.size >= 2 &&
            !charging &&
            duration >= MIN_BATTERY_MILLIS
        ) {
            val hours = duration / 3_600_000.0
            val lost = (batteryStart - batteryEnd).coerceAtLeast(0)
            if (hours > 0) (lost / hours).toFloat() else null
        } else {
            null
        }

        return BenchmarkSummary(
            sampleCount = points.size,
            durationMillis = duration,
            averageFps = averageFps,
            lowestWindowFps = windowFps.minOrNull(),
            onePercentLowFps = median(onePercent),
            frameVariationPercent = coefficientOfVariation(windowFps),
            p95FrameMillis = median(p95),
            p99FrameMillis = median(p99),
            jankPercent = if (totalFrames > 0) {
                frames.sumOf { it.jankFrames }.toFloat() / totalFrames * 100f
            } else {
                null
            },
            averageCpuPercent = mean(cpu),
            peakCpuPercent = cpu.maxOrNull(),
            cpuSource = source(points.mapNotNull { it.cpuSource }),
            averageGpuPercent = mean(gpu),
            peakGpuPercent = gpu.maxOrNull(),
            gpuSource = source(points.mapNotNull { it.gpuSource }),
            averageMemoryPercent = mean(memory),
            peakMemoryPercent = memory.maxOrNull(),
            memorySource = source(points.mapNotNull { it.memorySource }),
            averageTemperatureDeciCelsius = mean(temperature.map(Int::toFloat))?.toInt(),
            peakTemperatureDeciCelsius = temperature.maxOrNull(),
            temperatureSource = source(points.mapNotNull { it.temperatureSource }),
            startBatteryPercent = batteryStart,
            endBatteryPercent = batteryEnd,
            batteryDrainPerHour = batteryRate,
            batterySource = source(points.mapNotNull { it.batterySource }),
            averageRefreshRateHz = mean(refresh),
            refreshRateSource = source(points.mapNotNull { it.refreshRateSource }),
            fpsSource = if (averageFps != null) FPS_SOURCE else null,
            unavailable = unavailable,
        )
    }

    /**
     * Compares two real captures of the same game under the same profile.
     *
     * [baselineWindows] and [candidateWindows] are the per-window average FPS values from the two
     * runs, in capture order. They are the samples the t-test sees. A run whose summary has an
     * average but no windows cannot be compared, because the average alone has no variance.
     */
    fun compare(
        baseline: BenchmarkRun,
        baselineWindows: List<Float>,
        candidate: BenchmarkRun,
        candidateWindows: List<Float>,
    ): BenchmarkComparison {
        val conditions = mutableListOf<String>()
        if (baseline.gamePackage != candidate.gamePackage) {
            return notComparable("The two runs measured different games.")
        }
        conditions += "Same game: ${baseline.gameLabel ?: "device only"}"
        if (baseline.profile != candidate.profile) {
            return notComparable("The two runs used different benchmark profiles.")
        }
        conditions += "Same profile: ${baseline.profile.label}"

        if (baseline.summary.sampleCount < MIN_SAMPLES || candidate.summary.sampleCount < MIN_SAMPLES) {
            return notComparable(
                "Each run needs at least $MIN_SAMPLES captured readings; these had " +
                    "${baseline.summary.sampleCount} and ${candidate.summary.sampleCount}.",
            )
        }

        val short = minOf(baseline.summary.durationMillis, candidate.summary.durationMillis)
        val long = maxOf(baseline.summary.durationMillis, candidate.summary.durationMillis)
        if (short <= 0L || long.toDouble() / short.toDouble() > MAX_DURATION_RATIO) {
            return notComparable(
                "The runs lasted ${minutes(baseline.summary.durationMillis)} and " +
                    "${minutes(candidate.summary.durationMillis)}; more than a 2× difference is not " +
                    "the same test.",
            )
        }
        conditions += "Durations: ${minutes(baseline.summary.durationMillis)} vs " +
            minutes(candidate.summary.durationMillis)

        val baselineTemp = baseline.summary.averageTemperatureDeciCelsius
        val candidateTemp = candidate.summary.averageTemperatureDeciCelsius
        if (baselineTemp != null && candidateTemp != null) {
            val delta = candidateTemp - baselineTemp
            conditions += "Average temperature: ${formatTemperature(baselineTemp)} vs " +
                formatTemperature(candidateTemp)
            if (abs(delta) > MAX_TEMPERATURE_DELTA_DECI) {
                return BenchmarkComparison(
                    verdict = ComparisonVerdict.INCONCLUSIVE,
                    deltas = deltas(baseline, candidate),
                    confidencePercent = null,
                    explanation = "The runs were taken at temperatures more than 8 °C apart. A " +
                        "difference that large can change clocks by itself, so the change under test " +
                        "cannot be separated from the heat.",
                    conditions = conditions,
                    baselineRunId = baseline.id,
                    candidateRunId = candidate.id,
                )
            }
        }

        val baselineFps = baseline.summary.averageFps
        val candidateFps = candidate.summary.averageFps
        if (baselineFps == null || candidateFps == null) {
            return BenchmarkComparison(
                verdict = ComparisonVerdict.INCONCLUSIVE,
                deltas = deltas(baseline, candidate),
                confidencePercent = null,
                explanation = "Game frame rate was not measured in both runs, so no performance claim " +
                    "can be made. The other metrics are shown without a verdict.",
                conditions = conditions,
                baselineRunId = baseline.id,
                candidateRunId = candidate.id,
            )
        }
        if (baselineWindows.size < MIN_FPS_WINDOWS || candidateWindows.size < MIN_FPS_WINDOWS) {
            return BenchmarkComparison(
                verdict = ComparisonVerdict.INCONCLUSIVE,
                deltas = deltas(baseline, candidate),
                confidencePercent = null,
                explanation = "Fewer than $MIN_FPS_WINDOWS real frame windows were captured in one of " +
                    "the runs, which is not enough to tell a change from noise.",
                conditions = conditions,
                baselineRunId = baseline.id,
                candidateRunId = candidate.id,
            )
        }

        val baselineMean = mean(baselineWindows) ?: baselineFps
        val candidateMean = mean(candidateWindows) ?: candidateFps
        val delta = candidateMean - baselineMean
        val practicalFloor = maxOf(
            baselineMean * MIN_MEANINGFUL_FPS_FRACTION,
            MIN_MEANINGFUL_FPS,
        )
        val test = welch(baselineWindows.map { it.toDouble() }, candidateWindows.map { it.toDouble() })
        val confidence = test?.let { Math.round((1.0 - it.pValue) * 100.0).toInt().coerceIn(0, 99) }
        conditions += "${baselineWindows.size} vs ${candidateWindows.size} frame windows"

        if (test == null || confidence == null) {
            return BenchmarkComparison(
                verdict = ComparisonVerdict.INCONCLUSIVE,
                deltas = deltas(baseline, candidate),
                confidencePercent = null,
                explanation = "The frame windows had no measurable spread, so no statistical test " +
                    "could be run. The raw averages are shown but are not called a result.",
                conditions = conditions,
                baselineRunId = baseline.id,
                candidateRunId = candidate.id,
            )
        }

        if (abs(delta) < practicalFloor) {
            return BenchmarkComparison(
                verdict = ComparisonVerdict.NEUTRAL,
                deltas = deltas(baseline, candidate),
                confidencePercent = confidence,
                explanation = "Average FPS moved by ${formatDelta(delta)} fps, below the " +
                    "${formatDelta(practicalFloor)} fps threshold treated as meaningful at this rate.",
                conditions = conditions,
                baselineRunId = baseline.id,
                candidateRunId = candidate.id,
            )
        }

        if (confidence < MIN_CONFIDENCE_PERCENT) {
            return BenchmarkComparison(
                verdict = ComparisonVerdict.INCONCLUSIVE,
                deltas = deltas(baseline, candidate),
                confidencePercent = confidence,
                explanation = "Average FPS moved by ${formatDelta(delta)} fps, but the difference is " +
                    "only $confidence% likely to be real. Another run is needed before this is called " +
                    "an improvement or a regression.",
                conditions = conditions,
                baselineRunId = baseline.id,
                candidateRunId = candidate.id,
            )
        }

        val improved = delta > 0f
        return BenchmarkComparison(
            verdict = if (improved) ComparisonVerdict.IMPROVED else ComparisonVerdict.REGRESSED,
            deltas = deltas(baseline, candidate),
            confidencePercent = confidence,
            explanation = "Average FPS ${if (improved) "rose" else "fell"} by " +
                "${formatDelta(abs(delta))} fps with $confidence% confidence across " +
                "${baselineWindows.size + candidateWindows.size} real frame windows.",
            conditions = conditions,
            baselineRunId = baseline.id,
            candidateRunId = candidate.id,
        )
    }

    private fun deltas(baseline: BenchmarkRun, candidate: BenchmarkRun): List<MetricDelta> =
        buildList {
            fun add(
                label: String,
                before: Float?,
                after: Float?,
                unit: String,
                higherIsBetter: Boolean,
            ) {
                if (before != null && after != null) {
                    add(MetricDelta(label, before, after, unit, higherIsBetter))
                }
            }
            add("Average FPS", baseline.summary.averageFps, candidate.summary.averageFps, "fps", true)
            add("1% low FPS", baseline.summary.onePercentLowFps, candidate.summary.onePercentLowFps, "fps", true)
            add("Lowest window", baseline.summary.lowestWindowFps, candidate.summary.lowestWindowFps, "fps", true)
            add("Jank", baseline.summary.jankPercent, candidate.summary.jankPercent, "%", false)
            add("CPU", baseline.summary.averageCpuPercent, candidate.summary.averageCpuPercent, "%", false)
            add(
                "Temperature",
                baseline.summary.averageTemperatureDeciCelsius?.toFloat(),
                candidate.summary.averageTemperatureDeciCelsius?.toFloat(),
                "°C/10",
                false,
            )
            add(
                "Battery drain",
                baseline.summary.batteryDrainPerHour,
                candidate.summary.batteryDrainPerHour,
                "%/h",
                false,
            )
        }

    // ------------------------------------------------------------------------------- statistics

    /** The arithmetic mean, or null for an empty list. */
    fun mean(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        return (values.sumOf { it.toDouble() } / values.size).toFloat()
    }

    private fun source(values: List<String>): String? =
        values.distinct().takeIf { it.isNotEmpty() }?.joinToString(" / ")

    /** Population coefficient of variation across real measurement windows, as a percentage. */
    fun coefficientOfVariation(values: List<Float>): Float? {
        if (values.size < 2) return null
        val mean = mean(values) ?: return null
        if (mean <= 0f) return null
        val variance = values.sumOf { (it - mean).toDouble() * (it - mean).toDouble() } / values.size
        val sd = sqrt(variance)
        if (!sd.isFinite()) return null
        return (sd / mean * 100.0).toFloat()
    }

    /** The middle of a sorted copy, or the mean of the two middles. Null for an empty list. */
    fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    /** The result of a two-sample Welch t-test, with the effective degrees of freedom. */
    data class WelchResult(
        val t: Double,
        val degreesOfFreedom: Double,
        val pValue: Double,
    )

    /**
     * Welch's unequal-variance t-test.
     *
     * Null when either sample has fewer than two values or when both have zero variance: a test
     * with no spread has nothing to test, and returning a p-value of zero there would call every
     * constant sequence a certainty.
     */
    fun welch(baseline: List<Double>, candidate: List<Double>): WelchResult? {
        if (baseline.size < 2 || candidate.size < 2) return null
        val meanA = baseline.average()
        val meanB = candidate.average()
        val varA = sampleVariance(baseline) ?: return null
        val varB = sampleVariance(candidate) ?: return null
        if (varA <= 0.0 && varB <= 0.0) return null
        val seSquared = varA / baseline.size + varB / candidate.size
        if (seSquared <= 0.0) return null
        val t = (meanB - meanA) / sqrt(seSquared)
        val numerator = seSquared * seSquared
        val denominator = (varA / baseline.size).let { it * it } / (baseline.size - 1) +
            (varB / candidate.size).let { it * it } / (candidate.size - 1)
        if (denominator <= 0.0) return null
        val df = numerator / denominator
        if (!df.isFinite() || df <= 0.0) return null
        val p = twoSidedStudentP(t, df)
        if (!p.isFinite()) return null
        return WelchResult(t = t, degreesOfFreedom = df, pValue = p.coerceIn(0.0, 1.0))
    }

    /** Two-sided p-value of a t statistic: I_{df/(df+t²)}(df/2, 1/2). */
    fun twoSidedStudentP(t: Double, degreesOfFreedom: Double): Double {
        if (!t.isFinite() || !degreesOfFreedom.isFinite() || degreesOfFreedom <= 0.0) return Double.NaN
        val x = degreesOfFreedom / (degreesOfFreedom + t * t)
        return regularizedIncompleteBeta(degreesOfFreedom / 2.0, 0.5, x)
    }

    private fun sampleVariance(values: List<Double>): Double? {
        if (values.size < 2) return null
        val mean = values.average()
        val sum = values.sumOf { (it - mean) * (it - mean) }
        return sum / (values.size - 1)
    }

    /**
     * The regularized incomplete beta function, via its continued fraction.
     *
     * A small Numerical Recipes-style implementation is used rather than a statistics dependency:
     * this is the only distribution the app needs, and a test pins it against known t values.
     */
    private fun regularizedIncompleteBeta(a: Double, b: Double, x: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        val logTerm = lnGamma(a + b) - lnGamma(a) - lnGamma(b) +
            a * ln(x) + b * ln(1.0 - x)
        val front = Math.exp(logTerm)
        return if (x < (a + 1.0) / (a + b + 2.0)) {
            front * betaContinuedFraction(a, b, x) / a
        } else {
            1.0 - front * betaContinuedFraction(b, a, 1.0 - x) / b
        }
    }

    private fun betaContinuedFraction(a: Double, b: Double, x: Double): Double {
        val tiny = 1e-30
        val epsilon = 1e-12
        var c = 1.0
        var d = 1.0 - (a + b) * x / (a + 1.0)
        if (abs(d) < tiny) d = tiny
        d = 1.0 / d
        var h = d
        for (m in 1..200) {
            val m2 = 2 * m
            var aa = m * (b - m) * x / ((a + m2 - 1.0) * (a + m2))
            d = 1.0 + aa * d
            if (abs(d) < tiny) d = tiny
            c = 1.0 + aa / c
            if (abs(c) < tiny) c = tiny
            d = 1.0 / d
            h *= d * c

            aa = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1.0))
            d = 1.0 + aa * d
            if (abs(d) < tiny) d = tiny
            c = 1.0 + aa / c
            if (abs(c) < tiny) c = tiny
            d = 1.0 / d
            val del = d * c
            h *= del
            if (abs(del - 1.0) < epsilon) break
        }
        return h
    }

    private fun lnGamma(z: Double): Double {
        val coefficients = doubleArrayOf(
            76.18009172947146,
            -86.50532032941677,
            24.01409824083091,
            -1.231739572450155,
            0.1208650973866179e-2,
            -0.5395239384953e-5,
        )
        var y = z
        val x = z
        var tmp = x + 5.5
        tmp -= (x + 0.5) * ln(tmp)
        var ser = 1.000000000190015
        for (coefficient in coefficients) {
            y += 1.0
            ser += coefficient / y
        }
        return -tmp + ln(2.5066282746310005 * ser / x)
    }

    private fun notComparable(reason: String) = BenchmarkComparison.notComparable(reason)

    private fun minutes(millis: Long): String {
        val totalSeconds = millis / 1000
        return "${totalSeconds / 60}m ${totalSeconds % 60}s"
    }

    private fun formatTemperature(deci: Int): String =
        String.format(java.util.Locale.US, "%.1f °C", deci / 10f)

    private fun formatDelta(value: Float): String =
        String.format(java.util.Locale.US, "%.1f", value)
}
