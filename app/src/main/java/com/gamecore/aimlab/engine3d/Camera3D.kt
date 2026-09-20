package com.gamecore.aimlab.engine3d

/**
 * The first-person camera: where the player stands and where they look.
 *
 * Pure state and pure math — no `android.*`, no matrices (the GL layer builds those from [position],
 * [yawDegrees], [pitchDegrees] and [fovDegrees]). Everything the aim model needs is here: the crosshair
 * ray is [forward] from [position], and every look input turns into a yaw/pitch change that this clamps.
 *
 * Pitch is clamped to just short of straight up/down. Past ±90° the forward vector flips and the horizon
 * rolls, which no aim trainer wants and which would let a drag wrap the view over the top; [PITCH_LIMIT]
 * stops a hair short so the player can look nearly vertical without the singularity. Yaw wraps freely.
 *
 * The class is deliberately mutable and allocation-free on the hot path: [applyLook], [applyRecoil] and
 * [move] mutate in place because they run per input event and per tick, and a fresh Camera per frame
 * would be exactly the per-frame allocation §2 forbids.
 */
class Camera3D(
    var position: Vec3 = Vec3(0f, DEFAULT_EYE_HEIGHT, 0f),
    yawDegrees: Float = 0f,
    pitchDegrees: Float = 0f,
    var fovDegrees: Float = DEFAULT_FOV,
) {
    var yawDegrees: Float = normaliseYaw(yawDegrees)
        private set

    var pitchDegrees: Float = pitchDegrees.coerceIn(-PITCH_LIMIT, PITCH_LIMIT)
        private set

    /** The unit look direction — the crosshair ray's direction (§3). Recomputed from the angles. */
    val forward: Vec3 get() = forwardFromAngles(yawDegrees, pitchDegrees)

    /** The crosshair ray: from the eye, along [forward]. Every shot starts as this ray. */
    fun aimRay(): Ray = Ray(position, forward)

    /**
     * Turns the view by a yaw/pitch delta in **degrees**, clamping pitch and wrapping yaw.
     *
     * The sensitivity pipeline converts a raw touch/gyro delta into degrees before it reaches here, so
     * this is deliberately unit-agnostic: it moves the view by exactly the degrees it is handed. Returns
     * the actual applied pitch delta (after clamping), which recoil-compensation measurement needs so a
     * pull that hit the pitch limit is not counted as more correction than the camera actually made.
     */
    fun applyLook(deltaYawDegrees: Float, deltaPitchDegrees: Float): Float {
        yawDegrees = normaliseYaw(yawDegrees + deltaYawDegrees)
        val before = pitchDegrees
        pitchDegrees = (pitchDegrees + deltaPitchDegrees).coerceIn(-PITCH_LIMIT, PITCH_LIMIT)
        return pitchDegrees - before
    }

    /**
     * Adds a recoil kick to the aim, in degrees. Separate from [applyLook] only for clarity at the call
     * site; the clamp is identical, because a recoil kick that drove pitch past vertical would be as
     * wrong as a drag that did.
     */
    fun applyRecoil(kickYawDegrees: Float, kickPitchDegrees: Float) {
        yawDegrees = normaliseYaw(yawDegrees + kickYawDegrees)
        pitchDegrees = (pitchDegrees + kickPitchDegrees).coerceIn(-PITCH_LIMIT, PITCH_LIMIT)
    }

    /** Sets the eye height (crouch/jump/stand change it), keeping x/z. */
    fun setEyeHeight(height: Float) {
        position = Vec3(position.x, height, position.z)
    }

    /**
     * Moves the player on the floor plane, clamped inside the room's half-extent so the camera can never
     * leave the box (§3 movement). Y is left to [setEyeHeight]; this is walk, not fly.
     */
    fun move(delta: Vec3, roomHalfExtent: Float, margin: Float = MOVE_MARGIN) {
        val limit = (roomHalfExtent - margin).coerceAtLeast(0f)
        val nx = (position.x + delta.x).coerceIn(-limit, limit)
        val nz = (position.z + delta.z).coerceIn(-limit, limit)
        position = Vec3(nx, position.y, nz)
    }

    /** Resets to the room's default standing pose looking down −Z. Used at the start of every run. */
    fun reset() {
        position = Vec3(0f, DEFAULT_EYE_HEIGHT, 0f)
        yawDegrees = 0f
        pitchDegrees = 0f
        fovDegrees = DEFAULT_FOV
    }

    companion object {
        /** Just short of vertical, so the player can look nearly straight up/down without the flip. */
        const val PITCH_LIMIT = 89f

        /** Standing eye height in metres — a person's eyes, not their feet. */
        const val DEFAULT_EYE_HEIGHT = 1.6f

        /** Default horizontal field of view in degrees; ADS narrows it. */
        const val DEFAULT_FOV = 90f

        /** How far the camera is kept from a wall, so the near plane never clips through it. */
        const val MOVE_MARGIN = 0.4f

        /** Folds any yaw into `[-180, 180)` so the stored value cannot drift unbounded over a long run. */
        fun normaliseYaw(yaw: Float): Float {
            var y = yaw % 360f
            if (y >= 180f) y -= 360f
            if (y < -180f) y += 360f
            return y
        }
    }
}
