package com.gamecore.ui.controller

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.input.ControllerInputBus
import com.gamecore.core.input.ControllerInputSnapshot
import com.gamecore.core.input.ControllerReader
import com.gamecore.data.repository.DiagnosticsExport
import com.gamecore.data.repository.DiagnosticsExporter
import com.gamecore.data.repository.DiagnosticsFormat
import dagger.hilt.android.lifecycle.HiltViewModel
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
) : ViewModel() {

    private val local = MutableStateFlow(ControllerUiState())

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
        local.value = local.value.copy(selectedId = deviceId, message = null)
    }

    fun setTool(tool: ControllerTool) {
        if (tool == local.value.tool) return
        local.value = local.value.copy(tool = tool)
    }

    fun setShowAll(showAll: Boolean) {
        if (showAll == local.value.showAll) return
        local.value = local.value.copy(showAll = showAll, selectedId = null)
    }

    // --------------------------------------------------------------------------------- testing

    /** Clears the held buttons, press counts, peaks and event log. The device list is untouched. */
    fun clearInput() {
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

    private companion object {
        /** About fifteen reads a second — fast enough to look live, slow enough to cost nothing. */
        const val POLL_MILLIS = 66L

        const val CAPTURE_GRACE_MILLIS = 500L

        const val VIBRATION_SENT =
            "Rumble sent to the controller. If nothing moved, the controller accepted the command and has " +
                "no motor, or its motor is driven by a vendor protocol Android does not expose."

        const val VIBRATION_REFUSED =
            "This controller reports no vibrator to Android, so there is nothing for GameCore to run."

        const val NOTHING_CONNECTED =
            "There is no controller attached to write a report about."

        const val NO_SHARE_TARGET =
            "No app on this device offered to take that file. It is still saved inside GameCore."
    }
}
