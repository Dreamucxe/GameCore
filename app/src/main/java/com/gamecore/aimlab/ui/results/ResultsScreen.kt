package com.gamecore.aimlab.ui.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.aimlab.engine.NumberText
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.Stats
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.aimlab.ui.home.AimLabDestination
import com.gamecore.core.common.Formatters
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatEntry
import com.gamecore.ui.components.StatStrip
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import kotlin.math.roundToInt

/**
 * One stored session, in full — and only the parts of it that were measured.
 *
 * Seven modes share one [SessionSummary], so most of its fields are zero for any given session. This screen
 * asks [ResultsState] which of them the session's mode actually populates and draws nothing for the rest: a
 * reaction test has no tracking error and no time-on-target, a tracking run has no shots and therefore no
 * accuracy, a recoil run has no targets to miss, and free practice has no score. A "0.000" under a heading
 * the mode never measured would read as a very bad result rather than as an absence (§1/§30), so the row is
 * left out instead.
 *
 * An id that matches nothing gets a sentence saying so. That is the honest outcome for a session deleted
 * from the history screen while this one sat on the back stack — the alternative is a card of zeroes that
 * looks like a real, catastrophically bad session.
 *
 * @param sessionId the session to show. The route already carries it; passing it here lets a caller that
 *   holds the id navigate straight to a result without depending on the argument's name.
 * @param onBack leave the screen.
 */
@Composable
fun ResultsScreen(
    sessionId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ResultsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(sessionId) { viewModel.onSessionRequested(sessionId) }

    val padded = Modifier.padding(horizontal = ScreenPadding)
    val session = state.session

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Session result",
                subtitle = session?.let { "${it.mode.label} · ${it.difficulty.label}" },
                onBack = onBack,
                action = {
                    if (session != null && !session.mode.scored) {
                        StatusChip(text = "Not scored", tone = Tone.Muted)
                    }
                },
            )
        }

        if (state.isMissing) {
            item {
                EmptyState(
                    icon = Icons.Filled.History,
                    title = "That session is not here",
                    message = "Nothing is stored under this id. It was most likely deleted from your " +
                        "history — GameCore keeps no second copy of a session.",
                    actionLabel = "Back",
                    onAction = onBack,
                )
            }
            return@LazyColumn
        }

        if (session == null) return@LazyColumn

        item { OverviewCard(session = session, state = state, modifier = padded) }

        if (state.showsShooting || state.showsTargetsMissed || state.showsAcquire) {
            item { ShootingCard(session = session, state = state, modifier = padded) }
        }

        if (state.showsReaction) {
            item { ReactionCard(session = session, modifier = padded) }
        }

        if (state.showsTracking) {
            item { TrackingCard(session = session, state = state, modifier = padded) }
        }

        if (state.showsRecoil) {
            item { RecoilCard(session = session, modifier = padded) }
        }

        if (state.showsGyro) {
            item { GyroCard(session = session, modifier = padded) }
        }

        if (!session.mode.scored) {
            item {
                NoteBanner(
                    text = "${session.mode.label} is not scored, so this session set no personal records. " +
                        "What it did measure is above.",
                    tone = Tone.Muted,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------- overview

/**
 * The headline: the score where there is one, how long it took, and how accurate it was where shots were
 * fired. Each cell is conditional, so a tracking run's strip is two cells wide rather than three with a
 * fabricated one.
 */
@Composable
private fun OverviewCard(
    session: SessionSummary,
    state: ResultsState,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = session.mode.label,
        subtitle = "${session.difficulty.label} · ${Formatters.relativeDay(session.startedAtMillis)}",
        icon = iconFor(session.mode),
        modifier = modifier,
    ) {
        StatStrip(
            entries = buildList {
                if (state.showsScore) {
                    add(StatEntry(label = "Score", value = session.score.toString(), tone = Tone.Accent))
                }
                add(StatEntry(label = "Duration", value = Formatters.duration(session.durationMillis)))
                if (state.showsShooting) {
                    add(accuracyEntry(Stats.accuracy(session.hits, session.shots), session.shots))
                }
            },
        )
        RowDivider()
        KeyValueRow(label = "Started", value = Formatters.dateTime(session.startedAtMillis))
        KeyValueRow(label = "Finished", value = Formatters.clockTime(session.endedAtMillis))
        if (session.weaponName != null) {
            KeyValueRow(label = "Weapon", value = session.weaponName)
        }
        if (session.sensitivityName != null) {
            KeyValueRow(label = "Sensitivity", value = session.sensitivityName)
        }
    }
}

// -------------------------------------------------------------------------------------- shooting

/**
 * Hits, shots and what got away.
 *
 * Every row here is gated separately: tracking and gyro hold aim instead of shooting, so they have no hits
 * or shots; only the spawn-and-expire modes can let a target time out, and for those a zero is a real
 * measurement — nothing escaped — rather than an unmeasured field.
 */
@Composable
private fun ShootingCard(
    session: SessionSummary,
    state: ResultsState,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Shooting",
        icon = Icons.Filled.Adjust,
        modifier = modifier,
    ) {
        if (state.showsShooting) {
            KeyValueRow(label = "Hits", value = session.hits.toString(), tone = Tone.Good)
            KeyValueRow(label = "Shots", value = session.shots.toString())
            KeyValueRow(
                label = "Accuracy",
                value = Formatters.percent(Stats.accuracy(session.hits, session.shots), 0),
            )
        }
        if (state.showsTargetsMissed) {
            KeyValueRow(
                label = "Targets missed",
                value = session.targetsMissed.toString(),
                tone = if (session.targetsMissed > 0) Tone.Warning else Tone.Good,
            )
        }
        if (state.showsAcquire) {
            KeyValueRow(
                label = "Average acquisition",
                value = Formatters.millis(session.averageAcquireMillis.roundToInt()),
            )
        }
    }
}

// -------------------------------------------------------------------------------------- reaction

/** The reaction spread. Drawn only when attempts were recorded, which only the reaction test produces. */
@Composable
private fun ReactionCard(session: SessionSummary, modifier: Modifier = Modifier) {
    val reaction = session.reactionStats
    SectionCard(
        title = "Reaction",
        subtitle = "Measured from the moment a target appeared to the tap that hit it.",
        icon = Icons.Filled.Bolt,
        modifier = modifier,
    ) {
        KeyValueRow(label = "Attempts", value = reaction.attempts.toString())
        KeyValueRow(
            label = "Fastest",
            value = Formatters.millis(reaction.fastestMillis.toInt()),
            tone = Tone.Good,
        )
        KeyValueRow(label = "Median", value = Formatters.millis(reaction.medianMillis.roundToInt()))
        KeyValueRow(label = "Average", value = Formatters.millis(reaction.averageMillis.roundToInt()))
        KeyValueRow(label = "Slowest", value = Formatters.millis(reaction.slowestMillis.toInt()))
    }
}

// -------------------------------------------------------------------------------------- tracking

/**
 * Time on target and average error, the two figures the tracking accumulator produces.
 *
 * The error is a distance in arena units — a fraction of the arena's smaller side — which is what the engine
 * measures it in, so it is labelled as such rather than converted into pixels that would mean something
 * different on every device.
 */
@Composable
private fun TrackingCard(
    session: SessionSummary,
    state: ResultsState,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Tracking",
        subtitle = "Error is the average distance from the target, in arena units.",
        icon = Icons.Filled.Timeline,
        modifier = modifier,
    ) {
        if (state.showsTimeOnTarget) {
            KeyValueRow(
                label = "Time on target",
                value = Formatters.percent(session.timeOnTargetFraction, 0),
                tone = if (session.timeOnTargetFraction >= 0.5f) Tone.Good else Tone.Neutral,
            )
        }
        if (state.showsTrackingError) {
            KeyValueRow(
                label = "Average error",
                value = NumberText.fixed(session.trackingErrorAverage, 3),
            )
        }
    }
}

