package com.gamecore.ui.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.model.RowRestoreOutcome
import com.gamecore.core.plugin.PluginRegistry
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.domain.display.DisplayOverrideGuard
import com.gamecore.domain.display.DisplayOverrideRecovery
import com.gamecore.domain.display.DisplayOverrideStore
import com.gamecore.domain.display.DisplaySizeController
import com.gamecore.domain.display.PendingDisplayOverride
import com.gamecore.domain.overlay.OverlayController
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
 * The state behind the recovery screen: one pending override, a countdown, and what the device can do.
 *
 * [pending] is null when there is nothing to confirm, which is the state the screen draws as "nothing to
 * recover". [outcome] is the sentence produced by the last action, so a failed restore is reported rather
 * than swallowed — the restore points survive a failed attempt and the user is told they did.
 */
data class RecoveryUiState(
    val pending: PendingDisplayOverride? = null,
    val remainingSeconds: Long = 0L,
    val expired: Boolean = false,
    val shellAvailable: Boolean = false,
    val shellReason: String? = null,
    val isBusy: Boolean = false,
    val outcome: String? = null,
    /** Whether the plugin kill switch is on, read from the registry rather than inferred. */
    val pluginsDisabled: Boolean = false,
    /** Whether anything of GameCore's is on screen, read from the overlay controller. */
    val overlaysOff: Boolean = false,
    /** The guard's own sentence for what has been done. Never written here, only relayed. */
    val confirmSentence: String? = null,
    /** The guard's sentence for what Revert would put back, or that nothing was recorded. */
    val revertSentence: String? = null,
) {
    val isConfirming: Boolean get() = pending != null && !expired && !isBusy
}

/**
 * The recovery console: confirm or revert a display override, or fix a bad one from cold.
 *
 * The ViewModel holds only what the screen draws and the domain objects it needs. The guard's rules live
 * in [DisplayOverrideGuard] and are tested there; the store holds the row; [DisplayOverrideRecovery]
 * replays the restore point this app already recorded, one row at a time. Nothing here opens a second
 * shell path — the whole point of the dead-man's switch is that the undo is the same mechanism everything
 * else in this app uses, so there is exactly one thing to keep working.
 *
 * What this screen deliberately does not have: a "safe mode" flag threaded through the app. It is a
 * console you open when something is wrong, with three actions and an honest report of what each one did.
 * A global boot flag would be a fifth thing a game profile has to check, and this screen is the last
 * place that should add one.
 */
