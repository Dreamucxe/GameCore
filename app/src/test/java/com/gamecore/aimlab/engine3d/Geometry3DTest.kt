package com.gamecore.aimlab.engine3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 3D geometry the whole aim model is built on: ray–sphere intersection, angle between directions, and
 * the forward vector a yaw/pitch pair produces.
 *
 * These replace the 2D disc `contains` test (§3), so the four ray–sphere cases the spec names are pinned
 * explicitly — a ray that hits, one that misses pointing away, a tangent graze, and a ray whose origin is
 * inside the sphere — plus the "behind the camera" case that must be a miss. Angles are checked in
 * degrees because that is the engine's resolution-independent currency.
 */
class Geometry3DTest {

    private val eps = 1e-3f

    @Test
    fun `a ray pointing at a sphere hits at the near surface`() {
        // Sphere of radius 1 centred 5 ahead down -Z; ray from origin along -Z hits at distance 4.
        val ray = Ray.of(Vec3.ZERO, Vec3(0f, 0f, -1f))
        val hit = intersectRaySphere(ray, Vec3(0f, 0f, -5f), 1f)
        assertTrue(hit.hit)
        assertEquals(4f, hit.distance, eps)
    }

    @Test
    fun `a ray pointing away from the sphere misses even though the line would intersect`() {
        // Sphere behind the origin; ray points forward (-Z), away from it.
        val ray = Ray.of(Vec3.ZERO, Vec3(0f, 0f, -1f))
        val hit = intersectRaySphere(ray, Vec3(0f, 0f, 5f), 1f)
        assertFalse(hit.hit)
    }

    @Test
    fun `a tangent ray grazes the sphere and counts as a hit`() {
        // Sphere radius 1 centred at (1,0,-5); a ray down -Z passing at x=1 touches it at one point.
        val ray = Ray.of(Vec3.ZERO, Vec3(0f, 0f, -1f))
        val hit = intersectRaySphere(ray, Vec3(1f, 0f, -5f), 1f)
        assertTrue("a tangent ray should graze", hit.hit)
        assertEquals(5f, hit.distance, eps)
    }

    @Test
    fun `a ray whose origin is inside the sphere still hits`() {
        // Origin inside a radius-2 sphere centred at the origin; near root is negative, far root positive.
        val ray = Ray.of(Vec3.ZERO, Vec3(0f, 0f, -1f))
        val hit = intersectRaySphere(ray, Vec3.ZERO, 2f)
        assertTrue(hit.hit)
        assertEquals(2f, hit.distance, eps)
    }

    @Test
    fun `a ray missing to the side does not hit`() {
        val ray = Ray.of(Vec3.ZERO, Vec3(0f, 0f, -1f))
        val hit = intersectRaySphere(ray, Vec3(3f, 0f, -5f), 1f)
        assertFalse(hit.hit)
    }

    @Test
    fun `angle between identical directions is zero and opposite is 180`() {
        assertEquals(0f, angleBetweenDegrees(Vec3(0f, 0f, -1f), Vec3(0f, 0f, -1f)), eps)
        assertEquals(180f, angleBetweenDegrees(Vec3(0f, 0f, -1f), Vec3(0f, 0f, 1f)), eps)
        assertEquals(90f, angleBetweenDegrees(Vec3(1f, 0f, 0f), Vec3(0f, 0f, -1f)), eps)
    }

    @Test
    fun `angle never returns NaN for near-parallel directions`() {
        // A dot product a hair past 1 must not produce NaN through acos.
        val a = Vec3(0f, 0f, -1f)
        val b = Vec3(0.0000001f, 0f, -1f)
        val angle = angleBetweenDegrees(a, b)
        assertTrue("angle was NaN", !angle.isNaN())
        assertEquals(0f, angle, 0.01f)
    }

    @Test
    fun `forward from zero angles looks down negative Z`() {
        val f = forwardFromAngles(0f, 0f)
        assertEquals(0f, f.x, eps)
        assertEquals(0f, f.y, eps)
        assertEquals(-1f, f.z, eps)
    }

    @Test
    fun `positive pitch lifts the forward vector and positive yaw turns it toward positive X`() {
        val up = forwardFromAngles(0f, 45f)
        assertTrue("pitch up should raise Y", up.y > 0.6f)
        val right = forwardFromAngles(90f, 0f)
        assertEquals("yaw 90 should look toward +X", 1f, right.x, eps)
        assertEquals(0f, right.z, eps)
    }

    @Test
    fun `forward is always unit length across a sweep`() {
        var yaw = -180f
        while (yaw <= 180f) {
            var pitch = -89f
            while (pitch <= 89f) {
                val len = forwardFromAngles(yaw, pitch).length
                assertEquals("not unit at yaw=$yaw pitch=$pitch", 1f, len, eps)
                pitch += 17f
            }
            yaw += 23f
        }
    }
}
