package com.gamecore.core.plugin.content

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.model.BatteryReading
import com.gamecore.core.model.CpuReading
import com.gamecore.core.model.DisplayReading
import com.gamecore.core.model.FrameRateSample
import com.gamecore.core.model.GpuLoadSample
import com.gamecore.core.model.MemoryReading
import com.gamecore.core.model.NetworkReading
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.model.StorageReading
import com.gamecore.core.model.ThermalReading
import com.gamecore.domain.gaming.GamingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The condition-variable contract. These prove the map carries exactly the six published figures,
 * each rounded the way the HUD draws it; that an unreadable figure is omitted rather than
 * published as a fabricated 0; that RAM and battery (the two the platform always provides) are
 * always present; that a null snapshot is an empty map; and that neither "motion" nor any session
 * fact ever becomes a variable.
 */
class AutomationVariablesTest {

    private val SRC = DataSource.POWER_MANAGER

    @Test
    fun `a fully-populated snapshot yields all six variables rounded like the HUD`() {
        val vars = AutomationVariables.of(fullSnapshot(), GamingState.IDLE)
        assertEquals(
            mapOf(
                "fps" to 60.0,     // 59.6 -> 60
                "cpu" to 42.0,     // 42.3 -> 42
                "gpu" to 72.0,     // 71.8 -> 72
                "ram" to 75.0,     // used 75 of 100
                "battery" to 84.0, // level, no rounding needed
                "temp" to 36.0,    // 362 deci -> 36.2 C -> 36
            ),
            vars,
        )
    }

    @Test
    fun `absent figures are omitted while ram and battery always remain`() {
        // fps, cpu, gpu and temp are all unreadable; only the two figures the platform always
        // provides survive, so a `fps LT 30` rule cannot fire on a device that cannot measure FPS.
        val vars = AutomationVariables.of(
            snapshot(
                memory = MemoryReading.EMPTY.copy(totalBytes = 100L, availableBytes = 40L),
                battery = BatteryReading.EMPTY.copy(
                    levelPercent = 55,
                    temperatureDeciCelsius = Observed.notPresent("no battery sensor"),
                ),
            ),
            GamingState.IDLE,
        )
        assertEquals(mapOf("ram" to 60.0, "battery" to 55.0), vars)
        assertFalse(vars.containsKey("fps"))
        assertFalse(vars.containsKey("cpu"))
        assertFalse(vars.containsKey("gpu"))
        assertFalse(vars.containsKey("temp"))
    }

    @Test
    fun `a null snapshot yields an empty map so no rule fires on fabricated metrics`() {
        assertEquals(emptyMap<String, Double>(), AutomationVariables.of(null, GamingState.IDLE))
    }

    @Test
    fun `the published vocabulary is exactly the six, and motion is never among them`() {
        val vars = AutomationVariables.of(fullSnapshot(), playing())
        assertEquals(setOf("fps", "cpu", "gpu", "ram", "battery", "temp"), vars.keys)
        assertFalse(vars.containsKey("motion"))
    }

    @Test
    fun `the gaming session contributes no variable`() {
        // The session facts drive triggers, not condition variables, so the same snapshot
        // produces the same map whether the device is idle or deep in a game.
        val perf = snapshot(memory = MemoryReading.EMPTY.copy(totalBytes = 100L, availableBytes = 50L))
        assertEquals(
            AutomationVariables.of(perf, GamingState.IDLE),
            AutomationVariables.of(perf, playing()),
        )
    }

    // ------------------------------------------------------------------ fixtures

    private fun playing() = GamingState(playing = "com.game", gameLabel = "Game", startedAtMillis = 1_000L)

    private fun frame(averageFps: Float) = FrameRateSample(
        averageFps = averageFps,
        onePercentLowFps = averageFps - 10f,
        frameCount = 120,
        windowMillis = 1_000L,
        jankFrames = 2,
    )

    private fun fullSnapshot() = snapshot(
        frameRate = Observed.of(frame(59.6f), SRC),
        cpuPercent = Observed.of(42.3f, SRC),
        gpu = Observed.of(GpuLoadSample(71.8f), SRC),
        memory = MemoryReading.EMPTY.copy(totalBytes = 100L, availableBytes = 25L),
        battery = BatteryReading.EMPTY.copy(
            levelPercent = 84,
            temperatureDeciCelsius = Observed.of(362, SRC),
        ),
    )

    private fun snapshot(
        frameRate: Observed<FrameRateSample> = Observed.notPresent("x"),
        cpuPercent: Observed<Float> = Observed.notPresent("x"),
        gpu: Observed<GpuLoadSample> = Observed.notPresent("x"),
        memory: MemoryReading = MemoryReading.EMPTY,
        battery: BatteryReading = BatteryReading.EMPTY,
    ): PerformanceSnapshot = PerformanceSnapshot(
        capturedAtMillis = 0L,
        cpu = CpuReading.unavailable(coreCount = 8, absence = Observed.notPresent("x"))
            .copy(overallPercent = cpuPercent),
        memory = memory,
        battery = battery,
        thermal = ThermalReading.unavailable("x", statusSupported = true),
        storage = StorageReading.EMPTY,
        display = DisplayReading.unavailable("x"),
        network = NetworkReading.DISCONNECTED,
        frameRate = frameRate,
        latency = Observed.notPresent("x"),
        gpu = gpu,
        accessLevel = AccessLevel.NORMAL,
    )
}
