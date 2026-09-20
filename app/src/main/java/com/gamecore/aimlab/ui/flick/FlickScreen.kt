package com.gamecore.aimlab.ui.flick

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.aimlab.TrainingFrame
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.runtime.AimTrainingLoop3D
import com.gamecore.aimlab.ui.input.Aim3DArena
import com.gamecore.aimlab.ui.input.currentControlOrientation
import com.gamecore.aimlab.render.RenderQuality
import com.gamecore.core.common.Formatters
import com.gamecore.core.model.CrosshairPreset
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SliderRow
import com.gamecore.ui.components.StatEntry
import com.gamecore.ui.components.StatStrip
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import kotlin.math.roundToInt

/**
 * Flick training: one target at a time (or a handful, higher up), snapped to and shot as fast as you can.
 *
 * The screen is three states and never two at once. **Setup** asks the two questions that change the run —
 * how hard, and how long — and nothing else; there is no weapon or sensitivity here, because flick training
 * is about the snap, not the gun. **Live** is deliberately the whole display: an arena edge to edge, with
 * the smallest honest strip of figures at the top, because every pixel of chrome is a pixel the user cannot
 * flick to. **Result** is the card, and it is the first moment the screen shows a number that was not on
 * screen a frame ago.
 *
 * Nothing here is drawn from anything but the loop. The live strip is the current frame; the result card is
 * the session the loop produced; and if the loop produced nothing — a run ended before a shot was fired —
 * the screen says that in a banner instead of showing a result of zeroes (§1/§30).
 *
 * @param onBack leave the screen. Also what "Done" does: a finished run has nowhere else to go.
 */
@Composable
fun FlickScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FlickViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loop = state.loop
    val frame = state.frame

    if (state.isLive && loop != null && frame != null) {
        LiveArena(
            loop = loop,
            state = state,
            onStop = viewModel::stopRun,
            modifier = modifier,
        )
    } else {
        SetupAndResult(
            state = state,
            onBack = onBack,
            onDifficulty = viewModel::onDifficultySelected,
            onDuration = viewModel::onDurationChanged,
            onLayout = viewModel::onLayoutSelected,
            onStart = viewModel::start,
            onDone = onBack,
            modifier = modifier,
        )
    }
}

// --------------------------------------------------------------------------------------------- live

/**
 * The run itself: the arena, full bleed, with the live figures floating over it.
 *
 * The strip is a plain [Box] rather than anything clickable, so it draws over the surface without taking a
 * single touch away from it — a shot that lands under the figures is still a shot. The one control that
 * does take touches is the stop button, which is why it sits hard in the corner.
 *
 * The colours are read here and captured by the draw lambda: a draw scope cannot ask the theme anything.
 */
