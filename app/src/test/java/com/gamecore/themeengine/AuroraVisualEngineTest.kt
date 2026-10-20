package com.gamecore.themeengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the Theme Engine's colours are decided by.
 *
 * These assert the exact figures the specification names — `rgb(148, 90, 180)` at `1.92x` for a
 * moderately loaded device, molten crimson at 98 °C, ice at half speed when idle — because those
 * figures are the contract, not an illustration of one. Everything is driven through the public
 * [AuroraVisualEngine.resolve] with hand-written readings, so a failure points at a rule rather than
 * at a sensor.
 */
class AuroraVisualEngineTest {

    private val balanced = AuroraDefaults.preset()

    @Test
    fun `a moderately loaded device is aurora violet at the reference vector`() {
        val resolution = AuroraVisualEngine.resolve(
            ThemeEngineMetrics(cpuUsage = 63f, ramAllocation = 21.6f, coreTemperatureC = 45f),
            balanced,
        )

        assertEquals(ColorVectorMode.AURORA_VIOLET, resolution.mode)
        assertEquals(Triple(148, 90, 180), resolution.vector.rgb)
        assertEquals(1.92f, resolution.vector.velocity, 0.0001f)
    }

    @Test
    fun `an idle cold device is ice at half speed`() {
        val resolution = AuroraVisualEngine.resolve(
            ThemeEngineMetrics(cpuUsage = 5f, ramAllocation = 12f, coreTemperatureC = 31f),
            balanced,
        )

        assertEquals(ColorVectorMode.ICE_GLACIER, resolution.mode)
        assertEquals(AuroraPalette.ICE, resolution.vector.rgb)
        assertEquals(0.5f, resolution.vector.velocity, 0.0001f)
    }

    @Test
    fun `the reference temperature is molten`() {
        val resolution = AuroraVisualEngine.resolve(
            ThemeEngineMetrics(cpuUsage = 40f, ramAllocation = 30f, coreTemperatureC = 98f),
            balanced,
        )

        assertEquals(ColorVectorMode.MOLTEN_LAVA, resolution.mode)
        assertEquals(AuroraPalette.LAVA, resolution.vector.rgb)
        assertEquals(2.8f, resolution.vector.velocity, 0.0001f)
    }

    @Test
    fun `three quarters of the temperature threshold is already warm`() {
        // 70 °C is below the 90 °C molten threshold but above three quarters of it (67.5 °C).
        val resolution = AuroraVisualEngine.resolve(
            ThemeEngineMetrics(cpuUsage = 5f, ramAllocation = 5f, coreTemperatureC = 70f),
            balanced,
        )

        assertEquals(ColorVectorMode.AURORA_VIOLET, resolution.mode)
    }

    @Test
    fun `extreme cpu goes molten even when the thresholds are raised`() {
        val relaxed = balanced.copy(
            cpuThreshold = 100f,
            ramThreshold = 100f,
            tempThreshold = 120f,
        )
        val resolution = AuroraVisualEngine.resolve(
            ThemeEngineMetrics(cpuUsage = 96f, ramAllocation = 10f, coreTemperatureC = 30f),
            relaxed,
        )

        assertEquals(ColorVectorMode.MOLTEN_LAVA, resolution.mode)
    }

    @Test
    fun `a custom preset paints its own colour and still follows load`() {
        val custom = balanced.copy(
            vectorMode = ColorVectorMode.CUSTOM,
            customColorRgb = Triple(10, 20, 30),
        )
        val quiet = AuroraVisualEngine.resolve(
            ThemeEngineMetrics(cpuUsage = 5f, ramAllocation = 5f, coreTemperatureC = 25f),
            custom,
        )
        val busy = AuroraVisualEngine.resolve(
            ThemeEngineMetrics(cpuUsage = 95f, ramAllocation = 5f, coreTemperatureC = 25f),
            custom,
        )

        assertEquals(ColorVectorMode.CUSTOM, quiet.mode)
        assertEquals(Triple(10, 20, 30), quiet.vector.rgb)
        assertEquals(Triple(10, 20, 30), busy.vector.rgb)
        assertTrue(busy.vector.velocity > quiet.vector.velocity)
    }

