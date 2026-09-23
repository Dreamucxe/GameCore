package com.gamecore.domain.monitoring

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.model.BatteryReading
import com.gamecore.core.model.CpuReading
import com.gamecore.core.model.DisplayReading
import com.gamecore.core.model.FrameRateSample
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.MemoryReading
import com.gamecore.core.model.NetworkReading
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.model.StorageReading
import com.gamecore.core.model.ThermalReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the arithmetic inside [HudStatReader] — the last mile where a sampled `Float` or a byte count
 * becomes the digits drawn in the pill.
 *
 * [HudStatReaderThermalTest] covers the §8 thermal routing; this covers everything numeric, which was
 * untested. It matters more than it looks: every one of these figures is redrawn once a second over a
 * game, so a rounding rule that disagrees with itself between two stats shows up as two read-outs that
 * never quite agree, and an off-by-one in a temperature is the difference between "85" and "86" on a
 * phone the user is deciding whether to keep playing on.
 *
 * Three rules are asserted here, and they are deliberately not all the same rule:
 *
 *  - **Percentages, degrees and rates round to the nearest** (`Math.round`, half away from zero).
 *  - **Millamps and megabytes truncate**, because they come from integer and `Long` division. This is an
 *    asymmetry, not a bug being blessed: at mA and MB scale the discarded remainder is below what the
 *    stat can honestly claim to resolve. It is pinned so that if anyone changes it, they change it on
 *    purpose.
 *  - **A zero that was measured is a real reading.** A 100 B/s trickle rounds to "0" KB/s and stays
 *    `isAvailable` — the honest answer. Only an absent source produces no value, and then it carries a
 *    reason rather than a zero (the invariant the whole file is built around).
 *
 * All pure: a snapshot in, a string out, no Android and no running sampler.
 */
class HudStatReaderFormatTest {

    // --- percentages: round to the nearest whole, half away from zero -----------------------------

    @Test
    fun `a CPU percentage rounds to the nearest whole, with a half rounding up`() {
        assertEquals("41", value(HudStat.CPU_USAGE, snapshot(cpuPercent = 41.4f)))
        assertEquals("42", value(HudStat.CPU_USAGE, snapshot(cpuPercent = 41.5f)))
        assertEquals("42", value(HudStat.CPU_USAGE, snapshot(cpuPercent = 41.6f)))
    }

    @Test
    fun `the percentage carries no unit, and the unit is added only when it is drawn`() {
        // The contract StatReading documents: `value` is the bare figure so a renderer can lay the unit
        // out where it wants, and `display()` is the joined form.
        val reading = HudStatReader.read(HudStat.CPU_USAGE, snapshot(cpuPercent = 41.5f))
        assertEquals("42", reading.value)
        assertEquals("42%", reading.display())
        assertEquals("42", reading.display(withUnit = false))
    }

    @Test
    fun `RAM usage rounds the derived percentage rather than truncating it`() {
        // 55 of 128 bytes is 42.96875% — chosen because it is exact in a Float and because rounding and
        // truncating disagree about it, which is the whole point. 54 of 128 is 42.1875%, rounding down.
        // (Byte counts here are deliberately dyadic: a value like 41.5% is unreachable exactly in Float,
        // so asserting on it would be asserting on the last bit of a division.)
        assertEquals("43", value(HudStat.RAM_USAGE, snapshot(memoryTotalBytes = 128L, memoryAvailableBytes = 73L)))
        assertEquals("42", value(HudStat.RAM_USAGE, snapshot(memoryTotalBytes = 128L, memoryAvailableBytes = 74L)))
    }

    @Test
    fun `a frame rate rounds like a percentage does`() {
        assertEquals("60", value(HudStat.FRAME_RATE, snapshot(averageFps = 59.6f)))
        assertEquals("59", value(HudStat.FRAME_RATE, snapshot(averageFps = 59.4f)))
    }

    // --- degrees: deci-celsius to whole degrees ---------------------------------------------------

