package com.gamecore.core.overlay

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.gamecore.core.model.HuntFilter
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The hunting filter (§Hunt): a full-screen colour grade that makes targets separate from the scene.
 *
 * Which grade is drawn is [filter], a user choice; this composable turns that choice into pixels. Every
 * grade is a function of the pixels already on the glass — a tint, an inversion, a contrast push — never a
 * read of the game, which keeps Hunt on the same side of §24 as the loupe and the crosshair.
 *
 * There are two honest halves, split by [HuntFilter.needsCapture]:
 *
 *  * The capture-free grade ([HuntFilter.MOVIE]) draws over GameCore's own transparent window: a warm wash
 *    and a soft vignette the player still sees the game through. It needs no [MediaProjection]
 *    [android.media.projection.MediaProjection], so it comes up the instant the toggle is hit and is the
 *    one grade restored across a process death.
 *  * The capture grades re-grade the *actual* screen, which cannot be done to a transparent overlay — you
 *    can only re-grade pixels you can see. So they take the projection [frame] the service hands in (the
 *    same feed the loupe and Scout draw from), paint it back full-screen and opaque, and apply the grade as
 *    a [ColorFilter] at draw time. Nothing is drawn until the first frame arrives; the service discloses the
 *    capture with its own banner.
 */
@Composable
fun HuntOverlay(
    filter: HuntFilter,
    frame: Bitmap?,
    modifier: Modifier = Modifier,
) {
    if (filter.needsCapture) {
        CaptureGrade(filter = filter, frame = frame, modifier = modifier)
    } else {
        TintGrade(modifier = modifier)
    }
}

/**
 * The capture-free grade: a translucent warm wash and a vignette laid over the game.
 *
 * Drawn with plain `drawRect`s over a transparent window, so the game shows through — this is a mood
 * grade, not a replacement of the picture. The vignette is a radial gradient that stays clear in the
 * centre (where the player is looking) and darkens the edges (where distraction lives).
 */
@Composable
private fun TintGrade(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // The warm cinematic wash.
            drawRect(color = MOVIE_TINT)
            // A soft vignette: clear in the middle, darkening toward the corners.
            val radius = hypot(size.width, size.height) / 2f
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, MOVIE_VIGNETTE),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = radius.coerceAtLeast(1f),
                ),
            )
        }
    }
}

/**
 * A capture grade: the live frame painted back full-screen and opaque, with [filter]'s matrix applied.
 *
 * The frame may be at a lower resolution than the display (the service is free to size the reader down to
 * save memory); it is scaled to fill, which is right for a full-screen grade where an exact pixel mapping
 * does not matter the way it does for the loupe's crop.
 */
@Composable
private fun CaptureGrade(filter: HuntFilter, frame: Bitmap?, modifier: Modifier = Modifier) {
    val image: ImageBitmap? = remember(frame) { frame?.asImageBitmap() }
    val colorFilter: ColorFilter? = remember(filter) { gradeMatrix(filter)?.let { ColorFilter.colorMatrix(it) } }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val bitmap = image ?: return@Canvas
            val screenW = size.width.roundToInt()
            val screenH = size.height.roundToInt()
            if (screenW <= 0 || screenH <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return@Canvas
            drawImage(
                image = bitmap,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(bitmap.width, bitmap.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(screenW, screenH),
                colorFilter = colorFilter,
            )
        }
    }
}

/**
 * The colour matrix each grade applies, or null for the capture-free one.
 *
 * A `when` with no `else`, so a grade added to [HuntFilter] cannot be shipped without deciding what it does
 * to the picture here — the §32 discipline the enums are built on. Every matrix is a function of the pixels
 * alone: a desaturation with contrast, a straight inversion, a hard two-tone push.
 */
private fun gradeMatrix(filter: HuntFilter): ColorMatrix? = when (filter) {
    // Capture-free: handled by the tint path, never reaches here.
    HuntFilter.MOVIE -> null
    // Desaturate to luminance, then a moderate contrast push around mid-grey: a clean instrument readout.
    HuntFilter.INSTRUMENT -> monochromeContrast(1.6f)
    // Straight per-channel inversion — a photographic negative.
    HuntFilter.FILM -> ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
    // Desaturate, then a hard contrast push toward black-and-white — a stark, sketch-like grade.
    HuntFilter.SKETCH -> monochromeContrast(3f)
}

/**
 * A grayscale-plus-contrast matrix: collapse to luminance, then stretch around mid-grey by [contrast].
 *
 * One matrix rather than a desaturate matrix multiplied by a contrast matrix, so the two never disagree and
 * the whole grade is a single pass. Each output channel is the same luminance line, scaled by the contrast
 * and offset so mid-grey (128) stays put while the ends spread.
 */
private fun monochromeContrast(contrast: Float): ColorMatrix {
    val r = LUM_R * contrast
    val g = LUM_G * contrast
    val b = LUM_B * contrast
    val offset = 128f - 128f * contrast
    return ColorMatrix(
        floatArrayOf(
            r, g, b, 0f, offset,
            r, g, b, 0f, offset,
            r, g, b, 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

/** Rec. 601 luminance weights, the standard desaturation coefficients. */
private const val LUM_R = 0.299f
private const val LUM_G = 0.587f
private const val LUM_B = 0.114f

/** A warm amber wash at low opacity — a tint, not a curtain. */
private val MOVIE_TINT = Color(0xFFFFB74D).copy(alpha = 0.18f)

/** How dark the vignette's corners go. */
private val MOVIE_VIGNETTE = Color.Black.copy(alpha = 0.55f)
