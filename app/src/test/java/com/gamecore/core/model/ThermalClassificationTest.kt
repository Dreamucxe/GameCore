package com.gamecore.core.model

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.model.ThermalSensorType.BATTERY
import com.gamecore.core.model.ThermalSensorType.CPU
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Drives [ThermalClassifier.classify] directly with numbers and optional platform statuses.
 *
 * The load-bearing guarantee is that the label and the level come from one computation and can never
 * disagree (the old bug: a tile red at 85.3 °C while the word said "Normal"). These tests assert that
 * invariant across a sweep, then pin the thresholds, the plausibility clamp, and the platform escalation.
 */
class ThermalClassificationTest {

    // 1. The core invariant: for any input, the label is exactly the level's own label.

    @Test
    fun `label always agrees with level across a representative sweep`() {
        val sensors = listOf(CPU, BATTERY)
        val platforms = listOf(null) + ThermalStatus.values().toList()
        // A wide sweep including nulls, out-of-range values, and every boundary neighbourhood.
        val readings = listOf(
            null, -400, -300, -1, 0, 300, 399, 400, 449, 450, 499, 500,
            599, 600, 799, 800, 853, 1500, 1501, 1600,
        )
        for (sensor in sensors) {
            for (platform in platforms) {
                for (reading in readings) {
                    val result = ThermalClassifier.classify(reading, sensor, platform)
                    assertEquals(
                        "label must equal level.label for reading=$reading sensor=$sensor platform=$platform",
                        result.level.label,
                        result.label,
                    )
                }
            }
        }
    }

    // 2. The exact bug case: a hot CPU with the platform still reporting NONE must not read "Normal".

    @Test
    fun `the original bug case 853 CPU with platform NONE is CRITICAL and labelled Critical not Normal`() {
        val result = ThermalClassifier.classify(853, CPU, ThermalStatus.NONE)
        assertEquals(ThermalClass.CRITICAL, result.level)
        assertEquals("Critical", result.label)
    }

    @Test
    fun `853 CPU with no platform status is CRITICAL`() {
        val result = ThermalClassifier.classify(853, CPU, null)
        assertEquals(ThermalClass.CRITICAL, result.level)
        assertEquals("Critical", result.label)
    }

    // 3. CPU threshold boundaries (deci-degrees).

    @Test
    fun `CPU 449 is OK`() {
        assertEquals(ThermalClass.OK, ThermalClassifier.classify(449, CPU).level)
    }

    @Test
    fun `CPU 450 is WARM`() {
        assertEquals(ThermalClass.WARM, ThermalClassifier.classify(450, CPU).level)
    }

    @Test
    fun `CPU 599 is WARM`() {
        assertEquals(ThermalClass.WARM, ThermalClassifier.classify(599, CPU).level)
    }

    @Test
    fun `CPU 600 is HOT`() {
        assertEquals(ThermalClass.HOT, ThermalClassifier.classify(600, CPU).level)
    }

    @Test
    fun `CPU 799 is HOT`() {
        assertEquals(ThermalClass.HOT, ThermalClassifier.classify(799, CPU).level)
    }

    @Test
    fun `CPU 800 is CRITICAL`() {
        assertEquals(ThermalClass.CRITICAL, ThermalClassifier.classify(800, CPU).level)
    }

    // 4. BATTERY threshold boundaries, and the fact battery alarms hotter at a lower absolute temperature.

    @Test
    fun `BATTERY 399 is OK`() {
        assertEquals(ThermalClass.OK, ThermalClassifier.classify(399, BATTERY).level)
    }

    @Test
    fun `BATTERY 400 is WARM`() {
        assertEquals(ThermalClass.WARM, ThermalClassifier.classify(400, BATTERY).level)
    }

    @Test
    fun `BATTERY 449 is WARM`() {
        assertEquals(ThermalClass.WARM, ThermalClassifier.classify(449, BATTERY).level)
    }

    @Test
    fun `BATTERY 450 is HOT`() {
        assertEquals(ThermalClass.HOT, ThermalClassifier.classify(450, BATTERY).level)
    }

    @Test
    fun `BATTERY 499 is HOT`() {
        assertEquals(ThermalClass.HOT, ThermalClassifier.classify(499, BATTERY).level)
    }

    @Test
    fun `BATTERY 500 is CRITICAL`() {
        assertEquals(ThermalClass.CRITICAL, ThermalClassifier.classify(500, BATTERY).level)
    }

    @Test
    fun `same 450 deci is WARM on CPU but HOT on battery`() {
        assertEquals(ThermalClass.WARM, ThermalClassifier.classify(450, CPU).level)
        assertEquals(ThermalClass.HOT, ThermalClassifier.classify(450, BATTERY).level)
    }

    // 5. Missing or implausible readings are UNAVAILABLE.

    @Test
    fun `null reading is UNAVAILABLE`() {
        val result = ThermalClassifier.classify(null, CPU)
        assertEquals(ThermalClass.UNAVAILABLE, result.level)
        assertEquals("Unavailable", result.label)
    }

    @Test
    fun `reading below the plausible minimum is UNAVAILABLE`() {
        assertEquals(ThermalClass.UNAVAILABLE, ThermalClassifier.classify(-400, CPU).level)
    }

    @Test
    fun `reading above the plausible maximum is UNAVAILABLE`() {
        assertEquals(ThermalClass.UNAVAILABLE, ThermalClassifier.classify(1600, CPU).level)
    }

