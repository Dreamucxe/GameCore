package com.gamecore.ui.extraction

import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.system.ScreenCaptureController
import com.gamecore.core.system.capture.CropMath
import com.gamecore.core.system.capture.CropRect
import com.gamecore.core.system.capture.CropSize
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.domain.CaptureGate
import com.gamecore.domain.CaptureRequest
import com.gamecore.service.CapturePurpose
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * §Screen-Extraction: grab the screen once, crop it, save or share it.
 *
 * This adds almost no new machinery. Capture rides the exact spine the screenshot and recorder use —
 * [CaptureGate] routes an [CapturePurpose.EXTRACT_FRAME] request either straight to the running
 * `mediaProjection` service or through the consent sheet, and the captured frame comes back on the service's
 * side as [ScreenCaptureController.lastExtractedFrame], which this view-model simply collects. The only genuinely
 * new steps are holding one in-memory frame, mapping a crop box from preview pixels to bitmap pixels through
 * [CropMath], and handing the result to the controller to write.
 *
 * The honesty branches ([ScreenExtractionState]) are decided here, not in the screen: a build with no
 * projection reports `captureSupported = false` and the screen shows "Unavailable" rather than a dead
 * "Extract" button, and a capture that only prompted for consent is reported as prompting rather than as a
 * frame that was never taken.
 *
 * The capture is a *single* frame taken on demand, never a live feed drawn back into a preview — the loupe and
 * Scout show why that matters, since a feed that contains the surface drawing it spirals into a mirror. One
 * frame, shown statically, cannot do that.
 */
