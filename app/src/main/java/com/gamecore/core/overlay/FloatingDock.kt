package com.gamecore.core.overlay

import android.view.MotionEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gamecore.core.model.DockConfig

/**
 * The draggable floating dock (feature 1) — the floating button's sibling.
 *
 * A compact pill/handle at rest that the user drags to move, snaps to an edge, and taps to expand into
 * the compact [DockPanel]. It clones [FloatingGameButton]'s touch handling deliberately: the same
 * raw-screen-coordinate drag folded through [DragGesture], the same touch-slop tap-vs-drag decision, the
 * same fire-once haptic the instant a touch becomes a drag, and the same "full opacity while dragged or
 * expanded, [DockConfig.opacity] at rest" fade. See [DragGesture] for *why* the drag is tracked in raw
 * coordinates rather than through `detectDragGestures`.
 *
 * One difference from the button, and it is the reason this reports floats where the button reports ints:
 * the button reads its live window position through a `positionProvider` and folds the drag *here*,
 * handing the service a finished window target. The dock instead reports the **raw** screen coordinates
 * and lets the service fold them (its `dragDockTo`), which keeps this composable free of any knowledge of
 * where the dock currently sits. [onDragTo] therefore fires the raw `rawX`/`rawY` on every drag frame
 * past the slop, and [onDragFinished] carries no coordinates — the service already has the last raw point
 * and owns the clamp, the edge snap and the persisted fraction, exactly as it does for the button.
 *
 * `pointerInteropFilter` is opted into for the same reason the button opts in: it is the only way to see
 * `rawX`/`rawY`, the coordinate space a window drag has to happen in.
 *
 * @param config the dock's size, rest opacity and haptic preference.
 * @param expanded true while the [DockPanel] is open, so the handle can show it is the thing that opened
 *   it and stay at full opacity. The service owns this state and flips it on [onTap].
 * @param onTap a tap (travel within the touch slop); the service toggles [expanded].
 * @param onDragTo the raw screen coordinates on every drag frame past the slop; the service folds them
 *   into a clamped window position.
 * @param onDragFinished the drag ended, or was cancelled mid-drag; the service commits and snaps.
 * @param modifier applied to the handle's root box.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FloatingDock(
    config: DockConfig,
    expanded: Boolean,
    onTap: () -> Unit,
    onDragTo: (rawX: Float, rawY: Float) -> Unit,
    onDragFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val slopPx = LocalViewConfiguration.current.touchSlop
    var gesture by remember { mutableStateOf(DragGesture.IDLE) }

    // Fired once per gesture, at the moment a touch becomes a drag. Without the flag every ACTION_MOVE
    // past the slop would buzz and a long drag would be a continuous vibration — the button's guard.
    var hasBuzzed by remember { mutableStateOf(false) }

    val targetAlpha = if (expanded || gesture.isActive) 1f else config.opacity
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = FADE_MILLIS),
        label = "floatingDockAlpha",
    )

    Box(
        modifier = modifier
            .size(width = config.sizeDp.dp * WIDTH_FACTOR, height = config.sizeDp.dp)
            .alpha(alpha)
            .background(OverlayPalette.Plate, DockShape)
            .border(BORDER_DP.dp, OverlayPalette.Muted.copy(alpha = BORDER_ALPHA), DockShape)
            .semantics { contentDescription = if (expanded) EXPANDED_DESCRIPTION else REST_DESCRIPTION }
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        hasBuzzed = false
                        // Window origin is unused here: the dock reports raw coordinates and the service
                        // folds them, so DragGesture is used only for its raw travel and the slop decision.
                        gesture = gesture.began(event.rawX, event.rawY, 0, 0)
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val moved = gesture.movedTo(event.rawX, event.rawY)
                        gesture = moved
                        // Nothing moves until the finger passes the platform slop, so a tap that wobbles a
                        // couple of pixels does not nudge the dock and then expand from a spot the user did
                        // not choose.
                        if (moved.isDrag(slopPx)) {
                            if (config.hapticFeedback && !hasBuzzed) {
                                hasBuzzed = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            onDragTo(event.rawX, event.rawY)
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        val ended = gesture.movedTo(event.rawX, event.rawY)
                        gesture = ended.ended()
                        if (ended.isTap(slopPx)) {
                            if (config.hapticFeedback) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            onTap()
                        } else {
                            onDragFinished()
                        }
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        // A cancel took the gesture away mid-drag. The dock is left where it is and the
                        // position is committed, so a service restart does not move it back to somewhere the
                        // user can see it is not — the same reasoning as the button's cancel path.
                        val ended = gesture.ended()
                        gesture = ended
                        if (ended.isDrag(slopPx)) onDragFinished()
                        true
                    }

                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The grip: three dots that read as "drag me / more", brightened from muted to full when the panel
        // is open so the handle shows it is the thing that opened it (the button uses opacity for the same
        // cue). Foundation-only, so this file needs no icon dependency the overlay layer may not carry.
        val gripColour = if (expanded) OverlayPalette.Text else OverlayPalette.Muted
        Row(
            horizontalArrangement = Arrangement.spacedBy(config.sizeDp.dp * DOT_GAP_FRACTION),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(GRIP_DOTS) {
                Box(
                    modifier = Modifier
                        .size(config.sizeDp.dp * DOT_FRACTION)
                        .background(gripColour, CircleShape),
                )
            }
        }
    }
}

/** A horizontal pill: the handle is [WIDTH_FACTOR]× as wide as it is tall. */
private const val WIDTH_FACTOR = 1.6f

/** Matches the floating button's fade so the two siblings settle at the same speed. */
private const val FADE_MILLIS = 400

private const val BORDER_DP = 1f

private const val BORDER_ALPHA = 0.55f

/** The grip dots and their gap, as fractions of the handle's height. */
private const val GRIP_DOTS = 3
private const val DOT_FRACTION = 0.12f
private const val DOT_GAP_FRACTION = 0.11f

/** A full pill at rest. */
private val DockShape = RoundedCornerShape(percent = 50)

// ------------------------------------------------------------------------------- user-facing copy
private const val REST_DESCRIPTION = "GameCore dock"
private const val EXPANDED_DESCRIPTION = "GameCore dock, panel open"
