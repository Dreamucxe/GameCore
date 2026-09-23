package com.gamecore.core.common

/**
 * A fixed-capacity ring buffer of `Float` samples, backing the Home screen's
 * Sparklines (redesign §4).
 *
 * The Sparkline shows only what happened while Home was resumed: samples arrive
 * one at a time via [add], the oldest are dropped once [capacity] is reached, and
 * [clear] wipes the lot when Home stops. There are no timers and no threads here —
 * this is purely the data structure; whoever owns it decides when to sample.
 *
 * Backed by a single [FloatArray] with a [head] and a [count], so [add] never
 * allocates and never grows: it writes one slot and moves two integers. That
 * matters because a Sparkline is redrawn every frame, and [snapshot] — the one
 * call that copies — is the only thing it reads. Keep the per-frame path off the
 * allocating call: sample into the buffer as data arrives, and take a [snapshot]
 * once per redraw, not per point.
 *
 * Not thread-safe. Sampling and reading are expected to happen on the same
 * (main) thread, which is where Home's lifecycle callbacks and Compose recomposition
 * already live.
 */
class SampleRingBuffer(val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
    }

    private val data = FloatArray(capacity)

    /** Index of the oldest sample. Only meaningful while [count] > 0. */
    private var head = 0

    /** Number of live samples, in `0..capacity`. */
    private var count = 0

    /** How many samples are currently held, never more than [capacity]. */
    val size: Int get() = count

    fun isEmpty(): Boolean = count == 0

    fun isFull(): Boolean = count == capacity

    /**
     * Appends [value]. Once the buffer is full this overwrites the oldest sample
     * (FIFO eviction), so [size] rises to [capacity] and then holds there. No
     * allocation: it writes one array slot and advances the indices.
     */
    fun add(value: Float) {
        // Next write slot is head + count, wrapped. Fits exactly whether the
        // buffer is filling (write past the end) or full (overwrite the oldest).
        val writeIndex = (head + count) % capacity
        data[writeIndex] = value
        if (count == capacity) {
            // Full: the slot we just wrote was the oldest, so the new oldest is
            // the one after it.
            head = (head + 1) % capacity
        } else {
            count++
        }
    }

    /**
     * The current samples in oldest→newest order, as a fresh [List] copy — not a
     * live view, so the caller may hold or mutate it without touching the buffer.
     *
     * This is the only call that allocates, which is deliberate: the Sparkline
     * reads through here once per redraw, while [add] on the sampling path stays
     * allocation-free.
     */
    fun snapshot(): List<Float> {
        val out = ArrayList<Float>(count)
        for (i in 0 until count) {
            out.add(data[(head + i) % capacity])
        }
        return out
    }

    /** The newest sample, or `null` when empty. */
    fun latest(): Float? {
        if (count == 0) return null
        return data[(head + count - 1) % capacity]
    }

    /**
     * Empties the buffer: [size] returns to 0 and [snapshot] to an empty list.
     * Called when Home stops. The backing array is not reallocated; stale slots
     * are simply out of [count]'s reach.
     */
    fun clear() {
        head = 0
        count = 0
    }
}
