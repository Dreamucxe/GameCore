package com.gamecore.core.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The live half of the Controller Lab: the events themselves.
 *
 * Controller input reaches an app only through the focused activity's dispatch chain, so `MainActivity`
 * hands `dispatchKeyEvent` and `dispatchGenericMotionEvent` here and this class holds the resulting state
 * for the lab screen to poll. Nothing is intercepted globally and nothing is read from another app —
 * Android does not offer either, and inventing a path to them is not on the table.
 *
 * Two rules keep the rest of GameCore working while the lab is open. Events are only ever claimed while
 * [capturing] is on, which the lab screen turns on when it is resumed and off when it is not; and even
 * then only gamepad and joystick input is claimed, never the navigation keys, so Back and the volume keys
 * behave exactly as they always did.
 *
 * The write side allocates nothing. A stick being pushed produces a motion event per frame per axis, and
 * those land in a pre-sized `FloatArray`; a button produces one key event, rare enough to afford a lock
 * and a small object. The read side builds one immutable snapshot per poll, at the screen's refresh rate
 * rather than the controller's.
 */
@Singleton
class ControllerInputBus @Inject constructor() {

    /** Values indexed by `MotionEvent.AXIS_*`, written on the input thread, copied on read. */
    private val axisValues = FloatArray(AXIS_SLOTS)
    private val axisSeen = BooleanArray(AXIS_SLOTS)
    private val axisPeak = FloatArray(AXIS_SLOTS)
    private val pressed = BooleanArray(KEY_SLOTS)
    private val pressCounts = IntArray(KEY_SLOTS)

    private val eventLock = Any()
    private val recent = ArrayDeque<ControllerEvent>()

    @Volatile
    var capturing: Boolean = false
        private set

    @Volatile
    private var keyEvents = 0L

    @Volatile
    private var motionEvents = 0L

    @Volatile
    private var lastEventMillis = 0L

    @Volatile
    private var lastDeviceId = -1

    @Volatile
    private var lastDeviceName = ""

    @Volatile
    private var sequence = 0

    /** Called when the lab is resumed. Until this runs, every event passes straight through. */
    fun startCapture() {
        capturing = true
    }

    /** Called when the lab is paused or left. Claims nothing afterwards. */
    fun stopCapture() {
        capturing = false
        releaseAll()
    }

    /**
     * A key event from the activity. Returns true when the lab consumed it.
     *
     * Consumed means the button did not also act on whatever is underneath, which for a gamepad button in
     * a diagnostics screen is what the user wants. Anything the system or the user needs — Back, Home,
     * volume — is read for display and then passed on untouched.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!capturing) return false
        if (!isControllerSource(event.source)) return false
        val code = event.keyCode
        if (code in NEVER_CONSUME) return false

        keyEvents++
        lastEventMillis = System.currentTimeMillis()
        noteDevice(event.deviceId)

        val slot = code.takeIf { it in 0 until KEY_SLOTS }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Auto-repeat fires while a button is held. Counting those as presses would turn one
                // press into thirty, so only the first down of a press is recorded.
                if (event.repeatCount == 0 && slot != null && !pressed[slot]) {
                    pressed[slot] = true
                    pressCounts[slot] = pressCounts[slot] + 1
                    push(code, ControllerEventKind.DOWN, null)
                }
            }

            KeyEvent.ACTION_UP -> {
                if (slot != null) pressed[slot] = false
                push(code, ControllerEventKind.UP, null)
            }
        }
        return true
    }

    /**
     * A generic motion event — sticks, triggers and the hat switch.
     *
     * Only `SOURCE_CLASS_JOYSTICK` events are taken. A mouse or a touchpad also arrives through
     * `dispatchGenericMotionEvent`, and claiming those would break scrolling everywhere else in the app.
     */
    fun onMotionEvent(event: MotionEvent): Boolean {
        if (!capturing) return false
        if (event.source and InputDevice.SOURCE_CLASS_JOYSTICK != InputDevice.SOURCE_CLASS_JOYSTICK) {
            return false
        }
        if (event.action != MotionEvent.ACTION_MOVE) return false

        motionEvents++
        lastEventMillis = System.currentTimeMillis()
        noteDevice(event.deviceId)

        // Reading only the axes the device declares avoids sweeping sixty-four slots per frame and avoids
        // writing a zero into an axis this controller does not have, which would look like a centred stick
        // rather than an absent one.
        val ranges = event.device?.motionRanges
        if (ranges.isNullOrEmpty()) {
            for (axis in 0 until AXIS_SLOTS) store(axis, event.getAxisValue(axis))
        } else {
            for (i in ranges.indices) {
                val axis = ranges[i].axis
                if (axis in 0 until AXIS_SLOTS) store(axis, event.getAxisValue(axis))
            }
        }
        return true
    }

