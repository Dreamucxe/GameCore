package com.gamecore.core.model

/**
 * The user's chosen priority for Smart Intelligence.
 *
 * A goal is not a device setting by itself. It changes how the recommendation engine weighs
 * evidence that is already there — which measured trade-off matters most — and it never changes
 * what the device is allowed to do. Every recommendation still has to name its evidence and pass
 * the same compatibility check regardless of goal.
 */
enum class OptimizationGoal(val label: String, val summary: String) {
    MAXIMUM_PERFORMANCE(
        "Maximum performance",
        "Prefer settings that can raise measured frame rate, and say when a setting only reduces heat.",
    ),
    STABLE_FPS(
        "Stable FPS",
        "Prefer consistent frame pacing and sustained speed over a higher peak.",
    ),
    COOLER_GAMING(
        "Cooler gaming",
        "Prefer lower temperatures and sustained clocks, accepting a smaller performance ceiling.",
    ),
    BATTERY_EFFICIENCY(
        "Battery efficiency",
        "Prefer lower power draw while keeping the game playable.",
    ),
    ;

    companion object {
        /** Reads a stored name defensively, the same way the database mappers do. */
        fun of(name: String?): OptimizationGoal =
            entries.firstOrNull { it.name == name } ?: MAXIMUM_PERFORMANCE
    }
}

/**
 * When an already-running game may trigger a fresh offline analysis.
 *
 * These intervals are checked only when the existing foreground-game detector announces a game;
 * they do not schedule a wake-up, keep a timer alive or monitor any extra application. Manual is
 * the privacy-first default.
 */
enum class IntelligenceAnalysisFrequency(
    val label: String,
    val description: String,
    val minimumIntervalMillis: Long?,
) {
    MANUAL(
        "Manual only",
        "Analyse only when you open Performance Intelligence and refresh it.",
        null,
    ),
    EVERY_GAME_LAUNCH(
        "Every game launch",
        "Run one local scan whenever a configured game is detected.",
        0L,
    ),
    DAILY(
        "Once a day",
        "Run at the first configured game launch at least 24 hours after the last analysis.",
        24L * 60L * 60L * 1_000L,
    ),
    WEEKLY(
        "Once a week",
        "Run at the first configured game launch at least seven days after the last analysis.",
        7L * 24L * 60L * 60L * 1_000L,
    ),
}

/**
 * How strongly the evidence supports a recommendation.
 *
 * [UNSUPPORTED] is separate from [NOT_RECOMMENDED]: the first means the device cannot do it at all,
 * the second means it can but the measurements say it would be a bad idea. Collapsing them would
 * offer a user a button for something their phone does not implement.
 */
enum class RecommendationClass(val label: String) {
    STRONGLY_RECOMMENDED("Strongly recommended"),
    RECOMMENDED("Recommended"),
    OPTIONAL("Optional"),
    EXPERIMENTAL("Experimental"),
    NOT_RECOMMENDED("Not recommended"),
    UNSUPPORTED("Not supported"),
    ;

    val isUsable: Boolean
        get() = this != NOT_RECOMMENDED && this != UNSUPPORTED

    /** Sort order for the recommendation list: strongest first, refused last. */
    val rank: Int
        get() = when (this) {
            STRONGLY_RECOMMENDED -> 0
            RECOMMENDED -> 1
            OPTIONAL -> 2
            EXPERIMENTAL -> 3
            NOT_RECOMMENDED -> 4
            UNSUPPORTED -> 5
        }
}

