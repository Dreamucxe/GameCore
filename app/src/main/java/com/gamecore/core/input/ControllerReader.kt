package com.gamecore.core.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connected controllers, as `InputDevice` describes them.
 *
 * Every field is the platform's: the vendor and product ids are the USB/Bluetooth ids the device
 * reported, the axis ranges are its own `MotionRange` values including the flat and fuzz the driver
 * declares, and the button list is the answer to `InputDevice.hasKeys` rather than an assumption about
 * what a gamepad has. A controller that declares no left trigger axis is shown without one.
 *
 * What this class deliberately does not do is measure latency. Android timestamps an input event when
 * the *framework* receives it, which already includes the radio, the driver and the input pipeline, and
 * gives no access to when the button was physically pressed. A figure derived from those timestamps
 * would describe queueing inside this process, not the controller, so GameCore reports the event
 * timestamps it has and makes no latency claim at all.
 */
@Singleton
class ControllerReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val inputManager: InputManager? =
        context.getSystemService(Context.INPUT_SERVICE) as? InputManager

    val hasInputService: Boolean get() = inputManager != null

    /** Every attached device that looks like a game controller, in a stable order. */
    fun controllers(): Observed<List<ControllerDevice>> = Observed.catching(DataSource.INPUT_MANAGER) {
        // `getDeviceIds` hands back an IntArray, which has no `mapNotNull` — and the null it would drop
        // is real: a controller unplugged between the id list and the lookup returns null rather than
        // throwing, which is exactly the race this screen is most likely to be sitting in.
        InputDevice.getDeviceIds()
            .toList()
            .mapNotNull { id -> InputDevice.getDevice(id) }
            .filter { it.isController() }
            .map { describe(it) }
            .sortedWith(compareBy({ it.controllerNumber }, { it.deviceId }))
    }

    /** Every attached input device, controller or not. The lab shows this behind a toggle. */
    fun allDevices(): Observed<List<ControllerDevice>> = Observed.catching(DataSource.INPUT_MANAGER) {
        InputDevice.getDeviceIds()
            .toList()
            .mapNotNull { id -> InputDevice.getDevice(id) }
            .map { describe(it) }
            .sortedBy { it.deviceId }
    }

    /**
     * Emits whenever a device is added, removed or reconfigured.
     *
     * Registered against the main looper because `InputManager` posts to the handler it is given and a
     * null handler means the caller's looper, which inside a coroutine is not guaranteed to have one.
     * Unregistered when collection stops, so nothing is listening while the lab is closed.
     */
    fun deviceChanges(): Flow<Unit> = callbackFlow {
        val manager = inputManager
        if (manager == null) {
            close()
            return@callbackFlow
        }
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                trySend(Unit)
            }

            override fun onInputDeviceRemoved(deviceId: Int) {
                trySend(Unit)
            }

            override fun onInputDeviceChanged(deviceId: Int) {
                trySend(Unit)
            }
        }
        manager.registerInputDeviceListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
        awaitClose { manager.unregisterInputDeviceListener(listener) }
    }

    private fun InputDevice.isController(): Boolean {
        val gamepad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        val joystick = sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        val dpad = sources and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
        return (gamepad || joystick || dpad) && !isVirtual
    }

    private fun describe(device: InputDevice): ControllerDevice {
        val axes = device.motionRanges
            .filter { it.source and InputDevice.SOURCE_CLASS_JOYSTICK != 0 || it.source and InputDevice.SOURCE_JOYSTICK != 0 }
            .ifEmpty { device.motionRanges }
            .map { range ->
                ControllerAxis(
                    axis = range.axis,
                    label = MotionEvent.axisToString(range.axis).removePrefix("AXIS_"),
                    minimum = range.min,
                    maximum = range.max,
                    flat = range.flat,
                    fuzz = range.fuzz,
                    resolution = range.resolution,
                )
            }
            .distinctBy { it.axis }
            .sortedBy { it.axis }

        val supported = device.hasKeys(*GAMEPAD_KEYS)
        val buttons = GAMEPAD_KEYS.filterIndexed { index, _ -> supported.getOrNull(index) == true }

        return ControllerDevice(
            deviceId = device.id,
            name = device.name.orEmpty(),
            descriptor = device.descriptor.orEmpty(),
            vendorId = device.vendorId,
            productId = device.productId,
            controllerNumber = device.controllerNumber,
            sources = sourceLabels(device.sources),
            rawSources = device.sources,
            isGamepad = device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD,
            isJoystick = device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK,
            isDpad = device.sources and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD,
            isKeyboard = device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC,
            keyboardType = keyboardTypeLabel(device.keyboardType),
            isVirtual = device.isVirtual,
            axes = axes,
            buttons = buttons,
            vibration = vibrationFor(device),
        )
    }

    /**
     * Whether this controller exposes a vibrator, through whichever API this Android version has.
     *
     * `InputDevice.getVibratorManager` from API 31, `getVibrator` before it. Both report `hasVibrator`
     * honestly, and a controller that has none is reported as having none rather than as an error.
     */
    private fun vibrationFor(device: InputDevice): VibrationSupport = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager: VibratorManager = device.vibratorManager
            val ids = manager.vibratorIds
            val has = manager.defaultVibrator.hasVibrator()
            VibrationSupport(
                available = has,
                vibratorCount = ids.size,
                detail = if (has) "Reported by InputDevice.getVibratorManager()." else null,
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator: Vibrator? = device.vibrator
            val has = vibrator?.hasVibrator() == true
            VibrationSupport(
                available = has,
                vibratorCount = if (has) 1 else 0,
                detail = if (has) "Reported by InputDevice.getVibrator()." else null,
            )
        }
    } catch (error: Throwable) {
        VibrationSupport(available = false, vibratorCount = 0, detail = error.javaClass.simpleName)
    }

    /**
     * Runs the controller's own vibrator for a moment.
     *
     * Only ever the device's vibrator, never the phone's: a rumble test that silently buzzed the handset
     * would be a test of nothing. Returns false when the platform refused or there is nothing to run.
     */
    fun testVibration(deviceId: Int, milliseconds: Long = 400L): Boolean = try {
        val device = InputDevice.getDevice(deviceId)
        val vibrator: Vibrator? = when {
            device == null -> null
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> device.vibratorManager.defaultVibrator
            else -> {
                @Suppress("DEPRECATION")
                device.vibrator
            }
        }
        if (vibrator == null || !vibrator.hasVibrator()) {
            false
        } else {
            vibrator.vibrate(
                VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE),
            )
            true
        }
    } catch (error: Throwable) {
        false
    }

    private fun keyboardTypeLabel(type: Int): String = when (type) {
        InputDevice.KEYBOARD_TYPE_ALPHABETIC -> "Alphabetic"
        InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC -> "Non-alphabetic"
        InputDevice.KEYBOARD_TYPE_NONE -> "None"
        else -> "Unknown"
    }

    private fun sourceLabels(sources: Int): List<String> = SOURCE_NAMES
        .filter { (mask, _) -> sources and mask == mask }
        .map { it.second }

    companion object {
        /**
         * The keycodes a gamepad can declare, in the order the tester lays them out.
         *
         * `hasKeys` answers for exactly these and nothing else, so a button not on this list is not shown
         * as absent — it is simply not asked about, which is a different claim.
         */
        val GAMEPAD_KEYS = intArrayOf(
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_1,
            KeyEvent.KEYCODE_BUTTON_2,
            KeyEvent.KEYCODE_BUTTON_3,
            KeyEvent.KEYCODE_BUTTON_4,
        )

        private val SOURCE_NAMES = listOf(
            InputDevice.SOURCE_GAMEPAD to "Gamepad",
            InputDevice.SOURCE_JOYSTICK to "Joystick",
            InputDevice.SOURCE_DPAD to "D-pad",
            InputDevice.SOURCE_KEYBOARD to "Keyboard",
            InputDevice.SOURCE_TOUCHSCREEN to "Touchscreen",
            InputDevice.SOURCE_MOUSE to "Mouse",
            InputDevice.SOURCE_STYLUS to "Stylus",
            InputDevice.SOURCE_TRACKBALL to "Trackball",
            InputDevice.SOURCE_TOUCHPAD to "Touchpad",
            InputDevice.SOURCE_ROTARY_ENCODER to "Rotary encoder",
        )

        /** The keycode's platform name without its prefix: `BUTTON_A`, `DPAD_UP`. */
        fun keyLabel(keyCode: Int): String =
            KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
    }
}

