package com.gamecore.ui.startup

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamecore.BuildConfig
import com.gamecore.core.i18n.LocalStrings
import com.gamecore.core.model.StartupAnimationStyle
import com.gamecore.ui.theme.LocalAnimationsEnabled
import kotlin.math.cos
import kotlin.math.sin

/**
 * The full-screen cover GameCore shows while its first loading pass finishes.
 *
 * The cover exists for two concrete first-frame problems rather than for decoration. Home can draw
 * its 3D metric instruments for one frame before the stored `show3DHomeStats` preference arrives and
 * then snap to the configured 2D cards; and a plugin theme, its glass material and its icon can only
 * arrive after the registry has read every installed package, which costs seconds on a large install.
 * A launch therefore has a short window in which the app is visibly assembling itself. This covers
 * that window and gives it a deliberate opening instead of a flash.
 *
 * Three composition styles share one layout so switching style changes the mood, not the screen:
 * [StartupAnimationStyle.ORBIT] counter-rotates two arcs around the mark, [StartupAnimationStyle.PULSE]
 * sends ripples out from it, and [StartupAnimationStyle.MINIMAL] keeps the mark still behind a single
 * travelling progress line. [StartupAnimationStyle.OFF] is handled by the caller and draws nothing.
 *
 * **Motion is a veto, not a prerequisite.** When [LocalAnimationsEnabled] is false — the app's
 * Animations setting is off, or the platform animator scale is zero — the chosen composition is drawn
 * at rest: the mark, the rings and the status line are identical, only the movement stops. Turning the
 * cover off entirely is the separate Off style. That split matters because the cover's job is hiding
 * a load, which a motion-sensitive user still needs; the animation is the part they declined.
 *
 * **It is a cover, not a dialog.** Taps do not reach the navigation graph underneath; the transparent
 * sibling behind the content consumes them. The one deliberate control is Skip, which ends the cover
 * early. There is no auto-focus request and no modal semantics, because nothing is being asked of the
 * user — a screen reader hears the status line as a polite live region and can leave at any time.
 */
