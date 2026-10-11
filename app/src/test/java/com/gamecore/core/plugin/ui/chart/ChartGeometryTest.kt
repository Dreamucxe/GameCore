package com.gamecore.core.plugin.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure geometry a chart is drawn from, pinned at the edges a renderer cannot easily be tested
 * on: a flat series, an inverted author range, an empty series, a reading past the top of the plot.
 *
 * [ChartGeometry.autoRange] is a deliberate replica of the house `com.gamecore.ui.components.autoRange`;
 * the expected ranges below are the values that function produces for the same inputs (floor the
 * padded minimum, ceil the padded maximum, open to a single unit when the two collapse), computed
 * by hand here rather than by calling into the Compose-layer original so this stays a pure JVM test.
 */
class ChartGeometryTest {

    private val eps = 0.0001f

    private fun assertRange(
        expectedStart: Float,
        expectedEnd: Float,
        actual: ClosedFloatingPointRange<Float>,
    ) {
        assertEquals(expectedStart, actual.start, eps)
        assertEquals(expectedEnd, actual.endInclusive, eps)
    }

    // --------------------------------------------------------------------------------- autoRange

    @Test
    fun `autoRange rounds outward to whole units with the default padding`() {
        assertRange(8f, 32f, ChartGeometry.autoRange(listOf(10f, 20f, 30f)))
    }

    @Test
    fun `an empty series yields the neutral plate the live graphs fall back to`() {
        assertRange(-2f, 3f, ChartGeometry.autoRange(emptyList()))
    }

    @Test
    fun `a flat series opens to a single unit rather than a zero-width range`() {
        // With no padding the padded min and max collapse; the range must still be plottable.
        assertRange(50f, 51f, ChartGeometry.autoRange(listOf(50f), padding = 0f))
    }

    @Test
    fun `non-finite values are filtered before the range is fitted`() {
        val range = ChartGeometry.autoRange(
            listOf(Float.NaN, 10f, Float.POSITIVE_INFINITY, 20f, Float.NEGATIVE_INFINITY),
        )
        assertRange(8f, 22f, range)
        // A series that is all non-finite is indistinguishable from an empty one.
        assertRange(-2f, 3f, ChartGeometry.autoRange(listOf(Float.NaN, Float.POSITIVE_INFINITY)))
    }

    // --------------------------------------------------------------------------------- effectiveRange

    @Test
    fun `an explicit range wins over the data`() {
        val spec = spec(min = 0f, max = 100f)
        assertRange(0f, 100f, ChartGeometry.effectiveRange(spec, listOf(1f, 2f, 3f)))
    }

    @Test
    fun `an invalid explicit range falls through to auto-scaling`() {
        val inverted = spec(min = 100f, max = 0f)
        assertRange(8f, 32f, ChartGeometry.effectiveRange(inverted, listOf(10f, 20f, 30f)))
    }

    @Test
    fun `a secondary series is folded into the auto-scale so it is never clipped`() {
        val spec = spec()
        val withSecondary = ChartGeometry.effectiveRange(spec, listOf(0f, 10f), listOf(100f))
        assertRange(-2f, 102f, withSecondary)
        val primaryOnly = ChartGeometry.effectiveRange(spec, listOf(0f, 10f))
        assertRange(-2f, 12f, primaryOnly)
    }

    // --------------------------------------------------------------------------------- barRects

    @Test
    fun `bars are evenly spaced with equal widths and heights set by value`() {
        val bars = ChartGeometry.barRects(
            values = listOf(0f, 5f, 10f),
            range = 0f..10f,
            width = 300f,
            height = 100f,
            gapRatio = 0.25f,
        )
        assertEquals(3, bars.size)
        // slot = 100, barWidth = 75, sideGap = 12.5
        bars.forEach { assertEquals(75f, it.width, eps) }
        assertEquals(12.5f, bars[0].left, eps)
        assertEquals(112.5f, bars[1].left, eps)
        assertEquals(212.5f, bars[2].left, eps)
        // Heights track the fraction of the range each value sits at.
        assertEquals(0f, bars[0].height, eps)
        assertEquals(50f, bars[1].height, eps)
        assertEquals(100f, bars[2].height, eps)
        // Top-left origin: a full-height bar starts at y=0, a zero-height bar at the baseline.
        assertEquals(0f, bars[2].top, eps)
        assertEquals(100f, bars[0].top, eps)
    }

    @Test
    fun `a reading past the ceiling is clamped and one below the floor is a zero bar`() {
        val bars = ChartGeometry.barRects(
            values = listOf(20f, -5f),
            range = 0f..10f,
            width = 200f,
            height = 100f,
        )
        assertEquals(100f, bars[0].height, eps) // 20 clamps to full height
        assertEquals(0f, bars[1].height, eps) // -5 is below the floor
    }

    @Test
    fun `an empty series or a zero-area plot draws no bars`() {
        assertTrue(ChartGeometry.barRects(emptyList(), 0f..10f, 100f, 100f).isEmpty())
        assertTrue(ChartGeometry.barRects(listOf(1f), 0f..10f, 0f, 100f).isEmpty())
        assertTrue(ChartGeometry.barRects(listOf(1f), 0f..10f, 100f, 0f).isEmpty())
    }

    // --------------------------------------------------------------------------------- helpers

    private fun spec(
        kind: ChartKind = ChartKind.LINE,
        min: Float? = null,
        max: Float? = null,
    ): ChartSpec = ChartSpec(
        kind = kind,
        values = emptyList(),
        values2 = null,
        seriesId = null,
        series2Id = null,
        min = min,
        max = max,
        label = null,
        label2 = null,
        note = null,
        colorArgb = null,
        color2Argb = null,
    )
}
