package com.gamecore.core.overlay

/** Which side of the screen a floating window settled against. */
enum class ScreenEdge {
    LEFT,
    RIGHT,

    /** Not snapped: the window is wherever the user left it. */
    FLOATING,
}

/** Where a floating window should actually sit, and which edge (if any) it ended up on. */
data class OverlayPlacement(
    val x: Int,
    val y: Int,
    val edge: ScreenEdge,
)

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
) {

    /** True once the screen has been measured. Placement before that is a guess and is left alone. */
    val isMeasured: Boolean get() = screenWidth > 0 && screenHeight > 0

    /** The rightmost x at which the window is still fully on screen. Never negative. */
    val maxX: Int get() = (screenWidth - windowWidth).coerceAtLeast(0)

    /** The lowest y at which the window is still fully on screen. Never negative. */
    val maxY: Int get() = (screenHeight - windowHeight).coerceAtLeast(0)

    /**
     * Moves a position inside the screen without changing which side it is on.
     *
     * Used for every drag frame, and after a rotation for windows that do not snap.
     */
    fun clamp(x: Int, y: Int): OverlayPlacement {
        if (!isMeasured) return OverlayPlacement(x.coerceAtLeast(0), y.coerceAtLeast(0), ScreenEdge.FLOATING)
        return OverlayPlacement(
            x = x.coerceIn(0, maxX),
            y = y.coerceIn(0, maxY),
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
        return if (centreX <= screenWidth / 2) {
            OverlayPlacement(marginPx.coerceAtMost(maxX), clamped.y, ScreenEdge.LEFT)
        } else {
            OverlayPlacement((maxX - marginPx).coerceAtLeast(0), clamped.y, ScreenEdge.RIGHT)
        }
    }

    /** [snap] or [clamp], by the user's setting. The one call sites use. */
    fun place(x: Int, y: Int, snapToEdge: Boolean): OverlayPlacement =
        if (snapToEdge) snap(x, y) else clamp(x, y)

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
