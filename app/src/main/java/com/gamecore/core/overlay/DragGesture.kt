package com.gamecore.core.overlay

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * One touch gesture on a floating window, as an immutable fold.
 *
 * This exists because dragging a `WindowManager` window is not the same problem as dragging a
 * composable, and the difference is a bug that a great many overlay implementations ship:
 *
 * Compose's `detectDragGestures` reports deltas in the *view's* coordinate space. When the view is a
 * window that is being moved to follow the finger, the finger's position inside the view barely changes
 * — so the reported delta collapses towards zero and the window stalls, or, because
 * `updateViewLayout` lands a frame later than the touch, alternates between a real delta and nothing
 * and the window shakes. Neither is fixable by tuning: the coordinate space is wrong.
 *
 * The fix is to track the raw screen position, which does not move when the window does. That is what
 * [began], [movedTo] and [targetX]/[targetY] do, and keeping them here as arithmetic rather than in a
 * touch listener means §31 can assert "a 3-pixel wobble is a tap, a 40-pixel drag is not" without a
 * `MotionEvent`.
 *
 * A gesture also has to decide, at the end, whether it was a *tap*. A floating button is both draggable
 * and clickable, and asking the user to be precise is not an option — everyone moves a few pixels while
 * tapping. [isTap] compares total travel against the platform's own touch slop rather than a number
 * invented here, so the threshold matches every other tappable thing on the device.
 */
data class DragGesture(
    val startRawX: Float = 0f,
    val startRawY: Float = 0f,
    val startWindowX: Int = 0,
    val startWindowY: Int = 0,
    val currentRawX: Float = 0f,
    val currentRawY: Float = 0f,
    /** True between [began] and [ended]. */
    val isActive: Boolean = false,
) {

    /** How far the finger has moved from where it went down, in pixels. */
    val travelPx: Float get() = hypot(currentRawX - startRawX, currentRawY - startRawY)

    /** Where the window should be right now, before clamping or snapping. */
    val targetX: Int get() = startWindowX + (currentRawX - startRawX).roundToInt()

    val targetY: Int get() = startWindowY + (currentRawY - startRawY).roundToInt()

    /**
     * True if this gesture should be treated as a tap rather than a drag.
     *
     * Total travel, not net displacement: a finger that goes 30 px out and comes back has dragged, and
     * treating it as a tap would open the control panel because the user changed their mind about where
     * to put the button. The distinction is small but it is the difference between a control that feels
     * deliberate and one that fires when you did not mean it to.
     */
    fun isTap(slopPx: Float): Boolean = travelPx <= slopPx

    /** True once the finger has moved far enough that this is unambiguously a drag. */
    fun isDrag(slopPx: Float): Boolean = travelPx > slopPx

    fun began(rawX: Float, rawY: Float, windowX: Int, windowY: Int) = DragGesture(
        startRawX = rawX,
        startRawY = rawY,
        startWindowX = windowX,
        startWindowY = windowY,
        currentRawX = rawX,
        currentRawY = rawY,
        isActive = true,
    )

    /**
     * Folds in a move.
     *
     * Ignored when not [isActive]: a move can arrive after a cancel on some ROMs, and applying it would
     * jump the window by the distance travelled since the gesture ended.
     */
    fun movedTo(rawX: Float, rawY: Float): DragGesture =
        if (!isActive) this else copy(currentRawX = rawX, currentRawY = rawY)

    /** Ends the gesture, keeping the coordinates so [isTap] can still be asked. */
    fun ended(): DragGesture = if (!isActive) this else copy(isActive = false)

    /**
     * True when the gesture has moved further vertically than horizontally.
     *
     * Used by the control panel, which follows the button it was opened from: a panel dragged
     * predominantly downwards should not also drift sideways into the edge it will be snapped to.
     */
    fun isMostlyVertical(): Boolean =
        abs(currentRawY - startRawY) > abs(currentRawX - startRawX)

    companion object {
        val IDLE = DragGesture()
    }
}