    // 6. Platform escalation: throttling levels win, softer levels never escalate, and a hotter raw wins.

    @Test
    fun `platform SEVERE escalates a mild 300 CPU reading to HOT`() {
        assertEquals(ThermalClass.HOT, ThermalClassifier.classify(300, CPU, ThermalStatus.SEVERE).level)
    }

    @Test
    fun `platform CRITICAL escalates a mild 300 CPU reading to CRITICAL`() {
        assertEquals(ThermalClass.CRITICAL, ThermalClassifier.classify(300, CPU, ThermalStatus.CRITICAL).level)
    }

    @Test
    fun `platform MODERATE never escalates so 300 CPU stays OK`() {
        assertEquals(ThermalClass.OK, ThermalClassifier.classify(300, CPU, ThermalStatus.MODERATE).level)
    }

    @Test
    fun `platform never de-escalates a hotter raw reading so 853 CPU with LIGHT stays CRITICAL`() {
        assertEquals(ThermalClass.CRITICAL, ThermalClassifier.classify(853, CPU, ThermalStatus.LIGHT).level)
    }

    // 7. A throttling platform status surfaces even when the raw reading is unavailable.

    @Test
    fun `platform SEVERE surfaces as HOT even when the raw reading is null`() {
        val result = ThermalClassifier.classify(null, CPU, ThermalStatus.SEVERE)
        assertEquals(ThermalClass.HOT, result.level)
        assertEquals("Hot", result.label)
    }

    // 8. The snapshot overload: the one seam the button dot (§2) and THERMAL_STATUS (§8) share. It must
    //    pick the CPU zone first, fall back to the battery, and — the case a bare number cannot get right —
    //    judge a battery-only reading against the *battery* thresholds, not the CPU ones.

    @Test
    fun `a snapshot with a CPU reading classifies it against the CPU thresholds`() {
        // 460 deci is WARM on CPU (warm at 450) — and would be HOT if wrongly read as a battery sensor.
        assertEquals(ThermalClass.WARM, ThermalClassifier.classify(snapshot(cpuDeci = 460)).level)
        assertEquals(ThermalClass.CRITICAL, ThermalClassifier.classify(snapshot(cpuDeci = 853)).level)
    }

    @Test
    fun `a snapshot with no CPU reading falls back to the battery and its own thresholds`() {
        // 460 deci with the CPU zone silent: the battery thresholds apply, so 460 is HOT (battery hot at
        // 450), not the WARM it would be on the CPU scale. This is the whole reason the overload exists.
        val result = ThermalClassifier.classify(snapshot(cpuDeci = null, batteryDeci = 460))
        assertEquals(ThermalClass.HOT, result.level)
        assertEquals("Hot", result.label)
    }

    @Test
    fun `a snapshot the CPU zone read ignores the battery entirely`() {
        // CPU present and cool wins over a hot battery: the primary temperature is the CPU zone, full stop.
        val result = ThermalClassifier.classify(snapshot(cpuDeci = 300, batteryDeci = 490))
        assertEquals(ThermalClass.OK, result.level)
    }

    @Test
    fun `a snapshot with neither sensor is UNAVAILABLE`() {
        assertEquals(ThermalClass.UNAVAILABLE, ThermalClassifier.classify(snapshot(cpuDeci = null, batteryDeci = null)).level)
    }

    @Test
    fun `a throttling platform status surfaces from a snapshot even when no sensor read`() {
        val result = ThermalClassifier.classify(
            snapshot(cpuDeci = null, batteryDeci = null, status = ThermalStatus.SEVERE),
        )
        assertEquals(ThermalClass.HOT, result.level)
    }

    @Test
    fun `a snapshot platform status escalates a cool CPU reading`() {
        val result = ThermalClassifier.classify(
            snapshot(cpuDeci = 300, status = ThermalStatus.CRITICAL),
        )
        assertEquals(ThermalClass.CRITICAL, result.level)
    }

    /**
     * A snapshot whose thermal and battery fields carry the given readings and everything else is a stated
     * absence. Only the four fields the classifier reads matter; the rest come from each model's own
     * empty/unavailable factory, so the fixture stays honest about what it does and does not report.
     */
    private fun snapshot(
        cpuDeci: Int? = null,
        batteryDeci: Int? = null,
        status: ThermalStatus? = null,
    ): PerformanceSnapshot = PerformanceSnapshot(
        capturedAtMillis = 0L,
        cpu = CpuReading.unavailable(coreCount = 0, absence = Observed.notPresent("test")),
        memory = MemoryReading.EMPTY,
        battery = BatteryReading.EMPTY.copy(
            temperatureDeciCelsius = batteryDeci
                ?.let { Observed.of(it, DataSource.BATTERY_MANAGER) }
                ?: Observed.notPresent("test"),
        ),
        thermal = ThermalReading(
            status = status
                ?.let { Observed.of(it, DataSource.POWER_MANAGER) }
                ?: Observed.notPresent("test"),
            cpuTemperatureDeciCelsius = cpuDeci
                ?.let { Observed.of(it, DataSource.SYS_FS) }
                ?: Observed.notPresent("test"),
            sensors = Observed.notPresent("test"),
            statusSupported = status != null,
        ),
        storage = StorageReading.EMPTY,
        display = DisplayReading.unavailable("test"),
        network = NetworkReading.DISCONNECTED,
        frameRate = Observed.notPresent("test"),
        latency = Observed.notPresent("test"),
        accessLevel = AccessLevel.NORMAL,
    )
}
