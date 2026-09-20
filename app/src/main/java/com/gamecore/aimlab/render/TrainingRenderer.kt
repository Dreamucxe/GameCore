package com.gamecore.aimlab.render

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.gamecore.aimlab.engine.WeaponCategory
import com.gamecore.aimlab.engine3d.Camera3D
import com.gamecore.aimlab.engine3d.Room
import com.gamecore.aimlab.engine3d.Vec3
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The GLSurfaceView renderer: draws one first-person frame of the training room per callback (§2).
 *
 * All GL resources — programs, meshes, the weapon viewmodels — are created in [onSurfaceCreated], so a
 * context loss that discards them is recovered simply by the system calling it again. The draw loop reads
 * the latest [RenderState] from an [AtomicReference] the loop/UI thread publishes into, so the GL thread
 * never touches engine mutable state and never locks. Matrices and scratch arrays are preallocated and
 * reused; nothing in [onDrawFrame] allocates.
 *
 * A failure to compile or link any shader throws [GlException] out of [onSurfaceCreated]; the hosting view
 * catches it via [onError] and shows the "3D view unavailable" screen rather than crashing (§2).
 *
 * @param stateSource returns the frame to draw; called once per frame on the GL thread.
 * @param onFrameTimeMillis receives the real measured frame time each frame (no fake values, §2).
 * @param onError called on the GL thread if GL setup fails, so the UI can fall back.
 */
