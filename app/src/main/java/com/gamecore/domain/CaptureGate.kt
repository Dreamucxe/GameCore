package com.gamecore.domain

import android.content.Context
import androidx.core.content.ContextCompat
import com.gamecore.core.system.ScreenCaptureController
import com.gamecore.service.CapturePurpose
import com.gamecore.service.ScreenRecordingService
import com.gamecore.ui.capture.CaptureConsentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place a capture is asked for.
 *
 * [BackgroundServiceGate]'s counterpart for screen capture, and it exists for the same reason: the two
 * calls involved — `startForegroundService` and `startActivity` on a consent Activity — both throw in
 * states that are easy to reach, and neither belongs in a ViewModel or a composable.
 *
 * The routing is the interesting part. A capture needs a live `MediaProjection`, consent for one can only
 * be obtained by an Activity, and from Android 14 the projection can only be *used* while a foreground
 * service of type `mediaProjection` is running. So there are exactly two paths: reuse the projection by
 * going straight to [ScreenRecordingService], or send the user through [CaptureConsentActivity], which
 * hands its result to that same service. §24B asks for recording's permission flow to be its own; this is
 * where that split is decided.
 */
@Singleton
class CaptureGate @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capture: ScreenCaptureController,
) {

    /**
     * Asks for [purpose], and says how far it got.
     *
     * [CaptureRequest.Prompting] is not success and not failure: the system sheet is up and the answer
     * arrives through the service. A caller that reported it as "screenshot taken" would be claiming a
     * file that may never be written, so the three cases are kept apart.
     */
    fun request(purpose: CapturePurpose): CaptureRequest {
        if (!capture.isSupported()) return CaptureRequest.Unsupported
        return try {
            if (capture.hasProjection()) {
                ContextCompat.startForegroundService(
                    context,
                    ScreenRecordingService.intentFor(context, purpose),
                )
                CaptureRequest.Started
            } else {
                context.startActivity(CaptureConsentActivity.intentFor(context, purpose))
                CaptureRequest.Prompting
            }
        } catch (refused: IllegalStateException) {
            // A foreground-service or background-activity start the platform would not allow.
            CaptureRequest.Refused
        } catch (denied: SecurityException) {
            CaptureRequest.Refused
        } catch (missing: android.content.ActivityNotFoundException) {
            // A ROM that reports a MediaProjectionManager and ships no consent UI.
            CaptureRequest.Unsupported
        }
    }

    fun hasConsent(): Boolean = capture.hasProjection()
}

/** How far [CaptureGate.request] got. The file, if there is one, arrives separately. */
enum class CaptureRequest {
    /** The service has it. The outcome will appear in the capture list.  */
    Started,

    /** The system consent sheet is on screen; nothing has been captured yet. */
    Prompting,

    /** No `MediaProjection` on this build at all. */
    Unsupported,

    /** The platform refused the start. Not the user's refusal — that arrives as a dismissed sheet. */
    Refused,
}
