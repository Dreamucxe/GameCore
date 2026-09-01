package com.gamecore.ui.tools

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.common.unavailabilityText
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.RecordingQuality
import com.gamecore.core.model.SavedCapture
import com.gamecore.core.permissions.GamePermission
import com.gamecore.core.permissions.PermissionChecker
import com.gamecore.core.system.AudioControls
import com.gamecore.core.system.ControlOutcome
import com.gamecore.core.system.DisplayControls
import com.gamecore.core.system.DisplayReader
import com.gamecore.core.system.DoNotDisturbState
import com.gamecore.core.system.MediaKey
import com.gamecore.core.system.ScreenCaptureController
import com.gamecore.core.system.ScreenRotation
import com.gamecore.core.system.TorchControls
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
 * §17's gaming tools.
 *
 * Eight controls that have almost nothing in common except that each one is a real system call which can
 * fail for a reason that is not a bug. So the shape here is the same for all of them: read the current
 * value, report [ControlOutcome] when the user changes it, and re-read afterwards rather than assuming the
 * write took. A brightness slider that moves to where the finger left it while the system value stayed put
 * is the small version of the lie §24 is about.
 *
 * Capture is the exception, and not by choice. A screenshot cannot be taken from here at all: the platform
 * requires a `mediaProjection` foreground service to be running, so [CaptureGate] routes the request to the
 * service or to the consent sheet and the file appears in [ScreenCaptureController.captures] afterwards.
 * That is why the capture list is re-read on resume instead of being awaited.
 */