@Composable
fun StartupMask(
    style: StartupAnimationStyle,
    pluginsLoaded: Boolean,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (style == StartupAnimationStyle.OFF) return

    val scheme = MaterialTheme.colorScheme
    val strings = LocalStrings.current
    val motion = LocalAnimationsEnabled.current
    val transition = rememberInfiniteTransition(label = "startupMask")

    // Zero-to-zero when motion is off: the transition still exists, but no value moves. Keeping the
    // animation calls unconditional keeps the composition stable if the verdict ever changes under a
    // running cover (it does not in practice, but a conditional call would restart every sibling).
    val orbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (motion) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (motion) 2_800 else 1,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "startupOrbit",
    )
    val pulse by transition.animateFloat(
        initialValue = if (motion) 0f else 0.55f,
        targetValue = if (motion) 1f else 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (motion) 1_600 else 1,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "startupPulse",
    )
    val sweep by transition.animateFloat(
        initialValue = if (motion) 0f else 0.5f,
        targetValue = if (motion) 1f else 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (motion) 2_200 else 1,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "startupSweep",
    )

    // The mark arrives rather than appearing: one short scale-and-fade on the cover's own first
    // frames. A zero-length spec when motion is off means the first drawn frame is already settled.
    val entrance = remember { androidx.compose.animation.core.Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(motion) {
        if (motion) entrance.animateTo(1f, tween(durationMillis = 520))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        scheme.primary.copy(alpha = 0.16f),
                        scheme.background,
                    ),
                ),
            ),
    ) {
        // The blocker sits under the content and over the app. It has no gesture, so it claims no
        // semantics: it exists only to keep a tap from reaching a nav row or a card that is still
        // being configured underneath.
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Main).changes.forEach { it.consume() }
                        }
                    }
                },
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .systemBarsPadding()
                .padding(horizontal = 32.dp)
                .graphicsLayer {
                    alpha = entrance.value
                    val growth = 0.94f + 0.06f * entrance.value
                    scaleX = growth
                    scaleY = growth
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StartupMark(
                style = style,
                orbit = orbit,
                pulse = pulse,
                sweep = sweep,
                motion = motion,
            )
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = "GAMECORE",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp,
                color = scheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            color = if (pluginsLoaded) scheme.primary else scheme.onSurfaceVariant,
                            shape = CircleShape,
                        ),
                )
                Text(
                    text = if (pluginsLoaded) strings.startupReady else strings.startupPreparing,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            TextButton(onClick = onSkip) {
                Text(text = strings.skip, color = scheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The mark itself: a diamond core inside whichever orbit the chosen style describes.
 *
 * Drawn rather than composed from an icon so the same geometry scales from a 120 dp phone cover to a
 * tablet without an asset per density, and so the moving parts share one coordinate space with the
 * rings they travel along. Colours are read from the scheme once, above the draw scope, because the
 * whole mark belongs to the theme the app is about to wear.
 */
@Composable
private fun StartupMark(
    style: StartupAnimationStyle,
    orbit: Float,
    pulse: Float,
    sweep: Float,
    motion: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val primary = scheme.primary
    val secondary = scheme.secondary
    val faint = scheme.onSurface.copy(alpha = 0.14f)
    val isOrbit = style == StartupAnimationStyle.ORBIT
    val isPulse = style == StartupAnimationStyle.PULSE

    Canvas(
        modifier = Modifier
            .size(184.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        val middle = center
        val radius = size.minDimension / 2f
        val hairline = 1.dp.toPx()
        val arcWidth = 2.5.dp.toPx()

        // A soft bloom under everything, so the mark sits in light rather than on a flat fill. It is
        // one radial gradient and no blur layer: the same look without an offscreen pass.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primary.copy(alpha = 0.22f), Color.Transparent),
                center = middle,
                radius = radius,
            ),
            radius = radius,
            center = middle,
        )

        if (isOrbit) {
            drawCircle(faint, radius = radius * 0.94f, center = middle, style = Stroke(hairline))
            drawCircle(faint, radius = radius * 0.68f, center = middle, style = Stroke(hairline))
            drawArc(
                color = primary,
                startAngle = orbit,
                sweepAngle = 108f,
                useCenter = false,
                topLeft = Offset(middle.x - radius * 0.94f, middle.y - radius * 0.94f),
                size = Size(radius * 1.88f, radius * 1.88f),
                style = Stroke(width = arcWidth, cap = StrokeCap.Round),
            )
            drawArc(
                color = secondary.copy(alpha = 0.85f),
                startAngle = -orbit * 1.35f,
                sweepAngle = 72f,
                useCenter = false,
                topLeft = Offset(middle.x - radius * 0.68f, middle.y - radius * 0.68f),
                size = Size(radius * 1.36f, radius * 1.36f),
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
            )
            drawTraveller(middle, radius * 0.94f, orbit + 108f, primary, 3.4.dp.toPx())
            drawTraveller(middle, radius * 0.68f, -orbit * 1.35f + 72f, secondary, 2.4.dp.toPx())
        }

        if (isPulse) {
            // Three rings spaced through the same cycle. Each fades as it grows, which reads as a
            // breath rather than a loading spinner and never points at a finish line that is not real.
            repeat(3) { index ->
                val progress = ((pulse + index / 3f) % 1f)
                val ringRadius = radius * (0.34f + 0.62f * progress)
                drawCircle(
                    color = primary.copy(alpha = (1f - progress) * 0.42f),
                    radius = ringRadius,
                    center = middle,
                    style = Stroke(width = (2.4f - progress).dp.toPx()),
                )
            }
            drawCircle(faint, radius = radius * 0.34f, center = middle, style = Stroke(hairline))
        }

        if (!isOrbit && !isPulse) {
            drawCircle(faint, radius = radius * 0.94f, center = middle, style = Stroke(hairline))
            drawCircle(primary.copy(alpha = 0.25f), radius = radius * 0.68f, center = middle, style = Stroke(hairline))
            // The one moving element: a short line travelling along a track under the core. At rest
            // it sits at the middle, which is a finished-looking position rather than a frozen start.
            val trackY = middle.y + radius * 0.92f
            val trackHalf = radius * 0.36f
            drawLine(
                color = faint,
                start = Offset(middle.x - trackHalf, trackY),
                end = Offset(middle.x + trackHalf, trackY),
                strokeWidth = hairline,
                cap = StrokeCap.Round,
            )
            val x = middle.x - trackHalf + trackHalf * 2f * sweep
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, primary, Color.Transparent),
                    startX = x - 18.dp.toPx(),
                    endX = x + 18.dp.toPx(),
                ),
                start = Offset(x - 18.dp.toPx(), trackY),
                end = Offset(x + 18.dp.toPx(), trackY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        // The core: an outlined diamond around a solid centre. The diamond is the brand mark shared by
        // the dock and the launcher icon; the pulse gives it a half-breath so it does not read as a
        // static logo pasted over a moving background.
        val core = radius * 0.27f
        val beat = if (motion) 0.96f + 0.06f * pulse else 1f
        rotate(45f, pivot = middle) {
            drawRoundRect(
                color = primary.copy(alpha = 0.14f),
                topLeft = Offset(middle.x - core * beat, middle.y - core * beat),
                size = Size(core * 2f * beat, core * 2f * beat),
                cornerRadius = CornerRadius(core * 0.36f),
            )
            drawRoundRect(
                color = primary,
                topLeft = Offset(middle.x - core * beat, middle.y - core * beat),
                size = Size(core * 2f * beat, core * 2f * beat),
                cornerRadius = CornerRadius(core * 0.36f),
                style = Stroke(width = arcWidth),
            )
        }
        drawCircle(color = secondary, radius = core * 0.18f, center = middle)
    }
}

/** One dot on an orbit, placed by polar coordinates so it stays exactly on its arc at every size. */
private fun DrawScope.drawTraveller(
    centre: Offset,
    radius: Float,
    angleDegrees: Float,
    colour: Color,
    dotRadius: Float,
) {
    val radians = Math.toRadians(angleDegrees.toDouble())
    val point = Offset(
        x = centre.x + radius * cos(radians).toFloat(),
        y = centre.y + radius * sin(radians).toFloat(),
    )
    drawCircle(color = colour.copy(alpha = 0.28f), radius = dotRadius * 2.4f, center = point)
    drawCircle(color = colour, radius = dotRadius, center = point)
}
