package com.gamecore.domain.monitoring

import com.gamecore.core.model.HudStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pill's last-mile formatting as arithmetic (spec §3): unit spacing, the compact line, and the one
 * word an absent reading shows. No Compose, no device — the point of moving this out of the composable.
 */
class PillFormatTest {

    private fun available(stat: HudStat, value: String) = StatReading(stat, value)
    private fun absent(stat: HudStat) = StatReading(stat, null, "Not available.")

    @Test
    fun `a percent and a degree hug their figure`() {
        assertEquals("74%", PillFormat.token(available(HudStat.RAM_USAGE, "74")))
        assertEquals("62°C", PillFormat.token(available(HudStat.CPU_TEMPERATURE, "62")))
    }

    @Test
    fun `a named unit takes a thin gap`() {
        assertEquals("120 Hz", PillFormat.token(available(HudStat.REFRESH_RATE, "120")))
        assertEquals("48 fps", PillFormat.token(available(HudStat.FRAME_RATE, "48")))
        assertEquals("12 ms", PillFormat.token(available(HudStat.NETWORK_LATENCY, "12")))
    }

    @Test
    fun `a unitless stat is just its value`() {
        assertEquals("14:03", PillFormat.token(available(HudStat.CLOCK, "14:03")))
        assertEquals("Warm", PillFormat.token(available(HudStat.THERMAL_STATUS, "Warm")))
    }

    @Test
    fun `the compact line is the example from the spec`() {
        val line = PillFormat.compactLine(
            listOf(
                available(HudStat.CPU_TEMPERATURE, "62"),
                available(HudStat.RAM_USAGE, "74"),
                available(HudStat.REFRESH_RATE, "120"),
            ),
        )
        assertEquals("62°C · 74% · 120 Hz", line)
    }

    @Test
    fun `the compact line drops the stats that have no reading`() {
        val line = PillFormat.compactLine(
            listOf(
                available(HudStat.CPU_TEMPERATURE, "62"),
                absent(HudStat.FRAME_RATE),
                available(HudStat.RAM_USAGE, "74"),
            ),
        )
        // The gap the drop leaves does not become a double separator or a dangling middot.
        assertEquals("62°C · 74%", line)
    }

    @Test
    fun `a compact line with nothing readable is null, so the caller can fall back`() {
        assertNull(PillFormat.compactLine(emptyList()))
        assertNull(PillFormat.compactLine(listOf(absent(HudStat.FRAME_RATE), absent(HudStat.NETWORK_LATENCY))))
    }

    @Test
    fun `a detailed cell keeps its slot and says the word when there is no reading`() {
        assertEquals("Unavailable", PillFormat.cellValue(absent(HudStat.CPU_TEMPERATURE)))
        // Never the ellipsis the spec forbids, never "n/a", never a zero.
        assertEquals(PillFormat.UNAVAILABLE, PillFormat.cellValue(absent(HudStat.FRAME_RATE)))
    }

    @Test
    fun `a detailed cell with a reading is the same spaced token as the compact line`() {
        assertEquals("120 Hz", PillFormat.cellValue(available(HudStat.REFRESH_RATE, "120")))
        assertEquals("74%", PillFormat.cellValue(available(HudStat.RAM_USAGE, "74")))
    }
}
