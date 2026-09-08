package com.gamecore.core.overlay

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gamecore.core.model.CrosshairDesign
import com.gamecore.core.model.CrosshairPreset
import java.io.File
import kotlin.math.roundToInt

/**
 * The crosshair of §9: a shape drawn in GameCore's own window, and nothing else.
 *
 * Worth repeating here because this is the file where someone would try: this composable has no
 * knowledge of the game underneath. It does not read its memory, its frames, or its own reticle, it
 * does not move on its own, and it does not receive touches — the window hosting it is
 * `FLAG_NOT_TOUCHABLE`. It is the sticker-on-the-glass of the model's KDoc, which is what keeps it on
 * the right side of §24 while an aim assist would not be.
 *
 * Everything is drawn from [CrosshairPreset] with Compose primitives, so every design is sharp at
 * every size and the APK carries no crosshair bitmaps.
 *
 * The outline is drawn first and slightly wider, in black. Without it a thin cyan cross vanishes
 * against a bright sky or a snow map, and a crosshair that disappears in some scenes is worse than no
 * crosshair at all — the player stops trusting where the centre is.
 */
@Composable
fun CrosshairOverlay(preset: CrosshairPreset, modifier: Modifier = Modifier) {
    val normalised = remember(preset) { preset.normalised() }
    val density = LocalDensity.current
    val sizePx = remember(normalised.sizeDp, density) {
        with(density) { normalised.sizeDp.dp.toPx() }
    }
    val thicknessPx = remember(normalised.thicknessDp, density) {
        with(density) { normalised.thicknessDp.dp.toPx() }
    }
    val gapPx = remember(normalised.centreGapDp, density) {
        with(density) { normalised.centreGapDp.dp.toPx() }
    }

    // Decoded once per path rather than per frame. A decode inside the draw scope would run on every
    // recomposition of a window that updates once a second.
    val image: ImageBitmap? = remember(normalised.imagePath) {
        normalised.imagePath?.let(::decodeCrosshairImage)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centre = Offset(
                x = size.width * normalised.xFraction,
                y = size.height * normalised.yFraction,
            )
            val alpha = normalised.opacityPercent / 100f
            val colour = Color(normalised.colorArgb).copy(alpha = alpha)

            if (normalised.design == CrosshairDesign.CUSTOM_IMAGE) {
                // A missing file draws nothing. The preset is not silently swapped for a cross: the
                // user chose an image, and a different shape appearing in its place would look like the
                // overlay had been tampered with rather than like a file had gone missing.
                image?.let { drawCrosshairImage(it, centre, sizePx, alpha) }
                return@Canvas
            }

            rotate(degrees = normalised.rotationDegrees.toFloat(), pivot = centre) {
                if (normalised.showOutline) {
                    drawDesign(
                        design = normalised.design,
                        centre = centre,
                        radius = sizePx / 2f,
                        thickness = thicknessPx + OUTLINE_EXTRA_PX,
                        gap = gapPx / 2f,
                        colour = Color.Black.copy(alpha = alpha * OUTLINE_ALPHA_FACTOR),
                        withDot = normalised.showDot,
                    )
                }
                drawDesign(
                    design = normalised.design,
                    centre = centre,
                    radius = sizePx / 2f,
                    thickness = thicknessPx,
                    gap = gapPx / 2f,
                    colour = colour,
                    withDot = normalised.showDot,
                )
            }
        }
    }
}

/**
 * Draws the imported image centred on the crosshair position, scaled to the preset's size.
 *
 * The aspect ratio is preserved by fitting the longer edge, so a non-square import is not stretched;
 * a stretched crosshair is off-centre in one axis, which defeats the point of it.
 */
private fun DrawScope.drawCrosshairImage(
    image: ImageBitmap,
    centre: Offset,
    sizePx: Float,
    alpha: Float,
) {
    val longest = maxOf(image.width, image.height).takeIf { it > 0 } ?: return
    val scale = sizePx / longest
    val width = (image.width * scale).roundToInt().coerceAtLeast(1)
    val height = (image.height * scale).roundToInt().coerceAtLeast(1)
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(
            (centre.x - width / 2f).roundToInt(),
            (centre.y - height / 2f).roundToInt(),
        ),
        dstSize = IntSize(width, height),
        alpha = alpha,
    )
}

/**
 * Every drawn design, as one exhaustive `when`.
 *
 * Counted in neither the summary nor the KDoc, deliberately. This said "the nine drawn designs" until a
 * tenth was added, and a number in a comment beside an enum is a fact that goes stale silently — the
 * compiler checks the `when`, and nothing checks the sentence. The set is [CrosshairDesign.isDrawn] and
 * that is where to read it.
 *
 * [gap] is half the preset's centre gap — the distance from the centre at which a line starts — so a
 * gap of 8 dp leaves 8 dp of clear space across the middle rather than 16.
 */
