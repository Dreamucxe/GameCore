package com.gamecore.core.common

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The strings the user actually reads.
 *
 * Every number GameCore shows passes through here, and a factor-of-1024 mistake is invisible until
 * someone compares a HUD against another app — so the expected values are written out literally
 * rather than computed from the same arithmetic the code uses.
 */
class FormattersTest {

    @Test
    fun `byte sizes use the binary divisor Android's own memory APIs report in`() {
        assertEquals("0 B", Formatters.bytes(0))
        assertEquals("512 B", Formatters.bytes(512))
        assertEquals("1.0 KB", Formatters.bytes(1024))
        assertEquals("1.5 KB", Formatters.bytes(1536))
        assertEquals("1.0 MB", Formatters.bytes(1024L * 1024))
        assertEquals("8.0 GB", Formatters.bytes(8L * 1024 * 1024 * 1024))
        assertEquals("1.0 TB", Formatters.bytes(1024L * 1024 * 1024 * 1024))
        assertEquals("—", Formatters.bytes(-1))
    }

    @Test
    fun `the decimal is dropped above 100 so a redrawn HUD column keeps its width`() {
        assertEquals("99.9 MB", Formatters.bytes((99.9 * 1024 * 1024).toLong()))
        assertEquals("100 MB", Formatters.bytes(100L * 1024 * 1024))
        assertEquals("512 MB", Formatters.bytes(512L * 1024 * 1024))
    }

    @Test
    fun `meminfo kibibytes and the dashboard's gigabyte pair agree with bytes`() {
        assertEquals("2.0 MB", Formatters.kibibytes(2048))
        assertEquals("8.0", Formatters.gigabytes(8L * 1024 * 1024 * 1024))
        assertEquals(
            "4.2 / 8 GB",
            Formatters.memoryPair((4.2 * 1024 * 1024 * 1024).toLong(), 8L * 1024 * 1024 * 1024),
        )
    }

    @Test
    fun `a percentage is clamped rather than allowed to read 140 per cent`() {
        assertEquals("50%", Formatters.percent(0.5f))
        assertEquals("0%", Formatters.percent(0f))
        assertEquals("100%", Formatters.percent(1f))
        assertEquals("100%", Formatters.percent(1.4f))
        assertEquals("0%", Formatters.percent(-0.5f))
        assertEquals("45.7%", Formatters.percent(0.4567f, decimals = 1))
    }

    @Test
    fun `a percentage that could not be computed says so instead of reading NaN`() {
        assertEquals("—", Formatters.percent(Float.NaN))
        assertEquals("—", Formatters.percentValue(Float.NaN))
    }

    @Test
    fun `an already-scaled percentage is not divided again`() {
        assertEquals("83%", Formatters.percentValue(83.4f))
        assertEquals("0%", Formatters.percentValue(-3f))
        assertEquals("12.34%", Formatters.percentValue(12.34f, decimals = 2))
    }

    @Test
    fun `a session timer grows an hours field only when it needs one`() {
        assertEquals("00:00", Formatters.duration(0))
        assertEquals("00:59", Formatters.duration(59_999))
        assertEquals("01:00", Formatters.duration(60_000))
        assertEquals("59:59", Formatters.duration(3_599_000))
        assertEquals("1:00:00", Formatters.duration(3_600_000))
        assertEquals("1:01:01", Formatters.duration(3_661_000))
        assertEquals("—", Formatters.duration(-1))
    }

    @Test
    fun `the coarse form names the two largest units and stops`() {
        assertEquals("0s", Formatters.durationCoarse(0))
        assertEquals("9s", Formatters.durationCoarse(9_400))
        assertEquals("1m 15s", Formatters.durationCoarse(75_000))
        assertEquals("1h 24m", Formatters.durationCoarse(5_040_000))
        assertEquals("1d 1h", Formatters.durationCoarse(90_000_000))
        assertEquals("—", Formatters.durationCoarse(-1))
    }

    @Test
    fun `a total playtime of nothing is zero minutes, not a dash`() {
        assertEquals("0m", Formatters.durationTotal(0))
        assertEquals("0m", Formatters.durationTotal(-1))
        assertEquals("48m", Formatters.durationTotal(2_880_000))
        assertEquals("14h 30m", Formatters.durationTotal(52_200_000))
    }

    @Test
    fun `throughput below a byte per second reads zero rather than a fraction`() {
        assertEquals("0 B/s", Formatters.rate(0.0))
        assertEquals("0 B/s", Formatters.rate(0.5))
        assertEquals("1.5 KB/s", Formatters.rate(1536.0))
        assertEquals("—", Formatters.rate(-1.0))
        assertEquals("—", Formatters.rate(Double.NaN))
    }

    @Test
    fun `the pill's compact throughput fits beside four other stats`() {
        assertEquals("0K", Formatters.rateCompact(500.0))
        assertEquals("200K", Formatters.rateCompact(1024.0 * 200))
        assertEquals("1023K", Formatters.rateCompact(1024.0 * 1023))
        assertEquals("1.5M", Formatters.rateCompact(1024.0 * 1024 * 1.5))
        assertEquals("—", Formatters.rateCompact(-1.0))
    }

    @Test
    fun `a clock speed crosses to gigahertz at a round million kilohertz`() {
        assertEquals("900 MHz", Formatters.frequencyKHz(900_000))
        assertEquals("1.00 GHz", Formatters.frequencyKHz(1_000_000))
        assertEquals("1.80 GHz", Formatters.frequencyKHz(1_804_800))
        assertEquals("2.84 GHz", Formatters.frequencyKHz(2_841_600))
        assertEquals("—", Formatters.frequencyKHz(0))
        assertEquals("—", Formatters.frequencyKHz(-1))
    }