    private fun store(axis: Int, value: Float) {
        axisValues[axis] = value
        axisSeen[axis] = true
        val magnitude = if (value < 0f) -value else value
        if (magnitude > axisPeak[axis]) axisPeak[axis] = magnitude
    }

    private fun noteDevice(deviceId: Int) {
        if (deviceId == lastDeviceId) return
        lastDeviceId = deviceId
        lastDeviceName = try {
            InputDevice.getDevice(deviceId)?.name.orEmpty()
        } catch (error: Throwable) {
            ""
        }
    }

    private fun push(keyCode: Int, kind: ControllerEventKind, detail: String?) {
        val event = ControllerEvent(
            sequence = sequence++,
            keyCode = keyCode,
            label = ControllerReader.keyLabel(keyCode),
            kind = kind,
            detail = detail,
            timestampMillis = System.currentTimeMillis(),
        )
        synchronized(eventLock) {
            recent.addLast(event)
            while (recent.size > MAX_EVENTS) recent.removeFirst()
        }
    }

    /** Clears the button and axis state without touching the counters. Used when a device unplugs. */
    fun releaseAll() {
        pressed.fill(false)
        axisValues.fill(0f)
    }

    /** Full reset: the lab's "clear" action. */
    fun reset() {
        pressed.fill(false)
        pressCounts.fill(0)
        axisValues.fill(0f)
        axisPeak.fill(0f)
        axisSeen.fill(false)
        keyEvents = 0L
        motionEvents = 0L
        lastEventMillis = 0L
        sequence = 0
        synchronized(eventLock) { recent.clear() }
    }

    fun snapshot(): ControllerInputSnapshot {
        val heldKeys = ArrayList<Int>(4)
        for (code in 0 until KEY_SLOTS) {
            if (pressed[code]) heldKeys.add(code)
        }
        val counts = HashMap<Int, Int>()
        for (code in 0 until KEY_SLOTS) {
            val count = pressCounts[code]
            if (count > 0) counts[code] = count
        }
        val events = synchronized(eventLock) { recent.toList().asReversed() }
        return ControllerInputSnapshot(
            capturing = capturing,
            axisValues = axisValues.copyOf(),
            axisPeak = axisPeak.copyOf(),
            axisSeen = axisSeen.copyOf(),
            heldKeys = heldKeys,
            pressCounts = counts,
            recentEvents = events,
            keyEventCount = keyEvents,
            motionEventCount = motionEvents,
            lastEventMillis = lastEventMillis,
            deviceId = lastDeviceId,
            deviceName = lastDeviceName,
        )
    }

    private fun isControllerSource(source: Int): Boolean =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD

    companion object {
        /** `AXIS_GENERIC_16` is 47; sixty-four leaves room for anything a later platform adds. */
        const val AXIS_SLOTS = 64

        /** Above `KEYCODE_BUTTON_16` (203) with headroom, and small enough to sweep in a poll. */
        const val KEY_SLOTS = 288

        const val MAX_EVENTS = 60

        /**
         * Keys the lab will report but never swallow.
         *
         * Some controllers map their menu button to Back and their home button to Home. Consuming those
         * would trap the user inside the lab with a controller and no touchscreen hand free, which is a
         * worse outcome than not testing those two buttons.
         */
        val NEVER_CONSUME = intArrayOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_APP_SWITCH,
        )
    }
}

