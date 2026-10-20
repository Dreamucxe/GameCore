package com.gamecore.themeengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plugin's lifecycle and the split between what it reports and what it paints.
 *
 * The measured state is driven through [ThemeEnginePlugin.ingest] and [ThemeEnginePlugin.tick] rather
 * than through a collector and a real frame clock: those two are the whole of the engine's behaviour,
 * and a test that slept for a second to watch a colour change would be a slower test of the same thing.
 */
class ThemeEnginePluginTest {

    @Test
    fun `a fresh plugin is inactive and ice-cold`() {
        val plugin = ThemeEnginePlugin()

        assertFalse(plugin.state.value.isActive)
        assertEquals(ColorVectorMode.ICE_GLACIER, plugin.state.value.resolution.mode)
        assertEquals(AuroraDefaults.preset(), plugin.state.value.preset)
    }

    @Test
    fun `ingest replaces the reported target immediately`() {
        val plugin = ThemeEnginePlugin()

        plugin.ingest(ThemeEngineMetrics(cpuUsage = 63f, ramAllocation = 21.6f, coreTemperatureC = 45f))

        val state = plugin.state.value
        assertEquals(ColorVectorMode.AURORA_VIOLET, state.resolution.mode)
        assertEquals(Triple(148, 90, 180), state.resolution.vector.rgb)
        // The painted vector has not moved yet: the table and the canvas are different answers on
        // purpose, and only the table is allowed to jump.
        assertEquals(AuroraPalette.ICE, plugin.renderVector.value.rgb)
    }

    @Test
    fun `tick walks the painted vector toward the target and stops there`() {
        val plugin = ThemeEnginePlugin()

        plugin.ingest(ThemeEngineMetrics(cpuUsage = 98f, ramAllocation = 30f, coreTemperatureC = 98f))
        val target = plugin.state.value.resolution.vector
        assertEquals(ColorVectorMode.MOLTEN_LAVA, plugin.state.value.resolution.mode)

        plugin.tick(16)
        assertNotEquals(target, plugin.renderVector.value)

        repeat(600) { plugin.tick(16) }

        assertEquals(target.rgb, plugin.renderVector.value.rgb)
        assertEquals(target.velocity, plugin.renderVector.value.velocity, 0.0001f)
    }

    @Test
    fun `changing the preset re-resolves without restarting anything`() {
        val plugin = ThemeEnginePlugin()
        plugin.ingest(ThemeEngineMetrics(cpuUsage = 63f, ramAllocation = 10f, coreTemperatureC = 40f))

        plugin.setPreset(AuroraDefaults.preset().copy(fluidVelocity = 1.5f))

        assertEquals(1.5f, plugin.state.value.preset.fluidVelocity, 0.0001f)
        assertEquals(1.92f * 1.5f, plugin.state.value.resolution.vector.velocity, 0.0001f)
    }

    @Test
    fun `an inactive plugin still reports readings but does not paint`() {
        val plugin = ThemeEnginePlugin()

        plugin.ingest(ThemeEngineMetrics(cpuUsage = 98f, ramAllocation = 30f, coreTemperatureC = 98f))
        plugin.stop()

        assertFalse(plugin.state.value.isActive)
        assertEquals(ColorVectorMode.MOLTEN_LAVA, plugin.state.value.resolution.mode)
        // Nothing advanced the painted vector, so it is still the initial ice.
        assertEquals(AuroraPalette.ICE, plugin.renderVector.value.rgb)
    }

    @Test
    fun `readings are normalised before they are stored`() {
        val plugin = ThemeEnginePlugin()

        plugin.ingest(ThemeEngineMetrics(cpuUsage = -5f, ramAllocation = 250f, coreTemperatureC = 400f))

        val metrics = plugin.state.value.metrics
        assertEquals(0f, metrics.cpuUsage, 0.0001f)
        assertTrue(metrics.ramAllocation <= AuroraBounds.MAX_PERCENT_METRIC)
        assertTrue(metrics.coreTemperatureC <= AuroraBounds.MAX_TEMP_METRIC)
    }
}
