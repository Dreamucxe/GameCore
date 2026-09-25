package com.gamecore.service

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.gamecore.R
import com.gamecore.core.common.NotificationChannels
import com.gamecore.core.model.CaptureOutcome
import com.gamecore.core.system.ScreenCaptureController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * The only place in GameCore that touches a `MediaProjection`.
 *
 * It exists because of one Android 14 rule: `createVirtualDisplay` is permitted only while a foreground
 * service of type `mediaProjection` is running, and `getMediaProjection` itself throws before one is.
 * That has two consequences worth stating plainly, because both are easy to get wrong and neither is
 * obvious from the API:
 *
 *  - **A screenshot is taken here too.** A single frame needs a virtual display exactly as a recording
 *    does, so the service that declares the type has to take it — even though nothing is being recorded
 *    and the service stops again immediately afterwards.
 *  - **The consent result is handed to this service rather than used by the Activity that received it.**
 *    [com.gamecore.ui.capture.CaptureConsentActivity] can obtain consent and nothing else; the ordering
 *    the platform demands is service-first, then `getMediaProjection`.
 *
 * §24B asks for screen recording to have its own foreground service, its own type and its own permission
 * flow, separate from the overlay's and from the screenshot permission handling. This is that service:
 * `GamingOverlayService` never holds a projection, and this one never draws a window.
 */
@AndroidEntryPoint
class ScreenRecordingService : GameCoreService() {

    @Inject lateinit var capture: ScreenCaptureController

    override val notificationId: Int = NotificationChannels.ID_RECORDING

    override val serviceType: Int = TYPE_MEDIA_PROJECTION

    private var job: Job? = null

    /**
     * The purpose currently being carried out, so [buildNotification] can name the right thing before
     * the capture state has caught up.
     *
     * A feed or a recording only reads as "running" once its `start` call has returned, but the service
     * goes foreground *before* that call — the platform demands the notification first. Without this hint
     * the first notification a magnifier start posts would say "Recording screen", flash for the instant
     * the feed takes to come up, then correct itself; the hint lets it be right the first time.
     */
    private var pending: CapturePurpose? = null

    /**
     * The notification this service runs behind, chosen from what it is actually hosting.
     *
     * One `mediaProjection` service fronts two independent things — a recording and the magnifier's frame
     * feed (§13) — and the notification has to tell the truth about which. A recording always wins: its
     * chronometer and its own mp4-finalising Stop action must survive a feed starting or stopping under
     * it. A feed with no recording gets the magnifier wording and a Stop that ends only the feed. A
     * screenshot, which the service is up for only an instant, falls through to a plain ongoing notice.
     */
    override fun buildNotification(): Notification {
        val recording = capture.recording.value
        if (recording.isRecording || pending == CapturePurpose.START_RECORDING) {
            return ServiceNotifications.recording(this, recording.startedAtMillis, stopPendingIntent())
        }
        if (capture.magnifierRunning.value || pending == CapturePurpose.START_FRAME_FEED) {
            return ServiceNotifications.magnifier(this, feedStopPendingIntent())
        }
        return ServiceNotifications.recording(this, 0L, stopPendingIntent())
    }

    /**
     * Validates the start, goes foreground, then does the one thing it was started for.
     *
     * The purpose is checked against [CapturePurpose] before anything happens — §24A.3 — and an
     * unrecognised start is dropped. Dropping means stopping, *unless* a recording is running: a stale
     * `PendingIntent` from a previous run must not be able to end a capture in progress.
     */
    override fun onStartAction(intent: Intent?) {
        val purpose = CapturePurpose.from(intent?.getStringExtra(EXTRA_PURPOSE))
        if (intent == null || purpose == null) {
            if (!capture.recording.value.isRecording) stopSelf()
            return
        }
        // Foreground before the projection is claimed, never after: this ordering is the whole reason the
        // consent result travels here instead of being used where it was received.
        pending = purpose
        if (!goForeground()) return
        val consent = intent.consentResult()
        job?.cancel()
        job = lifecycleScope.launch { perform(purpose, consent) }
    }

    private suspend fun perform(purpose: CapturePurpose, consent: Pair<Int, Intent>?) {
        if (consent != null && !capture.acceptConsent(consent.first, consent.second)) {
            toast(getString(R.string.capture_consent_failed))
            stopSelf()
            return
        }
        when (purpose) {
            CapturePurpose.SCREENSHOT -> report(capture.takeScreenshot())
            CapturePurpose.START_RECORDING -> report(capture.startRecording())
            CapturePurpose.STOP_RECORDING -> report(capture.stopRecording())
            // The feed is a second virtual display on the same projection, so it can start whether or not
            // a recording is already running and never touches one that is. A device that refuses the
            // display leaves the loupe with no frames to draw; the toast is the only place the user would
            // hear why, since an empty loupe draws nothing rather than an error.
            CapturePurpose.START_FRAME_FEED ->
                if (!capture.startFrameFeed()) toast(getString(R.string.capture_magnifier_failed))
            CapturePurpose.STOP_FRAME_FEED -> capture.stopFrameFeed()
        }
        settle()
    }

