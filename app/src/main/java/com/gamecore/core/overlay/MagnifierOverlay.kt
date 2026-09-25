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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.gamecore.core.vision.MagnifierGeometry
import com.gamecore.core.vision.MagnifierResolution
import com.gamecore.core.vision.PixelRect
import kotlin.math.roundToInt

/**
 * The pinned magnifier of §13: a loupe that shows the centre of the screen enlarged, and nothing more.
 *
 * Like [CrosshairOverlay] this composable is a sticker on the glass — but the pixels it draws come from a
 * [MediaProjection][android.media.projection.MediaProjection] frame the service hands in through [frame],
 * not from the game's memory. It reads that one bitmap, crops the region the loupe covers out of it, and
 * scales that crop up into a corner. There is no detection, no tracking and no aim logic here; it is a
 * crop-and-scale, which is what keeps it on the right side of §24.
 *
 * ## Why the placement comes from [MagnifierGeometry] and not from here
 *
 * The projection captures *everything* composited onto the display, this window included. If the region
 * being magnified ever overlapped the loupe, the next captured frame would contain the loupe, the loupe
 * would magnify itself, and the picture would spiral into an infinite mirror (see [MagnifierGeometry]).
 * The geometry object is the one place that guarantees the source region and the parked loupe are
 * disjoint, so the drawing here does exactly what it is told and never chooses coordinates of its own.
 *
 * ## Scope of this build
 *
 * The anchor is the centre of the screen and the factor is fixed at [DEFAULT_MAGNIFIER_FACTOR]; there is
 * no drag, no factor slider and no remembered corner (`preferred = null`, so the loupe auto-parks in the
 * first free corner). Those are the §13 controls a later build adds; this one ships the loupe itself.
 *
 * Nothing is drawn when [frame] is null — the feed has not produced a frame yet — or when the geometry
 * cannot place the loupe without self-capture, which at a centred anchor with a small loupe does not
 * arise but is handled honestly rather than by drawing an overlapping rectangle.
 */
@Composable
fun MagnifierOverlay(
    frame: Bitmap?,
    modifier: Modifier = Modifier,
    factor: Float = DEFAULT_MAGNIFIER_FACTOR,
) {
    // Wrapped once per frame rather than inside the draw scope, so an unrelated recomposition does not
    // re-wrap a bitmap that has not changed. The feed replaces the whole bitmap on each update, so the
    // key is the frame itself — a new frame is a new wrapper, the same frame reuses the last one.
    val image: ImageBitmap? = remember(frame) { frame?.asImageBitmap() }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val bitmap = image ?: return@Canvas
            val screenW = size.width.roundToInt()
            val screenH = size.height.roundToInt()
            if (screenW <= 0 || screenH <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return@Canvas

            val loupeSide = (minOf(screenW, screenH) * LOUPE_FRACTION).roundToInt().coerceAtLeast(1)
            val screen = PixelRect(0, 0, screenW, screenH)

            val ready = MagnifierGeometry.resolve(
                anchorX = screenW / 2,
                anchorY = screenH / 2,
                loupeWidth = loupeSide,
                loupeHeight = loupeSide,
                requestedFactor = factor,
                screen = screen,
            ) as? MagnifierResolution.Ready ?: return@Canvas

            // The source region is in screen pixels; the captured frame can be at a different resolution
            // (the service is free to size the reader below the display to save memory), so map the region
            // into frame pixels by each axis's own scale. A frame that matches the screen leaves both at 1.
            val scaleX = bitmap.width.toFloat() / screenW
            val scaleY = bitmap.height.toFloat() / screenH
            val src = ready.source.rect
            val srcLeft = (src.left * scaleX).roundToInt().coerceIn(0, bitmap.width - 1)
            val srcTop = (src.top * scaleY).roundToInt().coerceIn(0, bitmap.height - 1)
            val srcWidth = (src.width * scaleX).roundToInt().coerceIn(1, bitmap.width - srcLeft)
            val srcHeight = (src.height * scaleY).roundToInt().coerceIn(1, bitmap.height - srcTop)

            val loupe = ready.loupe
            drawImage(
                image = bitmap,
                srcOffset = IntOffset(srcLeft, srcTop),
                srcSize = IntSize(srcWidth, srcHeight),
                dstOffset = IntOffset(loupe.left, loupe.top),
                dstSize = IntSize(loupe.width, loupe.height),
            )
            // A border, so the loupe reads as a pane laid over the game rather than a torn-out patch of it.
            drawRect(
                color = BORDER_COLOR,
                topLeft = Offset(loupe.left.toFloat(), loupe.top.toFloat()),
                size = Size(loupe.width.toFloat(), loupe.height.toFloat()),
                style = Stroke(width = BORDER_WIDTH_PX),
            )
        }
    }
}

/**
 * The loupe's side as a fraction of the screen's shorter edge.
 *
 * A shade under a third: big enough to read at [DEFAULT_MAGNIFIER_FACTOR] and small enough that a corner
 * placement leaves the centred source region it magnifies untouched, which is the disjointness
 * [MagnifierGeometry] relies on.
 */
private const val LOUPE_FRACTION = 0.32f

/**
 * The fixed magnification for this build (spec §14.3's range is 2×–8×; this sits low in it).
 *
 * 2.5× is enough to make a distant figure or a small readout legible without shrinking the source region
 * so far that the crop is a handful of pixels. A later build turns this into a user setting.
 */
const val DEFAULT_MAGNIFIER_FACTOR = 2.5f

/** White at most of full opacity — visible over a dark game without being a hard line. */
private val BORDER_COLOR = Color.White.copy(alpha = 0.85f)

private const val BORDER_WIDTH_PX = 3f
