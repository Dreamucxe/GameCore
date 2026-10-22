package com.gamecore.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gamecore.core.plugin.theme.PluginGlitch
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The host's half of a schema-3 theme's `glitch` block: a subtree that periodically tears itself.
 *
 * The design constraint the whole file works under is that a `.gcplugin` is **data**. The blueprint
 * a "true glitch" theme is usually written from assumes it can intercept the rendering pipeline,
 * rewrite `WindowManager.LayoutParams`, and reach into the frame buffer — a plugin cannot do any of
 * that here, and the day one could, installing a package would stop being a low-risk act. So the
 * plugin says *how hard* (see [PluginGlitch]) and this file, which ships in the APK and is written
 * by the same people who write the app, decides *how*. Every frame below is the host's.
 *
 * ## How a burst is drawn
 *
 * The content of the subtree is recorded once into a [androidx.compose.ui.graphics.layer.GraphicsLayer]
 * at the start of the burst frame, then drawn back up to three times per band:
 *
 *  - **Bands.** The frame is split into `slices` horizontal bands of random height. Each band is
 *    drawn inside a `clipRect` of its own, translated sideways by its own offset. Because the
 *    recording is the *whole* subtree, the tear cuts through text, icons and panels alike — this is
 *    structural, not a colour filter.
 *  - **Ghosts.** Each band is drawn twice more at ±`ghostOffsetDp` before the crisp copy, so the
 *    edges of the displaced recording peek out on both sides. That is the double-exposure read of a
 *    chromatic split; a per-channel filter would need a layer property swap between draws that is
 *    not guaranteed to land within one frame, and a tear that sometimes renders as a flat image is
 *    worse than one that always renders slightly differently.
 *  - **Fractures.** A one-pixel line in the theme's secondary colour at each band boundary.
 *  - **Noise.** Translucent blocks in the accent and fracture colours over the top.
 *
 * Between bursts nothing is recorded and the draw is a straight `drawContent()` — a theme that is
 * not glitching at this instant costs exactly what it did before this file existed. The one
 * exception is [PluginGlitch.microJitter], which rides on `Modifier.graphicsLayer` and therefore
 * translates the already-drawn result without a recording.
 *
 * ## When it runs
 *
 * Idle-for-a-random-interval, then burst-for-two-to-six-frames. [GlitchConditions] can shorten the
 * idle interval (a loaded device looks worse than an idle one) and can add a vertical drop on the
 * first frame of a burst when the platform reports thermal throttling — the "frame drop illusion".
 * All of it is suppressed entirely when the caller passes `enabled = false`, which is what the
 * user's own Animations setting does: a theme is an intention, the accessibility preference is a
 * veto, and the veto always wins.
 */
@Composable
fun GlitchLayer(
    glitch: PluginGlitch?,
    enabled: Boolean,
    accent: Color,
    fracture: Color,
    conditions: GlitchConditions,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // A theme with no motion block, or a user who turned animation off, costs one composable call
    // and no Box: `content()` is invoked directly so the tree is identical to the untweaked app.
    if (glitch == null || !enabled) {
        content()
        return
    }

    // Conditions arrive from a sampler and change on their own schedule. Read through a State so a
    // new reading is picked up by the *next* burst instead of restarting the loop — a restart every
    // time the CPU percentage moved a decimal would reset the idle interval to its minimum.
    val liveConditions = rememberUpdatedState(conditions)

    // Keyed on the block, so switching theme mid-session starts a fresh rhythm rather than
    // continuing one that was tuned to a different intensity.
    var frame by remember(glitch) { mutableStateOf(GlitchFrame.IDLE) }

    LaunchedEffect(glitch) {
        // Seeded from the block's own content so a given theme produces the same rhythm on every
        // launch — the effect is a design, not a dice roll — while two themes that differ in any
        // field diverge. `hashCode` of a data class is exactly the field-wise value wanted here.
        val rng = Random(glitch.hashCode())
        while (isActive) {
            delay(nextIdleDelayMs(rng, glitch, liveConditions.value))
            repeat(burstFrameCount(glitch)) { index ->
                frame = burstFrame(rng, glitch, liveConditions.value, index)
                // One state change per displayed frame, not per iteration: the gesture the user is
                // meant to read is a dropped frame, and a timer that ran faster than the display
                // would draw bursts nobody ever saw.
                withFrameNanos { }
            }
            frame = GlitchFrame.IDLE
        }
    }

    val layer = rememberGraphicsLayer()
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                // dp, so the jitter reads the same on a 400 and a 640 dpi panel. Applied outside
                // the recording, so it moves the finished image and costs no extra draw.
                translationX = frame.jitterX.dp.toPx()
                translationY = frame.jitterY.dp.toPx()
            }
            .drawWithContent {
                val current = frame
                if (!current.tearing) {
                    drawContent()
                    return@drawWithContent
                }

                layer.record(
                    density = this,
                    layoutDirection = this.layoutDirection,
                    size = IntSize(size.width.toInt(), size.height.toInt()),
                ) { this@drawWithContent.drawContent() }

                val ghost = glitch.ghostOffsetDp.dp.toPx()
                val lineHeight = 1.dp.toPx()
                var top = 0f
                current.bands.forEachIndexed { index, band ->
                    // The last band takes whatever is left rather than its own fraction, so a
                    // rounding error across five bands can never leave a hairline of background
                    // undrawn at the bottom of the screen.
                    val bottom = if (index == current.bands.lastIndex) {
                        size.height
                    } else {
                        top + size.height * band.fraction
                    }
                    clipRect(left = 0f, top = top, right = size.width, bottom = bottom) {
                        val dx = band.offsetDp.dp.toPx()
                        if (ghost > 0f) {
                            translate(left = dx - ghost) { drawLayer(layer) }
                            translate(left = dx + ghost) { drawLayer(layer) }
                        }
                        translate(left = dx) { drawLayer(layer) }
                    }
                    if (band.fracture) {
                        drawRect(
                            color = fracture,
                            topLeft = Offset(0f, top),
                            size = Size(size.width, lineHeight),
                        )
                    }
                    top = bottom
                }

                for (block in current.blocks) {
                    drawRect(
                        color = if (block.hot) accent.copy(alpha = 0.20f) else fracture.copy(alpha = 0.16f),
                        topLeft = Offset(block.left * size.width, block.top * size.height),
                        size = Size(block.width * size.width, block.height * size.height),
                    )
                }
            },
    ) {
        content()
    }
}