/** Whether an event was a press or a release. */
enum class ControllerEventKind(val label: String) {
    DOWN("DOWN"),
    UP("UP"),
}

/** One button event, kept for the event log. */
data class ControllerEvent(
    val sequence: Int,
    val keyCode: Int,
    val label: String,
    val kind: ControllerEventKind,
    val detail: String?,
    val timestampMillis: Long,
)

/**
 * The controller's state at one instant, immutable and safe to hand to Compose.
 *
 * `axisSeen` matters as much as `axisValues`: an axis this controller has never reported is a different
 * thing from an axis sitting at zero, and the lab draws them differently rather than showing a centred
 * stick for hardware that has none.
 */
data class ControllerInputSnapshot(
    val capturing: Boolean,
    val axisValues: FloatArray,
    val axisPeak: FloatArray,
    val axisSeen: BooleanArray,
    val heldKeys: List<Int>,
    val pressCounts: Map<Int, Int>,
    val recentEvents: List<ControllerEvent>,
    val keyEventCount: Long,
    val motionEventCount: Long,
    val lastEventMillis: Long,
    val deviceId: Int,
    val deviceName: String,
) {
    val totalEvents: Long get() = keyEventCount + motionEventCount

    val hasActivity: Boolean get() = totalEvents > 0L

    fun axis(axis: Int): Float = axisValues.getOrElse(axis) { 0f }

    fun peak(axis: Int): Float = axisPeak.getOrElse(axis) { 0f }

    fun reported(axis: Int): Boolean = axisSeen.getOrElse(axis) { false }

    fun isPressed(keyCode: Int): Boolean = heldKeys.contains(keyCode)

    fun presses(keyCode: Int): Int = pressCounts[keyCode] ?: 0

    // Arrays compare by identity by default, and these are replaced on every poll, so the generated
    // equals would report every snapshot as new and recompose the screen fifteen times a second while
    // the controller sits untouched.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ControllerInputSnapshot) return false
        return capturing == other.capturing &&
            axisValues.contentEquals(other.axisValues) &&
            axisPeak.contentEquals(other.axisPeak) &&
            axisSeen.contentEquals(other.axisSeen) &&
            heldKeys == other.heldKeys &&
            pressCounts == other.pressCounts &&
            recentEvents == other.recentEvents &&
            keyEventCount == other.keyEventCount &&
            motionEventCount == other.motionEventCount &&
            lastEventMillis == other.lastEventMillis &&
            deviceId == other.deviceId &&
            deviceName == other.deviceName
    }

    override fun hashCode(): Int {
        var result = capturing.hashCode()
        result = 31 * result + axisValues.contentHashCode()
        result = 31 * result + axisPeak.contentHashCode()
        result = 31 * result + axisSeen.contentHashCode()
        result = 31 * result + heldKeys.hashCode()
        result = 31 * result + pressCounts.hashCode()
        result = 31 * result + recentEvents.hashCode()
        result = 31 * result + keyEventCount.hashCode()
        result = 31 * result + motionEventCount.hashCode()
        result = 31 * result + lastEventMillis.hashCode()
        result = 31 * result + deviceId
        result = 31 * result + deviceName.hashCode()
        return result
    }

    companion object {
        val EMPTY = ControllerInputSnapshot(
            capturing = false,
            axisValues = FloatArray(ControllerInputBus.AXIS_SLOTS),
            axisPeak = FloatArray(ControllerInputBus.AXIS_SLOTS),
            axisSeen = BooleanArray(ControllerInputBus.AXIS_SLOTS),
            heldKeys = emptyList(),
            pressCounts = emptyMap(),
            recentEvents = emptyList(),
            keyEventCount = 0L,
            motionEventCount = 0L,
            lastEventMillis = 0L,
            deviceId = -1,
            deviceName = "",
        )
    }
}
