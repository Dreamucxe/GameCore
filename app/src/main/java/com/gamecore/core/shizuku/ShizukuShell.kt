package com.gamecore.core.shizuku

import android.content.Context
import android.content.pm.PackageManager
import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.model.ShizukuState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Shizuku-backed shell, ported from ProcessLens where this connect/check/fallback
 * flow is already proven on this device.
 *
 * Shizuku hands an app the authority `adb shell` has, through a binder the user
 * starts themselves. That is genuinely more than a normal app gets — `dumpsys
 * display` becomes readable, `settings put system peak_refresh_rate` becomes
 * writable — and it is emphatically *not* root. Nothing in this class presents it as
 * root, and nothing in GameCore requires it.
 *
 * Two defensive habits run through the whole file, both of them learned rather than
 * theoretical:
 *
 *  * Every Shizuku symbol is touched inside a `try` that catches [Throwable], not
 *    [Exception]. The Shizuku API is a thin wrapper over a binder plus reflection
 *    into hidden platform APIs; on a device where the manager app is absent, ancient
 *    or has been frozen, what fails is class loading or a reflected lookup, and
 *    those arrive as [NoClassDefFoundError] and [NoSuchMethodError]. Catching
 *    `Exception` here would let an optional feature crash the app.
 *  * The state is never inferred from a build-time constant or cached across a
 *    binder death. [state] is recomputed on demand and published as a [StateFlow] so
 *    the Shizuku screen updates the moment the service stops.
 */
@Singleton
class ShizukuShell @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ElevatedShell {

    override val accessLevel: AccessLevel = AccessLevel.SHIZUKU

    private val _state = MutableStateFlow(computeState())

    /** Observable connection state, for the Shizuku screen and the capability layer. */
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    /**
     * One command at a time.
     *
     * Shizuku's `newProcess` spawns a real process on the other side of a binder. A
     * profile being applied fires several settings writes at once, and letting six
     * `settings put` processes exist simultaneously on a low-memory device is a
     * needless risk for operations that complete in milliseconds anyway.
     */
    private val executionLock = Mutex()

    /**
     * Set when a permission request has been made and its result not yet observed.
     * Prevents the Shizuku screen from firing a second dialog while the first is up.
     */
    @Volatile
    private var permissionRequestInFlight = false

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        permissionRequestInFlight = false
        refresh()
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { refresh() }

