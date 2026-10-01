package com.gamecore.ui.trigger

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gamecore.core.model.FractionPoint
import com.gamecore.core.overlay.OverlayPalette
import com.gamecore.ui.components.screenAspectRatio

/**
 * An in-app preview, shaped like this device's screen, in which the trigger point is placed by pointing at
 * it — the same act the crosshair editor uses to place a sight (§9's [com.gamecore.ui.crosshair] picker).
 *
 * The point is where the injected tap will land, so the way to say it is to touch there rather than to type
 * two numbers. The stored value is a [FractionPoint] in [0, 1] on each axis, which means the same place on
 * any screen the profile is later carried to, and which is why the box is drawn at [screenAspectRatio] — a
 * point dragged into a corner here lands in the same corner when the tap is injected for real.
 *
 * Store-on-release, like the crosshair's drag: [onPointChange] fires continuously so the marker follows the
 * finger, and [onCommit] fires once when the gesture ends, so the caller can write the profile row once per
 * placement instead of forty times a second. A tap has no end to wait for, so it reports its point and
 * commits in the same breath. Fractions are clamped to the frame here, so a drag past the edge lands the
 * tap on the edge rather than off the screen.
 */
@Composable
fun VolumeTriggerPointPicker(
    point: FractionPoint?,
    onPointChange: (FractionPoint) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var canvas by remember { mutableStateOf(IntSize.Zero) }
    val markerColour = MaterialTheme.colorScheme.primary
    val guideColour = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(screenAspectRatio())
            .clip(RoundedCornerShape(12.dp))
            .background(OverlayPalette.PanelPlate)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .onSizeChanged { canvas = it }
            .pointerInput(canvas) {
                if (canvas.width <= 0 || canvas.height <= 0) return@pointerInput
                detectTapGestures { offset ->
                    onPointChange(offset.toFraction(canvas))
                    onCommit()
                }
            }
            .pointerInput(canvas) {
                if (canvas.width <= 0 || canvas.height <= 0) return@pointerInput
                detectDragGestures(
                    onDragEnd = onCommit,
                    onDrag = { change, _ -> onPointChange(change.position.toFraction(canvas)) },
                )
            },
    ) {
        CentreGuides(guideColour)
        if (point != null) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val cx = point.x.coerceIn(0f, 1f) * size.width
                val cy = point.y.coerceIn(0f, 1f) * size.height
                val centre = Offset(cx, cy)
                val ring = 15.dp.toPx()
                val arm = 12.dp.toPx()
                val stroke = 2.dp.toPx()
                drawLine(markerColour, Offset(cx - ring - arm, cy), Offset(cx - ring, cy), stroke)
                drawLine(markerColour, Offset(cx + ring, cy), Offset(cx + ring + arm, cy), stroke)
                drawLine(markerColour, Offset(cx, cy - ring - arm), Offset(cx, cy - ring), stroke)
                drawLine(markerColour, Offset(cx, cy + ring), Offset(cx, cy + ring + arm), stroke)
                drawCircle(markerColour, radius = ring, center = centre, style = Stroke(width = stroke))
                drawCircle(markerColour, radius = 2.5.dp.toPx(), center = centre)
            }
        } else {
            Text(
                text = "Tap or drag in the frame to place the trigger point",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
            )
        }
    }
}

/** A pointer position turned into a screen fraction, clamped to the frame so the tap never lands off it. */
private fun Offset.toFraction(canvas: IntSize): FractionPoint = FractionPoint(
    x = (x / canvas.width).coerceIn(0f, 1f),
    y = (y / canvas.height).coerceIn(0f, 1f),
)

/** Where dead centre is, so a placement can be judged against something — as the crosshair preview does. */
@Composable
private fun BoxScope.CentreGuides(colour: Color) {
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxWidth()
            .height(1.dp)
            .background(colour),
    )
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxHeight()
            .width(1.dp)
            .background(colour),
    )
}
