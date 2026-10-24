package com.gamecore.ui.display

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import com.gamecore.MainActivity
import com.gamecore.R
import com.gamecore.core.common.NotificationChannels
import com.gamecore.ui.Destination
import com.gamecore.domain.display.DisplayOverrideGuard
import com.gamecore.domain.display.DisplayOverrideNotices
import com.gamecore.domain.display.PendingDisplayOverride
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every notification the dead-man's switch posts, in one place.
 *
 * These are not decoration. The confirmation's fallback *is* the question when the full-screen Activity
 * cannot be started — a display override applied from the overlay panel's aspect tile, with GameCore
 * nowhere on screen, is exactly the case where there is no activity to put in front of the user — and
 * the launch notice is the only thing that tells somebody their display was stretched by a session that
 * was killed and has now been put back. So each of them carries the two answers as actions, and each of
 * them says what actually happened.
 *
 * Three details that are easy to get wrong and expensive to debug:
 *
 *  - `FLAG_IMMUTABLE` on every `PendingIntent`. Mandatory from API 31 and correct anyway: a mutable one
 *    handed to the system is an intent any app holding it could rewrite.
 *  - The token travels with each action and is checked against the row that is waiting *now*. A
 *    notification answered twice, or answered after the row was cleared, must be a no-op — otherwise a
 *    stale "Revert" would change the display hours after the user settled the question.
 *  - The [NotificationChannels.ALERTS] channel, and no other. It is the one channel this app allows to
 *    interrupt, and this is the one notification that has to: a user who cannot read their screen
 *    needs the notification to appear over whatever they are doing.
 */
