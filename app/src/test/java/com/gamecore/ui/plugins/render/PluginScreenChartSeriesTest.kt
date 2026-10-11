package com.gamecore.ui.plugins.render

import com.gamecore.core.model.HistoryPoint
import com.gamecore.core.model.MetricHistory
import com.gamecore.core.plugin.DataScope
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chart half of a plugin screen's data backstop, exercised through the renderer's real series
 * picker ([resolveSeries], the exact function [NodeChart] calls).
 *
 * A live chart draws the host's rolling window when the series' scope was granted and the resolver
 * hands back a feed, and falls back to the author's literal values otherwise. The ungranted case is the
 * load-bearing one: a plugin that drew a chart with authored numbers must keep showing those numbers,
 * never a host figure it was not granted the scope for. Pure JVM — no Compose, no monitor.
 */
class PluginScreenChartSeriesTest {

    private val history = MetricHistory(
        points = listOf(
            HistoryPoint(atMillis = 0L, temperatureDeciCelsius = 300),
            HistoryPoint(atMillis = 1L, temperatureDeciCelsius = 310),
            HistoryPoint(atMillis = 2L, temperatureDeciCelsius = 320),
        ),
    )

    /** The author's own numbers, deliberately distinct from the live window so a swap is visible. */
    private val literal = listOf(10f, 20f, 30f)

    @Test
    fun `a granted live series resolves through the resolver, not the literal`() {
        val resolver = PluginScreenStatResolver(
            grantedScopes = setOf(DataScope.THERMALS),
            snapshot = null,
            history = history,
        )
        val resolved = resolveSeries(id = "temp", literal = literal, resolver = resolver::series)
        assertEquals(history.temperatureSeries(), resolved)
    }

    @Test
    fun `an ungranted live series falls back to the authored literal values`() {
        val resolver = PluginScreenStatResolver(
            grantedScopes = emptySet(),
            snapshot = null,
            history = history,
        )
        val resolved = resolveSeries(id = "temp", literal = literal, resolver = resolver::series)
        assertEquals(literal, resolved)
    }
}
