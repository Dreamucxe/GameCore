package com.gamecore.aimlab.ui.tracking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timeline
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.TrackingPattern
import com.gamecore.aimlab.ui.input.Aim3DArena
import com.gamecore.aimlab.ui.input.currentControlOrientation
import com.gamecore.aimlab.render.RenderQuality
import com.gamecore.core.model.CrosshairPreset
import com.gamecore.core.common.Formatters
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.Meter
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

/**
 * Tracking training: hold the crosshair on a target that will not stop moving (§3).
 *
 * The screen is three states and never two at once — choose a run, run it, read what it was worth — and the
 * phase in [TrackingState] is the only thing that decides which is on screen. Each is a separate composable
 * below because they share almost nothing: setup is a scrolling column of choices, the run is a full-height
 * arena that must not scroll under the user's thumb, and the result is a card.
 *
 * What makes this mode different from flick is that it is *continuous*. There is no shot to fire and no hit
 * to count, so the live readout is time-on-target and elapsed time and nothing else — the frame's `hits`,
 * `shots` and `score` fields are all structurally zero for `TRACKING` and are never displayed, because a
 * row reading "0 hits" would describe a mode that does not exist rather than a run going badly (§1/§30).
 *
 * Drawing is split the way the shared surface intends: [TrainingSurface] owns input and the crosshair, and
 * the `drawArena` lambda passed to it owns the target. Everything it draws comes from the frame it is given,
 * so the arena cannot show a target the engine is not scoring against.
 *
 * @param onBack pop back to the Aim Lab home screen.
 */
