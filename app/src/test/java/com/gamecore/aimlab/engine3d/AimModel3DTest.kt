package com.gamecore.aimlab.engine3d

import com.gamecore.aimlab.engine.RecoilEngine
import com.gamecore.aimlab.engine.RecoilSpec
import com.gamecore.aimlab.engine.SeededRng
import com.gamecore.aimlab.engine.SensitivityMath
import com.gamecore.aimlab.engine.SensitivityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 3D aim math beyond raw geometry: spread cones, angle-error accumulation, the sensitivity→degrees
 * conversion, target spawning inside the room, and recoil driven into the camera.
 *
 * Every randomised piece is seeded so these are deterministic, not statistical, checks — the same
 * property the 2D engine tests rely on.
 */
class AimModel3DTest {

    private val eps = 1e-3f
    private val room = Room()

    // ---- spread cone ----

    @Test
    fun `zero spread returns the ray unchanged`() {
        val ray = Ray.of(Vec3.ZERO, Vec3(0f, 0f, -1f))
        val out = applySpreadCone(ray, 0f, SeededRng(1))
        assertEquals(ray.direction.x, out.direction.x, eps)
        assertEquals(ray.direction.y, out.direction.y, eps)
        assertEquals(ray.direction.z, out.direction.z, eps)
    }

    @Test
    fun `a spread cone stays within its half-angle and is reproducible for a seed`() {
        val ray = Ray.of(Vec3.ZERO, Vec3(0f, 0f, -1f))
        val cone = 5f
        val a = SeededRng(42)
        // Every draw must lie inside the cone half-angle (a small tolerance for float error).
        repeat(500) {
            val out = applySpreadCone(ray, cone, a)
            val off = angleBetweenDegrees(ray.direction, out.direction)
            assertTrue("shot outside the cone: $off", off <= cone + 0.05f)
        }
        // Same seed → same first perturbation.
        val first = applySpreadCone(ray, cone, SeededRng(7)).direction
        val second = applySpreadCone(ray, cone, SeededRng(7)).direction
        assertEquals(first.x, second.x, eps)
        assertEquals(first.y, second.y, eps)
    }

    @Test
    fun `a wide spread actually deflects the shot`() {
        val ray = Ray.of(Vec3.ZERO, Vec3(0f, 0f, -1f))
        val rng = SeededRng(3)
        var maxOff = 0f
        repeat(200) {
            val off = angleBetweenDegrees(ray.direction, applySpreadCone(ray, 10f, rng).direction)
            if (off > maxOff) maxOff = off
        }
        assertTrue("spread never deflected the shot", maxOff > 1f)
    }

    // ---- angle error accumulator ----

    @Test
    fun `the tracking accumulator reports time on target and average error in degrees`() {
        val acc = TrackingAccumulator3D(onTargetAngleDegrees = 3f)
        acc.add(errorDegrees = 1f, frameSeconds = 0.1f) // on target
        acc.add(errorDegrees = 2f, frameSeconds = 0.1f) // on target
        acc.add(errorDegrees = 10f, frameSeconds = 0.1f) // off target
        acc.add(errorDegrees = 20f, frameSeconds = 0.1f) // off target
        assertEquals(0.5f, acc.timeOnTargetFraction(), eps)
        assertEquals((1f + 2f + 10f + 20f) / 4f, acc.averageErrorDegrees(), 0.01f)
    }

    @Test
    fun `an empty accumulator is all zeros, never NaN`() {
        val acc = TrackingAccumulator3D(3f)
        assertEquals(0f, acc.timeOnTargetFraction(), eps)
        assertEquals(0f, acc.averageErrorDegrees(), eps)
        assertEquals(0f, acc.errorStdDevDegrees(), eps)
    }

    @Test
    fun `aim error is the angle between the ray and the direction to the target`() {
        val ray = Ray.of(Vec3.ZERO, Vec3(0f, 0f, -1f))
        // Target straight ahead → zero error.
        assertEquals(0f, aimErrorDegrees(ray, Vec3(0f, 0f, -5f)), eps)
        // Target 90 degrees to the side.
        assertEquals(90f, aimErrorDegrees(ray, Vec3(5f, 0f, 0f)), eps)
    }

    // ---- sensitivity → degrees ----

    @Test
    fun `touch look is resolution independent`() {
        val conv = { LookConversion(SensitivityMath(SensitivityProfile(name = "t"))) }
        // The same fraction of the surface must produce the same yaw on two different widths.
        val onNarrow = conv().fromTouch(deltaXpx = 540f, deltaYpx = 0f, surfaceWidthPx = 1080, aiming = false)
        val onWide = conv().fromTouch(deltaXpx = 720f, deltaYpx = 0f, surfaceWidthPx = 1440, aiming = false)
        // Both are half the width → identical yaw.
        assertEquals(onNarrow.first, onWide.first, eps)
        assertTrue("half a swipe should turn the view", onNarrow.first > 1f)
    }