@Composable
private fun LiveArena(
    loop: AimTrainingLoop3D,
    state: FlickState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The first-person 3D arena: the GL room + spheres, the fixed centre crosshair, and the HUD over it.
    // The arena forwards a centre tap as a shot and drags as look, straight into the 3D loop. If the user
    // picked a control layout on setup, it is drawn over the arena for the orientation on screen.
    Aim3DArena(
        loop = loop,
        crosshair = remember { CrosshairPreset(name = "aimlab") },
        quality = RenderQuality.FULL,
        onShot = { loop.onShot(0.5f, 0.5f) },
        controlLayout = state.layout,
        controlOrientation = currentControlOrientation(),
        modifier = modifier,
    ) {
        // The HUD reads only the scalar figures, never the render frame, so it is a separate composable
        // taking primitive params — skipped on any tick where those scalars are unchanged while the GL
        // surface still redraws the 3D world every frame.
        LiveHud(
            remainingSeconds = state.remainingSeconds,
            hits = state.hits,
            shots = state.shots,
            accuracyPercent = state.accuracyPercent,
            score = state.score,
            difficultyLabel = state.difficulty.label,
            onStop = onStop,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/**
 * The live figures and the stop button, over the arena.
 *
 * Every parameter is a primitive or a stable String/lambda, so this whole subtree is skipped on the many
 * ticks where none of them change. The stat list and the "pts" string are built here, from the params, so
 * their per-tick allocation happens only when the composable actually recomposes.
 */
@Composable
private fun LiveHud(
    remainingSeconds: Int,
    hits: Int,
    shots: Int,
    accuracyPercent: Int,
    score: Int,
    difficultyLabel: String,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                .padding(horizontal = ScreenPadding, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatStrip(
                    entries = liveEntries(remainingSeconds, hits, shots, accuracyPercent),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onStop) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "End this run",
                        tint = Tone.Danger.colour(),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Clear of the floating navigation bar, which draws over this screen like every other.
                .padding(bottom = ScreenBottomPadding),
        ) {
            StatusChip(
                text = "$difficultyLabel · $score pts",
                tone = Tone.Accent,
                icon = Icons.Filled.Bolt,
            )
        }
    }
}

/** The four live figures §2 asks for, built from the scalar readout the HUD already holds. */
private fun liveEntries(
    remainingSeconds: Int,
    hits: Int,
    shots: Int,
    accuracyPercent: Int,
): List<StatEntry> = listOf(
    StatEntry(
        label = "Left",
        value = Formatters.durationCoarse(remainingSeconds * 1000L),
        // The last ten seconds are the ones worth pushing through, so they read differently.
        tone = if (remainingSeconds <= 10) Tone.Warning else Tone.Neutral,
    ),
    StatEntry(label = "Hits", value = hits.toString(), tone = Tone.Good),
    StatEntry(label = "Shots", value = shots.toString()),
    accuracyEntry(accuracyPercent, shots),
)

// ------------------------------------------------------------------------------------ setup / result

@Composable
private fun SetupAndResult(
    state: FlickState,
    onBack: () -> Unit,
    onDifficulty: (Difficulty) -> Unit,
    onDuration: (Int) -> Unit,
    onLayout: (ControlLayout?) -> Unit,
    onStart: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val padded = Modifier.padding(horizontal = ScreenPadding)
    val finished = state.phase == FlickPhase.Result

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Flick training",
                subtitle = if (finished) {
                    "How that run went."
                } else {
                    "Targets appear across the arena. Snap to each one and shoot it before it goes."
                },
                onBack = onBack,
            )
        }

        if (finished) {
            val summary = state.result
            if (summary != null) {
                item { ResultCard(summary = summary, modifier = padded) }
            }
            if (state.saving) {
                item {
                    NoteBanner(
                        text = "Saving this session…",
                        tone = Tone.Muted,
                        icon = Icons.Filled.Info,
                        modifier = padded,
                    )
                }
            } else if (state.wasDiscarded) {
                item {
                    NoteBanner(
                        // Two different truths, and neither of them is a result card of zeroes.
                        text = if (summary == null) {
                            "That run ended before anything happened, so there was nothing to record."
                        } else {
                            "That run was not added to your history — there was too little in it to score."
                        },
                        tone = Tone.Warning,
                        icon = Icons.Filled.Info,
                        modifier = padded,
                    )
                }
            }
            item {
                Box(modifier = padded) {
                    ActionRow {
                        Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                            Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Retry")
                        }
                        TextButton(onClick = onDone) { Text("Done") }
                    }
                }
            }
        } else {
            if (state.abandoned) {
                item {
                    NoteBanner(
                        text = "Your last run was dropped when you left the arena — training does not " +
                            "carry on in the background, so nothing was recorded.",
                        tone = Tone.Warning,
                        icon = Icons.Filled.Info,
                        modifier = padded,
                    )
                }
            }

            item {
                SectionCard(
                    title = "Difficulty",
                    subtitle = "Harder levels use smaller targets, spawn them sooner, and give you less " +
                        "time before one counts as missed.",
                    icon = Icons.Filled.Bolt,
                    modifier = padded,
                ) {
                    ChoiceRow(
                        options = FlickState.DIFFICULTIES,
                        selected = state.difficulty,
                        onSelect = onDifficulty,
                        label = { it.label },
                        perRow = 2,
                    )
                }
            }

            item {
                SectionCard(
                    title = "Session length",
                    icon = Icons.Filled.Timer,
                    modifier = padded,
                ) {
                    SliderRow(
                        title = "Duration",
                        value = state.durationSeconds,
                        range = FlickState.DURATION_RANGE,
                        onValueChange = onDuration,
                        valueLabel = Formatters.durationCoarse(state.durationSeconds * 1000L),
                        description = "The run ends on its own when the time is up.",
                    )
                }
            }

            // The HUD picker, offered only when the user has saved a layout to choose. "None" is a real
            // choice — flick needs no on-screen controls — so it heads the options.
            if (state.layouts.isNotEmpty()) {
                item {
                    SectionCard(
                        title = "On-screen controls",
                        subtitle = "Draw one of your saved control layouts over the arena during the run.",
                        icon = Icons.Filled.Adjust,
                        modifier = padded,
                    ) {
                        ChoiceRow(
                            options = state.layoutOptions,
                            selected = state.layout,
                            onSelect = onLayout,
                            label = { it?.name ?: "None" },
                            perRow = 2,
                        )
                    }
                }
            }

            item {
                Box(modifier = padded) {
                    ActionRow {
                        Button(
                            onClick = onStart,
                            modifier = Modifier.weight(1f),
                            enabled = state.phase == FlickPhase.Setup,
                        ) {
                            Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Start")
                        }
                    }
                }
            }

            item {
                NoteBanner(
                    text = "The whole screen becomes the arena once you start. Tap a target to shoot it; " +
                        "drag anywhere to aim.",
                    tone = Tone.Muted,
                    icon = Icons.Filled.SportsEsports,
                    modifier = padded,
                )
            }
        }
    }
}

