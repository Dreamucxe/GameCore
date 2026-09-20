package com.gamecore.ui.controller

import android.view.MotionEvent
import com.gamecore.core.common.Observed
import com.gamecore.core.common.isAvailable
import com.gamecore.core.input.ControllerDevice
import com.gamecore.core.input.ControllerInputSnapshot
import com.gamecore.data.repository.DiagnosticsExport

/**
 * The controller lab's state: what is plugged in, and what it is doing right now.
 *
 * The two halves come from different places on purpose. The device list is `InputDevice` describing the
 * hardware — vendor ids, axis ranges, declared buttons — and it only changes when something is plugged in
 * or pulled out. The [snapshot] is the live event state, polled from the input bus at the screen's rate
 * rather than the controller's.
 *
 * A device that reports no controllers is not a failure and is not shown as one: Android tells us about a
 * gamepad only once it is actually attached, so an empty list means exactly that.
 */
data class ControllerUiState(
    val isLoaded: Boolean = false,
    val hasInputService: Boolean = true,
    val devices: Observed<List<ControllerDevice>>? = null,
    val showAll: Boolean = false,
    val selectedId: Int? = null,
    val tool: ControllerTool = ControllerTool.STICKS,
    val snapshot: ControllerInputSnapshot = ControllerInputSnapshot.EMPTY,
    val isExporting: Boolean = false,
    val export: DiagnosticsExport? = null,
    val message: String? = null,
) {
    val controllers: List<ControllerDevice>
        get() = (devices as? Observed.Value)?.value ?: emptyList()

    /**
     * The device the lab is showing.
     *
     * Falls back to the first attached controller rather than storing a default at load time: a controller
     * that is unplugged while the lab is open would otherwise leave the screen pointing at a device id that
     * no longer exists.
     */
    val selected: ControllerDevice?
        get() = controllers.firstOrNull { it.deviceId == selectedId } ?: controllers.firstOrNull()

    val hasControllers: Boolean get() = controllers.isNotEmpty()

    val readFailed: Boolean get() = devices != null && !devices.isAvailable

    /** True once at least one event has arrived, which is not the same as being connected. */
    val hasInput: Boolean get() = snapshot.hasActivity

    val lastExport: DiagnosticsExport.Written? get() = export as? DiagnosticsExport.Written
}

/** Which tester the lab is showing. One at a time, so nothing is drawn that is not being looked at. */
enum class ControllerTool(val label: String) {
    STICKS("Sticks"),
    TRIGGERS("Triggers"),
    BUTTONS("Buttons"),
    AXES("Axes"),
    EVENTS("Events"),
}

/**
 * The two thumbsticks and the hat switch, as the axis pairs Android reports them on.
 *
 * These are the conventional mappings and nothing more: a controller is free to report its right stick on
 * `AXIS_Z`/`AXIS_RZ` (most do) or not to have one at all. The lab checks whether the device actually
 * declared each axis before drawing it, so a stick that is absent is shown as absent rather than as a
 * stick resting perfectly at centre.
 */
enum class StickSlot(val label: String, val xAxis: Int, val yAxis: Int) {
    LEFT("Left stick", MotionEvent.AXIS_X, MotionEvent.AXIS_Y),
    RIGHT("Right stick", MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ),
    HAT("Hat switch", MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y),
}

/**
 * The analog triggers, with the fallback axis some controllers use instead.
 *
 * Android has both `AXIS_LTRIGGER`/`AXIS_RTRIGGER` and `AXIS_BRAKE`/`AXIS_GAS`, and which pair a
 * controller reports on is up to the controller. The lab looks for the first and falls back to the second,
 * then names the axis it actually read so the reading can be traced back to something real.
 */
enum class TriggerSlot(val label: String, val axis: Int, val alternate: Int) {
    LEFT("Left trigger", MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE),
    RIGHT("Right trigger", MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS),
}
