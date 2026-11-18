package com.gamecore.aimlab.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.aimlab.engine.TrainingPlanRecommendation
import com.gamecore.core.common.Formatters
import com.gamecore.ui.components.ClickableCard
import com.gamecore.ui.components.CompactStat
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionLabel
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.theme.Density
import com.gamecore.ui.theme.Spacing
import com.gamecore.ui.theme.StatLabelStyle

/**
 * The front door to Aim Lab: what the section can do, and how you have done so far.
 *
 * The screen is arranged as three shelves rather than a stack of cards. Progress is a hero at the top —
 * the reason a returning user opens the section — with the session count at display size and the bests
 * laid out as a bento underneath it. Today's plan is a single recommendation banner whose button starts
 * the run it names. The thirteen destinations are a two-column grid of mode tiles, grouped into Training,
 * Setup and Progress, so a dozen equal rows no longer read as one undifferentiated menu.
 *
 * Every card still hands its route back through [onOpen]; this screen navigates nothing itself, which
 * keeps the whole section's routing in the graph the orchestrator owns.
 *
 * The hero is the honesty rule of §1/§30 made concrete. Before any session exists there is nothing true
 * to put in a stat strip, so the hero holds an inviting [EmptyState] rather than a row of zeroes that
 * would read as "your best reaction time is 0 ms". The moment a real session lands, the figures replace
 * it, every one computed from stored history by [AimLabSummary].
 *
 * @param onOpen navigate to one of [AimLabRoutes]' constants. The screen calls this and nothing else to
 *   move; the graph decides what each route resolves to.
 * @param onBack pop back to wherever Aim Lab was opened from, or null when there is nowhere to go back
 *   *to*. Null is the normal case since §9: Aim Lab is one of the bottom-bar tabs, and a tab is not a
 *   screen you arrived at from somewhere, so it carries no back arrow — the bar below it is how you leave.
 */
