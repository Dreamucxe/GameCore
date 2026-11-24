package com.gamecore.core.wheel

import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent

/**
 * The privileged half of the High Sensitivity Wheel.
 *
 * Shizuku starts this class with `app_process` under shell uid 2000, in a process that has no Activity,
 * no Hilt graph and no GameCore singletons. It therefore does exactly one thing: turn the primitive
 * calls in [IInputInjector] into `MotionEvent`s and hand them to `InputManager.injectInputEvent`.
 *
 * **Why reflection.** `InputManager.injectInputEvent` is a hidden platform API. Shell uid holds
 * `android.permission.INJECT_EVENTS`, so the call is permitted, but the method is not part of the public
 * SDK and the class that owns it moved in Android 14 (`InputManager` → `InputManagerGlobal`). The
 * resolver below tries the documented-by-AOSP shapes in order, remembers the first that works and
 * reports every failure in [diagnostics]. If none resolves, [isReady] is false and the app says so —
 * it does not pretend a decorative wheel is injecting.
 *
 * **What success means.** `injectInputEvent` returning true means the input dispatcher accepted the
 * event. It does not mean a particular game consumed it, that the game's anti-cheat allowed it, or that
 * the event was merged with a physical finger the way the user expects. Those are device facts and the
 * diagnostics screen states them as unverified.
 */
class InputInjectorService : IInputInjector.Stub() {

    private val lock = Any()

    private var resolved: Injector? = null
    private var resolutionError: String? = null

    /** -1 means "no target"; the dispatcher's own `INJECT_TARGET_UID_ALL` value. */
    private var targetUid: Int = NO_TARGET

    /** The optional `injectInputEventToTarget` path, resolved lazily and at most once. */
    private var targeted: TargetedInjector? = null

    private var targetedChecked = false
    private var targetedError: String? = null

    private var active = false
    private var activePointerId = -1
    private var lastX = 0f
    private var lastY = 0f
    private var lastDownTime = 0L

    private var accepted = 0L
    private var rejected = 0L
    private var lastError: String? = null

    init {
        synchronized(lock) { resolveLocked() }
    }

    override fun down(pointerId: Int, x: Float, y: Float, downTime: Long, eventTime: Long): Boolean =
        synchronized(lock) {
            if (pointerId !in WheelTouchProcessor.POINTER_ID_RANGE) {
                lastError = "Pointer id $pointerId is outside Android's 0..31 range."
                rejected++
                return@synchronized false
            }
            // A second DOWN without an UP is a lifecycle bug above us. Release the old stream rather than
            // stacking two pointers the game would have to disentangle.
            if (active) cancelLocked(lastDownTime, eventTime)
            val safeDown = if (downTime > 0L) downTime else eventTime
            val safeTime = eventTime.coerceAtLeast(safeDown)
            val sent = injectLocked(MotionEvent.ACTION_DOWN, pointerId, x, y, safeDown, safeTime)
            if (sent) {
                active = true
                activePointerId = pointerId
                lastX = x
                lastY = y
                lastDownTime = safeDown
            }
            sent
        }

    override fun move(pointerId: Int, x: Float, y: Float, downTime: Long, eventTime: Long): Boolean =
        synchronized(lock) {
            if (!active || pointerId != activePointerId) return@synchronized false
            val safeDown = if (downTime > 0L) downTime else lastDownTime
            val sent = injectLocked(
                MotionEvent.ACTION_MOVE,
                pointerId,
                x,
                y,
                safeDown,
                eventTime.coerceAtLeast(safeDown),
            )
            if (sent) {
                lastX = x
                lastY = y
            }
            sent
        }

    override fun up(pointerId: Int, x: Float, y: Float, downTime: Long, eventTime: Long): Boolean =
        synchronized(lock) {
            if (!active || pointerId != activePointerId) return@synchronized false
            val safeDown = if (downTime > 0L) downTime else lastDownTime
            val sent = injectLocked(
                MotionEvent.ACTION_UP,
                pointerId,
                x,
                y,
                safeDown,
                eventTime.coerceAtLeast(safeDown),
            )
            // The stream is over whether or not the final event was accepted. Keeping `active` true would
            // make a later cancel try to cancel a pointer the dispatcher has already released.
            active = false
            activePointerId = -1
            if (sent) {
                lastX = x
                lastY = y
            }
            sent
        }

