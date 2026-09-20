package com.gamecore.aimlab.ui.sensitivity

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.aimlab.engine.SensitivityMath
import com.gamecore.aimlab.engine.SensitivityPreset
import com.gamecore.aimlab.engine.SensitivityProfile
import com.gamecore.aimlab.ui.input.TouchMath
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.LegendKey
import com.gamecore.ui.components.NavRow
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
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.TextFieldRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * §10's Sensitivity Lab: the saved profiles, and a place to feel one before saving it.
 *
 * Every control here is a real parameter of [SensitivityMath]. There is no cosmetic slider and no field
 * that is stored but unread — the exponent is the curve the engine raises input to, the deadzone is the
 * magnitude below which it returns exactly zero, and the smoothing percentage is the weight it keeps from
 * the previous output. The sliders' ranges are the engine's own clamps, derived from the constants in
 * [SensitivityProfile] rather than written out again (see [SensitivityDraft]).
 *
 * The live pad is why the lab is worth opening rather than a table of numbers. A finger dragged across it
 * produces genuine pointer deltas, [TouchMath.deltaToLook] turns them into the full-scale look units the
 * engine speaks, and [SensitivityMath.apply] — the same instance a run would use, smoothing memory and all
 * — turns those into camera movement. The pale trail is the finger; the accent trail is what the profile
 * did with it. Nothing on that pad is a formula reimplemented for the picture.
 *
 * The response curve underneath is the same maths swept from a standstill to a full-width swipe, so the
 * exponent bends it, the deadzone flattens its start, inversion flips it under the axis and smoothing —
 * which depends on what came before, not on the input alone — shows as a sweep that never catches up.
 *
 * "Active" means active *in this lab*: the profile the pad and the curve are running. GameCore has no
 * app-wide sensitivity setting for it to be — every drill picks a profile when a run starts — and the
 * screen says so rather than implying a default it cannot store.
 *
 * @param onBack pop back to the Aim Lab home screen.
 */
@Composable
fun SensitivityLabScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SensitivityLabViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val padded = Modifier.padding(horizontal = ScreenPadding)

    // The dialog sits outside the list rather than in an item, so a confirmation cannot be disposed by
    // scrolling the row that raised it off the screen.
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScreenHeader(
                    title = "Sensitivity lab",
                    subtitle = "Camera and gyro sensitivity, response curve, deadzone and smoothing — " +
                        "with a pad to feel them on.",
                    onBack = onBack,
                    action = {
                        IconButton(onClick = viewModel::newProfile) {
                            Icon(Icons.Filled.Add, contentDescription = "New profile")
                        }
                    },
                )
            }

            item {
                ProfileListCard(
                    state = state,
                    onSetActive = viewModel::setActive,
                    onNew = viewModel::newProfile,
                    modifier = padded,
                )
            }

            item {
                ProfileFieldsCard(
                    state = state,
                    onDraft = viewModel::updateDraft,
                    onPreset = viewModel::applyPreset,
                    modifier = padded,
                )
            }

            item {
                PreviewCard(
                    state = state,
                    onAiming = viewModel::setPreviewAiming,
                    onGyro = viewModel::setPreviewGyro,
                    modifier = padded,
                )
            }

            item {
                CurveCard(
                    state = state,
                    onDraft = viewModel::updateDraft,
                    modifier = padded,
                )
            }

            item {
                AxisCard(
                    state = state,
                    onDraft = viewModel::updateDraft,
                    modifier = padded,
                )
            }

            item {
                EditorActions(
                    state = state,
                    onSave = viewModel::save,
                    onDuplicate = viewModel::duplicate,
                    onDelete = viewModel::requestDelete,
                    modifier = padded,
                )
            }
        }

        if (state.confirmingDelete) {
            ConfirmDialog(
                title = "Delete this profile?",
                message = "\"${state.draft.name}\" is removed. Sessions already recorded with it keep the " +
                    "name they were run under, but the profile cannot be brought back.",
                confirmLabel = "Delete it",
                onConfirm = viewModel::confirmDelete,
                onDismiss = viewModel::dismissDelete,
                isDestructive = true,
            )
        }
    }
}

// --------------------------------------------------------------------------------------------- list

