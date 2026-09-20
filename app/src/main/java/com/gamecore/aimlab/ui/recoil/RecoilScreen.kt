package com.gamecore.aimlab.ui.recoil

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.aimlab.runtime.AimTrainingLoop3D
import com.gamecore.aimlab.ui.input.Aim3DArena
import com.gamecore.aimlab.ui.input.currentControlOrientation
import com.gamecore.aimlab.render.RenderQuality
import com.gamecore.core.model.CrosshairPreset
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.Weapon
import com.gamecore.core.common.Formatters
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.Meter
import com.gamecore.ui.components.NoteBanner
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
import kotlin.math.exp

/**
 * Recoil control training: fire, and hold the shot on the reticle while the weapon fights you.
 *
 * The screen is in one of three states and never two. **Setup** picks the weapon — the mode is unrunnable
 * without one, because the pattern being trained *is* the weapon's `RecoilSpec` — then the difficulty the
 * session is filed under and how long to run for. **Live** is a full-bleed arena: a fixed reticle at the
 * centre, and a bright dot showing where the accumulated recoil has dragged the aim to, joined to the
 * centre by a faint trail so the climb is visible rather than inferred. **Result** is the session the loop
 * produced, as the repository stored it.
 *
 * The arena's contract with the finger is deliberately small: a press anywhere is a shot, and dragging
 * that finger is counter-aim. That is the whole skill — fire, pull back against the kick, watch the dot
 * stay near the reticle — and it needs no buttons, so there are none between the player and the surface.
 * Pointer handling is raw `awaitPointerEvent` with pointer-id tracking rather than a gesture detector: a
 * detector would swallow the press until it had decided the gesture was a tap or a drag, and here the
 * press *is* the shot and the drag that follows it is the same finger still down.
 *
 * Nothing on this screen is computed from an assumption. The live dot, the residual and the shot count are
 * the loop's own frame; the result card is the `SessionSummary` the loop returned on stop, and whether it
 * was kept is the repository's answer. There is no live score, because the loop has none for this mode:
 * recoil is scored from the mean residual across the whole run, so a mid-run figure would be a zero
 * pretending to be a reading.
 *
 * @param onBack pop back to the Aim Lab home screen.
 */
