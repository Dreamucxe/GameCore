package com.gamecore.aimlab.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.core.common.Formatters
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.NavRow
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatEntry
import com.gamecore.ui.components.StatStrip
import com.gamecore.ui.components.Tone

/**
 * The front door to Aim Lab: what the thirteen things you can do are, and how you have done so far.
 *
 * The order is deliberate and matches the rest of the app's screens. The summary of real progress is at the
 * top — the reason a returning user opens the section is to see it — and the thirteen entries follow, grouped
 * into Training, Setup and Progress so a grid of a dozen bare rows does not read as one undifferentiated
 * list. Every card is a [NavRow] that hands its route back through [onOpen]; this screen navigates nothing
 * itself, which keeps the whole section's routing in the graph the orchestrator owns.
 *
 * The summary is the honesty rule of §1/§30 made concrete. Before any session exists there is nothing true
 * to put in a stat strip, so the top of the screen is an inviting [EmptyState] banner rather than a row of
 * zeroes that would read as "your best reaction time is 0 ms". The moment a real session lands, the strip
 * replaces it, every figure in it computed from stored history by [AimLabSummary].
 *
 * @param onOpen navigate to one of [AimLabRoutes]' constants. The screen calls this and nothing else to
 *   move; the graph decides what each route resolves to.
 * @param onBack pop back to wherever Aim Lab was opened from (the main Home screen).
 */
@Composable
fun AimLabHomeScreen(
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AimLabHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Aim Lab",
                subtitle = "Practice aim, tune your setup, and track how you improve.",
                onBack = onBack,
            )
        }

        // Progress at the top. Real figures once there is history, an inviting banner before then — never a
        // strip of zeroes (§1/§30). The loading frame shows neither, so the banner does not flash before the
        // database has answered.
        if (!state.loading) {
            item {
                SummaryCard(summary = state.summary, hasHistory = state.hasHistory, modifier = padded)
            }
        }

        // The thirteen entries, grouped. Each group is one card of NavRows.
        AimLabGroup.entries.forEach { group ->
            item {
                GroupCard(group = group, onOpen = onOpen, modifier = padded)
            }
        }
    }
}

/**
 * The summary strip, or the "you haven't trained yet" banner.
 *
 * Which one is shown is decided entirely by [hasHistory], which is true only once a real session has been
 * recorded. When it is false there is nothing honest to put in figures, so the card holds an [EmptyState]
 * that points at the first thing worth doing rather than a grid of zeroes.
 */
@Composable
private fun SummaryCard(
    summary: AimLabSummary,
    hasHistory: Boolean,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Your progress",
        icon = Icons.Filled.Insights,
        modifier = modifier,
    ) {
        if (!hasHistory) {
            EmptyState(
                icon = Icons.Filled.SportsEsports,
                title = "No sessions yet",
                message = "Run any training mode and your stats, bests and personal records will build up " +
                    "here. Nothing is shown until there is something real to show.",
                actionLabel = null,
                onAction = null,
            )
            return@SectionCard
        }

        // First row: activity. Totals from real, stored sessions.
        StatStrip(
            entries = listOf(
                StatEntry("Sessions", summary.totalSessions.toString(), Tone.Neutral),
                StatEntry("Total time", Formatters.durationTotal(summary.totalTrainingMillis), Tone.Neutral),
                StatEntry("Last session", lastSessionLabel(summary.lastSession), Tone.Accent),
            ),
        )

        // Second row: personal bests. A best never set reads as the em-dash absence marker, not a zero.
        StatStrip(
            modifier = Modifier.padding(top = 12.dp),
            entries = listOf(
                StatEntry(
                    label = "Best reaction",
                    value = summary.bestReactionMillis?.let { "$it ms" } ?: ABSENT,
                    tone = if (summary.bestReactionMillis != null) Tone.Good else Tone.Muted,
                ),
                StatEntry(
                    label = "Best accuracy",
                    value = summary.bestAccuracyPercent?.let { "$it%" } ?: ABSENT,
                    tone = if (summary.bestAccuracyPercent != null) Tone.Good else Tone.Muted,
                ),
                StatEntry(
                    label = "Best flick",
                    value = summary.bestFlickScore?.let { Formatters.count(it, "pt") } ?: ABSENT,
                    tone = if (summary.bestFlickScore != null) Tone.Good else Tone.Muted,
                ),
                StatEntry(
                    label = "Best tracking",
                    value = summary.bestTrackingScore?.let { Formatters.count(it, "pt") } ?: ABSENT,
                    tone = if (summary.bestTrackingScore != null) Tone.Good else Tone.Muted,
                ),
            ),
        )
    }
}

/**
 * One group of entries — Training, Setup or Progress — as a card of tappable rows.
 *
 * The card's own icon is the group's, chosen to read at a glance; each row carries the destination's own
 * icon and its one-line "why you'd tap it". The row's trailing text is left off deliberately: there is no
 * per-entry figure worth repeating here, and the description already earns the tap.
 */
@Composable
private fun GroupCard(
    group: AimLabGroup,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = group.title,
        icon = groupIcon(group),
        modifier = modifier,
    ) {
        AimLabDestination.inGroup(group).forEach { entry ->
            NavRow(
                title = entry.label,
                onClick = { onOpen(entry.route) },
                description = entry.description,
                icon = entry.icon,
            )
        }
    }
}

/** The header icon for each group. */
private fun groupIcon(group: AimLabGroup): ImageVector = when (group) {
    AimLabGroup.TRAIN -> Icons.Filled.SportsEsports
    AimLabGroup.SETUP -> Icons.Filled.Tune
    AimLabGroup.PROGRESS -> Icons.Filled.EmojiEvents
}

/**
 * "Flick training · Today", or "—" if somehow there is no last session while history exists.
 *
 * Combines the mode's label with the relative day the session started, so "last session" answers both what
 * and when in the width of one stat cell. [Formatters.relativeDay] gives Today/Yesterday/date.
 */
private fun lastSessionLabel(session: SessionSummary?): String {
    if (session == null) return ABSENT
    return "${session.mode.label} · ${Formatters.relativeDay(session.startedAtMillis)}"
}
