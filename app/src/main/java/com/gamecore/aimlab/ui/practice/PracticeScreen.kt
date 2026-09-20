package com.gamecore.aimlab.ui.practice

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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.gamecore.aimlab.engine.Stats
import com.gamecore.aimlab.engine.Weapon
import com.gamecore.aimlab.ui.input.Aim3DArena
import com.gamecore.aimlab.ui.input.currentControlOrientation
import com.gamecore.aimlab.render.RenderQuality
import com.gamecore.core.model.CrosshairPreset
import com.gamecore.core.common.Formatters
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
import com.gamecore.ui.components.StatEntry
import com.gamecore.ui.components.StatStrip
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour

/**
 * Free practice: the open sandbox of §8 — targets, a crosshair, and nothing telling you when to stop.
 *
 * Two things are deliberately missing from this screen, and their absence is the feature. There is **no
 * timer**: the run lasts until the user ends it, so setup asks nothing about duration and the live readout
 * counts up rather than down. And there is **no score**: `TrainingMode.FREE_PRACTICE` is unscored, the
 * engine returns 0 for it, and a "Score: 0" line on the result card would be that zero presented as a
 * result. What is here instead is the honest arithmetic of the run — hits, shots, accuracy, how long it
 * lasted.
 *
 * Setup asks for difficulty, then offers whatever gear the user has actually saved. Each of weapons,
 * sensitivity profiles and control layouts may be empty, and an empty one is simply not offered: no
 * placeholder entry is invented to fill the row, and a run without a weapon is a perfectly valid run.
 *
 * Ending a run is the one moment a number appears that was not already on screen. If the loop returns a
 * session, it is saved and shown; if it returns nothing — the run ended before a shot was fired — the
 * screen says so and saves nothing, rather than storing a session of zeroes (§1/§30).
 *
 * @param onBack leave the screen. Also what "Done" does: a finished run has nowhere else to go.
 */
@Composable
fun PracticeScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PracticeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loop = state.loop
    val frame = state.frame

    if (state.isLive && loop != null && frame != null) {
        LiveArena(
            loop = loop,
            state = state,
            onPause = viewModel::pause,
            onResume = viewModel::resume,
            onStop = viewModel::stopRun,
            modifier = modifier,
        )
    } else {
        SetupAndResult(
            state = state,
            onBack = onBack,
            onDifficulty = viewModel::onDifficultySelected,
            onWeapon = viewModel::onWeaponSelected,
            onSensitivity = viewModel::onSensitivitySelected,
            onLayout = viewModel::onLayoutSelected,
            onStart = viewModel::start,
            onDone = onBack,
            modifier = modifier,
        )
    }
}

// --------------------------------------------------------------------------------------------- live

/**
 * The run itself: the arena, full bleed, with the live figures over the top and the two controls below.
 *
 * The readout strip is a plain [Box] and takes no touches, so a shot that lands under the figures is still
 * a shot. The controls do take touches — that is what they are for — and they sit at the bottom, clear of
 * the floating navigation bar and away from where a thumb flicks.
 *
 * Pause and Resume are one button showing one state, read from `frame.running` rather than from a flag of
 * this screen's own, so what the button says is what the loop is actually doing.
 *
 * The colours are read here and captured by the draw lambda: a draw scope cannot ask the theme anything.
 */
@Composable
private fun LiveArena(
    loop: AimTrainingLoop3D,
    state: PracticeState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = state.running

    Aim3DArena(
        loop = loop,
        crosshair = remember { CrosshairPreset(name = "aimlab") },
        quality = RenderQuality.FULL,
        onShot = { loop.onShot(0.5f, 0.5f) },
        controlLayout = state.layout,
        controlOrientation = currentControlOrientation(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                .padding(horizontal = ScreenPadding, vertical = 10.dp),
        ) {
            // Scalar params only, so the strip skips the per-tick recompositions the arena Canvas needs:
            // elapsed advances every tick but hits/shots/accuracy change only on a shot.
            PracticeStats(
                elapsedMillis = state.elapsedMillis,
                hits = state.hits,
                shots = state.shots,
                accuracyFraction = state.accuracyFraction,
                running = running,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                // Clear of the floating navigation bar, which draws over this screen like every other.
                .padding(horizontal = ScreenPadding, vertical = 10.dp)
                .padding(bottom = ScreenBottomPadding),
        ) {
            ActionRow {
                Button(
                    onClick = if (running) onPause else onResume,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (running) "Pause" else "Resume")
                }
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Tone.Danger.colour()),
                ) {
                    Icon(imageVector = Icons.Filled.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop")
                }
            }
        }
    }
}

/**
 * The live readout: how long, and how the shooting is going.
 *
 * Elapsed counts up because there is nothing to count down to. Accuracy before the first shot is the
 * absence marker, not "0%" — a run in which nothing has been attempted has no accuracy yet.
 */
private fun liveEntries(
    elapsedMillis: Long,
    hits: Int,
    shots: Int,
    accuracyFraction: Float,
): List<StatEntry> = listOf(
    StatEntry(label = "Elapsed", value = Formatters.duration(elapsedMillis)),
    StatEntry(label = "Hits", value = hits.toString(), tone = Tone.Good),
    StatEntry(label = "Shots", value = shots.toString()),
    accuracyEntry(accuracyFraction, shots),
)

/**
 * The practice readout and the paused chip, off scalar params.
 *
 * Everything the row shows is a primitive here, so it recomposes on its own scalars rather than on the
 * whole per-tick frame the arena redraws from. Elapsed advances every tick by nature, so this row does
 * recompose each tick — but on its own, no longer dragging the arena's siblings with it.
 */
