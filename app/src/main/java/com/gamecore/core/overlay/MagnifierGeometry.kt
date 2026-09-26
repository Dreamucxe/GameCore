package com.gamecore.core.overlay

/**
 * A rectangle in screen pixels, as plain `Int`s.
 *
 * Not `android.graphics.Rect`, and not Compose's `IntRect`, for the reason
 * [com.gamecore.core.common.ContrastMath] is not built on `Color`: the whole of the magnifier's geometry —
 * including the constraint that decides whether the feature works at all ([MagnifierGeometry.placeLoupe]) —
 * is arithmetic, and arithmetic belongs in a fast JVM test rather than in an instrumented one. The
 * Android-facing layer converts at its own edge.
 *
 * Half-open, like every other rectangle convention in graphics: [right] and [bottom] are the first
 * pixel *outside* the rectangle. That is what makes two rectangles sharing an edge not overlap, which
 * is load-bearing in [intersects] — a loupe whose left edge is exactly the source region's right edge
 * is safe, and a convention that called that an overlap would refuse placements that are fine.
 */
data class PixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)

    /** True when the rectangle encloses no pixels. An empty rectangle overlaps nothing. */
    val isEmpty: Boolean get() = right <= left || bottom <= top

    /**
     * True when the two rectangles share at least one pixel.
     *
     * Strict comparisons on all four sides, so touching edges are not an overlap (see the class KDoc).
     * An empty rectangle is never an overlap: a zero-area source region cannot be re-captured, and
     * treating it as a collision would make the degenerate case look like the dangerous one.
     */
    fun intersects(other: PixelRect): Boolean =
        !isEmpty && !other.isEmpty &&
            left < other.right && other.left < right &&
            top < other.bottom && other.top < bottom

    /** True when [other] lies entirely inside this rectangle. Used to check a rect is on screen. */
    fun contains(other: PixelRect): Boolean =
        !other.isEmpty &&
            other.left >= left && other.top >= top &&
            other.right <= right && other.bottom <= bottom

    /**
     * Area in pixels, as a `Long`.
     *
     * `Long` because the product overflows a signed 32-bit int at around 46000 square, and while no phone
     * is that wide, an unclamped detection box scaled up from a downscaled inference frame can be — and an
     * overflow here would come out negative and make a false positive look like the smallest box in the
     * frame rather than the largest.
     */
    val area: Long get() = width.toLong() * height.toLong()

    /**
     * The overlapping region of the two rectangles, or an empty rectangle when they do not overlap.
     *
     * Sibling of [intersects], and the reason both exist: the magnifier only ever needs the yes/no, while
     * the figure filter needs the area of the overlap to work out whether two detection boxes are the same
     * person seen twice.
     */
    fun intersect(other: PixelRect): PixelRect {
        if (!intersects(other)) return PixelRect(0, 0, 0, 0)
        return PixelRect(
            left = maxOf(left, other.left),
            top = maxOf(top, other.top),
            right = minOf(right, other.right),
            bottom = minOf(bottom, other.bottom),
        )
    }

    /** This rectangle confined to [bounds], or an empty rectangle when it lies wholly outside. */
    fun clampTo(bounds: PixelRect): PixelRect = intersect(bounds)

    companion object {
        /** A rectangle from a top-left corner and a size, which is how a window is positioned. */
        fun of(left: Int, top: Int, width: Int, height: Int) =
            PixelRect(left, top, left + width.coerceAtLeast(0), top + height.coerceAtLeast(0))
    }
}

/** The corner a loupe window is parked in. Fixed order, because placement must be deterministic. */
enum class LoupeCorner(val label: String) {
    TOP_LEFT("Top left"),
    TOP_RIGHT("Top right"),
    BOTTOM_LEFT("Bottom left"),
    BOTTOM_RIGHT("Bottom right"),
    ;

