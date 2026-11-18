package com.gamecore.domain.intelligence

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A single-flight gate for benchmark captures.
 *
 * The ViewModel already disables its start button while a capture runs, but that is UI state, not a
 * guarantee: a second caller, a restored screen or a future automation path could still enter the
 * repository. Two overlapping captures would both read the same baseline and could both claim the
 * same applied action. This gate makes the repository itself refuse the second capture instead.
 */
class BenchmarkCaptureGate {

    private val running = AtomicBoolean(false)

    /** Returns true for the one caller that now owns the capture. */
    fun tryEnter(): Boolean = running.compareAndSet(false, true)

    /** Releases the gate. Safe to call from the capture's `finally` path. */
    fun leave() {
        running.set(false)
    }

    val isRunning: Boolean get() = running.get()
}
