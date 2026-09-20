package com.gamecore.aimlab.render

import com.gamecore.aimlab.engine.WeaponCategory
import com.gamecore.aimlab.engine3d.Target3D
import com.gamecore.aimlab.engine3d.Vec3

/**
 * The immutable snapshot the GL thread draws one frame from (§2, §5).
 *
 * The training loop and the UI run on other threads and mutate their own state freely; the renderer never
 * touches that state. Instead the loop publishes one of these — a plain immutable value — and the GL
 * thread reads the latest via an `AtomicReference`. Because it is immutable and swapped atomically, the
 * renderer always draws a self-consistent frame and there is no lock on the hot path.
 *
 * Only fields the renderer actually draws live here (§9.item-2: expose only what the UI/consumer uses).
 * Positions are world metres from the 3D engine; the renderer builds its own matrices from the camera
 * fields.
 */
data class RenderState(
    val cameraPosition: Vec3,
    val cameraYawDegrees: Float,
    val cameraPitchDegrees: Float,
    val fovDegrees: Float,
    /** The live targets to draw this frame. A snapshot list; the renderer never mutates it. */
    val targets: List<Target3D> = emptyList(),
    /** Which weapon silhouette to draw as the viewmodel; null hides it (e.g. reaction mode). */
    val weaponCategory: WeaponCategory? = null,
    /** Recoil kick to offset the viewmodel by, in a small local unit; decays as the aim recovers. */
    val viewmodelRecoil: Float = 0f,
    /** 0 = hip fire, 1 = fully aimed; drives FOV narrowing and the viewmodel sliding to centre. */
    val adsProgress: Float = 0f,
    /** Horizontal sway phase while moving, for the idle/walk weapon sway. */
    val swayPhase: Float = 0f,
    /** Set for the frame a shot is fired, so the renderer flashes the muzzle and a hit marker. */
    val muzzleFlash: Boolean = false,
    /** Set for the frames after a hit, so the renderer shows the centre hit marker. */
    val hitMarker: Boolean = false,
    /** World positions of recent misses, for wall bullet-hole decals (recoil/miss feedback). */
    val decals: List<Vec3> = emptyList(),
    /** World positions to spawn a hit particle burst at this frame; consumed once by the renderer. */
    val hitBursts: List<Vec3> = emptyList(),
) {
    companion object {
        /** A safe empty state to draw before the first real frame arrives (camera at the origin). */
        val INITIAL = RenderState(
            cameraPosition = Vec3(0f, 1.6f, 0f),
            cameraYawDegrees = 0f,
            cameraPitchDegrees = 0f,
            fovDegrees = 90f,
        )
    }
}

/** Resolution-scale presets for the "Render quality" setting (§2). */
enum class RenderQuality(val scale: Float, val label: String) {
    FULL(1f, "Full"),
    HIGH(0.75f, "High"),
    LOW(0.5f, "Low"),
    ;

    companion object {
        fun fromName(name: String?): RenderQuality = entries.firstOrNull { it.name == name } ?: FULL
    }
}
