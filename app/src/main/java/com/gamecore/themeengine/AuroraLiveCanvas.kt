package com.gamecore.themeengine

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The Theme Engine's live backdrop.
 *
 * Two layers, drawn every frame with nothing cached:
 *
 *  1. **Diffusion.** Three large radial gradients of the active vector's colour, laid over a near-black
 *     base and added together with [BlendMode.Plus]. They are deliberately much larger than the canvas
 *     and drift on independent slow sine paths, so they overlap rather than stand as three discs — the
 *     accumulation is what produces a diffused field instead of a blurred circle.
 *  2. **The fluid wave.** Two open sine bands filled to the bottom with a vertical gradient, at
 *     different frequencies and speeds, so the crest of one crosses the trough of the other. That
 *     interference is what reads as liquid; a single band reads as a graph.
 *
 * The only animated value is [phase] — one float, advanced by elapsed time multiplied by the vector's
 * velocity. Everything else is a function of it, which keeps the canvas independent of how high the
 * velocity is: a 3.0x backdrop is the same painting moving faster, not a different one.
 *
 * [vector] is already the smoothed one. This composable does no colour interpolation of its own; it
 * draws whatever it is handed, which is what keeps a transition the engine's business rather than a
 * second animation layered on top of the first.
 */
@Composable
fun AuroraLiveCanvas(
    vector: AuroraVector,
    active: Boolean,
    modifier: Modifier = Modifier,
    description: String = "Theme engine preview",
) {
    val colour = remember(vector.rgb) { vector.rgb.asComposeColor() }
    // Read at draw time rather than captured: the engine updates the vector outside recomposition,
    // and a captured copy would freeze the backdrop at whatever colour the screen was first composed
    // with.
    val currentVector by rememberUpdatedState(vector)
    var phase by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val seconds = (now - last) / 1_000_000_000f
            last = now
            // Wrapped so a session left open for hours does not lose float precision in the sine terms.
            phase = (phase + seconds * currentVector.velocity) % PHASE_WRAP
        }
    }

    Canvas(modifier.semantics { contentDescription = description }) {
        drawAurora(phase, colour, currentVector.velocity)
    }
}

private val PHASE_WRAP = (2.0 * PI * 512.0).toFloat()
private val TAU = (2.0 * PI).toFloat()

private val BASE_TOP = Color(0xFF0B0916)
private val BASE_BOTTOM = Color(0xFF05040A)

/**
 * Draws one frame.
 *
 * Kept as an extension rather than a lambda inside [AuroraLiveCanvas] so the whole painting is in one
 * readable place and can be reasoned about as a picture rather than as composable plumbing.
 */
private fun DrawScope.drawAurora(phase: Float, colour: Color, velocity: Float) {
    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f) return

    // 0 at the slowest velocity, 1 at the fastest. Drives amplitude and opacity so that "more load"
    // is visible even when the device's frame rate would not make the extra speed obvious.
    val span = AuroraBounds.MAX_VELOCITY - AuroraBounds.MIN_VELOCITY
    val energy = ((velocity - AuroraBounds.MIN_VELOCITY) / span).coerceIn(0f, 1f)

    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(BASE_TOP, BASE_BOTTOM),
            startY = 0f,
            endY = height,
        ),
        size = Size(width, height),
    )

    drawDiffusion(phase, colour, energy, width, height)
    drawWaveLayer(
        phase = phase,
        colour = colour,
        energy = energy,
        width = width,
        height = height,
        frequency = 1.25f,
        speed = 1f,
        baseline = 0.66f,
        amplitude = 0.050f,
        alpha = 0.16f + 0.26f * energy,
        phaseOffset = 0f,
    )
    drawWaveLayer(
        phase = phase,
        colour = colour,
        energy = energy,
        width = width,
        height = height,
        frequency = 2.05f,
        speed = 0.62f,
        baseline = 0.78f,
        amplitude = 0.032f,
        alpha = 0.12f + 0.18f * energy,
        phaseOffset = 1.9f,
    )
    drawHighlightCrest(phase, colour, energy, width, height)

    // A vignette over everything. Without it the additive blobs flatly light the corners and the
    // canvas reads as a gradient wallpaper; with it the colour sits in the middle and the edges fall
    // away, which is what makes the wave look like it is inside something.
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
            center = Offset(width * 0.5f, height * 0.45f),
            radius = max(width, height) * 0.75f,
        ),
        size = Size(width, height),
    )
}

