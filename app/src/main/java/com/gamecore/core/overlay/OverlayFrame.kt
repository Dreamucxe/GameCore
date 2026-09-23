package com.gamecore.core.overlay

/** Which side of the screen a floating window settled against. */
enum class ScreenEdge {
    LEFT,
    RIGHT,

    /** Not snapped: the window is wherever the user left it. */
    FLOATING,
}

/**
 * The four corners spec §2 lets the user pin the button to, plus the arithmetic that decides which one a
 * free drag lands nearest.
 *
 * A corner is a *choice the user makes* (Top-Left / Top-Right / Bottom-Left / Bottom-Right), which is why
 * it is a first-class named thing rather than a pair of booleans at the call site. The two accessors below
 * are how the placement maths asks the only two questions it has — is this corner on the left, is it on the
 * top — without a `when` per site.
 */
enum class Corner(val isLeft: Boolean, val isTop: Boolean, val label: String) {
    TOP_LEFT(isLeft = true, isTop = true, label = "Top left"),
    TOP_RIGHT(isLeft = false, isTop = true, label = "Top right"),
    BOTTOM_LEFT(isLeft = true, isTop = false, label = "Bottom left"),
    BOTTOM_RIGHT(isLeft = false, isTop = false, label = "Bottom right"),
    ;

    /**
     * The corner as a stored [PositionFraction]: 0f on an axis it hugs the top/left of, 1f the bottom/right.
     *
     * A corner is the same fraction in any orientation — "top-right" is (1, 0) of the usable box whether the
     * screen is tall or wide — which is exactly why pinning one writes the *same* pair to both orientations
     * and why it re-resolves sensibly after a rotation. [OverlayFrame.fromFraction] turns this back into the
     * pixels for whatever screen the button is on, with the safe-area margin applied there, not here.
     */
    val fraction: PositionFraction
        get() = PositionFraction(
            xFraction = if (isLeft) 0f else 1f,
            yFraction = if (isTop) 0f else 1f,
        )

    companion object {
        /** The corner a point on the given side/half resolves to. */
        fun of(isLeft: Boolean, isTop: Boolean): Corner = when {
            isLeft && isTop -> TOP_LEFT
            !isLeft && isTop -> TOP_RIGHT
            isLeft -> BOTTOM_LEFT
            else -> BOTTOM_RIGHT
        }

        /**
         * The corner a stored fraction *exactly* names, or null when it is a free-dragged spot between them.
         *
         * Used to light up the right corner chip in settings: the four corners are the only fractions with
         * both components at 0 or 1, so anything else — a drag to two-thirds down the right edge — belongs
         * to no chip and lights none, rather than being rounded to the nearest corner behind the user's back.
         */
        fun fromFraction(fraction: PositionFraction): Corner? = entries.firstOrNull {
            it.fraction.xFraction == fraction.xFraction && it.fraction.yFraction == fraction.yFraction
        }
    }
}

/**
 * Where a floating window should actually sit, and which edge (if any) it ended up on.
 *
 * [corner] is null for the horizontal-only edge snap the free drag uses and for a plain clamp; it is set
 * only when a window was placed at one of the four named corners (spec §2), so a caller that stores the
 * corner can tell "pinned to bottom-right" apart from "snapped to the right at whatever height".
 */
data class OverlayPlacement(
    val x: Int,
    val y: Int,
    val edge: ScreenEdge,
    val corner: Corner? = null,
)

/**
 * A window position as a fraction of the usable screen, stored separately per orientation (spec §2).
 *
 * Both components are 0f..1f, measured at the window's centre against the box inside the safe-area insets:
 * 0f is the top/left safe edge, 1f the bottom/right one. Storing the fraction rather than a pixel is what
 * lets a position set in portrait re-resolve onto a landscape screen instead of landing in the void — the
 * pixel is only ever computed for the screen the window is about to be placed on.
 */
data class PositionFraction(
    val xFraction: Float,
    val yFraction: Float,
) {
    companion object {
        /** The default anchor: three-quarters of the way down the right edge, where a right thumb rests. */
        val DEFAULT = PositionFraction(1f, 0.75f)
    }
}

/**
 * The geometry of placing a floating window on a screen, as a pure value.
 *
 * All of §7's drag behaviour that can be got wrong lives here rather than in a touch listener, for one
 * reason: §31 asks for the edge-snap rule to be tested, and a `MotionEvent` sequence against a real
 * `WindowManager` is not something a JVM test can produce. Everything here is arithmetic on four
 * integers, so "a button dragged off the right edge of a 1080-wide screen comes back to 1080 minus its
 * own width" is a one-line test.
 *
 * Three rules, each of which is a bug that overlay implementations routinely ship:
 *
 *  1. **A window is clamped by its own size, not by the screen.** Clamping x to `screenWidth` puts a
 *     48 dp button 47 dp off-screen with one pixel of itself showing and no way to get it back, because
 *     the part left on screen is too small to grab. The limit is `screenWidth - windowWidth`.
 *
 *  2. **Snapping picks the nearer edge by the window's centre**, not by its left coordinate. Judging by
 *     the left edge makes a button dragged to the right side snap left whenever it is wider than the
 *     gap it left behind, which reads as the app fighting the user.
 *
 *  3. **A window bigger than its screen is pinned, not clamped into a negative range.** Rotation and
 *     split-screen both produce a screen narrower than a wide pill; `coerceIn(0, negative)` throws, so
 *     the maximum is floored at zero and the window sits at the origin, fully visible from one corner.
 */