@Composable
fun AimLabHomeScreen(
    onOpen: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: AimLabHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val padded = Modifier.padding(horizontal = ScreenPadding)
    // The same gap the rest of the app uses, tightened by the same factor when the user has asked for
    // compact density (§8). Aim Lab reading a different spacing from Games and Sessions is exactly the
    // seam this step exists to remove.
    val cardGap = if (state.isCompact) Spacing.md * Density.COMPACT_FACTOR else Spacing.md

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = Spacing.xs, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(cardGap),
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
                ProgressHero(summary = state.summary, hasHistory = state.hasHistory, modifier = padded)
            }
            // Today's recommendation follows the summary because it is the action the figures point at.
            // It is hidden until the plan exists, so the cold-start card never appears on the loading frame.
            state.plan?.let { plan ->
                item {
                    PlanBanner(plan = plan, onOpen = onOpen, modifier = padded)
                }
            }
        }

        // The thirteen entries, as mode tiles. Each group is a shelf with its own heading; the tiles are
        // built in rows of two because this LazyColumn already scrolls vertically.
        AimLabGroup.entries.forEach { group ->
            item {
                SectionLabel(
                    text = group.title,
                    subtitle = groupSubtitle(group),
                    modifier = padded.padding(top = Spacing.xs),
                )
            }
            AimLabDestination.inGroup(group).chunked(2).forEach { row ->
                item {
                    Row(
                        modifier = padded
                            // A row of mode tiles fades and slides when the section list changes,
                            // matching the library and session lists.
                            .animateItem()
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        row.forEach { entry ->
                            ModeTile(
                                entry = entry,
                                onOpen = onOpen,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                        if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * The progress hero: the session count at display size, the total training time beside it, and the four
 * personal bests as a bento underneath.
 *
 * Which half is shown is decided entirely by [hasHistory], which is true only once a real session has been
 * recorded. When it is false there is nothing honest to put in figures, so the hero holds an [EmptyState]
 * that points at the first thing worth doing rather than a grid of zeroes.
 *
 * A best never set reads as a word, not an em-dash and never a zero — "Not set" is the truer statement,
 * and a zero would be an outright lie.
 */
@Composable
private fun ProgressHero(
    summary: AimLabSummary,
    hasHistory: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    PlainCard(modifier = modifier, contentPadding = 0.dp) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            scheme.primary.copy(alpha = 0.24f),
                            scheme.primary.copy(alpha = 0.06f),
                            Color.Transparent,
                        ),
                    ),
                )
                .padding(16.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "YOUR PROGRESS",
                        style = StatLabelStyle,
                        color = scheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (hasHistory) {
                        StatusChip(text = "All runs", tone = Tone.Accent)
                    }
                }
                if (!hasHistory) {
                    EmptyState(
                        icon = Icons.Filled.SportsEsports,
                        title = "No sessions yet",
                        message = "Run any training mode and your stats, bests and personal records will " +
                            "build up here. Nothing is shown until there is something real to show.",
                        actionLabel = null,
                        onAction = null,
                    )
                    return@Column
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = summary.totalSessions.toString(),
                            style = MaterialTheme.typography.displaySmall,
                            color = scheme.onSurface,
                        )
                        Text(
                            text = "sessions recorded",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = Formatters.durationTotal(summary.totalTrainingMillis),
                            style = MaterialTheme.typography.titleLarge,
                            color = scheme.onSurface,
                        )
                        Text(
                            text = "total training",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }

                // The personal bests, two to a row. A best never set is a muted word, not a zero.
                Row(
                    modifier = Modifier.padding(top = Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    CompactStat(
                        label = "Best reaction",
                        value = summary.bestReactionMillis?.let { "$it ms" } ?: NOT_SET,
                        tone = if (summary.bestReactionMillis != null) Tone.Good else Tone.Muted,
                        modifier = Modifier.weight(1f),
                    )
                    CompactStat(
                        label = "Best accuracy",
                        value = summary.bestAccuracyPercent?.let { "$it%" } ?: NOT_SET,
                        tone = if (summary.bestAccuracyPercent != null) Tone.Good else Tone.Muted,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.padding(top = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    CompactStat(
                        label = "Best flick",
                        value = summary.bestFlickScore?.let { Formatters.count(it, "pt") } ?: NOT_SET,
                        tone = if (summary.bestFlickScore != null) Tone.Good else Tone.Muted,
                        modifier = Modifier.weight(1f),
                    )
                    CompactStat(
                        label = "Best tracking",
                        value = summary.bestTrackingScore?.let { Formatters.count(it, "pt") } ?: NOT_SET,
                        tone = if (summary.bestTrackingScore != null) Tone.Good else Tone.Muted,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.sm))
                RowDivider()
                CompactStat(
                    label = "Last session",
                    value = lastSessionLabel(summary.lastSession),
                    tone = Tone.Accent,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Today's recommended run as one banner, with the measured reason it was chosen and one button to start it.
 *
 * The banner is deliberately the bridge from the figures above to the grid below: the hero says what
 * happened, the plan says what to do about it, and the button goes straight to that mode's route. [plan]
 * already carries the difficulty and the evidence, so this composable adds no judgement of its own — it
 * only lays the recommendation out and resolves the mode's route.
 */
@Composable
private fun PlanBanner(
    plan: TrainingPlanRecommendation,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = scheme.primary.copy(alpha = 0.10f),
        contentColor = scheme.onSurface,
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.35f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(scheme.primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TODAY'S PLAN",
                        style = StatLabelStyle,
                        color = scheme.primary,
                    )
                    Text(
                        text = "${plan.modeLabel} · ${plan.difficulty.label}",
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = plan.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = plan.detail,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Spacing.md))
            Button(
                onClick = { onOpen(plan.mode.route()) },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Start ${plan.modeLabel}")
            }
        }
    }
}

/**
 * One destination as a mode tile: its icon, its name, and the one-line reason to tap it.
 *
 * A [ClickableCard] rather than a row, because a grid cell is a destination in its own right and should
 * carry the same spring press feedback as every other card in the app. [fillHeight] keeps the two tiles
 * in a row the same height when one description runs longer than the other.
 */
@Composable
private fun ModeTile(
    entry: AimLabDestination,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ClickableCard(
        onClick = { onOpen(entry.route) },
        modifier = modifier.defaultMinSize(minHeight = 136.dp),
        contentPadding = 12.dp,
        fillHeight = true,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = entry.label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = entry.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The route that starts [mode].
 *
 * Tilt shares the gyro card — the mode screen picks the sensor the device actually has — so both resolve
 * to [AimLabRoutes.GYRO]. Free practice is the unscored sandbox at [AimLabRoutes.PRACTICE]. Keeping the
 * mapping here means the button never spells a route literal of its own.
 */
private fun TrainingMode.route(): String = when (this) {
    TrainingMode.FLICK -> AimLabRoutes.FLICK
    TrainingMode.TRACKING -> AimLabRoutes.TRACKING
    TrainingMode.REACTION -> AimLabRoutes.REACTION
    TrainingMode.GYRO, TrainingMode.TILT -> AimLabRoutes.GYRO
    TrainingMode.RECOIL -> AimLabRoutes.RECOIL
    TrainingMode.MOVEMENT -> AimLabRoutes.MOVEMENT
    TrainingMode.FREE_PRACTICE -> AimLabRoutes.PRACTICE
}

/** One line per shelf, so the heading says why these entries are together. */
private fun groupSubtitle(group: AimLabGroup): String = when (group) {
    AimLabGroup.TRAIN -> "Scored modes and the free sandbox."
    AimLabGroup.SETUP -> "Tune the controls the runs use."
    AimLabGroup.PROGRESS -> "The record behind the figures above."
}

/**
 * A personal best that has never been set.
 *
 * A word rather than the em-dash [com.gamecore.ui.components.ABSENT] marker, for the §10 reason the rest of
 * this redesign replaced its symbols: "—" is read aloud by a screen reader as punctuation or skipped
 * entirely, so the one cell whose whole job is to say *you have not done this yet* is the one cell that says
 * nothing at all. "Not set" is also the truer word here — the figure is not unavailable, Android is not
 * withholding it, and nothing failed. It simply has not been earned yet, which is an invitation rather than
 * a fault, and a zero would have been an outright lie.
 */
private const val NOT_SET = "Not set"

/**
 * "Flick training · Today", or "Not set" if somehow there is no last session while history exists.
 *
 * Combines the mode's label with the relative day the session started, so "last session" answers both what
 * and when in the width of one stat cell. [Formatters.relativeDay] gives Today/Yesterday/date.
 */
private fun lastSessionLabel(session: SessionSummary?): String {
    if (session == null) return NOT_SET
    return "${session.mode.label} · ${Formatters.relativeDay(session.startedAtMillis)}"
}
