package com.gamecore.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `the fastest tier stacks down the left and the rest group on the right`() {
        val cells = cpuCoreCells(referenceCores())
        val fast = cells.filter { it.core.index >= 6 }
        val slow = cells.filter { it.core.index < 6 }

        assertEquals(2, fast.size)
        assertEquals(6, slow.size)
        assertTrue(fast.all { it.left == 0f })
        assertTrue(fast.all { it.width > 0.4f })
        assertEquals(0f, fast[0].top, 0.0001f)
        assertEquals(0.5f, fast[1].top, 0.0001f)
        assertTrue(slow.all { it.left >= fast[0].width - 0.0001f })
    }

    @Test
    fun `a performance block is more than twice the area of an efficiency core`() {
        val cells = cpuCoreCells(referenceCores())
        val area = cells.associate { it.core.index to it.width * it.height }
        val fastArea = area.getValue(6)
        val slowArea = area.getValue(0)

        assertTrue(fastArea > slowArea * 2f)
    }

    @Test
    fun `core cells fill the die without escaping it`() {
        val cells = cpuCoreCells(referenceCores())

        assertEquals(8, cells.size)
        assertEquals(1.0, cells.sumOf { it.width.toDouble() * it.height }, 0.0001)
        assertTrue(
            cells.all {
                it.left >= -0.0001f &&
                    it.top >= -0.0001f &&
                    it.left + it.width <= 1.0001f &&
                    it.top + it.height <= 1.0001f
            },
        )
    }

    @Test
    fun `a single fast core keeps the ordinary grid instead of inventing a tier`() {
        val cells = cpuCoreCells(
            listOf(
                core(0, 1_800_000),
                core(1, 2_000_000),
                core(2, 2_200_000),
                core(3, 2_800_000),
            ),
        )

        assertEquals(5f / 8.8f, cells[3].height, 0.0001f)
        assertTrue(cells[3].left > 0f)
    }

    @Test
    fun `cores without a reported ceiling still get an even grid`() {
        val cells = cpuCoreCells((0 until 8).map { core(it, 0L) })

        assertTrue(cells.all { kotlin.math.abs(it.width - 0.25f) < 0.0001f })
        assertTrue(cells.all { kotlin.math.abs(it.height - 0.5f) < 0.0001f })
    }

    @Test
    fun `an unknown ceiling takes the average of the reported ones`() {
        val cells = cpuCoreCells(
            listOf(
                core(0, 1_800_000),
                core(1, 0L),
                core(2, 2_300_000),
                core(3, 2_300_000),
            ),
        )
        val area = cells.associate { it.core.index to it.width * it.height }

        // 0 is treated as the mean of 1.8 and 2.3 GHz, so it sits between the known slow and fast cells.
        assertTrue(area.getValue(1) > area.getValue(0))
        assertTrue(cells.first { it.core.index == 1 }.left > 0f)
    }

    @Test
    fun `a partial last row is centred instead of stretching its cores`() {
        val cells = cpuCoreCells((0 until 5).map { core(it, 2_000_000) })
        val firstInLastRow = cells[3]

        assertEquals(1f / 3f, firstInLastRow.width, 0.0001f)
        assertEquals(1f / 6f, firstInLastRow.left, 0.0001f)
    }

    /** The common phone layout from the reference: two performance cores and six efficiency cores. */
    private fun referenceCores() = listOf(
        core(0, 1_900_000),
        core(1, 1_900_000),
        core(2, 1_900_000),
        core(3, 1_900_000),
        core(4, 1_900_000),
        core(5, 1_900_000),
        core(6, 2_300_000),
        core(7, 2_300_000),
    )

    private fun core(index: Int, maxKHz: Long) =
        HomeCpuCore(index = index, speed = "—", load = null, isOnline = true, maxKHz = maxKHz)
}
