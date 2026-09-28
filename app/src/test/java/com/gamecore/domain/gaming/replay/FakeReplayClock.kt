package com.gamecore.domain.gaming.replay

/**
 * A [ReplayClock] whose time the tests set by hand, so every replay decision is driven at a known instant
 * without touching the wall clock. Mirrors the "fake time" style the thermal machine tests use with a plain
 * `nowMillis` argument, here behind the injected-clock seam that [ClockedReplayThermalMachine] consumes.
 */
class FakeReplayClock(var now: Long = 0L) : ReplayClock {
    override fun nowMillis(): Long = now

    /** Advance the clock and return the new time, for readable arrange steps. */
    fun advance(byMillis: Long): Long {
        now += byMillis
        return now
    }
}
