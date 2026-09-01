package com.gamecore.ui.tools

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.common.Formatters
import com.gamecore.core.model.RecordingQuality
import com.gamecore.core.model.SavedCapture
import com.gamecore.core.system.DoNotDisturbState
import com.gamecore.core.system.MediaKey
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SliderRow
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.components.startIntentSafely

/**
 * §17's gaming tools: the device controls a player wants without leaving the game, on their own screen.
 *
 * These same controls are in the floating panel, which is where they are actually used mid-game. This screen
 * is where they can be understood — each card has room for the sentence explaining what the control does and
 * what Android will not let it do, which a 48 dp panel button does not.
 *
 * Nothing here is a simulation. Where a control is missing on this device the card says so and shows no
 * button, and where an access is missing it shows the button that goes and asks for it.
 */
@Composable
fun ToolsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ToolsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val padded = Modifier.padding(horizontal = ScreenPadding)

    // Brightness, volume, Do Not Disturb and the rotation lock are all changeable outside GameCore —
    // including on the Settings pages these buttons open. Resume is the only moment that is knowable.
    OnResume { viewModel.refresh() }

    val launch: (Intent?) -> Unit = { intent ->
        if (intent == null || !context.startIntentSafely(intent)) viewModel.onIntentFailed()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Tools",
                subtitle = "Capture, brightness, volume and the rest, without leaving the game",
                onBack = onBack,
            )
        }

        state.message?.let { message ->
            item {
                NoteBanner(
                    text = message,
                    tone = Tone.Accent,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                    action = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
                )
            }
        }

        item {
            CaptureCard(
                state = state,
                onScreenshot = viewModel::screenshot,
                onToggleRecording = viewModel::toggleRecording,
                onQuality = viewModel::setQuality,
                modifier = padded,
            )
        }

        item {
            DisplayCard(
                state = state,
                onBrightnessChange = viewModel::setBrightnessDraft,
                onBrightnessCommit = viewModel::commitBrightness,
                onAutoBrightness = viewModel::setAutoBrightness,
                onRotationToggle = viewModel::toggleRotationLock,
                onGrantWriteSettings = { launch(viewModel.writeSettingsIntent()) },
                modifier = padded,
            )
        }

        item {
            AudioCard(
                state = state,
                onVolumeChange = viewModel::setVolumeDraft,
                onVolumeCommit = viewModel::commitVolume,
                onMediaKey = viewModel::sendMediaKey,
                onDoNotDisturb = viewModel::setDoNotDisturb,
                onGrantPolicyAccess = { launch(viewModel.notificationPolicyIntent()) },
                modifier = padded,
            )
        }

        item { TorchCard(state = state, onToggle = viewModel::toggleTorch, modifier = padded) }

        if (state.hasCaptures) {
            item {
                SectionCard(
                    title = "Saved files",
                    modifier = padded,
                    subtitle = Formatters.count(state.captures.size, "file"),
                    icon = Icons.Filled.Folder,
                ) {
                    Text(
                        text = SAVED_FILES_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.captures, key = { it.absolutePath }) { capture ->
                CaptureRow(
                    capture = capture,
                    onShare = { launch(viewModel.shareIntent(capture)) },
                    onDelete = { viewModel.delete(capture) },
                    modifier = padded,
                )
            }
        }
    }
}

/**
 * Screenshot and recording, which are the same permission and the same service.
 *
 * Neither button captures anything itself. Both hand the request to the `mediaProjection` foreground
 * service, because since Android 14 a projection can only be *used* while such a service is running, and
 * the first request of a session goes through Android's consent sheet first. So the card describes what it
 * asked for rather than what it did, and the file turns up in the list below when it exists.
 */
