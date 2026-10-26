package com.gamecore.ui.controller

import android.view.MotionEvent
import com.gamecore.core.common.Observed
import com.gamecore.core.common.isAvailable
import com.gamecore.core.controller.AxisNormalizer
import com.gamecore.core.controller.AxisRange
import com.gamecore.core.controller.DriftResult
import com.gamecore.core.controller.ControllerSignature
import com.gamecore.core.controller.ResponseCurveKind
import com.gamecore.core.controller.StickKind
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
    val labTool: ControllerLabTool = ControllerLabTool.DRIFT,
    val snapshot: ControllerInputSnapshot = ControllerInputSnapshot.EMPTY,
    val drift: DriftUiState = DriftUiState(),
    val curve: CurveUiState = CurveUiState(),
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
 * The second, separate tool row: diagnostics and saved references rather than live testers.
 *
 * Kept out of [ControllerTool] because these panels need state of their own — a run, a curve, a database —
 * and mixing them into the live tester's "one at a time" switch would make the tester card carry controls
 * that are not an input tester. The screen still shows one of these at a time.
 */
enum class ControllerLabTool(val label: String) {
    DRIFT("Drift"),
    CURVE("Curve"),
    PROFILES("Profiles"),
}

/**
 * The drift panel's state.
 *
 * [progressResult] is a live partial analysis while [running]; its verdict stays NOT_ENOUGH_DATA until the
 * run has enough fresh readings, but its offset and jitter figures are real running values. [result] is set
 * only when the run finishes. [saved] is per-result, so a new run starts unsaved rather than looking as if a
 * previous save covered it.
 */
data class DriftUiState(
    val running: Boolean = false,
    /** The device the current run or result belongs to, so a result is never shown under another pad. */
    val deviceId: Int? = null,
    val stick: StickSlot = StickSlot.LEFT,
    val durationSeconds: Int = 10,
    val offsetThreshold: Float = 0.08f,
    val jitterThreshold: Float = 0.02f,
    val elapsedMillis: Long = 0L,
    val sampleCount: Int = 0,
    val progressResult: DriftResult? = null,
    val result: DriftResult? = null,
    val saved: Boolean = false,
) {
    val progress: Float
        get() = if (durationSeconds <= 0) 0f else (elapsedMillis.toFloat() / (durationSeconds * 1000f)).coerceIn(0f, 1f)
}

/** The curve lab's state. A simulation reference; nothing here is applied to a game. */
data class CurveUiState(
    val stick: StickSlot = StickSlot.LEFT,
    val deadzone: Float = 0.08f,
    val kind: ResponseCurveKind = ResponseCurveKind.EXPONENTIAL,
    val exponent: Float = 2f,
) {
    val settings: com.gamecore.core.controller.ResponseCurveSettings
        get() = com.gamecore.core.controller.ResponseCurveSettings(
            deadzone = deadzone,
            kind = kind,
            exponent = exponent,
        )
}

/** True only when the device declared both axes the slot is read from. Nothing is inferred. */
fun ControllerDevice.hasStickAxes(slot: StickSlot): Boolean =
    axisFor(slot.xAxis) != null && axisFor(slot.yAxis) != null

/**
 * The key a profile and a drift result are filed under. Capabilities, not the device name: a pad that
 * exposes different axes gets a different key even when the driver calls it the same thing as another.
 */
fun ControllerDevice.capabilitySignature(): String = ControllerSignature.of(
    vendorId = vendorId,
    productId = productId,
    axisIds = axes.map { it.axis },
    buttonCount = buttons.size,
)

/**
 * The driver's declared flat and fuzz radii for a stick, as fractions of travel, or null when the device
 * does not declare both axes. These are floors a drift verdict is judged against: a controller is not
 * drifting while it rests inside the dead zone its own driver applies.
 */
fun ControllerDevice.stickFloors(slot: StickSlot): Pair<Float, Float>? {
    val x = axisFor(slot.xAxis) ?: return null
    val y = axisFor(slot.yAxis) ?: return null
    val xRange = AxisRange(x.minimum, x.maximum, x.flat, x.fuzz)
    val yRange = AxisRange(y.minimum, y.maximum, y.flat, y.fuzz)
    val flat = maxOf(
        AxisNormalizer.signedFlat(xRange),
        AxisNormalizer.signedFlat(yRange),
    )
    val fuzz = maxOf(
        AxisNormalizer.signedFuzz(xRange),
        AxisNormalizer.signedFuzz(yRange),
    )
    return flat to fuzz
}

/** The domain name of the slot, used for a persisted result. HAT is never offered by the drift panel. */
val StickSlot.stickKind: StickKind
    get() = when (this) {
        StickSlot.LEFT -> StickKind.LEFT
        StickSlot.RIGHT -> StickKind.RIGHT
        StickSlot.HAT -> StickKind.HAT
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
