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
}