@HiltViewModel
class ScreenExtractionViewModel @Inject constructor(
    private val capture: ScreenCaptureController,
    private val captureGate: CaptureGate,
    private val preferences: SecurePreferenceStore,
) : ViewModel() {

    private val editing = MutableStateFlow(ScreenExtractionState())
    val state: StateFlow<ScreenExtractionState> = editing.asStateFlow()

    init {
        editing.value = editing.value.copy(
            enabled = preferences.settings.value.screenExtractionEnabled,
            captureSupported = capture.isSupported(),
            hasConsent = captureGate.hasConsent(),
        )
        // The feature switch can be toggled from Settings while this screen is open; follow it live.
        viewModelScope.launch {
            preferences.settings.collect { settings ->
                editing.value = editing.value.copy(enabled = settings.screenExtractionEnabled)
            }
        }
        // The one-shot frame arrives asynchronously: the request routes through the service, which captures
        // and publishes here. A non-null emission is proof consent was granted, and ends the busy state.
        viewModelScope.launch {
            capture.lastExtractedFrame.collect { bitmap ->
                editing.value = editing.value.copy(
                    frame = bitmap,
                    busy = if (bitmap != null) false else editing.value.busy,
                    hasConsent = if (bitmap != null) true else editing.value.hasConsent,
                )
            }
        }
    }

    /** Re-reads the two capability facts that can change outside this screen. Called from `OnResume`. */
    fun refresh() {
        editing.value = editing.value.copy(
            captureSupported = capture.isSupported(),
            hasConsent = captureGate.hasConsent(),
        )
    }

    /**
     * Asks for one frame of the current screen and reports how far the request got.
     *
     * Busy is set only when the projection is already held (`Started`), because that is the case where a frame
     * is imminent and a spinner is honest. When the consent sheet is shown (`Prompting`) the sheet itself is
     * the feedback, and a frame only follows if the user grants it — so nothing is left spinning if they don't.
     */
    fun extract() {
        if (!editing.value.enabled) return
        val request = captureGate.request(CapturePurpose.EXTRACT_FRAME)
        val note = when (request) {
            CaptureRequest.Started -> null
            CaptureRequest.Prompting -> CONSENT_PROMPTED
            CaptureRequest.Unsupported -> CAPTURE_UNSUPPORTED
            CaptureRequest.Refused -> CAPTURE_REFUSED
        }
        editing.value = editing.value.copy(
            busy = request == CaptureRequest.Started,
            message = note,
            savedUri = null,
            captureSupported = capture.isSupported(),
            hasConsent = captureGate.hasConsent(),
        )
    }

    /**
     * Records the crop box and the preview it was drawn against.
     *
     * The preview size travels with the rect because the box is in preview pixels but the save is in bitmap
     * pixels, and [CropMath] cannot bridge the two without it. A null [rectPreview] means "no box" — [save]
     * then keeps the whole frame.
     */
    fun setCrop(rectPreview: CropRect?, previewSize: CropSize) {
        editing.value = editing.value.copy(cropRectPreview = rectPreview, previewSize = previewSize)
    }

    /**
     * Writes the current frame — cropped to the box if one is set — and remembers its shareable URI.
     *
     * The preview→pixel mapping happens here through [CropMath]; the controller receives an already-resolved
     * bitmap-space rect and does the crop and the write. A degenerate box (empty after mapping) falls back to
     * the whole frame rather than saving nothing.
     */
    fun save() {
        val bitmap = editing.value.frame ?: return
        if (editing.value.busy) return
        editing.value = editing.value.copy(busy = true, message = null)
        viewModelScope.launch {
            val cropPx = resolveCropPixels(bitmap)
            val uri = capture.saveExtractedBitmap(bitmap, cropPx)
            editing.value = editing.value.copy(
                busy = false,
                savedUri = uri,
                message = if (uri != null) SAVED else SAVE_FAILED,
            )
        }
    }

    /**
     * The share-sheet intent for the saved crop, or null when nothing has been saved yet.
     *
     * The same shape [com.gamecore.ui.tools.ToolsViewModel.shareIntent] uses — `ACTION_SEND`, a one-shot read
     * grant on a `content://` URI — with `image/png`. Built here so the URI and its grant never reach the
     * composable. Saving is what produces the shareable URI, so the screen offers Share only once a save landed.
     */
    fun share(): Intent? {
        val uri = editing.value.savedUri ?: return null
        return Intent(Intent.ACTION_SEND)
            .setType(MIME_PNG)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * Drops the held frame and everything derived from it.
     *
     * Called when the screen goes away or the user is done: a full-screen bitmap is the one expensive thing in
     * the state, and there is no reason to keep it (and its crop and saved URI) alive behind a back press.
     */
    fun clearFrame() {
        // Release the controller's held copy too, not just this view-model's: it is a singleton, so a
        // full-screen bitmap left in its flow would outlive the screen — exactly the back-press leak the
        // screen's onDispose is there to prevent.
        capture.clearExtractedFrame()
        editing.value = editing.value.copy(
            frame = null,
            cropRectPreview = null,
            previewSize = null,
            savedUri = null,
            busy = false,
        )
    }

    fun dismissMessage() {
        editing.value = editing.value.copy(message = null)
    }

    private fun resolveCropPixels(bitmap: Bitmap): CropRect {
        val whole = CropRect(0, 0, bitmap.width, bitmap.height)
        val preview = editing.value.previewSize ?: return whole
        val crop = editing.value.cropRectPreview ?: return whole
        if (crop.isEmpty) return whole
        val mapped = CropMath.previewToBitmap(crop, preview, CropSize(bitmap.width, bitmap.height))
        return if (mapped.isEmpty) whole else mapped
    }

    private companion object {
        const val MIME_PNG = "image/png"

        const val CONSENT_PROMPTED =
            "GameCore asked to capture your screen. Grant it once and the extraction will follow."
        const val CAPTURE_UNSUPPORTED =
            "This device does not provide a screen capture, so a frame cannot be extracted."
        const val CAPTURE_REFUSED =
            "The system would not start a screen capture just now. Try again in a moment."
        const val SAVED =
            "Saved. You can share it from here, or find it in your gallery."
        const val SAVE_FAILED =
            "The crop could not be saved. Nothing was written."
    }
}
