package com.gamecore.aimlab.ui.input

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import com.gamecore.aimlab.TrainingFrame
import com.gamecore.aimlab.TrainingLoop
import com.gamecore.aimlab.engine.Vec2
import kotlin.math.hypot

/**
 * The surface every training mode plays on: one multi-touch input area and one canvas.
 *
 * Input is real `PointerInputChange` data tracked per pointer id (§10), not a gesture detector — a
 * training arena has to tell a tap apart from a drag *per finger*, and let one finger aim while another
 * shoots, which `detectTapGestures`/`detectDragGestures` cannot do simultaneously on the same modifier.
 * `awaitPointerEvent` in a loop is the one form that gives every pointer's position on every frame.
 *
 * Deliberately NOT `pointerInteropFilter`: that is an experimental API this project has no opt-in for, and
 * the raw-MotionEvent path is unnecessary here — Compose's pointer events already carry the ids. It also
 * matters that `MainActivity` overrides only `dispatchKeyEvent`/`dispatchGenericMotionEvent` and never
 * `dispatchTouchEvent`, so nothing upstream can swallow a touch before it reaches this surface.
 *
 * A pointer that goes down and comes up having travelled less than the platform touch slop is a **shot**,
 * fired at the position it went down (not where it came up, so a slight roll of the thumb does not drag the
 * shot off the target). Any pointer that travels further is **aim**: its per-frame delta is converted to a
 * look delta and handed to the loop, which runs it through the sensitivity curve. `aiming` is true while
 * more than one pointer is down, which is the ADS-style two-finger claw grip this trains for.
 *
 * Nothing here accumulates history or allocates per event beyond the small per-pointer maps: the loop
 * samples its own state on its ticker, so a 240 Hz digitiser costs the same number of recompositions as a
 * 60 Hz one (§21).
 *
 * [excludeTouch] carves out the regions an on-screen control occupies, in arena fractions. A pointer that
 * goes **down** inside one is ignored for its whole life — not a shot, not aim — and the control's own
 * composable handles it instead. This cannot be done by consuming the event in the control: the handler
 * below deliberately reads `changedToDownIgnoreConsumed`, so a thumb resting on a movement stick would
 * otherwise register as a shot into empty space and count as a miss against the player's accuracy. The
 * decision is made once, on the down, so a stick drag that wanders out of its own box does not suddenly
 * become an aim delta halfway through.
 */
@Composable
fun TrainingSurface(
    loop: TrainingLoop,
    frame: TrainingFrame,
    modifier: Modifier = Modifier,
    crosshairColour: Color = Color.White,
    excludeTouch: ((Vec2) -> Boolean)? = null,
    drawArena: DrawScope.(size: Size) -> Unit,
) {
    // The screen may hand us a fresh loop when a run restarts; capture the latest without re-arming the
    // pointer filter, which would drop any finger currently down.
    val current by rememberUpdatedState(loop)
    val excluded by rememberUpdatedState(excludeTouch)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                val downAt = HashMap<PointerId, Offset>(8)
                val lastAt = HashMap<PointerId, Offset>(8)
                val travelled = HashMap<PointerId, Float>(8)
                val ignored = HashSet<PointerId>(4)

                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed && it.id !in ignored }
                        event.changes.forEach { change ->
                            when {
                                change.changedToDownIgnoreConsumed() -> {
                                    val arena = TouchMath.toArena(
                                        change.position.x,
                                        change.position.y,
                                        size.width,
                                        size.height,
                                    )
                                    if (excluded?.invoke(arena) == true) {
                                        ignored += change.id
                                    } else {
                                        downAt[change.id] = change.position
                                        lastAt[change.id] = change.position
                                        travelled[change.id] = 0f
                                    }
                                }

                                change.changedToUpIgnoreConsumed() -> {
                                    if (ignored.remove(change.id)) return@forEach
                                    val start = downAt.remove(change.id)
                                    val distance = travelled.remove(change.id) ?: 0f
                                    lastAt.remove(change.id)
                                    if (start != null && distance <= slop) {
                                        val arena = TouchMath.toArena(start.x, start.y, size.width, size.height)
                                        current.onShot(arena.x, arena.y)
                                    }
                                }

                                change.pressed -> {
                                    if (change.id in ignored) return@forEach
                                    val previous = lastAt[change.id] ?: change.position
                                    val dx = change.position.x - previous.x
                                    val dy = change.position.y - previous.y
                                    if (dx != 0f || dy != 0f) {
                                        lastAt[change.id] = change.position
                                        travelled[change.id] = (travelled[change.id] ?: 0f) + hypot(dx, dy)
                                        val look = TouchMath.deltaToLook(dx, dy, size.width, size.height)
                                        current.onAimDelta(look.x, look.y, aiming = pressedCount > 1)
                                    }
                                }
                            }
                        }
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawArena(size)
            drawCrosshair(frame.crosshair, crosshairColour)
        }
    }
}

/**
 * The aim point, in the arena's own normalised coordinates.
 *
 * A gap in the middle rather than a solid cross, so a target sitting exactly under the crosshair is still
 * readable — the same reason the existing crosshair presets leave a centre gap.
 */
private fun DrawScope.drawCrosshair(position: Vec2, colour: Color) {
    val cx = position.x * size.width
    val cy = position.y * size.height
    val arm = size.minDimension * ARM_FRACTION
    val gap = arm * GAP_FRACTION

    drawLine(colour, Offset(cx - arm, cy), Offset(cx - gap, cy), strokeWidth = STROKE)
    drawLine(colour, Offset(cx + gap, cy), Offset(cx + arm, cy), strokeWidth = STROKE)
    drawLine(colour, Offset(cx, cy - arm), Offset(cx, cy - gap), strokeWidth = STROKE)
    drawLine(colour, Offset(cx, cy + gap), Offset(cx, cy + arm), strokeWidth = STROKE)
    drawCircle(colour.copy(alpha = 0.85f), radius = STROKE, center = Offset(cx, cy))
}

/**
 * Draws a target disc for the arena, given a centre and radius in arena fractions.
 *
 * Offered here so every mode's `drawArena` renders targets identically — radius scales off the smaller
 * dimension so a target is a circle rather than an ellipse on any aspect ratio, matching how the engine
 * defines [com.gamecore.aimlab.engine.Target.radius].
 */
fun DrawScope.drawTarget(
    center: Vec2,
    radius: Float,
    colour: Color,
    highlighted: Boolean = false,
) {
    val cx = center.x * size.width
    val cy = center.y * size.height
    val r = radius * size.minDimension
    drawCircle(colour.copy(alpha = if (highlighted) 0.95f else 0.7f), radius = r, center = Offset(cx, cy))
    drawCircle(
        colour,
        radius = r,
        center = Offset(cx, cy),
        style = Stroke(width = if (highlighted) STROKE * 1.5f else STROKE),
    )
}

private const val ARM_FRACTION = 0.035f
private const val GAP_FRACTION = 0.28f
private const val STROKE = 3f