    @Test
    fun `a custom preset without a colour blends from the reading`() {
        val custom = balanced.copy(vectorMode = ColorVectorMode.CUSTOM, customColorRgb = null)
        val resolution = AuroraVisualEngine.resolve(
            ThemeEngineMetrics(cpuUsage = 30f, ramAllocation = 0f, coreTemperatureC = 0f),
            custom,
        )

        assertEquals(ColorVectorMode.CUSTOM, resolution.mode)
        assertNotEquals(AuroraPalette.ICE, resolution.vector.rgb)
        // 0.3 of the ice → lava ramp: velocity 0.5 + 2.3 * 0.3, times the preset's 1.0 multiplier.
        assertEquals(1.19f, resolution.vector.velocity, 0.0001f)
    }

    @Test
    fun `the preset multiplier scales the mode velocity`() {
        val lively = balanced.copy(fluidVelocity = 1.5f)
        val resolution = AuroraVisualEngine.resolve(
            ThemeEngineMetrics(cpuUsage = 63f, ramAllocation = 10f, coreTemperatureC = 40f),
            lively,
        )

        assertEquals(1.92f * 1.5f, resolution.vector.velocity, 0.0001f)
    }

    @Test
    fun `velocity never leaves the renderable range`() {
        val fast = balanced.copy(fluidVelocity = 3f)
        val resolution = AuroraVisualEngine.resolve(
            ThemeEngineMetrics(cpuUsage = 99f, ramAllocation = 99f, coreTemperatureC = 110f),
            fast,
        )

        // 2.8x times a 3.0x preset multiplier would be 8.4x, far past what the renderer accepts.
        assertEquals(AuroraBounds.MAX_VELOCITY, resolution.vector.velocity, 0.0001f)
    }

    @Test
    fun `impossible readings are clamped rather than trusted`() {
        val resolution = AuroraVisualEngine.resolve(
            ThemeEngineMetrics(cpuUsage = -50f, ramAllocation = 1000f, coreTemperatureC = 500f),
            balanced,
        )

        // RAM at 100 % is an extreme condition on its own.
        assertEquals(ColorVectorMode.MOLTEN_LAVA, resolution.mode)
        assertTrue(resolution.vector.velocity <= AuroraBounds.MAX_VELOCITY)
        assertTrue(resolution.vector.velocity >= AuroraBounds.MIN_VELOCITY)
    }

    @Test
    fun `a non-zero delta moves the vector toward the target`() {
        val start = AuroraRenderVector.of(AuroraVector(AuroraPalette.ICE, AuroraPalette.ICE_VELOCITY))
        val target = AuroraVector(AuroraPalette.VIOLET, AuroraPalette.VIOLET_VELOCITY)

        val stepped = blendVector(start, target, 250).painted()

        assertNotEquals(start, stepped)
        assertTrue(stepped.rgb.first in AuroraPalette.ICE.first..AuroraPalette.VIOLET.first)
        assertTrue(stepped.velocity > start.velocity)
        assertTrue(stepped.velocity < target.velocity)
    }

    @Test
    fun `a zero delta changes nothing`() {
        val start = AuroraRenderVector.of(AuroraVector(AuroraPalette.ICE, AuroraPalette.ICE_VELOCITY))
        val target = AuroraVector(AuroraPalette.LAVA, AuroraPalette.LAVA_VELOCITY)

        assertEquals(start, blendVector(start, target, 0))
    }

    @Test
    fun `blending is frame-rate independent`() {
        val start = AuroraRenderVector.of(AuroraVector(AuroraPalette.ICE, AuroraPalette.ICE_VELOCITY))
        val target = AuroraVector(AuroraPalette.LAVA, AuroraPalette.LAVA_VELOCITY)

        val oneStep = blendVector(start, target, 320)
        var manySteps = start
        repeat(20) { manySteps = blendVector(manySteps, target, 16) }

        // Twenty 16 ms steps and one 320 ms step describe the same elapsed time, so the exponential
        // smoothing must land on the same colour. The tolerance is float noise, not whole bytes: an
        // implementation that rounded per step would drift by several units here.
        assertEquals(oneStep.red, manySteps.red, 0.05f)
        assertEquals(oneStep.green, manySteps.green, 0.05f)
        assertEquals(oneStep.blue, manySteps.blue, 0.05f)
    }

    @Test
    fun `blending converges and then stops`() {
        val target = AuroraVector(AuroraPalette.VIOLET, AuroraPalette.VIOLET_VELOCITY)
        var current = AuroraRenderVector.of(AuroraVector(Triple(0, 0, 0), 0.25f))

        repeat(600) { current = blendVector(current, target, 16) }

        assertEquals(target.rgb, current.painted().rgb)
        assertEquals(target.velocity, current.velocity, 0.0001f)
    }
}
