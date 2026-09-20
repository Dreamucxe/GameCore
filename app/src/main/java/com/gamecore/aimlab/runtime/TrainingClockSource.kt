package com.gamecore.aimlab.runtime

import com.gamecore.aimlab.engine.Clock
import com.gamecore.aimlab.engine.Rng
import com.gamecore.aimlab.engine.SeededRng
import com.gamecore.aimlab.engine.SystemClock

/**
 * The production time and randomness sources for the training runtime.
 *
 * The engine takes a [Clock] and an [Rng] as interfaces so it can be driven deterministically in tests;
 * this is the one place the real, non-deterministic implementations are created. It is a plain factory
 * rather than a Hilt module because [AimTrainingLoop] needs a *fresh* seeded RNG per run (so each session
 * differs, but a given seed reproduces), which a singleton binding cannot express.
 */
object TrainingClockSource {

    /** The real monotonic + wall clock. */
    fun clock(): Clock = SystemClock()

    /** A seeded RNG; pass the run's seed (or a clock-derived one) so the run is reproducible. */
    fun rng(seed: Long): Rng = SeededRng(seed)
}
