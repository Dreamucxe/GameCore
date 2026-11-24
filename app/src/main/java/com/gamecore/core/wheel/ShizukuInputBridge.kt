package com.gamecore.core.wheel

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.gamecore.BuildConfig
import com.gamecore.core.model.ShizukuState
import com.gamecore.core.shizuku.ShizukuManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The seven situations the wheel's privileged backend can be in.
 *
 * They are separate because each one needs a different sentence and a different next action. Collapsing
 * them into "injection unavailable" is how a screen ends up telling someone to install Shizuku they
 * already have, or to grant a permission that is already granted.
 */
enum class InjectionBackendStatus(val label: String) {
    /** Nothing has been checked yet. Claims nothing. */
    UNKNOWN("Not checked"),

    /** No Shizuku/Sui package on the device. */
    SHIZUKU_MISSING("Shizuku not installed"),

    /** Installed but its service is not running. It has to be started after every reboot. */
    SHIZUKU_STOPPED("Shizuku not running"),

    /** Running, but GameCore has not been granted its API permission. */
    PERMISSION_REQUIRED("Permission required"),

    /** Permission granted; the privileged helper process is starting or reconnecting. */
    BINDING("Binding injector"),

    /** Bound and the reflected `injectInputEvent` path resolved. Injection can be attempted. */
    READY("Injection ready"),

    /** The helper was ready and the connection dropped. The next arm attempts a reconnect. */
    CONNECTION_LOST("Connection lost"),

    /** Bound, but this Android build's input façade could not be resolved or injection is denied. */
    UNSUPPORTED("Injection unsupported"),

    /** A binding error that is not one of the above. [InjectionState.detail] carries it. */
    ERROR("Backend error"),
    ;
}

/**
 * Everything the overview and diagnostics sections need to describe the backend honestly.
 *
 * [binderMicros] is an exponential average of *the AIDL call round trip*, measured by this class. It is
 * not end-to-end latency: it excludes the overlay's touch delivery, the game's frame, and the display's
 * scan-out. The diagnostics copy says so. There is no `zero latency` claim anywhere in the feature.
 */
data class InjectionState(
    val status: InjectionBackendStatus = InjectionBackendStatus.UNKNOWN,
    val detail: String = "The privileged injector has not been checked yet.",
    val bound: Boolean = false,
    val isReady: Boolean = false,
    /**
     * True when the helper resolved the API 33+ targeted path and a game uid is set.
     *
     * This is a capability fact, not a promise that the game accepted an event. It lets the controller
     * attempt the first DOWN immediately and retry a target-mismatch refusal instead of waiting a fixed
     * interval for the wheel's `FLAG_NOT_TOUCHABLE` update to land.
     */
    val targeted: Boolean = false,
    val acceptedEvents: Long = 0L,
    val rejectedEvents: Long = 0L,
    /** Average synchronous AIDL call duration. Not a claim about when the game sees the touch. */
    val binderMicros: Int = 0,
    val remoteDiagnostics: String = "",
    val lastError: String? = null,
) {
    val canInject: Boolean get() = status == InjectionBackendStatus.READY && isReady
}

/**
 * Binds, reconnects and talks to [InputInjectorService] across Shizuku's UserService binder.
 *
 * The bridge owns the connection lifecycle and nothing else. It does not know what a wheel profile is,
 * whether the wheel is armed, or what a MotionEvent means beyond "the injector accepted it". That keeps
 * the dangerous half — a privileged helper injecting touches into other apps — behind a small contract
 * and makes its state observable to the UI without the UI holding a binder.
 *
 * **Lifetime.** [arm] binds, [disarm] releases every pointer and unbinds. Binding is deliberately not
 * automatic on process start: a privileged helper should exist because the user asked to inject, not
 * because GameCore launched. A dropped binder is noticed and reported; the next [arm] retries.
 */