/** The real GameCore actions a recommendation can map onto. */
enum class RecommendationAction(
    val key: String,
    val title: String,
    val optimizationAction: OptimizationAction?,
    val profileField: SmartProfileField?,
) {
    PIN_PEAK_REFRESH_RATE(
        key = "refresh_peak",
        title = "Hold the highest refresh rate",
        optimizationAction = OptimizationAction.PIN_PEAK_REFRESH_RATE,
        profileField = SmartProfileField.REFRESH_RATE,
    ),
    PIN_LOWEST_REFRESH_RATE(
        key = "refresh_lowest",
        title = "Hold the lowest refresh rate",
        optimizationAction = OptimizationAction.PIN_LOWEST_REFRESH_RATE,
        profileField = SmartProfileField.REFRESH_RATE,
    ),
    ENABLE_BATTERY_SAVER(
        key = "battery_saver",
        title = "Turn on Android battery saver",
        optimizationAction = OptimizationAction.ENABLE_BATTERY_SAVER,
        profileField = null,
    ),
    KEEP_FULL_PERFORMANCE(
        key = "full_performance",
        title = "Keep full performance while this game runs",
        optimizationAction = null,
        profileField = SmartProfileField.FULL_PERFORMANCE,
    ),
    DISABLE_ANIMATIONS(
        key = "animations_off",
        title = "Switch off window animations",
        optimizationAction = OptimizationAction.DISABLE_ANIMATIONS,
        profileField = null,
    ),
    ENABLE_THERMAL_DOWNSHIFT(
        key = "thermal_downshift",
        title = "Enable thermal auto-cooling for this game",
        optimizationAction = null,
        profileField = SmartProfileField.THERMAL_DOWNSHIFT,
    ),
    SET_CPU_AFFINITY(
        key = "cpu_affinity",
        title = "Test a CPU core restriction",
        optimizationAction = null,
        profileField = SmartProfileField.CPU_AFFINITY,
    ),
    FREE_RAM_ON_LAUNCH(
        key = "free_ram_on_launch",
        title = "Close background apps when this game starts",
        optimizationAction = null,
        profileField = SmartProfileField.FREE_RAM_ON_LAUNCH,
    ),
    ;

    companion object {
        fun of(key: String?): RecommendationAction? = entries.firstOrNull { it.key == key }
    }
}

/** A game-profile field a recommendation can ask the user to review. */
enum class SmartProfileField {
    REFRESH_RATE,
    PERFORMANCE_MODE,
    FULL_PERFORMANCE,
    THERMAL_DOWNSHIFT,
    CPU_AFFINITY,
    FREE_RAM_ON_LAUNCH,
}

/** How a recommendation can be acted on, if at all. */
sealed interface RecommendationTarget {
    /** A real device setting, applied through the existing optimization engine. */
    data class DeviceAction(val action: OptimizationAction) : RecommendationTarget

    /** A game profile field. The user reviews it in the profile editor; it is never written silently. */
    data class GameProfile(val field: SmartProfileField) : RecommendationTarget

    /** Advice only. There is no operation to perform. */
    data object Information : RecommendationTarget
}

/**
 * Everything the scanner actually read, with nothing filled in for the parts it could not.
 *
 * This is a snapshot of facts, not a score. A missing field means the platform or the kernel did
 * not expose it; it never means zero. The report builder and the recommendation engine both read
 * this type, which is what keeps them from disagreeing about the device.
 */
data class DeviceScan(
    val scannedAtMillis: Long,
    val manufacturer: String,
    val model: String,
    val socManufacturer: String?,
    val socModel: String?,
    val cpuArchitecture: String,
    val coreCount: Int,
    val cpuClusters: List<CpuCluster>,
    val gpuRenderer: String?,
    val gpuVendor: String?,
    /** Current GPU clock in megahertz, or null when the kernel exposes no frequency node. */
    val gpuClockMhz: Float?,
    /** The ceiling the GPU node itself reports, in megahertz; null if it reported none. */
    val gpuMaxClockMhz: Float?,
    /** The node's full frequency table in megahertz, ascending; empty when it published none. */
    val gpuClockFrequenciesMhz: List<Float>,
    /** Where the clock figure was read from, e.g. `/sys/class/devfreq/7c.../cur_freq`; null if never read. */
    val gpuClockSource: String?,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val lowMemory: Boolean,
    val storageTotalBytes: Long,
    val storageAvailableBytes: Long,
    val displayWidthPixels: Int,
    val displayHeightPixels: Int,
    val displayRefreshRateHz: Float?,
    val supportedRefreshRates: List<Float>,
    val androidRelease: String,
    val sdkInt: Int,
    val kernelVersion: String?,
    val batteryLevelPercent: Int,
    val batteryTemperatureDeciCelsius: Int?,
    val batteryCharging: Boolean,
    val batteryPowerSave: Boolean,
    val thermalSensors: List<ThermalSensor>,
    val cpuTemperatureDeciCelsius: Int?,
    val platformThermalStatus: ThermalStatus?,
    val capabilities: DeviceCapabilities,
    val unavailableReadings: List<String>,
) {
    val ramUsedPercent: Float
        get() = if (totalRamBytes <= 0L) {
            0f
        } else {
            ((totalRamBytes - availableRamBytes).toFloat() / totalRamBytes.toFloat())
                .coerceIn(0f, 1f) * 100f
        }

    val storageUsedPercent: Float
        get() = if (storageTotalBytes <= 0L) {
            0f
        } else {
            ((storageTotalBytes - storageAvailableBytes).toFloat() / storageTotalBytes.toFloat())
                .coerceIn(0f, 1f) * 100f
        }

    /** The highest rate the panel confirms. Null when the mode list was unreadable. */
    val peakRefreshRateHz: Float? get() = supportedRefreshRates.maxOrNull()

    val hasVariableRefreshRate: Boolean get() = supportedRefreshRates.size >= 2

    val thermalClass: ThermalClassification
        get() = ThermalClassifier.classify(
            deciCelsius = cpuTemperatureDeciCelsius ?: batteryTemperatureDeciCelsius,
            sensor = if (cpuTemperatureDeciCelsius != null) {
                ThermalSensorType.CPU
            } else {
                ThermalSensorType.BATTERY
            },
            platformStatus = platformThermalStatus,
        )
}