    @Test
    fun `a CPU temperature rounds deci-celsius to the nearest whole degree`() {
        assertEquals("85", value(HudStat.CPU_TEMPERATURE, snapshot(cpuDeci = 853)))
        assertEquals("86", value(HudStat.CPU_TEMPERATURE, snapshot(cpuDeci = 855)))
        assertEquals("86", value(HudStat.CPU_TEMPERATURE, snapshot(cpuDeci = 858)))
    }

    @Test
    fun `a battery temperature uses the same degree rule as the CPU`() {
        // Both feed the same helper, so a device showing 30.5 °C on one and 31 °C on the other would be
        // the pill contradicting itself.
        assertEquals("31", value(HudStat.BATTERY_TEMPERATURE, snapshot(batteryDeci = 305)))
        assertEquals("30", value(HudStat.BATTERY_TEMPERATURE, snapshot(batteryDeci = 304)))
    }

    // --- rates: bytes per second to KB/s ----------------------------------------------------------

    @Test
    fun `a download rate rounds bytes per second to the nearest KB`() {
        assertEquals("1", value(HudStat.NETWORK_DOWN, snapshot(rxBytesPerSecond = 1_024.0)))
        assertEquals("2", value(HudStat.NETWORK_DOWN, snapshot(rxBytesPerSecond = 1_536.0)))
        assertEquals("1", value(HudStat.NETWORK_DOWN, snapshot(rxBytesPerSecond = 512.0)))
    }

    @Test
    fun `an upload rate uses the same KB rule as the download`() {
        assertEquals("3", value(HudStat.NETWORK_UP, snapshot(txBytesPerSecond = 3_072.0)))
    }

    @Test
    fun `a measured trickle below half a KB reads zero and stays available`() {
        // The distinction this file exists to protect: "0" here is a real measurement of a nearly-idle
        // link, not a placeholder standing in for a source that could not be read.
        val reading = HudStatReader.read(HudStat.NETWORK_DOWN, snapshot(rxBytesPerSecond = 100.0))
        assertEquals("0", reading.value)
        assertTrue(reading.isAvailable)
        assertNull(reading.reason)
    }

    // --- the two that truncate --------------------------------------------------------------------

    @Test
    fun `battery current truncates microamps to milliamps rather than rounding`() {
        // Integer division, so 1999 µA is 1 mA and not 2. Pinned deliberately: the remainder is below
        // what a mA read-out claims to resolve.
        assertEquals("1", value(HudStat.BATTERY_CURRENT, snapshot(currentMicroAmps = 1_999)))
        assertEquals("2", value(HudStat.BATTERY_CURRENT, snapshot(currentMicroAmps = 2_000)))
    }

    @Test
    fun `a discharging current keeps its sign and truncates toward zero`() {
        // Discharge is reported negative. Truncation must not turn a small drain into a larger one.
        assertEquals("-1", value(HudStat.BATTERY_CURRENT, snapshot(currentMicroAmps = -1_999)))
    }

    @Test
    fun `free RAM truncates to whole megabytes`() {
        // One byte short of 3 MB is 2 MB, not 3 — a free-memory figure must never round upward into
        // memory the device does not have.
        assertEquals(
            "2",
            value(HudStat.RAM_FREE, snapshot(memoryTotalBytes = 8L * MEGABYTE, memoryAvailableBytes = 3L * MEGABYTE - 1L)),
        )
        assertEquals(
            "3",
            value(HudStat.RAM_FREE, snapshot(memoryTotalBytes = 8L * MEGABYTE, memoryAvailableBytes = 3L * MEGABYTE)),
        )
    }

    // --- absence is never a zero ------------------------------------------------------------------

    @Test
    fun `a numeric stat with no source carries a reason instead of a zero`() {
        // The invariant behind every branch above: there is no default case that returns "0".
        val reading = HudStatReader.read(HudStat.CPU_USAGE, snapshot())
        assertNull(reading.value)
        assertNotNull(reading.reason)
        assertFalse(reading.isAvailable)
        assertEquals(StatReading.PLACEHOLDER, reading.display())
    }

