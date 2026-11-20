package com.gamecore.data.database

import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.model.BenchmarkProfile
import com.gamecore.core.model.BenchmarkRun
import com.gamecore.core.model.BenchmarkSummary
import com.gamecore.core.model.LearningDecision
import com.gamecore.core.model.LearningOutcome
import com.gamecore.core.model.LearningRecord
import com.gamecore.core.model.OptimizationGoal
import com.gamecore.core.model.TestedAction
import com.gamecore.core.model.TestedActionTarget

/**
 * Entity ↔ model conversion for the Smart Intelligence tables.
 *
 * The same two rules as [Mappers]: enum names are parsed defensively so a row written by a newer
 * build does not crash an older one, and third-party text is sanitised on the way in. A benchmark's
 * game label comes from the package manager and is drawn on screen, so it crosses the same boundary
 * as a profile label.
 */
internal object SmartIntelligenceMappers {

    // ------------------------------------------------------------------------------ benchmarks

    fun toEntity(
        run: BenchmarkRun,
        windows: List<Float>,
        windowFrames: List<Int>? = null,
    ): SmartBenchmarkEntity {
        val summary = run.summary
        return SmartBenchmarkEntity(
            id = run.id,
            profile = run.profile.name,
            gamePackage = run.gamePackage,
            gameLabel = run.gameLabel?.let { TextSanitizer.sanitizeName(it) },
            goal = run.goal.name,
            startedAtMillis = run.startedAtMillis,
            endedAtMillis = run.endedAtMillis,
            sampleCount = summary.sampleCount,
            durationMillis = summary.durationMillis,
            averageFps = summary.averageFps,
            lowestWindowFps = summary.lowestWindowFps,
            onePercentLowFps = summary.onePercentLowFps,
            frameVariationPercent = summary.frameVariationPercent,
            p95FrameMillis = summary.p95FrameMillis,
            p99FrameMillis = summary.p99FrameMillis,
            jankPercent = summary.jankPercent,
            averageCpuPercent = summary.averageCpuPercent,
            peakCpuPercent = summary.peakCpuPercent,
            cpuSource = summary.cpuSource,
            averageGpuPercent = summary.averageGpuPercent,
            peakGpuPercent = summary.peakGpuPercent,
            gpuSource = summary.gpuSource,
            averageMemoryPercent = summary.averageMemoryPercent,
            peakMemoryPercent = summary.peakMemoryPercent,
            memorySource = summary.memorySource,
            averageTemperatureDeciCelsius = summary.averageTemperatureDeciCelsius,
            peakTemperatureDeciCelsius = summary.peakTemperatureDeciCelsius,
            temperatureSource = summary.temperatureSource,
            startBatteryPercent = summary.startBatteryPercent,
            endBatteryPercent = summary.endBatteryPercent,
            batteryDrainPerHour = summary.batteryDrainPerHour,
            batterySource = summary.batterySource,
            averageRefreshRateHz = summary.averageRefreshRateHz,
            refreshRateSource = summary.refreshRateSource,
            fpsSource = summary.fpsSource,
            unavailable = summary.unavailable.joinToString("\n"),
            fpsWindows = encodeWindows(windows, windowFrames),
            baselineRunId = run.baselineRunId,
            testedActionRecordId = run.testedAction?.appliedRecordId,
            testedActionKey = run.testedAction?.actionKey,
            testedActionTitle = run.testedAction?.title,
            testedActionGamePackage = run.testedAction?.gamePackage,
            testedActionGoal = run.testedAction?.goal?.name,
            testedActionAppliedAtMillis = run.testedAction?.appliedAtMillis,
            testedActionMetrics = run.testedAction?.metricNames?.joinToString(METRIC_SEPARATOR),
            testedActionTarget = run.testedAction?.target?.name,
            testedActionEffectConfirmedAtMillis = run.testedAction?.effectConfirmedAtMillis,
        )
    }

    fun toModel(entity: SmartBenchmarkEntity): BenchmarkRun = BenchmarkRun(
        id = entity.id,
        profile = BenchmarkProfile.entries.firstOrNull { it.name == entity.profile }
            ?: BenchmarkProfile.QUICK,
        gamePackage = entity.gamePackage,
        gameLabel = entity.gameLabel,
        goal = OptimizationGoal.of(entity.goal),
        startedAtMillis = entity.startedAtMillis,
        endedAtMillis = entity.endedAtMillis,
        summary = BenchmarkSummary(
            sampleCount = entity.sampleCount,
            durationMillis = entity.durationMillis,
            averageFps = entity.averageFps,
            lowestWindowFps = entity.lowestWindowFps,
            onePercentLowFps = entity.onePercentLowFps,
            frameVariationPercent = entity.frameVariationPercent,
            p95FrameMillis = entity.p95FrameMillis,
            p99FrameMillis = entity.p99FrameMillis,
            jankPercent = entity.jankPercent,
            averageCpuPercent = entity.averageCpuPercent,
            peakCpuPercent = entity.peakCpuPercent,
            cpuSource = entity.cpuSource,
            averageGpuPercent = entity.averageGpuPercent,
            peakGpuPercent = entity.peakGpuPercent,
            gpuSource = entity.gpuSource,
            averageMemoryPercent = entity.averageMemoryPercent,
            peakMemoryPercent = entity.peakMemoryPercent,
            memorySource = entity.memorySource,
            averageTemperatureDeciCelsius = entity.averageTemperatureDeciCelsius,
            peakTemperatureDeciCelsius = entity.peakTemperatureDeciCelsius,
            temperatureSource = entity.temperatureSource,
            startBatteryPercent = entity.startBatteryPercent,
            endBatteryPercent = entity.endBatteryPercent,
            batteryDrainPerHour = entity.batteryDrainPerHour,
            batterySource = entity.batterySource,
            averageRefreshRateHz = entity.averageRefreshRateHz,
            refreshRateSource = entity.refreshRateSource,
            fpsSource = entity.fpsSource,
            unavailable = entity.unavailable
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
        ),
        baselineRunId = entity.baselineRunId,
        testedAction = testedAction(entity),
    )

