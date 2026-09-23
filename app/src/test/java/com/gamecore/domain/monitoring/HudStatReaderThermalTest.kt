package com.gamecore.domain.monitoring

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.model.BatteryReading
import com.gamecore.core.model.CpuReading
import com.gamecore.core.model.DisplayReading
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.MemoryReading
import com.gamecore.core.model.NetworkReading
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.model.StorageReading
import com.gamecore.core.model.ThermalReading
import com.gamecore.core.model.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the spec §8 fix: the in-game HUD's `THERMAL_STATUS` stat must read the shared classifier's word,
 * not the raw platform label.
 *
 * The bug this guards against (the same one Spec A fixed on the dashboard) is a device sitting at 85 °C
 * while `PowerManager` still reports `THERMAL_STATUS_NONE` — the pill then drew "Normal" over a phone that
 * was cooking. [HudStatReader] now routes the stat through [com.gamecore.core.model.ThermalClassifier], the
 * one seam the button dot and pill dot already use, so all three thermal read-outs agree by construction.
 * These tests drive [HudStatReader.read] with a snapshot and assert the word, so no Android or live sampler
 * is involved.
 */
class HudStatReaderThermalTest {

    @Test
    fun `a hot CPU with a Normal platform status reads Critical, not Normal`() {
        // 85.3 °C from the sensor; the platform still says NONE — the exact shape of the old bug.
        val reading = HudStatReader.read(
            stat = HudStat.THERMAL_STATUS,
            snapshot = snapshot(cpuDeci = 853, status = ThermalStatus.NONE),
        )
        assertEquals("Critical", reading.value)
    }

    @Test
    fun `a warm CPU reads Warm`() {
        val reading = HudStatReader.read(
            stat = HudStat.THERMAL_STATUS,
            snapshot = snapshot(cpuDeci = 500),
        )
        assertEquals("Warm", reading.value)
    }

    @Test
    fun `a cool CPU throttled by the platform escalates to Hot`() {
        // No hot sensor, but the OS is clamping at SEVERE — the classifier surfaces that as HOT.
        val reading = HudStatReader.read(
            stat = HudStat.THERMAL_STATUS,
            snapshot = snapshot(cpuDeci = 300, status = ThermalStatus.SEVERE),
        )
        assertEquals("Hot", reading.value)
    }

    @Test
    fun `no thermal reading at all shows the honest word, never a fake temperature`() {
        val reading = HudStatReader.read(
            stat = HudStat.THERMAL_STATUS,
            snapshot = snapshot(),
        )
        assertEquals("Unavailable", reading.value)
    }

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
