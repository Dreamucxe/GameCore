package com.gamecore.core.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The in-game "Save last N" action for Instant Replay (§3.6) — the one control the user reaches for while
 * playing to keep the window that is already in the buffer.
 *
 * Pure UI, like [QuickSheet] and [PerformancePill]: it owns no state and knows nothing about the service,
 * the buffer, or MediaStore. The window it is saving is [bufferSeconds]; the save itself is [onSaveClip],
 * wired by the service. It draws with [OverlayPalette] and [BasicText] rather than `MaterialTheme`, for
 * the reason the rest of the overlay does — this is painted over a game GameCore has never seen, and light
 * text on its own dark plate is the only thing legible over both a white loading screen and a night map.
 *
 * Three states, all from parameters so they can be reasoned about without a running capture:
 *  - **armed** ([enabled] true, [saving] false): a live dot in [accent] and "Save last 30s"; tappable.
 *  - **saving** ([saving] true): "Saving…" with a muted dot; the tap is dropped so a second press cannot
 *    start a second save while the first is still muxing.
 *  - **unavailable** ([enabled] false): dimmed and inert — shown, so the user knows the action exists,
 *    but honestly not tappable (the chip beside it, driven by `replayChip(...)`, carries the reason so
 *    this is never a greyed control that explains nothing — §10).
 *
 * @param bufferSeconds the window the buffer is holding; shown in the label and one of
 *   `GameProfile.INSTANT_REPLAY_BUFFER_CHOICES`.
 * @param onSaveClip run on tap when armed; the service stitches and publishes the retained window.
 * @param enabled whether saving is possible right now (buffering and healthy); dims and disables when not.
 * @param saving whether a save is already in flight; shows the in-flight label and drops the tap.
 * @param accent the user's accent, used for the armed dot where it sits on GameCore's own plate.
 */
@Composable
fun InstantReplaySavePill(
    bufferSeconds: Int,
    onSaveClip: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    saving: Boolean = false,
    accent: Color = OverlayPalette.Good,
) {
    val clickable = enabled && !saving
    val label = if (saving) "Saving…" else "Save last ${bufferWindowLabel(bufferSeconds)}"
    val description = when {
        saving -> "Saving a clip"
        !enabled -> "Save clip, not available right now"
        else -> "Save the last ${bufferWindowLabel(bufferSeconds)} of play"
    }
    val dotColour = when {
        saving -> OverlayPalette.Muted
        enabled -> accent
        else -> OverlayPalette.Absent
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .alpha(if (enabled) 1f else 0.5f)
            .background(OverlayPalette.Plate)
            .clickable(enabled = clickable, role = Role.Button, onClick = onSaveClip)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(dotColour))
        Spacer(modifier = Modifier.width(8.dp))
        BasicText(
            text = label,
            maxLines = 1,
            style = TextStyle(
                color = if (enabled) OverlayPalette.Text else OverlayPalette.Muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

/** "15s", "30s", "1 min", "2 min" — a whole number of minutes reads as minutes, anything else seconds. */
private fun bufferWindowLabel(seconds: Int): String = when {
    seconds >= 60 && seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "${seconds}s"
}
