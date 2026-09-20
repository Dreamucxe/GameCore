package com.gamecore.aimlab.ui.gyro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.aimlab.runtime.AimTrainingLoop3D
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.SensitivityProfile
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.ui.input.Aim3DArena
import com.gamecore.aimlab.ui.input.currentControlOrientation
import com.gamecore.aimlab.render.RenderQuality
import com.gamecore.core.model.CrosshairPreset
import com.gamecore.aimlab.ui.input.drawTarget
import com.gamecore.core.common.Formatters
import com.gamecore.ui.components.ABSENT
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
 * Gyro training: the phone itself is the mouse, turned to keep the crosshair on a target that will not
 * hold still.
 *
 * The screen is three states and never two at once, and one device question sits in front of all of them.
 * A phone with no gyroscope cannot do this at all, so when [GyroState.gyroAvailable] is false the screen
 * says exactly that and offers no Start button — not a greyed-out one, not a run that plays out with a
 * crosshair nailed to the centre. There is no simulated motion anywhere in this feature (§5/§30); a device
 * that lacks the sensor gets an explanation, which is the honest thing to give it.
 *
 * With the sensor present, **Setup** asks the three things that change the run: how hard, whose sensitivity
 * curve, and how long. **Live** is the whole display, because a gyro run is aimed with the hands rather
 * than with a thumb and any chrome is a place the target can hide behind. **Result** is the card, and it is
 * the first point at which a score exists — the loop scores a gyro session only when it ends, so the live
 * strip shows elapsed time and time on target and simply does not pretend to a score before there is one.
 *
 * @param onBack leave the screen. Also what "Done" does: a finished run has nowhere else to go.
 */
@Composable
fun GyroScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GyroViewModel = hiltViewModel(),
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
            onSensitivity = viewModel::onSensitivitySelected,
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
 * The target is drawn through the shared [drawTarget] so a gyro target looks like every other mode's, and
 * it is highlighted the moment the crosshair is inside it — that ring is the only feedback a gyro run has,
 * because there is nothing to tap and no hit marker to flash. The crosshair itself belongs to
 * [TrainingSurface], which draws it over whatever the arena drew.
 *
 * The strip is a plain [Box] and takes no touches. The stop button is the one control here, hard in the
 * corner, because a hand holding a phone steady is not a hand that wants a target near its thumb.
 *
 * The colours are read here and captured by the draw lambda: a draw scope cannot ask the theme anything.
 */
