package com.gamecore.domain.intelligence

import com.gamecore.core.model.DeviceCapabilities
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.LearningDecision
import com.gamecore.core.model.LearningOutcome
import com.gamecore.core.model.LearningRecord
import com.gamecore.core.model.OptimizationGoal
import com.gamecore.core.model.RecommendationAction
import com.gamecore.core.model.RecommendationClass
import com.gamecore.core.model.RecommendationTarget
import com.gamecore.core.model.RefreshRateMechanism
import com.gamecore.core.model.GameSession
import com.gamecore.core.model.ShizukuState
import com.gamecore.core.model.ThermalClass
import com.gamecore.core.model.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertEquals(550, thermal.targetThermalLimitDeciCelsius)
        assertEquals(60f, thermal.targetThermalFloorRateHz!!, 0.001f)
        assertNull(thermal.targetThermalStatusFloor)
    }

    @Test
    fun `hot scan offers a thermal profile with a trigger and a lower floor`() {
        val result = RecommendationEngine.recommend(
            scan = deviceScan(cpuTemperatureDeciCelsius = 700),
            goal = OptimizationGoal.COOLER_GAMING,
            gamePackage = "game.pkg",
            gameLabel = "Game",
            profile = GameProfile.forGame("game.pkg", "Game"),
        )
        val thermal = result.first { it.action == RecommendationAction.ENABLE_THERMAL_DOWNSHIFT }

        assertEquals(700, thermal.targetThermalLimitDeciCelsius)
        assertEquals(60f, thermal.targetThermalFloorRateHz!!, 0.001f)
        assertNull(thermal.targetThermalStatusFloor)
        assertTrue(thermal.proposed.contains("70.0 °C"))
        assertTrue(thermal.proposed.contains("never below 60 Hz"))
        assertTrue(
            thermal.evidence.first { it.source == "Profile field" }.detail
                .contains("thermalFloorRateHz=60 Hz"),
        )
    }

    @Test
    fun `no thermal recommendation without a device-wide refresh mechanism`() {
        for (mechanism in listOf(
            RefreshRateMechanism.NONE,
            RefreshRateMechanism.WINDOW_PREFERENCE,
        )) {
            val result = RecommendationEngine.recommend(
                scan = deviceScan(
                    cpuTemperatureDeciCelsius = 700,
                    capabilities = capabilities(mechanism = mechanism),
                ),
                goal = OptimizationGoal.COOLER_GAMING,
                gamePackage = "game.pkg",
                gameLabel = "Game",
                profile = GameProfile.forGame("game.pkg", "Game"),
            )

            assertFalse(
                "mechanism=$mechanism",
                result.any { it.action == RecommendationAction.ENABLE_THERMAL_DOWNSHIFT },
            )
        }
    }

    @Test
    fun `Shizuku-backed thermal control is not offered when Shizuku is unusable`() {
        val denied = RecommendationEngine.recommend(
            scan = deviceScan(
                cpuTemperatureDeciCelsius = 700,
                capabilities = capabilities(
                    mechanism = RefreshRateMechanism.SHIZUKU_SETTINGS,
                    shizuku = ShizukuState.RUNNING_PERMISSION_DENIED,
                ),
            ),
            goal = OptimizationGoal.COOLER_GAMING,
            gamePackage = "game.pkg",
            gameLabel = "Game",
            profile = GameProfile.forGame("game.pkg", "Game"),
        )
        assertFalse(
            denied.any { it.action == RecommendationAction.ENABLE_THERMAL_DOWNSHIFT },
        )

        val granted = RecommendationEngine.recommend(
            scan = deviceScan(
                cpuTemperatureDeciCelsius = 700,
                capabilities = capabilities(
                    mechanism = RefreshRateMechanism.SHIZUKU_SETTINGS,
                    shizuku = ShizukuState.RUNNING_PERMISSION_GRANTED,
                ),
            ),
            goal = OptimizationGoal.COOLER_GAMING,
            gamePackage = "game.pkg",
            gameLabel = "Game",
            profile = GameProfile.forGame("game.pkg", "Game"),
        )
        assertTrue(
            granted.any { it.action == RecommendationAction.ENABLE_THERMAL_DOWNSHIFT },
        )
    }

    @Test
    fun `a profile already at the panel's lowest rate is not offered cooling`() {
        val result = RecommendationEngine.recommend(
            scan = deviceScan(cpuTemperatureDeciCelsius = 700),
            goal = OptimizationGoal.COOLER_GAMING,
            gamePackage = "game.pkg",
            gameLabel = "Game",
            profile = GameProfile.forGame("game.pkg", "Game").copy(targetRefreshRate = 60f),
        )

        assertFalse(result.any { it.action == RecommendationAction.ENABLE_THERMAL_DOWNSHIFT })
    }

    @Test
    fun `an enabled profile with no trigger or lower floor is repaired`() {
        val result = RecommendationEngine.recommend(
            scan = deviceScan(cpuTemperatureDeciCelsius = 700),
            goal = OptimizationGoal.COOLER_GAMING,
            gamePackage = "game.pkg",
            gameLabel = "Game",
            profile = GameProfile.forGame("game.pkg", "Game").copy(
                thermalDownshiftEnabled = true,
                targetRefreshRate = 120f,
            ),
        )
        val thermal = result.first { it.action == RecommendationAction.ENABLE_THERMAL_DOWNSHIFT }

        assertEquals(700, thermal.targetThermalLimitDeciCelsius)
        assertEquals(60f, thermal.targetThermalFloorRateHz!!, 0.001f)
        assertTrue(thermal.current.contains("no usable trigger", ignoreCase = true))
    }

    @Test
    fun `a fully configured enabled profile is not offered again`() {
        val result = RecommendationEngine.recommend(
            scan = deviceScan(cpuTemperatureDeciCelsius = 700),
            goal = OptimizationGoal.COOLER_GAMING,
            gamePackage = "game.pkg",
            gameLabel = "Game",
            profile = GameProfile.forGame("game.pkg", "Game").copy(
                thermalDownshiftEnabled = true,
                targetRefreshRate = 120f,
                thermalLimitDeciCelsius = 600,
                thermalFloorRateHz = 60f,
            ),
        )

        assertFalse(result.any { it.action == RecommendationAction.ENABLE_THERMAL_DOWNSHIFT })
    }

    @Test
    fun `applying a thermal recommendation keeps the user's trigger and floor`() {
        val result = RecommendationEngine.recommend(
            scan = deviceScan(
                cpuTemperatureDeciCelsius = 700,
                supportedRefreshRates = listOf(60f, 90f, 120f),
            ),
            goal = OptimizationGoal.COOLER_GAMING,
            gamePackage = "game.pkg",
            gameLabel = "Game",
            profile = GameProfile.forGame("game.pkg", "Game").copy(
                targetRefreshRate = 120f,
                thermalLimitDeciCelsius = 620,
                thermalFloorRateHz = 90f,
            ),
        )
        val thermal = result.first { it.action == RecommendationAction.ENABLE_THERMAL_DOWNSHIFT }

        assertEquals(620, thermal.targetThermalLimitDeciCelsius)
        assertEquals(90f, thermal.targetThermalFloorRateHz!!, 0.001f)
    }

    @Test
    fun `a status-only trigger is not offered when the platform cannot report status`() {
        val result = RecommendationEngine.recommend(
            scan = deviceScan(
                cpuTemperatureDeciCelsius = null,
                batteryTemperatureDeciCelsius = null,
                platformThermalStatus = ThermalStatus.SEVERE,
                capabilities = capabilities(thermalStatusSupported = false),
            ),
            goal = OptimizationGoal.COOLER_GAMING,
            gamePackage = "game.pkg",
            gameLabel = "Game",
            profile = GameProfile.forGame("game.pkg", "Game"),
        )

        assertFalse(result.any { it.action == RecommendationAction.ENABLE_THERMAL_DOWNSHIFT })
    }

    @Test
    fun `platform status can supply the trigger when no temperature is readable`() {
        val result = RecommendationEngine.recommend(
            scan = deviceScan(
                cpuTemperatureDeciCelsius = null,
                batteryTemperatureDeciCelsius = null,
                platformThermalStatus = ThermalStatus.SEVERE,
                capabilities = capabilities(thermalStatusSupported = true),
            ),
            goal = OptimizationGoal.COOLER_GAMING,
            gamePackage = "game.pkg",
            gameLabel = "Game",
            profile = GameProfile.forGame("game.pkg", "Game"),
        )
        val thermal = result.first { it.action == RecommendationAction.ENABLE_THERMAL_DOWNSHIFT }

        assertNull(thermal.targetThermalLimitDeciCelsius)
        assertEquals(ThermalClass.HOT, thermal.targetThermalStatusFloor)
        assertEquals(60f, thermal.targetThermalFloorRateHz!!, 0.001f)
        assertTrue(thermal.proposed.contains("Android reports throttling"))
    }

    private fun capabilities(
        mechanism: RefreshRateMechanism = RefreshRateMechanism.SYSTEM_SETTINGS,
        shizuku: ShizukuState = ShizukuState.RUNNING_PERMISSION_GRANTED,
        rates: List<Float> = listOf(60f, 120f),
        thermalStatusSupported: Boolean = false,
    ): DeviceCapabilities = DeviceCapabilities.UNKNOWN.copy(
        shizuku = shizuku,
        refreshRateMechanism = mechanism,
        supportedRefreshRates = rates,
        hasVariableRefreshRate = rates.size > 1,
        thermalStatusSupported = thermalStatusSupported,
    )
}