@Composable
fun TrackingScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TrackingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        when (state.phase) {
            TrackingPhase.Setup -> SetupPhase(
                state = state,
                onBack = onBack,
                onDifficulty = viewModel::onDifficultySelected,
                onPattern = viewModel::onPatternSelected,
                onDuration = viewModel::onDurationChanged,
                onLayout = viewModel::onLayoutSelected,
                onStart = viewModel::start,
            )

            TrackingPhase.Running -> RunningPhase(
                state = state,
                onStop = viewModel::stopRun,
            )

            TrackingPhase.Result -> ResultPhase(
                state = state,
                onBack = onBack,
                onRetry = viewModel::start,
                onDone = viewModel::reset,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------- setup

/**
 * The run to be. Difficulty sets the target's size and speed, the pattern sets the path it takes, and the
 * duration sets how long the user has to stay on it.
 *
 * Two chips per row throughout: the pattern labels run to "Accelerating" and "Decelerating", which wrap to
 * two lines at a third of the width and make the grid ragged.
 */
@Composable
private fun SetupPhase(
    state: TrackingState,
    onBack: () -> Unit,
    onDifficulty: (Difficulty) -> Unit,
    onPattern: (TrackingPattern) -> Unit,
    onDuration: (Int) -> Unit,
    onLayout: (ControlLayout?) -> Unit,
    onStart: () -> Unit,
) {
    val padded = Modifier.padding(horizontal = ScreenPadding)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            title = "Tracking training",
            subtitle = "Hold the crosshair on a moving target",
            onBack = onBack,
        )

        SectionCard(
            title = "Difficulty",
            modifier = padded,
            subtitle = "Sets the target's size and how fast it travels",
            icon = Icons.Filled.Adjust,
        ) {
            ChoiceRow(
                options = TrackingState.DIFFICULTIES,
                selected = state.difficulty,
                onSelect = onDifficulty,
                label = { it.label },
                perRow = 2,
            )
        }

        SectionCard(
            title = "Movement pattern",
            modifier = padded,
            subtitle = "The path the target follows for the whole run",
            icon = Icons.Filled.Timeline,
        ) {
            ChoiceRow(
                options = TrackingState.PATTERNS,
                selected = state.pattern,
                onSelect = onPattern,
                label = { it.label },
                perRow = 2,
            )
        }

        SectionCard(
            title = "Duration",
            modifier = padded,
            icon = Icons.Filled.Timer,
        ) {
            SliderRow(
                title = "Session length",
                value = state.durationSeconds,
                range = TrackingState.DURATION_RANGE,
                onValueChange = onDuration,
                valueLabel = "${state.durationSeconds}s",
                description = "How long the target keeps moving before the run ends",
            )
        }

        // The HUD picker, offered only when the user has saved a layout to choose. "None" is a real choice —
        // tracking needs no on-screen controls — so it heads the options.
        if (state.layouts.isNotEmpty()) {
            SectionCard(
                title = "On-screen controls",
                modifier = padded,
                subtitle = "Draw one of your saved control layouts over the arena during the run.",
                icon = Icons.Filled.Adjust,
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

        Column(modifier = padded) {
            ActionRow {
                Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start")
                }
            }
        }

        Spacer(modifier = Modifier.height(ScreenBottomPadding))
    }
}

// -------------------------------------------------------------------------------------- the run

/**
 * The live run: the arena, and the two figures that mean anything while it is happening.
 *
 * This column does not scroll. The arena is the input surface, and a vertical scroll container would
 * compete with the drag gesture that aims — the surface would lose the pointer to the scroller mid-track.
 * The arena takes every pixel the readout does not, which is why this is a [ColumnScope] extension: it
 * emits into the caller's column so the surface's `weight` measures against the whole screen.
 */
@Composable
private fun ColumnScope.RunningPhase(
    state: TrackingState,
    onStop: () -> Unit,
) {
    val loop = state.loop
    val frame = state.frame

    // The header, strip, meter and chip all read only scalars off the state, so they move into a child
    // taking primitive params. It is a ColumnScope extension, so its children stay direct members of this
    // Column — layout is unchanged — while Compose skips the whole group on the ticks where the target
    // moved but the figures did not. The TrainingSurface below stays here: it legitimately needs the frame.
    TrackingHud(
        patternLabel = state.pattern.label,
        difficultyLabel = state.difficulty.label,
        onTargetFraction = state.onTargetFraction,
        elapsedMillis = state.elapsedMillis,
        remainingSeconds = state.remainingSeconds,
        onTargetNow = state.onTargetNow,
        onStop = onStop,
    )

    if (loop != null && frame != null) {
        // The 3D room takes the rest of the column. The tracked sphere moves across the far wall and the
        // player keeps the crosshair on it by dragging; the on-target ring feedback lives in the renderer.
        Aim3DArena(
            loop = loop,
            crosshair = remember { CrosshairPreset(name = "aimlab") },
            quality = RenderQuality.FULL,
            onShot = { /* tracking is hold-to-track; a tap does nothing */ },
            controlLayout = state.layout,
            controlOrientation = currentControlOrientation(),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    } else {
        // One frame at most, between start() and the loop's first tick.
        Spacer(modifier = Modifier.weight(1f))
    }
}

/**
 * The header, live strip, meter and on/off-target chip — all off scalar params.
 *
 * A [ColumnScope] extension so its children remain direct members of the running phase's Column, leaving
 * the layout identical, while Compose skips the whole group on the many ticks where the crosshair moved
 * but none of these figures changed. Everything it reads is a primitive or a stable String.
 */
@Composable
private fun ColumnScope.TrackingHud(
    patternLabel: String,
    difficultyLabel: String,
    onTargetFraction: Float,
    elapsedMillis: Long,
    remainingSeconds: Int,
    onTargetNow: Boolean,
    onStop: () -> Unit,
) {
    val padded = Modifier.padding(horizontal = ScreenPadding)

    ScreenHeader(
        title = "Tracking",
        subtitle = "$patternLabel · $difficultyLabel",
        action = {
            IconButton(onClick = onStop) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop run")
            }
        },
    )

    StatStrip(
        modifier = padded,
        entries = listOf(
            StatEntry(
                label = "On target",
                value = Formatters.percent(onTargetFraction, decimals = 0),
                tone = Tone.Accent,
            ),
            StatEntry(label = "Elapsed", value = Formatters.duration(elapsedMillis)),
            StatEntry(label = "Left", value = "${remainingSeconds}s", tone = Tone.Muted),
        ),
    )

    Spacer(modifier = Modifier.height(8.dp))

    Column(modifier = padded) {
        Meter(
            fraction = onTargetFraction,
            tone = if (onTargetNow) Tone.Good else Tone.Muted,
        )
        Spacer(modifier = Modifier.height(8.dp))
        StatusChip(
            text = if (onTargetNow) "On target" else "Off target",
            tone = if (onTargetNow) Tone.Good else Tone.Muted,
            icon = Icons.Filled.Adjust,
        )
    }

    Spacer(modifier = Modifier.height(12.dp))
}

// ----------------------------------------------------------------------------------- the result

/**
 * What the run was worth, straight off the [SessionSummary] the loop produced.
 *
 * Every figure here is the engine's own, including the time-on-target percentage — the live readout during
 * the run was this screen's estimate, and it is discarded the moment the authoritative one exists.
 */
@Composable
private fun ResultPhase(
    state: TrackingState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    val padded = Modifier.padding(horizontal = ScreenPadding)
    val result = state.result

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            title = "Run complete",
            subtitle = "${state.pattern.label} · ${state.difficulty.label}",
            onBack = onBack,
        )

        if (result == null) {
            NoteBanner(
                text = "That run had no tracking time in it, so nothing was saved. Start another and hold " +
                    "the crosshair on the target to record a session.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
                modifier = padded,
            )
        } else {
            SectionCard(
                title = "Result",
                modifier = padded,
                subtitle = if (state.saving) "Saving…" else null,
                icon = Icons.Filled.Adjust,
            ) {
                StatStrip(
                    entries = listOf(
                        StatEntry(label = "Score", value = result.score.toString(), tone = Tone.Accent),
                        StatEntry(
                            label = "On target",
                            value = Formatters.percent(result.timeOnTargetFraction, decimals = 0),
                            tone = Tone.Good,
                        ),
                        StatEntry(
                            label = "Avg error",
                            value = Formatters.percent(result.trackingErrorAverage, decimals = 1),
                        ),
                    ),
                )

                Spacer(modifier = Modifier.height(10.dp))
                Meter(fraction = result.timeOnTargetFraction, tone = Tone.Good)
                Spacer(modifier = Modifier.height(10.dp))
                RowDivider()

                KeyValueRow(
                    label = "Time on target",
                    value = Formatters.percent(result.timeOnTargetFraction, decimals = 0),
                )
                // Error is a distance in arena units, so it is shown as a share of the arena rather than
                // as pixels — the run means the same thing on any screen size.
                KeyValueRow(
                    label = "Average tracking error",
                    value = "${Formatters.percent(result.trackingErrorAverage, decimals = 1)} of arena",
                )
                KeyValueRow(label = "Duration", value = Formatters.duration(result.durationMillis))
                KeyValueRow(label = "Score", value = result.score.toString(), tone = Tone.Accent)
            }

            if (state.wasDiscarded) {
                NoteBanner(
                    text = "This run was not added to your history — it did not hold enough on-target time " +
                        "to count as a session.",
                    tone = Tone.Warning,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                )
            }
        }

        Column(modifier = padded) {
            ActionRow {
                OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) {
                    Text("Done")
                }
                Button(onClick = onRetry, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Retry")
                }
            }
        }

        Spacer(modifier = Modifier.height(ScreenBottomPadding))
    }
}