@Composable
private fun LiveArena(
    loop: AimTrainingLoop3D,
    state: GyroState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onTarget = state.onTargetNow

    // Gyro aims by rotating the device; the surface still takes taps (none needed here) and the loop reads
    // the gyroscope through the view model. The 3D arena draws the room and the tracked sphere.
    Aim3DArena(
        loop = loop,
        crosshair = remember { CrosshairPreset(name = "aimlab") },
        quality = RenderQuality.FULL,
        onShot = { /* gyro is hold-to-track; a tap does nothing */ },
        controlLayout = state.layout,
        controlOrientation = currentControlOrientation(),
        modifier = modifier,
    ) {
        LiveHud(
            remainingSeconds = state.remainingSeconds,
            elapsedMillis = state.elapsedMillis,
            onTargetFraction = state.onTargetFraction,
            onTargetNow = onTarget,
            onStop = onStop,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/**
 * The live figures, the on-target bar, and the on/off-target chip — all off scalar params.
 *
 * Every argument is a primitive or a nullable Float, so this subtree is skipped on the many gyro ticks
 * where the crosshair moved but none of these figures did. Nesting the chip here keeps everything that
 * reads a scalar in one skippable place.
 */
@Composable
private fun LiveHud(
    remainingSeconds: Int,
    elapsedMillis: Long,
    onTargetFraction: Float?,
    onTargetNow: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                .padding(horizontal = ScreenPadding, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatStrip(
                    entries = liveEntries(remainingSeconds, elapsedMillis, onTargetFraction),
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
            // The same fraction as the strip's figure, drawn: a bar is readable at a glance by someone
            // whose eyes are on a moving target, and a percentage is not.
            Spacer(modifier = Modifier.height(8.dp))
            Meter(fraction = onTargetFraction ?: 0f, tone = Tone.Good)
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Clear of the floating navigation bar, which draws over this screen like every other.
                .padding(bottom = ScreenBottomPadding),
        ) {
            StatusChip(
                text = if (onTargetNow) "On target" else "Off target",
                tone = if (onTargetNow) Tone.Good else Tone.Muted,
                icon = Icons.Filled.Adjust,
            )
        }
    }
}

/**
 * The live figures, each one real at the moment it is drawn.
 *
 * There is no score cell. The loop computes a gyro score when the run ends and leaves it untouched until
 * then, so a live "Score 0" would be an artefact of the engine's timing rather than a fact about the run
 * (§1). Time on target is the figure that does exist continuously, and it is the one this mode is about.
 */
private fun liveEntries(
    remainingSeconds: Int,
    elapsedMillis: Long,
    onTargetFraction: Float?,
): List<StatEntry> = listOf(
    StatEntry(
        label = "Left",
        value = Formatters.durationCoarse(remainingSeconds * 1000L),
        // The last ten seconds are the ones worth holding steady through, so they read differently.
        tone = if (remainingSeconds <= 10) Tone.Warning else Tone.Neutral,
    ),
    StatEntry(label = "Elapsed", value = Formatters.durationCoarse(elapsedMillis)),
    StatEntry(
        label = "On target",
        // Absent rather than "0%" until something has been sampled: at the first frame nothing has
        // elapsed, and a zero there reads as a miss that has not had the chance to happen yet.
        value = onTargetFraction?.let { Formatters.percent(it, 0) } ?: ABSENT,
        tone = Tone.Good,
    ),
)

// ------------------------------------------------------------------------------------ setup / result

@Composable
private fun SetupAndResult(
    state: GyroState,
    onBack: () -> Unit,
    onDifficulty: (Difficulty) -> Unit,
    onSensitivity: (SensitivityProfile) -> Unit,
    onDuration: (Int) -> Unit,
    onLayout: (ControlLayout?) -> Unit,
    onStart: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val padded = Modifier.padding(horizontal = ScreenPadding)
    val finished = state.phase == GyroPhase.Result

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Gyro training",
                subtitle = when {
                    !state.gyroAvailable -> "What this needs from your device."
                    finished -> "How that run went."
                    else -> "Turn the phone itself to follow a moving target. No thumbs involved."
                },
                onBack = onBack,
            )
        }

        // The device question comes before everything else, and when the answer is no it is the whole
        // screen: there is nothing below worth configuring for a run that cannot happen (§5).
        if (!state.gyroAvailable) {
            item {
                NoteBanner(
                    text = "This device has no gyroscope, so gyro training is unavailable.",
                    tone = Tone.Warning,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                )
            }
            item {
                NoteBanner(
                    text = "Gyro training works by reading the phone's angular velocity as you turn it. " +
                        "Without that sensor there is no aim input to read, and GameCore will not invent " +
                        "one — the other Aim Lab modes are aimed by touch and work here as normal.",
                    tone = Tone.Muted,
                    icon = Icons.Filled.ScreenRotation,
                    modifier = padded,
                )
            }
            return@LazyColumn
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
            } else if (!state.recorded) {
                item {
                    NoteBanner(
                        // Two different truths, and neither of them is a result card of zeroes. A gyro run
                        // is scored on time on target alone, so a run that never got on target genuinely
                        // has nothing in it to keep.
                        text = if (summary == null) {
                            "That run ended without any time on target, so there was nothing to record."
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
            item {
                SectionCard(
                    title = "Difficulty",
                    subtitle = "Harder levels use a smaller target on a faster, less predictable path.",
                    icon = Icons.Filled.Adjust,
                    modifier = padded,
                ) {
                    ChoiceRow(
                        options = GyroState.DIFFICULTIES,
                        selected = state.difficulty,
                        onSelect = onDifficulty,
                        label = { it.label },
                        perRow = 2,
                    )
                }
            }

            item {
                SensitivityCard(
                    state = state,
                    onSensitivity = onSensitivity,
                    modifier = padded,
                )
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
                        range = GyroState.DURATION_RANGE,
                        onValueChange = onDuration,
                        valueLabel = Formatters.durationCoarse(state.durationSeconds * 1000L),
                        description = "The run ends on its own when the time is up.",
                    )
                }
            }

            // The HUD picker, offered only when the user has saved a layout to choose. "None" is a real
            // choice — gyro needs no on-screen controls — so it heads the options.
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
                            // The same rule the view model enforces, rather than a second one that would
                            // have to be kept in step with it.
                            enabled = state.canStart,
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
                    text = "The whole screen becomes the arena once you start. Hold the phone as you would " +
                        "in a match and turn it to move the crosshair — the gyroscope is read only while a " +
                        "run is on screen, and stops the moment it ends.",
                    tone = Tone.Muted,
                    icon = Icons.Filled.ScreenRotation,
                    modifier = padded,
                )
            }
        }
    }
}

