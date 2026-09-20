package com.gamecore.aimlab.engine3d

import com.gamecore.aimlab.engine.RecoilEngine
import com.gamecore.aimlab.engine.RecoilSpec
import com.gamecore.aimlab.engine.Vec2

/**
 * Drives recoil as camera kicks in **degrees** and measures how well the player counter-aimed (§3).
 *
 * The 2D recoil mode moved a crosshair dot in arena units; here the weapon kicks the *camera's* pitch and
 * yaw, the player drags to pull it back, and compensation is scored from the camera's own angular motion.
 * The pattern generation and the compensation scoring stay in the shared, tested [RecoilEngine] — this
 * only converts its arena-unit offsets into aim angles and records the two things the score needs: the
 * recoil each shot imposed and the player's counter-aim between shots.
 *
 * The conversion is a fixed [DEGREES_PER_UNIT]: a `RecoilSpec` says "0.02 units of vertical kick", and
 * one arena unit maps to a set number of degrees so the same spec produces a comparable climb whatever
 * the surface. Vertical kick is upward, which is positive pitch for the camera (the 2D engine expressed
 * up as negative Y for a top-left screen origin, so the sign is flipped on the way in).
 *
 * Stateful across a burst but allocation-light: it holds the running per-shot lists the score is folded
 * from at the end. Pure, android-free.
 */
class RecoilCamera3D(private val engine: RecoilEngine) {

    // The recoil offset imposed per shot (in degrees, as pitch/yaw) and the player's counter-aim per shot.
    private val recoilPerShot = ArrayList<Vec2>(64)
    private val counterPerShot = ArrayList<Vec2>(64)

    // The camera-angle delta the player has applied since the last shot, accumulated from applyLook.
    private var pendingCounterYaw = 0f
    private var pendingCounterPitch = 0f

    private var shotsFired = 0

    /** Clears burst state for a fresh run. */
    fun reset() {
        recoilPerShot.clear()
        counterPerShot.clear()
        pendingCounterYaw = 0f
        pendingCounterPitch = 0f
        shotsFired = 0
    }

    /**
     * The player's aim between shots is their compensation. Feed the applied camera deltas here so the
     * counter-aim accumulated since the last shot is attributed to countering it.
     */
    fun recordPlayerAim(deltaYawDegrees: Float, deltaPitchDegrees: Float) {
        pendingCounterYaw += deltaYawDegrees
        pendingCounterPitch += deltaPitchDegrees
    }

    /**
     * Fires one shot: returns the incremental camera kick (yaw, pitch) in degrees to apply, and banks the
     * counter-aim the player made since the previous shot against the previous shot's recoil.
     *
     * The kick is the difference between this shot's cumulative pattern offset and the previous one's, so
     * applying it to the camera walks the aim along the same pattern the 2D mode drew — only now it is
     * the view that moves. Returns yaw in `.first`, pitch in `.second`.
     */
    fun fireShot(spec: RecoilSpec): Pair<Float, Float> {
        // Attribute the counter-aim gathered since the last shot to that shot's recoil.
        if (shotsFired > 0) {
            counterPerShot.add(Vec2(pendingCounterYaw, pendingCounterPitch))
        }
        pendingCounterYaw = 0f
        pendingCounterPitch = 0f

        shotsFired++
        val cumulative = engine.pattern(spec, shotsFired).lastOrNull() ?: Vec2(0f, 0f)
        val previous = if (shotsFired > 1) {
            engine.pattern(spec, shotsFired - 1).lastOrNull() ?: Vec2(0f, 0f)
        } else {
            Vec2(0f, 0f)
        }
        // Incremental offset in arena units → degrees. Up is negative-Y in the 2D engine; flip for pitch.
        val kickYaw = (cumulative.x - previous.x) * DEGREES_PER_UNIT
        val kickPitch = -(cumulative.y - previous.y) * DEGREES_PER_UNIT
        recoilPerShot.add(Vec2(kickYaw, kickPitch))
        return kickYaw to kickPitch
    }

    /**
     * Folds the burst into a compensation score in [0,1] via the shared [RecoilEngine].
     *
     * The last shot's counter-aim is banked here (there is no shot after it to trigger the bank in
     * [fireShot]), then the recoil list and the counter list are handed to
     * [RecoilEngine.compensationScore], which is already unit-tested. The player's counter should be the
     * negative of the recoil to cancel it, so it is negated on the way in.
     */
    fun compensationScore(): Float {
        if (shotsFired == 0) return 0f
        // Bank the final shot's counter-aim.
        val recoil = ArrayList(recoilPerShot)
        val counter = ArrayList(counterPerShot)
        counter.add(Vec2(pendingCounterYaw, pendingCounterPitch))
        // recordPlayerAim already stores the player's *actual* camera delta — for perfect play a pull-back
        // of −kick — which is exactly the `playerCounter` compensationScore wants (it computes
        // recoil+counter and expects ≈0 for a cancelled kick). So the counter is passed through as-is; no
        // negation, which would double the residual and make perfect compensation score worst.
        val n = minOf(recoil.size, counter.size)
        if (n == 0) return 0f
        return engine.compensationScore(recoil.subList(0, n), counter.subList(0, n))
    }

    companion object {
        /** Arena-unit → degree scale for recoil kicks; a spec's 0.02 vertical becomes a modest climb. */
        const val DEGREES_PER_UNIT = 60f
    }
}
