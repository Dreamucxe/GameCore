package com.gamecore.service.replay

import com.gamecore.core.model.ThermalClassifier
import com.gamecore.domain.gaming.replay.ClockedReplayThermalMachine
import com.gamecore.domain.gaming.replay.ReplayThermalDecision
import com.gamecore.domain.gaming.replay.ReplayThermalState
import com.gamecore.domain.gaming.replay.SystemReplayClock
import com.gamecore.domain.monitoring.PerformanceMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Thermal auto-pause/resume of the buffer (audit A7), wiring the live metrics to the pure state machine.
 *
 * It collects [PerformanceMonitor.snapshots] (the one shared sampling loop; nulls — the pre-first-sample
 * state — are skipped), turns each into a [com.gamecore.core.model.ThermalClass] through the app-unified
 * [ThermalClassifier], and drives a [ClockedReplayThermalMachine] tick by tick. The machine keys strictly on
 * `CRITICAL` with min-dwell and anti-flap, and only ever emits [ReplayThermalDecision.Pause] /
 * [ReplayThermalDecision.Resume] on a real transition — those are handed to [onDecision] so the service can
 * pause or resume the encoder. It never stops the buffer: the user's setting is kept while paused.
 *
 * Collecting the snapshots is also what keeps the metrics loop alive while Instant Replay runs; [stop]
 * cancels the collection and lets the loop wind down.
 */
class ReplayThermalCollector(
    private val monitor: PerformanceMonitor,
    private val scope: CoroutineScope,
    private val machine: ClockedReplayThermalMachine = ClockedReplayThermalMachine(SystemReplayClock()),
    private val onDecision: (ReplayThermalDecision) -> Unit,
) {
    private var job: Job? = null
    private var state = ReplayThermalState()

    /** Begins collecting snapshots and driving the machine. Idempotent — a second call is a no-op. */
    fun start() {
        if (job != null) return
        job = scope.launch {
            monitor.snapshots.filterNotNull().collect { snapshot ->
                val thermalClass = ThermalClassifier.classify(snapshot).level
                val (next, decision) = machine.evaluate(state, thermalClass)
                state = next
                when (decision) {
                    is ReplayThermalDecision.Pause -> onDecision(decision)
                    is ReplayThermalDecision.Resume -> onDecision(decision)
                    ReplayThermalDecision.NoChange -> Unit
                }
            }
        }
    }

    /** Stops collecting. The buffer's paused/running state is left as-is; the service owns that teardown. */
    fun stop() {
        job?.cancel()
        job = null
    }
}