/**
 * The optional sensitivity profile.
 *
 * Optional is the point: a gyro run without a profile uses the engine's default gyro scale, which is a
 * perfectly good place to start, and most users will have no saved profiles at all when they first arrive
 * here. So an empty list is stated as a fact rather than shown as an empty control, and a chosen profile
 * can be un-chosen — a one-way choice would quietly take "the default" off the table for good.
 */
@Composable
private fun SensitivityCard(
    state: GyroState,
    onSensitivity: (SensitivityProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Sensitivity",
        subtitle = "Which of your saved profiles to aim with. Optional.",
        icon = Icons.Filled.ScreenRotation,
        modifier = modifier,
    ) {
        if (state.sensitivities.isEmpty()) {
            NoteBanner(
                text = "You have no saved sensitivity profiles yet, so this run uses the default gyro " +
                    "scale. Profiles you create in Sensitivity setup will appear here.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
            return@SectionCard
        }
        Text(
            text = "PROFILE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        ChoiceRow(
            options = state.sensitivities,
            selected = state.sensitivity,
            onSelect = onSensitivity,
            label = { it.name },
            perRow = 2,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = state.sensitivity?.let { "Tap ${it.name} again to run at the default gyro scale instead." }
                ?: "None selected — this run uses the default gyro scale.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The finished run, as the loop reported it.
 *
 * Every figure here is from the [SessionSummary] the loop produced, including the time on target — the
 * live reading the arena showed was measured over the frames the screen was handed, and this one is the
 * engine's own integral, so this is the number that goes into history and the one shown beside it.
 *
 * Aim error and stability both need a sentence: neither is a unit anyone carries around, and a bare
 * "0.08" or "62%" would be a figure the user has to guess at.
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
                StatEntry(
                    label = "On target",
                    value = Formatters.percent(summary.timeOnTargetFraction, 0),
                    tone = Tone.Good,
                ),
                StatEntry(
                    label = "Stability",
                    value = Formatters.percent(summary.gyroStability, 0),
                    tone = Tone.Accent,
                ),
            ),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Meter(fraction = summary.timeOnTargetFraction, tone = Tone.Good)
        RowDivider()
        KeyValueRow(
            label = "Average aim error",
            value = Formatters.percent(summary.trackingErrorAverage, 1),
            tone = Tone.Accent,
        )
        KeyValueRow(
            label = "Sensitivity",
            value = summary.sensitivityName ?: "Default gyro scale",
        )
        KeyValueRow(label = "Duration", value = Formatters.durationCoarse(summary.durationMillis))
        Spacer(modifier = Modifier.height(8.dp))
        NoteBanner(
            text = "Aim error is how far your crosshair sat from the target on average, as a share of the " +
                "arena — smaller is better. Stability is how steady your corrections were, where higher " +
                "means less shake.",
            tone = Tone.Muted,
            icon = Icons.Filled.Info,
        )
    }
}