    companion object {
        /** Parse a stored corner, or null for one this build does not know — the `enum.of` discipline. */
        fun of(name: String?): LoupeCorner? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Why a loupe could not be placed anywhere safe.
 *
 * Structured rather than a message string, so the UI can decide what to say and a test can assert which
 * case fired. Every one of these is a real configuration a user can ask for, and none of them is an
 * error in the sense of a bug — the honest response is to say which one happened and offer the nearest
 * configuration that works, which is what [MagnifierGeometry.smallestSafeFactor] is for.
 */
enum class PlacementFailure {
    /** The loupe window is larger than the screen it has to sit on. */
    LOUPE_LARGER_THAN_SCREEN,

    /**
     * Every candidate corner overlaps the region being magnified.
     *
     * The interesting case, and the counter-intuitive one: it gets *more* likely as magnification goes
     * *down*, because a lower factor magnifies a larger source region. See [MagnifierGeometry].
     */
    SOURCE_COVERS_EVERY_CORNER,

    /** The screen size handed in was degenerate (zero or negative in a dimension). */
    SCREEN_DEGENERATE,
}

/**
 * Where the loupe ended up, or why it could not go anywhere.
 *
 * A sealed result rather than a nullable rect, because "there is nowhere safe to put this" is a state
 * the UI has to render — silently falling back to an overlapping placement is exactly the bug this
 * whole file exists to prevent.
 */
sealed interface LoupePlacement {
    data class Safe(val bounds: PixelRect, val corner: LoupeCorner) : LoupePlacement
    data class Impossible(val reason: PlacementFailure) : LoupePlacement
}

/**
 * The magnified region that will be shown, and whether it is the region that was asked for.
 *
 * [effectiveFactor] exists because of the one case where the request cannot be honoured literally: a
 * source region wider or taller than the screen has to be shrunk to fit, and shrinking it raises the
 * magnification above what the user selected. Reporting the factor actually achieved — rather than
 * echoing the requested one — is the no-fake-data rule applied to geometry: the readout must not say
 * "4×" while the pixels on screen are at 5.2×.
 */
data class SourceRegion(
    val rect: PixelRect,
    val requestedFactor: Float,
    val effectiveFactor: Float,
) {
    /** True when the region had to be shrunk to fit the screen, so the factor is not the requested one. */
    val wasConstrained: Boolean get() = effectiveFactor > requestedFactor + FACTOR_EPSILON

    private companion object {
        /** Float slop, so a rounding wobble in the last bit is not reported to the user as a constraint. */
        const val FACTOR_EPSILON = 0.001f
    }
}

/**
 * The magnifier's geometry, and the constraint that keeps it from eating its own output.
 *
 * ## The problem this file exists to solve
 *
 * `MediaProjection` captures everything composited onto the display, and that includes GameCore's own
 * overlay windows. The loupe draws magnified screen content *onto the screen*, so unless something
 * stops it, the next captured frame contains the loupe, the loupe magnifies itself, and the result is
 * an infinite mirror that gets worse every frame. This is not a rare edge case — it is what happens on
 * the very first run if nothing prevents it (spec §14.4).
 *
 * The fix chosen here is the first of the three the spec lists, because it is the only one with no cost
 * to the user: **constrain the geometry so the region being magnified can never overlap the window
 * doing the magnifying.** Cropping the loupe out of each captured frame costs a per-frame copy, and
 * blanking the loupe for the captured frame costs visible flicker; a placement rule costs nothing at
 * all once it is right, which is why it is worth getting right here rather than at the pixel layer.
 *
 * ## The non-obvious part
 *
 * Feedback risk is *worst at low magnification*. The source region is the loupe's own size divided by
 * the factor, so 2× magnifies a region half the loupe's width and 8× magnifies one an eighth of it. A
 * user dragging the magnification slider *down* is walking toward the failure, not away from it, which
 * is the opposite of the intuition that "more zoom is more demanding". [smallestSafeFactor] exists so
 * the UI can clamp the slider at the point where placement becomes impossible instead of letting the
 * user select a configuration that cannot be rendered honestly.
 *
 * Everything here is pure: no `android.*`, no Compose, no clock, no state. The Android layer owns the
 * `WindowManager` params and the capture plumbing; this owns the arithmetic that decides what is legal.
 */
object MagnifierGeometry {

    /**
     * Magnification bounds (spec §14.3).
     *
     * Below 2× the effect is not worth an overlay — the system-wide display-size override already in
     * the app does a better job of making everything slightly larger, at no capture cost. Above 8× a
     * phone-sized source region is a few dozen pixels and the result is unreadable mush, so a higher
     * ceiling would only be offering the user a worse picture.
     */
    const val MIN_FACTOR = 2f
    const val MAX_FACTOR = 8f

    /** Gap kept between a parked loupe and the screen edge, so it is not flush against the bezel. */
    const val DEFAULT_MARGIN = 16

    /** Step used when scanning for [smallestSafeFactor]. Fine enough to be useful on a slider. */
    const val FACTOR_SCAN_STEP = 0.1f

    /**
     * The scan expressed in whole steps above [MIN_FACTOR], so the loop counter is an `Int`.
     *
     * Not cosmetic. Adding 0.1f sixty times does not land on 8.0 — it lands a few bits off, and the
     * returned factor would be a value like 4.3000007 that a readout renders as "4.3000007x" and a test
     * cannot assert on without a delta. Counting steps and multiplying once keeps every value the scan
     * can return a clean tenth.
     *
     * Rounded rather than truncated for the same family of reasons: `0.1f` is a shade larger than a
     * tenth, so `6f / 0.1f` is 59.999999 and `toInt()` would silently drop the top step and cap the scan
     * at 7.9x — a ceiling one step below the one this file advertises.
     */
    private val SCAN_STEPS: Int = kotlin.math.round((MAX_FACTOR - MIN_FACTOR) / FACTOR_SCAN_STEP).toInt()

    /**
     * Slop used when turning a requested factor into a step index.
     *
     * A request that is already exactly on a step must resolve to *that* step, not the one above it.
     * Without the slop, a float representation of 3.0x that is a hair over three would `ceil` to the
     * next step and quietly raise a factor that needed no raising, which would then trip
     * [MagnifierResolution.Ready.raisedFromRequested] and have the UI apologise for nothing.
     */
    private const val STEP_EPSILON = 0.001f

    /** The factor at whole-step [step] above [MIN_FACTOR], rounded to a tenth. */
    private fun factorAtStep(step: Int): Float =
        kotlin.math.round((MIN_FACTOR + step * FACTOR_SCAN_STEP) * 10f) / 10f

    /** The first whole step at or above [factor]. [factor] must already be clamped. */
    private fun stepFor(factor: Float): Int =
        kotlin.math.ceil((factor - MIN_FACTOR) / FACTOR_SCAN_STEP - STEP_EPSILON)
            .toInt()
            .coerceIn(0, SCAN_STEPS)

    /** Clamp a requested factor into the offered range. Called on read as well as on write. */
    fun clampFactor(factor: Float): Float = when {
        factor.isNaN() -> MIN_FACTOR
        else -> factor.coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    /**
     * Quantise a requested factor to the nearest scan step at or above it.
     *
     * Rounds *up*, never down, so a stored or dragged value is never answered with less magnification
     * than was asked for — for a user who turned this on because they cannot read the screen, losing a
     * step is the wrong direction to err in. This is also the baseline
     * [MagnifierResolution.Ready.raisedFromRequested] is measured against: snapping 3.24x to 3.3x is
     * slider resolution, not the feedback-loop constraint pushing the factor up, and the UI should not
     * explain the first as though it were the second.
     */
    fun snapFactor(factor: Float): Float = factorAtStep(stepFor(clampFactor(factor)))

    /**
     * The region of the screen a loupe of [loupeWidth] x [loupeHeight] shows at [factor], centred on
     * the anchor the user put down.
     *
     * Clamping is done by **shifting** the region back inside the screen, not by shrinking it. Shrinking
     * would quietly change the magnification of an anchor dragged into a corner, so the picture would
     * zoom as it approached the edge — a factor that moves on its own is worse than a region that stops
     * sliding. Shrinking happens only when the region genuinely cannot fit ([SourceRegion.wasConstrained]),
     * and then the real factor is reported rather than the requested one.
     */
    fun sourceRegion(
        anchorX: Int,
        anchorY: Int,
        loupeWidth: Int,
        loupeHeight: Int,
        factor: Float,
        screen: PixelRect,
    ): SourceRegion {
        val requested = clampFactor(factor)
        val wantWidth = (loupeWidth / requested).toInt().coerceAtLeast(1)
        val wantHeight = (loupeHeight / requested).toInt().coerceAtLeast(1)

        // Shrink only if the screen cannot hold the region at all.
        val width = wantWidth.coerceAtMost(screen.width.coerceAtLeast(1))
        val height = wantHeight.coerceAtMost(screen.height.coerceAtLeast(1))

        // Centre on the anchor, then slide wholly inside the screen.
        val left = (anchorX - width / 2).coerceIn(screen.left, (screen.right - width).coerceAtLeast(screen.left))
        val top = (anchorY - height / 2).coerceIn(screen.top, (screen.bottom - height).coerceAtLeast(screen.top))

        // The factor actually achieved is the loupe size over the region size that fitted. An axis counts
        // as squeezed only when the screen clamped it below the want-size the requested factor asked for; a
        // want-size that merely floored down by a pixel is not a real constraint. Reported from whichever
        // axis was squeezed hardest, so the figure is never flattering (see regionTooLargeForTheScreen).
        val effectiveX = if (width in 1 until wantWidth) loupeWidth.toFloat() / width else requested
        val effectiveY = if (height in 1 until wantHeight) loupeHeight.toFloat() / height else requested
        val effective = maxOf(effectiveX, effectiveY)

        return SourceRegion(
            rect = PixelRect.of(left, top, width, height),
            requestedFactor = requested,
            effectiveFactor = effective,
        )
    }

    /**
     * Park the loupe in a corner that does not overlap [source].
     *
     * [preferred] is tried first so a user's remembered corner is honoured whenever it is legal; the
     * remaining corners are then tried in [LoupeCorner] declaration order. Fixed order rather than
     * "nearest free corner" on purpose: a loupe that hops to a different corner as the anchor moves is
     * disorienting, and for a user who is magnifying the screen because they cannot see it well, a
     * window that relocates itself is worse than one parked somewhere slightly inconvenient.
     */
    fun placeLoupe(
        source: PixelRect,
        loupeWidth: Int,
        loupeHeight: Int,
        screen: PixelRect,
        preferred: LoupeCorner? = null,
        margin: Int = DEFAULT_MARGIN,
    ): LoupePlacement {
        if (screen.isEmpty) return LoupePlacement.Impossible(PlacementFailure.SCREEN_DEGENERATE)
        if (loupeWidth <= 0 || loupeHeight <= 0) {
            return LoupePlacement.Impossible(PlacementFailure.LOUPE_LARGER_THAN_SCREEN)
        }
        if (loupeWidth > screen.width || loupeHeight > screen.height) {
            return LoupePlacement.Impossible(PlacementFailure.LOUPE_LARGER_THAN_SCREEN)
        }

        val order = buildList {
            preferred?.let { add(it) }
            addAll(LoupeCorner.entries.filter { it != preferred })
        }
        for (corner in order) {
            val bounds = cornerBounds(corner, loupeWidth, loupeHeight, screen, margin)
            if (!bounds.intersects(source)) return LoupePlacement.Safe(bounds, corner)
        }
        return LoupePlacement.Impossible(PlacementFailure.SOURCE_COVERS_EVERY_CORNER)
    }

    /**
     * The smallest factor at or above [from] at which this anchor and loupe size can be placed safely,
     * or null when no factor up to [MAX_FACTOR] works.
     *
     * A scan rather than a closed-form solve. The predicate chains [sourceRegion] into [placeLoupe] and
     * both of those clamp and shift, so an analytic inverse would have to reproduce that behaviour and
     * would then be a second copy of it to keep in step. A scan in 0.1x steps over a range of six is at
     * most sixty cheap integer computations, runs on a slider drag without being noticed, and cannot
     * disagree with the functions it calls.
     *
     * Scanning *upward* is correct because the source region shrinks monotonically as the factor grows:
     * if a placement is unsafe at some factor it may become safe at a higher one, never the reverse.
     */
    fun smallestSafeFactor(
        anchorX: Int,
        anchorY: Int,
        loupeWidth: Int,
        loupeHeight: Int,
        screen: PixelRect,
        preferred: LoupeCorner? = null,
        margin: Int = DEFAULT_MARGIN,
        from: Float = MIN_FACTOR,
    ): Float? {
        // Start at the first whole step at or above the requested factor, so a request of 3.24x is not
        // answered with 3.2x — the answer must never be *below* what was asked for.
        val firstStep = stepFor(clampFactor(from))
        for (step in firstStep..SCAN_STEPS) {
            val factor = factorAtStep(step)
            val region = sourceRegion(anchorX, anchorY, loupeWidth, loupeHeight, factor, screen)
            val placement = placeLoupe(region.rect, loupeWidth, loupeHeight, screen, preferred, margin)
            if (placement is LoupePlacement.Safe) return factor
        }
        return null
    }

    /**
     * Resolve a whole requested configuration in one call: the region, a safe placement, and the factor
     * that was actually used.
     *
     * The single entry point the Android layer should call, so the "compute region, place window, and if
     * that failed raise the factor until it works" sequence lives here once instead of being re-derived
     * by every caller. When even [MAX_FACTOR] cannot be placed, the failure is returned rather than a
     * best-effort overlapping rectangle — an overlapping loupe is the infinite mirror, and shipping one
     * would be worse than telling the user this anchor will not work.
     */
    fun resolve(
        anchorX: Int,
        anchorY: Int,
        loupeWidth: Int,
        loupeHeight: Int,
        requestedFactor: Float,
        screen: PixelRect,
        preferred: LoupeCorner? = null,
        margin: Int = DEFAULT_MARGIN,
    ): MagnifierResolution {
        val usable = smallestSafeFactor(
            anchorX = anchorX,
            anchorY = anchorY,
            loupeWidth = loupeWidth,
            loupeHeight = loupeHeight,
            screen = screen,
            preferred = preferred,
            margin = margin,
            from = requestedFactor,
        ) ?: run {
            val region = sourceRegion(anchorX, anchorY, loupeWidth, loupeHeight, requestedFactor, screen)
            val failure = placeLoupe(region.rect, loupeWidth, loupeHeight, screen, preferred, margin)
            return MagnifierResolution.Failed(
                reason = (failure as? LoupePlacement.Impossible)?.reason
                    ?: PlacementFailure.SOURCE_COVERS_EVERY_CORNER,
            )
        }

        val region = sourceRegion(anchorX, anchorY, loupeWidth, loupeHeight, usable, screen)
        val placement = placeLoupe(region.rect, loupeWidth, loupeHeight, screen, preferred, margin)
        return when (placement) {
            is LoupePlacement.Safe -> MagnifierResolution.Ready(
                source = region,
                loupe = placement.bounds,
                corner = placement.corner,
                raisedFromRequested = usable > snapFactor(requestedFactor) + STEP_EPSILON,
            )
            is LoupePlacement.Impossible -> MagnifierResolution.Failed(placement.reason)
        }
    }

    /** The bounds a loupe of this size occupies when parked in [corner], inset by [margin]. */
    private fun cornerBounds(
        corner: LoupeCorner,
        width: Int,
        height: Int,
        screen: PixelRect,
        margin: Int,
    ): PixelRect {
        val left = when (corner) {
            LoupeCorner.TOP_LEFT, LoupeCorner.BOTTOM_LEFT -> screen.left + margin
            LoupeCorner.TOP_RIGHT, LoupeCorner.BOTTOM_RIGHT -> screen.right - width - margin
        }
        val top = when (corner) {
            LoupeCorner.TOP_LEFT, LoupeCorner.TOP_RIGHT -> screen.top + margin
            LoupeCorner.BOTTOM_LEFT, LoupeCorner.BOTTOM_RIGHT -> screen.bottom - height - margin
        }
        // A margin wider than the slack left over would push the window off screen; clamp rather than
        // honour it, so a large margin degrades to "flush against the edge" instead of "off the display".
        val clampedLeft = left.coerceIn(screen.left, (screen.right - width).coerceAtLeast(screen.left))
        val clampedTop = top.coerceIn(screen.top, (screen.bottom - height).coerceAtLeast(screen.top))
        return PixelRect.of(clampedLeft, clampedTop, width, height)
    }
}

/**
 * The outcome of [MagnifierGeometry.resolve].
 *
 * [Ready.raisedFromRequested] is the honesty flag: the magnifier is working, but at a higher factor than
 * the user selected, because the one they asked for could not be placed without the loupe capturing
 * itself. The UI is expected to show the factor in use, and to say why it differs, rather than leaving
 * the slider reading a number the pixels do not match.
 */
sealed interface MagnifierResolution {
    data class Ready(
        val source: SourceRegion,
        val loupe: PixelRect,
        val corner: LoupeCorner,
        val raisedFromRequested: Boolean,
    ) : MagnifierResolution

    data class Failed(val reason: PlacementFailure) : MagnifierResolution
}
