package com.gamecore.ui.controller

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.controller.AxisNormalizer
import com.gamecore.core.controller.AxisRange
import com.gamecore.core.controller.ControllerSignature
import com.gamecore.core.controller.ControllerTestResult
import com.gamecore.core.controller.DriftSample
import com.gamecore.core.controller.DriftThresholds
import com.gamecore.core.controller.ResponseCurve
import com.gamecore.core.controller.ResponseCurveSettings
import com.gamecore.core.controller.StickDriftAnalyzer
import com.gamecore.core.input.ControllerAxis
import com.gamecore.core.input.ControllerDevice
import com.gamecore.core.input.ControllerInputBus
import com.gamecore.core.input.ControllerInputSnapshot
import com.gamecore.core.input.ControllerReader
import com.gamecore.data.repository.ControllerTestResultRepository
import com.gamecore.data.repository.ControllerWriteResult
import com.gamecore.data.repository.DiagnosticsExport
import com.gamecore.data.repository.DiagnosticsExporter
import com.gamecore.data.repository.DiagnosticsFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The controller lab: the device list, and the live input behind it.
 *
 * Capture is tied to collection and to nothing else. The pulse flow below turns the input bus on when the
 * screen starts collecting and off when it stops, so a controller's buttons are claimed by this app only
 * while the lab is actually on screen. Everywhere else in GameCore the events pass straight through as they
 * always did — which is why `MainActivity` can hand every key event to the bus unconditionally.
 *
 * Two rates again. A pushed stick produces a motion event per display frame per axis and those land in the
 * bus's arrays without allocating; this class reads one immutable snapshot from it every [POLL_MILLIS] and
 * publishes that. `distinctUntilChanged` on the snapshot means a controller sitting untouched costs one
 * array comparison fifteen times a second and no recomposition at all.
 *
 * The device list is re-read when Android says a device was added, removed or reconfigured, rather than on
 * a timer: `InputDevice` cannot change on its own.
 */