@Composable
private fun PracticeStats(
    elapsedMillis: Long,
    hits: Int,
    shots: Int,
    accuracyFraction: Float,
    running: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatStrip(
            entries = liveEntries(elapsedMillis, hits, shots, accuracyFraction),
            modifier = Modifier.weight(1f),
        )
        if (!running) {
            Spacer(modifier = Modifier.width(8.dp))
            StatusChip(text = "Paused", tone = Tone.Warning, icon = Icons.Filled.Pause)
        }
    }
}

// ------------------------------------------------------------------------------------ setup / result

@Composable
private fun SetupAndResult(
    state: PracticeState,
    onBack: () -> Unit,
    onDifficulty: (Difficulty) -> Unit,
    onWeapon: (Weapon?) -> Unit,
    onSensitivity: (SensitivityProfile?) -> Unit,
    onLayout: (ControlLayout?) -> Unit,
    onStart: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val padded = Modifier.padding(horizontal = ScreenPadding)
    val finished = state.phase == PracticePhase.Result

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Free practice",
                subtitle = if (finished) {
                    "How that session went."
                } else {
                    "An open arena with no clock and no score. Practise for as long as you like."
                },
                onBack = onBack,
            )
        }

        if (finished) {
            val summary = state.result
            if (summary != null) {
                item { ResultCard(summary = summary, modifier = padded) }
                item {
                    NoteBanner(
                        text = when {
                            state.saving -> "Saving this session…"
                            state.recorded -> "Saved to your history. Free practice is not scored, so it " +
                                "sets no records."
                            else -> "This session was not added to your history."
                        },
                        tone = if (state.recorded) Tone.Good else Tone.Muted,
                        icon = Icons.Filled.Info,
                        modifier = padded,
                    )
                }
            } else {
                item {
                    // The loop returned no session, so there is nothing to show and nothing was written.
                    NoteBanner(
                        text = "Nothing recorded — no shots were taken.",
                        tone = Tone.Muted,
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
                        text = "Your last session ended when you left the arena — practice does not carry " +
                            "on in the background, so nothing was recorded.",
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
                        "time before one disappears.",
                    icon = Icons.Filled.Adjust,
                    modifier = padded,
                ) {
                    ChoiceRow(
                        options = PracticeState.DIFFICULTIES,
                        selected = state.difficulty,
                        onSelect = onDifficulty,
                        label = { it.label },
                        perRow = 2,
                    )
                }
            }

            // Gear is optional in both senses: the run does not need any, and a user who has saved none is
            // not shown an empty row. Nothing here is invented to fill a gap.
            if (state.hasGear) {
                item {
                    SectionCard(
                        title = "Gear",
                        subtitle = "Optional. Practice runs without any of these just as well.",
                        icon = Icons.Filled.SportsEsports,
                        modifier = padded,
                    ) {
                        if (state.weapons.isNotEmpty()) {
                            ChoiceLabel("Weapon")
                            ChoiceRow(
                                options = state.weaponOptions,
                                selected = state.weapon,
                                onSelect = onWeapon,
                                label = { it?.name ?: "None" },
                                perRow = 2,
                            )
                        }
                        if (state.sensitivities.isNotEmpty()) {
                            if (state.weapons.isNotEmpty()) RowDivider()
                            ChoiceLabel("Sensitivity")
                            ChoiceRow(
                                options = state.sensitivityOptions,
                                selected = state.sensitivity,
                                onSelect = onSensitivity,
                                label = { it?.name ?: "Default" },
                                perRow = 2,
                            )
                        }
                        if (state.layouts.isNotEmpty()) {
                            if (state.weapons.isNotEmpty() || state.sensitivities.isNotEmpty()) RowDivider()
                            ChoiceLabel("Controls")
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
            }

            item {
                Box(modifier = padded) {
                    ActionRow {
                        Button(
                            onClick = onStart,
                            modifier = Modifier.weight(1f),
                            enabled = state.phase == PracticePhase.Setup,
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
                    text = "The whole screen becomes the arena. Tap a target to shoot it, drag anywhere to " +
                        "aim, and stop whenever you like — there is no clock and no score here.",
                    tone = Tone.Muted,
                    icon = Icons.Filled.SportsEsports,
                    modifier = padded,
                )
            }
        }
    }
}

/** A heading for one choice inside a card that holds several. */
@Composable
private fun ChoiceLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/**
 * The finished session, as the loop reported it.
 *
 * Four figures and no fifth: hits, shots, accuracy, duration. There is no score line, because free practice
 * is not scored — the engine produces a zero for it, and showing that zero under a "Score" heading would be
 * reporting an absence as a result.
 */
@Composable
private fun ResultCard(summary: SessionSummary, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Session",
        subtitle = "${summary.difficulty.label} · not scored",
        icon = Icons.Filled.Adjust,
        modifier = modifier,
    ) {
        StatStrip(
            entries = listOf(
                StatEntry(label = "Hits", value = summary.hits.toString(), tone = Tone.Good),
                StatEntry(label = "Shots", value = summary.shots.toString()),
                accuracyEntry(Stats.accuracy(summary.hits, summary.shots), summary.shots),
            ),
        )
        RowDivider()
        KeyValueRow(label = "Duration", value = Formatters.duration(summary.durationMillis))
        if (summary.weaponName != null) {
            KeyValueRow(label = "Weapon", value = summary.weaponName)
        }
        if (summary.sensitivityName != null) {
            KeyValueRow(label = "Sensitivity", value = summary.sensitivityName)
        }
    }
}

/**
 * Accuracy as a stat cell, with the one case that matters: before a shot is fired there is no accuracy.
 *
 * Showing "0%" then would be a figure about a run in which nothing had been attempted, which is exactly the
 * kind of number §30 rules out.
 */
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
