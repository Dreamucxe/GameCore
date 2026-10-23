package com.gamecore.ui.sensors

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.sensors.CalibrationResult
import com.gamecore.core.sensors.SensorCalibration
import com.gamecore.core.sensors.SensorTraceReader
import com.gamecore.core.sensors.TraceSample
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Everything the calibration screen draws, in one snapshot.
 *
 * [samples] is the live trace, not a summary of it: the Canvas needs the points themselves, and keeping
 * them here means the screen has no second source of truth for what was captured. [progress] is the
 * fraction of the ten-second window that has elapsed, so a stopped-early capture reports how much of the
 * intended window it actually covered rather than jumping to one.
 */
data class CalibrationUiState(
    val isCapturing: Boolean = false,
    val progress: Float = 0f,
    val samples: List<TraceSample> = emptyList(),
    val result: CalibrationResult? = null,
)

/**
 * Runs a ten-second capture and hands the resulting trace to [SensorCalibration].
 *
 * The flow is cold and is collected only inside [startCapture], so the sensors stop when the capture
 * ends or [stopCapture] cancels it — there is no monitoring left running behind the screen. Stopping
 * early is not a failure: whatever was collected is analysed, and the sample count in the summary makes
 * a short window visible instead of letting it look like a full one.
 */
@HiltViewModel
class SensorCalibrationViewModel @Inject constructor(
    private val traceReader: SensorTraceReader,
) : ViewModel() {

    private val _state = MutableStateFlow(CalibrationUiState())
    val state: StateFlow<CalibrationUiState> = _state.asStateFlow()

    private var captureJob: Job? = null

    /**
     * Starts a ten-second capture, replacing any previous result.
     *
     * Re-entry is a no-op while a capture is running; the screen uses that same state to render Stop
     * rather than a second Start.
     */
    fun startCapture() {
        if (_state.value.isCapturing) return
        captureJob?.cancel()

        captureJob = viewModelScope.launch {
            _state.value = CalibrationUiState(isCapturing = true)
            val samples = mutableListOf<TraceSample>()
            val startedAt = SystemClock.elapsedRealtime()

            val ticker = launch {
                while (isActive) {
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    val progress = (elapsed.toFloat() / CAPTURE_MILLIS.toFloat()).coerceIn(0f, 1f)
                    _state.update { it.copy(progress = progress) }
                    if (progress >= 1f) break
                    delay(PROGRESS_TICK_MILLIS)
                }
            }

            try {
                withTimeoutOrNull(CAPTURE_MILLIS) {
                    traceReader.stream().collect { sample ->
                        samples.add(sample)
                        _state.update { it.copy(samples = samples.toList()) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The reader closes the flow on the failures it expects. If something else goes wrong,
                // the samples collected so far are still worth analysing rather than crashing the screen.
            } finally {
                ticker.cancel()
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val progress = (elapsed.toFloat() / CAPTURE_MILLIS.toFloat()).coerceIn(0f, 1f)
                val result = SensorCalibration.analyse(samples)
                _state.update {
                    it.copy(
                        isCapturing = false,
                        progress = progress,
                        samples = samples.toList(),
                        result = result,
                    )
                }
            }
        }
    }

    /**
     * Stops the current capture and analyses the samples already collected.
     *
     * Cancelling the job runs the capture's `finally`, which is the one place analysis happens, so a
     * stopped capture and a completed one produce the same kind of result.
     */
    fun stopCapture() {
        captureJob?.cancel()
    }

    private companion object {
        const val CAPTURE_MILLIS = 10_000L
        const val PROGRESS_TICK_MILLIS = 50L
    }
}