/** What kind of sentence a [DeviceFinding] is. */
enum class FindingKind { STRENGTH, LIMITATION, OPPORTUNITY }

/**
 * One readable statement about the device, with the reading behind it.
 *
 * [evidence] is not decoration: a finding without a named source is a guess, and the report does
 * not contain guesses. The UI prints it under the sentence.
 */
data class DeviceFinding(
    val kind: FindingKind,
    val title: String,
    val detail: String,
    val evidence: String,
)

/** The device report: facts grouped into what helps, what limits, and what is worth trying. */
data class DeviceIntelligenceReport(
    val scan: DeviceScan,
    val findings: List<DeviceFinding>,
) {
    val strengths: List<DeviceFinding> get() = findings.filter { it.kind == FindingKind.STRENGTH }
    val limitations: List<DeviceFinding> get() = findings.filter { it.kind == FindingKind.LIMITATION }
    val opportunities: List<DeviceFinding> get() = findings.filter { it.kind == FindingKind.OPPORTUNITY }
}

/**
 * The measurement recipes the benchmark center can run.
 *
 * A benchmark here records a device or game that is already running. None of these profiles starts
 * a workload, raises a clock or draws frames: a phone cannot honestly benchmark a game it is not
 * rendering, and a synthetic load would measure the synthetic load. The duration is a capture
 * window, not an instruction to the device.
 */
enum class BenchmarkProfile(
    val label: String,
    val description: String,
    val defaultDurationMillis: Long,
    val needsFrameRate: Boolean,
) {
    QUICK(
        label = "Quick check",
        description = "One minute of real readings. Frame metrics appear only if the platform exposes them.",
        defaultDurationMillis = 60_000L,
        needsFrameRate = false,
    ),
    FULL_GAMING(
        label = "Full gaming check",
        description = "Ten minutes across a real play session, long enough for heat and clocks to settle.",
        defaultDurationMillis = 10 * 60_000L,
        needsFrameRate = false,
    ),
    THERMAL_STABILITY(
        label = "Thermal stability",
        description = "Fifteen minutes to show whether clocks and frame rate fall as the device heats.",
        defaultDurationMillis = 15 * 60_000L,
        needsFrameRate = false,
    ),
    MEMORY_PRESSURE(
        label = "Memory pressure",
        description = "Five minutes of memory readings, including the platform's low-memory flag.",
        defaultDurationMillis = 5 * 60_000L,
        needsFrameRate = false,
    ),
    BATTERY_EFFICIENCY(
        label = "Battery efficiency",
        description = "Twenty minutes minimum, because Android's battery counter moves in whole points.",
        defaultDurationMillis = 20 * 60_000L,
        needsFrameRate = false,
    ),
    OPTIMIZATION_COMPARISON(
        label = "Optimization comparison",
        description = "A run to compare with another run of the same game and profile after one change.",
        defaultDurationMillis = 5 * 60_000L,
        needsFrameRate = true,
    ),
}

/** One captured reading inside a benchmark run. Nulls mean the source did not report that metric. */
data class BenchmarkPoint(
    val elapsedMillis: Long,
    val cpuPercent: Float?,
    val cpuSource: String?,
    val memoryPercent: Float?,
    val memorySource: String?,
    val batteryPercent: Int?,
    val batterySource: String?,
    val charging: Boolean?,
    val temperatureDeciCelsius: Int?,
    val temperatureSource: String?,
    val refreshRateHz: Float?,
    val refreshRateSource: String?,
    val frameRate: FrameRateSample?,
    val gpuPercent: Float?,
    val gpuSource: String?,
)

