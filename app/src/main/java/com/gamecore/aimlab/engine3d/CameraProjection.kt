package com.gamecore.aimlab.engine3d

import kotlin.math.atan
import kotlin.math.tan

/**
 * The FOV maths that make aim feel the same in portrait and landscape (§2 camera), as pure functions the
 * renderer and the tests share.
 *
 * The camera is configured with a **horizontal** field of view. A projection matrix, though, is built
 * from a *vertical* FOV plus an aspect ratio — so a fixed vertical FOV would make the horizontal field
 * balloon in landscape and pinch in portrait, and a flick that was a 30° turn in one orientation would be
 * a different angle in the other. Fixing the horizontal FOV and deriving the vertical from the real
 * aspect keeps the horizontal turn constant across every aspect ratio, which is what a trainer needs.
 *
 * `aspect` is width / height. The relation is `tan(hFov/2) = aspect · tan(vFov/2)`, so
 * `vFov = 2·atan(tan(hFov/2) / aspect)`.
 */
object CameraProjection {

    /** Vertical FOV in degrees for a horizontal FOV and a width/height [aspect]. */
    fun verticalFovDegrees(horizontalFovDegrees: Float, aspect: Float): Float {
        val safeAspect = aspect.takeIf { it.isFinite() && it > 0f } ?: 1f
        val hHalf = Math.toRadians((horizontalFovDegrees / 2f).toDouble())
        val vHalf = atan(tan(hHalf) / safeAspect)
        return Math.toDegrees(2.0 * vHalf).toFloat()
    }

    /** Horizontal FOV in degrees for a vertical FOV and a width/height [aspect] — the inverse, for tests. */
    fun horizontalFovDegrees(verticalFovDegrees: Float, aspect: Float): Float {
        val safeAspect = aspect.takeIf { it.isFinite() && it > 0f } ?: 1f
        val vHalf = Math.toRadians((verticalFovDegrees / 2f).toDouble())
        val hHalf = atan(tan(vHalf) * safeAspect)
        return Math.toDegrees(2.0 * hHalf).toFloat()
    }

    /**
     * Whether a target at [errorDegrees] off the crosshair is inside the visible frustum for a horizontal
     * FOV and [aspect] (§2: no target spawns off-screen after a rotation).
     *
     * A spawn is only accepted if it is within both the horizontal half-FOV and the vertical half-FOV, so
     * the caller can reject a candidate that would sit past the edge of a narrow portrait view. The margin
     * pulls the limit in slightly so a target never spawns hard against the frame edge.
     */
    fun isWithinView(
        yawOffsetDegrees: Float,
        pitchOffsetDegrees: Float,
        horizontalFovDegrees: Float,
        aspect: Float,
        marginDegrees: Float = 2f,
    ): Boolean {
        val hLimit = horizontalFovDegrees / 2f - marginDegrees
        val vLimit = verticalFovDegrees(horizontalFovDegrees, aspect) / 2f - marginDegrees
        return kotlin.math.abs(yawOffsetDegrees) <= hLimit && kotlin.math.abs(pitchOffsetDegrees) <= vLimit
    }
}
