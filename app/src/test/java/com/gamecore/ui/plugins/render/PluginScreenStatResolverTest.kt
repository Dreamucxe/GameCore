package com.gamecore.ui.plugins.render

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.model.BatteryReading
import com.gamecore.core.model.CpuReading
import com.gamecore.core.model.DisplayReading
import com.gamecore.core.model.MemoryReading
import com.gamecore.core.model.MetricHistory
import com.gamecore.core.model.NetworkReading
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.model.StorageReading
import com.gamecore.core.model.ThermalReading
import com.gamecore.core.plugin.DataScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The plugin screen's data backstop — the second of its two consent locks.
 *
 * Pins the rule the whole feature exists for: a live figure surfaces on a plugin screen only when the
 * plugin was granted the [DataScope] that figure sits under; an ungranted scope resolves to null, which
 * the renderer draws as the absent em dash, never as a real reading. The resolver is pure, so these run
 * on the JVM with hand-built snapshots and no Compose, Hilt or live monitor.
 */
class PluginScreenStatResolverTest {

    @Test
    fun `a granted device_stats stat renders the live reading`() {
        val resolver = PluginScreenStatResolver(
            grantedScopes = setOf(DataScope.DEVICE_STATS),
            snapshot = snapshot(cpuPercent = 42f),
            history = MetricHistory.EMPTY,
        )
        assertEquals("42%", resolver.stats("cpu"))
    }

    @Test
    fun `a granted thermals stat renders the live reading`() {
        val resolver = PluginScreenStatResolver(
            grantedScopes = setOf(DataScope.THERMALS),
            snapshot = snapshot(batteryDeci = 300),
            history = MetricHistory.EMPTY,
        )
        assertEquals("30°C", resolver.stats("temp"))
    }

    @Test
    fun `an ungranted scope resolves to absent even when the host could read it`() {
        // The host CAN read CPU (42%), but device_stats was never granted — the backstop must win.
        val resolver = PluginScreenStatResolver(
            grantedScopes = emptySet(),
            snapshot = snapshot(cpuPercent = 42f),
            history = MetricHistory.EMPTY,
        )
        assertNull(resolver.stats("cpu"))
    }

    @Test
    fun `one granted scope does not unlock a stat under a different scope`() {
        // device_stats granted, thermals NOT: a temperature must still resolve absent.
        val resolver = PluginScreenStatResolver(
            grantedScopes = setOf(DataScope.DEVICE_STATS),
            snapshot = snapshot(batteryDeci = 300),
            history = MetricHistory.EMPTY,
        )
        assertNull(resolver.stats("temp"))
    }

    @Test
    fun `motion stats resolve to null because this surface has no sampler`() {
        // Motion granted, yet rotation_rate/movement have no sampler here, so both are absent.
        val resolver = PluginScreenStatResolver(
            grantedScopes = setOf(DataScope.MOTION),
            snapshot = snapshot(),
            history = MetricHistory.EMPTY,
        )
        assertNull(resolver.stats("rotation_rate"))
        assertNull(resolver.stats("movement"))
    }

    @Test
    fun `a granted stat the host cannot currently read resolves to absent`() {
        // device_stats granted, but the snapshot carries no CPU reading — absent, not a fake 0.
        val resolver = PluginScreenStatResolver(
            grantedScopes = setOf(DataScope.DEVICE_STATS),
            snapshot = snapshot(),
            history = MetricHistory.EMPTY,
        )
        assertNull(resolver.stats("cpu"))
    }

    @Test
    fun `an unknown stat id resolves to null`() {
        val resolver = PluginScreenStatResolver(
            grantedScopes = DataScope.entries.toSet(),
            snapshot = snapshot(cpuPercent = 42f),
            history = MetricHistory.EMPTY,
        )
        assertNull(resolver.stats("banana"))
    }

    private fun snapshot(
        cpuPercent: Float? = null,
        batteryDeci: Int? = null,
    ): PerformanceSnapshot = PerformanceSnapshot(
        capturedAtMillis = 0L,
        cpu = CpuReading.unavailable(coreCount = 0, absence = Observed.notPresent("test")).copy(
            overallPercent = cpuPercent
                ?.let { Observed.of(it, DataSource.PROC_FS) }
                ?: Observed.notPresent("test"),
        ),
        memory = MemoryReading.EMPTY,
        battery = BatteryReading.EMPTY.copy(
            temperatureDeciCelsius = batteryDeci
                ?.let { Observed.of(it, DataSource.BATTERY_MANAGER) }
                ?: Observed.notPresent("test"),
        ),
        thermal = ThermalReading(
            status = Observed.notPresent("test"),
            cpuTemperatureDeciCelsius = Observed.notPresent("test"),
            sensors = Observed.notPresent("test"),
            statusSupported = false,
        ),
        storage = StorageReading.EMPTY,
        display = DisplayReading.unavailable("test"),
        network = NetworkReading.DISCONNECTED,
        frameRate = Observed.notPresent("test"),
        latency = Observed.notPresent("test"),
        gpu = Observed.notPresent("test"),
        accessLevel = AccessLevel.NORMAL,
    )
}
