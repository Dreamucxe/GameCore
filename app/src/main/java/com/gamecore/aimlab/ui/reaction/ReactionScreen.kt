package com.gamecore.aimlab.ui.reaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.ReactionStats
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.ui.input.Aim3DArena
import com.gamecore.aimlab.ui.input.currentControlOrientation
import com.gamecore.aimlab.render.RenderQuality
import com.gamecore.core.model.CrosshairPreset
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

@Composable
fun ReactionScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReactionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val frame = state.frame
    // The loop travels in the state rather than being read off the view model during composition: a plain
    // field read is not a snapshot, so nothing would guarantee it was re-read when the run changed.
    val loop = state.loop

    if (state.phase == ReactionPhase.Live && frame != null && loop != null) {
        Column(modifier.fillMaxSize()) {
            ScreenHeader(
                title = "Reaction Test",
                subtitle = if (state.waiting) {
                    "Wait for the target — hold your fire"
                } else {
                    "Tap it. Now."
                },
                onBack = {
                    viewModel.reset()
                    onBack()
                },
            )
            // The figures are a child taking only scalars, so they skip the ticks where a target's
            // position changed but attempts/misses/elapsed/left did not — the arena below still redraws.
            ReactionHud(
                attempts = frame.hits,
                misses = frame.shots - frame.hits,
                elapsedSeconds = state.elapsedSeconds,
                remainingSeconds = state.remainingSeconds,
                onStop = viewModel::stopRun,
            )
            // The 3D room: the sphere appears without warning, and a tap fires the crosshair ray at it.
            // The loop measures the reaction from when the sphere is shown to the hit. During the wait the
            // room simply shows no target, and the overlay tells the player to hold fire.
            Aim3DArena(
                loop = loop,
                crosshair = remember { CrosshairPreset(name = "aimlab") },
                quality = RenderQuality.FULL,
                onShot = { loop.onShot(0.5f, 0.5f) },
                controlLayout = state.layout,
                controlOrientation = currentControlOrientation(),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.waiting) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(ScreenPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusChip(text = "WAITING", tone = Tone.Muted, icon = Icons.Filled.Timer)
                        Text(
                            text = "Tapping before the target appears counts as a false start.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Tone.Muted.colour(),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Reaction Test",
            subtitle = "Measure how fast you answer a target that appears without warning",
            onBack = {
                viewModel.reset()
                onBack()
            },
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val summary = state.summary
            if (summary != null) {
                item("result") {
                    ResultCard(
                        summary = summary,
                        saving = state.saving,
                        recorded = state.recorded,
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                    )
                }
                item("result-actions") {
                    ActionRow {
                        Button(onClick = { viewModel.start() }) {
                            Text("Retry")
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.reset()
                                onBack()
                            },
                        ) {
                            Text("Done")
                        }
                    }
                }
            } else {
                if (state.abandoned) {
                    item("abandoned") {
                        NoteBanner(
                            text = "Your last test was dropped when you left the arena — training does " +
                                "not carry on in the background, so nothing was recorded.",
                            tone = Tone.Warning,
                            icon = Icons.Filled.Info,
                            modifier = Modifier.padding(horizontal = ScreenPadding),
                        )
                    }
                }
                item("setup") {
                    SetupCard(
                        state = state,
                        onDifficulty = viewModel::selectDifficulty,
                        onDuration = viewModel::setDuration,
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                    )
                }
                // The HUD picker, offered only when the user has saved a layout to choose. "None" is a real
                // choice — a reaction test needs no on-screen controls — so it heads the options.
                if (state.layouts.isNotEmpty()) {
                    item("hud") {
                        SectionCard(
                            title = "On-screen controls",
                            subtitle = "Draw one of your saved control layouts over the arena during the run.",
                            icon = Icons.Filled.Adjust,
                            modifier = Modifier.padding(horizontal = ScreenPadding),
                        ) {
                            ChoiceRow(
                                options = state.layoutOptions,
                                selected = state.layout,
                                onSelect = viewModel::onLayoutSelected,
                                label = { it?.name ?: "None" },
                                perRow = 2,
                            )
                        }
                    }
                }
                item("setup-note") {
                    NoteBanner(
                        text = "The field stays dim for a random delay. The instant a target " +
                            "appears, tap it — every reaction is timed to the millisecond.",
                        tone = Tone.Muted,
                        icon = Icons.Filled.Info,
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                    )
                }
                item("setup-actions") {
                    ActionRow {
                        Button(onClick = { viewModel.start() }) {
                            Text("Start")
                        }
                    }
                }
            }
        }
    }
}