    /**
     * Keeps the service up for exactly as long as it is hosting something, and no longer.
     *
     * The projection can carry a recording and the magnifier feed at once, and on Android 14 both are
     * only permitted while this service runs — so a capture that finishes cannot blindly `stopSelf`: a
     * screenshot taken while the loupe is up, or a recording stopped while it is up, would pull the
     * service out from under a feed that is still meant to be live. The service stops only once the last
     * of the two is gone; while either remains, the notification is re-posted so it names what is left.
     */
    private fun settle() {
        if (capture.recording.value.isRecording || capture.magnifierRunning.value) refresh() else stopSelf()
    }

    /**
     * Finalises a recording that is still running when the service goes down.
     *
     * `runBlocking` in `onDestroy`, deliberately. The alternative is a coroutine that is cancelled with
     * the scope a moment later, and an mp4 whose trailing atoms were never written is a file that exists,
     * has a plausible size, and no player will open — the worst possible outcome for a recording of
     * something the user cannot replay. `MediaRecorder.stop` is milliseconds of work.
     */
    override fun onDestroy() {
        job?.cancel()
        job = null
        // The feed's virtual display may not outlive this service: on Android 14 it is only permitted
        // while a `mediaProjection` foreground service runs, so the service going down has to take the
        // feed with it. Idempotent and lock-free, so it costs nothing when no feed was up.
        capture.stopFrameFeed()
        if (capture.recording.value.isRecording) {
            runBlocking { capture.stopRecording() }
        }
        super.onDestroy()
    }

    /**
     * The consent result carried in a start intent, or null when there is none to use.
     *
     * A missing or cancelled result is not an error here: a second screenshot reuses the projection from
     * the first and arrives with no consent extras at all.
     */
    @Suppress("DEPRECATION")
    private fun Intent.consentResult(): Pair<Int, Intent>? {
        val code = getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_CONSENT, Intent::class.java)
        } else {
            getParcelableExtra<Intent>(EXTRA_CONSENT)
        }
        if (code != Activity.RESULT_OK || data == null) return null
        return code to data
    }

    /**
     * Says what a capture did.
     *
     * Everything except a started recording, which announces itself with a notification, the system's own
     * recording indicator and a chronometer. A toast on top of those three would be noise; for a saved
     * file, a refusal or a failure, a toast is the only way the user finds out at all.
     */
    private fun report(outcome: CaptureOutcome) {
        if (outcome is CaptureOutcome.Recording) return
        toast(outcome.message)
    }

    private fun toast(message: String) {
        if (message.isBlank()) return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_STOP,
        intentFor(this, CapturePurpose.STOP_RECORDING),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The magnifier notification's Stop button: ends the feed and nothing else.
     *
     * A distinct request code from [stopPendingIntent] on purpose. `Intent.filterEquals` — which the
     * `PendingIntent` cache keys on — ignores extras, so these two intents (same action, same component,
     * differing only in the purpose extra) are equal to the system. The same request code would have one
     * silently reuse the other's, and the magnifier's Stop would end up finalising a recording.
     */
    private fun feedStopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_STOP_FEED,
        intentFor(this, CapturePurpose.STOP_FRAME_FEED),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val ACTION_CAPTURE = "com.gamecore.action.CAPTURE"

        private const val EXTRA_PURPOSE = "com.gamecore.extra.PURPOSE"

        private const val EXTRA_RESULT_CODE = "com.gamecore.extra.RESULT_CODE"

        private const val EXTRA_CONSENT = "com.gamecore.extra.CONSENT"

        private const val REQUEST_STOP = 901

        private const val REQUEST_STOP_FEED = 902

        /** A capture that can use a projection this process already holds. */
        fun intentFor(context: Context, purpose: CapturePurpose): Intent =
            Intent(context, ScreenRecordingService::class.java)
                .setAction(ACTION_CAPTURE)
                .putExtra(EXTRA_PURPOSE, purpose.name)

        /**
         * A capture that carries the consent the Activity has just been given.
         *
         * The result `Intent` is passed as an extra rather than consumed where it arrived, because
         * `getMediaProjection` may only be called once this service is in the foreground.
         */
        fun consentIntentFor(
            context: Context,
            purpose: CapturePurpose,
            resultCode: Int,
            data: Intent,
        ): Intent = intentFor(context, purpose)
            .putExtra(EXTRA_RESULT_CODE, resultCode)
            .putExtra(EXTRA_CONSENT, data)
    }
}
