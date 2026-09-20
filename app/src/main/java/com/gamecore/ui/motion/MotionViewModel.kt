package com.gamecore.ui.motion

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.sensors.MotionRecorder
import com.gamecore.core.sensors.MotionSampler
import com.gamecore.data.repository.DiagnosticsExport
import com.gamecore.data.repository.DiagnosticsExporter
import com.gamecore.data.repository.DiagnosticsFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Gyroscope and accelerometer, live, with one recording behind them.
 *
 * The sensors are registered by *collection* and by nothing else. [MotionSampler.stream] registers its
 * listeners when the flow is collected and unregisters them in `awaitClose`, and the state below is shared
 * with [SharingStarted.WhileSubscribed], so the screen going away takes the listeners with it. There is no
 * `onPause` to forget to call and no service involved: a screen that is not on top costs this device
 * nothing, which is the whole point of a gaming tool that reads a sensor at 50 Hz.
 *
 * The sampler splits the two rates on purpose. [MotionSampler.stream] emits a frame for the UI about
 * fifteen times a second — fast enough to read, slow enough not to recompose the screen at sensor rate —
 * while [MotionRecorder] is handed every raw sample as a [com.gamecore.core.sensors.MotionSink] on the
 * sensor's own thread. So the plot is a summary and the export is the telemetry, and neither is the other
 * pretending.
 *
 * The recorder accumulates the aim figures whenever sampling is running, not only while recording. That is
 * why the analysis card has numbers in it before the user presses Record, and why [MotionRecorder.start]
 * resets them: a session summary that included the two minutes before the session started would be a
 * different measurement wearing the same label.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MotionViewModel @Inject constructor(
    private val sampler: MotionSampler,
    private val exporter: DiagnosticsExporter,
) : ViewModel() {

    /**
     * The recorder outlives any one collection of the stream.
     *
     * Held here rather than created per stream so that a recording survives the sampling rate being
     * changed mid-capture — the flow restarts, the sink is the same object, and the trace keeps growing.
     */
    private val recorder = MotionRecorder()

    private val local = MutableStateFlow(MotionUiState())

    /**
     * Live motion, restarted when the rate changes and stopped when nobody is looking.
     *
     * `runningFold` rather than `scan`: the fold emits its seed immediately, so a device with no motion
     * sensors at all still produces a state — the one that renders the empty explanation — instead of a
     * screen that waits forever for a first frame that is never coming.
     */
    private val live = local
        .map { it.rate }
        .distinctUntilChanged()
        .flatMapLatest { rate -> sampler.stream(rateMicros = rate.micros, sink = recorder) }
        .runningFold(MotionLive.EMPTY) { trails, frame -> trails.add(frame) }

    val state: StateFlow<MotionUiState> = combine(local, live) { own, motion ->
        own.copy(
            frame = motion.frame,
            gyroSeries = motion.gyro,
            accelerationSeries = motion.acceleration,
            summary = recorder.summary(),
            recording = recorder.state,
            recordedSamples = recorder.trace.size,
            isTraceFull = recorder.trace.isFull,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STREAM_GRACE_MILLIS),
        initialValue = MotionUiState(),
    )

    init {
        // Read once. A sensor is not added to a phone while the screen is open, and re-reading the
        // descriptors on every resume would be a list of allocations for an answer that cannot change.
        local.value = local.value.copy(
            isLoaded = true,
            hasSensorService = sampler.hasSensorService,
            sensors = sampler.describeAll(),
        )
    }

    // ----------------------------------------------------------------------------- sampling

    /**
     * Changes the requested rate, which restarts the stream.
     *
     * The plots are deliberately not cleared. The window is a hundred and twenty samples either way, and a
     * user switching from Normal to Fastest wants to see the line get denser, not disappear.
     */
    fun setRate(rate: SampleRate) {
        if (rate == local.value.rate) return
        local.value = local.value.copy(rate = rate, message = null)
    }

    // ---------------------------------------------------------------------------- recording

    fun startRecording() {
        recorder.start()
        local.value = local.value.copy(recording = recorder.state, export = null, message = null)
    }

    fun pauseRecording() {
        recorder.pause()
        local.value = local.value.copy(recording = recorder.state, message = PAUSED_NOTE)
    }

    fun resumeRecording() {
        recorder.resume()
        local.value = local.value.copy(recording = recorder.state, message = null)
    }

    fun stopRecording() {
        recorder.stop()
        local.value = local.value.copy(recording = recorder.state, message = null)
    }

    fun clearRecording() {
        recorder.clear()
        local.value = local.value.copy(recording = recorder.state, export = null, message = null)
    }

    // ------------------------------------------------------------------------------ export

    /**
     * Writes the raw trace to GameCore's own storage, in the format the user picked.
     *
     * Nothing leaves the device. The file lands in the app's private files directory and the only way it
     * goes anywhere else is the share sheet below, which the user opens themselves.
     */
    fun export(format: DiagnosticsFormat) {
        if (local.value.isExporting) return
        viewModelScope.launch {
            local.value = local.value.copy(isExporting = true, message = null)
            val result = exporter.exportMotion(
                trace = recorder.trace,
                summary = recorder.summary(),
                sensors = local.value.sensors,
                format = format,
            )
            local.value = local.value.copy(
                isExporting = false,
                export = result,
                message = messageFor(result),
            )
        }
    }

    /** The share sheet's intent for the file just written, or null when there is nothing to share. */
    fun shareIntent(): Intent? {
        val written = local.value.export as? DiagnosticsExport.Written ?: return null
        val uri = written.uri ?: return null
        return Intent(Intent.ACTION_SEND)
            .setType(written.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun onIntentFailed() {
        local.value = local.value.copy(message = NO_SHARE_TARGET)
    }

    fun dismissMessage() {
        local.value = local.value.copy(message = null)
    }

    private fun messageFor(result: DiagnosticsExport): String? = when (result) {
        is DiagnosticsExport.Written -> null
        DiagnosticsExport.Empty -> NOTHING_RECORDED
        is DiagnosticsExport.Failed -> "That file could not be written (${result.detail})."
    }

    private companion object {
        /**
         * A second of grace before the sensors are let go.
         *
         * Long enough that a configuration change the activity did not absorb does not tear the listeners
         * down and put them straight back up; short enough that leaving the screen stops the sampling
         * while the user is still looking at the animation that took them away from it.
         */
        const val STREAM_GRACE_MILLIS = 1_000L

        const val PAUSED_NOTE =
            "Paused. The raw trace and the figures are kept — Stop ends the recording, Clear discards it."

        const val NOTHING_RECORDED =
            "There is nothing recorded yet. Press Record, move the device, then Stop."

        const val NO_SHARE_TARGET =
            "No app on this device offered to take that file. It is still saved inside GameCore."
    }
}
