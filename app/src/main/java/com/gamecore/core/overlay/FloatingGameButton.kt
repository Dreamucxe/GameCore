package com.gamecore.core.overlay

import android.view.MotionEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gamecore.core.model.FloatingButtonConfig
import kotlinx.coroutines.delay

/**
 * The draggable floating button of §7.
 *
 * Three behaviours the spec asks for, and the reason each is where it is:
 *
 *  **It drags.** Through raw screen coordinates and [DragGesture], not `detectDragGestures` — see that
 *  class for why the obvious approach makes the window stall or shake. This composable does not know
 *  where the button is allowed to be; it reports a raw target and the service applies [OverlayFrame],
 *  so the clamp and the edge snap have one implementation and it is the tested one.
 *
 *  **It snaps to an edge and remembers where it was.** Both belong to the service: snapping needs the
 *  screen size, and remembering needs the preference store. What happens here is only that
 *  [onDragFinished] fires once, at the end, rather than on every frame — a write to
 *  `EncryptedSharedPreferences` per touch event would be a keystore round trip per frame.
 *
 *  **It fades when idle.** [FloatingButtonConfig.idleOpacityPercent] after [IDLE_AFTER_MILLIS] of no
 *  touches, animated rather than stepped. The point is that an opaque control parked over a game is a
 *  distraction the user did not ask for, and a control that has vanished entirely cannot be found again —
 *  so it fades to still-visible rather than to nothing, and [FloatingButtonConfig] floors the setting.
 *
 * `pointerInteropFilter` is opted into deliberately. It is the only way to see `rawX`/`rawY`, which is
 * the coordinate space a window drag has to happen in, and the alternative — a wrapper `View` with an
 * `OnTouchListener` around the `ComposeView` — puts the gesture even further from the thing it moves.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FloatingGameButton(
    config: FloatingButtonConfig,
    accent: Color,
    /** Reads the window's live position, so a drag starts from where the button actually is. */
    positionProvider: () -> IntOffset,
    onDragTo: (x: Int, y: Int) -> Unit,
    onDragFinished: (x: Int, y: Int) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    /** True while the panel is open, so the button can show it is the thing that opened it. */
    isExpanded: Boolean = false,
    /**
     * The thermal dot (spec §2), from the shared [thermalDot] map. [ThermalDot.NONE] — which includes an
     * unavailable temperature — draws nothing, so a button over a cool phone or a phone whose sensor is
     * silent looks exactly as it does today. The same state is stated as a word in the pill and panel.
     */
    thermalDot: ThermalDot = ThermalDot.NONE,
    /**
     * Optional double-tap-to-open-the-panel (spec §2). Null when the setting is off, which is the default:
     * a single tap opens the quick sheet, and only when the user turns this on does a double tap reach the
     * full panel. Kept as a callback rather than a boolean so this composable owns no navigation.
     */
    onDoubleTap: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val slopPx = LocalViewConfiguration.current.touchSlop
    var gesture by remember { mutableStateOf(DragGesture.IDLE) }

    // Bumped by every touch. `LaunchedEffect` keyed on it restarts the idle countdown, which is both
    // simpler and more correct than cancelling a timer by hand — a gesture that ends while the previous
    // countdown is still pending cannot leave two of them running.
    var interactions by remember { mutableIntStateOf(0) }
    var isIdle by remember { mutableStateOf(false) }

    // Fired once per gesture, at the moment a touch becomes a drag. Without the flag every
    // `ACTION_MOVE` past the slop would buzz and a two-second drag would be a continuous vibration —
    // the difference between feedback and a fault.
    var hasBuzzed by remember { mutableStateOf(false) }

    // The event time of the last completed tap, for the optional double-tap. Only read when onDoubleTap is
    // non-null; a single-tap-only button never consults it.
    var lastTapTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(interactions, isExpanded) {
        if (isExpanded) {
            isIdle = false
            return@LaunchedEffect
        }
        isIdle = false
        delay(IDLE_AFTER_MILLIS)
        isIdle = true
    }

    val targetAlpha = when {
        isExpanded || gesture.isActive -> 1f
        isIdle -> config.idleOpacityPercent / 100f
        else -> config.opacityPercent / 100f
    }
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = FADE_MILLIS),
        label = "floatingButtonAlpha",
    )

    Box(
        modifier = modifier
            .size(config.sizeDp.dp)
            .alpha(alpha)
            .background(OverlayPalette.Plate, CircleShape)
            .border(BORDER_DP.dp, accent.copy(alpha = BORDER_ALPHA), CircleShape)
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        interactions++
                        hasBuzzed = false
                        val origin = positionProvider()
                        gesture = gesture.began(event.rawX, event.rawY, origin.x, origin.y)
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val moved = gesture.movedTo(event.rawX, event.rawY)
                        gesture = moved
                        // Nothing is moved until the finger passes the platform's touch slop, so a tap
                        // that wobbles by two pixels does not nudge the button and then open the panel
                        // from a position the user did not choose.
                        if (moved.isDrag(slopPx)) {
                            if (config.hapticFeedback && !hasBuzzed) {
                                hasBuzzed = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            onDragTo(moved.targetX, moved.targetY)
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        interactions++
                        val ended = gesture.movedTo(event.rawX, event.rawY)
                        gesture = ended.ended()
                        if (ended.isTap(slopPx)) {
                            if (config.hapticFeedback) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            // When double-tap-to-open-panel is on, a tap that lands within the window of the
                            // previous one is the double tap; otherwise every tap opens the quick sheet. The
                            // single tap still fires immediately when the setting is off, so the common path
                            // has no added latency — only an armed double-tap defers, and only to itself.
                            val now = event.eventTime
                            if (onDoubleTap != null && now - lastTapTime <= DOUBLE_TAP_WINDOW_MILLIS) {
                                lastTapTime = 0L
                                onDoubleTap()
                            } else {
                                lastTapTime = now
                                onTap()
                            }
                        } else {
                            onDragFinished(ended.targetX, ended.targetY)
                        }
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        // A cancel means the gesture was taken away mid-drag — a notification arriving,
                        // the window being reparented. The button is left where it currently is and the
                        // position is committed, because leaving it un-persisted would move it back on
                        // the next service restart to somewhere the user can see it is not.
                        val ended = gesture.ended()
                        gesture = ended
                        if (ended.isDrag(slopPx)) onDragFinished(ended.targetX, ended.targetY)
                        true
                    }

                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.SportsEsports,
            contentDescription = "GameCore controls",
            tint = accent,
            modifier = Modifier.size((config.sizeDp * ICON_FRACTION).dp),
        )
        if (thermalDot != ThermalDot.NONE) {
            val dotColour = if (thermalDot == ThermalDot.CRITICAL) OverlayPalette.Danger else OverlayPalette.Warning
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // A ring in the plate colour around the dot so it reads against both the icon and a
                    // bright game behind a translucent button.
                    .size((config.sizeDp * DOT_RING_FRACTION).dp)
                    .background(OverlayPalette.Plate, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size((config.sizeDp * DOT_FRACTION).dp)
                        .background(dotColour, CircleShape)
                        // The state is not conveyed by colour alone — the word is in the pill and panel
                        // (spec §2, §6) — but the dot still names itself for a screen reader.
                        .semantics { contentDescription = thermalDot.word },
                )
            }
        }
    }
}

/** How long the button stays at full opacity after the last touch. */
private const val IDLE_AFTER_MILLIS = 3_000L

private const val FADE_MILLIS = 400

private const val BORDER_DP = 1.5f

private const val BORDER_ALPHA = 0.55f

/** The glyph inside the circle, as a fraction of the button's diameter. */
private const val ICON_FRACTION = 0.52f

/** The thermal dot and the plate-coloured ring around it, as fractions of the button's diameter. */
private const val DOT_FRACTION = 0.22f
private const val DOT_RING_FRACTION = 0.34f

/**
 * How close two taps must be to count as a double tap, in milliseconds. Android's own
 * `ViewConfiguration.getDoubleTapTimeout()` is 300 ms; matching it keeps the gesture feeling native.
 */
private const val DOUBLE_TAP_WINDOW_MILLIS = 300L