    override fun cancel(pointerId: Int, downTime: Long, eventTime: Long): Boolean =
        synchronized(lock) { cancelLocked(downTime, eventTime) }

    override fun releaseAll() {
        synchronized(lock) {
            if (active) {
                cancelLocked(lastDownTime, SystemClock.uptimeMillis())
            }
            active = false
            activePointerId = -1
        }
    }

    override fun isReady(): Boolean = synchronized(lock) {
        if (resolved == null) resolveLocked()
        resolved != null
    }

    override fun isTargeted(): Boolean = synchronized(lock) {
        targetUid != NO_TARGET && resolveTargetedLocked() != null
    }

    override fun setTargetUid(uid: Int) {
        synchronized(lock) {
            val safeUid = uid.takeIf { it >= 0 } ?: NO_TARGET
            if (safeUid == targetUid) return@synchronized
            // A stream must end under the uid that received its DOWN. Controller normally guarantees
            // this, but the privileged binder boundary defends itself too.
            if (active) cancelLocked(lastDownTime, SystemClock.uptimeMillis())
            targetUid = safeUid
        }
    }

    override fun diagnostics(): String = synchronized(lock) {
        val how = resolved?.description ?: "unresolved"
        buildString {
            append("injector=").append(how)
            append("; accepted=").append(accepted)
            append("; rejected=").append(rejected)
            append("; active=").append(active)
            append("; targetUid=").append(if (targetUid == NO_TARGET) "none" else targetUid.toString())
            append("; targeted=").append(
                when {
                    targetUid == NO_TARGET -> "notRequested"
                    targeted != null -> targeted!!.description
                    targetedChecked -> "unavailable"
                    else -> "unresolved"
                },
            )
            lastError?.let { append("; lastError=").append(it) }
            resolutionError?.let { append("; resolveError=").append(it) }
            targetedError?.let { append("; targetError=").append(it) }
        }
    }

    /**
     * Releases everything and leaves. Delayed by a moment so the binder reply to [destroy] can leave the
     * process before `System.exit` tears it down; a caller that sees an exception here would otherwise
     * have no way to distinguish "process gone" from "the helper failed".
     */
    override fun destroy() {
        releaseAll()
        Thread {
            try {
                Thread.sleep(150L)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            System.exit(0)
        }.apply {
            name = "wheel-injector-exit"
            isDaemon = true
            start()
        }
    }

    private fun cancelLocked(downTime: Long, eventTime: Long): Boolean {
        val pointer = activePointerId.takeIf { it >= 0 } ?: return false
        val safeDown = if (downTime > 0L) downTime else lastDownTime
        val sent = injectLocked(
            MotionEvent.ACTION_CANCEL,
            pointer,
            lastX,
            lastY,
            safeDown,
            eventTime.coerceAtLeast(safeDown),
        )
        active = false
        activePointerId = -1
        return sent
    }

    private fun injectLocked(
        action: Int,
        pointerId: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
    ): Boolean {
        if (resolved == null) resolveLocked()
        val injector = resolved ?: run {
            rejected++
            return false
        }
        // A NaN or infinity reaching MotionEvent makes an event the dispatcher can reject or, worse, a
        // coordinate no one can reason about. The wheel's maths is guarded, but this is a binder boundary.
        if (!x.isFinite() || !y.isFinite()) {
            lastError = "Refused a non-finite coordinate."
            rejected++
            return false
        }
        val event = try {
            buildEvent(action, pointerId, x, y, downTime, eventTime)
        } catch (error: Throwable) {
            lastError = "MotionEvent construction failed: ${error.javaClass.simpleName}"
            rejected++
            return false
        }
        val targetedNow = if (targetUid == NO_TARGET) null else resolveTargetedLocked()
        return try {
            val result = if (targetedNow != null) {
                targetedNow.inject(event, targetUid)
            } else {
                injector.inject(event)
            }
            if (result) {
                accepted++
            } else {
                rejected++
                lastError = if (targetedNow != null) {
                    "Targeted dispatcher refused the ${actionName(action)} event for uid $targetUid."
                } else {
                    "InputDispatcher refused the ${actionName(action)} event."
                }
            }
            result
        } catch (denied: SecurityException) {
            rejected++
            lastError = "INJECT_EVENTS denied — the helper is not running as shell uid."
            false
        } catch (error: Throwable) {
            rejected++
            lastError = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(120)}"
            false
        } finally {
            // Safe by the API's ownership rules: `injectInputEvent` copies the event into the dispatcher
            // queue before returning in asynchronous mode. Recycle only after the call has returned, never
            // before and never while another thread could still hold it.
            runCatching { event.recycle() }
        }
    }

