package com.gamecore.core.shizuku

import android.content.Context
import android.content.pm.PackageManager
import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.model.ShizukuState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
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
     *
     * The cost of serialising is that one command that does not finish is every
     * command's problem, which is why [execute] bounds both the command and the wait
     * for this lock rather than only the command.
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
     * Runs one enumerated command, and returns inside [timeoutMillis] whatever the
     * device does with it.
     *
     * Two independent bounds, and the second one is not decoration. [drainWithin] is
     * where the first one lives and why it has to exist at all; this adds the wait for
     * [executionLock], so a command that somehow still fails to let go costs the app one
     * honest failure per caller rather than every elevated reading it has for the life
     * of the process.
     */
    override suspend fun execute(command: ShellCommand, timeoutMillis: Long): ShellResult =
        withContext(io) {
            if (!isAvailable()) {
                return@withContext ShellResult.failure(
                    "Shizuku is not running, or permission has not been granted.",
                    accessLevel,
                )
            }
            withTimeoutOrNull(QUEUE_TIMEOUT_MILLIS) {
                executionLock.withLock { runProcess(command, timeoutMillis) }
            } ?: ShellResult.failure(
                "The elevated shell did not free up, so this command was not run.",
                accessLevel,
            )
        }

    /**
     * Spawns the process, drains it inside its budget, and reports what it did.
     *
     * The drain itself is [drainWithin], which is where the bound lives and the one
     * part of this that can be checked without a device. What is left here is the
     * result assembly and the second half of the budget: a wait that gets whatever
     * the drain did not spend, rather than a fresh copy of the whole allowance.
     */
    private suspend fun runProcess(command: ShellCommand, timeoutMillis: Long): ShellResult {
        val started = System.currentTimeMillis()
        val process = newProcess(command.argv.toTypedArray())
            ?: return ShellResult.failure(
                "Shizuku could not start a process on this device.",
                accessLevel,
            )

        return try {
            val drained = process.drainWithin(timeoutMillis, io)
                ?: return ShellResult.timeout(accessLevel, timeoutMillis)

            val remaining = timeoutMillis - (System.currentTimeMillis() - started)
            if (!process.waitForTimeout(remaining.coerceAtLeast(0))) {
                return ShellResult.timeout(accessLevel, timeoutMillis)
            }
            ShellResult(
                exitCode = process.exitValue(),
                stdout = drained.stdout,
                stderr = drained.stderr,
                accessLevel = accessLevel,
                durationMillis = System.currentTimeMillis() - started,
            )
        } catch (cancelled: CancellationException) {
            // A cancelled read is not a failed command, and reporting it as one would have a
            // screen that closed mid-sample print a device error it invented.
            throw cancelled
        } catch (error: Throwable) {
            ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = error.message ?: error::class.java.simpleName,
                accessLevel = accessLevel,
                durationMillis = System.currentTimeMillis() - started,
            )
        } finally {
            process.destroyQuietly()
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
         * How long a command will wait for the one in front of it before giving up.
         *
         * Longer than [ElevatedShell.LONG_TIMEOUT], deliberately: a genuinely slow
         * `dumpsys` ahead of you in the queue is not a stuck shell, and refusing a
         * command because another one was slow would be a fault invented by the fix.
         * Short enough, equally deliberately, that a shell nothing can unstick shows
         * up as a sentence on the screen instead of a spinner that never resolves.
         */
        private const val QUEUE_TIMEOUT_MILLIS = 20_000L
    }
}

/** What a process said for itself, once both of its pipes have been read to the end. */
internal data class Drained(val stdout: String, val stderr: String)

/**
 * Reads both of a process's pipes to the end, or gives up and returns null.
 *
 * Split out of [ShizukuShell] because this is the decision the fix rests on and it is
 * the one part of the shell that can be checked on a machine with no Shizuku: given a
 * process that stops talking without closing its pipes, does the read end?
 *
 * Both pipes are drained before anyone waits on the process, and that ordering is not
 * optional — a `dumpsys display` on a device with several displays fills the pipe
 * buffer, and a `waitFor` that has not been read from deadlocks against a full buffer.
 * It has its own failure mode, though, and it is the one that produced the bug this
 * function exists for: a read on a pipe whose writer is alive and silent blocks until
 * that writer closes it. A timeout applied *after* the drain cannot interrupt that, and
 * neither can cancellation, because a native read is not a suspension point. A remote
 * process wedged behind a busy `system_server` therefore used to hold the shell's
 * execution lock for the life of the app's process, and every elevated read after it
 * queued behind it forever — which is why the screen resolution section could sit on
 * "Measuring…" until GameCore was force-stopped.
 *
 * So the drain is watched, and the watchdog closes the descriptors rather than
 * cancelling anything. Android signals the threads blocked on a file descriptor when it
 * is closed, so the read fails, [readAllTextSafely] returns the empty string it returns
 * for every other unreadable stream, and this returns null inside [timeoutMillis] with
 * the lock released. `destroy` follows the close rather than replacing it: killing the
 * remote process is the tidy end and the one that stops a stray `dumpsys` costing CPU,
 * but it is a binder round trip, and a binder that has stopped answering is one of the
 * ways to arrive here.
 *
 * [watchdogDispatcher] has to be one with a thread to spare — the drain blocks the
 * caller's, so a watchdog sharing it would not run until the thing it is watching had
 * already finished.
 */
internal suspend fun Process.drainWithin(
    timeoutMillis: Long,
    watchdogDispatcher: CoroutineDispatcher,
): Drained? {
    // Held as locals so the watchdog closes the same two objects the drain is reading.
    val out = inputStream
    val err = errorStream
    val abandoned = AtomicBoolean(false)

    return coroutineScope {
        val watchdog = launch(watchdogDispatcher) {
            delay(timeoutMillis)
            abandoned.set(true)
            out.closeQuietly()
            err.closeQuietly()
            destroyQuietly()
        }

        val stdout = out.readAllTextSafely(MAX_OUTPUT_BYTES)
        val stderr = err.readAllTextSafely(MAX_ERROR_BYTES)
        watchdog.cancel()

        if (abandoned.get()) null else Drained(stdout, stderr)
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
 * Closes a descriptor, and in doing so lets go of any thread blocked reading it.
 *
 * The reason this exists rather than an inline `try`/`catch`: the close is not
 * housekeeping here, it is the mechanism. Android signals the threads blocked on a
 * file descriptor when that descriptor is closed, which is the only thing that ends
 * an uninterruptible read on a pipe whose writer has gone quiet without closing it.
 * The exception it raises on the reading side is caught there — see
 * [readAllTextSafely] — and read as "nothing further was said", which is true.
 */
internal fun Closeable.closeQuietly() {
    try {
        close()
    } catch (ignored: Throwable) {
    }
}

/**
 * Kills the remote process if the binder is still listening, and says nothing if it
 * is not. Called on every path, including the ones where it has already exited.
 */
internal fun Process.destroyQuietly() {
    try {
        destroy()
    } catch (ignored: Throwable) {
    }
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

/**
 * Caps on captured output. `dumpsys SurfaceFlinger --latency` is small, but `dumpsys
 * display` on a foldable is not, and an unbounded read on a 4 GB phone that is
 * currently running a game is a real OOM risk.
 */
private const val MAX_OUTPUT_BYTES = 2 * 1024 * 1024
private const val MAX_ERROR_BYTES = 32 * 1024
