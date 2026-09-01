package com.gamecore.ui.tools

import com.gamecore.core.model.RecordingQuality
import com.gamecore.core.model.RecordingState
import com.gamecore.core.model.SavedCapture
import com.gamecore.core.system.DoNotDisturbState

/**
 * §17's gaming tools, as this device actually answers for them.
 *
 * Every nullable field here is a reading that may not exist, and null means exactly that — not zero, not a
 * default. Brightness on a device whose settings provider refuses the read, a rotation lock on a build that
 * does not expose one, a media volume while the audio policy is busy: each is shown as unavailable with the
 * reason beside it rather than as a control that looks live and does nothing.
 *
 * The two draft fields are the slider convention from Settings: a brightness slider bound straight to the
 * system value writes to the settings provider on every frame of a drag, so the draft carries the value
 * under the thumb and the write happens once on release.
 */
data class ToolsUiState(
    val isLoaded: Boolean = false,
    val captureSupported: Boolean = true,
    val hasCaptureConsent: Boolean = false,
    val recording: RecordingState = RecordingState.IDLE,
    val quality: RecordingQuality = RecordingQuality.BALANCED,
    val captures: List<SavedCapture> = emptyList(),
    val brightnessPercent: Int? = null,
    val brightnessDraft: Int? = null,
    val brightnessNote: String? = null,
    val isAutoBrightness: Boolean = false,
    val canWriteSettings: Boolean = false,
    val screenTimeoutMillis: Long? = null,
    val isRotationLocked: Boolean? = null,
    val rotationNote: String? = null,
    val volumePercent: Int? = null,
    val volumeDraft: Int? = null,
    val volumeNote: String? = null,
    val isMusicActive: Boolean = false,
    val doNotDisturb: DoNotDisturbState = DoNotDisturbState.UNKNOWN,
    val hasNotificationPolicyAccess: Boolean = false,
    val torchAvailable: Boolean = false,
    val isTorchOn: Boolean = false,
    val message: String? = null,
) {
    val brightnessValue: Int get() = brightnessDraft ?: brightnessPercent ?: 0

    val volumeValue: Int get() = volumeDraft ?: volumePercent ?: 0

    val hasCaptures: Boolean get() = captures.isNotEmpty()
}
