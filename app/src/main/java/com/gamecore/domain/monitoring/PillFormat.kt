package com.gamecore.domain.monitoring

import com.gamecore.core.model.HudStat

/**
 * The one place a resolved [StatReading] becomes the text the stats pill draws (spec §3).
 *
 * The numbers are already rounded and stripped of units by [HudStatReader] — its `value` is "42", not
 * "42%". This object does the last mile: it puts the unit back on the way each unit wants it, composes the
 * compact single line, and owns the one word an absent reading shows. Keeping that here rather than in the
 * composable is what lets §10 assert "120 Hz", "62°C" and a dropped-when-absent line as arithmetic, with no
 * Compose and no device.
 *
 * Two rendering shapes share this object so they cannot drift:
 *  - [compactLine] — the glanceable line, "62°C · 74% · 120 Hz", available stats only.
 *  - [cellValue] — one detailed-card cell's value, which shows [UNAVAILABLE] rather than dropping out,
 *    because a card of labelled cells with a gap in it reads as a bug where a single line reads as terse.
 */
object PillFormat {

    /** The word a stat with no reading shows on the detailed card. A word, never "n/a" and never "…" (spec §3). */
    const val UNAVAILABLE = "Unavailable"

    /** What joins the tokens on the compact line: a spaced middot, so "62°C" and "74%" stay distinct. */
    const val SEPARATOR = " · "

    /**
     * One reading as "value + unit", spaced the way its unit is written: a percent or a degree hugs the
     * figure ("74%", "62°C"), a named unit takes a thin gap ("120 Hz", "48 fps"), and a unitless stat
     * (clock, session time, thermal word) is just its value. Only ever called for an available reading; a
     * value-less reading would render its bare [StatReading.PLACEHOLDER], which is why the callers filter or
     * branch on [StatReading.isAvailable] first.
     */
    fun token(reading: StatReading): String {
        val value = reading.value ?: return reading.display()
        return value + unitSuffix(reading.stat)
    }

    /**
     * The compact line: every *available* reading as a spaced-unit token, joined by [SEPARATOR]. Unavailable
     * stats are dropped rather than shown as a word — the line is a one-glance read, and a gap in it would
     * make the eye stop on the one thing that is missing. Returns null when nothing can be shown, so the
     * caller falls back rather than drawing an empty plate. The thermal dot beside the line is the caller's,
     * not one of these tokens.
     */
    fun compactLine(readings: List<StatReading>): String? {
        val line = readings.filter { it.isAvailable }.joinToString(SEPARATOR) { token(it) }
        return line.ifEmpty { null }
    }

    /**
     * One detailed-card cell's value: the spaced-unit token when there is a reading, else [UNAVAILABLE].
     * Unlike the compact line this keeps the cell — a labelled card is where the user goes to see *why* a
     * number is missing, so the slot has to stay and say so.
     */
    fun cellValue(reading: StatReading): String =
        if (reading.isAvailable) token(reading) else UNAVAILABLE

    /**
     * The gap, if any, between a figure and its unit. Percent and degree are written closed up; everything
     * else gets a hair space's worth of gap; a unitless stat gets nothing.
     */
    private fun unitSuffix(stat: HudStat): String {
        val unit = stat.unit
        return when {
            unit.isEmpty() -> ""
            unit.startsWith("%") || unit.startsWith("°") -> unit
            else -> " $unit"
        }
    }
}
