package com.gamecore.ui.capture

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.gamecore.R
import com.gamecore.core.system.ScreenCaptureController
import com.gamecore.service.CapturePurpose
import com.gamecore.service.ScreenRecordingService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * An invisible Activity whose entire purpose is to ask for screen-capture consent.
 *
 * It exists because of a mismatch the platform leaves the app to solve: consent can only be requested
 * from an Activity, but the request usually comes from the floating control panel, with GameCore itself
 * nowhere on screen. So this is started, it shows nothing of its own — a transparent theme, so the user
 * sees the system sheet over the game they were playing — and it finishes on every path.
 *
 * What it deliberately does **not** do is use the result. On Android 14 `getMediaProjection` throws unless
 * a foreground service of type `mediaProjection` is already running, so the result is forwarded to
 * [ScreenRecordingService], which goes foreground first and then claims the projection. Splitting it this
 * way is also what §24B asks for: recording's permission flow is its own, separate from the overlay's.
 *
 * There is a second, quieter reason this activity is worth its own file. Starting the recording service
 * from here happens while GameCore has a visible activity, which is precisely when the platform permits
 * a foreground service to start. The same call from the overlay service alone would be a background
 * start, and on Android 12+ that throws.
 */
@AndroidEntryPoint
class CaptureConsentActivity : ComponentActivity() {

    @Inject lateinit var capture: ScreenCaptureController

    private var purpose: CapturePurpose? = null

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        deliver(it.resultCode, it.data)
    }

    /**
     * Validates the purpose, then asks — once.
     *
     * The purpose is an Intent extra, so it is untrusted input even though nothing else can reach this
     * component (§24A.3); an unrecognised value finishes silently rather than defaulting to a capture the
     * user did not ask for. A device with no `MediaProjectionManager` at all is a different answer and
     * says so, because that is the honest "not available on this device" case rather than a failure.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        purpose = CapturePurpose.from(intent?.getStringExtra(EXTRA_PURPOSE))
        if (purpose == null) {
            finish()
            return
        }
        // A recreation — a rotation while the sheet is up — must not ask a second time. The launcher
        // registered above survives it and the pending result is delivered to the new instance.
        if (savedInstanceState != null) return
        val request = capture.consentIntent()
        if (request == null) {
            toast(getString(R.string.capture_unsupported))
            finish()
            return
        }
        try {
            consent.launch(request)
        } catch (missing: ActivityNotFoundException) {
            // A ROM that reports a MediaProjectionManager but ships no consent UI. Rare, and not
            // something to crash over.
            toast(getString(R.string.capture_unsupported))
            finish()
        }
    }

    /**
     * Hands the answer to the service, whatever the answer was.
     *
     * A declined dialog gets no toast: the user has just been asked a question and said no, and telling
     * them what they did is noise. A granted one that the platform then refuses to start a service for
     * does get one, because in that case nothing visible happens and the user is owed an explanation.
     */
    private fun deliver(resultCode: Int, data: Intent?) {
        val requested = purpose
        if (requested == null || resultCode != Activity.RESULT_OK || data == null) {
            finish()
            return
        }
        try {
            ContextCompat.startForegroundService(
                this,
                ScreenRecordingService.consentIntentFor(this, requested, resultCode, data),
            )
        } catch (refused: IllegalStateException) {
            toast(getString(R.string.overlay_capture_failed))
        } catch (denied: SecurityException) {
            toast(getString(R.string.overlay_capture_failed))
        }
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val ACTION_CONSENT = "com.gamecore.action.CAPTURE_CONSENT"

        private const val EXTRA_PURPOSE = "com.gamecore.extra.PURPOSE"

        /**
         * The intent that asks for consent for [purpose].
         *
         * `NEW_TASK` because the caller is usually a service, `CLEAR_TASK` because its own task — the
         * manifest gives it an empty `taskAffinity` — must never hold a stale instance from a request the
         * user walked away from.
         */
        fun intentFor(context: Context, purpose: CapturePurpose): Intent =
            Intent(context, CaptureConsentActivity::class.java)
                .setAction(ACTION_CONSENT)
                .putExtra(EXTRA_PURPOSE, purpose.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