// ----------------------------------------------------------------------------------- recoil/gyro

/** How much of the weapon's kick was pulled back out. Recoil training only. */
@Composable
private fun RecoilCard(session: SessionSummary, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Recoil control",
        subtitle = "How much of the weapon's climb you compensated for.",
        icon = Icons.Filled.Layers,
        modifier = modifier,
    ) {
        KeyValueRow(
            label = "Compensation",
            value = Formatters.percent(session.recoilCompensation, 0),
            tone = if (session.recoilCompensation >= 0.6f) Tone.Good else Tone.Neutral,
        )
    }
}

/** How steady the device was held. Gyro training only. */
@Composable
private fun GyroCard(session: SessionSummary, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Gyro",
        subtitle = "Steadiness of your corrections. Less jitter is a higher figure.",
        icon = Icons.Filled.Sensors,
        modifier = modifier,
    ) {
        KeyValueRow(
            label = "Stability",
            value = Formatters.percent(session.gyroStability, 0),
            tone = if (session.gyroStability >= 0.6f) Tone.Good else Tone.Neutral,
        )
    }
}

// ----------------------------------------------------------------------------------------- bits

/** The mode's own icon from the home grid, so a result looks like the card that launched it. */
private fun iconFor(mode: TrainingMode): ImageVector =
    AimLabDestination.entries.firstOrNull { it.mode == mode }?.icon ?: Icons.Filled.Adjust

/** Accuracy as a stat cell. With no shots there is no accuracy — an absence, not 0 %. */
private fun accuracyEntry(fraction: Float, shots: Int): StatEntry = StatEntry(
    label = "Accuracy",
    value = if (shots <= 0) ABSENT else Formatters.percent(fraction, 0),
    tone = when {
        shots <= 0 -> Tone.Muted
        fraction >= 0.75f -> Tone.Good
        fraction >= 0.45f -> Tone.Neutral
        else -> Tone.Warning
    },
)
