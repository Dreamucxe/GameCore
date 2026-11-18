package com.gamecore.core.overlay

import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gamecore.core.model.FloatingButtonConfig
import com.gamecore.core.model.FloatingButtonStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 *  **It wears one of three looks.** [FloatingButtonConfig.style] chooses the visual; the gesture code
 *  above is shared by all three. Reactor and Vanguard add a press dip, a one-shot shockwave/sweep when a
 *  surface opens, a quarter-turn of the ring, and a charge arc during the long press; Classic draws the
 *  pre-5.6 disc and deliberately none of those, so the revert option is a true revert.
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
    /**
     * Optional long-press (spec §7). Null when the caller offers no held gesture, which keeps an older
     * call site byte-for-byte the same button. The layout picker uses it: a press held past the platform's
     * own long-press timeout opens the chooser without stealing the single tap, the double tap or the
     * drag — those are all decided on the finger lifting, and a long press has already answered first.
     */
    onLongPress: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val slopPx = LocalViewConfiguration.current.touchSlop
    val longPressMillis = LocalViewConfiguration.current.longPressTimeoutMillis
    val scope = rememberCoroutineScope()
    var gesture by remember { mutableStateOf(DragGesture.IDLE) }

    // The pending long-press and whether it has already fired for this touch. The job is cancelled on
    // every move past the slop and on the finger lifting, so a drag never opens the picker halfway
    // through; the flag stops the same touch from opening it again as a tap on the way up.
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var longPressFired by remember { mutableStateOf(false) }

    // The long-press charge animation. Kept as a job rather than only as an Animatable so a lift that
    // lands before the launch has run cannot leave a charge animating on its own.
    var chargeJob by remember { mutableStateOf<Job?>(null) }

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

    // True between ACTION_DOWN and the finger lifting. It drives the press dip, and it is separate from
    // `gesture` because a touch that has not moved yet is neither a tap nor a drag — only a press.
    var pressed by remember { mutableStateOf(false) }

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

    // 5.6's interaction motion. Classic is deliberately left at 1f: the revert option has to look and
    // behave exactly like the button it replaces, and the old one did not dip or spring.
    val scale by animateFloatAsState(
        targetValue = when {
            config.style == FloatingButtonStyle.CLASSIC -> 1f
            gesture.isDrag(slopPx) -> 1.07f
            pressed -> 0.90f
            isExpanded -> 1.03f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "floatingButtonScale",
    )

    // The Reactor ring turns a quarter turn when the panel opens. A tween rather than a spring because
    // the ring has to land on the same angle every time; a bounce would leave the segments uneven.
    val ringTurn by animateFloatAsState(
        targetValue = if (isExpanded) 96f else 0f,
        animationSpec = tween(durationMillis = RING_TURN_MILLIS),
        label = "floatingButtonRingTurn",
    )

    // One-shot effects, both parked at their end state so a button nobody is touching draws no moving
    // parts. `burst` runs when a surface opens; `charge` runs while the layout-picker long press is held.
    val burst = remember { Animatable(1f) }
    val charge = remember { Animatable(0f) }
    LaunchedEffect(isExpanded) {
        if (!isExpanded) return@LaunchedEffect
        burst.snapTo(0f)
        burst.animateTo(1f, tween(durationMillis = BURST_MILLIS))
    }

    // Cancel the charge and put the arc back to empty. The stop() is what prevents a late frame from
    // repainting a charge the finger has already abandoned; the snap is launched because this is called
    // from the pointer callback, which is not a suspend context.
    fun resetCharge() {
        chargeJob?.cancel()
        chargeJob = null
        if (charge.value == 0f) return
        // snapTo cancels any in-flight animation and writes the end value in one suspend call.
        scope.launch { charge.snapTo(0f) }
    }

    Box(
        modifier = modifier
            .size(config.sizeDp.dp)
            .alpha(alpha)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        interactions++
                        pressed = true
                        hasBuzzed = false
                        longPressFired = false
                        longPressJob?.cancel()
                        val origin = positionProvider()
                        gesture = gesture.began(event.rawX, event.rawY, origin.x, origin.y)
                        if (onLongPress != null && config.style != FloatingButtonStyle.CLASSIC) {
                            // The arc fills over the platform's own long-press timeout, so the gesture
                            // announces itself before the picker opens instead of surprising the user.
                            // The short delay keeps it off a quick tap: only a press that is becoming a
                            // hold ever draws the arc.
                            chargeJob?.cancel()
                            chargeJob = scope.launch {
                                charge.snapTo(0f)
                                delay(CHARGE_DELAY_MILLIS)
                                charge.animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(
                                        durationMillis = (longPressMillis - CHARGE_DELAY_MILLIS)
                                            .toInt()
                                            .coerceAtLeast(1),
                                        easing = LinearEasing,
                                    ),
                                )
                            }
                        }
                        if (onLongPress != null) {
                            longPressJob = scope.launch {
                                delay(longPressMillis)
                                // Re-read the gesture: a finger that has travelled past the slop by the
                                // time this wakes is dragging, not holding, and must not open anything.
                                if (!longPressFired && !gesture.isDrag(slopPx)) {
                                    longPressFired = true
                                    if (config.hapticFeedback) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                    onLongPress()
                                }
                            }
                        }
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val moved = gesture.movedTo(event.rawX, event.rawY)
                        gesture = moved
                        // Nothing is moved until the finger passes the platform's touch slop, so a tap
                        // that wobbles by two pixels does not nudge the button and then open the panel
                        // from a position the user did not choose.
                        if (moved.isDrag(slopPx)) {
                            longPressJob?.cancel()
                            longPressJob = null
                            resetCharge()
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
                        pressed = false
                        longPressJob?.cancel()
                        longPressJob = null
                        resetCharge()
                        val ended = gesture.movedTo(event.rawX, event.rawY)
                        gesture = ended.ended()
                        if (ended.isTap(slopPx)) {
                            // A long press has already been answered on this touch; treating the lift as
                            // a tap would open the sheet over the picker the user just asked for.
                            if (longPressFired) {
                                lastTapTime = 0L
                            } else {
                                if (config.hapticFeedback) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                // When double-tap-to-open-panel is on, a tap that lands within the window of
                                // the previous one is the double tap; otherwise every tap opens the quick
                                // sheet. The single tap still fires immediately when the setting is off, so
                                // the common path has no added latency — only an armed double-tap defers,
                                // and only to itself.
                                val now = event.eventTime
                                if (onDoubleTap != null && now - lastTapTime <= DOUBLE_TAP_WINDOW_MILLIS) {
                                    lastTapTime = 0L
                                    onDoubleTap()
                                } else {
                                    lastTapTime = now
                                    onTap()
                                }
                            }
                        } else {
                            onDragFinished(ended.targetX, ended.targetY)
                        }
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        pressed = false
                        longPressJob?.cancel()
                        longPressJob = null
                        resetCharge()
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
        FloatingButtonVisual(
            style = config.style,
            sizeDp = config.sizeDp,
            accent = accent,
            expanded = isExpanded,
            thermalDot = thermalDot,
            burst = burst.value,
            charge = charge.value,
            ringTurn = ringTurn,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** How long the button stays at full opacity after the last touch. */
private const val IDLE_AFTER_MILLIS = 3_000L

private const val FADE_MILLIS = 400

/** The Reactor ring's turn when a surface opens, and the shockwave/sweep that follows it. */
private const val RING_TURN_MILLIS = 320
private const val BURST_MILLIS = 460

/** How long a press must last before the charge arc appears, so a quick tap never flashes it. */
private const val CHARGE_DELAY_MILLIS = 120L

/**
 * How close two taps must be to count as a double tap, in milliseconds. Android's own
 * `ViewConfiguration.getDoubleTapTimeout()` is 300 ms; matching it keeps the gesture feeling native.
 */
private const val DOUBLE_TAP_WINDOW_MILLIS = 300L
