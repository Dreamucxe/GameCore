package com.gamecore.domain.intelligence

import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.RecommendationAction
import com.gamecore.core.model.RecommendationClass
import com.gamecore.core.model.RecommendationTarget
import com.gamecore.core.model.SmartProfileField
import com.gamecore.core.model.SmartRecommendation
import com.gamecore.core.model.ThermalClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the seam between a thermal recommendation and the profile it writes.
 *
 * The recommendation is only worth applying when the saved profile carries a trigger the thermal
 * machine can act on, plus a floor below the rate the session starts at. These tests cover the
 * meaningful save, the no-op, the trigger-less recommendation and a failed save — the four paths
 * that decide whether the caller may record an `APPLIED` outcome.
 */
class ThermalProfileApplierTest {

    private fun recommendation(
        limit: Int? = 600,
        status: ThermalClass? = null,
        floor: Float? = 60f,
        action: RecommendationAction = RecommendationAction.ENABLE_THERMAL_DOWNSHIFT,
    ) = SmartRecommendation(
        key = RecommendationAction.ENABLE_THERMAL_DOWNSHIFT.key,
        action = action,
        title = "Cool the device when it gets hot",
        current = "Off for this game's profile",
        proposed = "Downshift at 60.0 °C",
        reason = "The device is warm.",
        tradeoff = "Frames may drop earlier.",
        compatibility = CapabilityStatus.AVAILABLE,
        classification = RecommendationClass.RECOMMENDED,
        confidencePercent = 80,
        requiresTesting = false,
        target = RecommendationTarget.GameProfile(SmartProfileField.THERMAL_DOWNSHIFT),
        evidence = emptyList(),
        appliesToPackage = "game.pkg",
        profileField = SmartProfileField.THERMAL_DOWNSHIFT,
        targetThermalLimitDeciCelsius = limit,
        targetThermalStatusFloor = status,
        targetThermalFloorRateHz = floor,
    )

    private fun profile() = GameProfile.forGame("game.pkg", "Game").copy(
        targetRefreshRate = 120f,
    )

    @Test
    fun `a trigger and a lower floor are written to the profile`() = runTest {
        val profile = profile()
        var saved: GameProfile? = null

        val result = ThermalProfileApplier.apply(
            recommendation = recommendation(limit = 600, floor = 60f),
            profile = profile,
            save = { saved = it },
        )

        assertTrue(result is ThermalProfileApplyResult.Applied)
        assertEquals(profile.copy(
            thermalDownshiftEnabled = true,
            thermalLimitDeciCelsius = 600,
            thermalFloorRateHz = 60f,
        ), saved)
        assertEquals(120f, saved!!.targetRefreshRate!!, 0.001f)
    }

    @Test
    fun `an enabled profile with a floor but no trigger is repaired`() = runTest {
        val profile = profile().copy(
            thermalDownshiftEnabled = true,
            thermalFloorRateHz = 60f,
        )
        var saved: GameProfile? = null

        val result = ThermalProfileApplier.apply(
            recommendation = recommendation(limit = 600, floor = 60f),
            profile = profile,
            save = { saved = it },
        )

        assertTrue(result is ThermalProfileApplyResult.Applied)
        assertEquals(true, saved!!.thermalDownshiftEnabled)
        assertEquals(600, saved!!.thermalLimitDeciCelsius)
        assertEquals(60f, saved!!.thermalFloorRateHz!!, 0.001f)
    }

    @Test
    fun `a recommendation with no usable trigger is rejected before saving`() = runTest {
        var saves = 0

        val result = ThermalProfileApplier.apply(
            recommendation = recommendation(limit = null, status = null, floor = 60f),
            profile = profile(),
            save = { saves++ },
        )

        assertEquals(ThermalProfileApplyResult.NoUsableTrigger, result)
        assertEquals(0, saves)
    }

