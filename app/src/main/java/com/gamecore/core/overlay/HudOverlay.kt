package com.gamecore.core.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamecore.core.model.HudLayout
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.HudWidget
import com.gamecore.domain.monitoring.StatReading
import kotlin.math.roundToInt

/**
 * The user's HUD layout, drawn over the game.
 *
 * The interesting part is the placement, not the widgets. Positions are fractions of the screen — see
 * [HudWidget]'s KDoc for why — and turning a fraction into a pixel offset is where the two mistakes
 * live:
 *
 *  1. A widget at `xFraction = 0.98` placed at 98% of the screen width has almost all of itself off the
 *     right edge. Its own measured width has to come off the limit, exactly as in [OverlayFrame], which
 *     is why this uses a `Layout` rather than a `Box` full of `offset` modifiers: the clamp needs each
 *     child's measured size, and an `offset` is applied before anything knows it.
 *  2. A widget wider than the screen — a long session timer at 28 sp on a small phone in portrait —
 *     would clamp into a negative range. The limit is floored at zero, so it starts at the left edge and
 *     is cut off on the right rather than being placed off-screen to the left.
 *
 * Every string drawn here comes from [StatReading] or from [com.gamecore.core.model.HudStat]'s compiled
 * labels. The one piece of user text a layout carries — its name — is not rendered in the overlay, so
 * §24A's sanitise-before-render rule has nothing to bite on in this window. That is a property worth
 * keeping: if a future widget ever draws a user-supplied caption, it must be sanitised at the point it
 * is stored, not here.
 */
@Composable
fun HudOverlay(
    layout: HudLayout,
    readings: Map<HudStat, StatReading>,
    modifier: Modifier = Modifier,
) {
    val widgets = layout.widgets
    if (widgets.isEmpty()) return

    Layout(
        modifier = modifier,
        content = {
            widgets.forEach { widget ->
                val reading = readings[widget.stat] ?: StatReading(widget.stat, value = null)
                HudWidgetView(widget = widget, reading = reading)
            }
        },
    ) { measurables, constraints ->
        // Loosened so a widget measures at its natural size rather than being stretched to the screen.
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val widget = widgets[index]
                val maxX = (constraints.maxWidth - placeable.width).coerceAtLeast(0)
                val maxY = (constraints.maxHeight - placeable.height).coerceAtLeast(0)
                placeable.place(
                    x = (widget.xFraction * constraints.maxWidth).roundToInt().coerceIn(0, maxX),
                    y = (widget.yFraction * constraints.maxHeight).roundToInt().coerceIn(0, maxY),
                )
            }
        }
    }
}

/**
 * One HUD widget.
 *
 * Shares [PerformancePill]'s conventions — monospaced digits, dimmed unavailable readings, opacity
 * applied to the plate as well as the text — because the two are the same information in two
 * arrangements and a user who configured one should recognise the other.
 *
 * The plate is optional per widget: a single figure in a corner reads better without one, and a widget
 * over a busy scene is illegible without one. That is a judgement only the person looking at their own
 * game can make, so it is a setting rather than a decision made here.
 *
 * `internal` rather than private because the HUD builder's preview draws its widgets with this same
 * function. A preview that had its own copy of this rendering would drift from it, and a builder whose
 * preview does not match the overlay is a builder that lies about what it is building.
 */
@Composable
internal fun HudWidgetView(widget: HudWidget, reading: StatReading) {
    val normalised = widget.normalised()
    val size = normalised.textSizeSp.sp
    val colour = Color(normalised.colorArgb)
    val plate = if (normalised.showBackground) {
        Modifier
            .background(OverlayPalette.Plate, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    } else {
        Modifier
    }

    Row(
        modifier = Modifier
            .alpha(normalised.opacityPercent / 100f)
            .then(plate),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (normalised.showLabel && normalised.stat.shortLabel.isNotEmpty()) {
            BasicText(
                text = normalised.stat.shortLabel,
                style = TextStyle(
                    color = OverlayPalette.Muted,
                    fontSize = size * 0.82f,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
        BasicText(
            text = reading.display(),
            style = TextStyle(
                // The widget's own colour when there is a figure, the shared dim grey when there is
                // not: a user's chosen bright green on `--` reads as a measurement of zero.
                color = if (reading.isAvailable) colour else OverlayPalette.Absent,
                fontSize = size,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}