/**
 * The honest summary of a capture.
 *
 * [lowestWindowFps] is the lowest average among the measurement windows, not a per-frame minimum:
 * the probe returns a window of real frames, and the app does not retain the individual intervals.
 * The label in the UI says exactly that. [averageFps] is weighted by each window's frame count, so
 * a 120-frame window counts more than a 10-frame one.
 */
data class BenchmarkSummary(
    val sampleCount: Int,
    val durationMillis: Long,
    val averageFps: Float?,
    val lowestWindowFps: Float?,
    val onePercentLowFps: Float?,
    val frameVariationPercent: Float?,
    val p95FrameMillis: Float?,
    val p99FrameMillis: Float?,
    val jankPercent: Float?,
    val averageCpuPercent: Float?,
    val peakCpuPercent: Float?,
    val cpuSource: String?,
    val averageGpuPercent: Float?,
    val peakGpuPercent: Float?,
    val gpuSource: String?,
    val averageMemoryPercent: Float?,
    val peakMemoryPercent: Float?,
    val memorySource: String?,
    val averageTemperatureDeciCelsius: Int?,
    val peakTemperatureDeciCelsius: Int?,
    val temperatureSource: String?,
    val startBatteryPercent: Int?,
    val endBatteryPercent: Int?,
    val batteryDrainPerHour: Float?,
    val batterySource: String?,
    val averageRefreshRateHz: Float?,
    val refreshRateSource: String?,
    val fpsSource: String?,
    val unavailable: List<String>,
) {
    val hasFrameRate: Boolean get() = averageFps != null

    companion object {
        val EMPTY = BenchmarkSummary(
            sampleCount = 0,
            durationMillis = 0L,
            averageFps = null,
            lowestWindowFps = null,
            onePercentLowFps = null,
            frameVariationPercent = null,
            p95FrameMillis = null,
            p99FrameMillis = null,
            jankPercent = null,
            averageCpuPercent = null,
            peakCpuPercent = null,
            cpuSource = null,
            averageGpuPercent = null,
            peakGpuPercent = null,
            gpuSource = null,
            averageMemoryPercent = null,
            peakMemoryPercent = null,
            memorySource = null,
            averageTemperatureDeciCelsius = null,
            peakTemperatureDeciCelsius = null,
            temperatureSource = null,
            startBatteryPercent = null,
            endBatteryPercent = null,
            batteryDrainPerHour = null,
            batterySource = null,
            averageRefreshRateHz = null,
            refreshRateSource = null,
            fpsSource = null,
            unavailable = emptyList(),
        )
    }
}

/** A persisted benchmark run. [summary] carries every measured figure and every absence. */
data class BenchmarkRun(
    val id: Long = 0L,
    val profile: BenchmarkProfile,
    val gamePackage: String?,
    val gameLabel: String?,
    val goal: OptimizationGoal,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val summary: BenchmarkSummary,
    val baselineRunId: Long? = null,
)

/** The direction a measured change moved in, stated in the unit it was measured in. */
data class MetricDelta(
    val label: String,
    val before: Float,
    val after: Float,
    val unit: String,
    val higherIsBetter: Boolean,
) {
    val delta: Float get() = after - before

    val percentChange: Float?
        get() = if (before == 0f) null else (delta / before) * 100f

    val isImprovement: Boolean
        get() = if (higherIsBetter) delta > 0f else delta < 0f
}

/** What comparing two real captures can honestly conclude. */
enum class ComparisonVerdict(val label: String) {
    IMPROVED("Measured improvement"),
    REGRESSED("Measured regression"),
    NEUTRAL("No meaningful change"),
    INCONCLUSIVE("Inconclusive"),
    NOT_COMPARABLE("Not comparable"),
    ;
}

/**
 * The result of comparing a baseline and a candidate capture.
 *
 * [confidencePercent] is null when no statistical comparison was possible. A verdict is never
 * produced from two numbers alone: the conditions have to match first, and a change has to clear
 * both a practical threshold and the confidence floor.
 */
data class BenchmarkComparison(
    val verdict: ComparisonVerdict,
    val deltas: List<MetricDelta>,
    val confidencePercent: Int?,
    val explanation: String,
    val conditions: List<String>,
    val baselineRunId: Long?,
    val candidateRunId: Long?,
) {
    companion object {
        fun notComparable(reason: String) = BenchmarkComparison(
            verdict = ComparisonVerdict.NOT_COMPARABLE,
            deltas = emptyList(),
            confidencePercent = null,
            explanation = reason,
            conditions = emptyList(),
            baselineRunId = null,
            candidateRunId = null,
        )
    }
}

