package com.gamecore.domain.gaming.replay

/**
 * The one time source the Instant Replay pure logic is allowed to read.
 *
 * §0 asks the replay decisions to be pure and driven by an *injected* clock so a unit test can move time
 * by hand. The app already has two other clocks — `aimlab.engine.Clock` (Aim Lab only, not Hilt-wired) and
 * the plain `nowMillis: Long` every thermal machine already takes — and dragging either into this package
 * would either couple it to Aim Lab or add nothing. So this package defines its own tiny seam: an interface
 * with a single system-backed implementation, and a `FakeReplayClock` in the test source that tests advance.
 *
 * Nothing here touches `android.*`; [SystemReplayClock] calls `java.lang.System`, which is available in a
 * plain JVM unit test, so even the "real" clock does not force an instrumented test.
 */
interface ReplayClock {
    /** Milliseconds since the epoch, the same base every other GameCore machine threads as `nowMillis`. */
    fun nowMillis(): Long
}

/**
 * The production [ReplayClock]. The single allowed seam to wall-clock time; kept to one line so tests never
 * reach for it and reach for a `FakeReplayClock` instead.
 */
class SystemReplayClock : ReplayClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