data class OverlayFrame(
    val screenWidth: Int,
    val screenHeight: Int,
    val windowWidth: Int,
    val windowHeight: Int,
    /**
     * How far a snapped window stops short of the physical edge.
     *
     * Zero is a legitimate choice for a pill; a button wants a few pixels, because a control sitting
     * flush against the edge on a device with curved glass is partly under the curve and swallows the
     * back gesture.
     */
    val marginPx: Int = 0,
    /**
     * The unusable frame around the screen: display cutouts, the status/navigation bars, and a margin
     * from the system back-gesture zones (spec §2). Zero on every side by default, so a frame built the
     * old way — button and pill drag today — behaves exactly as it did before this field existed.
     *
     * These are insets *into* the screen: [insetLeft] pixels along the left are spoken for, and so on. A
     * clamp or a corner placement keeps the window inside `insetLeft..(screenWidth - insetRight - windowWidth)`
     * rather than `0..(screenWidth - windowWidth)`, which is what stops a pinned button from sitting under
     * the notch or inside the strip the OS watches for the back swipe.
     */
    val insetLeft: Int = 0,
    val insetTop: Int = 0,
    val insetRight: Int = 0,
    val insetBottom: Int = 0,
) {

    /** True once the screen has been measured. Placement before that is a guess and is left alone. */
    val isMeasured: Boolean get() = screenWidth > 0 && screenHeight > 0

    /**
     * Which of the two stored fraction pairs this screen uses (spec §2 keeps one per orientation).
     *
     * Taller-than-wide is portrait; a square ties to portrait, which only decides which slot a fraction is
     * filed under, never where the button lands. Measured against the raw screen, not the usable box, so a
     * tall inset cannot flip a landscape display into the portrait slot.
     */
    val isPortrait: Boolean get() = screenHeight >= screenWidth

    /** The leftmost x at which the window clears the left safe-area inset. */
    val minX: Int get() = insetLeft

    /** The topmost y at which the window clears the top safe-area inset. */
    val minY: Int get() = insetTop

    /**
     * The rightmost x at which the window is still fully on screen and clear of the right inset.
     *
     * Floored at [minX] rather than 0 so a window wider than the safe area is pinned at the left inset
     * instead of being pulled back across it — the same "pin, don't clamp into a negative range" rule the
     * class KDoc states, now measured against the usable width rather than the raw screen.
     */
    val maxX: Int get() = (screenWidth - insetRight - windowWidth).coerceAtLeast(minX)

    /** The lowest y at which the window is still fully on screen and clear of the bottom inset. */
    val maxY: Int get() = (screenHeight - insetBottom - windowHeight).coerceAtLeast(minY)

    /**
     * Moves a position inside the screen without changing which side it is on.
     *
     * Used for every drag frame, and after a rotation for windows that do not snap.
     */
    fun clamp(x: Int, y: Int): OverlayPlacement {
        if (!isMeasured) return OverlayPlacement(x.coerceAtLeast(0), y.coerceAtLeast(0), ScreenEdge.FLOATING)
        return OverlayPlacement(
            x = x.coerceIn(minX, maxX),
            y = y.coerceIn(minY, maxY),
            edge = ScreenEdge.FLOATING,
        )
    }

    /**
     * Clamps, then pulls the window to whichever vertical edge its centre is nearer.
     *
     * Vertical only. Snapping to the top or bottom as well would mean a button dragged to the middle of
     * the screen flies to a corner, and the four-way version of this is the behaviour people disable
     * the moment they find the setting.
     */
    fun snap(x: Int, y: Int): OverlayPlacement {
        val clamped = clamp(x, y)
        if (!isMeasured) return clamped
        val centreX = clamped.x + windowWidth / 2
        val usableCentre = (minX + maxX + windowWidth) / 2
        return if (centreX <= usableCentre) {
            OverlayPlacement((minX + marginPx).coerceIn(minX, maxX), clamped.y, ScreenEdge.LEFT)
        } else {
            OverlayPlacement((maxX - marginPx).coerceIn(minX, maxX), clamped.y, ScreenEdge.RIGHT)
        }
    }

    /**
     * Pins the window to one of the four named corners (spec §2), inside the safe area.
     *
     * Unlike [snap] this moves the window vertically as well, because a corner is a corner — the user
     * asked for "bottom-right", not "the right edge at whatever height I happened to drag to". The margin
     * is applied on whichever two sides the corner touches, and every coordinate is held inside
     * `minX..maxX` / `minY..maxY`, so the notch and the gesture strips are cleared the same way a clamp
     * clears them.
     */
    fun placeAtCorner(corner: Corner): OverlayPlacement {
        if (!isMeasured) return OverlayPlacement(minX, minY, ScreenEdge.FLOATING, corner)
        val x = if (corner.isLeft) (minX + marginPx) else (maxX - marginPx)
        val y = if (corner.isTop) (minY + marginPx) else (maxY - marginPx)
        return OverlayPlacement(
            x = x.coerceIn(minX, maxX),
            y = y.coerceIn(minY, maxY),
            edge = if (corner.isLeft) ScreenEdge.LEFT else ScreenEdge.RIGHT,
            corner = corner,
        )
    }

    /** The corner a free-dragged position is nearest, by the window's centre against each usable midpoint. */
    fun nearestCorner(x: Int, y: Int): Corner {
        val clamped = clamp(x, y)
        val centreX = clamped.x + windowWidth / 2
        val centreY = clamped.y + windowHeight / 2
        val usableCentreX = (minX + maxX + windowWidth) / 2
        val usableCentreY = (minY + maxY + windowHeight) / 2
        return Corner.of(isLeft = centreX <= usableCentreX, isTop = centreY <= usableCentreY)
    }

    /** [snap] or [clamp], by the user's setting. The one call sites use. */
    fun place(x: Int, y: Int, snapToEdge: Boolean): OverlayPlacement =
        if (snapToEdge) snap(x, y) else clamp(x, y)

    /**
     * The window's stored position as a fraction of the *usable* screen, measured at its centre.
     *
     * Spec §2 stores the button's position as fractions — separately per orientation — rather than as
     * pixels, so a position set in portrait re-resolves sensibly in landscape instead of landing off the
     * new screen. The fraction is of the usable box (inside the insets), so 0f is the top/left safe edge
     * and 1f is the bottom/right one, and it is taken at the centre so a small and a large button asked to
     * sit "three-quarters down" agree on where that is.
     */
    fun fractionOf(x: Int, y: Int): PositionFraction {
        if (!isMeasured) return PositionFraction(0f, 0f)
        val usableW = (maxX - minX).coerceAtLeast(1)
        val usableH = (maxY - minY).coerceAtLeast(1)
        val clamped = clamp(x, y)
        return PositionFraction(
            xFraction = ((clamped.x - minX).toFloat() / usableW).coerceIn(0f, 1f),
            yFraction = ((clamped.y - minY).toFloat() / usableH).coerceIn(0f, 1f),
        )
    }

    /**
     * The pixel placement a stored [PositionFraction] resolves to on *this* screen, clamped to the safe
     * area. The inverse of [fractionOf]; the round trip is exact to within integer rounding, which a test
     * pins. This is what re-resolves a per-orientation fraction after a rotation without a pixel ever
     * being carried across the size change.
     */
    fun fromFraction(fraction: PositionFraction, snapToEdge: Boolean): OverlayPlacement {
        if (!isMeasured) return OverlayPlacement(minX, minY, ScreenEdge.FLOATING)
        val x = minX + (fraction.xFraction * (maxX - minX)).toInt()
        val y = minY + (fraction.yFraction * (maxY - minY)).toInt()
        return place(x, y, snapToEdge)
    }

    /**
     * Re-places a window for a screen that changed size, keeping it proportionally where it was.
     *
     * Rotation, folding and entering split-screen all land here. The horizontal position is
     * re-derived from the fraction of the *old* screen the window sat at rather than kept in pixels,
     * because a pill at x = 900 on a 1080-wide screen is at the right edge and on a 2400-wide one it is
     * in the middle of nowhere. When snapping is on the fraction only decides which edge wins, which is
     * why a landscape button stays on the side it was on in portrait.
     */
    fun rescaleFrom(previous: OverlayFrame, x: Int, y: Int, snapToEdge: Boolean): OverlayPlacement {
        if (!previous.isMeasured || !isMeasured) return place(x, y, snapToEdge)
        val xFraction = (x + previous.windowWidth / 2f) / previous.screenWidth
        val yFraction = (y + previous.windowHeight / 2f) / previous.screenHeight
        val scaledX = (xFraction * screenWidth - windowWidth / 2f).toInt()
        val scaledY = (yFraction * screenHeight - windowHeight / 2f).toInt()
        return place(scaledX, scaledY, snapToEdge)
    }

    companion object {
        /** An unmeasured frame, so a window created before its first layout has something to hold. */
        val UNMEASURED = OverlayFrame(0, 0, 0, 0)
    }
}