@Singleton
class DisplayOverrideNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
    private val guard: DisplayOverrideGuard,
) : DisplayOverrideNotices {

    /**
     * The question, asked as a notification, with both answers on it.
     *
     * The fallback for the case the Activity could not be started. High priority and auto-alerting,
     * because a countdown is ticking: [PendingDisplayOverride.CONFIRM_WINDOW_MILLIS] from the write the
     * device puts the previous size back on its own whether this was noticed or not, which is the
     * behaviour the whole feature rests on.
     */
    override fun confirmation(row: PendingDisplayOverride) {
        val remaining = (row.appliedAtMillis + PendingDisplayOverride.CONFIRM_WINDOW_MILLIS -
            System.currentTimeMillis()).coerceAtLeast(0L)
        post(
            builder = base(row)
                .setContentTitle(context.getString(R.string.display_guard_ask_title))
                .setContentText(guard.confirmCopy(row))
                // The one thing the guard's sentence cannot say is how long is left, because it does not
                // know when it was written. The number belongs to the notification, and only here.
                .setSubText(context.getString(R.string.display_guard_ask_countdown, remaining / 1000L))
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "${guard.confirmCopy(row)} " +
                            context.getString(R.string.display_guard_ask_countdown, remaining / 1000L),
                    ),
                )
                .addAction(
                    0,
                    context.getString(R.string.display_guard_action_keep),
                    action(DisplayOverrideActionReceiver.ACTION_KEEP, row.token),
                )
                .addAction(
                    0,
                    context.getString(R.string.display_guard_action_revert),
                    action(DisplayOverrideActionReceiver.ACTION_REVERT, row.token),
                ),
        )
    }

    /**
     * The launch recovery put the recorded size back. Says which size came back.
     *
     * No actions on this one: the question is answered and the row is gone, so a button that could
     * change the display now would be a tap with nothing behind it.
     */
    override fun reverted(row: PendingDisplayOverride) {
        post(
            builder = base(row)
                .setContentTitle(context.getString(R.string.display_guard_reverted_title))
                .setContentText(guard.revertedCopy(row))
                .setStyle(NotificationCompat.BigTextStyle().bigText(guard.revertedCopy(row))),
        )
    }

    /** It was not put back, and [reason] is why. The row is still waiting, so this is actionable. */
    override fun notReverted(row: PendingDisplayOverride, reason: String) {
        val copy = guard.stillSetCopy(row, reason)
        post(
            builder = base(row)
                .setContentTitle(context.getString(R.string.display_guard_not_reverted_title))
                .setContentText(copy)
                .setStyle(NotificationCompat.BigTextStyle().bigText(copy))
                .addAction(
                    0,
                    context.getString(R.string.display_guard_action_revert),
                    action(DisplayOverrideActionReceiver.ACTION_REVERT, row.token),
                ),
        )
    }

    /**
     * The row is too old to act on unasked, so the user is offered the choice instead.
     *
     * Both answers are present and both are wired to the row's own token, so a row left untouched for a
     * week is answered exactly as if it were a minute old.
     */
    override fun offered(row: PendingDisplayOverride) {
        val copy = guard.offeredCopy(row)
        post(
            builder = base(row)
                .setContentTitle(context.getString(R.string.display_guard_offered_title))
                .setContentText(copy)
                .setStyle(NotificationCompat.BigTextStyle().bigText(copy))
                .addAction(
                    0,
                    context.getString(R.string.display_guard_action_keep),
                    action(DisplayOverrideActionReceiver.ACTION_KEEP, row.token),
                )
                .addAction(
                    0,
                    context.getString(R.string.display_guard_action_revert),
                    action(DisplayOverrideActionReceiver.ACTION_REVERT, row.token),
                ),
        )
    }

    /** A row exists that could not be read, so nothing was done and nothing was claimed. */
    override fun unreadableRow() {
        post(
            base(null)
                .setContentTitle(context.getString(R.string.display_guard_unreadable_title))
                .setContentText(context.getString(R.string.display_guard_unreadable_text)),
        )
    }

    // --------------------------------------------------------------------- internals

    /** The shared shape of all five: one channel, one id, one icon, one way back into the app. */
    private fun base(row: PendingDisplayOverride?): NotificationCompat.Builder =
        NotificationCompat.Builder(context, NotificationChannels.ALERTS)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setLocalOnly(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Opening the recovery screen is the one tap that covers every case: it re-reads the row,
            // shows the countdown if it is still running, and holds the manual reset if it is not.
            .setContentIntent(openRecovery(row))

    /**
     * The content tap: straight to the Recovery screen, not to Home.
     *
     * The token travels as the closed-set destination token, which [MainActivity] validates before
     * opening anything — so an unrecognised one lands on Home rather than on a screen that does not
     * exist. Dropping the user at Home and asking them to find the one screen that fixes this would be
     * the exact friction the feature exists to remove.
     */
    private fun openRecovery(row: PendingDisplayOverride?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .putExtra(MainActivity.EXTRA_DESTINATION, Destination.Recovery.EXTERNAL)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (row != null) intent.putExtra(DisplayOverrideActionReceiver.EXTRA_TOKEN, row.token)
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * One answer, aimed at the receiver with the row's token attached.
     *
     * An explicit component: the receiver is not exported, so nothing outside GameCore can deliver
     * this, and a null action is not expressible.
     */
    private fun action(what: String, token: String): PendingIntent {
        val intent = Intent(context, DisplayOverrideActionReceiver::class.java)
            .setAction(what)
            .putExtra(DisplayOverrideActionReceiver.EXTRA_TOKEN, token)
        val code = if (what == DisplayOverrideActionReceiver.ACTION_KEEP) REQUEST_KEEP else REQUEST_REVERT
        return PendingIntent.getBroadcast(
            context,
            code,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun post(builder: NotificationCompat.Builder) {
        val manager = NotificationManagerCompat.from(context)
        // A revoked POST_NOTIFICATIONS turns this into a silent no-op. The switch still does its job —
        // the countdown still reverts — and the screen still shows what happened; only the notice is
        // lost, which is the platform's decision and not something to paper over.
        try {
            manager.notify(NotificationChannels.ID_DISPLAY_GUARD, builder.build())
        } catch (denied: SecurityException) {
            // An OEM that throws rather than returning false. Same answer: the notice is lost and
            // nothing else changes.
        }
    }

    private companion object {
        /** Distinct request codes, so two answers are two intents rather than one reused PendingIntent. */
        const val REQUEST_OPEN = 910

        const val REQUEST_KEEP = 911

        const val REQUEST_REVERT = 912
    }
}