    @Test
    fun `an upward drag raises the pitch`() {
        val conv = LookConversion(SensitivityMath(SensitivityProfile(name = "t")))
        // Negative pixel Y is upward on a top-left origin; pitch should come back positive.
        val (_, pitch) = conv.fromTouch(0f, -200f, surfaceWidthPx = 1080, aiming = false)
        assertTrue("upward drag should raise pitch, was $pitch", pitch > 0f)
    }

    @Test
    fun `ads multiplier slows the look`() {
        val profile = SensitivityProfile(name = "t", adsMultiplier = 0.5f)
        val hip = LookConversion(SensitivityMath(profile)).fromTouch(300f, 0f, 1080, aiming = false).first
        val ads = LookConversion(SensitivityMath(profile)).fromTouch(300f, 0f, 1080, aiming = true).first
        assertTrue("ADS should be slower than hip fire", kotlin.math.abs(ads) < kotlin.math.abs(hip))
    }

    // ---- spawn inside room ----

    @Test
    fun `flick targets always spawn inside the room bounds`() {
        val rng = SeededRng(99)
        val eye = Vec3(0f, 1.6f, 0f)
        repeat(1_000) {
            val p = spawnFlickTarget3D(
                rng, eye, room,
                minDistance = 3f, maxDistance = 12f,
                maxYawDegrees = 60f, maxPitchDegrees = 30f,
            )
            assertTrue("x outside room", p.x in -room.halfWidth..room.halfWidth)
            assertTrue("y outside room", p.y in 0f..room.height)
            assertTrue("z outside room", p.z in -room.halfDepth..room.halfDepth)
        }
    }

    @Test
    fun `tracking path stays within its span and is reproducible`() {
        val path = { TargetPath3D(Pattern3D.HORIZONTAL, depth = 6f, halfSpan = 3f, eyeHeight = 1.6f, speed = 2f, rng = SeededRng(1)) }
        val p = path()
        var t = 0f
        while (t < 20f) {
            val pos = p.positionAt(t)
            assertTrue("x left the span at t=$t: ${pos.x}", pos.x in -3.01f..3.01f)
            t += 0.13f
        }
        // RANDOM is seed-reproducible.
        val r1 = TargetPath3D(Pattern3D.RANDOM, 6f, 3f, 1.6f, 2f, SeededRng(5)).positionAt(3.3f)
        val r2 = TargetPath3D(Pattern3D.RANDOM, 6f, 3f, 1.6f, 2f, SeededRng(5)).positionAt(3.3f)
        assertEquals(r1.x, r2.x, eps)
        assertEquals(r1.y, r2.y, eps)
    }

    // ---- recoil into camera ----

    @Test
    fun `recoil kicks the camera up and a matching counter-aim scores high compensation`() {
        val spec = RecoilSpec(verticalPerShot = 0.02f, horizontalPerShot = 0.004f, randomness = 0f)
        val recoil = RecoilCamera3D(RecoilEngine(SeededRng(1)))
        val cam = Camera3D()
        // Fire 10 shots, and each time immediately counter-aim by the negative of the kick.
        repeat(10) {
            val (kickYaw, kickPitch) = recoil.fireShot(spec)
            cam.applyRecoil(kickYaw, kickPitch)
            // Perfect player: pull back exactly the kick.
            recoil.recordPlayerAim(-kickYaw, -kickPitch)
            cam.applyLook(-kickYaw, -kickPitch)
        }
        val score = recoil.compensationScore()
        assertTrue("near-perfect compensation should score high, was $score", score > 0.8f)
    }

    @Test
    fun `no compensation scores low`() {
        val spec = RecoilSpec(verticalPerShot = 0.02f, horizontalPerShot = 0.004f, randomness = 0f)
        val recoil = RecoilCamera3D(RecoilEngine(SeededRng(1)))
        repeat(10) {
            recoil.fireShot(spec)
            recoil.recordPlayerAim(0f, 0f) // player never pulls back
        }
        val score = recoil.compensationScore()
        assertTrue("doing nothing should score low, was $score", score < 0.2f)
    }

    @Test
    fun `a fired shot kicks the pitch upward`() {
        val spec = RecoilSpec(verticalPerShot = 0.02f, randomness = 0f)
        val recoil = RecoilCamera3D(RecoilEngine(SeededRng(1)))
        val (_, kickPitch) = recoil.fireShot(spec)
        assertTrue("vertical kick should raise pitch, was $kickPitch", kickPitch > 0f)
    }
}
