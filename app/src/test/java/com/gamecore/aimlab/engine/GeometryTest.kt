package com.gamecore.aimlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arena's coordinate math, and the hit test every shot is judged by.
 *
 * [Target.contains] is the single decision that turns a tap into a hit or a miss, so its edge behaviour is
 * not a detail: the source defines the disc as closed (`distance <= radius`), and a shot exactly on the rim
 * counts. That, and the promise that [Vec2.clampToArena] can never let a point leave the unit square, are
 * what this pins down. Every literal here is a dyadic fraction (halves, quarters, eighths) so the expected
 * values are exact in binary and the assertions test the math rather than float rounding.
 */
class GeometryTest {

    private val eps = 1e-6f

    @Test
    fun `vectors add, subtract and scale component-wise`() {
        val sum = Vec2(0.25f, 0.5f) + Vec2(0.5f, 0.125f)
        assertEquals(0.75f, sum.x, eps)
        assertEquals(0.625f, sum.y, eps)

        val difference = Vec2(0.75f, 0.5f) - Vec2(0.25f, 0.125f)
        assertEquals(0.5f, difference.x, eps)
        assertEquals(0.375f, difference.y, eps)

        val scaled = Vec2(0.25f, 0.5f) * 3f
        assertEquals(0.75f, scaled.x, eps)
        assertEquals(1.5f, scaled.y, eps)

        val flipped = Vec2(0.25f, -0.5f) * -1f
        assertEquals(-0.25f, flipped.x, eps)
        assertEquals(0.5f, flipped.y, eps)

        assertEquals(Vec2(0f, 0f), Vec2(0.3f, 0.7f) * 0f)
        // Adding then subtracting the same vector returns the original.
        val original = Vec2(0.375f, 0.625f)
        assertEquals(original, original + Vec2(0.125f, 0.25f) - Vec2(0.125f, 0.25f))
    }

    @Test
    fun `length is the euclidean norm and zero only at the origin`() {
        assertEquals(5f, Vec2(3f, 4f).length, eps)
        assertEquals(0f, Vec2(0f, 0f).length, eps)
        assertEquals(0.5f, Vec2(-0.5f, 0f).length, eps)
        assertEquals(0.5f, Vec2(0f, 0.5f).length, eps)
        // Scaling a vector scales its length by the same factor.
        assertEquals(Vec2(0.3f, 0.4f).length * 2f, (Vec2(0.3f, 0.4f) * 2f).length, eps)
    }

    @Test
    fun `distance is symmetric, zero to itself, and agrees with the length of the difference`() {
        val a = Vec2(0.1f, 0.2f)
        val b = Vec2(0.4f, 0.6f)
        assertEquals(0.5f, a.distanceTo(b), eps)
        assertEquals(a.distanceTo(b), b.distanceTo(a), eps)
        assertEquals(0f, a.distanceTo(a), eps)
        assertEquals((a - b).length, a.distanceTo(b), eps)
        // Axis-aligned distance is just the coordinate difference.
        assertEquals(0.25f, Vec2(0.25f, 0.5f).distanceTo(Vec2(0.5f, 0.5f)), eps)
    }

    @Test
    fun `the centre constant really is the middle of the arena`() {
        assertEquals(0.5f, Vec2.CENTER.x, 0f)
        assertEquals(0.5f, Vec2.CENTER.y, 0f)
        assertEquals(Vec2(0.5f, 0.5f), Vec2.CENTER)
        assertEquals(0.5f, Vec2.CENTER.distanceTo(Vec2(0f, 0.5f)), eps)
        // Equidistant from all four corners.
        val corners = listOf(Vec2(0f, 0f), Vec2(1f, 0f), Vec2(0f, 1f), Vec2(1f, 1f))
        for (corner in corners) assertEquals(0.70710678f, Vec2.CENTER.distanceTo(corner), 1e-5f)
    }

    @Test
    fun `clamping keeps a point inside the unit square, inset by the margin`() {
        // Already inside: untouched.
        assertEquals(Vec2(0.4f, 0.6f), Vec2(0.4f, 0.6f).clampToArena())
        assertEquals(Vec2(0.4f, 0.6f), Vec2(0.4f, 0.6f).clampToArena(margin = 0.1f))

        // Outside on both axes, in both directions.
        assertEquals(Vec2(1f, 0f), Vec2(1.5f, -0.3f).clampToArena())
        assertEquals(Vec2(0f, 1f), Vec2(-4f, 9f).clampToArena())

        val inset = Vec2(1.5f, -0.3f).clampToArena(margin = 0.1f)
        assertEquals(0.9f, inset.x, eps)
        assertEquals(0.1f, inset.y, eps)

        // A margin of half the arena leaves exactly one legal point.
        assertEquals(Vec2.CENTER, Vec2(0.01f, 0.99f).clampToArena(margin = 0.5f))
    }

    @Test
    fun `a target contains its centre, points inside, and points exactly on the rim`() {
        val target = Target(id = 1L, center = Vec2(0.5f, 0.5f), radius = 0.25f, spawnedAtNanos = 0L)
        assertTrue("centre is a miss", target.contains(Vec2(0.5f, 0.5f)))
        assertTrue(target.contains(Vec2(0.6f, 0.6f)))
        assertTrue("just inside the rim", target.contains(Vec2(0.74f, 0.5f)))
        // The disc is closed: distance == radius is a hit, on either axis.
        assertTrue("rim on x is a miss", target.contains(Vec2(0.75f, 0.5f)))
        assertTrue("rim on y is a miss", target.contains(Vec2(0.5f, 0.75f)))
        assertTrue("rim on the near side is a miss", target.contains(Vec2(0.25f, 0.5f)))
    }

    @Test
    fun `a target does not contain points past its radius`() {
        val target = Target(id = 1L, center = Vec2(0.5f, 0.5f), radius = 0.25f, spawnedAtNanos = 0L)
        assertFalse("just outside the rim", target.contains(Vec2(0.76f, 0.5f)))
        assertFalse(target.contains(Vec2(0.5f, 0.1f)))
        // Inside the bounding box but outside the disc: the corner of the square, at distance 0.354.
        assertFalse("hit test is a square, not a disc", target.contains(Vec2(0.75f, 0.75f)))
        assertFalse(target.contains(Vec2(0f, 0f)))
    }

    @Test
    fun `a zero-radius target is hit only at its exact centre`() {
        val point = Target(id = 2L, center = Vec2(0.25f, 0.75f), radius = 0f, spawnedAtNanos = 0L)
        assertTrue(point.contains(Vec2(0.25f, 0.75f)))
        assertFalse(point.contains(Vec2(0.26f, 0.75f)))
        assertFalse(point.contains(Vec2(0.25f, 0.76f)))
    }

    @Test
    fun `a target stands still unless it is given a velocity`() {
        val still = Target(id = 3L, center = Vec2.CENTER, radius = 0.05f, spawnedAtNanos = 1_234L)
        assertEquals(Vec2(0f, 0f), still.velocity)
        assertEquals(0f, still.velocity.length, eps)
        assertEquals(1_234L, still.spawnedAtNanos)

        // Velocity is arena units per second, so a second of travel is the velocity itself.
        val moving = still.copy(velocity = Vec2(0.25f, -0.125f))
        val afterOneSecond = moving.center + moving.velocity * 1f
        assertEquals(0.75f, afterOneSecond.x, eps)
        assertEquals(0.375f, afterOneSecond.y, eps)
    }
}