@Singleton
class ShizukuInputBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizuku: ShizukuManager,
) {

    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(InjectionState())
    val state: StateFlow<InjectionState> = _state.asStateFlow()

    @Volatile
    private var injector: IInputInjector? = null

    /**
     * The uid events should be addressed to, or -1 for the dispatcher's default.
     *
     * Kept here rather than in the helper so a rebind after Shizuku restarts re-applies the same target.
     * The controller resolves it from the foreground game package; this class only carries the integer.
     */
    @Volatile
    private var targetUid: Int = -1

    @Volatile
    private var wantBound = false

    /** Guards the binder reference and the event counters; never held across a binder call to the UI. */
    private val lock = Any()

    private var binderSamples = 0
    private var binderAverageMicros = 0L

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, InputInjectorService::class.java.name),
        )
            // Not a daemon: the helper exists while the wheel is armed. [disarm] releases pointers and
            // asks it to exit, so no shell-uid process lingers after the feature is switched off.
            .daemon(false)
            .processNameSuffix("wheel")
            .tag(USER_SERVICE_TAG)
            .version(BuildConfig.VERSION_CODE)
            .debuggable(BuildConfig.DEBUG)
    }

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = binder?.let { IInputInjector.Stub.asInterface(it) }
            if (service == null) {
                injector = null
                wantBound = false
                _state.value = InjectionState(
                    status = InjectionBackendStatus.ERROR,
                    detail = "Shizuku connected but returned no injector binder.",
                    lastError = "null binder",
                )
                return
            }
            injector = service
            // Apply the target before the state becomes READY, so no sample can be sent untargeted while
            // the game is known. A failure here is not fatal: the helper falls back to untargeted injection
            // and reports that in its own diagnostics.
            runCatching { service.setTargetUid(targetUid) }
            val ready = runCatching { service.isReady() }.getOrDefault(false)
            val diagnostics = runCatching { service.diagnostics() }.getOrDefault("")
            val targeted = if (ready) {
                runCatching { service.isTargeted() }.getOrDefault(false)
            } else {
                false
            }
            _state.value = if (ready) {
                InjectionState(
                    status = InjectionBackendStatus.READY,
                    detail = "Shell-identity input helper bound. The foreground game uid is used for " +
                        "targeted asynchronous injection when this Android build supports it; otherwise " +
                        "the helper falls back to InputManager.injectInputEvent.",
                    bound = true,
                    isReady = true,
                    targeted = targeted,
                    remoteDiagnostics = diagnostics,
                )
            } else {
                // A helper with no resolved injection path has no useful resident job. Tear it down now
                // and require an explicit retry after an app/OS update rather than leave a shell process
                // bound behind a disabled wheel.
                injector = null
                wantBound = false
                runCatching { service.releaseAll() }
                runCatching { service.destroy() }
                main.post {
                    runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
                }
                InjectionState(
                    status = InjectionBackendStatus.UNSUPPORTED,
                    detail = "The helper is bound but could not resolve an input injection path on " +
                        "this Android build. $diagnostics",
                    bound = false,
                    isReady = false,
                    remoteDiagnostics = diagnostics,
                    lastError = "injectInputEvent unavailable",
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            injector = null
            wantBound = false
            _state.value = InjectionState(
                status = InjectionBackendStatus.CONNECTION_LOST,
                detail = "The privileged helper disconnected. Arm the wheel to reconnect.",
                bound = false,
                lastError = "service disconnected",
            )
        }

        override fun onBindingDied(name: ComponentName?) {
            injector = null
            wantBound = false
            _state.value = InjectionState(
                status = InjectionBackendStatus.CONNECTION_LOST,
                detail = "Shizuku's binder died. Start Shizuku again, then arm the wheel.",
                bound = false,
                lastError = "binding died",
            )
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        injector = null
        wantBound = false
        _state.value = InjectionState(
            status = InjectionBackendStatus.CONNECTION_LOST,
            detail = "Shizuku stopped. The wheel has been disarmed by the loss of its injector.",
            bound = false,
            lastError = "Shizuku binder dead",
        )
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        // Only reconnect a helper the user had already armed. A Shizuku restart must not silently
        // recreate a privileged injector for a wheel that is off.
        if (wantBound && injector == null) bind()
    }

    /**
     * Checks Shizuku and, when it is usable, starts the helper.
     *
     * Returns the state as it stands immediately. Binding is asynchronous, so the caller reads [state]
     * for BINDING → READY. Nothing here throws: no Shizuku, an old Shizuku, a denied permission and a
     * refused bind are all states, because a wheel switch must not crash a game.
     */
    fun arm(): InjectionState {
        wantBound = true
        val current = refreshShizuku()
        when {
            current == ShizukuState.NOT_INSTALLED -> publish(
                InjectionBackendStatus.SHIZUKU_MISSING,
                "Shizuku is not installed. The wheel needs Shizuku or Sui for shell-level touch injection.",
            )
            current == ShizukuState.INSTALLED_NOT_RUNNING -> publish(
                InjectionBackendStatus.SHIZUKU_STOPPED,
                "Shizuku is installed but not running. Start it, then arm the wheel again.",
            )
            current == ShizukuState.VERSION_UNSUPPORTED -> publish(
                InjectionBackendStatus.UNSUPPORTED,
                "This Shizuku version predates the UserService API GameCore uses. Update Shizuku.",
            )
            current != ShizukuState.RUNNING_PERMISSION_GRANTED -> publish(
                InjectionBackendStatus.PERMISSION_REQUIRED,
                "Shizuku is running but GameCore does not have its permission. Grant it on the Shizuku screen.",
            )
            else -> bind()
        }
        if (current != ShizukuState.RUNNING_PERMISSION_GRANTED) {
            // A failed arm is complete, not a standing request. Starting or granting Shizuku later must
            // not create a shell helper until the user explicitly enables the wheel again.
            wantBound = false
        }
        return _state.value
    }

    /** Releases every injected pointer and tears the helper down. Idempotent. */
    fun disarm() {
        wantBound = false
        val service = injector
        injector = null
        if (service != null) {
            runCatching { service.releaseAll() }
            runCatching { service.destroy() }
        }
        if (Shizuku.pingBinder()) {
            runCatching {
                main.post {
                    runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
                }
            }
        }
        _state.value = _state.value.copy(
            status = InjectionBackendStatus.UNKNOWN,
            detail = "The privileged injector is stopped. No pointer is held.",
            bound = false,
            isReady = false,
            remoteDiagnostics = "",
        )
    }

    /**
     * Re-reads the backend after a permission dialog or a trip to Shizuku.
     *
     * Called from the UI's permission flow and on resume. It never starts a helper; [arm] does that.
     */
    fun refresh() {
        val current = refreshShizuku()
        if (injector != null) {
            val ready = runCatching { injector?.isReady() == true }.getOrDefault(false)
            val diagnostics = runCatching { injector?.diagnostics().orEmpty() }.getOrDefault("")
            _state.value = _state.value.copy(
                status = if (ready) InjectionBackendStatus.READY else InjectionBackendStatus.UNSUPPORTED,
                isReady = ready,
                bound = true,
                remoteDiagnostics = diagnostics,
            )
            return
        }
        when {
            current == ShizukuState.NOT_INSTALLED -> publish(
                InjectionBackendStatus.SHIZUKU_MISSING,
                "Shizuku is not installed.",
            )
            current == ShizukuState.INSTALLED_NOT_RUNNING -> publish(
                InjectionBackendStatus.SHIZUKU_STOPPED,
                "Shizuku is installed but not running.",
            )
            current == ShizukuState.VERSION_UNSUPPORTED -> publish(
                InjectionBackendStatus.UNSUPPORTED,
                "This Shizuku version is too old for UserService.",
            )
            current != ShizukuState.RUNNING_PERMISSION_GRANTED -> publish(
                InjectionBackendStatus.PERMISSION_REQUIRED,
                "Shizuku is running; GameCore needs its API permission.",
            )
            _state.value.status == InjectionBackendStatus.UNKNOWN -> publish(
                InjectionBackendStatus.UNKNOWN,
                "Shizuku is ready. Arm the wheel to bind the injector.",
            )
        }
    }

    /**
     * One DOWN/MOVE/UP/CANCEL call.
     *
     * Synchronous on purpose: the caller processes a pointer callback and needs the acceptance result
     * before reporting the sample. The measured round trip is kept as a diagnostic, not used to delay
     * anything. Binder failures never throw at the caller — they disarm and return false, because an
     * exception in a touch callback would take down the overlay service.
     */
    fun send(frame: WheelTouchFrame): Boolean {
        val service = injector
        if (service == null) {
            // A dead binder is not a reason to crash a game. Release the app-side gesture and let the
            // caller surface "connection lost" in diagnostics.
            if (_state.value.status == InjectionBackendStatus.READY) {
                _state.value = _state.value.copy(
                    status = InjectionBackendStatus.CONNECTION_LOST,
                    isReady = false,
                    bound = false,
                    detail = "The injector binder disappeared. Arm the wheel to reconnect.",
                )
            }
            return false
        }
        val started = System.nanoTime()
        val accepted = try {
            when (frame.action) {
                WheelAction.DOWN -> service.down(
                    frame.pointerId, frame.x, frame.y, frame.downTime, frame.eventTime,
                )
                WheelAction.MOVE -> service.move(
                    frame.pointerId, frame.x, frame.y, frame.downTime, frame.eventTime,
                )
                WheelAction.UP -> service.up(
                    frame.pointerId, frame.x, frame.y, frame.downTime, frame.eventTime,
                )
                WheelAction.CANCEL -> service.cancel(
                    frame.pointerId, frame.downTime, frame.eventTime,
                )
            }
        } catch (error: Throwable) {
            injector = null
            _state.value = _state.value.copy(
                status = InjectionBackendStatus.CONNECTION_LOST,
                isReady = false,
                bound = false,
                detail = "The injector call failed (${error.javaClass.simpleName}).",
                lastError = error.message?.take(160),
            )
            false
        }
        val elapsedMicros = ((System.nanoTime() - started) / 1_000L).coerceAtLeast(0L)
        synchronized(lock) {
            binderSamples++
            binderAverageMicros = if (binderSamples <= 1) {
                elapsedMicros
            } else {
                // 1/8 EWMA: responsive to a changed device load, not a single outlier for the rest of the session.
                binderAverageMicros + (elapsedMicros - binderAverageMicros) / 8L
            }
        }
        _state.value = _state.value.copy(
            acceptedEvents = _state.value.acceptedEvents + if (accepted) 1 else 0,
            rejectedEvents = _state.value.rejectedEvents + if (accepted) 0 else 1,
            binderMicros = synchronized(lock) { binderAverageMicros.toInt() },
            lastError = if (accepted) _state.value.lastError else {
                "The dispatcher returned false for ${frame.action.name}."
            },
        )
        return accepted
    }

    /** Sends a cancel for whatever the helper still holds, without needing an app-side frame. */
    fun releaseAll() {
        val service = injector ?: return
        runCatching { service.releaseAll() }
    }

    /**
     * Re-addresses subsequent events to [uid], or to the dispatcher default with -1.
     *
     * Best-effort: the helper falls back to untargeted injection when the targeted hidden method is not
     * present on the platform. A dead binder is ignored; the value is re-applied on the next connect.
     */
    fun setTargetUid(uid: Int) {
        if (targetUid == uid) return
        targetUid = uid
        val service = injector ?: return
        runCatching { service.setTargetUid(uid) }
    }

    /**
     * Runs the remote diagnostics call. Returns the bridge's own state when the helper is gone.
     *
     * This is the only bridge method the UI calls directly, and it is a read. It cannot enable anything.
     */
    fun readDiagnostics(): String {
        val service = injector ?: return "No privileged helper is bound."
        return runCatching { service.diagnostics() }
            .getOrElse { "Diagnostics call failed: ${it.javaClass.simpleName}" }
            .also { _state.value = _state.value.copy(remoteDiagnostics = it) }
    }

    private fun bind() {
        injector = null
        _state.value = InjectionState(
            status = InjectionBackendStatus.BINDING,
            detail = "Starting Shizuku's shell-identity helper…",
            bound = false,
        )
        main.post {
            if (!wantBound) return@post
            try {
                if (!Shizuku.pingBinder()) {
                    wantBound = false
                    publish(
                        InjectionBackendStatus.SHIZUKU_STOPPED,
                        "Shizuku stopped while the injector was binding.",
                    )
                    return@post
                }
                if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    wantBound = false
                    publish(
                        InjectionBackendStatus.PERMISSION_REQUIRED,
                        "Shizuku permission was revoked before the injector could bind.",
                    )
                    return@post
                }
                Shizuku.bindUserService(userServiceArgs, connection)
            } catch (error: Throwable) {
                wantBound = false
                _state.value = InjectionState(
                    status = InjectionBackendStatus.ERROR,
                    detail = "Could not bind the injector: ${error.message ?: error.javaClass.simpleName}",
                    lastError = error.javaClass.simpleName,
                )
            }
        }
    }

    private fun refreshShizuku(): ShizukuState = runCatching { shizuku.refresh() }
        .getOrDefault(ShizukuState.NOT_INSTALLED)

    private fun publish(status: InjectionBackendStatus, detail: String) {
        _state.value = InjectionState(status = status, detail = detail)
    }

    init {
        // Sticky listeners are process-wide and cheap. They let a Shizuku restart or death update the
        // wheel's state even when the settings screen is not open. Guarded because on a device without
        // Shizuku the class itself can fail to initialise.
        runCatching {
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        }
    }

    private companion object {
        /**
         * Stable identity for the helper. Changing it launches a second process rather than reusing the
         * one whose class shape may have changed between app versions.
         */
        const val USER_SERVICE_TAG = "gamecore-wheel-injector"
    }
}
