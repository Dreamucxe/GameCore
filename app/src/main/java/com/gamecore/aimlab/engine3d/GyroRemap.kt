package com.gamecore.aimlab.engine3d

/**
 * Remaps a raw gyroscope delta to the current display rotation so looking left/right and up/down feels
 * correct in every orientation, including reverse landscape (§3).
 *
 * The gyroscope reports angular velocity about the *device's* physical axes, which do not turn when the
 * display rotates — so a phone rotated 90° into landscape reports what was "pitch" in portrait as the
 * axis the player now experiences as "yaw". Android's `SensorManager.remapCoordinateSystem` solves the
 * same problem for the rotation vector; here the two axes the look pipeline uses are remapped directly,
 * which is cheaper and keeps the engine android-free and unit-testable.
 *
 * Input is `(dx, dy)` where `dx` turns the view horizontally and `dy` vertically in the sensor's natural
 * (portrait) frame. The output is the pair the camera should apply for the given [rotation], one of the
 * four `Surface.ROTATION_*` constants (0/1/2/3 = 0°/90°/180°/270°). Real sensor data only — this only
 * relabels and signs the axes, it never fabricates motion.
 */
object GyroRemap {

    const val ROTATION_0 = 0
    const val ROTATION_90 = 1
    const val ROTATION_180 = 2
    const val ROTATION_270 = 3

    /**
     * Remaps a `(dx, dy)` gyro delta for a display [rotation].
     *
     * Portrait (0°) passes through. At 90° (landscape, device rotated counter-clockwise) the portrait
     * vertical axis becomes the horizontal one and vice versa, with a sign flip so a physical turn to the
     * player's right still turns the view right. 270° (reverse landscape) is the mirror of 90°, and 180°
     * (upside-down portrait) negates both axes. Returns `(yaw, pitch)` for the camera.
     */
    fun remap(dx: Float, dy: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
        ROTATION_90 -> dy to -dx
        ROTATION_180 -> -dx to -dy
        ROTATION_270 -> -dy to dx
        else -> dx to dy // ROTATION_0 and any unexpected value pass through unchanged
    }
}
