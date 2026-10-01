package com.gamecore.ui.touch

import com.gamecore.core.common.Observed
import com.gamecore.core.input.TouchSamplingMonitor

/**
 * Everything the Touch Sampling Monitor screen draws, and nothing it does not.
 *
 * The four metrics arrive already wrapped in [Observed], so an absent figure cannot reach the screen as a
 * zero — the card renders the reason instead. The defaults are [TouchSamplingMonitor.EMPTY]'s own readings,
 * which is why a freshly opened screen shows "…"/"—" honestly rather than a fabricated starting value.
 *
 * [isCapturing] is the live state of the pad's pointer loop, held only in memory; [enabled] mirrors the
 * user's `touchSamplingEnabled` setting, and when it is off the screen shows a disabled state and the pad
 * never listens.
 */
data class SamplingMonitorState(
    val enabled: Boolean = true,
    val isCapturing: Boolean = false,
    val deliveredEventRate: Observed<Float> = TouchSamplingMonitor.EMPTY.deliveredEventRatePerSecond,
    val observedSampleRateHz: Observed<Float> = TouchSamplingMonitor.EMPTY.observedSampleRateHz,
    val hardwareTouchLatency: Observed<*> = TouchSamplingMonitor.EMPTY.hardwareTouchLatency,
    val inputProcessingDelay: Observed<Float> = TouchSamplingMonitor.EMPTY.inputProcessingDelayMillis,
)
