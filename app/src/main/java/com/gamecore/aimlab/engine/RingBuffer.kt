package com.gamecore.aimlab.engine

/**
 * A fixed-capacity circular buffer of floats for high-frequency training samples.
 *
 * The engine keeps per-frame data (tracking error, gyro correction, recoil offset) in one of these rather
 * than an unbounded list, for the same reason `MotionTrace` uses flat arrays: at 60–200 Hz an
 * `ArrayList<Float>` would allocate and grow without bound over a long session and eventually be the thing
 * that runs the device out of memory. This never grows and never allocates after construction — a full
 * buffer overwrites its oldest sample.
 *
 * It is not thread-safe. The training loop owns one and writes from a single coroutine; nothing reads it
 * concurrently. Reads for summary happen after the loop has stopped.
 *
 * @param capacity maximum retained samples. Once full, [add] overwrites the oldest.
 */
class FloatRingBuffer(val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val data = FloatArray(capacity)
    private var head = 0
    private var count = 0
    private var total = 0L

    /** How many samples are currently retained (≤ capacity). */
    val size: Int get() = count

    /** Total samples ever added, including ones since overwritten. */
    val totalAdded: Long get() = total

    val isFull: Boolean get() = count == capacity

    fun add(value: Float) {
        data[head] = value
        head = (head + 1) % capacity
        if (count < capacity) count++
        total++
    }

    /** Reads the i-th retained sample, oldest first (0) to newest (size-1). */
    fun get(index: Int): Float {
        require(index in 0 until count) { "index $index out of bounds for size $count" }
        val start = if (count < capacity) 0 else head
        return data[(start + index) % capacity]
    }

    fun clear() {
        head = 0
        count = 0
        total = 0L
    }

    // ---- Aggregates. All defined over retained samples; all safe on an empty buffer. ----

    /** Mean of retained samples, or 0 when empty (never NaN). */
    fun mean(): Float {
        if (count == 0) return 0f
        var sum = 0.0
        for (i in 0 until count) sum += get(i)
        return (sum / count).toFloat()
    }

    /** Largest retained sample, or 0 when empty. */
    fun max(): Float {
        if (count == 0) return 0f
        var m = get(0)
        for (i in 1 until count) {
            val v = get(i)
            if (v > m) m = v
        }
        return m
    }

    /** Smallest retained sample, or 0 when empty. */
    fun min(): Float {
        if (count == 0) return 0f
        var m = get(0)
        for (i in 1 until count) {
            val v = get(i)
            if (v < m) m = v
        }
        return m
    }

    /** Population standard deviation of retained samples, or 0 when empty. A stability measure. */
    fun standardDeviation(): Float {
        if (count == 0) return 0f
        val avg = mean()
        var sq = 0.0
        for (i in 0 until count) {
            val d = get(i) - avg
            sq += d * d
        }
        return kotlin.math.sqrt(sq / count).toFloat()
    }

    /** A snapshot copy of the retained samples, oldest first. For serialization only. */
    fun toList(): List<Float> = List(count) { get(it) }
}
