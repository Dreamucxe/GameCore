package com.gamecore.ui.touch

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.input.TouchSamplingMonitor
import com.gamecore.data.preferences.SecurePreferenceStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The Touch Sampling Monitor screen's state, from GameCore's own capture pad and from nowhere else.
 *
 * Its shape is the touch heatmap's, for the same reason: a finger dragging across the pad produces move
 * events at the display's rate, and publishing a new state per event would recompose the whole screen a
 * hundred times a second. So [onSamples] folds each delivered event's timestamps into [TouchSamplingMonitor]
 * — an immutable value swap on the main thread, no state allocation — and a pulse coroutine rebuilds the
 * snapshot [REFRESH_MILLIS] apart. The pulse also feeds the monitor an empty "tick" so a finger held still
 * lets the observed rate fall back to "move to measure" rather than freeze on its last cadence.
 *
 * The pulse is inside the shared state, so it runs only while the screen is collected: navigating away
 * stops the rebuild loop and, with it, the capture. Nothing here is persisted — the monitor lives only in
 * memory, and the one setting the screen reads, [SecurePreferenceStore] `touchSamplingEnabled`, it does not
 * write. When that setting is off the pad never listens and the screen shows a disabled state.
 */
@HiltViewModel
class SamplingMonitorViewModel @Inject constructor(
    private val preferences: SecurePreferenceStore,
) : ViewModel() {

    /**
     * The immutable accumulator, replaced on the main thread by [onSamples] and by the pulse's clock tick.
     * Volatile defensively, exactly as the touch heatmap's revision counter is: both writers run on the
     * main dispatcher, so a torn read is not actually reachable, but the field is shared across coroutines.
     */
    @Volatile
    private var monitor = TouchSamplingMonitor.EMPTY

    private val capturing = MutableStateFlow(false)

    private val pulses = flow {
        var tick = 0L
        while (true) {
            if (capturing.value) {
                // Empty array = a clock tick: advances the monitor's "now" without recording a sample, so
                // an idle finger stops being reported as if it were still moving.
                monitor = monitor.record(NO_SAMPLES, SystemClock.uptimeMillis())
            }
            emit(tick++)
            delay(REFRESH_MILLIS)
        }
    }

    val state: StateFlow<SamplingMonitorState> =
        combine(capturing, preferences.settings, pulses) { isCapturing, settings, _ ->
            val enabled = settings.touchSamplingEnabled
            SamplingMonitorState(
                enabled = enabled,
                isCapturing = isCapturing && enabled,
                deliveredEventRate = monitor.deliveredEventRatePerSecond,
                observedSampleRateHz = monitor.observedSampleRateHz,
                hardwareTouchLatency = monitor.hardwareTouchLatency,
                inputProcessingDelay = monitor.inputProcessingDelayMillis,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIBE_GRACE_MILLIS),
            initialValue = SamplingMonitorState(),
        )

    /**
     * One delivered pointer event's sample timestamps, newest last, from the pad's own pointer loop.
     *
     * [eventTimesMillis] is the change's `historical` sample uptimes followed by its own `uptimeMillis`, and
     * [nowMillis] is the clock reading when the event was handled. Every event here was dispatched to that
     * composable by Android; there is no path in this class that receives one from anywhere else. The guard
     * is not only tidiness — while capture is off the pad declines the gesture, so the list scrolls under
     * the finger instead of the pad swallowing it.
     */
    fun onSamples(eventTimesMillis: LongArray, nowMillis: Long) {
        if (!capturing.value) return
        monitor = monitor.record(eventTimesMillis, nowMillis)
    }

    /** Starts or stops the pad. Starting clears the accumulator so each run measures its own fresh window. */
    fun setCapturing(isCapturing: Boolean) {
        if (isCapturing == capturing.value) return
        if (isCapturing) monitor = TouchSamplingMonitor.EMPTY
        capturing.value = isCapturing
    }

    private companion object {
        /** About fifteen rebuilds a second, which reads as live and is a fraction of the event rate. */
        const val REFRESH_MILLIS = 66L

        const val SUBSCRIBE_GRACE_MILLIS = 500L

        val NO_SAMPLES = LongArray(0)
    }
}