@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val capture: ScreenCaptureController,
    private val captureGate: CaptureGate,
    private val display: DisplayControls,
    private val displayReader: DisplayReader,
    private val audio: AudioControls,
    private val torch: TorchControls,
    private val permissions: PermissionChecker,
    private val preferences: SecurePreferenceStore,
) : ViewModel() {

    private val editing = MutableStateFlow(ToolsUiState())
    val state: StateFlow<ToolsUiState> = editing.asStateFlow()

    init {
        editing.value = editing.value.copy(quality = preferences.settings.value.recordingQuality)
        viewModelScope.launch {
            capture.recording.collect { running ->
                editing.value = editing.value.copy(recording = running)
            }
        }
        refresh()
    }

    /**
     * Re-reads every control at once.
     *
     * One coroutine and one state update rather than eight, because eight separate updates would recompose
     * the screen eight times on every resume for readings that all arrive within a few milliseconds of each
     * other. Called from `OnResume`: brightness, volume, Do Not Disturb and the rotation lock are all things
     * the user can change outside GameCore, including on the Settings page a button here just sent them to.
     */
    fun refresh() {
        viewModelScope.launch {
            val brightness = display.brightness()
            val auto = display.isAutoBrightnessOn()
            val timeout = display.screenTimeoutMillis()
            val rotation = display.isRotationLocked()
            val volume = audio.mediaVolumePercent()
            val dnd = audio.doNotDisturbState()
            editing.value = editing.value.copy(
                isLoaded = true,
                captureSupported = capture.isSupported(),
                hasCaptureConsent = captureGate.hasConsent(),
                captures = capture.captures(),
                brightnessPercent = brightness.valueOrNull,
                brightnessNote = brightness.unavailabilityText(),
                isAutoBrightness = auto.valueOrNull ?: false,
                canWriteSettings = display.canWriteSystemSettings(),
                screenTimeoutMillis = timeout.valueOrNull,
                isRotationLocked = rotation.valueOrNull,
                rotationNote = rotation.unavailabilityText(),
                volumePercent = volume.valueOrNull,
                volumeNote = volume.unavailabilityText(),
                isMusicActive = audio.isMusicActive(),
                doNotDisturb = dnd.valueOrNull ?: DoNotDisturbState.UNKNOWN,
                hasNotificationPolicyAccess = permissions.hasNotificationPolicyAccess(),
                torchAvailable = torch.isAvailable(),
                isTorchOn = torch.isOn(),
            )
        }
    }

    // ------------------------------------------------------------------------------- capture

    fun screenshot() = requestCapture(CapturePurpose.SCREENSHOT)

    fun toggleRecording() = requestCapture(
        if (editing.value.recording.isRecording) {
            CapturePurpose.STOP_RECORDING
        } else {
            CapturePurpose.START_RECORDING
        },
    )

    /**
     * Asks for a capture and says what the platform did with the request.
     *
     * "Prompting" is reported rather than swallowed: the consent sheet appears over GameCore, and a user
     * who granted it once and does not see a second sheet needs to know why the first tap only asked.
     */
    private fun requestCapture(purpose: CapturePurpose) {
        val note = when (captureGate.request(purpose)) {
            CaptureRequest.Started -> null
            CaptureRequest.Prompting -> CONSENT_PROMPTED
            CaptureRequest.Unsupported -> CAPTURE_UNSUPPORTED
            CaptureRequest.Refused -> CAPTURE_REFUSED
        }
        editing.value = editing.value.copy(message = note, hasCaptureConsent = captureGate.hasConsent())
    }

    /** Written through to the store, because the recording service reads it there, not from here. */
    fun setQuality(quality: RecordingQuality) {
        preferences.updateSettings { it.copy(recordingQuality = quality) }
        editing.value = editing.value.copy(quality = quality)
    }

    /**
     * The share sheet's intent for one capture, or null when no grant could be made for it.
     *
     * Built here rather than in the composable so the `content://` URI and its read grant never reach the
     * presentation layer — the same reason [SavedCapture] carries a path and four facts instead of a `Uri`.
     */
    fun shareIntent(target: SavedCapture): Intent? {
        val uri = capture.shareUri(target) ?: return null
        return Intent(Intent.ACTION_SEND)
            .setType(target.kind.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun delete(target: SavedCapture) {
        viewModelScope.launch {
            val deleted = capture.delete(target)
            editing.value = editing.value.copy(
                captures = capture.captures(),
                message = if (deleted) null else "That file could not be deleted.",
            )
        }
    }

    // ------------------------------------------------------------------------------- display

    fun setBrightnessDraft(percent: Int) {
        editing.value = editing.value.copy(brightnessDraft = percent)
    }

    /**
     * Writes the brightness the finger was left on.
     *
     * Auto-brightness is switched off by the write — [DisplayControls] does that, because a manual level
     * with the ambient sensor still in charge is overwritten within a second and the user sees the slider
     * snap back for no visible reason. The re-read afterwards is what confirms it.
     */
    fun commitBrightness() {
        val draft = editing.value.brightnessDraft ?: return
        viewModelScope.launch {
            val outcome = display.setBrightnessPercent(draft)
            editing.value = editing.value.copy(brightnessDraft = null, message = noteFor(outcome))
            refresh()
        }
    }

    fun setAutoBrightness(enabled: Boolean) {
        viewModelScope.launch {
            val outcome = display.setAutoBrightness(enabled)
            editing.value = editing.value.copy(message = noteFor(outcome))
            refresh()
        }
    }

    /**
     * Locks the screen to whatever way up it is now, or unlocks it.
     *
     * The current rotation is read rather than assumed: locking to `NATURAL` on a phone held sideways would
     * turn the game portrait, which is the opposite of what the button is for.
     */
    fun toggleRotationLock() {
        viewModelScope.launch {
            val locked = editing.value.isRotationLocked == true
            val outcome = if (locked) {
                display.unlockRotation()
            } else {
                val degrees = displayReader.read().rotationDegrees
                display.lockRotation(ScreenRotation.fromSurface(degrees / DEGREES_PER_STEP))
            }
            editing.value = editing.value.copy(message = noteFor(outcome))
            refresh()
        }
    }

    // --------------------------------------------------------------------------------- audio

    fun setVolumeDraft(percent: Int) {
        editing.value = editing.value.copy(volumeDraft = percent)
    }

    fun commitVolume() {
        val draft = editing.value.volumeDraft ?: return
        viewModelScope.launch {
            val outcome = audio.setMediaVolumePercent(draft)
            editing.value = editing.value.copy(volumeDraft = null, message = noteFor(outcome))
            refresh()
        }
    }

    /**
     * Sends a transport key.
     *
     * A key event to whatever holds the media session, not a call into a player: there is no public API to
     * control another app's playback and GameCore holds no notification-listener access. If nothing is
     * playing the key goes nowhere, which is why the screen says so rather than showing dead buttons.
     */
    fun sendMediaKey(key: MediaKey) {
        viewModelScope.launch {
            val outcome = audio.sendMediaKey(key)
            editing.value = editing.value.copy(message = noteFor(outcome))
            editing.value = editing.value.copy(isMusicActive = audio.isMusicActive())
        }
    }

    fun setDoNotDisturb(enabled: Boolean) {
        viewModelScope.launch {
            val outcome = audio.setDoNotDisturb(enabled)
            editing.value = editing.value.copy(message = noteFor(outcome))
            refresh()
        }
    }

    /** The Settings page that grants Do Not Disturb access, for the screen to launch. */
    fun notificationPolicyIntent(): Intent? =
        permissions.settingsIntentFor(GamePermission.NOTIFICATION_POLICY)

    fun writeSettingsIntent(): Intent? =
        permissions.settingsIntentFor(GamePermission.WRITE_SETTINGS)

    // ---------------------------------------------------------------------------------- torch

    fun toggleTorch() {
        viewModelScope.launch {
            val outcome = torch.toggle()
            editing.value = editing.value.copy(
                message = noteFor(outcome),
                isTorchOn = torch.isOn(),
            )
        }
    }

    // ------------------------------------------------------------------------------- internals

    /**
     * A message for the user, or null when there is nothing to say.
     *
     * An applied control with no detail says nothing at all: a brightness slider that moved is its own
     * confirmation, and a banner reading "Applied" after every drag is noise. The three failure cases are
     * each shown, because in each of them the control did not do what the tap implied.
     */
    private fun noteFor(outcome: ControlOutcome): String? = when (outcome) {
        is ControlOutcome.Applied -> outcome.detail.takeIf { it.isNotEmpty() }
        is ControlOutcome.Unsupported -> outcome.detail
        is ControlOutcome.RequiresAccess -> outcome.detail
        is ControlOutcome.Failed -> outcome.detail
    }

    fun onIntentFailed() {
        editing.value = editing.value.copy(message = NO_SETTINGS_PAGE)
    }

    fun dismissMessage() {
        editing.value = editing.value.copy(message = null)
    }

    private companion object {
        const val DEGREES_PER_STEP = 90

        const val CONSENT_PROMPTED =
            "Android is asking for permission to capture the screen. It asks once per session and GameCore " +
                "cannot skip it — the capture happens after you allow it."

        const val CAPTURE_UNSUPPORTED =
            "This device has no screen-capture service, so screenshots and recording are not available on " +
                "it. Nothing GameCore can do works around that."

        const val CAPTURE_REFUSED =
            "Android would not let the capture start from here. Open GameCore first and try again — the " +
                "platform refuses a capture started from the background."

        const val NO_SETTINGS_PAGE =
            "This build of Android would not open that page. The access can still be granted from " +
                "GameCore's own details page in Settings."
    }
}
