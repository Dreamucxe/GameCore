package com.gamecore.domain.intelligence

import com.gamecore.core.model.BenchmarkComparison
import com.gamecore.core.model.ComparisonVerdict
import com.gamecore.core.model.GameSession
import com.gamecore.core.model.LearningDecision
import com.gamecore.core.model.LearningOutcome
import com.gamecore.core.model.LearningRecord
import com.gamecore.core.model.MetricDelta
import com.gamecore.core.model.OptimizationGoal
import com.gamecore.core.model.TestedAction
import com.gamecore.core.model.TestedActionStatus
import com.gamecore.core.model.TestedActionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkAttributionTest {

    @Test
    fun `one confirmed device action is snapshotted with its exact identity`() {
        val resolution = BenchmarkAttribution.resolve(
            records = listOf(record(10L, "refresh_peak", LearningDecision.APPLIED, 1_000L)),
            gamePackage = GAME,
            goal = OptimizationGoal.STABLE_FPS,
            sessions = emptyList(),
            atMillis = 2_000L,
        )

        val tested = requireTestedAction(resolution.testedAction)
        assertEquals(10L, tested.appliedRecordId)
        assertEquals("refresh_peak", tested.actionKey)
        assertEquals(GAME, tested.gamePackage)
        assertEquals(OptimizationGoal.STABLE_FPS, tested.goal)
        assertEquals(1_000L, tested.appliedAtMillis)
        assertEquals(1_000L, tested.effectConfirmedAtMillis)
        assertEquals(TestedActionTarget.DEVICE, tested.target)
        assertEquals(BenchmarkAttribution.AVERAGE_FPS, tested.metricNames.first())
    }

    @Test
    fun `profile action stays pending until a game session starts after it`() {
        val record = record(11L, "thermal_downshift", LearningDecision.APPLIED, 1_000L)

        val pending = BenchmarkAttribution.resolve(
            records = listOf(record),
            gamePackage = GAME,
            goal = OptimizationGoal.STABLE_FPS,
            sessions = listOf(session(startedAt = 500L)),
            atMillis = 2_000L,
        )
        assertNull(pending.testedAction)
        assertEquals(
            TestedActionStatus.PENDING_GAME_START,
            pending.candidates.single().status,
        )

        val effective = BenchmarkAttribution.resolve(
            records = listOf(record),
            gamePackage = GAME,
            goal = OptimizationGoal.STABLE_FPS,
            sessions = listOf(session(startedAt = 1_500L)),
            atMillis = 2_000L,
        )
        val tested = requireTestedAction(effective.testedAction)
        assertEquals(TestedActionTarget.PROFILE, tested.target)
        assertEquals(1_500L, tested.effectConfirmedAtMillis)
    }

    @Test
    fun `multiple consecutive device actions in the window are ambiguous`() {
        val resolution = BenchmarkAttribution.resolve(
            records = listOf(
                record(20L, "refresh_peak", LearningDecision.APPLIED, 1_000L),
                record(21L, "battery_saver", LearningDecision.APPLIED, 2_000L),
            ),
            gamePackage = GAME,
            goal = OptimizationGoal.STABLE_FPS,
            sessions = emptyList(),
            atMillis = 3_000L,
        )

        assertNull(resolution.testedAction)
        assertEquals(2, resolution.candidates.count { it.status == TestedActionStatus.AMBIGUOUS })
        assertTrue(resolution.reason!!.contains("More than one"))
    }

    @Test
    fun `multiple consecutive profile actions in the window are ambiguous when sessions exist`() {
        val resolution = BenchmarkAttribution.resolve(
            records = listOf(
                record(20L, "thermal_downshift", LearningDecision.APPLIED, 1_000L),
                record(21L, "full_performance", LearningDecision.APPLIED, 2_000L),
            ),
            gamePackage = GAME,
            goal = OptimizationGoal.STABLE_FPS,
            sessions = listOf(session(startedAt = 3_000L)),
            atMillis = 4_000L,
        )

        assertNull(resolution.testedAction)
        assertEquals(2, resolution.candidates.count { it.status == TestedActionStatus.AMBIGUOUS })
        assertTrue(resolution.reason!!.contains("More than one"))
    }

    @Test
    fun `a later action in the same setting supersedes the earlier one`() {
        val resolution = BenchmarkAttribution.resolve(
            records = listOf(
                record(30L, "refresh_peak", LearningDecision.APPLIED, 1_000L),
                record(31L, "refresh_lowest", LearningDecision.APPLIED, 2_000L),
            ),
            gamePackage = GAME,
            goal = OptimizationGoal.STABLE_FPS,
            sessions = emptyList(),
            atMillis = 3_000L,
        )

        val tested = requireTestedAction(resolution.testedAction)
        assertEquals(31L, tested.appliedRecordId)
        assertEquals(
            TestedActionStatus.SUPERSEDED,
            resolution.candidates.single { it.recordId == 30L }.status,
        )
    }

    @Test
    fun `failed and unconfirmed applications are never eligible`() {
        val failed = BenchmarkAttribution.resolve(
            records = listOf(record(40L, "refresh_peak", LearningDecision.ATTEMPTED, 1_000L)),
            gamePackage = GAME,
            goal = OptimizationGoal.STABLE_FPS,
            sessions = emptyList(),
            atMillis = 2_000L,
        )
        assertNull(failed.testedAction)
        assertEquals(TestedActionStatus.FAILED, failed.candidates.single().status)

        val unconfirmed = BenchmarkAttribution.resolve(
            records = listOf(record(41L, "refresh_peak", LearningDecision.UNCONFIRMED, 1_000L)),
            gamePackage = GAME,
            goal = OptimizationGoal.STABLE_FPS,
            sessions = emptyList(),
            atMillis = 2_000L,
        )
        assertNull(unconfirmed.testedAction)
        assertEquals(TestedActionStatus.UNCONFIRMED, unconfirmed.candidates.single().status)
        assertTrue(unconfirmed.reason!!.contains("could not be confirmed"))
    }

    @Test
    fun `an unconfirmed write blocks attribution even when another change is confirmed`() {
        val resolution = BenchmarkAttribution.resolve(
            records = listOf(
                record(50L, "refresh_peak", LearningDecision.APPLIED, 1_000L),
                record(51L, "animations_off", LearningDecision.UNCONFIRMED, 1_500L),
            ),
            gamePackage = GAME,
            goal = OptimizationGoal.STABLE_FPS,
            sessions = emptyList(),
            atMillis = 2_000L,
        )

        assertNull(resolution.testedAction)
        assertTrue(resolution.reason!!.contains("could not be confirmed"))
    }

    @Test
    fun `a run with no applied action has no tested action`() {
        val resolution = BenchmarkAttribution.resolve(
            records = emptyList(),
            gamePackage = GAME,
            goal = OptimizationGoal.STABLE_FPS,
            sessions = emptyList(),
            atMillis = 2_000L,
        )

        assertNull(resolution.testedAction)
        assertTrue(resolution.candidates.isEmpty())
        assertTrue(resolution.reason!!.contains("No confirmed optimization"))
    }

    @Test
    fun `wrong game goal and unknown legacy keys are not eligible`() {
        val resolution = BenchmarkAttribution.resolve(
            records = listOf(
                record(60L, "refresh_peak", LearningDecision.APPLIED, 1_000L, gamePackage = "other.game"),
                record(61L, "refresh_peak", LearningDecision.APPLIED, 1_100L, goal = OptimizationGoal.BATTERY_EFFICIENCY),
                record(62L, "legacy_key", LearningDecision.APPLIED, 1_200L),
            ),
            gamePackage = GAME,
            goal = OptimizationGoal.STABLE_FPS,
            sessions = emptyList(),
            atMillis = 2_000L,
        )

        assertNull(resolution.testedAction)
        assertEquals(TestedActionStatus.WRONG_GOAL, resolution.candidates.single { it.recordId == 61L }.status)
        assertEquals(TestedActionStatus.UNKNOWN_ACTION, resolution.candidates.single { it.recordId == 62L }.status)
    }

    @Test
    fun `a change during the capture is contamination and a later change is not`() {
        val tested = testedAction("refresh_peak", listOf(BenchmarkAttribution.AVERAGE_FPS))

        val during = BenchmarkAttribution.contamination(
            records = listOf(record(70L, "animations_off", LearningDecision.APPLIED, 1_500L)),
            testedAction = tested,
            gamePackage = GAME,
            endedAtMillis = 2_000L,
        )
        assertNotNull(during)

        val after = BenchmarkAttribution.contamination(
            records = listOf(record(71L, "animations_off", LearningDecision.APPLIED, 2_500L)),
            testedAction = tested,
            gamePackage = GAME,
            endedAtMillis = 2_000L,
        )
        assertNull(after)
    }

    @Test
    fun `a failed attempt during the capture does not contaminate it`() {
        val tested = testedAction("refresh_peak", listOf(BenchmarkAttribution.AVERAGE_FPS))

        val contamination = BenchmarkAttribution.contamination(
            records = listOf(record(80L, "animations_off", LearningDecision.ATTEMPTED, 1_500L)),
            testedAction = tested,
            gamePackage = GAME,
            endedAtMillis = 2_000L,
        )

        assertNull(contamination)
    }

    @Test
    fun `evidence uses the action's own metric rather than the fps verdict`() {
        val battery = testedAction(
            "battery_saver",
            listOf(BenchmarkAttribution.BATTERY_DRAIN, BenchmarkAttribution.TEMPERATURE),
        )
        val comparison = comparison(
            verdict = ComparisonVerdict.REGRESSED,
            deltas = listOf(
                delta(BenchmarkAttribution.AVERAGE_FPS, 60f, 50f, higherIsBetter = true),
                delta(BenchmarkAttribution.BATTERY_DRAIN, 20f, 10f, higherIsBetter = false),
            ),
        )

        val evidence = requireEvidence(BenchmarkAttribution.evidence(battery, comparison))
        assertEquals(BenchmarkAttribution.BATTERY_DRAIN, evidence.metricName)
        assertEquals(LearningOutcome.IMPROVED, evidence.outcome)
        assertEquals(20f, evidence.beforeValue, 0.001f)
        assertEquals(10f, evidence.afterValue, 0.001f)
    }

    @Test
    fun `average fps evidence follows the statistical verdict`() {
        val refresh = testedAction(
            "refresh_peak",
            listOf(BenchmarkAttribution.AVERAGE_FPS),
        )
        val comparison = comparison(
            verdict = ComparisonVerdict.IMPROVED,
            deltas = listOf(delta(BenchmarkAttribution.AVERAGE_FPS, 60f, 70f, higherIsBetter = true)),
        )

        val evidence = requireEvidence(BenchmarkAttribution.evidence(refresh, comparison))
        assertEquals(LearningOutcome.IMPROVED, evidence.outcome)
    }

    @Test
    fun `evidence refuses when no relevant metric was measured or the runs are incomparable`() {
        val thermal = testedAction(
            "thermal_downshift",
            listOf(BenchmarkAttribution.TEMPERATURE),
        )
        val noMetric = comparison(
            verdict = ComparisonVerdict.NEUTRAL,
            deltas = listOf(delta(BenchmarkAttribution.AVERAGE_FPS, 60f, 60f, higherIsBetter = true)),
        )
        assertNull(BenchmarkAttribution.evidence(thermal, noMetric))

        val incomparable = comparison(
            verdict = ComparisonVerdict.NOT_COMPARABLE,
            deltas = listOf(delta(BenchmarkAttribution.TEMPERATURE, 500f, 450f, higherIsBetter = false)),
        )
        assertNull(BenchmarkAttribution.evidence(thermal, incomparable))
    }

    @Test
    fun `an inconclusive comparison does not become a measured improvement`() {
        val thermal = testedAction(
            "thermal_downshift",
            listOf(BenchmarkAttribution.TEMPERATURE),
        )
        val comparison = comparison(
            verdict = ComparisonVerdict.INCONCLUSIVE,
            deltas = listOf(delta(BenchmarkAttribution.TEMPERATURE, 500f, 450f, higherIsBetter = false)),
        )

        val evidence = requireEvidence(BenchmarkAttribution.evidence(thermal, comparison))
        assertEquals(LearningOutcome.INCONCLUSIVE, evidence.outcome)
    }

    @Test
    fun `the recorded evidence is attributed to the tested action in learning`() {
        val tested = testedAction("thermal_downshift", listOf(BenchmarkAttribution.TEMPERATURE))
        val evidence = requireEvidence(
            BenchmarkAttribution.evidence(
                tested,
                comparison(
                    verdict = ComparisonVerdict.NEUTRAL,
                    deltas = listOf(
                        delta(BenchmarkAttribution.TEMPERATURE, 500f, 450f, higherIsBetter = false),
                    ),
                ),
            ),
        )
        val record = LearningRecord(
            actionKey = tested.actionKey,
            gamePackage = tested.gamePackage,
            goal = tested.goal,
            decision = LearningDecision.TESTED,
            outcome = evidence.outcome,
            metricName = evidence.metricName,
            beforeValue = evidence.beforeValue,
            afterValue = evidence.afterValue,
            note = evidence.verdict.label,
            createdAtMillis = 3_000L,
            sourceRunId = 99L,
        )

        val summary = LearningEngine.summarize(listOf(record), tested.actionKey)

        assertEquals(1, summary.testedCount)
        assertEquals(1, summary.improvedCount)
        assertTrue(summary.confidenceAdjustment > 0)
        assertEquals(0, summary.attemptedCount)
    }

    private fun record(
        id: Long,
        actionKey: String,
        decision: LearningDecision,
        createdAtMillis: Long,
        goal: OptimizationGoal = OptimizationGoal.STABLE_FPS,
        gamePackage: String? = GAME,
    ) = LearningRecord(
        id = id,
        actionKey = actionKey,
        gamePackage = gamePackage,
        goal = goal,
        decision = decision,
        outcome = LearningOutcome.UNKNOWN,
        metricName = null,
        beforeValue = null,
        afterValue = null,
        note = null,
        createdAtMillis = createdAtMillis,
    )

    private fun session(
        startedAt: Long,
        endedAt: Long? = null,
    ) = GameSession(
        id = 1L,
        packageName = GAME,
        gameLabel = "Game",
        startedAtMillis = startedAt,
        endedAtMillis = endedAt,
        batteryStartPercent = 80,
    )

    private fun testedAction(actionKey: String, metricNames: List<String>) = TestedAction(
        appliedRecordId = 1L,
        actionKey = actionKey,
        title = actionKey,
        gamePackage = GAME,
        goal = OptimizationGoal.STABLE_FPS,
        appliedAtMillis = 1_000L,
        metricNames = metricNames,
        target = TestedActionTarget.DEVICE,
        effectConfirmedAtMillis = 1_000L,
    )

    private fun comparison(
        verdict: ComparisonVerdict,
        deltas: List<MetricDelta>,
    ) = BenchmarkComparison(
        verdict = verdict,
        deltas = deltas,
        confidencePercent = 95,
        explanation = "test",
        conditions = emptyList(),
        baselineRunId = 1L,
        candidateRunId = 2L,
    )

    private fun delta(
        label: String,
        before: Float,
        after: Float,
        higherIsBetter: Boolean,
    ) = MetricDelta(
        label = label,
        before = before,
        after = after,
        unit = "",
        higherIsBetter = higherIsBetter,
    )

    private fun requireTestedAction(value: TestedAction?): TestedAction {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }

    private fun requireEvidence(value: TestedActionResult?): TestedActionResult {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }

    private companion object {
        const val GAME = "game.pkg"
    }
}
