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
import com.gamecore.core.model.RecordingQuality
import com.gamecore.core.system.ScreenCaptureController
import com.gamecore.core.system.replay.save.ReplaySaveResult
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
     * One `mediaProjection` service fronts three independent things — a recording, the magnifier's frame
     * feed (§13) and the Instant Replay rolling buffer (§3.6) — and the notification has to tell the truth
     * about which. A recording always wins: its chronometer and its own mp4-finalising Stop action must
     * survive a feed or buffer starting or stopping under it. Absent a recording, the feed and the buffer
     * are peers — each gets its own wording and a Stop that ends only itself. A screenshot, which the
     * service is up for only an instant, falls through to a plain ongoing notice.
     */
    override fun buildNotification(): Notification {
        val recording = capture.recording.value
        if (recording.isRecording || pending == CapturePurpose.START_RECORDING) {
            return ServiceNotifications.recording(this, recording.startedAtMillis, stopPendingIntent())
        }
        if (capture.magnifierRunning.value || pending == CapturePurpose.START_FRAME_FEED) {
            return ServiceNotifications.magnifier(this, feedStopPendingIntent())
        }
        if (capture.replayRunning.value || pending == CapturePurpose.START_REPLAY_BUFFER) {
            return ServiceNotifications.replay(this, replayStopPendingIntent())
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
        val replay = intent.replayArgs()
        job?.cancel()
        job = lifecycleScope.launch { perform(purpose, consent, replay) }
    }

    private suspend fun perform(purpose: CapturePurpose, consent: Pair<Int, Intent>?, replay: ReplayArgs) {
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
            // A third virtual display, peer to the feed and independent of any recording. A device that
            // refuses the encoder leaves nothing captured, so the toast is again the only place the user
            // hears why. Stopping passes the caller's discard choice; saving hands the retained buffer to
            // the injected saver and reports what it confirmed on disk.
            CapturePurpose.START_REPLAY_BUFFER ->
                if (!capture.startReplayBuffer(replay.windowSeconds, replay.quality)) {
                    toast(getString(R.string.capture_replay_failed))
                }
            CapturePurpose.STOP_REPLAY_BUFFER -> capture.stopReplayBuffer(discard = replay.discard)
            CapturePurpose.SAVE_REPLAY_CLIP -> reportSave(capture.saveReplayClip(replay.displayName))
            // One frame into memory for Screen Extraction, then straight down: nothing here keeps the
            // projection alive, so a capture taken only for an extraction lets the service settle and stop
            // (unless a recording, feed or buffer is also up). The frame surfaces in the extractor UI through
            // ScreenCaptureController.lastExtractedFrame, so a success needs no toast — only a refused token
            // or a genuine failure does, which is the one thing the user would otherwise never hear.
            CapturePurpose.EXTRACT_FRAME -> {
                val outcome = capture.captureFrameToMemory()
                if (outcome !is CaptureOutcome.Extracted) report(outcome)
            }
        }
        settle()
    }

    /**
     * Keeps the service up for exactly as long as it is hosting something, and no longer.
     *
     * The projection can carry a recording, the magnifier feed and the Instant Replay buffer at once, and
     * on Android 14 all three are only permitted while this service runs — so a capture that finishes
     * cannot blindly `stopSelf`: a screenshot taken while the loupe or the buffer is up, or a recording
     * stopped while either is up, would pull the service out from under something still meant to be live.
     * The service stops only once the last of the three is gone; while any remains, the notification is
     * re-posted so it names what is left.
     */
    private fun settle() {
        if (capture.recording.value.isRecording ||
            capture.magnifierRunning.value ||
            capture.replayRunning.value
        ) {
            refresh()
        } else {
            stopSelf()
        }
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
        // The buffer's virtual display is under the same Android 14 rule as the feed's — it cannot outlive
        // this service — so the service going down has to take it too. `discard = false` keeps whatever is
        // buffered rather than throwing it away, so a save the user has already asked for is not lost to the
        // teardown. Idempotent and lock-free, so it costs nothing when no buffer was up.
        capture.stopReplayBuffer(discard = false)
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
     * The replay parameters a start, stop or save intent carries.
     *
     * Every field has a safe default, so a malformed or extra-less intent — §24A.3 treats an intent extra
     * as untrusted even from a component that is not exported — yields a sane request rather than a crash:
     * the default window, BALANCED quality, discard-on-stop, and a blank name the saver turns into a
     * fallback. Only [windowSeconds] and [quality] matter to a start, [discard] to a stop, [displayName]
     * to a save; the fields a given purpose ignores cost nothing.
     */
    private data class ReplayArgs(
        val windowSeconds: Int,
        val quality: RecordingQuality,
        val discard: Boolean,
        val displayName: String,
    )

    /** Reads [ReplayArgs] off an intent, defaulting every field the intent does not carry. */
    private fun Intent.replayArgs(): ReplayArgs = ReplayArgs(
        windowSeconds = getIntExtra(EXTRA_REPLAY_WINDOW, DEFAULT_REPLAY_WINDOW_SECONDS),
        quality = getStringExtra(EXTRA_REPLAY_QUALITY)
            ?.let { name -> RecordingQuality.entries.firstOrNull { it.name == name } }
            ?: RecordingQuality.BALANCED,
        discard = getBooleanExtra(EXTRA_REPLAY_DISCARD, true),
        displayName = getStringExtra(EXTRA_REPLAY_NAME).orEmpty(),
    )

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

    /**
     * Says whether a replay save reached the disk.
     *
     * A save is the one replay action with a file at the end of it, and — unlike a started recording — it
     * posts no ongoing notification and lights no system indicator, so a toast is the only place the user
     * learns it worked or did not. The [ReplaySaveResult.Failed.reason] is deliberately not surfaced: it
     * names an internal stage (no segments, stitch failed, publish failed) that means nothing to the user
     * and, in `detail`, could carry an exception class name that has no business on screen.
     */
    private fun reportSave(result: ReplaySaveResult) {
        when (result) {
            is ReplaySaveResult.Saved -> toast(getString(R.string.capture_replay_saved))
            is ReplaySaveResult.Failed -> toast(getString(R.string.capture_replay_save_failed))
        }
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

    /**
     * The Instant Replay notification's Stop button: ends the buffer, discarding what it holds.
     *
     * A third distinct request code, for the same reason [feedStopPendingIntent] needs its own. All three
     * stop intents share an action and a component and differ only in the purpose extra, which
     * `Intent.filterEquals` — the `PendingIntent` cache's key — ignores; a shared request code would have
     * one silently reuse another's, so this button could end up finalising a recording or the feed.
     *
     * The intent carries `discard = true`: pressing Stop on a rolling buffer throws the buffered seconds
     * away. Saving is the deliberate, separate action ([CapturePurpose.SAVE_REPLAY_CLIP]); Stop is not it.
     */
    private fun replayStopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_STOP_REPLAY,
        replayStopIntentFor(this, discard = true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val ACTION_CAPTURE = "com.gamecore.action.CAPTURE"

        private const val EXTRA_PURPOSE = "com.gamecore.extra.PURPOSE"

        private const val EXTRA_RESULT_CODE = "com.gamecore.extra.RESULT_CODE"

        private const val EXTRA_CONSENT = "com.gamecore.extra.CONSENT"

        private const val REQUEST_STOP = 901

        private const val REQUEST_STOP_FEED = 902

        private const val REQUEST_STOP_REPLAY = 903

        private const val EXTRA_REPLAY_WINDOW = "com.gamecore.extra.REPLAY_WINDOW"

        private const val EXTRA_REPLAY_QUALITY = "com.gamecore.extra.REPLAY_QUALITY"

        private const val EXTRA_REPLAY_DISCARD = "com.gamecore.extra.REPLAY_DISCARD"

        private const val EXTRA_REPLAY_NAME = "com.gamecore.extra.REPLAY_NAME"

        /**
         * The rolling window a buffer keeps when a start intent names none.
         *
         * A start built by [replayStartIntentFor] always carries its own; this covers the first-consent
         * start, which is relayed through [com.gamecore.ui.capture.CaptureConsentActivity] and arrives with
         * no replay extras. 30s matches `ProfileModels.instantReplayBufferSeconds`.
         */
        private const val DEFAULT_REPLAY_WINDOW_SECONDS = 30

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

        /**
         * Start the rolling buffer with a chosen window and quality.
         *
         * Both ride as extras so the service honours the user's Instant Replay settings; absent them (a
         * first-consent start relayed through the Activity) it falls back to [DEFAULT_REPLAY_WINDOW_SECONDS]
         * and BALANCED. Quality travels as its enum name and is matched back by name, so a value this build
         * does not know degrades to the default rather than crashing.
         */
        fun replayStartIntentFor(
            context: Context,
            windowSeconds: Int,
            quality: RecordingQuality,
        ): Intent = intentFor(context, CapturePurpose.START_REPLAY_BUFFER)
            .putExtra(EXTRA_REPLAY_WINDOW, windowSeconds)
            .putExtra(EXTRA_REPLAY_QUALITY, quality.name)

        /**
         * Stop the rolling buffer, saying whether to keep or throw away what it holds.
         *
         * `discard = true` drops the buffered seconds — the notification's Stop button; `discard = false`
         * keeps them so a save can still reach them.
         */
        fun replayStopIntentFor(context: Context, discard: Boolean): Intent =
            intentFor(context, CapturePurpose.STOP_REPLAY_BUFFER)
                .putExtra(EXTRA_REPLAY_DISCARD, discard)

        /**
         * Save the buffered seconds to a clip.
         *
         * [displayName] is a preferred base name only: the saver sanitizes it and falls back to a default
         * when it is blank, so an empty string is safe to pass.
         */
        fun replaySaveIntentFor(context: Context, displayName: String): Intent =
            intentFor(context, CapturePurpose.SAVE_REPLAY_CLIP)
                .putExtra(EXTRA_REPLAY_NAME, displayName)
    }
}
