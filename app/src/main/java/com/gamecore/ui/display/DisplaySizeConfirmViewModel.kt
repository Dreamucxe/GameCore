package com.gamecore.ui.display

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.model.RowRestoreOutcome
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.domain.display.DisplayOverrideGuard
import com.gamecore.domain.display.DisplayOverrideRecovery
import com.gamecore.domain.display.DisplayOverrideStore
import com.gamecore.domain.display.PendingDisplayOverride
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The state behind the confirmation screen: the row, the countdown, and what happened.
 *
 * The countdown is *derived*, never counted. It is `appliedAtMillis` plus the window, re-read against
 * the clock on every tick, which is what makes it survive the two things that would otherwise break it:
 * a rotation, and the user walking away and coming back to a recreated activity. A timer started at
 * `onCreate` would restart on both and would then either ask twice or wait fifteen seconds from the
 * wrong moment.
 */
data class ConfirmUiState(
    /** Null once the question is answered, or when it was never asked. Nothing to draw. */
    val pending: PendingDisplayOverride? = null,
    val remainingSeconds: Long = 0L,
    val expired: Boolean = false,
    val shellAvailable: Boolean = true,
    val isBusy: Boolean = false,
    /** The sentence produced by the last action, so a failed restore is shown rather than swallowed. */
    val outcome: String? = null,
    /** Signals the Activity to close: the question is answered, or there was never one to ask. */
    val done: Boolean = false,
    /** The guard's own sentence describing what has been done. Never written here, only relayed. */
    val confirmSentence: String? = null,
    /** The guard's sentence for what Revert would put back, or that nothing was recorded. */
    val revertSentence: String? = null,
)

/**
 * The dead-man's switch's question, and both answers.
 *
 * Everything on this screen goes through the paths the rest of the app uses: the record is
 * [DisplayOverrideStore]'s, the undo is [DisplayOverrideRecovery.revertExplicitly] — the same call the
 * launch path and the notification's Revert button make — and the guard's own sentences are relayed
 * rather than rewritten here. No second restore path, no second shell, and no way to confirm a row this
 * screen did not read itself.
 */
@HiltViewModel
class DisplaySizeConfirmViewModel @Inject constructor(
    private val store: DisplayOverrideStore,
    private val guard: DisplayOverrideGuard,
    private val recovery: DisplayOverrideRecovery,
    private val shell: ElevatedShell,
) : ViewModel() {

    private val _state = MutableStateFlow(ConfirmUiState())
    val state: StateFlow<ConfirmUiState> = _state.asStateFlow()

    /** The counter, held so a recreation replaces it rather than joining it. */
    private var countdown: Job? = null

    /**
     * Reads the row and the clock. Called once, from [onCreate], and again after every action.
     *
     * A null row finishes the activity rather than drawing an empty countdown: the confirmation belongs
     * to a row that was written, and asking a question that has already been answered is exactly the
     * dishonesty this feature exists to prevent.
     */
    fun start() {
        viewModelScope.launch {
            val pending = store.pending()
            if (pending == null) {
                _state.update { it.copy(done = true) }
                return@launch
            }
            _state.update {
                it.copy(
                    pending = pending,
                    expired = guard.hasExpired(pending, System.currentTimeMillis()),
                    remainingSeconds = guard.remainingSeconds(pending, System.currentTimeMillis()),
                    shellAvailable = shell.isAvailable(),
                    confirmSentence = guard.confirmCopy(pending),
                    revertSentence = guard.revertCopy(pending),
                )
            }
            // Started from the row that was just read, not from the state the screen first composed —
            // which is null until this returns, so a counter started from there would never run.
            startCountdown()
        }
    }

    /** Recomputes the countdown. Cheap by design: one preference read and a comparison. */
    fun tick() {
        val current = _state.value.pending ?: return
        val now = System.currentTimeMillis()
        val remaining = guard.remainingSeconds(current, now)
        val expired = guard.hasExpired(current, now)
        _state.update {
            it.copy(remainingSeconds = remaining, expired = expired)
        }
        if (expired) revert()
    }

    /** The user said keep: the row goes, the display keeps its size, and nothing is changed. */
    fun keep() {
        store.clear()
        _state.update { it.copy(pending = null, done = true, outcome = null) }
    }

    /**
     * Puts the recorded size back.
     *
     * The countdown reaching zero calls this too, because silence and "Revert" are the same answer —
     * that is the entire premise of the switch. It is also called from a notification action, so the
     * row is cleared only after the restore has reported what it did.
     */
    fun revert() {
        val pending = _state.value.pending ?: store.pending()
        if (pending == null) {
            _state.update { it.copy(done = true) }
            return
        }
        _state.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            // Called outside `update` because it suspends: the restore runs a shell command and a
            // read-back, which state transformation must not wrap.
            val result = recovery.revertExplicitly()
            _state.update { current ->
                when (result) {
                    // Back at the recorded value, or nothing was owed any more — a session-end sweep may
                    // already have restored it. Either way the question is answered and this screen's work
                    // is done, so it closes.
                    is RowRestoreOutcome.Restored,
                    is RowRestoreOutcome.NotPending,
                    is RowRestoreOutcome.KeptByUser,
                    -> current.copy(isBusy = false, pending = null, done = true)

                    // The shell is not answering, so nothing was tried. The row is still waiting; the user
                    // is told that rather than being shown a "done" that did not happen.
                    null -> current.copy(isBusy = false, outcome = NO_SHELL)

                    // The row stays, so the next launch asks again. `detail` is the manager's own reason,
                    // so the failure is named rather than smoothed into a generic one.
                    is RowRestoreOutcome.Failed -> current.copy(
                        isBusy = false,
                        outcome = "${result.detail} The change has been left in place and will be " +
                            "asked again, so nothing has been lost.",
                    )
                }
            }
        }
    }

    /** Drops a stale screen: the user backed out, so the row keeps waiting for its answer. */
    fun abandon() {
        _state.update { it.copy(pending = null, done = true) }
    }

    /**
     * The ticker, owned by this ViewModel's scope so it stops when the screen does.
     *
     * Cancels any previous one first. [start] is called again if this activity is recreated — a device
     * that ignores the manifest's `configChanges` — and two tickers would make the countdown run at
     * double speed on exactly the screen whose number has to be trustworthy.
     */
    fun startCountdown() {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            while (true) {
                delay(TICK_MILLIS)
                if (_state.value.pending == null || _state.value.done) return@launch
                tick()
            }
        }
    }

    private companion object {
        /** Twice a second: enough for a whole-second readout, far less often than a frame. */
        const val TICK_MILLIS = 500L

        const val NO_SHELL =
            "GameCore cannot reach the elevated shell, so the previous size could not be put back. " +
                "The change has been left in place, and it will be asked again on the next launch."
    }
}
