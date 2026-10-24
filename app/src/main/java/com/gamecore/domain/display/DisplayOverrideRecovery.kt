package com.gamecore.domain.display

import com.gamecore.core.model.RowRestoreOutcome
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.data.repository.RestorePointRepository
import com.gamecore.domain.optimization.OptimizationManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything that answers the dead-man's switch's question, and everything that asks it again.
 *
 * A `wm size` override outlives the process, so a session that was killed — by the user, by Android, by a
 * crash — leaves the display stretched with nobody having answered the question. Three different things
 * can answer it: the user tapping Revert, the user tapping Revert on the notification, and the next
 * launch finding the row still waiting. All three come through this class, through [revertExplicitly],
 * so they cannot disagree about what "put it back" means.
 *
 * The undo is [OptimizationManager.restore] on the single restore point the controller already recorded
 * — the same row `restoreAll` replays at every session end. There is no second restore path and no second
 * shell path: one mechanism, one thing to keep working, one count on the dashboard.
 */
@Singleton
class DisplayOverrideRecovery @Inject constructor(
    private val store: DisplayOverrideStore,
    private val guard: DisplayOverrideGuard,
    private val optimizer: OptimizationManager,
    private val notices: DisplayOverrideNotices,
    private val shell: ElevatedShell,
) {

    /**
     * The launch half: answers a pending question that was never answered.
     *
     * Called on the application scope by `GameCoreApplication`. It only *acts* while the row is young —
     * past [PendingDisplayOverride.MAX_PENDING_AGE_MILLIS] the user has been living with the display and
     * is offered the choice instead — and it never withholds an explanation: no elevation, nothing owed,
     * and a row it cannot read each produce their own sentence rather than silence.
     */
    suspend fun onProcessStart() {
        val pending = store.pending()
        if (pending == null) {
            // A row that cannot be read is still a row: nothing was restored and nothing was claimed, so
            // the user is told the record exists and where the manual way out lives.
            if (store.hasUnreadableRow()) notices.unreadableRow()
            return
        }
        val now = System.currentTimeMillis()
        if (!guard.shouldRevertOnLaunch(pending, now)) {
            notices.offered(pending)
            return
        }
        if (!shell.isAvailable()) {
            notices.notReverted(pending, NO_ELEVATION)
            return
        }
        when (revertExplicitly()) {
            null, is RowRestoreOutcome.Failed -> notices.notReverted(pending, STILL_SET)
            is RowRestoreOutcome.NotPending -> notices.notReverted(pending, NO_RESTORE_POINT)
            is RowRestoreOutcome.KeptByUser -> notices.notReverted(pending, CHANGED_BY_SOMETHING_ELSE)
            is RowRestoreOutcome.Restored -> notices.reverted(pending)
        }
    }

    /**
     * Puts the waiting override back, because somebody asked.
     *
     * The row's fate is settled here and only here, which is the point of having one method rather than
     * three: a tap, a notification action and the countdown reaching zero all clear it on the same terms
     * — after the restore has run and reported what it did. A row cleared before a restore that could not
     * be completed is a row that can no longer be retried, and a failed restore on a display nobody can
     * read is the one failure this feature must not make permanent.
     *
     * Returns [OptimizationManager.restore]'s outcome, or null when there was nothing to try because the
     * elevated shell is not answering. The caller turns that into its own words and its own surface; this
     * only decides what is true.
     */
    suspend fun revertExplicitly(): RowRestoreOutcome? {
        if (!shell.isAvailable()) return null
        return when (val outcome = optimizer.restore(
            RestorePointRepository.NON_SETTING_NAMESPACE,
            RestorePointRepository.KEY_DISPLAY_SIZE,
        )) {
            // Back at the recorded value, and the manager has cleared its row on the strength of the
            // read-back. The pending record goes with it: the question has been answered.
            is RowRestoreOutcome.Restored -> {
                store.clear()
                outcome
            }

            // The user changed the display themselves since GameCore wrote it, so the restore point was
            // dropped without a write. That is the same answer — nothing of GameCore's is left.
            is RowRestoreOutcome.KeptByUser -> {
                store.clear()
                outcome
            }

            // Nothing owed any more: a session-end sweep may already have restored it. Not a failure, and
            // not something to keep asking about.
            is RowRestoreOutcome.NotPending -> {
                store.clear()
                outcome
            }

            // Left waiting on purpose: the device is still changed and the row is the retry, so the next
            // launch asks again.
            is RowRestoreOutcome.Failed -> outcome
        }
    }

    private companion object {
        const val NO_ELEVATION =
            "GameCore cannot change the display without the elevated shell, so nothing was put back."
        const val NO_RESTORE_POINT =
            "GameCore has no record of what the display was before the change, so it left the display alone."
        const val CHANGED_BY_SOMETHING_ELSE =
            "The display size has been changed by something else since GameCore set it, so it was left as it now stands."
        const val STILL_SET =
            "Check that GameCore still has its elevated access, then try again from the Recovery screen."
    }
}