@HiltViewModel
class ControllerViewModel @Inject constructor(
    private val reader: ControllerReader,
    private val bus: ControllerInputBus,
    private val exporter: DiagnosticsExporter,
    private val testResults: ControllerTestResultRepository,
) : ViewModel() {

    private val local = MutableStateFlow(ControllerUiState())

    /** The drift run currently sampling, so a new run or a device change cancels the old one deterministically. */
    private var driftJob: Job? = null

    /** Captured at start: a mid-run unplug must not move the result onto whichever device is next. */
    private var driftDevice: ControllerDevice? = null
    private var driftSignature: String = ""
    private var driftSlot: StickSlot = StickSlot.LEFT
    private var driftStartedAtMillis: Long = 0L
    private var driftRequestedMillis: Long = 0L
    private var driftFlatFloor: Float = 0f
    private var driftFuzzFloor: Float = 0f

    /** Bumped by Refresh. The device flow also re-reads on the platform's own device-change callback. */
    private val reloads = MutableStateFlow(0)

    private val devices = combine(
        reader.deviceChanges().onStart { emit(Unit) },
        reloads,
        local.map { it.showAll }.distinctUntilChanged(),
    ) { _, _, showAll -> showAll }
        .map { showAll -> if (showAll) reader.allDevices() else reader.controllers() }

    /**
     * The live input state, polled while — and only while — somebody is collecting.
     *
     * `onStart`/`onCompletion` rather than an `init` block and an `onCleared`: the ViewModel outlives the
     * screen being visible, and a lab that kept claiming gamepad buttons after the user navigated away
     * would break the controller everywhere else in the app.
     */
    private val live: Flow<ControllerInputSnapshot> = flow {
        while (true) {
            emit(bus.snapshot())
            delay(POLL_MILLIS)
        }
    }
        .onStart { bus.startCapture() }
        .onCompletion { bus.stopCapture() }
        .distinctUntilChanged()

    val state: StateFlow<ControllerUiState> = combine(local, devices, live) { own, list, snapshot ->
        own.copy(
            isLoaded = true,
            hasInputService = reader.hasInputService,
            devices = list,
            snapshot = snapshot,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(CAPTURE_GRACE_MILLIS),
        initialValue = ControllerUiState(),
    )

    // ------------------------------------------------------------------------------ selection

    fun refresh() {
        reloads.value = reloads.value + 1
    }

    fun select(deviceId: Int) {
        if (deviceId == local.value.selectedId) return
        cancelDrift(quiet = true)
        local.value = local.value.copy(selectedId = deviceId, message = null)
    }

    fun setTool(tool: ControllerTool) {
        if (tool == local.value.tool) return
        local.value = local.value.copy(tool = tool)
    }

    fun setLabTool(tool: ControllerLabTool) {
        if (tool == local.value.labTool) return
        local.value = local.value.copy(labTool = tool)
    }

    fun setShowAll(showAll: Boolean) {
        if (showAll == local.value.showAll) return
        cancelDrift(quiet = true)
        local.value = local.value.copy(showAll = showAll, selectedId = null)
    }

    // ---------------------------------------------------------------------------------- drift

    /** Sticks only. A hat is a set of switches, and a drift figure for it would be a category error. */
    fun setDriftStick(slot: StickSlot) {
        if (slot == StickSlot.HAT || local.value.drift.running) return
        local.value = local.value.copy(
            drift = local.value.drift.copy(
                stick = slot,
                deviceId = null,
                result = null,
                progressResult = null,
                saved = false,
                sampleCount = 0,
                elapsedMillis = 0L,
            ),
        )
    }

    fun setDriftDuration(seconds: Int) {
        if (local.value.drift.running) return
        local.value = local.value.copy(
            drift = local.value.drift.copy(
                durationSeconds = seconds.coerceIn(MIN_DRIFT_SECONDS, MAX_DRIFT_SECONDS),
                deviceId = null,
                result = null,
                progressResult = null,
                saved = false,
                sampleCount = 0,
                elapsedMillis = 0L,
            ),
        )
    }

    fun setDriftOffsetThreshold(value: Float) {
        if (local.value.drift.running) return
        local.value = local.value.copy(
            drift = local.value.drift.copy(
                offsetThreshold = value.coerceIn(0f, MAX_DRIFT_OFFSET),
                deviceId = null,
                result = null,
                progressResult = null,
                saved = false,
            ),
        )
    }

    fun setDriftJitterThreshold(value: Float) {
        if (local.value.drift.running) return
        local.value = local.value.copy(
            drift = local.value.drift.copy(
                jitterThreshold = value.coerceIn(0f, MAX_DRIFT_JITTER),
                deviceId = null,
                result = null,
                progressResult = null,
                saved = false,
            ),
        )
    }

    /**
     * Starts a resting-stick run.
     *
     * Samples are taken only when the controller has sent a **new** motion event. Polling the same held
     * snapshot fifty times a second would manufacture a sample count and understate jitter: identical
     * repeated values look like a perfectly quiet stick even when the controller stopped sending. A run
     * that produces too few fresh events ends as NOT_ENOUGH_DATA, not as a clean bill of health.
     *
     * The device, its declared ranges and its signature are captured at the start. If the controller is
     * unplugged mid-run, the samples simply stop; the result is inconclusive and is not attributed to
     * whatever device the screen falls back to.
     */
    fun startDrift() {
        if (local.value.drift.running) return
        val device = state.value.selected ?: run {
            local.value = local.value.copy(message = NO_CONTROLLER_FOR_DRIFT)
            return
        }
        val slot = local.value.drift.stick
        if (slot == StickSlot.HAT) {
            local.value = local.value.copy(message = HAT_NOT_DRIFTABLE)
            return
        }
        val xAxis = device.axisFor(slot.xAxis)
        val yAxis = device.axisFor(slot.yAxis)
        if (xAxis == null || yAxis == null) {
            local.value = local.value.copy(
                message = "${device.name.ifBlank { "This controller" }} does not declare both axes for " +
                    "${slot.label.lowercase()}, so there is nothing to measure.",
            )
            return
        }

        driftJob?.cancel()
        driftDevice = device
        driftSlot = slot
        driftSignature = device.capabilitySignature()
        driftStartedAtMillis = System.currentTimeMillis()
        driftRequestedMillis = local.value.drift.durationSeconds * 1000L
        driftFlatFloor = maxOf(
            AxisNormalizer.signedFlat(xAxis.toRange()),
            AxisNormalizer.signedFlat(yAxis.toRange()),
        )
        driftFuzzFloor = maxOf(
            AxisNormalizer.signedFuzz(xAxis.toRange()),
            AxisNormalizer.signedFuzz(yAxis.toRange()),
        )

        local.value = local.value.copy(
            message = null,
            drift = local.value.drift.copy(
                running = true,
                deviceId = device.deviceId,
                result = null,
                progressResult = null,
                saved = false,
                sampleCount = 0,
                elapsedMillis = 0L,
            ),
        )

        val thresholds = DriftThresholds(
            offset = local.value.drift.offsetThreshold,
            jitter = local.value.drift.jitterThreshold,
            minSamples = MIN_DRIFT_SAMPLES,
        )
        val declaredFlat = driftFlatFloor
        val declaredFuzz = driftFuzzFloor
        driftJob = viewModelScope.launch {
            val samples = ArrayList<DriftSample>(512)
            var lastMotionCount: Long? = null
            var lastUiMillis = -LIVE_UPDATE_MILLIS
            val started = SystemClock.elapsedRealtime()
            while (isActive) {
                val elapsed = SystemClock.elapsedRealtime() - started
                if (elapsed >= driftRequestedMillis) break
                val snapshot = bus.snapshot()
                val count = snapshot.motionEventCount
                val previous = lastMotionCount
                if (previous == null) {
                    lastMotionCount = count
                } else if (count != previous && snapshot.deviceId == device.deviceId) {
                    lastMotionCount = count
                    if (snapshot.reported(slot.xAxis) && snapshot.reported(slot.yAxis)) {
                        samples += DriftSample(
                            timestampMillis = elapsed,
                            x = AxisNormalizer.signed(snapshot.axis(slot.xAxis), xAxis.toRange()),
                            y = AxisNormalizer.signed(snapshot.axis(slot.yAxis), yAxis.toRange()),
                        )
                    }
                }
                if (elapsed - lastUiMillis >= LIVE_UPDATE_MILLIS) {
                    lastUiMillis = elapsed
                    val partial = StickDriftAnalyzer.analyze(
                        samples = samples,
                        requestedDurationMillis = driftRequestedMillis,
                        thresholds = thresholds,
                        declaredFlat = declaredFlat,
                        declaredFuzz = declaredFuzz,
                    )
                    local.value = local.value.copy(
                        drift = local.value.drift.copy(
                            elapsedMillis = elapsed,
                            sampleCount = samples.size,
                            progressResult = partial,
                        ),
                    )
                }
                delay(DRIFT_POLL_MILLIS)
            }
            if (!isActive) return@launch
            val final = StickDriftAnalyzer.analyze(
                samples = samples,
                requestedDurationMillis = driftRequestedMillis,
                thresholds = thresholds,
                declaredFlat = declaredFlat,
                declaredFuzz = declaredFuzz,
            )
            local.value = local.value.copy(
                drift = local.value.drift.copy(
                    running = false,
                    elapsedMillis = driftRequestedMillis,
                    sampleCount = samples.size,
                    progressResult = final,
                    result = final,
                    saved = false,
                ),
            )
        }
    }

    fun cancelDrift(quiet: Boolean = false) {
        driftJob?.cancel()
        driftJob = null
        driftDevice = null
        if (local.value.drift.running || local.value.drift.result != null) {
            local.value = local.value.copy(
                drift = local.value.drift.copy(
                    running = false,
                    deviceId = null,
                    result = null,
                    progressResult = null,
                    saved = false,
                    sampleCount = 0,
                    elapsedMillis = 0L,
                ),
                message = if (quiet) local.value.message else DRIFT_CANCELLED,
            )
        }
    }

    /**
     * Persists the finished run. Only a conclusive verdict can be saved: MOVED and NOT_ENOUGH_DATA are
     * refusals to diagnose, and storing one beside real results would make the history look like evidence.
     */
    fun saveDrift() {
        val current = local.value.drift
        val result = current.result ?: return
        if (!result.isConclusive) {
            local.value = local.value.copy(message = ONLY_CONCLUSIVE_SAVED)
            return
        }
        if (current.saved) return
        val device = driftDevice ?: run {
            local.value = local.value.copy(message = DRIFT_DEVICE_GONE)
            return
        }
        val record = ControllerTestResult.from(
            result = result,
            deviceSignature = driftSignature,
            deviceName = device.name.ifBlank { "Device ${device.deviceId}" },
            stick = driftSlot.stickKind,
            startedAtMillis = driftStartedAtMillis,
            durationMillis = result.spanMillis.coerceAtLeast(1L),
        )
        viewModelScope.launch {
            when (val outcome = testResults.save(record)) {
                is ControllerWriteResult.Saved ->
                    local.value = local.value.copy(
                        drift = local.value.drift.copy(saved = true),
                        message = DRIFT_SAVED,
                    )
                is ControllerWriteResult.Invalid ->
                    local.value = local.value.copy(message = outcome.errors.firstOrNull() ?: DRIFT_SAVE_FAILED)
                is ControllerWriteResult.Failed ->
                    local.value = local.value.copy(message = "$DRIFT_SAVE_FAILED (${outcome.detail})")
            }
        }
    }

    // ---------------------------------------------------------------------------------- curve

    fun setCurveStick(slot: StickSlot) {
        if (slot == local.value.curve.stick) return
        local.value = local.value.copy(curve = local.value.curve.copy(stick = slot))
    }

    fun setCurveDeadzone(value: Float) {
        local.value = local.value.copy(
            curve = local.value.curve.copy(deadzone = value.coerceIn(0f, MAX_CURVE_DEADZONE)),
        )
    }

    fun setCurveKind(kind: com.gamecore.core.controller.ResponseCurveKind) {
        local.value = local.value.copy(curve = local.value.curve.copy(kind = kind))
    }

    fun setCurveExponent(value: Float) {
        local.value = local.value.copy(
            curve = local.value.curve.copy(
                exponent = value.coerceIn(
                    ResponseCurveSettings.MIN_EXPONENT,
                    ResponseCurveSettings.MAX_EXPONENT,
                ),
            ),
        )
    }

    fun resetCurve() {
        local.value = local.value.copy(curve = CurveUiState())
    }

    // --------------------------------------------------------------------------------- testing

    /** Clears the held buttons, press counts, peaks and event log. The device list is untouched. */
    fun clearInput() {
        if (local.value.drift.running) cancelDrift(quiet = true)
        bus.reset()
        local.value = local.value.copy(message = null)
    }

    /**
     * Runs the selected controller's own rumble motor for a moment.
     *
     * The phone's vibrator is never used as a stand-in. A rumble test that buzzed the handset instead would
     * tell the user nothing about the controller, so a device without a vibrator says so.
     */
    fun testVibration(deviceId: Int) {
        val ran = reader.testVibration(deviceId)
        local.value = local.value.copy(message = if (ran) VIBRATION_SENT else VIBRATION_REFUSED)
    }

    // ---------------------------------------------------------------------------------- export

    fun export(format: DiagnosticsFormat) {
        if (local.value.isExporting) return
        val list = state.value.controllers
        viewModelScope.launch {
            local.value = local.value.copy(isExporting = true, message = null)
            val result = exporter.exportControllers(devices = list, format = format)
            local.value = local.value.copy(
                isExporting = false,
                export = result,
                message = messageFor(result),
            )
        }
    }

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
        DiagnosticsExport.Empty -> NOTHING_CONNECTED
        is DiagnosticsExport.Failed -> "That file could not be written (${result.detail})."
    }

    private fun ControllerAxis.toRange(): AxisRange =
        AxisRange(minimum = minimum, maximum = maximum, flat = flat, fuzz = fuzz)

    override fun onCleared() {
        driftJob?.cancel()
        super.onCleared()
    }

    private companion object {
        /** About fifteen reads a second — fast enough to look live, slow enough to cost nothing. */
        const val POLL_MILLIS = 66L

        const val CAPTURE_GRACE_MILLIS = 500L

        /** Fifty polls a second while a run is active; each new motion event is sampled at most once. */
        const val DRIFT_POLL_MILLIS = 20L

        const val LIVE_UPDATE_MILLIS = 100L

        const val MIN_DRIFT_SECONDS = 3
        const val MAX_DRIFT_SECONDS = 60
        const val MIN_DRIFT_SAMPLES = 20
        const val MAX_DRIFT_OFFSET = 0.30f
        const val MAX_DRIFT_JITTER = 0.10f
        const val MAX_CURVE_DEADZONE = 0.50f

        const val VIBRATION_SENT =
            "Rumble sent to the controller. If nothing moved, the controller accepted the command and has " +
                "no motor, or its motor is driven by a vendor protocol Android does not expose."

        const val VIBRATION_REFUSED =
            "This controller reports no vibrator to Android, so there is nothing for GameCore to run."

        const val NOTHING_CONNECTED =
            "There is no controller attached to write a report about."

        const val NO_SHARE_TARGET =
            "No app on this device offered to take that file. It is still saved inside GameCore."

        const val NO_CONTROLLER_FOR_DRIFT =
            "There is no controller attached to measure."

        const val HAT_NOT_DRIFTABLE =
            "A hat switch is a set of switches, not an analog stick. Drift is only measured on the left or " +
                "right stick."

        const val DRIFT_CANCELLED = "Drift test cancelled. Nothing was saved."

        const val ONLY_CONCLUSIVE_SAVED =
            "Only a conclusive run can be saved. A moved stick or a run without enough readings is not a " +
                "measurement."

        const val DRIFT_DEVICE_GONE =
            "The controller this run belonged to is no longer attached, so the result cannot be filed against it."

        const val DRIFT_SAVED = "Saved to this controller's drift history."

        const val DRIFT_SAVE_FAILED = "That result could not be saved."
    }
}
