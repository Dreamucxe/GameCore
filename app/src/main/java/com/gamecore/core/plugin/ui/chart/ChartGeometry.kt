package com.gamecore.core.plugin.ui.chart

import kotlin.math.ceil
import kotlin.math.floor

/**
 * The pure geometry a chart needs before anything is drawn: what vertical range a series plots
 * against, and where a bar chart's bars sit.
 *
 * It is a separate, model-free object for the same reason [ChartSpec] carries no Android type — the
 * decision "what range does this data plot against" is the one most likely to be wrong at an edge
 * (a single repeated value, an inverted author range, an empty live series) and the one a renderer
 * cannot easily be tested on. Kept here, every edge is a JVM unit test, and the Compose node in
 * `PluginChartNode` is left with nothing to decide but how to paint.
 *
 * [autoRange] is a deliberate, tested replica of `com.gamecore.ui.components.autoRange` rather than
 * a call into it: that one lives in a `@Composable` file in the UI layer, and this phase's model
 * must stay buildable and testable without Compose. `ChartGeometryTest` pins the two to the same
 * answer so the replica cannot drift from the graph the rest of the app draws.
 */
object ChartGeometry {

    /** The air left above the tallest point and below the lowest, in data units. Matches `Graphs`. */
    const val DEFAULT_PADDING = 2f

    /** The fraction of each bar's slot left empty, split evenly as the gap between bars. */
    const val DEFAULT_BAR_GAP_RATIO = 0.25f

    /**
     * A vertical range that fits [values], rounded outward to whole units and never zero-width.
     *
     * A replica of the house `autoRange`: floor the padded minimum, ceil the padded maximum, and if
     * the two collapse to less than a unit apart — a series sitting at one value — open the range to
     * a single unit so a flat line still draws through the middle rather than dividing by zero. An
     * empty series yields `0f..1f`, the same neutral plate the live graphs fall back to.
     */
    fun autoRange(values: List<Float>, padding: Float = DEFAULT_PADDING): ClosedFloatingPointRange<Float> {
        val finite = values.filter { it.isFinite() }
        val min = finite.minOrNull() ?: 0f
        val max = finite.maxOrNull() ?: 1f
        val low = floor(min - padding)
        val high = ceil(max + padding)
        return if (high - low < 1f) low..(low + 1f) else low..high
    }

    /**
     * The range a chart actually plots against: the author's explicit [ChartSpec.explicitRange] when
     * they pinned a valid one, or an [autoRange] over whatever data the host resolved otherwise.
     *
     * Both series feed the auto-scale so a secondary line is never clipped by a range fitted to the
     * primary alone. This is computed at render time rather than decode time because a live series'
     * values are not known until the host resolves them.
     */
    fun effectiveRange(
        spec: ChartSpec,
        primary: List<Float>,
        secondary: List<Float> = emptyList(),
    ): ClosedFloatingPointRange<Float> {
        spec.explicitRange()?.let { return it }
        val all = if (secondary.isEmpty()) primary else primary + secondary
        return autoRange(all)
    }

    /** One bar's rectangle in pixels, top-left origin — ready for a `Canvas` `drawRect`. */
    data class BarRect(val left: Float, val top: Float, val width: Float, val height: Float)

    /**
     * Lays [values] out as evenly spaced bars across [width], each as tall as its value's height in
     * [range] over [height]. Points are spaced evenly rather than by any timestamp, matching how the
     * line graph plots — the two are the same picture of the same sampling interval.
     *
     * A value below the range floor is a zero-height bar and one above the ceiling is clamped to full
     * height, so a stray reading cannot draw a bar past the top of the plot or below its baseline. An
     * empty series, or a zero-area plot, is no bars at all.
     */
    fun barRects(
        values: List<Float>,
        range: ClosedFloatingPointRange<Float>,
        width: Float,
        height: Float,
        gapRatio: Float = DEFAULT_BAR_GAP_RATIO,
    ): List<BarRect> {
        if (values.isEmpty() || width <= 0f || height <= 0f) return emptyList()
        val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
        val gap = gapRatio.coerceIn(0f, 0.9f)
        val slot = width / values.size
        val barWidth = slot * (1f - gap)
        val sideGap = (slot - barWidth) / 2f
        return values.mapIndexed { index, value ->
            val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
            val barHeight = height * fraction
            BarRect(
                left = index * slot + sideGap,
                top = height - barHeight,
                width = barWidth,
                height = barHeight,
            )
        }
    }
}
