package com.gamecore.ui.plugins.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.gamecore.core.plugin.ui.ComponentNode
import com.gamecore.core.plugin.ui.chart.ChartGeometry
import com.gamecore.core.plugin.ui.chart.ChartKind
import com.gamecore.core.plugin.ui.chart.ChartNodeCodec
import com.gamecore.core.plugin.ui.chart.ChartSpec
import com.gamecore.ui.components.LegendKey
import com.gamecore.ui.components.LineGraph
import com.gamecore.ui.theme.ChartAlternate

/** The height a plugin chart is drawn at, matching the app's own graphs. */
private const val CHART_HEIGHT_DP = 116

/**
 * `CHART`: a declarative plot the host draws from a [ChartSpec], reusing the app's own
 * [LineGraph] so a plugin's chart is the same picture GameCore draws for its own metrics.
 *
 * **This stays read-only by construction, like every node in this file (see the file KDoc).** A
 * chart carries data, never code and never arbitrary artwork: its shape is one of [ChartKind]'s two
 * and its series are numbers, so there is nothing here to execute and nothing to decode from plugin
 * bytes.
 *
 * ### How a series resolves
 *
 * Each series is either *literal* — numbers the plugin shipped, read straight from the node — or
 * *live* — the id of one of GameCore's approved stat feeds, resolved through [series] against the
 * host's rolling history. [series] is the chart's counterpart to the renderer's `stats` resolver: it
 * rides behind the same `READ_APPROVED_DATA` scope, which is why the full-screen and dashboard hosts
 * pass a real resolver and the overlay host passes the default `{ null }`. When a series names a live
 * id, that wins; when the resolver returns `null` — no grant, or a host with no feed — the literal
 * values are the fallback, so a chart degrades to the data it shipped rather than to a crash. The
 * resolver is wrapped, so one that throws on an unknown id costs that series, not the window.
 *
 * This is why an overlay chart must be literal-only and the overlay codec refuses a live series id:
 * an overlay module's data appetite is walked from its `stat` nodes, and a live chart series would
 * not be seen by that walk, so it would ride behind no granted scope.
 */
@Composable
internal fun NodeChart(
    node: ComponentNode,
    series: (String) -> List<Float>?,
    modifier: Modifier = Modifier,
) {
    val spec = remember(node) { ChartNodeCodec.decode(node) }
    val scheme = MaterialTheme.colorScheme
    val primaryColour = spec.colorArgb?.let { Color(it) } ?: scheme.primary
    val secondaryColour = spec.color2Argb?.let { Color(it) } ?: ChartAlternate

    val primary = resolveSeries(spec.seriesId, spec.values, series)
    val secondary = resolveSeries(spec.series2Id, spec.values2 ?: emptyList(), series)
    val range = ChartGeometry.effectiveRange(spec, primary, secondary)

    Column(modifier = modifier) {
        when (spec.kind) {
            ChartKind.LINE -> LineGraph(
                series = primary,
                valueRange = range,
                colour = primaryColour,
                secondary = secondary,
                secondaryColour = secondaryColour,
                graphHeight = CHART_HEIGHT_DP,
            )
            ChartKind.BAR -> BarGraph(
                values = primary,
                range = range,
                colour = primaryColour,
            )
        }
        ChartLegend(spec, primaryColour, secondaryColour, hasSecondary = secondary.isNotEmpty())
        spec.note?.let { note ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Picks a series' data: the live feed when one is named and resolves, the literal values otherwise.
 *
 * Not a `@Composable` — it is plain logic called inside composition, so it can be reasoned about and
 * (through [ChartNodeCodec] and [ChartGeometry]) tested without Compose. The resolver is wrapped so a
 * host resolver that throws on an unknown id yields the literal fallback rather than failing the draw.
 */
internal fun resolveSeries(
    id: String?,
    literal: List<Float>,
    resolver: (String) -> List<Float>?,
): List<Float> {
    if (id != null) {
        runCatching { resolver(id) }.getOrNull()?.let { return it }
    }
    return literal
}

/**
 * The legend under a chart: a dot and a name per labelled series, reusing the app's [LegendKey].
 *
 * A series is in the legend only when the author named it, and the secondary key appears only for a
 * line chart that actually drew a second line — a bar chart draws its primary series alone, so a
 * secondary label there would point at nothing.
 */
@Composable
private fun ChartLegend(
    spec: ChartSpec,
    primaryColour: Color,
    secondaryColour: Color,
    hasSecondary: Boolean,
) {
    val showSecondary = spec.kind == ChartKind.LINE && hasSecondary && spec.label2 != null
    if (spec.label == null && !showSecondary) return
    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        spec.label?.let { LegendKey(it, primaryColour) }
        if (showSecondary) LegendKey(spec.label2!!, secondaryColour)
    }
}

/**
 * A bar plot, drawn on a `Canvas` from [ChartGeometry.barRects] — the bar counterpart to [LineGraph],
 * kept here rather than added to the shared `Graphs.kt` so this phase adds no edit to that file.
 *
 * The same three gridlines as the line graph, so the two kinds read as one family, and bars laid out
 * by [ChartGeometry] so the geometry that decides a bar's height is the part under unit test.
 */
@Composable
private fun BarGraph(
    values: List<Float>,
    range: ClosedFloatingPointRange<Float>,
    colour: Color,
    modifier: Modifier = Modifier,
    graphHeight: Int = CHART_HEIGHT_DP,
) {
    val gridColour = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(graphHeight.dp),
    ) {
        drawChartGrid(gridColour)
        ChartGeometry.barRects(values, range, size.width, size.height).forEach { bar ->
            drawRect(
                color = colour,
                topLeft = Offset(bar.left, bar.top),
                size = Size(bar.width, bar.height),
            )
        }
    }
}

/** Three horizontal rules — floor, middle, ceiling — matching `Graphs.kt`'s own grid. */
private fun DrawScope.drawChartGrid(colour: Color) {
    val lines = 3
    repeat(lines) { index ->
        val y = size.height * index / (lines - 1).toFloat()
        val inset = 0.5f
        drawLine(
            color = colour,
            start = Offset(0f, y.coerceIn(inset, size.height - inset)),
            end = Offset(size.width, y.coerceIn(inset, size.height - inset)),
            strokeWidth = 1f,
        )
    }
}
