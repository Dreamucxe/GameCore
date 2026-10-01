package com.gamecore.domain.trigger

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.model.ShizukuState
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import com.gamecore.core.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Injects a synthetic tap, double tap or press-and-hold at a device-pixel coordinate, for the volume
 * button point trigger (feature 4).
 *
 * Every injection goes through [ElevatedShell] like every other privileged action in the app — there is
 * no second, hidden input path here — and the shell only ever runs one of the enumerated [ShellCommand]
 * cases: `input tap x y`, and `input swipe x y x y durationMs` for a hold, which is the same point held
 * for a duration. Because sending input to another app needs ADB-level authority, this is honest about
 * needing it: [tap], [doubleTap] and [hold] check the shell is actually live *before* they build a
 * command, and when it is not they return [TapResult.Unavailable] carrying the live Shizuku explanation
 * and never call `execute`. A granted permission that no longer has a working binder is not treated as
 * success — the check is [ElevatedShell.isAvailable], the same gate [DisplaySizeController] uses.
 *
 * Two things this deliberately does not do. It does no coordinate math: the pixels arriving here are
 * already the resolved device-pixel point another seam produced from a fraction, so this passes them
 * straight to the validating [ShellCommand] factory (which rejects a point it will not ask the device
 * for). And it keeps no cooldown between separate calls — silently dropping a tap the user asked for
 * would be the dishonest behaviour this app avoids; the only gap here is the short one *inside* a double
 * tap, which is what makes two `input tap`s read as a double tap rather than one long press.
 */
@Singleton
class PointTapController internal constructor(
    private val shell: ElevatedShell,
    private val io: CoroutineDispatcher,
    private val currentShizukuState: () -> ShizukuState,
) {

    /**
     * Production wiring. The [TapResult.Unavailable] reason is read live from [ShizukuManager.state] at
     * the moment of the gate rather than captured once, so a shell that dropped after this singleton was
     * built still reports the specific current state.
     */
    @Inject
    constructor(
        shell: ElevatedShell,
        shizuku: ShizukuManager,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(shell, io, { shizuku.state.value })

    /**
     * Whether an injection could run right now, cheaply and synchronously, so a trigger tile can be drawn
     * disabled with the reason on it rather than appearing to work and failing on tap. Derived from
     * [ShizukuManager.state]'s cached value; the actual [tap]/[hold] re-check [ElevatedShell.isAvailable]
     * for a stronger liveness guarantee before they act.
     */
    fun availability(): Observed<Unit> {
        val state = currentShizukuState()
        return if (state.isUsable) {
            Observed.of(Unit, DataSource.SHELL_SHIZUKU)
        } else {
            Observed.needsElevation(state.explanation)
        }
    }

    /** One tap at ([xPx], [yPx]). */
    suspend fun tap(xPx: Int, yPx: Int): TapResult = withContext(io) {
        gate()?.let { return@withContext it }
        injectOne(ShellCommand.inputTap(xPx, yPx))
    }

    /**
     * Two taps at ([xPx], [yPx]) with a short gap between them. Stops after the first if it did not go
     * through — a double tap whose first half failed is a failure, not a half-done one to paper over.
     */
    suspend fun doubleTap(xPx: Int, yPx: Int): TapResult = withContext(io) {
        gate()?.let { return@withContext it }
        val command = ShellCommand.inputTap(xPx, yPx)
            ?: return@withContext TapResult.Failed(POINT_REJECTED)
        val first = perform(command)
        if (first != TapResult.Success) return@withContext first
        delay(DOUBLE_TAP_GAP_MILLIS)
        perform(command)
    }

    /** Press and hold on ([xPx], [yPx]) for [ms], as an `input swipe` from the point back to itself. */
    suspend fun hold(xPx: Int, yPx: Int, ms: Int): TapResult = withContext(io) {
        gate()?.let { return@withContext it }
        injectOne(ShellCommand.inputSwipe(xPx, yPx, xPx, yPx, ms))
    }

    // --------------------------------------------------------------------- internals

    /**
     * Non-null when the shell is not usable, in which case the caller returns it and never reaches
     * `execute`. The reason is the live Shizuku explanation, so the user is told which of the six states
     * they are in rather than a generic "needs Shizuku".
     */
    private suspend fun gate(): TapResult? =
        if (shell.isAvailable()) null else TapResult.Unavailable(currentShizukuState().explanation)

    /** Runs [command], or reports the point rejected when the factory refused to build one. */
    private suspend fun injectOne(command: ShellCommand?): TapResult =
        if (command == null) TapResult.Failed(POINT_REJECTED) else perform(command)

    private suspend fun perform(command: ShellCommand): TapResult =
        try {
            val result = shell.execute(command)
            if (result.isSuccess) TapResult.Success else TapResult.Failed(result.failureReason())
        } catch (t: Throwable) {
            TapResult.Failed(t.message ?: t::class.java.simpleName)
        }

    private companion object {
        /** Long enough to read as two taps, short enough not to be felt as a pause. */
        const val DOUBLE_TAP_GAP_MILLIS = 40L

        const val POINT_REJECTED = "That point is not one GameCore will ask this device to tap."
    }
}

/**
 * The outcome of one injection. [Unavailable] and [Failed] are different on purpose: the first is "no
 * elevated shell, so nothing was even attempted" and the second is "the shell ran and the injection did
 * not go through", which the trigger surface needs to tell apart the same way [Observed] does.
 */
sealed interface TapResult {
    /** The shell accepted the injection. */
    data object Success : TapResult

    /** No usable elevated shell; nothing was injected. [reason] is the live [ShizukuState] explanation. */
    data class Unavailable(val reason: String) : TapResult

    /** The shell was reachable but the injection failed or the point was refused. */
    data class Failed(val reason: String) : TapResult
}
