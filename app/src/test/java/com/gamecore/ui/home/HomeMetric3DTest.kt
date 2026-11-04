package com.gamecore.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins every Home label to a different sculpted instrument; a rename must not silently reuse one. */
class HomeMetric3DTest {

    @Test
    fun `each built in metric resolves to its own 3D shape`() {
        val shapes = HomeLabels.METRICS.map { HomeMetricShape.fromLabel(it) }

        assertEquals(
            listOf(
                HomeMetricShape.CPU,
                HomeMetricShape.MEMORY,
                HomeMetricShape.BATTERY,
                HomeMetricShape.TEMPERATURE,
                HomeMetricShape.REFRESH,
            ),
            shapes,
        )
        assertEquals(shapes.size, shapes.distinct().size)
    }

    @Test
    fun `a future metric gets the safe faceted fallback`() {
        assertEquals(HomeMetricShape.UNKNOWN, HomeMetricShape.fromLabel("Future sensor"))
    }

    @Test
    fun `the die splits into one cell per reported core`() {
        assertEquals(0 to 0, cpuCoreGrid(0))
        assertEquals(1 to 1, cpuCoreGrid(1))
        assertEquals(2 to 1, cpuCoreGrid(2))
        assertEquals(2 to 2, cpuCoreGrid(4))
        assertEquals(3 to 2, cpuCoreGrid(6))
        assertEquals(4 to 2, cpuCoreGrid(8))
        assertEquals(4 to 3, cpuCoreGrid(12))
        assertEquals(4 to 4, cpuCoreGrid(16))
    }
}