    @Test
    fun `no snapshot at all is awaiting the first sample, not an unmeasurable device`() {
        // Two different facts that render identically. Telling a user their phone cannot measure CPU
        // because the sampler had not ticked yet would be wrong every time the overlay opened.
        val reading = HudStatReader.read(HudStat.CPU_USAGE, snapshot = null)
        assertNull(reading.value)
        assertTrue(reading.isAwaitingFirstSample)
    }

    @Test
    fun `readAll resolves each stat independently, keeping the order asked for`() {
        // One absent stat must not take its neighbours down with it.
        val readings = HudStatReader.readAll(
            stats = listOf(HudStat.CPU_USAGE, HudStat.BATTERY_CURRENT, HudStat.CPU_TEMPERATURE),
            snapshot = snapshot(cpuPercent = 41.5f, cpuDeci = 853),
        )
        assertEquals(listOf(HudStat.CPU_USAGE, HudStat.BATTERY_CURRENT, HudStat.CPU_TEMPERATURE), readings.map { it.stat })
        assertEquals("42", readings[0].value)
        assertNull(readings[1].value)
        assertEquals("85", readings[2].value)
    }

    private fun value(stat: HudStat, snapshot: PerformanceSnapshot): String? =
        HudStatReader.read(stat, snapshot).value

    private fun snapshot(
        cpuPercent: Float? = null,
        cpuDeci: Int? = null,
        batteryDeci: Int? = null,
        currentMicroAmps: Int? = null,
        memoryTotalBytes: Long = 0L,
        memoryAvailableBytes: Long = 0L,
        rxBytesPerSecond: Double? = null,
        txBytesPerSecond: Double? = null,
        averageFps: Float? = null,
    ): PerformanceSnapshot = PerformanceSnapshot(
        capturedAtMillis = 0L,
        cpu = CpuReading.unavailable(coreCount = 0, absence = Observed.notPresent("test")).copy(
            overallPercent = cpuPercent
                ?.let { Observed.of(it, DataSource.PROC_FS) }
                ?: Observed.notPresent("test"),
        ),
        memory = MemoryReading.EMPTY.copy(
            totalBytes = memoryTotalBytes,
            availableBytes = memoryAvailableBytes,
        ),
        battery = BatteryReading.EMPTY.copy(
            temperatureDeciCelsius = batteryDeci
                ?.let { Observed.of(it, DataSource.BATTERY_MANAGER) }
                ?: Observed.notPresent("test"),
            currentMicroAmps = currentMicroAmps
                ?.let { Observed.of(it, DataSource.BATTERY_MANAGER) }
                ?: Observed.notPresent("test"),
        ),
        thermal = ThermalReading(
            status = Observed.notPresent("test"),
            cpuTemperatureDeciCelsius = cpuDeci
                ?.let { Observed.of(it, DataSource.SYS_FS) }
                ?: Observed.notPresent("test"),
            sensors = Observed.notPresent("test"),
            statusSupported = false,
        ),
        storage = StorageReading.EMPTY,
        display = DisplayReading.unavailable("test"),
        network = NetworkReading.DISCONNECTED.copy(
            rxRateBytesPerSecond = rxBytesPerSecond
                ?.let { Observed.of(it, DataSource.TRAFFIC_STATS) }
                ?: Observed.notPresent("test"),
            txRateBytesPerSecond = txBytesPerSecond
                ?.let { Observed.of(it, DataSource.TRAFFIC_STATS) }
                ?: Observed.notPresent("test"),
        ),
        frameRate = averageFps
            ?.let {
                Observed.of(
                    FrameRateSample(
                        averageFps = it,
                        onePercentLowFps = null,
                        frameCount = 120,
                        windowMillis = 2_000L,
                        jankFrames = 0,
                    ),
                    DataSource.FRAME_METRICS,
                )
            }
            ?: Observed.notPresent("test"),
        latency = Observed.notPresent("test"),
        accessLevel = AccessLevel.NORMAL,
    )

    private companion object {
        const val MEGABYTE = 1024L * 1024L
    }
}