    @Test
    fun `an already configured profile is a no-op and is not saved again`() = runTest {
        val configured = profile().copy(
            thermalDownshiftEnabled = true,
            thermalLimitDeciCelsius = 600,
            thermalFloorRateHz = 60f,
        )
        var saves = 0

        val result = ThermalProfileApplier.apply(
            recommendation = recommendation(limit = 600, floor = 60f),
            profile = configured,
            save = { saves++ },
        )

        assertEquals(ThermalProfileApplyResult.AlreadyConfigured, result)
        assertEquals(0, saves)
    }

    @Test
    fun `a status-only trigger is written when no temperature is recommended`() = runTest {
        var saved: GameProfile? = null

        val result = ThermalProfileApplier.apply(
            recommendation = recommendation(limit = null, status = ThermalClass.HOT, floor = 60f),
            profile = profile(),
            save = { saved = it },
        )

        assertTrue(result is ThermalProfileApplyResult.Applied)
        assertEquals(ThermalClass.HOT, saved!!.thermalStatusFloor)
        assertNull(saved!!.thermalLimitDeciCelsius)
        assertEquals(60f, saved!!.thermalFloorRateHz!!, 0.001f)
    }

    @Test
    fun `a trigger already on the profile is not overwritten`() = runTest {
        val profile = profile().copy(thermalLimitDeciCelsius = 620)
        var saved: GameProfile? = null

        val result = ThermalProfileApplier.apply(
            recommendation = recommendation(limit = 600, floor = 60f),
            profile = profile,
            save = { saved = it },
        )

        assertTrue(result is ThermalProfileApplyResult.Applied)
        assertEquals(620, saved!!.thermalLimitDeciCelsius)
        assertEquals(60f, saved!!.thermalFloorRateHz!!, 0.001f)
    }

    @Test
    fun `a floor is only usable when the panel advertises it below the start rate`() {
        assertTrue(
            ThermalProfileApplier.isUsableFloor(60f, 120f, listOf(60f, 90f, 120f)),
        )
        assertFalse(
            "a rate the panel does not advertise leaves no ladder rung",
            ThermalProfileApplier.isUsableFloor(90f, 120f, listOf(60f, 120f)),
        )
        assertFalse(
            "the start rate is not a step down",
            ThermalProfileApplier.isUsableFloor(120f, 120f, listOf(60f, 120f)),
        )
        assertFalse(
            "there is nothing below the panel's only rate",
            ThermalProfileApplier.isUsableFloor(60f, 60f, listOf(60f)),
        )
    }

    @Test
    fun `a floor at the starting rate is rejected as a no-op`() = runTest {
        var saves = 0

        val result = ThermalProfileApplier.apply(
            recommendation = recommendation(limit = 600, floor = 120f),
            profile = profile(),
            save = { saves++ },
        )

        assertEquals(ThermalProfileApplyResult.NoUsableTrigger, result)
        assertEquals(0, saves)
    }

    @Test
    fun `a failed save is reported as failed and never as applied`() = runTest {
        val result = ThermalProfileApplier.apply(
            recommendation = recommendation(),
            profile = profile(),
            save = { throw IllegalStateException("disk full") },
        )

        assertTrue(result is ThermalProfileApplyResult.Failed)
        assertEquals("disk full", (result as ThermalProfileApplyResult.Failed).detail)
    }

    @Test
    fun `a non-thermal recommendation is rejected`() = runTest {
        var saves = 0

        val result = ThermalProfileApplier.apply(
            recommendation = recommendation(action = RecommendationAction.ENABLE_BATTERY_SAVER),
            profile = profile(),
            save = { saves++ },
        )

        assertEquals(ThermalProfileApplyResult.NoUsableTrigger, result)
        assertEquals(0, saves)
    }

    @Test
    fun `cancellation is propagated instead of being reported as a failure`() = runTest {
        var cancelled = false

        try {
            ThermalProfileApplier.apply(
                recommendation = recommendation(),
                profile = profile(),
                save = { throw CancellationException("cancelled") },
            )
        } catch (expected: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
    }
}
