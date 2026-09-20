package com.gamecore.aimlab.ui.movement

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import com.gamecore.aimlab.ui.input.drawTarget
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
import kotlin.math.roundToInt

/**
 * Movement training: shooting while strafing (§7).
 *
 * The targets and the shooting are the flick mode's, and the difference is entirely in the scoring —
 * `Scoring.movement` is the flick score multiplied by the fraction of ticks the player spent moving. A
 * flawless run stood still scores **zero**. That single fact drives the whole layout: the two strafe pads
 * are not decoration at the edge of the screen, they are half the exercise, so they get the bottom corners
 * where thumbs naturally rest and the run controls move to the top where a thumb will not catch them.
 *
 * ### Why the pads are excluded from the surface rather than consuming the touch
 *
 * `TrainingSurface` deliberately reads `changedToDownIgnoreConsumed`, so a control drawn over it cannot
 * suppress a shot by consuming the event. A thumb parked on a strafe pad would otherwise be read as a short
 * press — a shot into empty space — and counted as a miss against the player's accuracy. The pads' rectangle
 * is therefore handed to the surface as [TrainingSurface]'s `excludeTouch`, and the same [StrafePads] value
 * positions the touch targets and draws the pads, so the three cannot disagree.
 *
 * ### What this screen does not claim
 *
 * The engine samples one boolean per tick: moving, or not. It does not model acceleration, and it does not
 * read the weapon's `movementPenalty` — for this mode the weapon contributes only its name to the saved
 * session. The result card reports what was actually stored and says plainly that the movement figure is
 * folded into the score rather than kept beside it, because `SessionSummary` has no field for it (§1/§30).
 *
 * @param onBack leave the screen. Also what "Done" does once a run has finished.
 */