/**
 * The device state a glitch block with `hardwareReactive` on is allowed to read.
 *
 * Deliberately two numbers and nothing else. A renderer that read a dozen metrics would be a
 * renderer whose behaviour nobody could predict from the setting it was configured with, and the
 * cost of collecting them would be paid by users who never installed a glitch theme.
 *
 * [cpuPercent] is null until the sampler has two `/proc/stat` reads to difference, which is the
 * first tick of a session — treated as "no load", the conservative reading, rather than as zero
 * load and therefore "healthy" by accident.
 */
@Immutable
data class GlitchConditions(
    val cpuPercent: Float? = null,
    val thermalLevel: Int = 0,
) {
    /** True when the device is working hard enough that its UI should look like it is struggling. */
    val overloaded: Boolean get() = (cpuPercent ?: 0f) >= OVERLOAD_CPU_PERCENT

    /**
     * True when the platform reports the device is thermally throttled.
     *
     * Level 3 is `PowerManager.THERMAL_STATUS_SEVERE`. The constant is mirrored rather than
     * imported so this file stays free of `android.power` — the value is part of the platform's
     * API contract and has not moved since the status was introduced in API 29.
     */
    val throttling: Boolean get() = thermalLevel >= THROTTLE_THERMAL_LEVEL

    companion object {
        const val OVERLOAD_CPU_PERCENT = 85f
        const val THROTTLE_THERMAL_LEVEL = 3

        /** What an app that is not sampling, or a theme that is not reactive, passes in. */
        val None = GlitchConditions()
    }
}

/** One horizontal band of a torn frame. [fraction] is the share of the height it takes. */
internal data class GlitchBand(val fraction: Float, val offsetDp: Float, val fracture: Boolean)

/** One static-artefact block, in fractions of the frame, so it survives any panel size. */
internal data class GlitchBlock(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val hot: Boolean,
)

/**
 * One drawn frame of a burst.
 *
 * Empty [bands] is the idle frame and the only one the renderer is allowed to draw without
 * recording; [jitterX] and [jitterY] stand alone so micro-jitter and the frame-drop illusion work
 * on a frame that is not also tearing.
 */
internal data class GlitchFrame(
    val bands: List<GlitchBand> = emptyList(),
    val blocks: List<GlitchBlock> = emptyList(),
    val jitterX: Float = 0f,
    val jitterY: Float = 0f,
) {
    val tearing: Boolean get() = bands.isNotEmpty()

    companion object {
        val IDLE = GlitchFrame()
    }
}

/**
 * How long to stay still before the next burst, in milliseconds.
 *
 * Uniform over the block's range rather than a bell around its middle: a predictable interval reads
 * as a metronome, and the whole point of the effect is that the viewer cannot tell when the next
 * frame will fail. An overloaded device draws from the same distribution at half the interval,
 * floored at [PluginGlitch.MIN_BURST_MS] — the same floor a theme's own range is clamped to, so
 * load can make the effect twice as frequent and never more.
 */
