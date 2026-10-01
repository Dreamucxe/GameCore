package com.gamecore.domain.trigger

import com.gamecore.core.model.FractionPoint
import kotlin.math.roundToInt

/**
 * The four display orientations, kept android-free so the trigger maths runs on the JVM.
 *
 * The codebase already has a [com.gamecore.core.system.ScreenRotation], but that one carries
 * `android.view.Surface` constants in its constructor, so merely loading it in a unit test pulls in the
 * Android runtime and the test never starts. This enum exists so [TapCoordinateMapper] can stay a pure
 * value with no platform on its classpath — the integrator reads `Display.getRotation()` at runtime and
 * hands the result in through [TapCoordinateMapper.fromSurfaceRotation]. The names match the surface
 * rotation they stand for: [ROTATION_90] is a quarter turn, and [degrees] is that turn in degrees.
 */
enum class ScreenRotation(val degrees: Int) {
    ROTATION_0(0),
    ROTATION_90(90),
    ROTATION_180(180),
    ROTATION_270(270),
}

/** A pixel coordinate in the display's current (rotated) space, ready to feed `input tap x y`. */
data class PixelPoint(val x: Int, val y: Int)

/**
 * Keeps a stored trigger point (feature 4, Volume Button Point Trigger) landing where the user put it
 * across screen rotation and resolution changes.
 *
 * The point is stored once, as a pair of [0,1] fractions plus the rotation it was captured in. Two facts
 * about the platform make the maths less trivial than "multiply the fraction by the size":
 *
 *  - `input tap x y` injects into the display's **current, rotated** pixel space. A tap at (0, 0) is the
 *    top-left of the screen as the user is holding it right now, whichever way that is.
 *  - `wm size`, which is GameCore's only source of a display size, reports the panel in its **natural**
 *    orientation. So the integrator has to orient that size to the current rotation before passing it in
 *    as [toCurrentPixels]'s `currentWidthPx` / `currentHeightPx`: swap the two on a 90°/270° rotation, use
 *    them as-is on 0°/180°. Those two numbers are the current rotated dimensions, not the natural ones.
 *
 * When the point is fired in the same rotation it was captured in, the fraction maps straight back to a
 * pixel. When the rotation differs, a rigid geometric rotation (0/90/180/270) carries the fraction into
 * the current frame first.
 *
 * **Honest limitation.** The rotation transform assumes the screen content rotates rigidly with the
 * device — the same physical spot on the glass holds the same thing after a turn, like a photo rotating.
 * That is the best a coordinate mapper can do without reading the game, and it is right for a game that
 * simply rotates its view. A game that *re-lays-out* on rotation — moving a fire button from the
 * bottom-right of portrait to somewhere else in landscape — will have moved the target out from under the
 * transformed point, and the tap will land on whatever now occupies that physical spot. There is no pure
 * geometry that recovers a layout GameCore cannot see; this is a deliberate best-effort, not a bug.
 *
 * Everything here is arithmetic on fractions and integers, so the whole contract is JVM-testable with no
 * emulator: see `TapCoordinateMapperTest`.
 */
object TapCoordinateMapper {

    /**
     * The pixel to feed `input tap` for [point] in the **current** rotated space.
     *
     * `currentWidthPx` and `currentHeightPx` are the display's dimensions *as it is rotated now* (the same
     * space `input tap` reads), which is why they belong to the caller: only the integrator knows the live
     * rotation and can orient `wm size`'s natural reading to match it.
     *
     * When [captureRotation] equals [currentRotation] this is simply `(currentWidthPx * x, currentHeightPx * y)`.
     * Otherwise the fraction is rotated by the difference between the two — a quarter turn swaps the axes, a
     * half turn mirrors both — before being scaled, so a point captured in portrait resolves to the right
     * place in landscape. The stored fraction is clamped into [0, 1] before scaling, so a stray out-of-range
     * value cannot push the tap off the display.
     */
    fun toCurrentPixels(
        point: FractionPoint,
        captureRotation: ScreenRotation,
        currentRotation: ScreenRotation,
        currentWidthPx: Int,
        currentHeightPx: Int,
    ): PixelPoint {
        val normalised = point.normalised()
        val x = normalised.x
        val y = normalised.y

        // The clockwise turn that carries the capture frame onto the current frame. Both rotations are
        // measured the same way, so their difference is the only thing that matters — 90/270 swap the
        // axes, 180 mirrors both, 0 is the identity.
        val delta = ((currentRotation.degrees - captureRotation.degrees) % 360 + 360) % 360
        val (fx, fy) = when (delta) {
            90 -> (1f - y) to x
            180 -> (1f - x) to (1f - y)
            270 -> y to (1f - x)
            else -> x to y
        }

        return PixelPoint(
            x = (currentWidthPx * fx).roundToInt(),
            y = (currentHeightPx * fy).roundToInt(),
        )
    }

    /**
     * Turns a captured tap pixel into the rotation-independent fraction stored for it.
     *
     * `widthPx` / `heightPx` are the dimensions of the frame the tap arrived in — the current rotated
     * space, the same one [toCurrentPixels] scales back into. The rotation itself is *not* folded in here:
     * the caller records the capture rotation alongside the fraction and passes it back as
     * [toCurrentPixels]'s `captureRotation`. The fraction is clamped into [0, 1] so a tap reported on the
     * very edge, or a bad size, cannot store a point that lives off the screen. This is the inverse of
     * [toCurrentPixels] in the same rotation, to within integer rounding.
     */
    fun captureFraction(tapXPx: Int, tapYPx: Int, widthPx: Int, heightPx: Int): FractionPoint {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        return FractionPoint(
            x = tapXPx.toFloat() / w,
            y = tapYPx.toFloat() / h,
        ).normalised()
    }

    /**
     * The [ScreenRotation] for a `Surface.ROTATION_*` constant (0, 1, 2, 3).
     *
     * This is what the integrator wants for `Display.getRotation()`, which returns exactly those constants.
     * Anything outside 0..3 is wrapped rather than rejected, so a garbage read degrades to a defined
     * rotation instead of throwing.
     */
    fun fromSurfaceRotation(surfaceRotation: Int): ScreenRotation =
        rotationForStep(((surfaceRotation % 4) + 4) % 4)

    /**
     * The [ScreenRotation] nearest [deg] degrees clockwise, wrapped into 0..359 first.
     *
     * A convenience for callers holding a rotation in degrees rather than a surface constant; a value that
     * is not a clean multiple of 90 is rounded to the nearest quarter turn.
     */
    fun rotationFromDegrees(deg: Int): ScreenRotation =
        rotationForStep(((deg / 90.0).roundToInt() % 4 + 4) % 4)

    /** Maps a quarter-turn count (0..3) to its rotation. */
    private fun rotationForStep(step: Int): ScreenRotation = when (step) {
        1 -> ScreenRotation.ROTATION_90
        2 -> ScreenRotation.ROTATION_180
        3 -> ScreenRotation.ROTATION_270
        else -> ScreenRotation.ROTATION_0
    }
}
