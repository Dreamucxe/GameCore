package com.gamecore.aimlab.render

import android.opengl.Matrix
import com.gamecore.aimlab.engine3d.Camera3D
import com.gamecore.aimlab.engine3d.Vec3

/**
 * Column-major 4×4 matrix helpers for the GL layer, built on `android.opengl.Matrix`.
 *
 * The renderer must not allocate per frame (§2), so every function here writes into a caller-supplied
 * `FloatArray(16)` rather than returning a new one. The renderer keeps a small fixed set of scratch
 * matrices and reuses them each frame. All matrices are column-major, the layout GLES uniforms expect.
 */
object GlMath {

    /** Writes a perspective projection into [out] from a *horizontal* FOV, aspect, and clip planes. */
    fun perspective(out: FloatArray, fovDegreesHorizontal: Float, aspect: Float, near: Float, far: Float) {
        // Matrix.perspective takes a vertical FOV; convert from horizontal using the aspect ratio so the
        // world's horizontal field of view is what the camera's fovDegrees actually means.
        val halfH = Math.toRadians((fovDegreesHorizontal / 2f).toDouble())
        val vfovRad = 2.0 * Math.atan(Math.tan(halfH) / aspect.coerceAtLeast(0.0001f))
        val vfovDeg = Math.toDegrees(vfovRad).toFloat()
        Matrix.perspectiveM(out, 0, vfovDeg, aspect, near, far)
    }

    /** Writes a view matrix looking from the camera's eye along its forward vector into [out]. */
    fun view(out: FloatArray, camera: Camera3D) {
        val eye = camera.position
        val f = camera.forward
        val at = Vec3(eye.x + f.x, eye.y + f.y, eye.z + f.z)
        Matrix.setLookAtM(
            out, 0,
            eye.x, eye.y, eye.z,
            at.x, at.y, at.z,
            0f, 1f, 0f,
        )
    }

    /** out = a · b (both 4×4). [out] must not alias [a] or [b]. */
    fun multiply(out: FloatArray, a: FloatArray, b: FloatArray) {
        Matrix.multiplyMM(out, 0, a, 0, b, 0)
    }

    /** Sets [out] to identity. */
    fun identity(out: FloatArray) = Matrix.setIdentityM(out, 0)

    /** Post-translates [m] in place by (x,y,z). */
    fun translate(m: FloatArray, x: Float, y: Float, z: Float) = Matrix.translateM(m, 0, x, y, z)

    /** Post-scales [m] in place by (x,y,z). */
    fun scale(m: FloatArray, x: Float, y: Float, z: Float) = Matrix.scaleM(m, 0, x, y, z)

    /** Post-rotates [m] in place by [angleDeg] about (x,y,z). */
    fun rotate(m: FloatArray, angleDeg: Float, x: Float, y: Float, z: Float) =
        Matrix.rotateM(m, 0, angleDeg, x, y, z)

    /**
     * Writes the normal matrix (inverse-transpose of the model matrix's upper 3×3) into [out] as a
     * `FloatArray(16)`. For the rigid, uniformly-scaled transforms this renderer uses, the model matrix's
     * upper-left is already orthogonal-plus-uniform-scale, so the inverse-transpose reduces to the model
     * matrix itself; passing the full inverse-transpose keeps it correct if a non-uniform scale is ever
     * added. Uses two scratch operations on [out].
     */
    fun normalMatrix(out: FloatArray, model: FloatArray, scratch: FloatArray) {
        Matrix.invertM(scratch, 0, model, 0)
        Matrix.transposeM(out, 0, scratch, 0)
    }
}
