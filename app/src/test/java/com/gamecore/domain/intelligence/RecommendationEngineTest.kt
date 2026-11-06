package com.gamecore.domain.intelligence

import com.gamecore.core.model.LearningDecision
import com.gamecore.core.model.LearningOutcome
import com.gamecore.core.model.LearningRecord
import com.gamecore.core.model.OptimizationGoal
import com.gamecore.core.model.RecommendationAction
import com.gamecore.core.model.RecommendationClass
import com.gamecore.core.model.RecommendationTarget
import com.gamecore.core.model.RefreshRateMechanism
import com.gamecore.core.model.GameSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {

    @Test
    fun `performance goal proposes verified peak rate without claiming fps`() {
        val result = RecommendationEngine.recommend(
            scan = deviceScan(currentRefreshRate = 60f),
            goal = OptimizationGoal.MAXIMUM_PERFORMANCE,
        )
        val refresh = result.firstOrNull { it.action == RecommendationAction.PIN_PEAK_REFRESH_RATE }

        assertNotNull(refresh)
        assertEquals(120f, refresh!!.targetRefreshRateHz!!, 0.001f)
        assertTrue(refresh.reason.contains("does not", ignoreCase = true))
        assertTrue(refresh.target is RecommendationTarget.DeviceAction)
    }

    @Test
    fun `no game selection never emits a profile target`() {
        val result = RecommendationEngine.recommend(
            scan = deviceScan(cpuTemperatureDeciCelsius = 700),
            goal = OptimizationGoal.COOLER_GAMING,
            gamePackage = null,
        )

        assertFalse(result.any { it.target is RecommendationTarget.GameProfile })
    }

    @Test
    fun `a GameCore-window-only refresh preference is not offered as a game action`() {
        val scan = deviceScan(
            capabilities = com.gamecore.core.model.DeviceCapabilities.UNKNOWN.copy(
                refreshRateMechanism = RefreshRateMechanism.WINDOW_PREFERENCE,
                supportedRefreshRates = listOf(60f, 120f),
                hasVariableRefreshRate = true,
            ),
        )

        val result = RecommendationEngine.recommend(scan, OptimizationGoal.MAXIMUM_PERFORMANCE)

        assertFalse(result.any {
            it.action == RecommendationAction.PIN_PEAK_REFRESH_RATE ||
                it.action == RecommendationAction.PIN_LOWEST_REFRESH_RATE
        })
    }

    @Test
    fun `repeated measured regressions mark an otherwise supported action not recommended`() {
        val records = List(3) {
            LearningRecord(
                actionKey = RecommendationAction.PIN_PEAK_REFRESH_RATE.key,
                gamePackage = null,
                goal = OptimizationGoal.MAXIMUM_PERFORMANCE,
                decision = LearningDecision.TESTED,
                outcome = LearningOutcome.REGRESSED,
                metricName = "Average FPS",
                beforeValue = 60f,
                afterValue = 55f,
                note = null,
                createdAtMillis = it.toLong(),
            )
        }
        val result = RecommendationEngine.recommend(
            scan = deviceScan(currentRefreshRate = 60f),
            goal = OptimizationGoal.MAXIMUM_PERFORMANCE,
            learning = LearningEngine.summarizeAll(records),
        )
        val refresh = result.first { it.action == RecommendationAction.PIN_PEAK_REFRESH_RATE }

        assertEquals(RecommendationClass.NOT_RECOMMENDED, refresh.classification)
        assertTrue(refresh.history!!.contains("regressions"))
    }

    @Test
    fun `recurring measured session heat can justify per-game thermal advice when scan is cool`() {
        val sessions = listOf(
            GameSession(
                packageName = "game.pkg",
                gameLabel = "Game",
                startedAtMillis = 0L,
                endedAtMillis = 600_000L,
                batteryStartPercent = 80,
                batteryEndPercent = 75,
                averageTemperatureDeciCelsius = 590,
                peakTemperatureDeciCelsius = 650,
                sampleCount = 100,
            ),
        )

        val result = RecommendationEngine.recommend(
            scan = deviceScan(cpuTemperatureDeciCelsius = 400),
            goal = OptimizationGoal.STABLE_FPS,
            gamePackage = "game.pkg",
            gameLabel = "Game",
            sessions = sessions,
        )

        val thermal = result.firstOrNull {
            it.action == RecommendationAction.ENABLE_THERMAL_DOWNSHIFT
        }
        assertNotNull(thermal)
        assertTrue(thermal!!.evidence.any { it.source == "Gaming history" })
        assertTrue(thermal.proposed.contains("55.0 °C"))
    }
}