class TrainingRenderer(
    private val stateSource: () -> RenderState,
    private val onFrameTimeMillis: (Float) -> Unit,
    private val onError: (String) -> Unit,
    private val room: Room = Room(),
) : GLSurfaceView.Renderer {

    // Programs (recreated per surface).
    private var litProgram: GlProgram? = null
    private var roomProgram: GlProgram? = null
    private var flatProgram: GlProgram? = null

    // Meshes.
    private var sphereMesh: Mesh? = null
    private var roomMesh: Mesh? = null
    private var quadMesh: Mesh? = null
    private val weaponMeshes = HashMap<WeaponCategory, Mesh>()

    // Feedback pools.
    private val particles = ParticlePool()
    private val decals = DecalRing()

    // Scratch matrices — reused every frame, never allocated in the loop.
    private val projection = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val normalMat = FloatArray(16)
    private val scratch = FloatArray(16)

    private var aspect = 1f
    private var lastFrameNanos = 0L
    private val camera = Camera3D()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            GLES20.glClearColor(FOG[0], FOG[1], FOG[2], 1f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            GLES20.glCullFace(GLES20.GL_BACK)

            litProgram = GlProgram(Shaders.LIT_VERTEX, Shaders.LIT_FRAGMENT)
            roomProgram = GlProgram(Shaders.ROOM_VERTEX, Shaders.ROOM_FRAGMENT)
            flatProgram = GlProgram(Shaders.FLAT_VERTEX, Shaders.FLAT_FRAGMENT)

            sphereMesh = Meshes.buildUvSphere()
            roomMesh = Meshes.buildRoom(room)
            quadMesh = Meshes.buildUnitQuad()
            weaponMeshes.clear()
            for (c in WeaponCategory.entries) weaponMeshes[c] = Meshes.buildWeaponViewmodel(c)

            particles.clear()
            decals.clear()
            lastFrameNanos = 0L
        } catch (e: GlException) {
            onError(e.message ?: "OpenGL initialisation failed")
        } catch (e: Exception) {
            onError("OpenGL unavailable: ${e.message}")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = if (height > 0) width.toFloat() / height else 1f
    }

    override fun onDrawFrame(gl: GL10?) {
        val lit = litProgram ?: return
        val roomP = roomProgram ?: return
        val flat = flatProgram ?: return

        // Real frame time from the GL clock; never fabricated.
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else (now - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = now
        if (dt > 0f) onFrameTimeMillis(dt * 1000f)

        val state = stateSource()

        // Sync the local camera from the published pose (no allocation).
        camera.reset()
        camera.setEyeHeight(state.cameraPosition.y)
        camera.move(
            Vec3(state.cameraPosition.x, state.cameraPosition.y, state.cameraPosition.z) - camera.position,
            roomHalfExtent = room.halfExtent, margin = 0f,
        )
        camera.applyLook(state.cameraYawDegrees, state.cameraPitchDegrees)

        GlMath.perspective(projection, state.fovDegrees, aspect, NEAR, FAR)
        GlMath.view(viewMatrix, camera)
        GlMath.multiply(vp, projection, viewMatrix)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        drawRoom(roomP)
        drawTargets(lit, state)
        drawViewmodel(lit, state)

        // Feedback: spawn any new bursts, age particles, draw them and decals and the muzzle/hit marker.
        for (b in state.hitBursts) particles.spawnBurst(b)
        for (d in state.decals) decals.add(d)
        particles.update(dt)
        drawParticles(flat)
        drawDecals(flat)
        if (state.hitMarker) drawHitMarker(flat)
    }

    private fun drawRoom(p: GlProgram) {
        val mesh = roomMesh ?: return
        p.use()
        GlMath.identity(model)
        GlMath.multiply(mvp, vp, model)
        GLES20.glUniformMatrix4fv(p.uniform("uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(p.uniform("uModel"), 1, false, model, 0)
        GLES20.glUniform3f(p.uniform("uColorA"), 0.20f, 0.22f, 0.25f)
        GLES20.glUniform3f(p.uniform("uColorB"), 0.28f, 0.30f, 0.34f)
        GLES20.glUniform3f(p.uniform("uFogColor"), FOG[0], FOG[1], FOG[2])
        GLES20.glUniform1f(p.uniform("uFogDensity"), FOG_DENSITY)
        drawMesh(mesh, p.attribute("aPos"), p.attribute("aUv"), posSize = 3, secondSize = 2)
    }

    private fun drawTargets(p: GlProgram, state: RenderState) {
        val mesh = sphereMesh ?: return
        p.use()
        setLitCommon(p, state)
        for (t in state.targets) {
            GlMath.identity(model)
            GlMath.translate(model, t.position.x, t.position.y, t.position.z)
            GlMath.scale(model, t.radius, t.radius, t.radius)
            GlMath.multiply(mvp, vp, model)
            GlMath.normalMatrix(normalMat, model, scratch)
            GLES20.glUniformMatrix4fv(p.uniform("uMvp"), 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(p.uniform("uModel"), 1, false, model, 0)
            GLES20.glUniformMatrix4fv(p.uniform("uNormal"), 1, false, normalMat, 0)
            val c = colourFor(t.kind)
            GLES20.glUniform3f(p.uniform("uBaseColor"), c[0], c[1], c[2])
            drawMesh(mesh, p.attribute("aPos"), p.attribute("aNormal"), posSize = 3, secondSize = 3)
        }
    }

    private fun drawViewmodel(p: GlProgram, state: RenderState) {
        val category = state.weaponCategory ?: return
        val mesh = weaponMeshes[category] ?: return
        p.use()
        setLitCommon(p, state)
        // Place the viewmodel in front of and below-right of the camera, in world space, by offsetting
        // along the camera's own axes. ADS slides it toward centre and pulls it in.
        val fwd = camera.forward
        val right = fwd.cross(Vec3(0f, 1f, 0f)).normalised()
        val up = right.cross(fwd).normalised()
        val ads = state.adsProgress.coerceIn(0f, 1f)
        val rightOffset = (0.28f) * (1f - ads)
        val downOffset = 0.22f - 0.10f * ads + state.viewmodelRecoil * 0.05f
        val forwardOffset = 0.55f - 0.10f * ads
        val sway = kotlin.math.sin(state.swayPhase) * 0.01f
        val eye = camera.position
        val pos = Vec3(
            eye.x + fwd.x * forwardOffset + right.x * (rightOffset + sway) - up.x * downOffset,
            eye.y + fwd.y * forwardOffset + right.y * (rightOffset + sway) - up.y * downOffset,
            eye.z + fwd.z * forwardOffset + right.z * (rightOffset + sway) - up.z * downOffset,
        )
        GlMath.identity(model)
        GlMath.translate(model, pos.x, pos.y, pos.z)
        GlMath.rotate(model, camera.yawDegrees, 0f, 1f, 0f)
        GlMath.rotate(model, -state.viewmodelRecoil * 8f, 1f, 0f, 0f)
        GlMath.multiply(mvp, vp, model)
        GlMath.normalMatrix(normalMat, model, scratch)
        GLES20.glUniformMatrix4fv(p.uniform("uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(p.uniform("uModel"), 1, false, model, 0)
        GLES20.glUniformMatrix4fv(p.uniform("uNormal"), 1, false, normalMat, 0)
        GLES20.glUniform3f(p.uniform("uBaseColor"), 0.32f, 0.34f, 0.38f)
        drawMesh(mesh, p.attribute("aPos"), p.attribute("aNormal"), posSize = 3, secondSize = 3)

        if (state.muzzleFlash) drawMuzzleFlash(pos, fwd)
    }

    private fun drawParticles(p: GlProgram) {
        val mesh = quadMesh ?: return
        p.use()
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        particles.forEachLive { x, y, z, lifeFraction ->
            GlMath.identity(model)
            GlMath.translate(model, x, y, z)
            val s = 0.06f * lifeFraction
            GlMath.scale(model, s, s, s)
            GlMath.multiply(mvp, vp, model)
            GLES20.glUniformMatrix4fv(p.uniform("uMvp"), 1, false, mvp, 0)
            GLES20.glUniform4f(p.uniform("uColor"), 1f, 0.8f, 0.3f, lifeFraction)
            drawFlat(mesh, p.attribute("aPos"))
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawDecals(p: GlProgram) {
        val mesh = quadMesh ?: return
        p.use()
        decals.forEach { x, y, z ->
            GlMath.identity(model)
            GlMath.translate(model, x, y, z)
            GlMath.scale(model, 0.12f, 0.12f, 0.12f)
            GlMath.multiply(mvp, vp, model)
            GLES20.glUniformMatrix4fv(p.uniform("uMvp"), 1, false, mvp, 0)
            GLES20.glUniform4f(p.uniform("uColor"), 0.05f, 0.05f, 0.06f, 1f)
            drawFlat(mesh, p.attribute("aPos"))
        }
    }

    private fun drawMuzzleFlash(pos: Vec3, forward: Vec3) {
        val p = flatProgram ?: return
        val mesh = quadMesh ?: return
        p.use()
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GlMath.identity(model)
        GlMath.translate(model, pos.x + forward.x * 0.3f, pos.y + forward.y * 0.3f, pos.z + forward.z * 0.3f)
        GlMath.scale(model, 0.14f, 0.14f, 0.14f)
        GlMath.multiply(mvp, vp, model)
        GLES20.glUniformMatrix4fv(p.uniform("uMvp"), 1, false, mvp, 0)
        GLES20.glUniform4f(p.uniform("uColor"), 1f, 0.85f, 0.4f, 0.8f)
        drawFlat(mesh, p.attribute("aPos"))
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawHitMarker(p: GlProgram) {
        // A small quad at the crosshair depth, drawn in front of the camera. Depth test off so it always
        // shows. Kept minimal — the real crosshair is a Compose overlay.
        val mesh = quadMesh ?: return
        p.use()
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        val fwd = camera.forward
        val eye = camera.position
        GlMath.identity(model)
        GlMath.translate(model, eye.x + fwd.x, eye.y + fwd.y, eye.z + fwd.z)
        GlMath.scale(model, 0.03f, 0.03f, 0.03f)
        GlMath.multiply(mvp, vp, model)
        GLES20.glUniformMatrix4fv(p.uniform("uMvp"), 1, false, mvp, 0)
        GLES20.glUniform4f(p.uniform("uColor"), 1f, 1f, 1f, 0.9f)
        drawFlat(mesh, p.attribute("aPos"))
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun setLitCommon(p: GlProgram, state: RenderState) {
        GLES20.glUniform3f(p.uniform("uLightDir"), LIGHT_DIR[0], LIGHT_DIR[1], LIGHT_DIR[2])
        GLES20.glUniform3f(
            p.uniform("uCameraPos"),
            state.cameraPosition.x, state.cameraPosition.y, state.cameraPosition.z,
        )
    }

    private fun colourFor(kind: com.gamecore.aimlab.engine3d.TargetKind): FloatArray = when (kind) {
        com.gamecore.aimlab.engine3d.TargetKind.STANDARD -> floatArrayOf(0.1f, 0.8f, 0.9f)
        com.gamecore.aimlab.engine3d.TargetKind.SMALL -> floatArrayOf(0.2f, 0.9f, 0.7f)
        com.gamecore.aimlab.engine3d.TargetKind.FAR -> floatArrayOf(0.3f, 0.7f, 1.0f)
    }

    /** Draws an interleaved mesh with a position attribute and one secondary (normal or uv) attribute. */
    private fun drawMesh(mesh: Mesh, posLoc: Int, secondLoc: Int, posSize: Int, secondSize: Int) {
        val strideBytes = mesh.stride * 4
        mesh.vertices.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, posSize, GLES20.GL_FLOAT, false, strideBytes, mesh.vertices)
        mesh.vertices.position(posSize)
        GLES20.glEnableVertexAttribArray(secondLoc)
        GLES20.glVertexAttribPointer(secondLoc, secondSize, GLES20.GL_FLOAT, false, strideBytes, mesh.vertices)
        mesh.indices.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indexCount, GLES20.GL_UNSIGNED_SHORT, mesh.indices)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(secondLoc)
    }

    /** Draws a position-only flat mesh (particles/decals/markers). */
    private fun drawFlat(mesh: Mesh, posLoc: Int) {
        val strideBytes = mesh.stride * 4
        mesh.vertices.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, strideBytes, mesh.vertices)
        mesh.indices.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indexCount, GLES20.GL_UNSIGNED_SHORT, mesh.indices)
        GLES20.glDisableVertexAttribArray(posLoc)
    }

    /** Releases GL resources. Called from the GL thread on teardown. */
    fun release() {
        litProgram?.release(); litProgram = null
        roomProgram?.release(); roomProgram = null
        flatProgram?.release(); flatProgram = null
        weaponMeshes.clear()
    }

    private companion object {
        const val NEAR = 0.1f
        const val FAR = 60f
        const val FOG_DENSITY = 0.035f
        val FOG = floatArrayOf(0.12f, 0.13f, 0.16f)
        // Direction TO a soft key light, from the upper front-left.
        val LIGHT_DIR = floatArrayOf(-0.4f, 0.8f, 0.45f)
    }
}
