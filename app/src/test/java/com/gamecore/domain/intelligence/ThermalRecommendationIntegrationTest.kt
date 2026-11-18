package com.gamecore.domain.intelligence

import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.OptimizationGoal
import com.gamecore.core.model.RecommendationAction
import com.gamecore.core.model.ThermalClass
import com.gamecore.domain.thermal.DownshiftStatus
import com.gamecore.domain.thermal.ThermalDownshiftConfig
import com.gamecore.domain.thermal.ThermalDownshiftDecision
import com.gamecore.domain.thermal.ThermalDownshiftMachine
import com.gamecore.domain.thermal.ThermalDownshiftState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The H1 claim, end to end: a recommendation that is applied to a profile must give the thermal
 * machine a trigger it can act on, not just an enabled flag.
 *
 * The config below mirrors the fields [com.gamecore.domain.gaming.SmartFeatureRunner] reads from the
 * profile. The machine is then run against a reading above the recommended limit with no platform
 * status, which is the path that used to do nothing.
 */
class ThermalRecommendationIntegrationTest {

    @Test
    fun `applying the recommendation lets the machine step down above its limit`() = runTest {
        val profile = GameProfile.forGame("game.pkg", "Game").copy(targetRefreshRate = 120f)
        val recommendation = RecommendationEngine.recommend(
            scan = deviceScan(cpuTemperatureDeciCelsius = 700),
            goal = OptimizationGoal.COOLER_GAMING,
            gamePackage = "game.pkg",
            gameLabel = "Game",
            profile = profile,
        ).first { it.action == RecommendationAction.ENABLE_THERMAL_DOWNSHIFT }

        var saved: GameProfile? = null
        val applied = ThermalProfileApplier.apply(
            recommendation = recommendation,
            profile = profile,
            save = { saved = it },
        )
        assertTrue(applied is ThermalProfileApplyResult.Applied)

        val result = saved!!
        val config = ThermalDownshiftConfig(
            enabled = result.thermalDownshiftEnabled,
            temperatureLimitDeciCelsius = result.thermalLimitDeciCelsius,
            thermalStatusFloor = result.thermalStatusFloor,
            floorRateHz = result.thermalFloorRateHz
                ?: result.targetRefreshRate
                ?: error("The applied profile has no floor"),
        )
        val ladder = listOf(60f, 120f)

        // First hot tick arms the sustain timer; nothing changes before the configured 30 s.
        val (armed, first) = ThermalDownshiftMachine.evaluate(
            state = ThermalDownshiftState.initial(120f),
            config = config,
            nowMillis = 0L,
            temperatureDeciCelsius = 700,
            thermalClass = ThermalClass.HOT,
            observedRateHz = 120f,
            supportedRatesAtOrAboveFloor = ladder,
        )
        assertEquals(ThermalDownshiftDecision.NoChange, first)

        // After the sustain time the same reading steps the panel to the profile's floor.
        val (cooling, second) = ThermalDownshiftMachine.evaluate(
            state = armed,
            config = config,
            nowMillis = ThermalDownshiftConfig.DEFAULT_SUSTAIN_HOT_MILLIS,
            temperatureDeciCelsius = 700,
            thermalClass = ThermalClass.HOT,
            observedRateHz = 120f,
            supportedRatesAtOrAboveFloor = ladder,
        )
        assertTrue(second is ThermalDownshiftDecision.SetRate)
        assertEquals(60f, (second as ThermalDownshiftDecision.SetRate).targetHz, 0.001f)
        assertEquals(DownshiftStatus.COOLING, cooling.status)
    }
}