internal fun nextIdleDelayMs(
    rng: Random,
    glitch: PluginGlitch,
    conditions: GlitchConditions,
): Long {
    val span = (glitch.burstMaxMs - glitch.burstMinMs).coerceAtLeast(1)
    var delay = glitch.burstMinMs + rng.nextInt(span)
    if (conditions.overloaded) {
        delay = (delay / 2).coerceAtLeast(PluginGlitch.MIN_BURST_MS)
    }
    return delay.toLong()
}

/**
 * How many frames a burst lasts.
 *
 * Two at the floor and six at the ceiling: a single frame is indistinguishable from a dropped
 * frame, and past six the eye reads a broken layout rather than a struggling system. Intensity
 * moves along that range, so the theme's own `intensity` is also the burst's length.
 */
internal fun burstFrameCount(glitch: PluginGlitch): Int =
    (2 + glitch.intensity * 4).roundToInt().coerceIn(2, 6)

/** The vertical drop, in dp, of the first frame of a burst on a throttled device. */
internal const val FRAME_DROP_DP = 14f

/**
 * One frame of a burst. [index] is its position within the burst, which is what lets the first
 * frame carry the throttling drop and the rest not.
 *
 * Pure and seeded: every call takes its randomness from [rng], so a test can pin the exact frame a
 * given seed produces and the renderer can guarantee a theme looks the same on every launch.
 */
internal fun burstFrame(
    rng: Random,
    glitch: PluginGlitch,
    conditions: GlitchConditions,
    index: Int,
): GlitchFrame {
    var jitterX = 0f
    var jitterY = 0f
    if (glitch.microJitter) {
        jitterX = rng.nextInt(-2, 3).toFloat()
        jitterY = rng.nextInt(-1, 2).toFloat()
    }
    // The frame-drop illusion is a deliberate vertical jump on the first frame only: held for the
    // whole burst it would read as the panel being in the wrong place, not as a frame arriving late.
    if (conditions.throttling && index == 0) {
        jitterY = FRAME_DROP_DP
    }
    return GlitchFrame(
        bands = tearBands(rng, glitch),
        blocks = if (glitch.noise) noiseBlocks(rng, glitch) else emptyList(),
        jitterX = jitterX,
        jitterY = jitterY,
    )
}

/**
 * The band layout for one torn frame.
 *
 * Heights are random weights normalised to 1, not `1/slices` each — equal bands read as a venetian
 * blind, and the tear only looks like a fault when the cuts are at unpredictable places. Offsets are
 * symmetric around zero and scaled by intensity, so a theme at 0.5 never displaces a band as far as
 * its own `sliceOffsetDp` asks for.
 */
internal fun tearBands(rng: Random, glitch: PluginGlitch): List<GlitchBand> {
    val count = glitch.slices.coerceIn(PluginGlitch.MIN_SLICES, PluginGlitch.MAX_SLICES)
    val weights = FloatArray(count) { 0.6f + rng.nextFloat() }
    val total = weights.sum()
    val maxOffset = glitch.sliceOffsetDp * glitch.intensity
    var consumed = 0f
    return List(count) { index ->
        val fraction = if (index == count - 1) {
            (1f - consumed).coerceAtLeast(0f)
        } else {
            (weights[index] / total).also { consumed += it }
        }
        val offset = if (maxOffset <= 0f || rng.nextFloat() < 0.25f) {
            // A quarter of the bands — and every band of a zero-offset theme — stay put, so the
            // frame reads as two pieces torn apart rather than as every row sliding independently.
            0f
        } else {
            rng.nextInt(-maxOffset.roundToInt(), maxOffset.roundToInt() + 1).toFloat()
        }
        GlitchBand(
            fraction = fraction,
            offsetDp = offset,
            fracture = glitch.fractureLines && rng.nextFloat() < 0.7f,
        )
    }
}

/** The static-artefact blocks drawn over one torn frame. */
internal fun noiseBlocks(rng: Random, glitch: PluginGlitch): List<GlitchBlock> {
    val count = (glitch.intensity * 7).roundToInt().coerceIn(1, 8)
    return List(count) {
        GlitchBlock(
            left = rng.nextFloat() * 0.9f,
            top = rng.nextFloat() * 0.95f,
            width = 0.06f + rng.nextFloat() * 0.32f,
            height = 0.008f + rng.nextFloat() * 0.05f,
            hot = rng.nextBoolean(),
        )
    }
}
