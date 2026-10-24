package com.gamecore.ui.display

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gamecore.core.common.ApplicationScope
import com.gamecore.core.model.RowRestoreOutcome
import com.gamecore.domain.display.DisplayOverrideNotices
import com.gamecore.domain.display.DisplayOverrideRecovery
import com.gamecore.domain.display.DisplayOverrideStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The two buttons on the dead-man's switch's notification.
 *
 * It exists because a broadcast is the only thing a notification action can run, and because the two
 * paths that ask the question never wait for an answer: the confirmation Activity can be swiped away
 * mid-countdown, and the app process can be killed after the notification has been posted and before
 * the user has decided. In both cases the row is still waiting and the buttons still have to work.
 *
 * Three rules, and the third is the one that keeps a stale notification honest:
 *
 *  1. **The token is checked first.** A notification answered twice — or one that arrives after the row
 *     was already cleared by the Recovery screen — must do nothing. Without this, a "Revert" tapped
 *     hours later would change the display the user has since settled on.
 *  2. **`goAsync()` for the whole operation.** [onReceive] returns before the work is done on
 *     purpose — the restore is a shell round trip with a read-back — and an unfinished async task is
 *     cancelled rather than allowed to reorder the display behind the user's back.
 *  3. **The row is cleared only after the write landed.** A revert that failed leaves the row in place
 *     so it can be tried again; a row cleared first is a row that can no longer retry, and the device
 *     stays stretched.
 */
@AndroidEntryPoint
class DisplayOverrideActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var store: DisplayOverrideStore

    /**
     * The one object that both buttons and the countdown and the launch path go through.
     *
     * Injected as [DisplayOverrideRecovery] because the answer is exactly the same operation whichever
     * surface it arrived on, and duplicating it here would be a second thing to keep in step with the
     * first.
     */
    @Inject
    lateinit var recovery: DisplayOverrideRecovery

    @Inject
    lateinit var notices: DisplayOverrideNotices

    /**
     * The application scope, not a scope of this receiver's.
     *
     * The restore must outlive [onReceive]: the user tapped a button and walked away, and the answer
     * they were given is that the display goes back, not that the display goes back if the process
     * survives long enough to finish it.
     */
    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    /**
     * Answers the question, if it is still the same question.
     *
     * An unknown action, or a token that names nothing, finishes silently: nothing on this screen can
     * tell the user their tap did something when it did not, and a receiver that acted on a stale
     * intent is worse than one that ignores it.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (!store.matches(token)) return
        val pendingResult = goAsync()
        val keep = intent.action == ACTION_KEEP
        scope.launch {
            try {
                if (keep) {
                    // The user said keep. The row goes and nothing else happens: the display stays as
                    // it is and nothing is put back.
                    store.clear()
                } else if (intent.action == ACTION_REVERT) {
                    revert()
                }
            } finally {
                // Whatever happened — including a cancellation — the broadcast is finished, so the
                // process is not held open on behalf of a question nobody is waiting for any more.
                pendingResult.finish()
            }
        }
    }

    /**
     * Puts the recorded size back through the ordinary restore path, then reports.
     *
     * [DisplayOverrideRecovery.revertExplicitly] is the single place the row is cleared, so the two
     * buttons, the countdown and the launch path cannot end up with different ideas of what "reverted"
     * means. All that is left here is the reporting: this is the one surface where the user is not
     * looking at GameCore when the answer lands.
     */
    private suspend fun revert() {
        val pending = store.pending() ?: return
        when (recovery.revertExplicitly()) {
            // The row is gone and the display is as it was before the change.
            is RowRestoreOutcome.NotPending, is RowRestoreOutcome.Restored -> notices.reverted(pending)

            // Nothing of GameCore's is left, but nothing was written either — the display has been
            // changed by something else since. Said as that, not as a restore.
            is RowRestoreOutcome.KeptByUser -> notices.notReverted(pending, CHANGED_BY_SOMETHING_ELSE)

            null, is RowRestoreOutcome.Failed -> notices.notReverted(pending, STILL_SET)
        }
    }

    companion object {
        const val ACTION_KEEP = "com.gamecore.action.DISPLAY_OVERRIDE_KEEP"

        const val ACTION_REVERT = "com.gamecore.action.DISPLAY_OVERRIDE_REVERT"

        const val EXTRA_TOKEN = "com.gamecore.extra.DISPLAY_OVERRIDE_TOKEN"

        private const val CHANGED_BY_SOMETHING_ELSE =
            "The display size has been changed by something else since GameCore set it, so it was left as it now stands."
        private const val STILL_SET =
            "The previous size could not be put back. Check that GameCore still has its elevated access, " +
                "then try again from the Recovery screen."
    }
}