    /**
     * Registers the three lifecycle listeners.
     *
     * Called once from `Application.onCreate`. Guarded because on a device with no
     * Shizuku at all, registering a listener is itself a call into a class that may
     * not load — and this runs during application startup, where a throw is a launch
     * crash.
     */
    fun attachListeners() {
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        } catch (error: Throwable) {
            // Shizuku is absent or too old. The app runs without it; state() already
            // reports that honestly.
        }
        refresh()
    }

    fun detachListeners() {
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (error: Throwable) {
            // Nothing was registered, or the class is gone. Either way there is
            // nothing to unregister.
        }
    }

    /** Recomputes and republishes the state. Cheap; safe to call from a UI event. */
    fun refresh(): ShizukuState = computeState().also { _state.value = it }

    /**
     * Distinguishes all six states without blocking on the binder.
     *
     * `pingBinder` is a one-way liveness check rather than a transaction, so this is
     * safe to call on every capability refresh and from the main thread.
     */
    private fun computeState(): ShizukuState = try {
        when {
            !Shizuku.pingBinder() ->
                if (isManagerInstalled()) {
                    ShizukuState.INSTALLED_NOT_RUNNING
                } else {
                    ShizukuState.NOT_INSTALLED
                }
            // Pre-v11 used a permission model that no longer exists.
            Shizuku.isPreV11() -> ShizukuState.VERSION_UNSUPPORTED
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                ShizukuState.RUNNING_PERMISSION_GRANTED
            // Shizuku reports a rationale only after an explicit denial, which is what
            // separates "declined" from "never asked".
            Shizuku.shouldShowRequestPermissionRationale() ->
                ShizukuState.RUNNING_PERMISSION_DENIED
            else -> ShizukuState.RUNNING_PERMISSION_UNKNOWN
        }
    } catch (error: Throwable) {
        // NoClassDefFoundError where the provider never initialised, or a binder
        // death race between the ping and the permission check. Either way the
        // honest answer is that it is not usable.
        if (isManagerInstalled()) ShizukuState.INSTALLED_NOT_RUNNING else ShizukuState.NOT_INSTALLED
    }

    /**
     * Whether a Shizuku manager package is installed.
     *
     * Needs the `<queries>` entries in the manifest to return anything truthful on
     * API 30+, where package visibility is filtered by default — without them this
     * would report "not installed" on a device where Shizuku is sitting on the home
     * screen.
     */
    private fun isManagerInstalled(): Boolean {
        val pm = context.packageManager
        return MANAGER_PACKAGES.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (notFound: PackageManager.NameNotFoundException) {
                false
            } catch (error: Throwable) {
                false
            }
        }
    }

    override suspend fun isAvailable(): Boolean = refresh().isUsable

    /**
     * Asks Shizuku for permission.
     *
     * Returns the state as it stands after the request is *issued*, not after the
     * user answers — the answer arrives asynchronously on [permissionListener],
     * which republishes [state]. A caller that needs the outcome collects the flow;
     * one that just wants the dialog up gets it without a suspended wait that could
     * outlive the screen.
     */
    suspend fun requestPermission(): ShizukuState = withContext(io) {
        val current = refresh()
        if (current == ShizukuState.RUNNING_PERMISSION_GRANTED) return@withContext current
        // Nothing to ask when the service is not running or is too old to have this API.
        if (!current.isInstalled ||
            current == ShizukuState.INSTALLED_NOT_RUNNING ||
            current == ShizukuState.VERSION_UNSUPPORTED
        ) {
            return@withContext current
        }
        if (permissionRequestInFlight) return@withContext current

        try {
            permissionRequestInFlight = true
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (error: Throwable) {
            permissionRequestInFlight = false
        }
        refresh()
    }

    /**
     * Runs one enumerated command.
     *
     * Both pipes are drained before waiting on the process. A `dumpsys display` on a
     * device with several displays fills the pipe buffer, and a `waitFor` that has
     * not been read from deadlocks against a full buffer — the classic mistake with
     * this API, and one that would hang the sampler thread rather than fail.
     */
    override suspend fun execute(command: ShellCommand, timeoutMillis: Long): ShellResult =
        withContext(io) {
            if (!isAvailable()) {
                return@withContext ShellResult.failure(
                    "Shizuku is not running, or permission has not been granted.",
                    accessLevel,
                )
            }
            executionLock.withLock { runProcess(command, timeoutMillis) }
        }

    private fun runProcess(command: ShellCommand, timeoutMillis: Long): ShellResult {
        val started = System.currentTimeMillis()
        var process: Process? = null
        return try {
            process = newProcess(command.argv.toTypedArray())
                ?: return ShellResult.failure(
                    "Shizuku could not start a process on this device.",
                    accessLevel,
                )

            val out = process.inputStream.readAllTextSafely(MAX_OUTPUT_BYTES)
            val err = process.errorStream.readAllTextSafely(MAX_ERROR_BYTES)

            if (!process.waitForTimeout(timeoutMillis)) {
                process.destroy()
                return ShellResult.timeout(accessLevel, timeoutMillis)
            }
            ShellResult(
                exitCode = process.exitValue(),
                stdout = out,
                stderr = err,
                accessLevel = accessLevel,
                durationMillis = System.currentTimeMillis() - started,
            )
        } catch (error: Throwable) {
            ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = error.message ?: error::class.java.simpleName,
                accessLevel = accessLevel,
                durationMillis = System.currentTimeMillis() - started,
            )
        } finally {
            try {
                process?.destroy()
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * `Shizuku.newProcess` is a hidden member of the Shizuku API — annotated
     * `@hide`, present in every release since v11, deliberately not part of the
     * published surface — so it is reached by reflection. Wrapped so that a signature
     * change in a future Shizuku degrades the elevated path to "unavailable" instead
     * of throwing out of a sampler.
     */
    private fun newProcess(argv: Array<String>): Process? = try {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        method.invoke(null, argv, null, null) as? Process
    } catch (error: Throwable) {
        null
    }

    companion object {
        const val PERMISSION_REQUEST_CODE = 4271

        private val MANAGER_PACKAGES = listOf(
            "moe.shizuku.privileged.api",
            "moe.shizuku.manager",
        )

        /**
         * Caps on captured output. `dumpsys SurfaceFlinger --latency` is small, but
         * `dumpsys display` on a foldable is not, and an unbounded read on a 4 GB
         * phone that is currently running a game is a real OOM risk.
         */
        private const val MAX_OUTPUT_BYTES = 2 * 1024 * 1024
        private const val MAX_ERROR_BYTES = 32 * 1024
    }
}

/**
 * Reads a stream to text under a hard byte cap, never throwing.
 *
 * Truncation is marked inline so a parser cannot mistake a cut-off dump for a
 * complete one and conclude, say, that the display has one mode.
 */
internal fun InputStream.readAllTextSafely(maxBytes: Int): String = try {
    use { stream ->
        val buffer = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(chunk)
            if (read <= 0) break
            val allowed = minOf(read, maxBytes - total)
            if (allowed > 0) {
                buffer.write(chunk, 0, allowed)
                total += allowed
            }
            if (total >= maxBytes) {
                buffer.write("\n[output truncated at $maxBytes bytes]\n".toByteArray())
                break
            }
        }
        buffer.toString("UTF-8")
    }
} catch (error: Throwable) {
    ""
}

/**
 * `Process.waitFor(timeout, unit)` exists from API 26, which is this app's minSdk,
 * but the `Process` Shizuku returns is a remote proxy whose implementation need not
 * honour it. Polling `exitValue()` as a fallback keeps a hung command from blocking
 * a sampler tick forever.
 */
internal fun Process.waitForTimeout(timeoutMillis: Long): Boolean {
    try {
        return waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (error: Throwable) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            try {
                exitValue()
                return true
            } catch (notYet: IllegalThreadStateException) {
                try {
                    Thread.sleep(POLL_INTERVAL_MILLIS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return false
    }
}

private const val POLL_INTERVAL_MILLIS = 25L