private fun DrawScope.drawDesign(
    design: CrosshairDesign,
    centre: Offset,
    radius: Float,
    thickness: Float,
    gap: Float,
    colour: Color,
    withDot: Boolean,
) {
    val stroke = Stroke(width = thickness, cap = StrokeCap.Round)
    fun line(fromX: Float, fromY: Float, toX: Float, toY: Float) = drawLine(
        color = colour,
        start = Offset(centre.x + fromX, centre.y + fromY),
        end = Offset(centre.x + toX, centre.y + toY),
        strokeWidth = thickness,
        cap = StrokeCap.Round,
    )

    fun ring() = drawCircle(color = colour, radius = radius, center = centre, style = stroke)

    fun dot(scale: Float = DOT_SCALE) = drawCircle(
        color = colour,
        radius = (radius * scale).coerceAtLeast(thickness / 2f),
        center = centre,
    )

    when (design) {
        CrosshairDesign.CROSS -> {
            line(0f, -radius, 0f, -gap)
            line(0f, gap, 0f, radius)
            line(-radius, 0f, -gap, 0f)
            line(gap, 0f, radius, 0f)
        }

        CrosshairDesign.DOT -> dot(DOT_ONLY_SCALE)

        CrosshairDesign.CIRCLE -> ring()

        CrosshairDesign.CIRCLE_DOT -> {
            ring()
            dot()
        }

        CrosshairDesign.CROSS_CIRCLE -> {
            ring()
            line(0f, -radius, 0f, -gap)
            line(0f, gap, 0f, radius)
            line(-radius, 0f, -gap, 0f)
            line(gap, 0f, radius, 0f)
        }

        // Nothing above the centre: the shape exists so the line does not cover the thing being
        // aimed at in games that draw damage numbers above the target.
        CrosshairDesign.T_SHAPE -> {
            line(-radius, 0f, -gap, 0f)
            line(gap, 0f, radius, 0f)
            line(0f, gap, 0f, radius)
        }

        CrosshairDesign.X_SHAPE -> {
            val d = radius * DIAGONAL
            val g = gap * DIAGONAL
            line(-d, -d, -g, -g)
            line(g, g, d, d)
            line(-d, d, -g, g)
            line(g, -g, d, -d)
        }

        CrosshairDesign.CHEVRON -> {
            val apex = radius * CHEVRON_APEX
            val arm = radius * CHEVRON_ARM
            line(-arm, -apex, 0f, apex)
            line(0f, apex, arm, -apex)
        }

        // Four corner marks. `arm` is deliberately short: brackets that meet in the middle of each
        // edge are a square, and a square is not a crosshair.
        CrosshairDesign.BRACKETS -> {
            val arm = radius * BRACKET_ARM
            listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).forEach { (sx, sy) ->
                line(sx * radius, sy * radius, sx * (radius - arm), sy * radius)
                line(sx * radius, sy * radius, sx * radius, sy * (radius - arm))
            }
        }

        // A square outline the size of the circle designs' bounding box rather than of their ring: `radius`
        // inscribed would give a square noticeably smaller than the ring at the same preset size, and a user
        // switching between them expects the crosshair to stay roughly as big as it was. Drawn as one
        // stroked rect rather than four lines so the corners join instead of overlapping — four round-capped
        // lines meeting at a corner leave a visible lump at each one.
        CrosshairDesign.BOX -> drawRect(
            color = colour,
            topLeft = Offset(centre.x - radius, centre.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = stroke,
        )

        CrosshairDesign.CUSTOM_IMAGE -> Unit
    }

    // The centre dot is an addition to whatever shape was chosen, so it is drawn after and skipped for
    // the two designs that already are a dot.
    if (withDot && design != CrosshairDesign.DOT && design != CrosshairDesign.CIRCLE_DOT) {
        dot()
    }
}

/**
 * Decodes an imported crosshair, or null.
 *
 * The path is one GameCore wrote itself — imports are re-encoded into app storage — but it is still
 * checked and still decoded defensively, because the file can have been deleted by a storage cleaner
 * since. A decode failure produces null rather than an exception, so a corrupt file costs the crosshair
 * and not the overlay service.
 */
private fun decodeCrosshairImage(path: String): ImageBitmap? = try {
    val file = File(path)
    if (!file.isFile || !file.canRead()) {
        null
    } else {
        BitmapFactory.decodeFile(path)?.asImageBitmap()
    }
} catch (unreadable: Exception) {
    null
}

/**
 * How much wider than the crosshair's own stroke the black outline is drawn.
 *
 * Pixels rather than dp, and small: the outline exists to give the shape an edge, and one that scales
 * with density would swallow a 1 dp crosshair on a 3x screen.
 */
private const val OUTLINE_EXTRA_PX = 2f

/** The outline is never fully opaque, so a thick one does not read as a black cross with a tint. */
private const val OUTLINE_ALPHA_FACTOR = 0.65f

/** The centre dot, as a fraction of the crosshair's radius. */
private const val DOT_SCALE = 0.14f

/** The [CrosshairDesign.DOT] design, which is the whole crosshair rather than an addition to one. */
private const val DOT_ONLY_SCALE = 0.5f

/** cos 45°, so the arms of an X reach the same distance from the centre as the arms of a cross. */
private const val DIAGONAL = 0.7071f

private const val CHEVRON_APEX = 0.55f
private const val CHEVRON_ARM = 0.8f
private const val BRACKET_ARM = 0.45f
