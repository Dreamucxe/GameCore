package com.gamecore.ui.games

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gamecore.core.model.GameProfile
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone

/**
 * The profile editor's Instant Replay section (§3.6) — opt-in, buffer window, and the honest note that
 * this release records video only.
 *
 * Stateless, like every other control on this screen: the three profile fields come in and the two edits
 * go out, so the ViewModel's draft profile stays the only copy of the truth. It matches the peer sections
 * ([SmartFeaturesSection] and the rest) by drawing into a [SectionCard], so the caller drops it into the
 * editor's `LazyColumn` as one more `item { }` with no wrapper of its own.
 *
 * The include-audio control is present but **disabled**, with the reason stated beside it. The audit
 * deferred audio (it would need microphone-group permission and can silently fail on games that block
 * capture — §1 Q3), so the switch is shown off and inert rather than hidden, and rather than shipped as a
 * control that might not do what it says. [includeAudio] is still read and reflected so the stored value
 * is shown truthfully, but there is deliberately no `onIncludeAudioChange` — nothing here can turn it on.
 *
 * @param enabled the profile's [GameProfile.instantReplayEnabled].
 * @param bufferSeconds the profile's [GameProfile.instantReplayBufferSeconds]; one of
 *   [GameProfile.INSTANT_REPLAY_BUFFER_CHOICES], which is the picker's only source of truth.
 * @param includeAudio the profile's [GameProfile.instantReplayIncludeAudio]; shown, never toggled here.
 * @param onEnabledChange emitted when the user flips the opt-in switch.
 * @param onBufferChange emitted with the chosen window in seconds when the user picks one.
 */
@Composable
fun InstantReplaySettingsSection(
    enabled: Boolean,
    bufferSeconds: Int,
    includeAudio: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onBufferChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Instant Replay", icon = Icons.Filled.FiberManualRecord, modifier = modifier) {
        SwitchRow(
            title = "Keep an instant replay",
            checked = enabled,
            onCheckedChange = onEnabledChange,
            description = "Keeps the last few seconds of the screen in a rolling buffer while you play, " +
                "so you can save a clip the moment something happens. It stays on this device and " +
                "nothing is kept once you stop playing or turn this off.",
        )

        if (enabled) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "How much to keep", style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(6.dp))
            ChoiceRow(
                options = GameProfile.INSTANT_REPLAY_BUFFER_CHOICES,
                // The mapper snaps the stored value into this set, so an exact match is expected; a value
                // outside it simply leaves nothing selected rather than inventing an option.
                selected = bufferSeconds.takeIf { it in GameProfile.INSTANT_REPLAY_BUFFER_CHOICES },
                onSelect = onBufferChange,
                label = ::bufferWindowLabel,
                perRow = GameProfile.INSTANT_REPLAY_BUFFER_CHOICES.size,
            )

            RowDivider()

            // Video-only this release (§1 audit). Shown, off, and inert — a switch the user can see but
            // not flip, with the reason under it, rather than a missing feature or a lying toggle.
            SwitchRow(
                title = "Include sound",
                checked = includeAudio,
                onCheckedChange = {},
                description = "Video only in this version.",
                enabled = false,
            )
            NoteBanner(
                text = "Recording game sound would need microphone permission and can silently fail on " +
                    "games that block audio capture, so it is left off for now rather than offered as a " +
                    "switch that might not work. Your clips are video only.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
        }
    }
}

/**
 * The human label for a buffer window in seconds: "15s", "30s", "1 min", "2 min".
 *
 * Derived from the value rather than a hardcoded map, so it stays correct if
 * [GameProfile.INSTANT_REPLAY_BUFFER_CHOICES] ever gains a member — a whole number of minutes reads as
 * minutes, anything else as seconds.
 */
private fun bufferWindowLabel(seconds: Int): String = when {
    seconds >= 60 && seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "${seconds}s"
}
