package com.gamecore.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gamecore.domain.gaming.replay.ReplayChip
import com.gamecore.ui.theme.StatusColor
import com.gamecore.ui.theme.colour

/**
 * The Instant Replay status chip (§3.6, §10) — the one word for what the rolling buffer is doing, and,
 * when it is not simply buffering, the cause.
 *
 * Renders the committed [ReplayChip] the pure classifier `replayChip(...)` settled: the caller does the
 * settling and hands the result in, so this composable never re-derives state and cannot disagree with
 * the logic. The chip is why §10 forbids conveying these states with a greyed control alone — a save
 * button that is merely disabled says "no" without saying why, whereas "Needs permission" or "Unavailable:
 * not enough storage" names the remedy.
 *
 * The word is always [ReplayChip.label], so colour is never the only signal (the §2 rule): the tint comes
 * from the shared semantic [StatusColor] set, but a reader who cannot tell the tints apart still gets the
 * classifier's own sentence. Colours reuse the same set the thermal and other status chips use — an active
 * buffer reads "Ok" green, a self-clearing thermal pause reads warm, the two unavailable states read muted
 * (an unavailable feature is not a fault), a missing consent reads as the accent note it is, and Off is
 * muted and quiet.
 *
 * @param chip the state to show, from `replayChip(enabled, hasPermission, storage, blocked, thermalPaused)`.
 */
@Composable
fun InstantReplayChip(
    chip: ReplayChip,
    modifier: Modifier = Modifier,
) {
    val status = chip.statusColour()
    val icon = chip.icon()
    val colour = status.colour()
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "Instant Replay: ${chip.label}"
        },
        shape = MaterialTheme.shapes.small,
        color = colour.copy(alpha = 0.14f),
        contentColor = colour,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(text = chip.label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The semantic colour for each state — the same set the thermal and network chips draw from. */
private fun ReplayChip.statusColour(): StatusColor = when (this) {
    ReplayChip.Buffering -> StatusColor.Ok
    ReplayChip.PausedOverheating -> StatusColor.Hot
    ReplayChip.UnavailableBlocked -> StatusColor.Unavailable
    ReplayChip.UnavailableStorage -> StatusColor.Unavailable
    ReplayChip.NeedsPermission -> StatusColor.Info
    ReplayChip.Off -> StatusColor.Unavailable
}

/**
 * A glyph per state, paired with the word so the meaning survives a glance as well as a read. Off carries
 * none — a chip that says "Off" needs no picture of off, and a bare word is the quietest the chip gets.
 */
private fun ReplayChip.icon(): ImageVector? = when (this) {
    ReplayChip.Buffering -> Icons.Filled.FiberManualRecord
    ReplayChip.PausedOverheating -> Icons.Filled.Pause
    ReplayChip.UnavailableBlocked -> Icons.Filled.Block
    ReplayChip.UnavailableStorage -> Icons.Filled.Storage
    ReplayChip.NeedsPermission -> Icons.Filled.Lock
    ReplayChip.Off -> null
}