@Composable
private fun CaptureCard(
    state: ToolsUiState,
    onScreenshot: () -> Unit,
    onToggleRecording: () -> Unit,
    onQuality: (RecordingQuality) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRecording = state.recording.isRecording
    SectionCard(
        title = "Screenshot and recording",
        modifier = modifier,
        subtitle = "Through Android's own capture service",
        icon = Icons.Filled.PhotoCamera,
        action = {
            when {
                !state.captureSupported -> StatusChip("Not available", Tone.Muted)
                isRecording -> StatusChip("Recording", Tone.Accent)
                else -> Unit
            }
        },
    ) {
        if (!state.captureSupported) {
            Text(
                text = CAPTURE_UNAVAILABLE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = CAPTURE_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActionRow {
                Button(onClick = onScreenshot, enabled = !isRecording) { Text("Screenshot") }
                Button(onClick = onToggleRecording) {
                    Text(if (isRecording) "Stop recording" else "Record")
                }
            }
            if (isRecording) {
                KeyValueRow(
                    label = "Started",
                    value = Formatters.clockTime(state.recording.startedAtMillis),
                    tone = Tone.Accent,
                )
            }
            RowDivider()
            Text(
                text = "QUALITY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChoiceRow(
                options = RecordingQuality.entries.toList(),
                selected = state.quality,
                onSelect = onQuality,
                label = { it.label },
                perRow = 3,
                enabled = !isRecording,
            )
            Text(
                text = state.quality.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.hasCaptureConsent) {
                Text(
                    text = CONSENT_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Brightness, the ambient-sensor switch and the rotation lock.
 *
 * All three are writes into the settings provider, which needs "modify system settings" — an access Android
 * grants on its own page, not through a dialog. Without it the controls are shown disabled with the button
 * that goes and asks, rather than hidden: a user who cannot find the brightness slider assumes it is missing.
 *
 * The slider writes once, on release. Bound directly to the system value it would write on every frame of a
 * drag, and a settings-provider write per frame is both slow and visible as stutter in the game underneath.
 */
@Composable
private fun DisplayCard(
    state: ToolsUiState,
    onBrightnessChange: (Int) -> Unit,
    onBrightnessCommit: () -> Unit,
    onAutoBrightness: (Boolean) -> Unit,
    onRotationToggle: () -> Unit,
    onGrantWriteSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val brightness = state.brightnessPercent
    val locked = state.isRotationLocked
    SectionCard(
        title = "Display",
        modifier = modifier,
        subtitle = "Brightness and the rotation lock",
        icon = Icons.Filled.Brightness6,
        action = {
            if (locked == true) {
                StatusChip("Rotation locked", Tone.Accent, Icons.Filled.ScreenRotation)
            }
        },
    ) {
        Text(
            text = "BRIGHTNESS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (brightness == null) {
            Text(
                text = state.brightnessNote ?: BRIGHTNESS_UNREADABLE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SliderRow(
                title = "Screen brightness",
                value = state.brightnessValue,
                range = MIN_BRIGHTNESS..MAX_PERCENT,
                onValueChange = onBrightnessChange,
                valueLabel = "${state.brightnessValue}%",
                description = if (state.isAutoBrightness) AUTO_BRIGHTNESS_NOTE else null,
                enabled = state.canWriteSettings,
                onValueChangeFinished = onBrightnessCommit,
            )
            SwitchRow(
                title = "Automatic brightness",
                checked = state.isAutoBrightness,
                onCheckedChange = onAutoBrightness,
                description = "Hands the level back to the ambient light sensor.",
                enabled = state.canWriteSettings,
            )
        }
        state.screenTimeoutMillis?.let { timeout ->
            KeyValueRow(label = "Screen timeout", value = Formatters.durationCoarse(timeout))
        }
        if (!state.canWriteSettings) {
            Text(
                text = WRITE_SETTINGS_NEEDED,
                style = MaterialTheme.typography.bodySmall,
                color = Tone.Warning.colour(),
            )
            ActionRow {
                Button(onClick = onGrantWriteSettings) { Text("Open the setting") }
            }
        }
        RowDivider()
        Text(
            text = "ROTATION",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (locked == null) {
            Text(
                text = state.rotationNote ?: ROTATION_UNREADABLE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SwitchRow(
                title = "Lock the rotation",
                checked = locked,
                onCheckedChange = { onRotationToggle() },
                description = ROTATION_NOTE,
            )
        }
    }
}

/**
 * Media volume, the transport keys and Do Not Disturb.
 *
 * The transport keys are key events sent to whatever holds the media session, not calls into a player:
 * there is no public API to drive another app's playback and GameCore holds no notification-listener
 * access. When nothing is playing the key has nowhere to go, so the buttons are disabled and the card says
 * why instead of leaving three taps that do nothing.
 */
@Composable
private fun AudioCard(
    state: ToolsUiState,
    onVolumeChange: (Int) -> Unit,
    onVolumeCommit: () -> Unit,
    onMediaKey: (MediaKey) -> Unit,
    onDoNotDisturb: (Boolean) -> Unit,
    onGrantPolicyAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val volume = state.volumePercent
    SectionCard(
        title = "Sound",
        modifier = modifier,
        subtitle = "Volume, playback and Do Not Disturb",
        icon = Icons.AutoMirrored.Filled.VolumeUp,
        action = {
            if (state.doNotDisturb.isSilencing) {
                StatusChip(state.doNotDisturb.label, Tone.Accent, Icons.Filled.DoNotDisturbOn)
            }
        },
    ) {
        Text(
            text = "VOLUME",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (volume == null) {
            Text(
                text = state.volumeNote ?: VOLUME_UNREADABLE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SliderRow(
                title = "Media volume",
                value = state.volumeValue,
                range = 0..MAX_PERCENT,
                onValueChange = onVolumeChange,
                valueLabel = "${state.volumeValue}%",
                description = "The game's own stream. Ringer and alarm volumes are left alone.",
                onValueChangeFinished = onVolumeCommit,
            )
        }
        RowDivider()
        Text(
            text = "PLAYBACK",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (state.isMusicActive) MUSIC_ACTIVE else MUSIC_IDLE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionRow {
            MediaKey.entries.forEach { key ->
                TextButton(onClick = { onMediaKey(key) }, enabled = state.isMusicActive) {
                    Text(key.label)
                }
            }
        }
        RowDivider()
        Text(
            text = "DO NOT DISTURB",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KeyValueRow(
            label = "Current mode",
            value = state.doNotDisturb.label,
            tone = if (state.doNotDisturb == DoNotDisturbState.UNKNOWN) Tone.Muted else Tone.Neutral,
        )
        if (state.hasNotificationPolicyAccess) {
            SwitchRow(
                title = "Silence interruptions",
                checked = state.doNotDisturb.isSilencing,
                onCheckedChange = onDoNotDisturb,
                description = DND_NOTE,
            )
        } else {
            Text(
                text = DND_ACCESS_NEEDED,
                style = MaterialTheme.typography.bodySmall,
                color = Tone.Warning.colour(),
            )
            ActionRow {
                Button(onClick = onGrantPolicyAccess) { Text("Open the setting") }
            }
        }
    }
}

/**
 * The flashlight.
 *
 * The one control on this screen that needs no permission at all — `CameraManager.setTorchMode` is open to
 * any app since Android 6 — and the one most likely to be missing outright, because a device with no
 * flash unit has no torch to turn on. That is a fact about the hardware, so the card states it and shows
 * no switch.
 */
@Composable
private fun TorchCard(
    state: ToolsUiState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Flashlight",
        modifier = modifier,
        subtitle = "No permission needed for this one",
        icon = Icons.Filled.FlashlightOn,
        action = { if (state.isTorchOn) StatusChip("On", Tone.Good) },
    ) {
        if (state.torchAvailable) {
            SwitchRow(
                title = "Torch",
                checked = state.isTorchOn,
                onCheckedChange = { onToggle() },
                description = TORCH_NOTE,
            )
        } else {
            Text(
                text = TORCH_UNAVAILABLE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One saved file, with the two things that can be done to it.
 *
 * Share goes through the ViewModel, which asks the capture layer for a `content://` URI and puts it in the
 * intent. The composable never sees the URI and never holds the grant — §24A.2, and the reason
 * [SavedCapture] carries a path and four facts rather than a `Uri`.
 */
@Composable
private fun CaptureRow(
    capture: SavedCapture,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = listOfNotNull(
        capture.kind.label,
        capture.durationLabel,
        capture.sizeLabel,
        Formatters.dateTime(capture.createdAtMillis),
    ).joinToString(" · ")

    PlainCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = capture.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onShare) {
                Icon(imageVector = Icons.Filled.Share, contentDescription = "Share this file")
            }
            IconButton(onClick = onDelete) {
                Icon(imageVector = Icons.Filled.Delete, contentDescription = "Delete this file")
            }
        }
    }
}

private const val MIN_BRIGHTNESS = 1
private const val MAX_PERCENT = 100

private const val CAPTURE_NOTE =
    "Recording captures video only. Game audio needs a capture configuration any app is allowed to refuse, " +
        "and a silent file is more honest than one that records the microphone instead."

private const val CAPTURE_UNAVAILABLE =
    "This device has no screen-capture service, so neither screenshots nor recording are available on it. " +
        "Nothing GameCore can do works around that."

private const val CONSENT_NOTE =
    "Android will ask for permission the first time each session. It asks once, GameCore cannot skip it, and " +
        "the capture happens after you allow it."

private const val SAVED_FILES_NOTE =
    "Kept in GameCore's own storage, so uninstalling the app deletes them. Share sends a copy out to " +
        "whichever app you pick."

private const val AUTO_BRIGHTNESS_NOTE =
    "Automatic brightness is on, so it is switched off when you move this — a manual level with the sensor " +
        "still in charge is overwritten within a second."

private const val BRIGHTNESS_UNREADABLE =
    "This device would not report its brightness level, so there is nothing to show and no safe value to " +
        "write back."

private const val ROTATION_NOTE =
    "Locks the screen the way up it is now rather than to portrait, so a game held sideways stays sideways."

private const val ROTATION_UNREADABLE =
    "This build does not expose a rotation lock through the settings provider, so GameCore cannot read or " +
        "set one."

private const val WRITE_SETTINGS_NEEDED =
    "Changing brightness needs \"modify system settings\", which Android grants on its own page."

private const val VOLUME_UNREADABLE =
    "The audio policy would not report the media volume on this device."

private const val MUSIC_ACTIVE =
    "Something is playing. The keys go to whichever app holds the media session."

private const val MUSIC_IDLE =
    "Nothing is playing, so a transport key has nowhere to go. GameCore cannot start playback in another " +
        "app — no public API allows it."

private const val DND_NOTE =
    "Silences notifications and calls system-wide while you play. Alarms are left through."

private const val DND_ACCESS_NEEDED =
    "Do Not Disturb needs notification policy access. GameCore can read the mode without it, but not change it."

private const val TORCH_NOTE =
    "Stays on until you turn it off, including after this screen closes."

private const val TORCH_UNAVAILABLE =
    "This device reports no camera flash, so there is no torch to switch on."