/** One piece of evidence behind a recommendation. */
data class RecommendationEvidence(
    val source: String,
    val detail: String,
)

/**
 * A recommendation with its evidence, compatibility and confidence attached.
 *
 * [confidencePercent] is derived from named evidence counts and recorded outcomes; it is never a
 * model's opinion. The cap is 95 because a local rule set with finite history cannot honestly claim
 * certainty, and there is no 100 case.
 */
data class SmartRecommendation(
    val key: String,
    val action: RecommendationAction,
    val title: String,
    val current: String,
    val proposed: String,
    val reason: String,
    val tradeoff: String,
    val compatibility: CapabilityStatus,
    val classification: RecommendationClass,
    val confidencePercent: Int,
    val requiresTesting: Boolean,
    val target: RecommendationTarget,
    val evidence: List<RecommendationEvidence>,
    val appliesToPackage: String?,
    val profileField: SmartProfileField? = null,
    /** What the local outcome history says, when there is any. Null means no recorded outcomes. */
    val history: String? = null,
    /** The rate an apply should write, for a refresh-rate action. Null for every other action. */
    val targetRefreshRateHz: Float? = null,
    /** The affinity preset an apply should test, for a CPU-affinity action. */
    val targetCpuAffinity: CpuAffinityPreset? = null,
)

/** What happened to a recommendation, recorded locally. */
/**
 * What happened to a recommendation, recorded locally.
 *
 * [ATTEMPTED] is deliberately separate from [APPLIED]: a write Android refused, ignored or could
 * not confirm is a real event worth keeping, but it must never be counted as a setting that changed.
 * [UNKNOWN] is only used when a row written by a different build carries a decision this build does
 * not know; it prevents an unrecognised string from being mistaken for a successful application.
 */
enum class LearningDecision { GENERATED, APPLIED, ATTEMPTED, IGNORED, TESTED, UNKNOWN }

/** What a real before/after comparison said about a recommendation. */
enum class LearningOutcome { IMPROVED, NEUTRAL, REGRESSED, INCONCLUSIVE, UNKNOWN }

/** What a comparison just taught the local model, for the screen to confirm in words. */
data class LearningOutcomeRecord(
    val id: Long,
    val actionKey: String,
    val outcome: LearningOutcome,
    val metricName: String?,
    val beforeValue: Float?,
    val afterValue: Float?,
    val verdict: ComparisonVerdict,
    /** True when this run was already in the local history and nothing new was inserted. */
    val alreadyRecorded: Boolean = false,
)

/** One locally stored learning event. */
data class LearningRecord(
    val id: Long = 0L,
    val actionKey: String,
    val gamePackage: String?,
    val goal: OptimizationGoal,
    val decision: LearningDecision,
    val outcome: LearningOutcome,
    val metricName: String?,
    val beforeValue: Float?,
    val afterValue: Float?,
    val note: String?,
    val createdAtMillis: Long,
    /** The stored benchmark this measured outcome came from, or null for a decision event. */
    val sourceRunId: Long? = null,
)

/**
 * What the recorded outcomes say about one action.
 *
 * [blocked] is true only after repeated measured regressions. One bad run is not enough: the
 * engine's own rule is that a single benchmark can be a thermal fluke, so it lowers confidence
 * without refusing the action.
 */
data class LearningSummary(
    val actionKey: String,
    val generatedCount: Int,
    val appliedCount: Int,
    val attemptedCount: Int,
    val ignoredCount: Int,
    val testedCount: Int,
    val improvedCount: Int,
    val neutralCount: Int,
    val regressedCount: Int,
    val inconclusiveCount: Int,
    val confidenceAdjustment: Int,
    val blocked: Boolean,
    val explanation: String,
)

/** Progress reported while a benchmark capture runs. */
data class BenchmarkProgress(
    val profile: BenchmarkProfile,
    val elapsedMillis: Long,
    val totalMillis: Long,
    val sampleCount: Int,
    val latestPoint: BenchmarkPoint?,
) {
    val fraction: Float
        get() = if (totalMillis <= 0L) 0f else (elapsedMillis.toFloat() / totalMillis).coerceIn(0f, 1f)
}