/**
 * The three added radial fields.
 *
 * Each centre drifts on its own pair of sine terms and its own phase multiplier, so the three never
 * move together and their overlap is never the same twice.
 */
private fun DrawScope.drawDiffusion(
    phase: Float,
    colour: Color,
    energy: Float,
    width: Float,
    height: Float,
) {
    val whitened = lerp(colour, Color.White, 0.28f)
    val deepened = lerp(colour, Color.Black, 0.35f)
    val reach = max(width, height)

    val blobs = listOf(
        Triple(
            Offset(
                width * (0.34f + 0.10f * sin(phase * 0.21f)),
                height * (0.36f + 0.09f * cos(phase * 0.17f)),
            ),
            whitened,
            0.78f,
        ),
        Triple(
            Offset(
                width * (0.70f + 0.09f * cos(phase * 0.15f + 1.1f)),
                height * (0.62f + 0.08f * sin(phase * 0.23f + 0.4f)),
            ),
            colour,
            0.66f,
        ),
        Triple(
            Offset(
                width * (0.50f + 0.14f * sin(phase * 0.11f + 2.2f)),
                height * (0.88f + 0.06f * cos(phase * 0.19f + 1.7f)),
            ),
            deepened,
            0.60f,
        ),
    )

    blobs.forEach { (centre, tint, scale) ->
        val radius = reach * scale
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    tint.copy(alpha = 0.34f + 0.16f * energy),
                    tint.copy(alpha = 0.16f),
                    Color.Transparent,
                ),
                center = centre,
                radius = radius,
            ),
            radius = radius,
            center = centre,
            blendMode = BlendMode.Plus,
        )
        // A small, tight core on the first two fields. Purely visual: it keeps the largest field from
        // reading as a flat wash at the centre of the screen.
        if (scale > 0.65f) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        lerp(tint, Color.White, 0.5f).copy(alpha = 0.28f * (0.4f + energy)),
                        Color.Transparent,
                    ),
                    center = centre,
                    radius = reach * 0.18f,
                ),
                radius = reach * 0.18f,
                center = centre,
                blendMode = BlendMode.Plus,
            )
        }
    }
}

/** One sine band, filled from its crest down to the bottom edge. */
private fun DrawScope.drawWaveLayer(
    phase: Float,
    colour: Color,
    energy: Float,
    width: Float,
    height: Float,
    frequency: Float,
    speed: Float,
    baseline: Float,
    amplitude: Float,
    alpha: Float,
    phaseOffset: Float,
) {
    val travelled = phase * speed
    val swing = amplitude * (0.6f + 0.8f * energy)
    val steps = 64
    val path = Path()

    for (index in 0..steps) {
        val fraction = index / steps.toFloat()
        val x = width * fraction
        // Two sines at an irrational-looking ratio: the crest travels without ever repeating where it
        // began, which is what stops the motion reading as a loop.
        val y = height * (
            baseline +
                swing * sin(fraction * TAU * frequency + travelled) +
                swing * 0.35f * sin(fraction * TAU * (frequency * 2.37f) - travelled * 0.8f + phaseOffset)
            )
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.lineTo(width, height)
    path.lineTo(0f, height)
    path.close()

    drawPath(
        path = path,
        brush = Brush.verticalGradient(
            colors = listOf(colour.copy(alpha = alpha), Color.Transparent),
            startY = height * (baseline - amplitude),
            endY = height,
        ),
    )
}

/** A thin bright line along the first crest, so the wave has an edge instead of only a fill. */
private fun DrawScope.drawHighlightCrest(
    phase: Float,
    colour: Color,
    energy: Float,
    width: Float,
    height: Float,
) {
    val swing = 0.050f * (0.6f + 0.8f * energy)
    val steps = 64
    val path = Path()
    for (index in 0..steps) {
        val fraction = index / steps.toFloat()
        val x = width * fraction
        val y = height * (
            0.66f +
                swing * sin(fraction * TAU * 1.25f + phase) +
                swing * 0.35f * sin(fraction * TAU * 2.9625f - phase * 0.8f)
            )
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path = path,
        color = lerp(colour, Color.White, 0.55f).copy(alpha = 0.10f + 0.24f * energy),
        style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        ),
    )
}

/** `Triple<Int, Int, Int>` → an opaque sRGB [Color]. */
private fun Triple<Int, Int, Int>.asComposeColor(): Color = Color(first, second, third)
