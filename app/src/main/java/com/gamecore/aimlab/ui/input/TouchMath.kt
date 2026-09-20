package com.gamecore.aimlab.ui.input

import com.gamecore.aimlab.engine.ControlWidget
import com.gamecore.aimlab.engine.Vec2

/**
 * Pure, android-free geometry for the training surface, kept apart from the Compose code so it can be
 * unit-tested without a device.
 *
 * Everything here works in two coordinate spaces: pixels (what a `MotionEvent`/pointer reports, relative to
 * a surface of a given width and height) and arena fractions in [0,1] (what the engine speaks). The surface
 * converts at the boundary and the engine never sees a pixel.
 */
object TouchMath {

    /** A pointer at ([px], [py]) on a [width]×[height] surface, as an arena fraction clamped to [0,1]. */
    fun toArena(px: Float, py: Float, width: Int, height: Int): Vec2 {
        if (width <= 0 || height <= 0) return Vec2.CENTER
        return Vec2((px / width).coerceIn(0f, 1f), (py / height).coerceIn(0f, 1f))
    }

    /** A pixel delta turned into a full-scale look delta, where one surface width is ±1.0 of raw input. */
    fun deltaToLook(dpx: Float, dpy: Float, width: Int, height: Int): Vec2 {
        if (width <= 0 || height <= 0) return Vec2(0f, 0f)
        // Scale both axes by the width so horizontal and vertical sensitivity match the physical distance
        // moved rather than the aspect ratio; a tall surface should not make vertical flicks feel faster.
        return Vec2(dpx / width, dpy / width)
    }

    /**
     * Whether an arena point falls inside a control's box.
     *
     * The control's centre and size are fractions, so the test is a simple axis-aligned bounds check. Shape
     * is decorative — a circular control still hit-tests as its bounding box, which matches how the finger
     * actually lands and avoids a corner of a round button being dead.
     */
    fun hitsControl(point: Vec2, control: ControlWidget): Boolean {
        val halfW = control.widthFraction / 2f
        val halfH = control.heightFraction / 2f
        return point.x in (control.xFraction - halfW)..(control.xFraction + halfW) &&
            point.y in (control.yFraction - halfH)..(control.yFraction + halfH)
    }

    /**
     * The control an arena point is on, or null. When boxes overlap, the smaller one wins, so a small
     * button sitting on top of a large stick is still reachable.
     */
    fun controlAt(point: Vec2, controls: List<ControlWidget>): ControlWidget? =
        controls.filter { it.enabled && hitsControl(point, it) }
            .minByOrNull { it.widthFraction * it.heightFraction }
}
