package com.gamecore.data.database

import com.gamecore.core.model.BenchmarkProfile
import com.gamecore.core.model.BenchmarkRun
import com.gamecore.core.model.BenchmarkSummary
import com.gamecore.core.model.OptimizationGoal
import com.gamecore.core.model.TestedAction
import com.gamecore.core.model.TestedActionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartIntelligenceMapperTest {

    @Test
    fun `tested action snapshot survives an entity round trip`() {
        val run = BenchmarkRun(
            id = 7L,
            profile = BenchmarkProfile.OPTIMIZATION_COMPARISON,
            gamePackage = "game.pkg",
            gameLabel = "Game",
            goal = OptimizationGoal.STABLE_FPS,
            startedAtMillis = 1_000L,
            endedAtMillis = 2_000L,
            summary = BenchmarkSummary.EMPTY.copy(sampleCount = 20, durationMillis = 1_000L),
            baselineRunId = 3L,
            testedAction = TestedAction(
                appliedRecordId = 42L,
                actionKey = "refresh_peak",
                title = "Hold the highest refresh rate",
                gamePackage = "game.pkg",
                goal = OptimizationGoal.STABLE_FPS,
                appliedAtMillis = 900L,
                metricNames = listOf("Average FPS", "1% low FPS"),
                target = TestedActionTarget.DEVICE,
                effectConfirmedAtMillis = 900L,
            ),
        )

        val entity = SmartIntelligenceMappers.toEntity(run, listOf(60f, 61f))
        val restored = SmartIntelligenceMappers.toModel(entity)

        assertEquals(run.testedAction, restored.testedAction)
        assertEquals(listOf(60f, 61f), SmartIntelligenceMappers.windows(entity))
    }

    @Test
    fun `a legacy row with no snapshot reads back unattributed`() {
        val run = BenchmarkRun(
            id = 8L,
            profile = BenchmarkProfile.QUICK,
            gamePackage = null,
            gameLabel = null,
            goal = OptimizationGoal.MAXIMUM_PERFORMANCE,
            startedAtMillis = 1_000L,
            endedAtMillis = 2_000L,
            summary = BenchmarkSummary.EMPTY,
        )

        val entity = SmartIntelligenceMappers.toEntity(run, emptyList())

        assertNull(entity.testedActionRecordId)
        assertNull(SmartIntelligenceMappers.toModel(entity).testedAction)
    }

    @Test
    fun `a partial snapshot is not guessed into a tested action`() {
        val run = BenchmarkRun(
            id = 9L,
            profile = BenchmarkProfile.QUICK,
            gamePackage = "game.pkg",
            gameLabel = "Game",
            goal = OptimizationGoal.STABLE_FPS,
            startedAtMillis = 1_000L,
            endedAtMillis = 2_000L,
            summary = BenchmarkSummary.EMPTY,
            testedAction = TestedAction(
                appliedRecordId = 43L,
                actionKey = "refresh_peak",
                title = "Hold the highest refresh rate",
                gamePackage = "game.pkg",
                goal = OptimizationGoal.STABLE_FPS,
                appliedAtMillis = 900L,
                metricNames = listOf("Average FPS"),
                target = TestedActionTarget.DEVICE,
                effectConfirmedAtMillis = 900L,
            ),
        )

        val entity = SmartIntelligenceMappers.toEntity(run, emptyList())
            .copy(testedActionKey = null)

        assertNull(SmartIntelligenceMappers.toModel(entity).testedAction)
    }
}
