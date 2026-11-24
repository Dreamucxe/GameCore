package com.gamecore.core.wheel

import com.gamecore.core.model.WheelProfile
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The four touch actions the wheel emits. Mirrors the platform names on purpose. */
enum class WheelAction {
    DOWN,
    MOVE,
    UP,
    CANCEL,
}

/**
 * One processed touch sample.
 *
 * [x]/[y] are the absolute screen coordinates that go to the injector. For an overlay gesture they are
 * relative to the wheel's centre (the injected stream's neutral point), not necessarily the physical
 * finger. [rawDx]/[rawDy] and [outputDx]/[outputDy] are kept beside them because the calibration overlay
 * draws both: a user tuning a wheel needs to see what their thumb did and what the wheel made of it, not
 * a single dot that could be either. They are also what the JVM tests assert, since screen coordinates
 * need a device but a displacement does not.
 */
data class WheelTouchFrame(
    val action: WheelAction,
    val pointerId: Int,
    val x: Float,
    val y: Float,
    val downTime: Long,
    val eventTime: Long,
    val rawDx: Float = 0f,
    val rawDy: Float = 0f,
    val outputDx: Float = 0f,
    val outputDy: Float = 0f,
    val sensitivity: Float = 1f,
    val insideDeadzone: Boolean = false,
    val clamped: Boolean = false,
)

/**
 * The movement maths of the High Sensitivity Wheel.
 *
 * Deliberately pure Kotlin: no `MotionEvent`, no binder, no coroutine, no clock. The overlay hands in
 * pointer coordinates and an event time; this hands back the sample to inject. That separation is what
 * makes the one part of the feature that can be tested on a build host — sensitivity, the deadzone, axis
 * gains, inversion, response curves, smoothing and the radius clamp — actually tested, rather than
 * verified by reading the code.
 *
 * **No delay is introduced anywhere.** There is no debounce, no buffer, no `postDelayed`, no channel.
 * Each MOVE is computed and returned synchronously in the same pointer callback that received it.
 * Smoothing is an exponential filter on the *value*, not a queue: it adds no frame of delay, only a
 * gradual response, and its default is zero so the shipped path is a straight multiplication.
 *
 * **Anchor and origin.** The *anchor* is the physical DOWN's screen coordinate; all finger movement is
 * measured from it. The *origin* is the injected stream's neutral point — the wheel's centre, supplied by
 * the overlay. The injected DOWN starts at the origin, and each MOVE is `origin + (finger - anchor)`
 * after amplification and clamping. Keeping those two points separate is what lets a wheel placed over a
 * game's joystick activate that joystick where it actually lives: a thumb that lands off-centre no longer
 * drags the game's touch anchor outside the game's activation area.
 *
 * When no origin is supplied (calibration callers and the original tests), the origin defaults to the
 * anchor, preserving the relative-from-the-finger behaviour for callers that do not have a window centre.
 */
class WheelTouchProcessor {

    /** The pointer id the injected stream asks for. Nine is outside the ids a five-finger gesture uses. */
    var pointerId: Int = DEFAULT_POINTER_ID
        private set

    private var active = false
    /** The physical finger-down point, used to measure raw movement. */
    private var anchorX = 0f
    private var anchorY = 0f
    /** The injected stream's neutral point: the wheel centre. */
    private var originX = 0f
    private var originY = 0f
    private var downTime = 0L
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var smoothedDx = 0f
    private var smoothedDy = 0f

    /** The last sample emitted, or null when no gesture is in flight. Read by calibration. */
    var lastFrame: WheelTouchFrame? = null
        private set

    /** Monotonic count of processed events, exposed to diagnostics. Never a latency claim. */
    var processedEvents: Long = 0L
        private set

    /** True while a finger is down and a pointer stream is considered open. */
    val isActive: Boolean get() = active

    /** The physical anchor of the current gesture, in screen coordinates, or null. */
    val anchor: Pair<Float, Float>? get() = if (active) anchorX to anchorY else null

    /** The injected stream's neutral point (the wheel centre), or null when no gesture is open. */
    val origin: Pair<Float, Float>? get() = if (active) originX to originY else null