    /**
     * Rebuilds the snapshot only when every field is present.
     *
     * A row written before schema 24 has all nulls and reads as unattributed. A row with only some
     * of the columns set is treated the same way: a partial identity is not an identity.
     */
    private fun testedAction(entity: SmartBenchmarkEntity): TestedAction? {
        val recordId = entity.testedActionRecordId ?: return null
        val actionKey = entity.testedActionKey ?: return null
        val title = entity.testedActionTitle ?: return null
        val goalName = entity.testedActionGoal ?: return null
        val appliedAt = entity.testedActionAppliedAtMillis ?: return null
        val metrics = entity.testedActionMetrics
            ?.split(METRIC_SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val target = entity.testedActionTarget
            ?.let { name -> TestedActionTarget.entries.firstOrNull { it.name == name } }
            ?: return null
        val confirmedAt = entity.testedActionEffectConfirmedAtMillis ?: return null
        val goal = OptimizationGoal.entries.firstOrNull { it.name == goalName } ?: return null
        return TestedAction(
            appliedRecordId = recordId,
            actionKey = actionKey,
            title = title,
            gamePackage = entity.testedActionGamePackage,
            goal = goal,
            appliedAtMillis = appliedAt,
            metricNames = metrics,
            target = target,
            effectConfirmedAtMillis = confirmedAt,
        )
    }

    /** The per-window FPS sample set. Empty when frame timing was unavailable. */
    fun windows(entity: SmartBenchmarkEntity): List<Float> =
        parseWindows(entity.fpsWindows).map { it.first }

    /**
     * The real frame count behind each entry in [windows], when the row carries them.
     *
     * Rows written before weights were stored, or rows where any window lacks a count, return
     * null rather than a fabricated 1 per window: an unknown weight is not evidence that every
     * window had the same number of frames. The comparison then falls back to the summary's
     * frame-weighted average for its delta and an unweighted t-test.
     */
    fun windowFrames(entity: SmartBenchmarkEntity): List<Int>? {
        val parsed = parseWindows(entity.fpsWindows)
        if (parsed.isEmpty() || parsed.any { it.second == null }) return null
        return parsed.map { it.second!! }
    }

    /**
     * `fps:frames` per window when every count is known, otherwise the legacy plain `fps` list.
     *
     * The column is unchanged; only the token shape is extended. This build reads both shapes,
     * so rows captured before weights existed keep working.
     */
    private fun encodeWindows(windows: List<Float>, windowFrames: List<Int>?): String =
        if (windowFrames != null &&
            windowFrames.size == windows.size &&
            windowFrames.all { it > 0 }
        ) {
            windows.indices.joinToString(",") { index -> "${windows[index]}:${windowFrames[index]}" }
        } else {
            windows.joinToString(",")
        }

    private fun parseWindows(raw: String): List<Pair<Float, Int?>> =
        raw.split(',')
            .mapNotNull { token ->
                val trimmed = token.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val parts = trimmed.split(':')
                val fps = parts[0].toFloatOrNull()
                    ?.takeIf { it > 0f && it.isFinite() }
                    ?: return@mapNotNull null
                val frames = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
                fps to frames
            }

    private const val METRIC_SEPARATOR = "|"

    // -------------------------------------------------------------------------------- learning

    fun toEntity(record: LearningRecord): SmartLearningRecordEntity = SmartLearningRecordEntity(
        id = record.id,
        actionKey = record.actionKey,
        gamePackage = record.gamePackage,
        goal = record.goal.name,
        decision = record.decision.name,
        outcome = record.outcome.name,
        metricName = record.metricName,
        beforeValue = record.beforeValue,
        afterValue = record.afterValue,
        note = record.note?.let { TextSanitizer.sanitizeName(it) },
        sourceRunId = record.sourceRunId,
        createdAtMillis = record.createdAtMillis,
    )

    fun toModel(entity: SmartLearningRecordEntity): LearningRecord = LearningRecord(
        id = entity.id,
        actionKey = entity.actionKey,
        gamePackage = entity.gamePackage,
        goal = OptimizationGoal.of(entity.goal),
        decision = LearningDecision.entries.firstOrNull { it.name == entity.decision }
            ?: LearningDecision.UNKNOWN,
        outcome = LearningOutcome.entries.firstOrNull { it.name == entity.outcome }
            ?: LearningOutcome.UNKNOWN,
        metricName = entity.metricName,
        beforeValue = entity.beforeValue,
        afterValue = entity.afterValue,
        note = entity.note,
        createdAtMillis = entity.createdAtMillis,
        sourceRunId = entity.sourceRunId,
    )
}
