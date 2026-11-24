package com.gamecore.core.overlay

import android.view.MotionEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gamecore.core.model.WheelButtonConfig
import com.gamecore.core.model.WheelButtonIcon
import com.gamecore.core.model.WheelButtonStyle
import com.gamecore.core.model.WheelOperatingMode
import com.gamecore.core.model.WheelProfile
import com.gamecore.core.model.WheelVisualStyle
import com.gamecore.core.wheel.InjectionBackendStatus
import com.gamecore.core.wheel.WheelRuntimeState
import com.gamecore.core.wheel.WheelTouchFrame
import com.gamecore.core.wheel.WheelTouchProcessor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.min

/**
 * The real High Sensitivity Wheel: a bounded, touchable joystick that consumes a finger and re-emits it
 * through the privileged injector.
 *
 * This is the visual half of the control whose maths lives in
 * [com.gamecore.core.wheel.WheelTouchProcessor]. It draws a ring at the profile's size, the clamp circle the
 * injected pointer may not leave, and — during calibration and play — the vector the wheel has actually
 * produced. Every touch sample is handed to `onTouch` **before** any animation or state publication, so the
 * drawing never sits between the finger and the injected event.
 *
 * The window is square and exactly `diameterDp` across. That is deliberate: a full-screen touchable window
 * would swallow every tap in the game, and the wheel has no business being anywhere the user did not put it.
 * The window is moved by the service from the raw screen coordinates reported here, because clamping needs the
 * screen size and [com.gamecore.core.overlay.DragGesture] is what keeps that drag stable while the window
 * itself moves under the finger.
 *
 * The service supplies the window centre to the controller as the injected stream's neutral point, so a
 * wheel placed over a game's joystick activates that joystick at the wheel's centre even when the thumb lands
 * off-centre. The finger's own landing point is still the anchor for the movement delta.
 *
 * In positioning mode a drag is always a placement gesture. If the profile is position-locked, the first
 * drag frame unlocks it rather than refusing to move: a lock must not trap a wheel over the joystick it was
 * meant to sit on.
 *
 * **One pointer.** The wheel owns one gesture at a time. A second finger landing inside the ring is ignored,
 * which is the honest limitation of an injected stream: the platform gives no guarantee that an injected
 * pointer id coexists with a physical one, and pretending to accept two fingers would produce a stream no
 * game could be trusted to interpret. See the diagnostics copy.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WheelStick(
    profile: WheelProfile,
    mode: WheelOperatingMode,
    liveFrame: WheelTouchFrame?,
    positionProvider: () -> IntOffset,
    onTouch: (action: Int, x: Float, y: Float, eventTime: Long, downTime: Long) -> Boolean,
    onDragTo: (x: Int, y: Int) -> Unit,
    onDragFinished: () -> Unit,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val slopPx = LocalViewConfiguration.current.touchSlop
    val haptics = LocalHapticFeedback.current
    var gesture by remember { mutableStateOf(DragGesture.IDLE) }
    var lastDeadzoneState by remember { mutableStateOf<Boolean?>(null) }
    var unlockedByDrag by remember { mutableStateOf(false) }
    val accent = Color(profile.accent.argb)

    // Visual publication is throttled independently of injection, so this feedback can never delay the
    // binder call. The first observed frame seeds the state silently; only a real boundary crossing buzzes.
    LaunchedEffect(liveFrame?.action, liveFrame?.insideDeadzone, profile.haptics) {
        val frame = liveFrame ?: return@LaunchedEffect
        if (frame.action == com.gamecore.core.wheel.WheelAction.DOWN) {
            lastDeadzoneState = frame.insideDeadzone
            return@LaunchedEffect
        }
        if (frame.action == com.gamecore.core.wheel.WheelAction.UP ||
            frame.action == com.gamecore.core.wheel.WheelAction.CANCEL
        ) {
            lastDeadzoneState = null
            return@LaunchedEffect
        }
        val inside = frame.insideDeadzone
        val previous = lastDeadzoneState
        lastDeadzoneState = inside
        if (profile.haptics && previous != null && previous != inside) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(profile.opacity)
            .semantics {
                contentDescription = when (mode) {
                    WheelOperatingMode.POSITIONING -> "Wheel placement. Drag to move the wheel."
                    WheelOperatingMode.CALIBRATION -> "Wheel calibration. Move to see the output vector."
                    WheelOperatingMode.GAMING -> "High sensitivity wheel active."
                    WheelOperatingMode.DISABLED -> "High sensitivity wheel."
                }
            }
            .pointerInteropFilter { event ->
                // An injected event that landed back on this window (the untargeted hand-off race) must
                // not start a second gesture inside the overlay. The helper stamps every event it creates
                // with a device id no real touchscreen uses.
                if (event.deviceId == WheelTouchProcessor.INJECTED_DEVICE_ID) {
                    return@pointerInteropFilter false
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val origin = positionProvider()
                        unlockedByDrag = false
                        gesture = gesture.began(event.rawX, event.rawY, origin.x, origin.y)
                        onTouch(
                            MotionEvent.ACTION_DOWN,
                            event.rawX,
                            event.rawY,
                            event.eventTime,
                            event.downTime,
                        )
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (!gesture.isActive) return@pointerInteropFilter false
                        gesture = gesture.movedTo(event.rawX, event.rawY)
                        // Injection is the priority: this call happens first and does not wait for the
                        // window move, the canvas, or recomposition. In POSITIONING it is a no-op by
                        // design — that mode captures touches and injects nothing.
                        onTouch(
                            MotionEvent.ACTION_MOVE,
                            event.rawX,
                            event.rawY,
                            event.eventTime,
                            event.downTime,
                        )
                        if (mode == WheelOperatingMode.POSITIONING && gesture.isDrag(slopPx)) {
                            // An explicit placement drag wins over a stale lock. Unlock on the first
                            // frame and keep moving in the same gesture instead of refusing to budge.
                            if (profile.locked && !unlockedByDrag) {
                                unlockedByDrag = true
                                onUnlock()
                            }
                            onDragTo(gesture.targetX, gesture.targetY)
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        onTouch(
                            MotionEvent.ACTION_UP,
                            event.rawX,
                            event.rawY,
                            event.eventTime,
                            event.downTime,
                        )
                        val dragged = mode == WheelOperatingMode.POSITIONING &&
                            gesture.isDrag(slopPx)
                        gesture = gesture.ended()
                        if (dragged) onDragFinished()
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        // Cancellation is a release. The controller turns it into a platform CANCEL and
                        // releases the helper's pointer, which is the only acceptable way to end a stream
                        // the system took away from us.
                        onTouch(
                            MotionEvent.ACTION_CANCEL,
                            event.rawX,
                            event.rawY,
                            event.eventTime,
                            event.downTime,
                        )
                        val dragged = mode == WheelOperatingMode.POSITIONING &&
                            gesture.isDrag(slopPx)
                        gesture = gesture.ended()
                        if (dragged) onDragFinished()
                        true
                    }

                    else -> false
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val outer = min(size.width, size.height) / 2f - 2.dp.toPx()
            if (outer <= 0f) return@Canvas
            val minimal = profile.style == WheelVisualStyle.MINIMAL
            val thin = if (minimal) 1.2.dp.toPx() else 2.4.dp.toPx()
            val faint = accent.copy(alpha = if (profile.style == WheelVisualStyle.NEON) 0.95f else 0.7f)

            // The dark backing ring is not decoration: a bright ring over a bright map disappears, and a
            // control the player cannot find is worse than no control.
            drawCircle(
                color = Color.Black.copy(alpha = 0.5f),
                radius = outer,
                center = centre,
                style = Stroke(width = thin + 2.dp.toPx()),
            )
            drawCircle(color = faint, radius = outer, center = centre, style = Stroke(width = thin))

            // The profile's clamp radius is the maths; the drawn ring is its scale. When the radius is
            // larger than the window the clamp ring is drawn at the window edge with a muted stroke, so a
            // user can still see that the throw is being bounded at the screen rather than by the artwork.
            val visualScale = if (profile.radiusPx > 0) outer / profile.radiusPx else 0f
            val inner = (outer * 0.42f).coerceAtLeast(4.dp.toPx())
            drawCircle(
                color = accent.copy(alpha = 0.22f),
                radius = inner,
                center = centre,
                style = Stroke(width = 1.dp.toPx()),
            )

            if (!minimal) {
                val ticks = if (profile.style == WheelVisualStyle.ORBIT) 12 else 8
                for (i in 0 until ticks) {
                    val angle = (2.0 * Math.PI * i / ticks)
                    val dx = kotlin.math.cos(angle).toFloat()
                    val dy = kotlin.math.sin(angle).toFloat()
                    drawLine(
                        color = accent.copy(alpha = 0.38f),
                        start = Offset(centre.x + dx * outer * 0.82f, centre.y + dy * outer * 0.82f),
                        end = Offset(centre.x + dx * outer * 0.94f, centre.y + dy * outer * 0.94f),
                        strokeWidth = 1.4.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }

            drawCircle(color = accent, radius = if (minimal) 2.4.dp.toPx() else 3.2.dp.toPx(), center = centre)

            // The live vector. It is scaled from the maths radius into the drawn ring, so a clamped throw
            // visibly stops at the rim. It is drawn from the ring centre rather than from the anchor because
            // the window is the fixed reference; the anchor is wherever the finger landed inside it.
            val frame = liveFrame
            if (frame != null && profile.showVector && visualScale > 0f) {
                val length = hypot(frame.outputDx, frame.outputDy) * visualScale
                if (length > 0.5f) {
                    val unitX = frame.outputDx / hypot(frame.outputDx, frame.outputDy)
                    val unitY = frame.outputDy / hypot(frame.outputDx, frame.outputDy)
                    val end = Offset(
                        centre.x + unitX * length.coerceAtMost(outer),
                        centre.y + unitY * length.coerceAtMost(outer),
                    )
                    drawLine(
                        color = accent.copy(alpha = 0.9f),
                        start = centre,
                        end = end,
                        strokeWidth = 2.4.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawCircle(
                        color = if (frame.clamped) OverlayPalette.Warning else accent,
                        radius = 4.5.dp.toPx(),
                        center = end,
                    )
                }
            }
        }

        when (mode) {
            WheelOperatingMode.POSITIONING -> WheelCaption(
                text = if (profile.locked) "Drag to unlock & place" else "Drag to place",
                accent = accent,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            WheelOperatingMode.CALIBRATION -> WheelCaption(
                text = "Calibration · ×${"%.2f".format(profile.sensitivity)}",
                accent = accent,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            else -> Unit
        }
    }
}

@Composable
private fun WheelCaption(text: String, accent: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        modifier = modifier
            .padding(bottom = 2.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * The floating handle that arms and disarms the wheel.
 *
 * It is deliberately one window with one job, separate from GameCore's control-panel button: this is the
 * emergency off-switch for input injection. It persists its position and look, it is draggable even while the
 * wheel itself is locked, and it never arms on a drag — the tap that arms the wheel is decided only after the
 * finger lifts and only when the gesture stayed inside the platform's touch slop.
 *
 * A long press opens the compact quick panel. The long-press job is cancelled the instant the touch becomes a
 * drag, so holding the button while moving it never opens a panel under the finger.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WheelPowerButton(
    config: WheelButtonConfig,
    state: WheelRuntimeState,
    positionProvider: () -> IntOffset,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDragTo: (x: Int, y: Int) -> Unit,
    onDragFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val slopPx = LocalViewConfiguration.current.touchSlop
    val longPressMillis = LocalViewConfiguration.current.longPressTimeoutMillis
    val scope = rememberCoroutineScope()
    var gesture by remember { mutableStateOf(DragGesture.IDLE) }
    var pressed by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var longPressFired by remember { mutableStateOf(false) }
    var hasBuzzed by remember { mutableStateOf(false) }

    val armed = state.mode != WheelOperatingMode.DISABLED || state.arming
    val failed = state.injector.status == InjectionBackendStatus.CONNECTION_LOST ||
        state.injector.status == InjectionBackendStatus.UNSUPPORTED ||
        state.injector.status == InjectionBackendStatus.ERROR ||
        state.injector.status == InjectionBackendStatus.SHIZUKU_MISSING ||
        state.injector.status == InjectionBackendStatus.SHIZUKU_STOPPED ||
        state.injector.status == InjectionBackendStatus.PERMISSION_REQUIRED

    val targetAlpha = when {
        pressed || gesture.isActive -> 1f
        armed -> config.opacity
        else -> config.idleOpacity
    }
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = FADE_MILLIS),
        label = "wheelButtonAlpha",
    )
    val accent = when {
        failed -> OverlayPalette.Danger
        state.mode == WheelOperatingMode.GAMING -> Color(0xFF6BE39A)
        state.mode == WheelOperatingMode.CALIBRATION -> OverlayPalette.Warning
        state.mode == WheelOperatingMode.POSITIONING -> Color(config.accent.argb)
        else -> Color(config.accent.argb)
    }

    Box(
        modifier = modifier
            .size(config.sizeDp.dp)
            .alpha(alpha)
            .semantics {
                contentDescription = when {
                    state.arming -> "High sensitivity wheel starting"
                    failed -> "High sensitivity wheel error. Long press for controls."
                    armed -> "High sensitivity wheel on. Tap to turn off."
                    else -> "High sensitivity wheel off. Tap to turn on."
                }
            }
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pressed = true
                        hasBuzzed = false
                        longPressFired = false
                        longPressJob?.cancel()
                        longPressJob = scope.launch {
                            delay(longPressMillis)
                            if (pressed && !hasBuzzed) {
                                longPressFired = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongPress()
                            }
                        }
                        val origin = positionProvider()
                        gesture = gesture.began(event.rawX, event.rawY, origin.x, origin.y)
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (!gesture.isActive) return@pointerInteropFilter false
                        gesture = gesture.movedTo(event.rawX, event.rawY)
                        if (gesture.isDrag(slopPx)) {
                            if (!hasBuzzed) {
                                hasBuzzed = true
                                longPressJob?.cancel()
                                if (config.haptics) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                            onDragTo(gesture.targetX, gesture.targetY)
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        pressed = false
                        longPressJob?.cancel()
                        val tapped = gesture.isTap(slopPx) && !longPressFired
                        val dragged = gesture.isDrag(slopPx)
                        gesture = gesture.ended()
                        if (dragged) {
                            onDragFinished()
                        } else if (tapped) {
                            onTap()
                        }
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        pressed = false
                        longPressJob?.cancel()
                        val dragged = gesture.isDrag(slopPx)
                        gesture = gesture.ended()
                        if (dragged) onDragFinished()
                        true
                    }

                    else -> false
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val radius = min(size.width, size.height) / 2f - 2.dp.toPx()
            val plate = when (state.mode) {
                WheelOperatingMode.DISABLED -> Color(0xFF151A21)
                WheelOperatingMode.POSITIONING -> Color(0xFF132338)
                WheelOperatingMode.CALIBRATION -> Color(0xFF2A2415)
                WheelOperatingMode.GAMING -> Color(0xFF12291F)
            }
            when (config.style) {
                WheelButtonStyle.ORB -> {
                    drawCircle(color = plate.copy(alpha = 0.94f), radius = radius, center = centre)
                    drawCircle(
                        color = accent.copy(alpha = 0.95f),
                        radius = radius,
                        center = centre,
                        style = Stroke(width = if (armed) 2.6.dp.toPx() else 1.6.dp.toPx()),
                    )
                }

                WheelButtonStyle.RING -> {
                    drawCircle(color = plate.copy(alpha = 0.72f), radius = radius, center = centre)
                    drawCircle(
                        color = accent.copy(alpha = 0.98f),
                        radius = radius * 0.88f,
                        center = centre,
                        style = Stroke(width = if (armed) 3.2.dp.toPx() else 2.2.dp.toPx()),
                    )
                    drawCircle(
                        color = accent.copy(alpha = 0.35f),
                        radius = radius,
                        center = centre,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }

                WheelButtonStyle.MINIMAL -> {
                    drawCircle(color = plate.copy(alpha = 0.45f), radius = radius * 0.82f, center = centre)
                    drawCircle(
                        color = accent.copy(alpha = 0.9f),
                        radius = radius * 0.82f,
                        center = centre,
                        style = Stroke(width = 1.4.dp.toPx()),
                    )
                }
            }
            // A dim half-ring while binding says "starting", without claiming the wheel is injecting.
            if (state.arming) {
                drawArc(
                    color = accent.copy(alpha = 0.55f),
                    startAngle = -90f,
                    sweepAngle = 220f,
                    useCenter = false,
                    topLeft = Offset(centre.x - radius * 0.72f, centre.y - radius * 0.72f),
                    size = androidx.compose.ui.geometry.Size(radius * 1.44f, radius * 1.44f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            drawGlyph(config.icon, centre, radius * 0.42f, accent, failed)
        }
    }
}

/** Small vector glyphs, so the button carries no bitmap and no icon dependency. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlyph(
    icon: WheelButtonIcon,
    centre: Offset,
    reach: Float,
    accent: Color,
    failed: Boolean,
) {
    val stroke = 2.dp.toPx()
    when (icon) {
        WheelButtonIcon.WHEEL -> {
            drawCircle(color = accent, radius = reach * 0.72f, center = centre, style = Stroke(stroke))
            drawCircle(color = accent, radius = reach * 0.16f, center = centre)
            for (i in 0 until 4) {
                val angle = Math.PI / 4 + i * Math.PI / 2
                val dx = kotlin.math.cos(angle).toFloat()
                val dy = kotlin.math.sin(angle).toFloat()
                drawLine(
                    color = accent,
                    start = Offset(centre.x + dx * reach * 0.22f, centre.y + dy * reach * 0.22f),
                    end = Offset(centre.x + dx * reach * 0.68f, centre.y + dy * reach * 0.68f),
                    strokeWidth = stroke,
                )
            }
        }

        WheelButtonIcon.GAMEPAD -> {
            drawLine(
                color = accent,
                start = Offset(centre.x - reach * 0.72f, centre.y),
                end = Offset(centre.x + reach * 0.72f, centre.y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = accent,
                start = Offset(centre.x, centre.y - reach * 0.42f),
                end = Offset(centre.x, centre.y + reach * 0.42f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawCircle(color = accent, radius = reach * 0.16f, center = Offset(centre.x - reach * 0.55f, centre.y + reach * 0.4f))
        }

        WheelButtonIcon.CROSSHAIR -> {
            drawCircle(color = accent, radius = reach * 0.62f, center = centre, style = Stroke(stroke))
            drawLine(
                color = accent,
                start = Offset(centre.x - reach, centre.y),
                end = Offset(centre.x + reach, centre.y),
                strokeWidth = stroke,
            )
            drawLine(
                color = accent,
                start = Offset(centre.x, centre.y - reach),
                end = Offset(centre.x, centre.y + reach),
                strokeWidth = stroke,
            )
        }

        WheelButtonIcon.BOLT -> {
            val path = Path().apply {
                moveTo(centre.x + reach * 0.12f, centre.y - reach * 0.9f)
                lineTo(centre.x - reach * 0.48f, centre.y + reach * 0.08f)
                lineTo(centre.x - reach * 0.04f, centre.y + reach * 0.08f)
                lineTo(centre.x - reach * 0.2f, centre.y + reach * 0.9f)
                lineTo(centre.x + reach * 0.52f, centre.y - reach * 0.16f)
                lineTo(centre.x + reach * 0.06f, centre.y - reach * 0.16f)
                close()
            }
            drawPath(path, color = accent)
        }
    }
    if (failed) {
        drawCircle(color = OverlayPalette.Danger, radius = reach * 0.2f, center = Offset(centre.x + reach * 0.9f, centre.y - reach * 0.9f))
    }
}

/**
 * The compact controls a long press opens: everything a player needs mid-game without leaving it.
 *
 * It is a normal touchable window anchored to the handle. It never carries the wheel's gesture stream, so
 * opening it cannot interrupt a pointer the wheel is already injecting — the controller cancels any open
 * gesture before changing mode, and the panel's own touches never reach the stick.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WheelQuickPanel(
    state: WheelRuntimeState,
    profiles: List<WheelProfile>,
    onMode: (WheelOperatingMode) -> Unit,
    onPower: () -> Unit,
    onPrecision: (Boolean) -> Unit,
    onTurbo: (Boolean) -> Unit,
    onLock: () -> Unit,
    onSensitivity: (Float) -> Unit,
    onSelectProfile: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onHideButton: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = state.effectiveProfile
    Column(
        modifier = modifier
            .width(PANEL_WIDTH)
            .background(OverlayPalette.PanelPlate, RoundedCornerShape(16.dp))
            .border(1.dp, OverlayPalette.Divider, RoundedCornerShape(16.dp))
            .padding(14.dp)
            .pointerInteropFilter { event ->
                // The window's FLAG_WATCH_OUTSIDE_TOUCH delivers ACTION_OUTSIDE here. It closes the panel
                // and is consumed so it never falls through to the game as a stray tap.
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    onClose()
                    true
                } else {
                    false
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "High sensitivity wheel",
                    color = OverlayPalette.Text,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when {
                        state.arming -> "Starting injector…"
                        state.mode == WheelOperatingMode.GAMING -> "Gaming · injecting"
                        state.mode == WheelOperatingMode.POSITIONING -> "Positioning · not injecting"
                        state.mode == WheelOperatingMode.CALIBRATION -> "Calibration · not injecting"
                        else -> "Off · no injection"
                    },
                    color = statusColour(state),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                text = "Done",
                color = OverlayPalette.Muted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .background(OverlayPalette.Divider, RoundedCornerShape(8.dp))
                    .pointerInteropFilter {
                        if (it.actionMasked == MotionEvent.ACTION_UP) onClose()
                        true
                    }
                    .padding(horizontal = 9.dp, vertical = 6.dp),
            )
        }

        Spacer(modifier = Modifier.size(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelChip(
                text = if (state.mode == WheelOperatingMode.DISABLED && !state.arming) "Enable" else "Disable",
                active = state.mode != WheelOperatingMode.DISABLED || state.arming,
                colour = OverlayPalette.Good,
                onClick = onPower,
                modifier = Modifier.weight(1f),
            )
            PanelChip(
                text = "Position",
                active = state.mode == WheelOperatingMode.POSITIONING,
                colour = Color(0xFF7AB8FF),
                onClick = { onMode(WheelOperatingMode.POSITIONING) },
                modifier = Modifier.weight(1f),
            )
            PanelChip(
                text = "Gaming",
                active = state.mode == WheelOperatingMode.GAMING,
                colour = OverlayPalette.Good,
                onClick = { onMode(WheelOperatingMode.GAMING) },
                modifier = Modifier.weight(1f),
            )
            PanelChip(
                text = "Calibrate",
                active = state.mode == WheelOperatingMode.CALIBRATION,
                colour = OverlayPalette.Warning,
                onClick = { onMode(WheelOperatingMode.CALIBRATION) },
                modifier = Modifier.weight(1f),
            )
        }

        if (state.consentRequired) {
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "Read and accept the injection risk warning in Wheel settings before enabling. " +
                    "Nothing has been armed.",
                color = OverlayPalette.Warning,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Spacer(modifier = Modifier.size(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Sensitivity",
                color = OverlayPalette.Muted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            PanelChip(
                text = "−",
                active = false,
                colour = OverlayPalette.Muted,
                onClick = { onSensitivity(-SENSITIVITY_STEP) },
                compact = true,
            )
            Text(
                text = "×${"%.2f".format(profile.sensitivity)}",
                color = OverlayPalette.Text,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            PanelChip(
                text = "+",
                active = false,
                colour = OverlayPalette.Muted,
                onClick = { onSensitivity(SENSITIVITY_STEP) },
                compact = true,
            )
        }

        Spacer(modifier = Modifier.size(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelChip(
                text = "Precision",
                active = state.precision,
                colour = Color(0xFF7AB8FF),
                onClick = { onPrecision(!state.precision) },
                modifier = Modifier.weight(1f),
            )
            PanelChip(
                text = "Turbo",
                active = state.turbo,
                colour = OverlayPalette.Warning,
                onClick = { onTurbo(!state.turbo) },
                modifier = Modifier.weight(1f),
            )
            PanelChip(
                text = if (profile.locked) "Unlock" else "Lock",
                active = profile.locked,
                colour = OverlayPalette.Muted,
                onClick = onLock,
                modifier = Modifier.weight(1f),
            )
        }

        if (profiles.size > 1) {
            Spacer(modifier = Modifier.size(10.dp))
            Text(
                text = "Profile",
                color = OverlayPalette.Muted,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(modifier = Modifier.size(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val visible = if (profiles.size <= 3) profiles else profiles.take(2)
                visible.forEach { candidate ->
                    PanelChip(
                        text = candidate.name,
                        active = candidate.id == state.activeProfileId,
                        colour = Color(candidate.accent.argb),
                        onClick = { onSelectProfile(candidate.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (profiles.size > 3) {
                    PanelChip(
                        text = "Next",
                        active = false,
                        colour = OverlayPalette.Muted,
                        onClick = {
                            val index = profiles.indexOfFirst { it.id == state.activeProfileId }
                            onSelectProfile(profiles[(index + 1).mod(profiles.size)].id)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.size(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelChip(
                text = "Full settings",
                active = false,
                colour = Color(0xFF35D6E8),
                onClick = onOpenSettings,
                modifier = Modifier.weight(1f),
            )
            PanelChip(
                text = "Hide button",
                active = false,
                colour = OverlayPalette.Muted,
                onClick = onHideButton,
                modifier = Modifier.weight(1f),
            )
        }

        state.lastError?.let { error ->
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = error,
                color = OverlayPalette.Danger,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (state.injector.status != InjectionBackendStatus.READY &&
            state.injector.status != InjectionBackendStatus.UNKNOWN &&
            state.injector.detail.isNotBlank()
        ) {
            Spacer(modifier = Modifier.size(6.dp))
            Text(
                text = state.injector.detail,
                color = OverlayPalette.Muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun statusColour(state: WheelRuntimeState): Color = when {
    state.lastError != null -> OverlayPalette.Danger
    state.mode == WheelOperatingMode.GAMING -> OverlayPalette.Good
    state.mode == WheelOperatingMode.POSITIONING -> Color(0xFF7AB8FF)
    state.mode == WheelOperatingMode.CALIBRATION -> OverlayPalette.Warning
    state.arming -> OverlayPalette.Warning
    else -> OverlayPalette.Muted
}

@Composable
private fun PanelChip(
    text: String,
    active: Boolean,
    colour: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val background = if (active) colour.copy(alpha = 0.22f) else OverlayPalette.Divider
    val foreground = if (active) colour else OverlayPalette.Muted
    Surface(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
        shape = RoundedCornerShape(9.dp),
        color = background,
        contentColor = foreground,
        border = BorderStroke(1.dp, foreground.copy(alpha = 0.35f)),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = if (compact) 10.dp else 6.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = foreground,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

private const val FADE_MILLIS = 180
private const val SENSITIVITY_STEP = 0.1f
private val PANEL_WIDTH = 286.dp
