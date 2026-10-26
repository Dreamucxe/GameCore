package com.gamecore.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gamecore.core.model.SessionEvent
import com.gamecore.core.model.SessionEventKind
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import androidx.compose.ui.platform.LocalContext

/**
 * The session's event timeline, drawn as a vertical lane with markers coloured by kind.
 *
 * This is not a graph of readings — samples are already graphed above. It is a record of what
 * happened and when, so a reader can see that a thermal warning arrived three minutes before the
 * frame spike, or that the refresh cap was still in force when the session ended. It does not
 * claim causality; [SessionAttributionCard] does that.
 *
 * An empty timeline is honest: old sessions predate the feature, and a session that recorded no
 * transitions has no events to show. The text says why rather than drawing a fake line.
 */
@Composable
fun SessionTimelineCard(
    state: SessionReportUiState,
    detail: SessionReportDetail,
    modifier: Modifier = Modifier,
) {
    if (detail.events.isEmpty()) {
        NoteBanner(
            text = "No events were recorded for this session — it was recorded before GameCore " +
                "kept a session timeline. Thermal warnings, refresh changes, profile applies, and " +
                "frame-time spikes would appear here in a newer session.",
            tone = Tone.Muted,
            modifier = modifier,
        )
        return
    }

    val events = remember(detail) {
        detail.events
            // Probe readings are evidence for the network card, not milestones: one every ten seconds
            // would bury the profile applies and thermal changes this timeline exists to show. The
            // spikes and recoveries stay, because those are the events a reader came for.
            .filter { it.kind != SessionEventKind.NETWORK_PROBE }
            .sortedBy { it.elapsedMillis }
    }
    if (events.isEmpty()) {
        NoteBanner(
            text = "The only events recorded for this session were network probe readings, which are " +
                "compared in the network card above rather than listed as milestones.",
            tone = Tone.Muted,
            modifier = modifier,
        )
        return
    }
    val context = LocalContext.current
    val sharing = remember { mutableStateOf(false) }

    PlainCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Session timeline",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(
                    onClick = {
                        sharing.value = true
                    },
                    enabled = !sharing.value,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = "Share a receipt for this session",
                    )
                }
            }
            Text(
                text = "What happened and when, in the order it happened. The session's start is zero.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            // A plain Column, not a LazyColumn: this card is already an item inside the report's
            // LazyColumn. A nested vertical LazyColumn is measured with an infinite maximum height and
            // throws as soon as the timeline is scrolled into view, which reads as the app closing.
            Column(modifier = Modifier.fillMaxWidth()) {
                events.forEachIndexed { index, event ->
                    TimelineRow(
                        event = event,
                        nextEventTime = events.getOrNull(index + 1)?.elapsedMillis,
                        isFirst = index == 0,
                        isLast = index == events.lastIndex,
                    )
                }
            }
        }
    }

    if (sharing.value) {
        androidx.compose.runtime.LaunchedEffect(key1 = true) {
            shareReceipt(context, state, detail).also { sharing.value = false }
        }
    }
}

@Composable
private fun TimelineRow(
    event: SessionEvent,
    nextEventTime: Long?,
    isFirst: Boolean,
    isLast: Boolean,
) {
    val lane = laneOf(event.kind)
    val time = elapsed(event.elapsedMillis)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Lane marker + connecting line
        Box(
            modifier = Modifier
                .size(16.dp)
                .padding(end = 16.dp),
        ) {
            // Line up
            if (!isFirst) {
                Box(
                    modifier = Modifier
                        .size(2.dp, 8.dp)
                        .align(Alignment.TopCenter)
                        .background(lane.colour),
                )
            }
            // The marker
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(lane.colour)
                    .align(Alignment.Center),
            )
            // Line down
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .size(2.dp, 8.dp)
                        .align(Alignment.BottomCenter)
                        .background(lane.colour),
                )
            }
        }

        // Event text
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = lane.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = lane.colour,
                )
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            event.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun elapsed(millis: Long): String {
    val total = millis.coerceAtLeast(0L) / 1000L
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun laneOf(kind: SessionEventKind): KindLane = when (kind) {
    SessionEventKind.PROFILE_APPLIED, SessionEventKind.PROFILE_APPLY_FAILED -> KindLane(
        label = "Profile",
        colour = Tone.Accent.colour(),
    )
    SessionEventKind.THERMAL_WARNING, SessionEventKind.THERMAL_RECOVERED -> KindLane(
        label = "Thermal",
        colour = Tone.Warning.colour(),
    )
    SessionEventKind.REFRESH_CAPPED, SessionEventKind.REFRESH_RESTORED -> KindLane(
        label = "Display",
        colour = Tone.Accent.colour(),
    )
    SessionEventKind.POWER_SAVER_ON, SessionEventKind.POWER_SAVER_OFF -> KindLane(
        label = "Power",
        colour = Tone.Danger.colour(),
    )
    SessionEventKind.FRAME_OUTLIER -> KindLane(
        label = "Frames",
        colour = Tone.Accent.colour(),
    )
    SessionEventKind.NETWORK_PROBE, SessionEventKind.NETWORK_SPIKE, SessionEventKind.NETWORK_CLEAR -> KindLane(
        label = "Network",
        colour = Tone.Good.colour(),
    )
}

private data class KindLane(val label: String, val colour: Color)