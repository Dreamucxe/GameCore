package com.gamecore.aimlab.engine

/**
 * A source of monotonic time for the training engine.
 *
 * The engine never calls `System.nanoTime()` or `System.currentTimeMillis()` directly. Every elapsed
 * time, reaction time and per-tick delta is measured against an injected clock, so a test can drive the
 * engine through a scripted timeline and assert exact numbers rather than sleep and hope.
 *
 * [nowMillis] is wall-clock epoch milliseconds, used only for timestamps that get stored or displayed.
 * [elapsedNanos] is a monotonic counter with no defined zero — only differences between two readings are
 * meaningful — and is what every duration in the engine is derived from, because wall-clock can jump
 * backwards across an NTP correction and a reaction time must never come out negative.
 */
interface Clock {
    fun nowMillis(): Long
    fun elapsedNanos(): Long
}

/**
 * The production clock, and the only place in the engine allowed to read the platform's time.
 *
 * Lives in the engine package rather than the Android layer because it has no `android.*` dependency —
 * `System` is plain JVM — and keeping it here means the Android layer wires nothing just to get real time.
 */
class SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun elapsedNanos(): Long = System.nanoTime()
}

/**
 * A clock a test drives by hand. Not used in production.
 *
 * [advanceMillis] moves both the wall clock and the monotonic counter forward together, which is the
 * common case; a test that needs them to diverge can set the fields directly.
 */
class FakeClock(
    var wallMillis: Long = 0L,
    var monotonicNanos: Long = 0L,
) : Clock {
    override fun nowMillis(): Long = wallMillis
    override fun elapsedNanos(): Long = monotonicNanos

    fun advanceMillis(millis: Long) {
        wallMillis += millis
        monotonicNanos += millis * 1_000_000L
    }

    fun advanceNanos(nanos: Long) {
        monotonicNanos += nanos
        wallMillis += nanos / 1_000_000L
    }
}
