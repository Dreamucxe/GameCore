package com.gamecore.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.gamecore.core.model.MetricHistory
import com.gamecore.ui.theme.ChartAlternate
import kotlin.math.ceil
import kotlin.math.floor
/**
 * The graphs. Drawn on a `Canvas`, from a list of floats, and nothing else.
 *
 * A charting library would be several hundred kilobytes to plot up to two series of at most
 * [MetricHistory.CAPACITY] points, and would want its own theme. So this is a path, a fill and two
 * gridlines — which is also why it can afford to redraw at the sampler's interval without the frame cost
 * showing up in the very numbers it is plotting.
 *
 * Two rules the drawing follows:
 *
 *  - **A gap is not a zero.** The series handed in are already `mapNotNull`-ed by [MetricHistory], so a
 *    device that cannot report a temperature yields a shorter list rather than a line along the floor.
 *    Under [MetricHistory.MIN_PLOTTABLE] points there is nothing honest to draw and the card says so.
 *  - **The vertical range is explicit.** A percentage plots against 0–100 so the user can see that 40%
 *    is less than half the plate; a temperature auto-scales, because 38–44 °C plotted against 0–100 is a
 *    flat line that hides exactly the movement the user opened the screen for.
 */

/** The height every graph in the app is drawn at, unless a screen has a reason to differ. */
private const val DEFAULT_GRAPH_HEIGHT = 116

/**
 * A single or dual series line plot.
 *
 * [secondary] is drawn under [series] in [ChartAlternate] — a cool blue that reads as "the other line"
 * against any of the six accents, which is why it is not another tint of the primary.
 */
@Composable
fun LineGraph(
    series: List<Float>,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    colour: Color = MaterialTheme.colorScheme.primary,
    secondary: List<Float> = emptyList(),
    secondaryColour: Color = ChartAlternate,
    graphHeight: Int = DEFAULT_GRAPH_HEIGHT,
) {
    val gridColour = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(graphHeight.dp),
    ) {
        drawGrid(gridColour)
        if (secondary.size >= MetricHistory.MIN_PLOTTABLE) {
            drawSeries(secondary, valueRange, secondaryColour, fill = false)
        }
        if (series.size >= MetricHistory.MIN_PLOTTABLE) {
            drawSeries(series, valueRange, colour, fill = true)
        }
    }
}

/** Three horizontal rules: the floor, the middle and the ceiling of the plotted range. */
private fun DrawScope.drawGrid(colour: Color) {
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

/**
 * One series, as a stroked path and optionally a fade beneath it.
 *
 * Points are spaced evenly across the width rather than by timestamp. The sampler's interval is fixed
 * while a graph is being filled, so the two are the same picture — and plotting by timestamp would make
 * the line stretch and squash whenever the user changed the interval, which looks like the device did
 * something.
 */
private fun DrawScope.drawSeries(
    values: List<Float>,
    range: ClosedFloatingPointRange<Float>,
    colour: Color,
    fill: Boolean,
) {
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val stepX = if (values.size > 1) size.width / (values.size - 1) else size.width
    val stroke = 2.2f * density
    val top = stroke / 2f
    val usable = (size.height - stroke).coerceAtLeast(1f)

    fun yFor(value: Float): Float {
        val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
        return top + usable * (1f - fraction)
    }

    val line = Path()
    values.forEachIndexed { index, value ->
        val x = stepX * index
        val y = yFor(value)
        if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
    }
    if (fill) {
        val area = Path()
        area.addPath(line)
        area.lineTo(stepX * (values.size - 1), size.height)
        area.lineTo(0f, size.height)
        area.close()
        drawPath(
            path = area,
            brush = Brush.verticalGradient(
                listOf(colour.copy(alpha = 0.28f), colour.copy(alpha = 0.02f)),
            ),
        )
    }
    drawPath(
        path = line,
        color = colour,
        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * A titled graph with its scale, its legend and its own "not enough data yet" state.
 *
 * The ceiling and floor of the range are printed beside the plot, because a line without a scale is a
 * shape rather than a measurement — and this app's whole claim is that its numbers mean something.
 *
 * [note] is for the caveat a line cannot carry. A series whose points are not one reading each — a value
 * held between refreshes, for instance — draws a shape that overstates how long something lasted, and the
 * fix is to say so under the plot rather than to leave the reader to assume otherwise.
 */
@Composable
fun GraphCard(
    title: String,
    series: List<Float>,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    seriesLabel: String? = null,
    secondary: List<Float> = emptyList(),
    secondaryLabel: String? = null,
    note: String? = null,
    format: (Float) -> String = { it.toInt().toString() },
    emptyMessage: String = "Collecting samples.",
) {
    val primaryColour = MaterialTheme.colorScheme.primary
    SectionCard(title = title, modifier = modifier) {
        if (series.size < MetricHistory.MIN_PLOTTABLE) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 18.dp),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier
                        .height(DEFAULT_GRAPH_HEIGHT.dp)
                        .padding(end = 8.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    ScaleLabel(format(valueRange.endInclusive))
                    ScaleLabel(format(valueRange.start))
                }
                LineGraph(
                    series = series,
                    valueRange = valueRange,
                    secondary = secondary,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (seriesLabel != null) LegendKey(seriesLabel, primaryColour)
                if (secondaryLabel != null && secondary.size >= MetricHistory.MIN_PLOTTABLE) {
                    LegendKey(secondaryLabel, ChartAlternate)
                }
            }
            if (note != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScaleLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A dot and a name. Which line is which, without a legend library. */
@Composable
fun LegendKey(label: String, colour: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(colour),
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A vertical range that fits the data, with a little air above and below.
 *
 * For temperature and frame-rate plots, where a fixed 0–100 would flatten the line. Rounded outward to
 * whole units so the printed scale labels are not `38.4` and `44.1`, and never zero-width, so a device
 * sitting at exactly one value still draws a line through the middle rather than dividing by zero.
 */
fun autoRange(values: List<Float>, padding: Float = 2f): ClosedFloatingPointRange<Float> {
    val min = values.minOrNull() ?: 0f
    val max = values.maxOrNull() ?: 1f
    val low = floor(min - padding)
    val high = ceil(max + padding)
    return if (high - low < 1f) low..(low + 1f) else low..high
}