/**
 * The saved profiles, in the repository's own order.
 *
 * A row says what identifies a profile at a glance — its name, its two base multipliers, its curve and its
 * deadzone — and tapping one makes it active: it opens in the editor below and the pad starts running it
 * on the next frame.
 */
@Composable
private fun ProfileListCard(
    state: SensitivityLabState,
    onSetActive: (Long) -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Profiles",
        modifier = modifier,
        subtitle = if (state.profiles.isEmpty()) null else "Tap one to make it active and tune it below.",
        icon = Icons.Filled.Tune,
        action = {
            IconButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = "New profile")
            }
        },
    ) {
        when {
            // The first frame, before the database has answered. Neither list nor empty state is true yet,
            // so the card shows nothing rather than flashing "no profiles".
            state.loading -> Unit

            state.profiles.isEmpty() -> EmptyState(
                icon = Icons.Filled.Tune,
                title = "No profiles yet",
                message = "Build one here and every drill that asks for a sensitivity will be able to " +
                    "offer it.",
                actionLabel = "New profile",
                onAction = onNew,
            )

            else -> state.profiles.forEachIndexed { index, profile ->
                if (index > 0) RowDivider()
                val isActive = profile.id == state.activeId
                NavRow(
                    title = profile.name,
                    onClick = { onSetActive(profile.id) },
                    description = state.subtitleFor(profile),
                    icon = Icons.Filled.Tune,
                    trailing = if (isActive) "Active" else profile.preset.label,
                    trailingTone = if (isActive) Tone.Accent else Tone.Muted,
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------------- the profile

/**
 * What the profile is called, which starting point it came from, and the four base multipliers.
 *
 * The four sensitivity figures are two pairs: camera and gyro, each with the multiplier that applies while
 * aiming down sights. Only one pair is live for any given input, which is what the preview's two switches
 * select — so each description says which input it governs rather than leaving the user to guess why
 * moving one of them changed nothing on the pad.
 */
@Composable
private fun ProfileFieldsCard(
    state: SensitivityLabState,
    onDraft: (SensitivityDraft) -> Unit,
    onPreset: (SensitivityPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft

    SectionCard(
        title = state.editorTitle,
        modifier = modifier,
        subtitle = if (state.isNew) "Not saved yet. The pad below already runs these numbers." else null,
        icon = Icons.Filled.Speed,
        action = { StatusChip(text = draft.preset.label, tone = Tone.Muted) },
    ) {
        TextFieldRow(
            label = "Name",
            value = draft.name,
            onValueChange = { onDraft(draft.copy(name = it)) },
            placeholder = "Claw grip",
            maxLength = SensitivityDraft.MAX_NAME_LENGTH,
            description = "What this profile is called in a drill's setup and on your results.",
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "STARTING POINT",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        ChoiceRow(
            options = SensitivityDraft.PRESETS,
            selected = draft.preset,
            onSelect = onPreset,
            label = { it.label },
            perRow = 3,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "A preset sets the camera and gyro figures and nothing else — they are the only two " +
                "values it carries. Change either by hand and this profile becomes Custom.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowDivider()

        SliderRow(
            title = "Camera sensitivity",
            value = draft.cameraHundredths,
            range = SensitivityDraft.SENSITIVITY_RANGE,
            onValueChange = { onDraft(draft.copy(cameraHundredths = it)) },
            valueLabel = "×${SensitivityDraft.hundredthsLabel(draft.cameraHundredths)}",
            description = "The base multiplier on touch look input. Everything else multiplies onto this.",
        )
        SliderRow(
            title = "ADS multiplier",
            value = draft.adsHundredths,
            range = SensitivityDraft.MULTIPLIER_RANGE,
            onValueChange = { onDraft(draft.copy(adsHundredths = it)) },
            valueLabel = "×${SensitivityDraft.hundredthsLabel(draft.adsHundredths)}",
            description = "Applied on top of the camera figure while aiming. Below ×1.00 slows the aim " +
                "down, which is what most sighted weapons want.",
        )
        SliderRow(
            title = "Gyro sensitivity",
            value = draft.gyroHundredths,
            range = SensitivityDraft.SENSITIVITY_RANGE,
            onValueChange = { onDraft(draft.copy(gyroHundredths = it)) },
            valueLabel = "×${SensitivityDraft.hundredthsLabel(draft.gyroHundredths)}",
            description = "The base multiplier used instead of the camera figure when the input came " +
                "from the gyroscope.",
        )
        SliderRow(
            title = "Gyro ADS multiplier",
            value = draft.gyroAdsHundredths,
            range = SensitivityDraft.MULTIPLIER_RANGE,
            onValueChange = { onDraft(draft.copy(gyroAdsHundredths = it)) },
            valueLabel = "×${SensitivityDraft.hundredthsLabel(draft.gyroAdsHundredths)}",
            description = "The ADS multiplier for gyro input, which is usually gentler than the touch one.",
        )
    }
}

// ------------------------------------------------------------------------------------- live preview

/**
 * The pad, its readout, and the two switches that pick which half of the profile is being felt.
 *
 * The pad's state lives in this card and nowhere else: a finger on it recomposes [PadReadout] and redraws
 * one canvas, and never reaches the view model. A digitiser reporting 240 events a second must not cost 240
 * recompositions of a screen with eight cards on it (§21).
 */
@Composable
private fun PreviewCard(
    state: SensitivityLabState,
    onAiming: (Boolean) -> Unit,
    onGyro: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = state.previewProfile
    val aiming = state.previewAiming
    val gyro = state.previewGyro

    // A fresh trace whenever the maths changes, so a trail drawn under the previous numbers is never left
    // sitting next to the new ones as if it belonged to them.
    val trace = remember(profile, aiming, gyro) { PadTrace() }

    SectionCard(
        title = "Live preview",
        modifier = modifier,
        subtitle = "Drag on the pad. The pale trail is your finger; the accent trail is where this " +
            "profile put the camera.",
        icon = Icons.Filled.TouchApp,
    ) {
        PreviewPad(profile = profile, aiming = aiming, gyro = gyro, trace = trace)
        Spacer(modifier = Modifier.height(10.dp))
        PadReadout(trace = trace)
        RowDivider()
        SwitchRow(
            title = "Preview aiming",
            checked = aiming,
            onCheckedChange = onAiming,
            description = if (gyro) {
                "Applies the gyro ADS multiplier to everything the pad produces."
            } else {
                "Applies the ADS multiplier to everything the pad produces."
            },
        )
        SwitchRow(
            title = "Preview gyro input",
            checked = gyro,
            onCheckedChange = onGyro,
            description = "Runs the pad's movement through the gyro pair of multipliers instead of the " +
                "camera pair. The pad is still your finger — this is the maths a tilt would take, not a " +
                "reading from the sensor.",
        )
        Spacer(modifier = Modifier.height(4.dp))
        NoteBanner(
            text = "Active means active in this lab: it is the profile the pad and the curve run on. Each " +
                "drill still picks its own profile when you start a run.",
            tone = Tone.Muted,
            icon = Icons.Filled.Info,
        )
    }
}

/**
 * The touch area itself.
 *
 * Input is real `PointerInputChange` data tracked by pointer id, the same form the training surface uses
 * and for the same reasons: `pointerInteropFilter` is an experimental API this project has no opt-in for,
 * and `detectDragGestures` hands over a synthesised drag rather than the per-event deltas the engine has to
 * be fed to show its smoothing behaving. The first finger down owns the pad until it lifts; a second one
 * laid down meanwhile is ignored rather than fighting it for the trail.
 *
 * Every change is consumed. The pad lives inside a `LazyColumn`, and an unconsumed vertical drag would be
 * taken by the scroll container halfway through the gesture — the user would be scrolling the screen
 * instead of testing the profile.
 *
 * The [SensitivityMath] instance is built once per arming of the handler rather than per event, because it
 * is stateful: its smoothing memory is precisely the thing the smoothing slider is tuning, and rebuilding
 * it every frame would make any smoothing setting look like none.
 */
@Composable
private fun PreviewPad(
    profile: SensitivityProfile,
    aiming: Boolean,
    gyro: Boolean,
    trace: PadTrace,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val padColour = scheme.surfaceVariant
    val guide = scheme.outlineVariant
    val fingerColour = scheme.onSurfaceVariant
    val cameraColour = scheme.primary

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PAD_HEIGHT_DP.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(padColour)
            .pointerInput(profile, aiming, gyro) {
                val math = SensitivityMath(profile)
                var tracked: PointerId? = null
                var lastAt = Offset.Zero
                var cameraAt = Offset.Zero
                var fingerTravel = 0f
                var cameraTravel = 0f

                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val changes = event.changes
                        val held = tracked?.let { id -> changes.firstOrNull { it.id == id && it.pressed } }
                        val current = held ?: changes.firstOrNull { it.pressed }

                        if (current == null) {
                            // Every finger is up. The smoother's memory goes with the gesture, so the next
                            // drag starts from rest rather than inheriting the last one's momentum.
                            tracked = null
                            math.reset()
                            trace.end()
                            continue
                        }

                        if (current.id != tracked) {
                            tracked = current.id
                            lastAt = current.position
                            cameraAt = current.position
                            fingerTravel = 0f
                            cameraTravel = 0f
                            math.reset()
                            trace.begin(current.position)
                            current.consume()
                            continue
                        }

                        val dpx = current.position.x - lastAt.x
                        val dpy = current.position.y - lastAt.y
                        lastAt = current.position

                        // One surface width is ±1.0 of raw input, exactly as on the training surface.
                        val raw = TouchMath.deltaToLook(dpx, dpy, size.width, size.height)
                        // Zero deltas are fed through deliberately: a held-still finger is how a smoothed
                        // profile is seen settling back to rest, and how a deadzone is seen swallowing a
                        // tremor.
                        val out = math.apply(raw.x, raw.y, aiming = aiming, gyro = gyro)

                        fingerTravel += hypot(raw.x, raw.y)
                        cameraTravel += hypot(out.x, out.y)
                        // Back into pixels the same way round: both axes scale by the width, so the camera
                        // trail is the finger trail's own units and the two are directly comparable.
                        cameraAt = Offset(
                            x = (cameraAt.x + out.x * size.width).coerceIn(0f, size.width.toFloat()),
                            y = (cameraAt.y + out.y * size.width).coerceIn(0f, size.height.toFloat()),
                        )
                        trace.extend(
                            fingerAt = current.position,
                            cameraAt = cameraAt,
                            reading = PadSample(
                                active = true,
                                rawX = raw.x,
                                rawY = raw.y,
                                outX = out.x,
                                outY = out.y,
                                fingerTravel = fingerTravel,
                                cameraTravel = cameraTravel,
                            ),
                        )
                        current.consume()
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLine(guide, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1f)
            drawLine(guide, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1f)
            drawTrail(trace.finger, fingerColour.copy(alpha = 0.45f))
            drawTrail(trace.camera, cameraColour)
            trace.camera.lastOrNull()?.let {
                drawCircle(cameraColour, radius = PAD_DOT_PX, center = it)
            }
            trace.finger.lastOrNull()?.let {
                drawCircle(fingerColour.copy(alpha = 0.7f), radius = PAD_DOT_PX, center = it)
            }
        }

        // Reads one boolean that flips exactly once per trace, so the hint costs one recomposition rather
        // than one per pointer event.
        if (!trace.touched) {
            Text(
                text = "Drag a finger here",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * What the pad just produced, in the engine's own units.
 *
 * The per-event deltas are the honest live figures — what went in, what came out — and the travel pair is
 * what those add up to over the whole drag, which is the number that actually answers "is this profile too
 * fast". Gain is their ratio and is absent, not zero, before anything has been dragged: a profile nobody
 * has touched has no measured gain.
 *
 * This composable reads the trace's sample and is therefore the only thing on the screen that recomposes
 * while a finger is moving.
 */
@Composable
private fun PadReadout(trace: PadTrace, modifier: Modifier = Modifier) {
    val sample = trace.sample
    Column(modifier = modifier) {
        StatStrip(
            entries = listOf(
                StatEntry(label = "Raw x", value = deltaLabel(sample.rawX)),
                StatEntry(label = "Raw y", value = deltaLabel(sample.rawY)),
                StatEntry(label = "Out x", value = deltaLabel(sample.outX), tone = Tone.Accent),
                StatEntry(label = "Out y", value = deltaLabel(sample.outY), tone = Tone.Accent),
            ),
        )
        RowDivider()
        KeyValueRow(
            label = "Finger travel",
            value = "${ratioLabel(sample.fingerTravel)} pad widths",
        )
        KeyValueRow(
            label = "Camera travel",
            value = "${ratioLabel(sample.cameraTravel)} pad widths",
            tone = Tone.Accent,
        )
        KeyValueRow(
            label = "Measured gain",
            value = sample.gain?.let { "×${ratioLabel(it)}" } ?: ABSENT,
            tone = if (sample.gain == null) Tone.Muted else Tone.Neutral,
        )
    }
}

// -------------------------------------------------------------------------------------- the curve

/**
 * The response curve, and the three sliders that shape it.
 *
 * The curve is drawn by sweeping the input from a standstill to a full-width swipe through one instance of
 * [SensitivityMath] — the same class, the same call, the same order of operations a run uses. That is what
 * lets smoothing appear at all: it is a function of what came before rather than of the input alone, so on
 * a sweep it shows as an output that never quite catches the shape the exponent alone would give. The
 * figures under the drawing are read off that sweep rather than restated from the sliders.
 *
 * The preview sits above the sliders so a thumb on one is not covering the drawing it is changing.
 */
@Composable
private fun CurveCard(
    state: SensitivityLabState,
    onDraft: (SensitivityDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    val profile = state.previewProfile
    val aiming = state.previewAiming
    val gyro = state.previewGyro

    val horizontal = remember(profile, aiming, gyro) { responseSweep(profile, aiming, gyro, vertical = false) }
    val vertical = remember(profile, aiming, gyro) { responseSweep(profile, aiming, gyro, vertical = true) }
    // The same sweep with the smoothing removed, so the cost of smoothing can be stated as a figure
    // instead of described. Still the engine's maths — only one of its parameters differs.
    val unsmoothed = remember(profile, aiming, gyro) {
        if (profile.smoothingPercent <= 0) {
            horizontal
        } else {
            responseSweep(profile.copy(smoothingPercent = 0), aiming, gyro, vertical = false)
        }
    }

    val scheme = MaterialTheme.colorScheme
    val horizontalColour = scheme.primary
    val verticalColour = scheme.tertiary
    val guide = scheme.outlineVariant

    val peak = horizontal.maxOfOrNull { abs(it) } ?: 0f
    val unsmoothedPeak = unsmoothed.maxOfOrNull { abs(it) } ?: 0f

    SectionCard(
        title = "Response curve",
        modifier = modifier,
        subtitle = "Output against input, swept from nothing to a full-width swipe.",
        icon = Icons.Filled.Timeline,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(CURVE_HEIGHT_DP.dp),
        ) {
            drawResponseCurve(
                horizontal = horizontal,
                vertical = vertical,
                deadzone = draft.deadzonePercent / 100f,
                horizontalColour = horizontalColour,
                verticalColour = verticalColour,
                guide = guide,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendKey(label = "Horizontal", colour = horizontalColour)
            LegendKey(label = "Vertical", colour = verticalColour)
        }
        Spacer(modifier = Modifier.height(8.dp))
        KeyValueRow(
            label = "Full-swipe output",
            value = "${ratioLabel(peak)} pad widths",
            tone = Tone.Accent,
        )
        if (draft.deadzonePercent > 0) {
            KeyValueRow(
                label = "Ignored below",
                value = "${draft.deadzonePercent}% of a full swipe",
            )
        }
        if (draft.smoothingPercent > 0 && unsmoothedPeak > 0f) {
            KeyValueRow(
                label = "Smoothing holds it to",
                value = "${(peak / unsmoothedPeak * 100f).roundToInt()}% of the unsmoothed sweep",
                tone = Tone.Warning,
            )
        }
        RowDivider()

        SliderRow(
            title = "Response exponent",
            value = draft.exponentHundredths,
            range = SensitivityDraft.EXPONENT_RANGE,
            onValueChange = { onDraft(draft.copy(exponentHundredths = it)) },
            valueLabel = "^${SensitivityDraft.hundredthsLabel(draft.exponentHundredths)}",
            description = "1.00 is linear. Above it eases small movements for fine aim; below it sharpens " +
                "them. The sign of your input is always kept.",
        )
        SliderRow(
            title = "Deadzone",
            value = draft.deadzonePercent,
            range = SensitivityDraft.DEADZONE_RANGE,
            onValueChange = { onDraft(draft.copy(deadzonePercent = it)) },
            valueLabel = "${draft.deadzonePercent}%",
            description = "Movement smaller than this fraction of a full swipe produces exactly nothing. " +
                "Measured on the whole vector, so a diagonal nudge is not clipped to a cardinal one.",
        )
        SliderRow(
            title = "Smoothing",
            value = draft.smoothingPercent,
            range = SensitivityDraft.SMOOTHING_RANGE,
            onValueChange = { onDraft(draft.copy(smoothingPercent = it)) },
            valueLabel = "${draft.smoothingPercent}%",
            description = "How much of the previous output is kept. 0% is raw and immediate; at " +
                "${SensitivityDraft.MAX_SMOOTHING}% the previous value is kept in full and the camera " +
                "stops responding at all.",
        )
    }
}

// ---------------------------------------------------------------------------------------- the axes

/**
 * Per-axis scale and inversion — the last two steps of the pipeline, in the order the engine applies them.
 *
 * Inversion is deliberately last in [SensitivityMath], after every multiplication, which is why it is a
 * switch here rather than a negative scale: a negative multiplier would compose differently with the
 * response curve and would not be the same profile.
 */
@Composable
private fun AxisCard(
    state: SensitivityLabState,
    onDraft: (SensitivityDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft

    SectionCard(
        title = "Axes",
        modifier = modifier,
        subtitle = "Extra scale on one axis only, and which way each one points.",
        icon = Icons.Filled.GridView,
    ) {
        SliderRow(
            title = "Horizontal scale",
            value = draft.horizontalHundredths,
            range = SensitivityDraft.MULTIPLIER_RANGE,
            onValueChange = { onDraft(draft.copy(horizontalHundredths = it)) },
            valueLabel = "×${SensitivityDraft.hundredthsLabel(draft.horizontalHundredths)}",
            description = "Multiplies the X axis after the curve and before inversion.",
        )
        SliderRow(
            title = "Vertical scale",
            value = draft.verticalHundredths,
            range = SensitivityDraft.MULTIPLIER_RANGE,
            onValueChange = { onDraft(draft.copy(verticalHundredths = it)) },
            valueLabel = "×${SensitivityDraft.hundredthsLabel(draft.verticalHundredths)}",
            description = "The same for the Y axis. Lower than horizontal is the usual choice: vertical " +
                "aim needs less travel than horizontal.",
        )
        RowDivider()
        SwitchRow(
            title = "Invert X",
            checked = draft.invertX,
            onCheckedChange = { onDraft(draft.copy(invertX = it)) },
            description = "Drag left, look right. Flips the horizontal trail on the pad.",
        )
        SwitchRow(
            title = "Invert Y",
            checked = draft.invertY,
            onCheckedChange = { onDraft(draft.copy(invertY = it)) },
            description = "Drag up, look down — the flight-stick convention.",
        )
    }
}

// -------------------------------------------------------------------------------------- the actions

/**
 * Save, Duplicate, Delete.
 *
 * Duplicate is how most profiles after the first get made: a small variant of a curve that already works is
 * easier to judge than one built from defaults. Delete is last, separated, and only offered once there is a
 * stored row behind the draft.
 */
@Composable
private fun EditorActions(
    state: SensitivityLabState,
    onSave: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Actions", modifier = modifier, icon = Icons.Filled.Save) {
        ActionRow {
            Button(onClick = onSave, enabled = state.canSave) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (state.isNew) "Create" else "Save")
            }
            TextButton(onClick = onDuplicate, enabled = state.canDuplicate) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Duplicate")
            }
            Spacer(modifier = Modifier.weight(1f))
            if (state.canDelete) {
                TextButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = Tone.Danger.colour(),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Delete", color = Tone.Danger.colour())
                }
            }
        }
        val error = state.error
        if (error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            NoteBanner(text = error, tone = Tone.Danger, icon = Icons.Filled.Info)
        } else if (state.draft.name.isBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            NoteBanner(
                text = "Give the profile a name before saving it.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
        }
    }
}

// ------------------------------------------------------------------------------------ pad internals

/**
 * One reading off the live pad: what the pointer moved, and what the engine made of it.
 *
 * The deltas are per event and in full-scale look units, where 1.0 is one pad width. The travel pair is the
 * running total for the current drag, which is what makes the two trails comparable as numbers rather than
 * only as pictures.
 */
private data class PadSample(
    val active: Boolean = false,
    val rawX: Float = 0f,
    val rawY: Float = 0f,
    val outX: Float = 0f,
    val outY: Float = 0f,
    val fingerTravel: Float = 0f,
    val cameraTravel: Float = 0f,
) {
    /** How far the camera went for how far the finger went. Null until something has actually moved. */
    val gain: Float? get() = if (fingerTravel > 0f) cameraTravel / fingerTravel else null

    companion object {
        val IDLE = PadSample()
    }
}

/**
 * The two paths the pad draws, and the last reading off it.
 *
 * Snapshot state rather than plain lists, so appending a point invalidates the canvas's draw phase without
 * recomposing anything, and [sample] is separate so the one composable that shows numbers is the only one
 * that recomposes per event. [touched] flips once, the first time a finger lands, and exists so the "drag
 * here" hint can be shown without reading a value that changes on every frame.
 *
 * Trails are capped: a long drag on a 240 Hz digitiser would otherwise accumulate thousands of points that
 * are drawn every frame and say nothing the last few hundred do not.
 */
@Stable
private class PadTrace {
    val finger = mutableStateListOf<Offset>()
    val camera = mutableStateListOf<Offset>()

    var sample by mutableStateOf(PadSample.IDLE)
        private set

    var touched by mutableStateOf(false)
        private set

    fun begin(at: Offset) {
        finger.clear()
        camera.clear()
        finger += at
        camera += at
        sample = PadSample(active = true)
        touched = true
    }

    fun extend(fingerAt: Offset, cameraAt: Offset, reading: PadSample) {
        finger += fingerAt
        camera += cameraAt
        if (finger.size > MAX_TRAIL_POINTS) {
            finger.removeAt(0)
            camera.removeAt(0)
        }
        sample = reading
    }

    /** The drag ended. The figures stay on screen — they are what the drag measured — but stop being live. */
    fun end() {
        sample = sample.copy(active = false)
    }
}

/** Joins a path of pad pixels up. A single point is drawn as the dot it is rather than skipped. */
private fun DrawScope.drawTrail(points: List<Offset>, colour: Color) {
    if (points.size < 2) {
        points.firstOrNull()?.let { drawCircle(colour, radius = PAD_TRAIL_STROKE_PX, center = it) }
        return
    }
    for (index in 1 until points.size) {
        drawLine(
            color = colour,
            start = points[index - 1],
            end = points[index],
            strokeWidth = PAD_TRAIL_STROKE_PX,
            cap = StrokeCap.Round,
        )
    }
}

// ---------------------------------------------------------------------------------- curve internals

/**
 * Runs the engine over a ramp from no input to a full-width swipe and returns the output on one axis.
 *
 * One [SensitivityMath] for the whole sweep, deliberately: it is the stateful part of the engine and
 * rebuilding it per sample would draw a curve with the smoothing silently removed. The ramp is monotonic,
 * so the lag it exposes is smoothing's real behaviour rather than an artefact of the order of the samples.
 */
private fun responseSweep(
    profile: SensitivityProfile,
    aiming: Boolean,
    gyro: Boolean,
    vertical: Boolean,
): List<Float> {
    val math = SensitivityMath(profile)
    return List(CURVE_STEPS + 1) { step ->
        val input = step.toFloat() / CURVE_STEPS
        val out = if (vertical) {
            math.apply(0f, input, aiming = aiming, gyro = gyro)
        } else {
            math.apply(input, 0f, aiming = aiming, gyro = gyro)
        }
        if (vertical) out.y else out.x
    }
}

/**
 * Plots both axes' sweeps against a zero line, with the deadzone shaded.
 *
 * Zero sits in the middle of the box rather than at the bottom, because an inverted axis produces negative
 * output and a curve drawn off the bottom of its own plot would look like a profile that does nothing. Both
 * series share one scale, so a vertical axis set to half the horizontal one looks like half.
 */
private fun DrawScope.drawResponseCurve(
    horizontal: List<Float>,
    vertical: List<Float>,
    deadzone: Float,
    horizontalColour: Color,
    verticalColour: Color,
    guide: Color,
) {
    val inset = CURVE_INSET_PX
    val usableWidth = size.width - inset * 2f
    val usableHeight = size.height - inset * 2f
    if (horizontal.isEmpty() || usableWidth <= 0f || usableHeight <= 0f) return

    var peak = 0f
    horizontal.forEach { peak = max(peak, abs(it)) }
    vertical.forEach { peak = max(peak, abs(it)) }

    val zeroY = inset + usableHeight / 2f
    // A profile that outputs nothing at all — everything inside a full-width deadzone — is drawn as the
    // flat line it is rather than divided by zero into nothing.
    val scale = if (peak <= 0f) 0f else (usableHeight / 2f) / peak

    if (deadzone > 0f) {
        drawRect(
            color = guide.copy(alpha = 0.4f),
            topLeft = Offset(inset, inset),
            size = Size(usableWidth * deadzone.coerceIn(0f, 1f), usableHeight),
        )
    }
    drawLine(guide, Offset(inset, zeroY), Offset(inset + usableWidth, zeroY), 1f)
    drawLine(guide, Offset(inset, inset), Offset(inset, inset + usableHeight), 1f)

    drawSweep(vertical, verticalColour, inset, usableWidth, zeroY, scale)
    drawSweep(horizontal, horizontalColour, inset, usableWidth, zeroY, scale)
}

private fun DrawScope.drawSweep(
    values: List<Float>,
    colour: Color,
    inset: Float,
    usableWidth: Float,
    zeroY: Float,
    scale: Float,
) {
    val steps = (values.size - 1).coerceAtLeast(1)
    var previous: Offset? = null
    values.forEachIndexed { index, value ->
        val point = Offset(
            x = inset + usableWidth * index / steps,
            y = zeroY - value * scale,
        )
        val from = previous
        if (from != null) {
            drawLine(
                color = colour,
                start = from,
                end = point,
                strokeWidth = CURVE_STROKE_PX,
                cap = StrokeCap.Round,
            )
        }
        previous = point
    }
}

// --------------------------------------------------------------------------------------- formatting

/**
 * A full-scale delta as a signed percentage of a pad width, to one decimal.
 *
 * Integer maths rather than `String.format`: this runs on four figures per pointer event, and a locale that
 * writes a comma for the decimal point would make the readout disagree with every slider above it.
 */
private fun deltaLabel(value: Float): String {
    val tenths = (value * 1_000f).roundToInt()
    val magnitude = abs(tenths)
    return "${if (tenths < 0) "-" else ""}${magnitude / 10}.${magnitude % 10}%"
}

/** A ratio or a distance in pad widths, to two decimals. Same reasoning as [deltaLabel]. */
private fun ratioLabel(value: Float): String =
    SensitivityDraft.hundredthsLabel((value * 100f).roundToInt())

// ----------------------------------------------------------------------------------------- constants

/** Tall enough to drag a real swipe in, short enough to leave the sliders on the same screen. */
private const val PAD_HEIGHT_DP = 200

private const val PAD_DOT_PX = 5f
private const val PAD_TRAIL_STROKE_PX = 2.5f

/**
 * How many points a trail keeps.
 *
 * A long drag on a fast digitiser would otherwise grow without limit, and every point is redrawn on every
 * frame. Several seconds of movement at 240 Hz still fits.
 */
private const val MAX_TRAIL_POINTS = 512

private const val CURVE_HEIGHT_DP = 160
private const val CURVE_INSET_PX = 14f
private const val CURVE_STROKE_PX = 2.5f

/** Samples across the sweep. Enough for the exponent's bend to be smooth at card width. */
private const val CURVE_STEPS = 96