    @Test
    fun `temperature keeps the tenth of a degree the platform broadcasts`() {
        assertEquals("38.5°C", Formatters.temperature(385))
        assertEquals("0.0°C", Formatters.temperature(0))
        assertEquals("39°C", Formatters.temperatureShort(385))
        assertEquals("38°C", Formatters.temperatureShort(384))
        assertEquals("4.350 V", Formatters.voltage(4350))
    }

    @Test
    fun `a panel advertising 60_000004 Hz is shown as 60, and a real 48_5 Hz mode is not rounded away`() {
        assertEquals("60 Hz", Formatters.hertz(60.000004f))
        assertEquals("60 Hz", Formatters.hertz(59.94f))
        assertEquals("120 Hz", Formatters.hertz(120f))
        assertEquals("48.5 Hz", Formatters.hertz(48.5f))
        assertEquals("90.3 Hz", Formatters.hertz(90.3f))
    }

    @Test
    fun `a refresh rate that could not be read is a dash, never a zero`() {
        assertEquals("—", Formatters.hertz(0f))
        assertEquals("—", Formatters.hertz(-1f))
        assertEquals("—", Formatters.hertz(Float.NaN))
        assertEquals("—", Formatters.hertzValue(0f))
        assertEquals("—", Formatters.hertzValue(Float.NaN))
    }

    @Test
    fun `the bare value drops the unit for a HUD element that carries its own label`() {
        assertEquals("60", Formatters.hertzValue(60.000004f))
        assertEquals("48.5", Formatters.hertzValue(48.5f))
    }

    @Test
    fun `a resolution and a latency read as the units they are`() {
        assertEquals("1080 × 2400", Formatters.resolution(1080, 2400))
        assertEquals("0 ms", Formatters.millis(0))
        assertEquals("23 ms", Formatters.millis(23))
        assertEquals("—", Formatters.millis(-1))
    }

    @Test
    fun `battery drain is a rate, so a short session and a long one are comparable`() {
        assertEquals("12.0% / hour", Formatters.drainPerHour(12f, 3_600_000L))
        assertEquals("12.0% / hour", Formatters.drainPerHour(6f, 1_800_000L))
        assertEquals("0.0% / hour", Formatters.drainPerHour(0f, 3_600_000L))
    }

    @Test
    fun `a drain over no elapsed time is refused rather than extrapolated`() {
        assertNull(Formatters.drainPerHour(10f, 0L))
        assertNull(Formatters.drainPerHour(10f, -5L))
    }

    @Test
    fun `counts are pluralised, including the irregular ones the caller supplies`() {
        assertEquals("1 profile", Formatters.count(1, "profile"))
        assertEquals("0 profiles", Formatters.count(0, "profile"))
        assertEquals("2 profiles", Formatters.count(2, "profile"))
        assertEquals("1 entry", Formatters.count(1, "entry", "entries"))
        assertEquals("3 entries", Formatters.count(3, "entry", "entries"))
    }

    @Test
    fun `a battery delta keeps its sign, because charging while playing is possible`() {
        assertEquals("-12%", Formatters.signedPercent(-12))
        assertEquals("0%", Formatters.signedPercent(0))
        assertEquals("+4%", Formatters.signedPercent(4))
    }

    @Test
    fun `an exported timestamp is UTC, so the file means the same on another machine`() {
        assertEquals("1970-01-01T00:00:00Z", Formatters.iso8601(0L))
    }

    @Test
    fun `on-screen times are local, and a filename carries the same instant`() {
        val instant = localInstant(2026, Calendar.AUGUST, 29, 18, 42, 33)
        assertEquals("18:42", Formatters.clockTime(instant))
        assertEquals("2026-08-29 18:42", Formatters.dateTime(instant))
        assertEquals("20260829-184233", Formatters.fileTimestamp(instant))
    }

    @Test
    fun `history groups by calendar day rather than by elapsed hours`() {
        val now = localInstant(2026, Calendar.AUGUST, 29, 18, 42, 0)
        assertEquals("Today", Formatters.relativeDay(now, now))
        assertEquals("Today", Formatters.relativeDay(localInstant(2026, Calendar.AUGUST, 29, 0, 5), now))
        assertEquals(
            "Yesterday",
            Formatters.relativeDay(localInstant(2026, Calendar.AUGUST, 28, 23, 55), now),
        )
        assertEquals(
            "2026-08-20",
            Formatters.relativeDay(localInstant(2026, Calendar.AUGUST, 20, 9, 0), now),
        )
    }

    @Test
    fun `across a year boundary the exact date is shown rather than a guess`() {
        // A deliberate simplification: the day-of-year arithmetic only holds within one year, so New
        // Year's Eve reads as a date instead of "Yesterday" rather than as the wrong day.
        assertEquals(
            "2025-12-31",
            Formatters.relativeDay(
                localInstant(2025, Calendar.DECEMBER, 31, 23, 0),
                localInstant(2026, Calendar.JANUARY, 1, 1, 0),
            ),
        )
    }

    /** A local wall-clock instant, so the assertions hold in whatever zone the test host is in. */
    private fun localInstant(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int = 0,
    ): Long = Calendar.getInstance().apply {
        set(year, month, day, hour, minute, second)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
