package com.gamecore.aimlab.render

import com.gamecore.aimlab.engine3d.Vec3

/**
 * A fixed-capacity pool of hit-burst particles, updated and drawn with zero per-frame allocation (§2, §4).
 *
 * A hit pops a small cloud of short-lived particles. Rather than allocate a list of particle objects each
 * time — which is exactly the per-frame garbage the spec forbids — the pool preallocates parallel float
 * arrays for position, velocity and remaining life and simply marks slots live or dead. [spawnBurst]
 * reuses the oldest dead slots; [update] ages every live particle; the renderer reads the live ones by
 * index. When the pool is full the newest burst overwrites the oldest particles, which is invisible at
 * these lifetimes.
 */
class ParticlePool(private val capacity: Int = 128) {

    private val px = FloatArray(capacity)
    private val py = FloatArray(capacity)
    private val pz = FloatArray(capacity)
    private val vx = FloatArray(capacity)
    private val vy = FloatArray(capacity)
    private val vz = FloatArray(capacity)
    private val life = FloatArray(capacity)   // seconds remaining; <=0 means dead
    private var cursor = 0

    /**
     * Spawns [count] particles at [origin] with pseudo-random velocities.
     *
     * The randomness is a cheap deterministic hash of the slot index, not an RNG allocation — the burst
     * only needs to look scattered, and keeping it allocation-free matters more than true randomness here.
     */
    fun spawnBurst(origin: Vec3, count: Int = 12) {
        repeat(count.coerceAtMost(capacity)) {
            val i = cursor
            cursor = (cursor + 1) % capacity
            px[i] = origin.x; py[i] = origin.y; pz[i] = origin.z
            // Spread velocities over a sphere using the slot index as a cheap angle source.
            val a = i * 2.399963f            // golden-angle-ish, avoids clustering
            val b = i * 0.7654f
            vx[i] = kotlin.math.cos(a) * 1.6f
            vy[i] = kotlin.math.sin(b) * 1.6f + 0.4f
            vz[i] = kotlin.math.sin(a) * 1.6f
            life[i] = PARTICLE_LIFE
        }
    }

    /** Ages every live particle by [dt] seconds, applying a little gravity. No allocation. */
    fun update(dt: Float) {
        for (i in 0 until capacity) {
            if (life[i] <= 0f) continue
            life[i] -= dt
            px[i] += vx[i] * dt
            py[i] += vy[i] * dt
            pz[i] += vz[i] * dt
            vy[i] -= GRAVITY * dt
        }
    }

    /** Invokes [action] for each live particle with its position and normalised remaining life (1→0). */
    inline fun forEachLive(action: (x: Float, y: Float, z: Float, lifeFraction: Float) -> Unit) {
        for (i in 0 until liveCapacity) {
            val l = lifeAt(i)
            if (l > 0f) action(xAt(i), yAt(i), zAt(i), l / PARTICLE_LIFE)
        }
    }

    // Accessors kept internal so forEachLive (inline) can read the arrays without exposing them mutably.
    val liveCapacity: Int get() = capacity
    fun lifeAt(i: Int): Float = life[i]
    fun xAt(i: Int): Float = px[i]
    fun yAt(i: Int): Float = py[i]
    fun zAt(i: Int): Float = pz[i]

    fun clear() {
        for (i in 0 until capacity) life[i] = 0f
        cursor = 0
    }

    companion object {
        const val PARTICLE_LIFE = 0.45f
        const val GRAVITY = 5f
    }
}

/**
 * A fixed-capacity FIFO ring of wall bullet-hole decals (§4: miss → bullet hole; recoil grouping).
 *
 * Decals are permanent for the run but capped, so an eventual overwrite of the oldest keeps memory and
 * draw count bounded. Positions only; the renderer draws a small dark quad facing the player at each.
 */
class DecalRing(private val capacity: Int = 32) {
    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private val zs = FloatArray(capacity)
    private var count = 0
    private var head = 0

    fun add(p: Vec3) {
        val i = head
        xs[i] = p.x; ys[i] = p.y; zs[i] = p.z
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    inline fun forEach(action: (x: Float, y: Float, z: Float) -> Unit) {
        for (i in 0 until liveCount) action(xAt(i), yAt(i), zAt(i))
    }

    val liveCount: Int get() = count
    fun xAt(i: Int): Float = xs[i]
    fun yAt(i: Int): Float = ys[i]
    fun zAt(i: Int): Float = zs[i]

    fun clear() {
        count = 0
        head = 0
    }
}