@Composable
fun MovementScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MovementViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loop = state.loop
    val frame = state.frame

    if (state.isLive && loop != null && frame != null) {
        LiveArena(
            loop = loop,
            state = state,
            onStrafe = viewModel::onStrafe,
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
            onDuration = viewModel::onDurationSelected,
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
 * The run: arena behind, readout and controls at the top, the two strafe pads at the bottom corners.
 *
 * The pads are laid out from the measured arena rather than from fixed fractions, because the floating
 * navigation bar draws over this screen like every other and a pad extending under it would lose its touches
 * to the bar. [StrafePads.forArena] keeps them clear of it, and the same value is what the surface excludes.
 *
 * Which pad is held is tracked here rather than in the view model: the engine only wants "moving or not", so
 * the two booleans are OR'd on the way down and the direction is kept purely for what the chip says.
 *
 * The colours are read here and captured by the draw lambda — a draw scope cannot ask the theme anything.
 */
@Composable
private fun LiveArena(
    loop: AimTrainingLoop3D,
    state: MovementState,
    onStrafe: (Boolean) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = state.running

    var leftDown by remember { mutableStateOf(false) }
    var rightDown by remember { mutableStateOf(false) }

    // A run resumed with a thumb still on a pad would otherwise be scored as standing still until that
    // thumb moved, because pause pushes the flag false and nothing would push it back.
    LaunchedEffect(running) {
        if (running) onStrafe(leftDown || rightDown)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val arenaHeightPx = with(density) { maxHeight.toPx() }
        val bottomInsetPx = with(density) { ScreenBottomPadding.toPx() }
        val pads = remember(arenaHeightPx, bottomInsetPx) {
            StrafePads.forArena(heightPx = arenaHeightPx, bottomInsetPx = bottomInsetPx)
        }

        // Captured out of the BoxWithConstraints scope here, because inside the Aim3DArena hud lambda the
        // implicit receiver is a BoxScope and maxWidth/maxHeight are no longer in scope.
        val padTop = maxHeight * pads.top
        val padHeight = maxHeight * (pads.bottom - pads.top)
        val padWidth = maxWidth * StrafePads.WIDTH_FRACTION
        val rightPadStart = maxWidth * pads.rightStart

        // The 3D arena, with the strafe-pad rectangles carved out of its input so a thumb on a pad is not
        // read as look or a shot — the same exclusion the 2D surface used, now expressed in fractions.
        Aim3DArena(
            loop = loop,
            crosshair = remember { CrosshairPreset(name = "aimlab") },
            quality = RenderQuality.FULL,
            onShot = { loop.onShot(0.5f, 0.5f) },
            excludeTouch = { fx, fy -> pads.contains(com.gamecore.aimlab.engine.Vec2(fx, fy)) },
            controlLayout = state.layout,
            controlOrientation = currentControlOrientation(),
            modifier = Modifier.fillMaxSize(),
        ) {

        StrafePad(
            icon = Icons.Filled.KeyboardArrowLeft,
            pressed = leftDown,
            onPressedChange = { down ->
                leftDown = down
                onStrafe(down || rightDown)
            },
            modifier = Modifier
                .offset(y = padTop)
                .size(width = padWidth, height = padHeight),
        )
        StrafePad(
            icon = Icons.Filled.KeyboardArrowRight,
            pressed = rightDown,
            onPressedChange = { down ->
                rightDown = down
                onStrafe(down || leftDown)
            },
            modifier = Modifier
                .offset(x = rightPadStart, y = padTop)
                .size(width = padWidth, height = padHeight),
        )

        // Readout and run controls both sit at the top: the bottom belongs to the thumbs now.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                .padding(horizontal = ScreenPadding, vertical = 10.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Scalar params only, so the figures skip recomposition on the ticks where a target
                    // moved but hits/accuracy/time-left did not — the arena Canvas above still redraws.
                    MovementStats(
                        remainingMillis = state.remainingMillis,
                        hits = state.hits,
                        accuracyFraction = state.accuracyFraction,
                        shots = state.shots,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    MovementChip(running = running, moving = state.movingNow, direction = direction(leftDown, rightDown))
                }
                Spacer(modifier = Modifier.height(10.dp))
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
        } // end Aim3DArena hud lambda
    }
}

/**
 * One strafe pad: a touch target that reports whether any finger is on it.
 *
 * Pointers are tracked by id in a raw `awaitPointerEvent` loop rather than through a press gesture detector,
 * for the same reason the arena is: a second finger landing on the pad while the first is still down must
 * not be read as a release, and a gesture detector that tracks one press at a time would do exactly that.
 * Only the transitions are reported, so a held pad costs nothing per frame.
 *
 * Nothing is consumed here. The arena is told to ignore this rectangle instead, which holds whichever way
 * the two happen to be hit-tested.
 */
@Composable
private fun StrafePad(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    pressed: Boolean,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val report by rememberUpdatedState(onPressedChange)
    val tint = MaterialTheme.colorScheme.onBackground

    Box(
        modifier = modifier.pointerInput(Unit) {
            val down = HashSet<PointerId>(4)
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val before = down.isNotEmpty()
                    event.changes.forEach { change ->
                        if (change.pressed) down += change.id else down -= change.id
                    }
                    val now = down.isNotEmpty()
                    if (now != before) report(now)
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (icon == Icons.Filled.KeyboardArrowLeft) "Strafe left" else "Strafe right",
            tint = tint.copy(alpha = if (pressed) 0.9f else 0.45f),
            modifier = Modifier.size(36.dp),
        )
    }
}

/**
 * Draws both pads into the arena.
 *
 * A held pad is filled more strongly and outlined, so the player can see at a glance that the input is
 * registering — on a mode where standing still scores zero, a pad that silently stopped responding would
 * cost the whole run.
 */
private fun DrawScope.drawStrafePads(
    pads: StrafePads,
    colour: Color,
    leftDown: Boolean,
    rightDown: Boolean,
) {
    val top = pads.top * size.height
    val height = (pads.bottom - pads.top) * size.height
    val width = StrafePads.WIDTH_FRACTION * size.width
    val radius = CornerRadius(width * 0.18f, width * 0.18f)

    fun pad(x: Float, held: Boolean) {
        drawRoundRect(
            color = colour.copy(alpha = if (held) 0.30f else 0.12f),
            topLeft = Offset(x, top),
            size = Size(width, height),
            cornerRadius = radius,
        )
        if (held) {
            drawRoundRect(
                color = colour.copy(alpha = 0.75f),
                topLeft = Offset(x, top),
                size = Size(width, height),
                cornerRadius = radius,
                style = Stroke(width = 3f),
            )
        }
    }

    pad(0f, leftDown)
    pad(pads.rightStart * size.width, rightDown)
}

/** Which way the player is leaning. Both pads at once cancels out, and reads as plain movement. */
private fun direction(leftDown: Boolean, rightDown: Boolean): StrafeDirection = when {
    leftDown == rightDown -> StrafeDirection.None
    leftDown -> StrafeDirection.Left
    else -> StrafeDirection.Right
}

/**
 * The one chip at the top right, saying what the run is doing.
 *
 * Paused wins over moving, because a paused run banks nothing whatever the thumbs are doing. "Still" is
 * shown rather than left blank: on this mode standing still is not a neutral state, it is a run scoring zero,
 * and the player should be able to see that happening.
 */
@Composable
private fun MovementChip(running: Boolean, moving: Boolean, direction: StrafeDirection) = when {
    !running -> StatusChip(text = "Paused", tone = Tone.Warning, icon = Icons.Filled.Pause)
    moving -> StatusChip(
        text = when (direction) {
            StrafeDirection.Left -> "Left"
            StrafeDirection.Right -> "Right"
            StrafeDirection.None -> "Moving"
        },
        tone = Tone.Good,
        icon = Icons.Filled.DirectionsRun,
    )

    else -> StatusChip(text = "Still", tone = Tone.Warning, icon = Icons.Filled.DirectionsRun)
}

/**
 * The live readout.
 *
 * Time counts down, because a movement run is timed. Accuracy before the first shot is the absence marker
 * rather than "0%" — a run in which nothing has been attempted has no accuracy yet.
 */
private fun liveEntries(
    remainingMillis: Long,
    hits: Int,
    accuracyFraction: Float,
    shots: Int,
): List<StatEntry> = listOf(
    StatEntry(label = "Left", value = Formatters.duration(remainingMillis)),
    StatEntry(label = "Hits", value = hits.toString(), tone = Tone.Good),
    accuracyEntry(accuracyFraction, shots),
)

/**
 * The movement stat strip, off scalar params so it skips the per-tick recompositions the arena needs.
 *
 * The strip is a leaf: it builds its own [StatEntry] list here, only when it recomposes. `accuracyEntry`
 * shows the absence marker while [shots] is zero, so the fraction being 0 before a shot reads honestly.
 */
@Composable
private fun MovementStats(
    remainingMillis: Long,
    hits: Int,
    accuracyFraction: Float,
    shots: Int,
    modifier: Modifier = Modifier,
) {
    StatStrip(entries = liveEntries(remainingMillis, hits, accuracyFraction, shots), modifier = modifier)
}

// ------------------------------------------------------------------------------------ setup / result

@Composable
private fun SetupAndResult(
    state: MovementState,
    onBack: () -> Unit,
    onDifficulty: (Difficulty) -> Unit,
    onDuration: (Int) -> Unit,
    onWeapon: (Weapon?) -> Unit,
    onSensitivity: (SensitivityProfile?) -> Unit,
    onLayout: (ControlLayout?) -> Unit,
    onStart: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val padded = Modifier.padding(horizontal = ScreenPadding)
    val finished = state.phase == MovementPhase.Result

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Movement",
                subtitle = if (finished) {
                    "How that session went."
                } else {
                    "Shoot while strafing. Standing still scores nothing, however well you aim."
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
                            state.recorded -> "Saved to your history, and checked against your records."
                            else -> "This session was not added to your history."
                        },
                        tone = if (state.recorded) Tone.Good else Tone.Muted,
                        icon = Icons.Filled.Info,
                        modifier = padded,
                    )
                }
            } else {
                item {
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
                        text = "Your last session ended when you left the arena — training does not carry " +
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
                        options = MovementState.DIFFICULTIES,
                        selected = state.difficulty,
                        onSelect = onDifficulty,
                        label = { it.label },
                        perRow = 2,
                    )
                }
            }

            item {
                SectionCard(
                    title = "Length",
                    subtitle = "How long the run lasts. It ends on its own when the time is up.",
                    icon = Icons.Filled.Timer,
                    modifier = padded,
                ) {
                    ChoiceRow(
                        options = MovementState.DURATIONS,
                        selected = state.durationSeconds,
                        onSelect = onDuration,
                        label = { Formatters.duration(it * 1_000L) },
                        perRow = 4,
                    )
                }
            }

            if (state.hasGear) {
                item {
                    SectionCard(
                        title = "Gear",
                        subtitle = "Optional. The weapon is recorded against the session — this mode does " +
                            "not simulate its spread, fire rate or movement penalty.",
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
                            enabled = state.phase == MovementPhase.Setup,
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
                    text = "Hold a bottom corner to strafe and tap a target to shoot — both at once, which " +
                        "is the point. Your score is your flick score scaled by how much of the run you " +
                        "spent moving, so a run stood still scores zero.",
                    tone = Tone.Muted,
                    icon = Icons.Filled.DirectionsRun,
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
 * Every figure here is a field the engine actually stored for this mode. There is no "movement consistency"
 * row, because there is no such field on `SessionSummary` — the engine multiplies the flick score by it and
 * keeps only the product. Inventing the number back out of the score is not possible, so the card says where
 * it went instead of showing a figure that would have to be guessed (§30).
 */
@Composable
private fun ResultCard(summary: SessionSummary, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Session",
        subtitle = "${summary.difficulty.label} · movement",
        icon = Icons.Filled.DirectionsRun,
        modifier = modifier,
    ) {
        StatStrip(
            entries = listOf(
                StatEntry(label = "Score", value = summary.score.toString(), tone = Tone.Accent),
                StatEntry(label = "Hits", value = summary.hits.toString(), tone = Tone.Good),
                accuracyEntry(Stats.accuracy(summary.hits, summary.shots), summary.shots),
            ),
        )
        RowDivider()
        KeyValueRow(label = "Shots", value = summary.shots.toString())
        KeyValueRow(label = "Targets missed", value = summary.targetsMissed.toString())
        KeyValueRow(
            label = "Average acquire",
            // Zero means no target was ever acquired, which is an absence rather than an instant shot.
            value = if (summary.averageAcquireMillis > 0f) {
                Formatters.millis(summary.averageAcquireMillis.roundToInt())
            } else {
                ABSENT
            },
        )
        KeyValueRow(label = "Duration", value = Formatters.duration(summary.durationMillis))
        if (summary.weaponName != null) {
            KeyValueRow(label = "Weapon", value = summary.weaponName)
        }
        if (summary.sensitivityName != null) {
            KeyValueRow(label = "Sensitivity", value = summary.sensitivityName)
        }
        RowDivider()
        Text(
            text = "Your score is the flick score for this run scaled by the share of it you spent moving. " +
                "The two are not stored separately, so the movement share is not shown on its own.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Accuracy as a stat cell, with the one case that matters: before a shot is fired there is no accuracy.
 *
 * Showing "0%" then would be a figure about a run in which nothing had been attempted.
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