    /**
     * Starts a gesture. Any previous gesture is abandoned without emitting an UP: callers must have sent
     * [cancel] first if one was open, and [com.gamecore.core.wheel.WheelController] is the only caller.
     */
    fun down(
        profile: WheelProfile,
        x: Float,
        y: Float,
        eventTime: Long,
        downTime: Long = eventTime,
        originX: Float = x,
        originY: Float = y,
    ): WheelTouchFrame {
        active = true
        anchorX = x
        anchorY = y
        this.originX = originX
        this.originY = originY
        lastRawX = x
        lastRawY = y
        smoothedDx = 0f
        smoothedDy = 0f
        this.downTime = if (downTime > 0L) downTime else eventTime
        val frame = WheelTouchFrame(
            action = WheelAction.DOWN,
            pointerId = pointerId,
            x = originX,
            y = originY,
            downTime = this.downTime,
            eventTime = max(eventTime, this.downTime),
            sensitivity = profile.normalised().sensitivity,
        )
        lastFrame = frame
        processedEvents++
        return frame
    }

    /**
     * Processes a movement. Returns null when no gesture is open, which is the only case that cannot
     * produce a sample: a MOVE that arrives before its DOWN is a lifecycle bug, not a movement of zero.
     */
    fun move(profile: WheelProfile, x: Float, y: Float, eventTime: Long): WheelTouchFrame? {
        if (!active) return null
        lastRawX = x
        lastRawY = y
        return sample(profile, WheelAction.MOVE, x, y, eventTime)
    }

    /**
     * Ends a gesture with a valid release at the last processed position.
     *
     * The release is computed from the UP's own coordinates rather than from the last MOVE's. They are
     * usually the same, but a platform that coalesces the final movement into the UP would otherwise
     * release the pointer a few pixels short of where the finger actually stopped — and in a shooter that
     * last sample is aimed by the player.
     */
    fun up(profile: WheelProfile, x: Float, y: Float, eventTime: Long): WheelTouchFrame? {
        if (!active) return null
        val frame = sample(profile, WheelAction.UP, x, y, eventTime)
        active = false
        lastFrame = frame
        return frame
    }

    /**
     * Abandons a gesture with an ACTION_CANCEL.
     *
     * The injector needs a cancel as much as it needs an up: a stream that simply stops leaves the game
     * holding a finger down, which is the stuck-touch failure the directive calls out. If there was no
     * active gesture there is nothing to cancel and this returns null.
     */
    fun cancel(eventTime: Long): WheelTouchFrame? {
        if (!active) return null
        val frame = WheelTouchFrame(
            action = WheelAction.CANCEL,
            pointerId = pointerId,
            x = originX + smoothedDx,
            y = originY + smoothedDy,
            downTime = downTime,
            eventTime = max(eventTime, downTime),
            rawDx = lastRawX - anchorX,
            rawDy = lastRawY - anchorY,
            outputDx = smoothedDx,
            outputDy = smoothedDy,
        )
        active = false
        lastFrame = frame
        processedEvents++
        return frame
    }

    /**
     * Forgets the gesture without emitting anything. Only for a dead bridge, where even a cancel cannot
     * be delivered: the injector service is told to release everything it holds on reconnection.
     */
    fun reset() {
        active = false
        smoothedDx = 0f
        smoothedDy = 0f
        lastFrame = null
    }

