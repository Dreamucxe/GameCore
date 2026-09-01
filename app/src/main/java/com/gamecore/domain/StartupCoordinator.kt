package com.gamecore.domain

import com.gamecore.core.common.ApplicationScope
import com.gamecore.core.model.DeviceCapabilities
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.CrosshairRepository
import com.gamecore.data.repository.RestorePointRepository
import com.gamecore.data.repository.SessionRepository
import com.gamecore.domain.optimization.DeviceCapabilityChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the first launch of the process found and fixed.
 *
 * Reported rather than logged, because [outstandingRestores] is something the user has to be told:
 * it means the device is still carrying a change GameCore made and never undid.
 */
data class StartupReport(
    /** Sessions closed from their last sample after a process death. */
    val repairedSessions: Int = 0,
    /** True the first time the built-in crosshair presets were written. */
    val seededCrosshairs: Boolean = false,
    /**
     * Device settings GameCore changed and has not put back.
     *
     * Non-zero after a process death mid-session. The Home card offers to restore them; see
     * [StartupCoordinator] for why this class does not do it unasked.
     */
    val outstandingRestores: Int = 0,
    val capabilities: DeviceCapabilities = DeviceCapabilities.UNKNOWN,
) {
    /** True when the launch found something to show the user rather than just something to know. */
    val hasFindings: Boolean get() = repairedSessions > 0 || outstandingRestores > 0

    companion object {
        val NOTHING = StartupReport()
    }
}

/**
 * The once-per-process work, done off the main thread, before the first screen can be trusted.
 *
 * Not called from `Application.onCreate` — see [com.gamecore.GameCoreApplication] for why that stays
 * almost empty. The first Activity calls this from a coroutine, so the cost lands after the window
 * exists rather than in front of the first frame.
 *
 * Three decisions are worth stating.
 *
 * **Outstanding restore points are reported, not restored.** A process death mid-session leaves the
 * panel pinned and the brightness raised, and undoing that at launch looks like the obviously right
 * thing to do — until the case where the user is still playing and has tabbed into GameCore to check
 * something. Dropping them out of 120 Hz mid-match without being asked is a worse failure than the one
 * being fixed. So the count is surfaced and the Home card offers the button, which is what
 * `RestoreReport.outstanding` was designed for.
 *
 * **One failure does not take the launch down.** Each piece is guarded on its own and contributes its
 * neutral value if it throws. A crosshair table that cannot be seeded is a preset the user can
 * recreate; it is not a reason to refuse to start.
 *
 * **It runs once, and is safe to call from anywhere.** A configuration change, a second Activity and a
 * service started before any UI all reach this, and the repair pass in particular must not run twice:
 * a second pass could close a session that started in between, under its own recorder. The mutex and
 * the cached report make every later call return the first one's findings.
 */
@Singleton
class StartupCoordinator @Inject constructor(
    private val preferences: SecurePreferenceStore,
    private val sessions: SessionRepository,
    private val crosshairs: CrosshairRepository,
    private val restorePoints: RestorePointRepository,
    private val capabilities: DeviceCapabilityChecker,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val mutex = Mutex()

    @Volatile
    private var report: StartupReport? = null

    /** True once [run] has finished, so a screen can tell "nothing found" from "not checked yet". */
    val hasRun: Boolean get() = report != null

    /**
     * Runs the startup pass, or returns the findings of the pass that already ran.
     *
     * The five pieces are independent, so they run concurrently and the slowest — the capability
     * check, a set of shell round trips — does not sit behind the encrypted database opening. They are
     * launched on [scope] rather than the caller's, so an Activity finishing mid-check abandons the
     * *result* rather than a half-finished repair pass.
     */
    suspend fun run(): StartupReport = mutex.withLock {
        report?.let { return@withLock it }

        val prefs = attempt(Unit) { preferences.preload() }
        val repaired = attempt(0) { sessions.repairUnfinished() }
        val seeded = attempt(false) { crosshairs.seedDefaultsIfEmpty() }
        val pending = attempt(0) { restorePoints.pendingCount() }
        val probed = attempt(DeviceCapabilities.UNKNOWN) { capabilities.refresh() }

        // Awaited even though its value is Unit: the point of preloading is that the keystore unwrap
        // has happened by the time the first screen reads a setting, and returning before it finished
        // would hand that cost straight back to the main thread.
        prefs.await()

        StartupReport(
            repairedSessions = repaired.await(),
            seededCrosshairs = seeded.await(),
            outstandingRestores = pending.await(),
            capabilities = probed.await(),
        ).also { report = it }
    }

    /**
     * Runs one piece of startup work on the application scope, falling back to [fallback].
     *
     * `runCatching` is not used, for the reason it usually should not be in a coroutine: it swallows
     * [CancellationException], which would report a cancelled check as a completed one that found
     * nothing. Cancellation is rethrown; everything else becomes the neutral value.
     */
    private fun <T> attempt(fallback: T, block: suspend () -> T): Deferred<T> = scope.async {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            fallback
        }
    }
}
