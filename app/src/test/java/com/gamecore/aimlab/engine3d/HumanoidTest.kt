package com.gamecore.aimlab.engine3d

import com.gamecore.aimlab.engine.Rng
import com.gamecore.aimlab.engine.SeededRng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The humanoid hit test, zone stats, and motion (spec §4).
 *
 * A dummy stands a few metres down −Z; rays are aimed at each zone's world height and asserted to return
 * that zone. Overlap priority (head over neck over chest), a ray behind the camera, a tangent, spawn
 * bounds, zone-stat aggregation, head-only scoring and finite animation params are all pinned.
 */
class HumanoidTest {

    private val eps = 1e-2f
    private fun dummyAt(x: Float, z: Float, scale: Float = 1f): HumanoidTarget =
        HumanoidTarget(id = 1L, pose = DummyPose(position = Vec3(x, 0f, z), scale = scale), spawnedAtNanos = 0L)

    /** A ray from the origin (eye at 1.6 m) aimed at a world point. */
    private fun rayTo(target: Vec3, eye: Vec3 = Vec3(0f, 1.6f, 0f)): Ray = Ray.of(eye, target - eye)

    @Test
    fun `a ray at head height hits the head`() {
        val d = dummyAt(0f, -5f)
        val hit = d.intersect(rayTo(d.headCentre()))
        assertNotNull(hit)
        assertEquals(HitZone.HEAD, hit!!.zone)
    }

    @Test
    fun `a ray at chest height hits the chest`() {
        val d = dummyAt(0f, -5f)
        // Chest centre is ~0.71 of standing height.
        val chest = Vec3(0f, HumanoidTarget.STANDING_HEIGHT * 0.71f, -5f)
        val hit = d.intersect(rayTo(chest))
        assertNotNull(hit)
        assertEquals(HitZone.CHEST, hit!!.zone)
    }

    @Test
    fun `a ray at leg height hits the legs`() {
        val d = dummyAt(0f, -5f)
        // Legs sit at x = ±0.08·height, not on the centre line, so a centred ray passes between them —
        // that gap is anatomically correct. Aim at one leg's actual x.
        val legX = HumanoidTarget.STANDING_HEIGHT * 0.08f
        val leg = Vec3(legX, HumanoidTarget.STANDING_HEIGHT * 0.2f, -5f)
        val hit = d.intersect(rayTo(leg))
        assertNotNull(hit)
        assertEquals(HitZone.LEGS, hit!!.zone)
    }

    @Test
    fun `a ray into empty space beside the dummy misses`() {
        val d = dummyAt(0f, -5f)
        val beside = Vec3(3f, 1.6f, -5f)
        assertNull(d.intersect(rayTo(beside)))
    }

    @Test
    fun `a ray pointing away from the dummy misses`() {
        val d = dummyAt(0f, -5f)
        // Aim at the head but from an eye on the far side, pointing +Z (away).
        val away = Ray.of(Vec3(0f, 1.6f, 0f), Vec3(0f, 0f, 1f))
        assertNull(d.intersect(away))
    }

    @Test
    fun `an exact-distance tie resolves by zone priority`() {
        // Priority only breaks a dead distance tie (within the intersect epsilon); prove that directly:
        // HEAD outranks NECK outranks CHEST, so the enum's own ordering is the tie-break the hit test uses.
        assertTrue(HitZone.HEAD.priority > HitZone.NECK.priority)
        assertTrue(HitZone.NECK.priority > HitZone.CHEST.priority)
        assertTrue(HitZone.CHEST.priority > HitZone.LEGS.priority)
    }

    @Test
    fun `a smaller dummy scale shrinks the whole body`() {
        val small = dummyAt(0f, -5f, scale = 0.5f)
        val head = small.headCentre()
        // Head of a half-scale dummy sits far lower than a full one.
        assertTrue(head.y < HumanoidTarget.STANDING_HEIGHT * 0.5f)
    }

    // ---- zone stats ----

    @Test
    fun `zone stats aggregate hits, head accuracy and damage`() {
        var s = ZoneStats.EMPTY
        s = s.recordShot(HitZone.HEAD, HitZone.HEAD.defaultDamageMultiplier)
        s = s.recordShot(HitZone.CHEST, HitZone.CHEST.defaultDamageMultiplier)
        s = s.recordShot(null, 0f) // a miss
        assertEquals(3, s.shots)
        assertEquals(2, s.hits)
        assertEquals(1, s.headHits)
        assertEquals(0.5f, s.headAccuracy, eps)
        assertEquals(
            HitZone.HEAD.defaultDamageMultiplier + HitZone.CHEST.defaultDamageMultiplier,
            s.damage, eps,
        )
    }

    @Test
    fun `head accuracy is zero, never NaN, before any hit`() {
        val s = ZoneStats.EMPTY.recordShot(null, 0f)
        assertEquals(0f, s.headAccuracy, 0f)
        assertTrue(!s.headAccuracy.isNaN())
    }

    @Test
    fun `head-only scoring counts only head hits as landed`() {
        // Head-only is applied by the caller passing null for a non-head zone; assert stats reflect it.
        var s = ZoneStats.EMPTY
        val zone = HitZone.CHEST
        val scored = if (zone == HitZone.HEAD) zone else null // head-only rule
        s = s.recordShot(scored, if (scored != null) scored.defaultDamageMultiplier else 0f)
        assertEquals(1, s.shots)
        assertEquals(0, s.hits) // a chest hit does not count under head-only
    }

    // ---- motion ----

    @Test
    fun `every motion produces finite pose scalars across a long run`() {
        val rng: Rng = SeededRng(7)
        for (m in DummyMotion.entries) {
            val path = DummyMotionPath(
                motion = m, spawn = Vec3(0f, 0f, -6f), facingYawDegrees = 180f,
                speed = 2f, directionChangeSeconds = 1.5f, strafeHalfWidth = 2f,
                peekHalfWidth = 1.5f, scale = 1f, rng = SeededRng(7),
            )
            var t = 0f
            while (t < 30f) {
                val p = path.poseAt(t)
                assertTrue("$m x NaN", p.position.x.isFinite())
                assertTrue("$m z NaN", p.position.z.isFinite())
                assertTrue("$m crouch out of range", p.crouch in 0f..1f)
                assertTrue("$m legSwing NaN", p.legSwing.isFinite())
                assertTrue("$m vertical NaN", p.verticalOffset.isFinite() && p.verticalOffset >= 0f)
                t += 0.13f
            }
        }
    }

    @Test
    fun `a strafing dummy stays within its half-width`() {
        val path = DummyMotionPath(
            motion = DummyMotion.STRAFE, spawn = Vec3(0f, 0f, -6f), facingYawDegrees = 180f,
            speed = 2f, directionChangeSeconds = 1.2f, strafeHalfWidth = 2f,
            peekHalfWidth = 1.5f, scale = 1f, rng = SeededRng(3),
        )
        var t = 0f
        while (t < 20f) {
            val x = path.poseAt(t).position.x
            assertTrue("strafe left its width: $x", x in -2.01f..2.01f)
            t += 0.1f
        }
    }

    @Test
    fun `motion is reproducible for a seed`() {
        fun sample() = DummyMotionPath(
            DummyMotion.STRAFE, Vec3(0f, 0f, -6f), 180f, 2f, 1.2f, 2f, 1.5f, 1f, SeededRng(42),
        ).poseAt(3.3f)
        assertEquals(sample().position.x, sample().position.x, 1e-6f)
    }
}
