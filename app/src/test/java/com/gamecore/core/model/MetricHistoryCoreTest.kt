package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MetricHistoryCoreTest {

    @Test
    fun `per-core history stays aligned and preserves missing samples as gaps`() {
        val history = MetricHistory(
            listOf(
                HistoryPoint(atMillis = 1L, coreActivityPercent = listOf(10f, null)),
                HistoryPoint(atMillis = 2L, coreActivityPercent = listOf(20f, 40f)),
                HistoryPoint(atMillis = 3L, coreActivityPercent = listOf(null, 60f)),
            ),
        )

        assertEquals(
            listOf(
                listOf(10f, 20f, null),
                listOf(null, 40f, 60f),
            ),
            history.coreActivitySeries(coreCount = 2),
        )
    }

    @Test
    fun `newly visible cores receive an aligned empty history`() {
        val history = MetricHistory(
            listOf(
                HistoryPoint(atMillis = 1L, coreActivityPercent = listOf(12f)),
                HistoryPoint(atMillis = 2L, coreActivityPercent = listOf(18f)),
            ),
        )

        assertEquals(listOf(null, null), history.coreActivitySeries(coreCount = 2)[1])
    }
}
