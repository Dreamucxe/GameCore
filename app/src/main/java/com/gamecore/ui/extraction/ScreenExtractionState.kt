package com.gamecore.ui.extraction

import android.graphics.Bitmap
import android.net.Uri
import com.gamecore.core.system.capture.CropRect
import com.gamecore.core.system.capture.CropSize

/**
 * Everything the extraction screen draws, and nothing it does.
 *
 * The screen is a pure function of this state: the honest branches — the feature switched off, a device with
 * no `MediaProjection`, consent not yet given, a frame in hand ready to crop — are all decided by these flags
 * rather than by the composable poking at the controller. That is what lets the screen show "Unavailable" on a
 * device that cannot capture instead of a fake preview, which is the §24 line this feature has to stay on.
 *
 * [frame] is a full-screen bitmap and is deliberately the only heavy thing here; the view-model nulls it the
 * moment the screen goes away (see `clearFrame`) so a screenful of pixels is not retained behind a back press.
 */
data class ScreenExtractionState(
    /** Whether the feature is turned on in settings. Off → the screen explains it is disabled, no controls. */
    val enabled: Boolean = false,

    /** Whether this build/device has a usable `MediaProjection` at all. False → "Unavailable", never a stub. */
    val captureSupported: Boolean = false,

    /** Whether a projection is already held, so an extract reuses it rather than opening the consent sheet. */
    val hasConsent: Boolean = false,

    /** The one captured frame, or null before an extract (or after it is cleared on navigate-away). */
    val frame: Bitmap? = null,

    /**
     * The pixel size of the preview surface [cropRectPreview] is measured against.
     *
     * Held because the crop is dragged in preview pixels but saved in bitmap pixels, and
     * [com.gamecore.core.system.capture.CropMath] needs the preview size to bridge the two. The screen reports
     * it whenever the preview is laid out.
     */
    val previewSize: CropSize? = null,

    /** The user's crop box in preview coordinates, or null to mean "the whole frame". */
    val cropRectPreview: CropRect? = null,

    /** The shareable `content://` URI of the last saved crop, or null until a save succeeds. */
    val savedUri: Uri? = null,

    /** A one-line note for the user (consent prompted, saved, could not save…), or null. */
    val message: String? = null,

    /** True while a capture or a save is in flight, so the screen can show progress rather than a dead tap. */
    val busy: Boolean = false,
)