/**
 * The live figures and the stop button, off scalar params.
 *
 * All four cells are Ints, so the row skips recomposition on the ticks where the target moved but none of
 * the counters did. The [StatEntry] list is built here, only when this composable actually recomposes.
 */
@Composable
private fun ReactionHud(
    attempts: Int,
    misses: Int,
    elapsedSeconds: Int,
    remainingSeconds: Int,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = ScreenPadding),
    ) {
        StatStrip(
            entries = listOf(
                StatEntry("Attempts", attempts.toString(), Tone.Accent),
                StatEntry("Misses", misses.toString(), Tone.Warning),
                StatEntry("Elapsed", "${elapsedSeconds}s"),
                StatEntry("Left", "${remainingSeconds}s", Tone.Muted),
            ),
            modifier = Modifier.weight(1f),
        )
        // Ending early is a real end: whatever was timed up to this point is a session and is kept.
        IconButton(onClick = onStop) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = "End this test",
                tint = Tone.Danger.colour(),
            )
        }
    }
}

@Composable
private fun SetupCard(
    state: ReactionState,
    onDifficulty: (Difficulty) -> Unit,
    onDuration: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val difficulties = remember { Difficulty.entries.toList() }
    SectionCard(
        title = "Session",
        modifier = modifier,
        subtitle = "Shorter waits and smaller targets at higher difficulty",
        icon = Icons.Filled.Bolt,
    ) {
        ChoiceRow(
            options = difficulties,
            selected = state.difficulty,
            onSelect = onDifficulty,
            label = { it.label },
            modifier = Modifier.fillMaxWidth(),
        )
        RowDivider(Modifier.fillMaxWidth())
        SliderRow(
            title = "Duration",
            value = state.durationSeconds,
            range = ReactionState.DURATION_RANGE,
            onValueChange = onDuration,
            modifier = Modifier.fillMaxWidth(),
            valueLabel = "${state.durationSeconds}s",
            description = "How long the test keeps spawning targets",
        )
    }
}

@Composable
private fun ResultCard(
    summary: SessionSummary,
    saving: Boolean,
    recorded: Boolean,
    modifier: Modifier = Modifier,
) {
    val stats: ReactionStats = summary.reactionStats
    SectionCard(
        title = "Result",
        modifier = modifier,
        // Three distinct truths, and the last of them is not dressed up as the second: a write that came
        // back without a row id did not save anything, and saying "saved" there would be a lie the user
        // only discovers when the session is missing from their history.
        subtitle = when {
            saving -> "Saving session…"
            recorded -> "Session saved"
            else -> "Not saved — this session was not added to your history"
        },
        icon = Icons.Filled.Timer,
    ) {
        StatStrip(
            entries = listOf(
                StatEntry("Fastest", "${stats.fastestMillis} ms", Tone.Good),
                StatEntry("Average", "${stats.averageMillis} ms", Tone.Accent),
                StatEntry("Median", "${stats.medianMillis} ms", Tone.Neutral),
                StatEntry("Slowest", "${stats.slowestMillis} ms", Tone.Warning),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        RowDivider(Modifier.fillMaxWidth())
        KeyValueRow(
            label = "Attempts",
            value = stats.attempts.toString(),
            tone = Tone.Neutral,
            modifier = Modifier.fillMaxWidth(),
        )
        KeyValueRow(
            label = "Accuracy",
            value = "${summary.accuracyPercent.toInt()}%",
            tone = if (summary.accuracyPercent >= 80f) Tone.Good else Tone.Warning,
            modifier = Modifier.fillMaxWidth(),
        )
        KeyValueRow(
            label = "Hits / shots",
            value = "${summary.hits} / ${summary.shots}",
            tone = Tone.Muted,
            modifier = Modifier.fillMaxWidth(),
        )
        KeyValueRow(
            label = "Score",
            value = summary.score.toString(),
            tone = Tone.Accent,
            modifier = Modifier.fillMaxWidth(),
        )
        KeyValueRow(
            label = "Duration",
            value = "${summary.durationMillis / 1000L}s",
            tone = Tone.Muted,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