    private fun buildEvent(
        action: Int,
        pointerId: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
    ): MotionEvent {
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = pointerId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )
        val coordinates = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            },
        )
        return MotionEvent.obtain(
            downTime,
            eventTime.coerceAtLeast(downTime),
            action,
            1,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            WheelTouchProcessor.INJECTED_DEVICE_ID,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    /**
     * Resolves a working injection call, the first time a method is needed.
     *
     * The ordering is newest-known-shape first. `InputManagerGlobal` was introduced with Android 14;
     * `InputManager.getInstance()` is the shape that works on 8–13. The `IInputManager` service path is
     * the fallback for a build where both façades were changed — it is the binder the façades themselves
     * call, so it is the most stable shape of the three, at the cost of one more reflection hop.
     */
    private fun resolveLocked() {
        if (resolved != null) return
        val failures = mutableListOf<String>()

        val facades = listOf(
            "android.hardware.input.InputManagerGlobal",
            "android.hardware.input.InputManager",
        )
        for (className in facades) {
            try {
                val type = Class.forName(className)
                val instance = staticInstance(type)
                if (instance == null) {
                    failures += "$className has no instance accessor"
                    continue
                }
                val method = type.declaredMethods.firstOrNull {
                    it.name == "injectInputEvent" &&
                        it.parameterTypes.size == 2 &&
                        InputEvent::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                        it.parameterTypes[1] == Int::class.javaPrimitiveType
                }
                if (method == null) {
                    failures += "$className has no injectInputEvent(InputEvent, int)"
                    continue
                }
                method.isAccessible = true
                resolved = Injector(
                    description = className.substringAfterLast('.'),
                    call = { event -> (method.invoke(instance, event, ASYNC_MODE) as? Boolean) == true },
                )
                return
            } catch (error: Throwable) {
                failures += "$className: ${error.javaClass.simpleName}"
            }
        }

        // Direct IInputManager binder. This is the interface both façades proxy to.
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
                .apply { isAccessible = true }
            val binder = getService.invoke(null, "input") as? IBinder
            if (binder != null) {
                val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
                val asInterface = stub.getDeclaredMethod("asInterface", IBinder::class.java)
                    .apply { isAccessible = true }
                val service = asInterface.invoke(null, binder)
                val contract = Class.forName("android.hardware.input.IInputManager")
                val method = contract.getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }
                resolved = Injector(
                    description = "IInputManager binder",
                    call = { event -> (method.invoke(service, event, ASYNC_MODE) as? Boolean) == true },
                )
                return
            }
            failures += "ServiceManager returned no input binder"
        } catch (error: Throwable) {
            failures += "IInputManager: ${error.javaClass.simpleName}"
        }

        resolutionError = failures.joinToString(" | ").take(500)
    }

    /** `getInstance` on the two façades, public on some versions and declared on others. */
    private fun staticInstance(type: Class<*>): Any? {
        val method = type.declaredMethods.firstOrNull {
            it.name == "getInstance" &&
                java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0
        } ?: return null
        method.isAccessible = true
        return method.invoke(null)
    }

    /**
     * Resolves `injectInputEventToTarget(InputEvent, int, int)`, the API 33+ form that makes the input
     * dispatcher verify the topmost touchable window belongs to [targetUid].
     *
     * Deliberately independent of the untargeted path: a build can expose one without the other, and when
     * this is absent the caller falls back to plain `injectInputEvent` rather than losing injection
     * entirely. The one-time scan is cached — a per-event reflection walk on the touch path would be a
     * latency cost the wheel exists to avoid.
     */
    private fun resolveTargetedLocked(): TargetedInjector? {
        if (targetedChecked) return targeted
        targetedChecked = true
        if (targetUid == NO_TARGET) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            targetedError = "injectInputEventToTarget requires Android 13 or newer"
            return null
        }
        val failures = mutableListOf<String>()

        val facades = listOf(
            "android.hardware.input.InputManagerGlobal",
            "android.hardware.input.InputManager",
        )
        for (className in facades) {
            try {
                val type = Class.forName(className)
                val instance = staticInstance(type)
                if (instance == null) {
                    failures += "$className has no instance accessor"
                    continue
                }
                val method = targetMethod(type)
                if (method == null) {
                    failures += "$className has no injectInputEventToTarget(InputEvent, int, int)"
                    continue
                }
                method.isAccessible = true
                targeted = TargetedInjector(
                    description = "${className.substringAfterLast('.')}.injectInputEventToTarget",
                    call = { event, uid ->
                        (method.invoke(instance, event, ASYNC_MODE, uid) as? Boolean) == true
                    },
                )
                return targeted
            } catch (error: Throwable) {
                failures += "$className: ${error.javaClass.simpleName}"
            }
        }

        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
                .apply { isAccessible = true }
            val binder = getService.invoke(null, "input") as? IBinder
            if (binder == null) {
                failures += "ServiceManager returned no input binder"
            } else {
                val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
                val asInterface = stub.getDeclaredMethod("asInterface", IBinder::class.java)
                    .apply { isAccessible = true }
                val service = asInterface.invoke(null, binder)
                val contract = Class.forName("android.hardware.input.IInputManager")
                val method = targetMethod(contract)
                if (method == null) {
                    failures += "IInputManager has no injectInputEventToTarget(InputEvent, int, int)"
                } else {
                    method.isAccessible = true
                    targeted = TargetedInjector(
                        description = "IInputManager.injectInputEventToTarget",
                        call = { event, uid ->
                            (method.invoke(service, event, ASYNC_MODE, uid) as? Boolean) == true
                        },
                    )
                    return targeted
                }
            }
        } catch (error: Throwable) {
            failures += "IInputManager: ${error.javaClass.simpleName}"
        }

        targetedError = failures.joinToString(" | ").take(500)
        return null
    }

    private fun targetMethod(type: Class<*>): java.lang.reflect.Method? {
        val candidates: List<java.lang.reflect.Method> =
            type.declaredMethods.toList() + type.methods.toList()
        return candidates.firstOrNull {
            it.name == "injectInputEventToTarget" &&
                it.parameterCount == 3 &&
                InputEvent::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                it.parameterTypes[2] == Int::class.javaPrimitiveType
        }
    }

    private fun actionName(action: Int): String = when (action) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        else -> action.toString()
    }

    private class Injector(
        val description: String,
        val call: (InputEvent) -> Boolean,
    ) {
        fun inject(event: InputEvent): Boolean = call(event)
    }

    private class TargetedInjector(
        val description: String,
        val call: (InputEvent, Int) -> Boolean,
    ) {
        fun inject(event: InputEvent, uid: Int): Boolean = call(event, uid)
    }

    private companion object {
        /** `InputManager.INJECT_INPUT_EVENT_MODE_ASYNC`. */
        const val ASYNC_MODE = 0

        /** The dispatcher's "no target uid" value, also `UserHandle.USER_ALL`. */
        const val NO_TARGET = -1
    }
}