/** One axis, with the driver's own range and dead-zone figures. */
data class ControllerAxis(
    val axis: Int,
    val label: String,
    val minimum: Float,
    val maximum: Float,
    /** The driver's declared dead zone: values within this of centre should be treated as centre. */
    val flat: Float,
    /** The driver's declared error margin on a reading. */
    val fuzz: Float,
    val resolution: Float,
) {
    val range: String get() = "${trim(minimum)} … ${trim(maximum)}"

    /** Where [value] sits in the axis's own range, 0..1, for drawing it. */
    fun fraction(value: Float): Float {
        val span = maximum - minimum
        if (span <= 0f) return 0f
        return ((value - minimum) / span).coerceIn(0f, 1f)
    }

    private fun trim(value: Float): String {
        val rounded = kotlin.math.round(value * 100f) / 100f
        return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
    }
}

/** Whether a controller can rumble, and how the platform said so. */
data class VibrationSupport(
    val available: Boolean,
    val vibratorCount: Int,
    val detail: String?,
)

/** One attached input device, entirely as `InputDevice` described it. */
data class ControllerDevice(
    val deviceId: Int,
    val name: String,
    val descriptor: String,
    val vendorId: Int,
    val productId: Int,
    val controllerNumber: Int,
    val sources: List<String>,
    val rawSources: Int,
    val isGamepad: Boolean,
    val isJoystick: Boolean,
    val isDpad: Boolean,
    val isKeyboard: Boolean,
    val keyboardType: String,
    val isVirtual: Boolean,
    val axes: List<ControllerAxis>,
    val buttons: List<Int>,
    val vibration: VibrationSupport,
) {
    val vendorHex: String get() = "0x%04X".format(vendorId)

    val productHex: String get() = "0x%04X".format(productId)

    val classification: String get() = when {
        isGamepad && isJoystick -> "Gamepad with analog sticks"
        isGamepad -> "Gamepad"
        isJoystick -> "Joystick"
        isDpad -> "D-pad only"
        isKeyboard -> "Keyboard"
        else -> "Other input device"
    }

    fun axisFor(axis: Int): ControllerAxis? = axes.firstOrNull { it.axis == axis }
}