/**
 * The finished run, as the loop reported it.
 *
 * Average acquisition is the figure this mode exists for — the time between a target appearing and it being
 * hit — so it gets a row of its own rather than a cell in the strip. A run that never acquired anything has
 * no average, and shows the absence marker instead of "0 ms", which would read as impossibly fast.
 */
@Composable
private fun ResultCard(summary: SessionSummary, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Result",
        subtitle = "${summary.difficulty.label} · ${Formatters.durationCoarse(summary.durationMillis)}",
        icon = Icons.Filled.Adjust,
        modifier = modifier,
    ) {
        StatStrip(
            entries = listOf(
                StatEntry(label = "Score", value = summary.score.toString(), tone = Tone.Accent),
                accuracyEntry(summary.accuracyPercent, summary.shots),
                StatEntry(label = "Hits", value = "${summary.hits} / ${summary.shots}", tone = Tone.Good),
            ),
        )
        RowDivider()
        KeyValueRow(
            label = "Average acquisition",
            value = if (summary.averageAcquireMillis > 0f) {
                Formatters.millis(summary.averageAcquireMillis.roundToInt())
            } else {
                ABSENT
            },
            tone = Tone.Accent,
        )
        KeyValueRow(
            label = "Targets missed",
            value = summary.targetsMissed.toString(),
            tone = if (summary.targetsMissed > 0) Tone.Warning else Tone.Good,
        )
        KeyValueRow(label = "Duration", value = Formatters.durationCoarse(summary.durationMillis))
    }
}

/**
 * Accuracy as a stat cell, with the one case that matters: before a shot is fired there is no accuracy.
 *
 * Showing "0%" then would be a figure about a run in which nothing had been attempted, which is exactly the
 * kind of number §30 rules out.
 */
private fun accuracyEntry(percent: Int, shots: Int): StatEntry = StatEntry(
    label = "Accuracy",
    value = if (shots <= 0) ABSENT else "$percent%",
    tone = when {
        shots <= 0 -> Tone.Muted
        percent >= 75 -> Tone.Good
        percent >= 45 -> Tone.Neutral
        else -> Tone.Warning
    },
)
