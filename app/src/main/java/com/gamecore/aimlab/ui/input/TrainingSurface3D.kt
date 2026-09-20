package com.gamecore.aimlab.ui.input

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import com.gamecore.aimlab.engine.TouchClassifier
import com.gamecore.aimlab.engine.TouchResult
import com.gamecore.aimlab.render.RenderQuality
import com.gamecore.aimlab.render.RenderState
import com.gamecore.aimlab.render.TrainingGlView
import kotlin.math.hypot

/**
 * The first-person training surface: the GL room underneath, real multi-touch look/shoot on top (§3, §5).
 *
 * The 3D counterpart of [TrainingSurface]. It hosts [TrainingGlView] full-bleed and layers raw pointer
 * handling over it, tracked per pointer id in an `awaitPointerEvent` loop for the same reason the 2D
 * surface does — a tap and a drag must be told apart per finger, and one finger may aim while another
 * fires (§5: look, shoot and ADS at once). A pointer that goes down and comes up under the touch slop is
 * a **shot** (the crosshair is fixed at centre, so the tap position is irrelevant); any pointer that
 * travels is **look**, its per-frame pixel delta handed to [onLookPixels] with the surface width so the
 * sensitivity→degrees conversion is resolution-independent. `aiming` is true while more than one pointer
 * is down — the claw-grip ADS this trains.
 *
 * The GL view owns all per-frame work; this composable only forwards input and pulls the latest
 * [RenderState] for the renderer to draw (§5: sensor/touch events must not recompose the screen). If GL
 * is unavailable the [onGlUnavailable] callback fires and the screen shows its fallback instead.
 *
 * @param renderStateSource the loop's live render snapshot, read on the GL thread each frame.
 * @param onShot a discrete fire at the crosshair; position is not passed because the crosshair is centred.
 * @param onLookPixels a look drag: raw pixel delta plus the surface width and whether aiming.
 * @param quality resolution-scale preset.
 * @param onFrameTime real per-frame time for the debug overlay.
 * @param onGlUnavailable GL setup failed; show the fallback screen.
 */
@Composable
fun TrainingSurface3D(
    renderStateSource: () -> RenderState,
    onShot: () -> Unit,
    onLookPixels: (dxPx: Float, dyPx: Float, widthPx: Int, aiming: Boolean) -> Unit,
    quality: RenderQuality,
    onFrameTime: (Float) -> Unit,
    onGlUnavailable: (String) -> Unit,
    modifier: Modifier = Modifier,
    excludeTouch: ((xFraction: Float, yFraction: Float) -> Boolean)? = null,
) {
    val shot by rememberUpdatedState(onShot)
    val look by rememberUpdatedState(onLookPixels)
    val excluded by rememberUpdatedState(excludeTouch)

    Box(modifier = modifier.fillMaxSize()) {
        TrainingGlView(
            stateSource = renderStateSource,
            quality = quality,
            onFrameTimeMillis = onFrameTime,
            onGlUnavailable = onGlUnavailable,
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier
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
                                        // A pointer that goes down inside an on-screen control (a movement
                                        // strafe pad) belongs to that control for its whole life — never a
                                        // shot, never a look — exactly as the 2D surface excludes them.
                                        val fx = if (size.width > 0) change.position.x / size.width else 0.5f
                                        val fy = if (size.height > 0) change.position.y / size.height else 0.5f
                                        if (excluded?.invoke(fx, fy) == true) {
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
                                        // A shot fires ONLY for a deliberate tap — a pointer that went down
                                        // in free space and lifted having travelled no further than the
                                        // touch slop. Any pointer that ever crossed the slop was a look
                                        // drag (its travel is cumulative and never reset), so it lifts
                                        // silently: a drag can never be scored as a shot or a false start.
                                        // The classification is the pure, unit-tested TouchClassifier so
                                        // this rule is provable, not an inline comparison. This is the fix
                                        // for the Reaction "misses while WAITING" evidence.
                                        if (start != null &&
                                            TouchClassifier.classifyRelease(distance, slop) == TouchResult.TAP
                                        ) {
                                            shot()
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
                                            look(dx, dy, size.width, pressedCount > 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
        )
    }
}
