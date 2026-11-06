package com.gamecore.domain.intelligence

import com.gamecore.core.model.LearningDecision
import com.gamecore.core.model.LearningOutcome
import com.gamecore.core.model.LearningRecord
import com.gamecore.core.model.OptimizationGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningEngineTest {

    @Test
    fun `generated and dismissed events do not change confidence`() {
        val summary = LearningEngine.summarize(
            listOf(
                record(LearningDecision.GENERATED, LearningOutcome.UNKNOWN),
                record(LearningDecision.IGNORED, LearningOutcome.UNKNOWN),
            ),
            ACTION,
        )

        assertEquals(1, summary.generatedCount)
        assertEquals(1, summary.ignoredCount)
        assertEquals(0, summary.confidenceAdjustment)
        assertFalse(summary.blocked)
    }

    @Test
    fun `one regression lowers confidence but does not block`() {
        val summary = LearningEngine.summarize(
            listOf(record(LearningDecision.TESTED, LearningOutcome.REGRESSED)),
            ACTION,
        )

        assertTrue(summary.confidenceAdjustment < 0)
        assertFalse(summary.blocked)
    }

    @Test
    fun `an unconfirmed attempt is kept but never counted as applied`() {
        val summary = LearningEngine.summarize(
            listOf(record(LearningDecision.ATTEMPTED, LearningOutcome.UNKNOWN)),
            ACTION,
        )

        assertEquals(1, summary.attemptedCount)
        assertEquals(0, summary.appliedCount)
        assertEquals(0, summary.confidenceAdjustment)
        assertFalse(summary.blocked)
    }

    @Test
    fun `a decision written by another build is not mistaken for an application`() {
        val summary = LearningEngine.summarize(
            listOf(record(LearningDecision.UNKNOWN, LearningOutcome.UNKNOWN)),
            ACTION,
        )

        assertEquals(0, summary.appliedCount)
        assertEquals(0, summary.confidenceAdjustment)
        assertEquals(0, summary.testedCount)
    }

    @Test
    fun `repeated regressions block only after the documented margin`() {
        val summary = LearningEngine.summarize(
            listOf(
                record(LearningDecision.TESTED, LearningOutcome.REGRESSED),
                record(LearningDecision.TESTED, LearningOutcome.REGRESSED),
                record(LearningDecision.TESTED, LearningOutcome.REGRESSED),
                record(LearningDecision.TESTED, LearningOutcome.IMPROVED),
            ),
            ACTION,
        )

        assertTrue(summary.blocked)
        assertTrue(summary.explanation.contains("not recommended"))
    }

    private fun record(decision: LearningDecision, outcome: LearningOutcome) = LearningRecord(
        actionKey = ACTION,
        gamePackage = "game.pkg",
        goal = OptimizationGoal.STABLE_FPS,
        decision = decision,
        outcome = outcome,
        metricName = null,
        beforeValue = null,
        afterValue = null,
        note = null,
        createdAtMillis = 1L,
    )

    private companion object {
        const val ACTION = "refresh_peak"
    }
}