@HiltViewModel
class RecoveryViewModel @Inject constructor(
    private val guard: DisplayOverrideGuard,
    private val store: DisplayOverrideStore,
    private val recovery: DisplayOverrideRecovery,
    private val displaySize: DisplaySizeController,
    private val shell: ElevatedShell,
    private val overlays: OverlayController,
    private val plugins: PluginRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(RecoveryUiState())
    val state: StateFlow<RecoveryUiState> = _state.asStateFlow()

    /**
     * The counter, held so a re-read can replace it rather than join it.
     *
     * Without this, "Check again" would start a second loop beside the first and the countdown would
     * visibly run at double speed — a screen whose timer depends on how many times the user pressed a
     * button is not a screen anyone should trust about how long is left.
     */
    private var countdown: Job? = null

    init {
        refresh()
    }

    /**
     * Re-reads the pending row, the countdown, and what the device will let GameCore do.
     *
     * The overlay and plugin halves are read from their own sources of truth — the controller's published
     * request and the registry's kill switch — rather than from a local copy that could disagree with
     * what is on screen.
     *
     * The counter is started here, from the row that was actually read, rather than by the screen from
     * the state it first composed: on the first composition there is no row yet, so a counter started
     * from there would never run at all.
     */
    fun refresh() {
        viewModelScope.launch {
            val pending = store.pending()
            val now = System.currentTimeMillis()
            val ready = shell.isAvailable()
            _state.update {
                it.copy(
                    pending = pending,
                    shellAvailable = ready,
                    shellReason = if (ready) null else SHELL_REASON,
                    expired = pending?.let { row -> guard.hasExpired(row, now) } ?: false,
                    remainingSeconds = pending?.let { row -> guard.remainingSeconds(row, now) } ?: 0L,
                    confirmSentence = pending?.let(guard::confirmCopy),
                    revertSentence = pending?.let(guard::revertCopy),
                    pluginsDisabled = plugins.killSwitchOn.value,
                    overlaysOff = !overlays.status.value.anythingVisible,
                )
            }
            if (pending == null) countdown?.cancel() else runCountdown()
        }
    }

    /**
     * The user said keep: the row goes and the display keeps its override.
     *
     * Nothing else happens. There is no restore to run and no shell to talk to, because keeping is the
     * one answer that lets the device stay exactly as the last session left it.
     */
    fun confirm() {
        store.clear()
        _state.update {
            it.copy(
                pending = null,
                expired = false,
                remainingSeconds = 0L,
                confirmSentence = null,
                revertSentence = null,
                outcome = "Kept. The display stays as it was set, and GameCore stops asking.",
            )
        }
    }

    /** Drops the last action's sentence without touching the row, so a re-read cannot bring it back. */
    fun dismissOutcome() = _state.update { it.copy(outcome = null) }

    /**
     * Puts the recorded display size back through the ordinary restore path.
     *
     * The display row, and only the display row: this is the dead-man's switch asking one question, not a
     * session-end sweep, and a user who tapped "Revert" here did not ask for their frame-rate cap and
     * their charging suspension to be undone at the same time. [DisplayOverrideRecovery.revertExplicitly]
     * is the same single-row replay the mid-session card already uses, so the two behave identically.
     *
     * The pending row is cleared only after the restore has reported what it did. A row cleared before a
     * failed restore is a row that can no longer be retried, and a failed restore on a display nobody can
     * read is the one failure this feature must not make permanent.
     */
    fun revert() {
        val pending = store.pending()
        if (pending == null) {
            _state.update { it.copy(pending = null, outcome = "Nothing was waiting to be put back.") }
            return
        }
        // A previous size was written down but cannot be read back. Nothing is put back: a reset here
        // would clear an override the record asked to restore, and a guess would be a size nobody chose.
        // The user is told exactly that, and the record is cleared so it is not asked again.
        if (pending.hasRecordedPrevious && guard.previousSize(pending) == null) {
            store.clear()
            _state.update {
                it.copy(
                    pending = null,
                    outcome = "GameCore recorded a display size it cannot read back, so it left the " +
                        "display alone. Clearing the override by hand is `wm size reset`, which the " +
                        "button below does.",
                )
            }
            return
        }
        _state.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            // The same call the countdown, the notification's button and the launch path make, so a
            // revert from this screen and a revert that happens behind the user's back cannot disagree.
            // Called outside `update`: it is suspending, and a restore is exactly the work that must not
            // happen inside a state transformation.
            val result = recovery.revertExplicitly()
            _state.update { current ->
                when (result) {
                    is RowRestoreOutcome.Restored -> current.copy(
                        isBusy = false,
                        pending = null,
                        confirmSentence = null,
                        revertSentence = null,
                        outcome = "Put the display back to " +
                            "${pending.previousLabel ?: "its own size"}.",
                    )

                    // The display was changed by something else since, so the restore point was dropped
                    // without a write. Same answer — nothing of GameCore's is left — said as it is.
                    is RowRestoreOutcome.KeptByUser -> current.copy(
                        isBusy = false,
                        pending = null,
                        confirmSentence = null,
                        revertSentence = null,
                        outcome = "The display size has been changed by something else since GameCore " +
                            "set it, so it was left as it now stands.",
                    )

                    is RowRestoreOutcome.NotPending -> current.copy(
                        isBusy = false,
                        pending = null,
                        confirmSentence = null,
                        revertSentence = null,
                        outcome = "Nothing had been changed, so the display is already as it should be.",
                    )

                    null -> current.copy(
                        isBusy = false,
                        outcome = NO_SHELL,
                    )

                    is RowRestoreOutcome.Failed -> current.copy(
                        isBusy = false,
                        outcome = "${result.detail} The change has been left in place and will be " +
                            "asked again, so nothing has been lost.",
                    )
                }
            }
        }
    }

    /**
     * The countdown reaching zero is treated exactly like a silence: revert.
     *
     * It calls the same method the button does, so the two cannot drift apart, and it is guarded on
     * there being something to revert — a zero-countdown with no row is a screen that has already been
     * answered and has nothing to say.
     */
    fun onCountdownExpired() {
        if (_state.value.pending != null && !_state.value.isBusy) {
            _state.update { it.copy(expired = true) }
            revert()
        }
    }

    /**
     * Runs the counter for the pending row. Called by [refresh], never by the screen.
     *
     * That ordering is the point. The screen composes before the row has been read, so a counter it
     * started on entry would find nothing waiting and stop; starting it here means it starts from the row
     * that was actually found. It is also cancelled and replaced on every refresh, so "Check again" cannot
     * produce two loops.
     *
     * Half a second rather than a whole one, so the integer on screen ticks at the rate a video timer
     * does: a number that jumps in whole seconds reads as a stalled timer, and a stalled timer on a
     * screen that is about to change the display is worse than no timer at all.
     */
    private fun runCountdown() {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            while (true) {
                val pending = store.pending() ?: return@launch
                val now = System.currentTimeMillis()
                if (guard.hasExpired(pending, now)) {
                    onCountdownExpired()
                    return@launch
                }
                _state.update { it.copy(remainingSeconds = guard.remainingSeconds(pending, now)) }
                delay(TICK_MILLIS)
            }
        }
    }

    /**
     * Restores the display to the panel's own size, whether or not a pending row exists.
     *
     * The existing reset path — [DisplaySizeController.resetToNative] — and not a `wm size` of the
     * physical size. The two are different states: an override equal to the panel is still a row in the
     * window manager's settings that outlives GameCore, and only `wm size reset` removes it. The
     * controller reads the result back and clears its restore row on a confirmed reset, so a "done" here
     * means the display is back and nothing is left pending.
     */
    fun restoreDisplayToNative() {
        _state.update { it.copy(isBusy = true, outcome = null) }
        viewModelScope.launch {
            val outcome = displaySize.resetToNative()
            _state.update {
                it.copy(
                    isBusy = false,
                    pending = if (outcome.isSuccess) null else store.pending(),
                    outcome = when {
                        outcome.isSuccess ->
                            "The display is back to its own size, and nothing is left pending."
                        else -> "The display could not be put back: ${outcome.message} Check that " +
                            "GameCore still has its elevated access, then try again."
                    },
                )
            }
        }
    }

    /**
     * Turns every plugin off, with the registry's own kill switch.
     *
     * A real kill switch, not a UI filter: [PluginRegistry.setKillSwitch] is checked first by the
     * permission gate, so a plugin that is enabled and granted still gets refused every call with it on.
     * That is why this is the honest "disable plugins" action and a row of enable-switch toggles is not —
     * a user with a misbehaving plugin should not have to work out which one to blame.
     *
     * The switch is sticky across launches, so the copy says that it is, and says how to undo it.
     */
    fun disablePlugins() {
        viewModelScope.launch {
            plugins.setKillSwitch(true)
            _state.update {
                it.copy(
                    pluginsDisabled = true,
                    outcome = "Plugins are switched off. No plugin runs until you turn them back on " +
                        "from the plugins screen, whatever each plugin's own switch says.",
                )
            }
        }
    }

    /**
     * Hides every overlay GameCore is drawing, manual and profile-driven alike.
     *
     * [OverlayController.hideAll] is the same call the panel's own "stop overlay" makes, so a recovery
     * action cannot end up fighting the widget the user uses every day for control of `WindowManager`.
     * The preferences that remember which overlays are on are untouched: this stops the windows, it does
     * not clear what the user configured.
     */
    fun turnOffOverlays() {
        overlays.hideAll()
        _state.update {
            it.copy(
                overlaysOff = true,
                outcome = "Every GameCore overlay is hidden. What you have switched on is untouched, so " +
                    "the same overlays come back the next time you turn them on.",
            )
        }
    }

    private companion object {
        const val TICK_MILLIS = 500L

        const val NO_SHELL =
            "GameCore cannot reach the elevated shell, so the previous size could not be put back. The " +
                "record has been left in place, and it will be asked again on the next launch."

        const val SHELL_REASON =
            "Changing the display size needs the elevated shell. Android only allows a size override " +
                "from a shell with ADB-level authority, which on this device means Shizuku."
    }
}
