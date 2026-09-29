package com.gamecore.core.overlay

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * The Scout zoom pane (§Scout): the magnifier's sibling, and deliberately built from the same parts.
 *
 * Scout answers "zoom into the middle to spot something far away, and lift a dark scene so it reads."
 * Both halves are honest screen-space operations on a [MediaProjection][android.media.projection.MediaProjection]
 * frame the service hands in through [frame] — the same feed the loupe draws from — never a read of the
 * game. It crops the centre of the screen out of that bitmap, scales the crop up into a corner, and
 * optionally raises its brightness. There is no detection, no tracking and no aim logic: a crop, a scale
 * and a colour matrix, which is what keeps Scout on the right side of §24 exactly as the loupe is.
 *
 * ## Why the placement comes from [MagnifierGeometry], not from here
 *
 * The projection captures everything on the display, this window included, so if the magnified region ever
 * overlapped the pane the next frame would contain the pane and the picture would spiral into a mirror.
 * [MagnifierGeometry] is the one place that guarantees the centred source and the parked pane are disjoint,
 * and it is shared rather than reimplemented so Scout inherits that guarantee instead of restating it. The
 * pane auto-parks in the first free corner (`preferred = null`), same as the loupe.
 *
 * ## What Scout adds over the loupe
 *
 * Two controls the magnifier does not have, both user preferences the service reads and passes in:
 *  * [factor] — an adjustable zoom (2×–8× via [com.gamecore.core.model.AppSettings.scoutZoomTenths]) rather
 *    than the loupe's fixed [DEFAULT_MAGNIFIER_FACTOR].
 *  * [liftPercent] — a dark-scene brightness lift (0–100), applied to the zoomed crop *only* through a
 *    [ColorMatrix]. Zero draws a faithful crop with no filter at all; higher values raise the crop's
 *    brightness and lift its blacks so a shadowed corner becomes legible.
 *
 * Nothing is drawn when [frame] is null (no frame yet) or when the geometry cannot place the pane without
 * self-capture — handled honestly rather than by drawing an overlapping rectangle.
 */
@Composable
fun ScoutOverlay(
    frame: Bitmap?,
    modifier: Modifier = Modifier,
    factor: Float = DEFAULT_MAGNIFIER_FACTOR,
    liftPercent: Int = 0,
) {
    // Wrapped once per frame, like the loupe: the feed swaps the whole bitmap on each update, so keying on
    // the frame reuses the wrapper until a genuinely new frame arrives.
    val image: ImageBitmap? = remember(frame) { frame?.asImageBitmap() }
    // The lift matrix depends only on the percentage, so it is rebuilt only when that changes rather than on
    // every frame. Null below the threshold means "no filter" — a faithful crop, not a matrix that happens
    // to be the identity, so the common case pays nothing.
    val liftFilter: ColorFilter? = remember(liftPercent) { brightnessLiftFilter(liftPercent) }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val bitmap = image ?: return@Canvas
            val screenW = size.width.roundToInt()
            val screenH = size.height.roundToInt()
            if (screenW <= 0 || screenH <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return@Canvas

            val loupeSide = (minOf(screenW, screenH) * SCOUT_PANE_FRACTION).roundToInt().coerceAtLeast(1)
            val screen = PixelRect(0, 0, screenW, screenH)

            val ready = MagnifierGeometry.resolve(
                anchorX = screenW / 2,
                anchorY = screenH / 2,
                loupeWidth = loupeSide,
                loupeHeight = loupeSide,
                requestedFactor = factor,
                screen = screen,
            ) as? MagnifierResolution.Ready ?: return@Canvas

            // The captured frame may be at a different resolution than the display, so map the source region
            // into frame pixels by each axis's own scale. A frame that matches the screen leaves both at 1.
            val scaleX = bitmap.width.toFloat() / screenW
            val scaleY = bitmap.height.toFloat() / screenH
            val src = ready.source.rect
            val srcLeft = (src.left * scaleX).roundToInt().coerceIn(0, bitmap.width - 1)
            val srcTop = (src.top * scaleY).roundToInt().coerceIn(0, bitmap.height - 1)
            val srcWidth = (src.width * scaleX).roundToInt().coerceIn(1, bitmap.width - srcLeft)
            val srcHeight = (src.height * scaleY).roundToInt().coerceIn(1, bitmap.height - srcTop)

            val pane = ready.loupe
            drawImage(
                image = bitmap,
                srcOffset = IntOffset(srcLeft, srcTop),
                srcSize = IntSize(srcWidth, srcHeight),
                dstOffset = IntOffset(pane.left, pane.top),
                dstSize = IntSize(pane.width, pane.height),
                colorFilter = liftFilter,
            )
            // A border so the pane reads as laid over the game rather than a torn-out patch of it. Tinted so
            // Scout is not mistaken for the loupe at a glance when both are up.
            drawRect(
                color = SCOUT_BORDER_COLOR,
                topLeft = Offset(pane.left.toFloat(), pane.top.toFloat()),
                size = Size(pane.width.toFloat(), pane.height.toFloat()),
                style = Stroke(width = SCOUT_BORDER_WIDTH_PX),
            )
        }
    }
}

/**
 * A brightness lift for the zoomed crop, or null below the threshold so a lift of nothing draws no filter.
 *
 * Linear, because a [ColorMatrix] is the honest tool the draw scope already accepts and it costs one pass
 * over the crop rather than a per-pixel curve. The lift both scales the channels up (making the whole crop
 * brighter) and adds a floor offset (raising blacks so shadow detail separates from pure black), which is
 * the pair a player wants when they say "I can't see into that dark corner". Alpha is left untouched.
 */
private fun brightnessLiftFilter(liftPercent: Int): ColorFilter? {
    val f = liftPercent.coerceIn(0, 100) / 100f
    if (f <= 0f) return null
    val gain = 1f + SCOUT_LIFT_MAX_GAIN * f
    val offset = SCOUT_LIFT_MAX_OFFSET * f
    return ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                gain, 0f, 0f, 0f, offset,
                0f, gain, 0f, 0f, offset,
                0f, 0f, gain, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}

/**
 * The pane's side as a fraction of the screen's shorter edge — a touch larger than the loupe's, because
 * Scout is for reading distance rather than a quick glance, but still small enough to keep a corner
 * placement disjoint from the centred source [MagnifierGeometry] relies on.
 */
private const val SCOUT_PANE_FRACTION = 0.34f

/** Full channel gain added at a 100% lift: up to 1.6× brightness. */
private const val SCOUT_LIFT_MAX_GAIN = 0.6f

/** Black-floor offset added at a 100% lift, in the 0..255 colour-matrix scale: up to ~48/255. */
private const val SCOUT_LIFT_MAX_OFFSET = 48f

/** A cool tint at most of full opacity, distinct from the loupe's plain white border. */
private val SCOUT_BORDER_COLOR = Color(0xFF56C2FF).copy(alpha = 0.9f)

private const val SCOUT_BORDER_WIDTH_PX = 3f
