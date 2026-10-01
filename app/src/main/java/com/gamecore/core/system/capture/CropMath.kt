package com.gamecore.core.system.capture

import kotlin.math.roundToInt

/**
 * The one calculation the crop step turns on, kept android-free so it can be tested on the JVM.
 *
 * A crop is drawn in *preview* coordinates — the pixels of the on-screen image the user dragged a box over —
 * but it must be applied in *bitmap* coordinates, and the captured frame is almost never the same size as the
 * box it is shown in. So the box has to be rescaled per axis and clamped to the frame before a single pixel is
 * read. That mapping is exactly what [com.gamecore.core.overlay.ScoutOverlay] does inline to place its zoom
 * source (per-axis scale, `roundToInt`, `coerceIn`); pulled out here it becomes something a unit test can pin
 * down for the scaling-up, scaling-down, clamping and identity cases without an Android device in the loop.
 *
 * Nothing here touches `android.graphics`: [CropSize] and [CropRect] are plain integers, and the caller in the
 * view-model turns a resolved [CropRect] into an `android.graphics.Rect` (or a `Bitmap.createBitmap` call) at
 * the edge where the real bitmap lives.
 */
object CropMath {

    /**
     * Maps [rect], expressed in [preview] pixels, onto [bitmap] pixels.
     *
     * The result is always inside the bitmap: each edge is scaled by its own axis ratio (the preview and the
     * frame can differ in aspect as well as size), rounded to the nearest whole pixel, and clamped to
     * `0..dimension`. The rect is normalised first, so a box dragged right-to-left or bottom-to-top still
     * produces a left ≤ right, top ≤ bottom result rather than a negative width the caller has to guess about.
     *
     * A degenerate input — a non-positive preview or bitmap dimension — has no meaningful mapping, so an empty
     * [CropRect] is returned rather than a divide-by-zero or a fabricated region. When [preview] equals
     * [bitmap] the scale is 1 on both axes and an in-bounds rect maps to itself unchanged.
     */
    fun previewToBitmap(rect: CropRect, preview: CropSize, bitmap: CropSize): CropRect =
        scale(rect, from = preview, to = bitmap)

    /**
     * The inverse of [previewToBitmap]: a rect known in [bitmap] pixels expressed back in [preview] pixels.
     *
     * Useful for drawing a stored or bitmap-space selection back onto the preview (so a re-shown crop lands
     * where it was), and it obeys the same normalise-scale-clamp contract against the preview bounds.
     */
    fun bitmapToPreview(rect: CropRect, bitmap: CropSize, preview: CropSize): CropRect =
        scale(rect, from = bitmap, to = preview)

    private fun scale(rect: CropRect, from: CropSize, to: CropSize): CropRect {
        if (from.width <= 0 || from.height <= 0 || to.width <= 0 || to.height <= 0) {
            return CropRect(0, 0, 0, 0)
        }
        val scaleX = to.width.toFloat() / from.width
        val scaleY = to.height.toFloat() / from.height
        val left = minOf(rect.left, rect.right)
        val right = maxOf(rect.left, rect.right)
        val top = minOf(rect.top, rect.bottom)
        val bottom = maxOf(rect.top, rect.bottom)
        return CropRect(
            left = (left * scaleX).roundToInt().coerceIn(0, to.width),
            top = (top * scaleY).roundToInt().coerceIn(0, to.height),
            right = (right * scaleX).roundToInt().coerceIn(0, to.width),
            bottom = (bottom * scaleY).roundToInt().coerceIn(0, to.height),
        )
    }
}

/** A width and height in whole pixels — a preview surface or a captured frame. Android-free by design. */
data class CropSize(val width: Int, val height: Int)

/**
 * An axis-aligned rectangle in whole pixels, left/top inclusive and right/bottom exclusive.
 *
 * The same type carries both a preview-space selection ([ScreenExtractionState.cropRectPreview]) and the
 * bitmap-space region [CropMath] resolves it to, because they differ only in which coordinate space they are
 * read against — not in shape. [width] and [height] never go negative even if a caller hands in an unnormalised
 * rect, so `Bitmap.createBitmap(bitmap, left, top, width, height)` at the call site is always well-formed.
 */
data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}
