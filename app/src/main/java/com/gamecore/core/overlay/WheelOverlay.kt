package com.gamecore.core.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The high-sensitivity wheels guide (§Wheels): a feel-only ring marking where an on-screen stick sits.
 *
 * The honest half of the feature. GameCore cannot reach into another game and re-scale its virtual
 * joystick — that would need touch injection into a process it does not own, which the platform does not
 * offer and anti-cheat rightly treats as tampering — so what it draws here is a *guide*: a ring, a hub and
 * eight direction ticks over the stick's resting place, so the player can see the throw they are working
 * with and build a consistent, faster wheel by feel. The window hosting it is `FLAG_NOT_TOUCHABLE`, exactly
 * like [CrosshairOverlay]: it is a sticker on the glass, takes no touches, reads nothing, and moves on its
 * own never. The genuine sensitivity remap the feature pairs with lives inside GameCore's own Aim Lab
 * training surface, where there is a real stick to remap and no other app to touch.
 *
 * Everything is drawn with Compose primitives from a single [radiusPercent] (a fraction of the screen's
 * shorter edge, so it scales across displays), so the guide is sharp at any size and the APK carries no
 * bitmaps for it.
 */
@Composable
fun WheelOverlay(radiusPercent: Int, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val shortEdge = min(size.width, size.height)
            if (shortEdge <= 0f) return@Canvas
            val radius = shortEdge * (radiusPercent.coerceIn(4, 60) / 100f)
            if (radius <= 0f) return@Canvas

            // The resting place of a movement stick: low and to the left, where a left thumb sits. A guide,
            // not a control, so a fixed sensible spot is honest — there is nothing here to mis-tap.
            val centre = Offset(x = size.width * CENTRE_X_FRACTION, y = size.height * CENTRE_Y_FRACTION)
            val thickness = (radius * RING_THICKNESS_FRACTION).coerceAtLeast(MIN_THICKNESS_PX)
            val ringStroke = Stroke(width = thickness, cap = StrokeCap.Round)

            // Outline first, wider and dark, for the same reason the crosshair has one: a thin bright ring
            // vanishes over a bright scene, and a guide that disappears in some maps is worse than none.
            drawCircle(
                color = Color.Black.copy(alpha = OUTLINE_ALPHA),
                radius = radius,
                center = centre,
                style = Stroke(width = thickness + OUTLINE_EXTRA_PX, cap = StrokeCap.Round),
            )
            drawCircle(color = RING_COLOUR, radius = radius, center = centre, style = ringStroke)

            // Eight direction ticks just outside the ring — the wheel's "spokes", so the throw reads as a
            // wheel rather than a plain circle.
            val tickInner = radius * TICK_INNER_FRACTION
            val tickOuter = radius * TICK_OUTER_FRACTION
            for (i in 0 until TICK_COUNT) {
                val angle = (2.0 * Math.PI * i / TICK_COUNT)
                val dx = cos(angle).toFloat()
                val dy = sin(angle).toFloat()
                drawLine(
                    color = RING_COLOUR,
                    start = Offset(centre.x + dx * tickInner, centre.y + dy * tickInner),
                    end = Offset(centre.x + dx * tickOuter, centre.y + dy * tickOuter),
                    strokeWidth = thickness,
                    cap = StrokeCap.Round,
                )
            }

            // The hub: where the thumb rests. Outlined then filled, like the ring.
            val hubRadius = (radius * HUB_FRACTION).coerceAtLeast(MIN_THICKNESS_PX)
            drawCircle(
                color = Color.Black.copy(alpha = OUTLINE_ALPHA),
                radius = hubRadius + OUTLINE_EXTRA_PX,
                center = centre,
            )
            drawCircle(color = RING_COLOUR, radius = hubRadius, center = centre)
        }
    }
}

/** Bottom-left resting place of a left movement stick, as fractions of the screen. */
private const val CENTRE_X_FRACTION = 0.2f
private const val CENTRE_Y_FRACTION = 0.74f

private const val RING_THICKNESS_FRACTION = 0.06f
private const val MIN_THICKNESS_PX = 2.5f

/** Ticks sit just outside the ring, spanning from inside its stroke to a short reach beyond it. */
private const val TICK_COUNT = 8
private const val TICK_INNER_FRACTION = 0.9f
private const val TICK_OUTER_FRACTION = 1.16f

private const val HUB_FRACTION = 0.12f

private const val OUTLINE_EXTRA_PX = 2.5f
private const val OUTLINE_ALPHA = 0.6f

/** A warm amber, distinct from the crosshair's usual cyan so the two never read as one control. */
private val RING_COLOUR = Color(0xFFFFC24D).copy(alpha = 0.92f)