@Composable
fun RecoilScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecoilViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loop = state.loop

    if (state.isLive && loop != null) {
        RecoilArena(
            loop = loop,
            state = state,
            onStop = viewModel::stopRun,
            modifier = modifier,
        )
        return
    }

    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Recoil training",
                subtitle = "Fire, then pull back against the weapon's kick to hold your aim on centre.",
                onBack = onBack,
            )
        }

        when (state.phase) {
            RecoilPhase.Result -> item {
                ResultCard(
                    state = state,
                    onRetry = viewModel::start,
                    onDone = {
                        // Clear the finished run before leaving, so a re-entry that reuses this view model
                        // opens on setup rather than on a result card from a session already stored.
                        viewModel.reset()
                        onBack()
                    },
                    modifier = padded,
                )
            }
            // The gap between pressing Start and the loop's first frame is one tick. Saying so beats
            // flashing the setup card back with a disabled Start button.
            RecoilPhase.Running -> item {
                NoteBanner(
                    text = "Starting the run…",
                    tone = Tone.Muted,
                    icon = Icons.Filled.Timer,
                    modifier = padded,
                )
            }
            RecoilPhase.Setup -> {
                item { WeaponCard(state = state, onSelect = viewModel::onWeaponSelected, modifier = padded) }
                item {
                    RunCard(
                        state = state,
                        onDifficulty = viewModel::onDifficultySelected,
                        onDuration = viewModel::onDurationChanged,
                        onStart = viewModel::start,
                        modifier = padded,
                    )
                }
                // The HUD picker, offered only when the user has saved a layout to choose. "None" is a real
                // choice — recoil needs no on-screen controls — so it heads the options.
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
                                onSelect = viewModel::onLayoutSelected,
                                label = { it?.name ?: "None" },
                                perRow = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- setup

/**
 * The weapon choice, and what the chosen one will actually do to the aim.
 *
 * Only the four [com.gamecore.aimlab.engine.RecoilSpec] fields are listed, and that is a deliberate
 * omission rather than an oversight: the training loop simulates a weapon's recoil in this mode and
 * nothing else — fire rate, magazine, reload and spread are not applied to a recoil run — so printing
 * them here would describe a simulation that is not running (§30).
 *
 * With no weapons at all there is nothing to choose and nothing is invented to fill the gap; the banner
 * says so and Start stays disabled.
 */
@Composable
private fun WeaponCard(
    state: RecoilState,
    onSelect: (Weapon) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Weapon",
        subtitle = "The pattern you fight is this weapon's recoil.",
        icon = Icons.Filled.Adjust,
        modifier = modifier,
    ) {
        if (state.noWeapons) {
            NoteBanner(
                text = "No weapons yet",
                tone = Tone.Warning,
                icon = Icons.Filled.Info,
            )
            return@SectionCard
        }

        ChoiceRow(
            options = state.weapons,
            selected = state.weapon,
            onSelect = onSelect,
            label = { it.name },
            perRow = 2,
        )

        val weapon = state.weapon
        if (weapon == null) {
            Text(
                text = "Pick a weapon to see its recoil and to start a run.",
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.Muted.colour(),
                modifier = Modifier.padding(top = 10.dp),
            )
            return@SectionCard
        }

        val spec = weapon.recoil
        KeyValueRow(
            label = "Vertical kick",
            value = "${Formatters.percent(spec.verticalPerShot, 1)} per shot",
        )
        KeyValueRow(
            label = "Horizontal drift",
            value = "${Formatters.percent(spec.horizontalPerShot, 1)} per shot",
        )
        KeyValueRow(
            label = "Randomised",
            value = Formatters.percent(spec.randomness, 0),
        )
        KeyValueRow(label = "Recovers", value = "${recoveryPerSecondLabel(spec.recoveryPerSecond)} a second")
        Text(
            text = "Kicks are a share of the arena's width. A randomised share is different every shot, " +
                "so the rest is the part you can learn.",
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.Muted.colour(),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Difficulty, length and Start. */
@Composable
private fun RunCard(
    state: RecoilState,
    onDifficulty: (Difficulty) -> Unit,
    onDuration: (Int) -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Run",
        icon = Icons.Filled.Timer,
        modifier = modifier,
    ) {
        ChoiceRow(
            options = RecoilState.DIFFICULTIES,
            selected = state.difficulty,
            onSelect = onDifficulty,
            label = { it.label },
            perRow = 4,
        )
        Text(
            text = "Difficulty files the session and keeps its personal record separate. The kick itself " +
                "comes from the weapon, not from this.",
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.Muted.colour(),
            modifier = Modifier.padding(top = 6.dp),
        )

        SliderRow(
            title = "Length",
            value = state.durationSeconds,
            range = RecoilState.DURATION_RANGE,
            onValueChange = onDuration,
            valueLabel = "${state.durationSeconds}s",
            modifier = Modifier.padding(top = 4.dp),
        )

        ActionRow {
            Button(onClick = onStart, enabled = state.canStart) { Text("Start") }
            Spacer(modifier = Modifier.weight(1f))
            // Only once the armoury has actually been read: "pick a weapon" in the frame before the list
            // arrives is a nudge to do something the screen has not offered yet.
            if (state.weaponsLoaded && !state.noWeapons && state.weapon == null) {
                StatusChip(text = "Pick a weapon", tone = Tone.Muted, icon = Icons.Filled.Adjust)
            }
        }
    }
}

// ----------------------------------------------------------------------------------------------- live

/**
 * The full-bleed training arena.
 *
 * One pointer handler covers the whole surface. Every press is a shot at the arena's centre — the mode
 * does not test where the shot lands, it tests whether the aim was still on centre when it was taken — and
 * the finger that pressed becomes the one whose movement counter-aims, at one arena width per surface
 * width so the dot follows the finger at the same speed it moved.
 *
 * The changes are read with the consumption-aware `changedToDown`/`positionChanged` rather than their
 * `IgnoreConsumed` twins, which is what keeps the Stop button in the corner from also firing a round: a
 * child that has already claimed the press is not second-guessed here.
 */
@Composable
private fun RecoilArena(
    loop: AimTrainingLoop3D,
    state: RecoilState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A tap fires the weapon (fire-rate/magazine/reload gated in the loop); a drag counter-aims against
    // the camera kick, which is exactly what the compensation score measures. The 3D arena draws the room,
    // the muzzle flash, bullet-hole decals and the recoiling viewmodel; the reticle is the fixed crosshair.
    Aim3DArena(
        loop = loop,
        crosshair = remember { CrosshairPreset(name = "aimlab") },
        quality = RenderQuality.FULL,
        onShot = { loop.onShot(0.5f, 0.5f) },
        controlLayout = state.layout,
        controlOrientation = currentControlOrientation(),
        modifier = modifier,
    ) {
        // The reticle tracks the recoil offset every tick; the HUD reads only scalars, so it is a
        // child taking primitive params and skips the ticks where the offset moved but the figures did
        // not — which is every tick a shot is not fired and a decayed residual has not crossed a rounding
        // boundary. `residual` is a Float, stable, so an unchanged residual is a skipped recomposition.
        RecoilHud(
            remainingSeconds = state.remainingSeconds,
            shots = state.shots,
            residual = state.residual,
            onStop = onStop,
        )
    }
}

/**
 * The recoil HUD: the top stat strip and the bottom controls, off scalar params.
 *
 * Every value is a primitive, so this whole subtree is skipped on the ticks where the dot moved but the
 * time-left/shots/residual figures did not. The [StatEntry] list and the strings are built here, only
 * when it actually recomposes.
 */
@Composable
private fun BoxScope.RecoilHud(
    remainingSeconds: Int,
    shots: Int,
    residual: Float,
    onStop: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(ScreenPadding),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    ) {
        StatStrip(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            entries = listOf(
                StatEntry("Time left", "${remainingSeconds}s", Tone.Neutral),
                StatEntry("Shots", shots.toString(), Tone.Neutral),
                // The residual is the whole point of the mode: how far the aim currently sits from
                // where it started, as a share of the arena's width. One decimal, because the last
                // fraction of a percent is exactly the part a good run is fighting over.
                StatEntry("Off centre", Formatters.percent(residual, 1), Tone.Accent),
            ),
        )
    }

    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Shown until the first round is fired, then it gets out of the way for good.
        if (shots == 0) {
            NoteBanner(
                text = "Tap anywhere to fire. Keep the finger down and drag to pull the aim back " +
                    "onto the reticle.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
        }
        ActionRow {
            Button(onClick = onStop) { Text("Stop") }
            Spacer(modifier = Modifier.weight(1f))
            StatusChip(text = "Running", tone = Tone.Good, icon = Icons.Filled.PlayArrow)
        }
    }
}



// --------------------------------------------------------------------------------------------- result

/**
 * What the run came to.
 *
 * Every figure is out of the [SessionSummary] the loop returned; a null summary means the loop judged the
 * run empty and the card says that instead of showing a compensation percentage for a run with no shots.
 * Accuracy is deliberately absent — in this mode every round counts as landed, so an accuracy row would
 * read "100%" for every session ever run and mean nothing.
 */
@Composable
private fun ResultCard(
    state: RecoilState,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val result = state.result

    SectionCard(
        title = "Run complete",
        subtitle = result?.weaponName,
        icon = Icons.Filled.TrendingUp,
        modifier = modifier,
    ) {
        if (result == null) {
            NoteBanner(
                text = "No shots were fired, so there was nothing to score. Nothing was saved.",
                tone = Tone.Warning,
                icon = Icons.Filled.Info,
            )
        } else {
            StatStrip(
                entries = listOf(
                    StatEntry("Score", Formatters.count(result.score, "pt"), Tone.Accent),
                    StatEntry("Compensation", Formatters.percent(result.recoilCompensation, 0), Tone.Good),
                    StatEntry("Shots fired", result.shots.toString(), Tone.Neutral),
                ),
            )
            Meter(
                fraction = result.recoilCompensation,
                tone = Tone.Accent,
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            )
            KeyValueRow(label = "Weapon", value = result.weaponName ?: ABSENT)
            KeyValueRow(label = "Difficulty", value = result.difficulty.label)
            KeyValueRow(label = "Length", value = Formatters.duration(result.durationMillis))

            when {
                state.saving -> StatusChip(text = "Saving", tone = Tone.Muted, icon = Icons.Filled.Refresh)
                state.wasDiscarded -> NoteBanner(
                    text = "This run was not stored.",
                    tone = Tone.Warning,
                    icon = Icons.Filled.Info,
                )
                else -> StatusChip(text = "Saved", tone = Tone.Good)
            }
        }

        ActionRow {
            TextButton(onClick = onRetry, enabled = state.canStart) { Text("Retry") }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("Done") }
        }
    }
}

/**
 * "92%" for a recovery rate of 2.5 — the share of the standing offset the weapon pulls back in a second.
 *
 * The stored figure is an exponential rate, not a fraction, and `RecoilEngine.recover` retains
 * `e^(-rate × seconds)` of the offset; over one second that leaves `1 - e^(-rate)` recovered. Restating
 * the engine's own math is what makes this row a reading of the run rather than a number with a unit
 * nobody can picture.
 */
private fun recoveryPerSecondLabel(recoveryPerSecond: Float): String =
    Formatters.percent(1f - exp(-recoveryPerSecond.coerceAtLeast(0f)), 0)