    private fun sample(
        rawProfile: WheelProfile,
        action: WheelAction,
        x: Float,
        y: Float,
        eventTime: Long,
    ): WheelTouchFrame {
        val profile = rawProfile.normalised()
        val rawDx = x - anchorX
        val rawDy = y - anchorY
        val rawMagnitude = hypot(rawDx, rawDy)
        val radius = profile.radiusPx.toFloat()

        // The deadzone removes a *ring*, not a square: subtracting a number from each axis independently
        // would make diagonal movement easier to trigger than cardinal movement.
        val insideDeadzone = rawMagnitude <= profile.deadzonePx
        val usableMagnitude = (rawMagnitude - profile.deadzonePx).coerceAtLeast(0f)
        val normalisedThrow = (usableMagnitude / radius).coerceIn(0f, 1f)

        var targetDx = 0f
        var targetDy = 0f
        if (rawMagnitude > 0f && usableMagnitude > 0f) {
            // Curve shapes the throw as a fraction of the radius; sensitivity then multiplies it, and the
            // single clamp at the end bounds the result. Applying the curve before the gain keeps the
            // curve meaningful at every sensitivity instead of only at 1×.
            val shaped = profile.curve.shape(normalisedThrow) * radius
            val unitX = rawDx / rawMagnitude
            val unitY = rawDy / rawMagnitude
            val gainX = if (profile.invertX) -profile.xSensitivity else profile.xSensitivity
            val gainY = if (profile.invertY) -profile.ySensitivity else profile.ySensitivity
            targetDx = unitX * shaped * profile.sensitivity * gainX
            targetDy = unitY * shaped * profile.sensitivity * gainY
        }

        val targetMagnitude = hypot(targetDx, targetDy)
        val clamped = targetMagnitude > radius
        if (clamped && targetMagnitude > 0f) {
            val scale = radius / targetMagnitude
            targetDx *= scale
            targetDy *= scale
        }

        // Exponential smoothing. Default 0 means alpha 1 and the branch below is an identity; a non-zero
        // value filters the signal, never delays it, so the event is still emitted in this callback.
        val alpha = 1f - profile.smoothing
        smoothedDx += (targetDx - smoothedDx) * alpha
        smoothedDy += (targetDy - smoothedDy) * alpha

        val frame = WheelTouchFrame(
            action = action,
            pointerId = pointerId,
            x = originX + smoothedDx,
            y = originY + smoothedDy,
            downTime = downTime,
            eventTime = max(eventTime, downTime),
            rawDx = rawDx,
            rawDy = rawDy,
            outputDx = smoothedDx,
            outputDy = smoothedDy,
            sensitivity = profile.sensitivity,
            insideDeadzone = insideDeadzone,
            clamped = clamped,
        )
        lastFrame = frame
        processedEvents++
        return frame
    }

    companion object {
        /**
         * The pointer id the injected stream asks for.
         *
         * Android's input dispatcher does not guarantee that an injected `PointerProperties.id` keeps its
         * value if the event is merged into a physical gesture, so this is a best effort, not a promise of
         * coexistence. The diagnostics screen says exactly that rather than claiming multitouch support.
         */
        const val DEFAULT_POINTER_ID = 9

        /**
         * The device id stamped on every injected event.
         *
         * It is deliberately outside the range real input devices use. If the window flag that hands the
         * stream to the game has not reached the dispatcher yet and an untargeted event lands back on a
         * GameCore overlay, that overlay can recognise it as its own and ignore it instead of starting a
         * second, recursive gesture. It is a safety net, not a substitute for targeted injection: a
         * delivered-then-ignored event still did not reach the game, which is why the controller retries
         * the hand-off and reports the measured delay.
         */
        const val INJECTED_DEVICE_ID = 0x00C0FFEE

        /** Platform pointer ids are 0..31. */
        val POINTER_ID_RANGE = 0..31

        /** For rendering a calibration vector without a device: the magnitude of a frame's output. */
        fun magnitude(frame: WheelTouchFrame): Float = hypot(frame.outputDx, frame.outputDy)

        /** The output as a fraction of the profile's clamp radius, for the calibration gauge. */
        fun throwFraction(frame: WheelTouchFrame, profile: WheelProfile): Float {
            val radius = profile.normalised().radiusPx.toFloat()
            if (radius <= 0f) return 0f
            return (magnitude(frame) / radius).coerceIn(0f, 1f)
        }

        /** The smallest and largest input this build will ever emit, for the diagnostics copy. */
        fun bounds(profile: WheelProfile): Pair<Float, Float> {
            val safe = profile.normalised()
            return min(safe.sensitivity, 1f) to max(safe.sensitivity, 1f)
        }
    }
}
