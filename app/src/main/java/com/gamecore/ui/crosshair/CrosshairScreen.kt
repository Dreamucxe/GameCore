package com.gamecore.ui.crosshair

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.model.CROSSHAIR_COLOURS
import com.gamecore.core.model.CrosshairDesign
import com.gamecore.core.model.CrosshairPreset
import com.gamecore.core.overlay.CrosshairOverlay
import com.gamecore.core.overlay.OverlayPalette
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.ColourPicker
import com.gamecore.ui.components.ColourSwatches
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NavRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SectionGap
import com.gamecore.ui.components.SliderRow
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.TextFieldRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.components.screenAspectRatio
import kotlin.math.roundToInt

/**
 * §9's crosshair: pick a design, adjust it against a preview shaped like this screen, put it on top.
 *
 * There is no save button. Every control writes through — a design or a switch as it is changed, a slider
 * when it is released — because a crosshair is judged by looking at it, and a user adjusting the size of
 * the crosshair currently over their game expects the thing on the glass to change.
 *
 * The preview is the real renderer. [CrosshairOverlay] is the same composable the overlay window hosts, in
 * a box shaped like this device's screen, so what is drawn here is what will be drawn there — including
 * the position, which is stored as a fraction and therefore means the same thing in both.
 */
@Composable
fun CrosshairScreen(
    onBack: () -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CrosshairViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var askingToDelete by remember { mutableStateOf(false) }

    // The picker hands back a URI GameCore can read for this call only, which is why the repository copies
    // and re-encodes it rather than storing the URI. No `ContentResolver` appears in this file (§25).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::importImage)
    }

    // "Display over other apps" is granted on a screen belonging to Settings, and Android gives no
    // callback for coming back from it.
    OnResume { viewModel.refreshPermission() }

    val padded = Modifier.padding(horizontal = ScreenPadding)
    val draft = state.draft

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Crosshair",
                subtitle = draft?.name ?: "A shape drawn over the game",
                onBack = onBack,
            )
        }
        state.message?.let { message ->
            item {
                NoteBanner(
                    text = message,
                    tone = Tone.Warning,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                    action = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
                )
            }
        }
        if (!state.isLoaded) {
            item { LoadingCard(modifier = padded) }
            return@LazyColumn
        }
        if (draft == null) {
            item {
                PlainCard(modifier = padded) {
                    EmptyState(
                        icon = Icons.Filled.Add,
                        title = "No crosshairs saved",
                        message = "Add one to start adjusting it. Nothing is drawn over your games until " +
                            "you switch it on.",
                        actionLabel = "Add a preset",
                        onAction = viewModel::create,
                    )
                }
            }
            return@LazyColumn
        }

        item {
            PreviewCard(
                state = state,
                preset = draft,
                onPosition = viewModel::setPosition,
                onCommit = viewModel::commit,
                onCentre = viewModel::centre,
                onShow = viewModel::show,
                onHide = viewModel::hide,
                onNavigate = onNavigate,
                modifier = padded,
            )
        }
        item {
            PresetsCard(
                state = state,
                onSelect = viewModel::select,
                onCreate = viewModel::create,
                modifier = padded,
            )
        }
        item {
            DesignCard(
                preset = draft,
                isImporting = state.isImporting,
                hasBrokenImage = state.hasBrokenImage,
                onDesign = viewModel::setDesign,
                onImport = { picker.launch("image/*") },
                onClearImage = viewModel::clearImage,
                modifier = padded,
            )
        }
        item {
            ShapeCard(
                preset = draft,
                isDrawn = state.isDrawnDesign,
                onSize = viewModel::setSize,
                onThickness = viewModel::setThickness,
                onGap = viewModel::setCentreGap,
                onRotation = viewModel::setRotation,
                onOpacity = viewModel::setOpacity,
                onCommit = viewModel::commit,
                modifier = padded,
            )
        }
        item {
            AppearanceCard(
                preset = draft,
                isDrawn = state.isDrawnDesign,
                customColours = state.customColours,
                onColour = viewModel::setColour,
                onCustomColour = viewModel::pickCustomColour,
                onShowDot = viewModel::setShowDot,
                onShowOutline = viewModel::setShowOutline,
                onName = viewModel::setName,
                onDelete = { askingToDelete = true },
                modifier = padded,
            )
        }
        item { SafetyCard(modifier = padded) }
    }

    if (askingToDelete && draft != null) {
        ConfirmDialog(
            title = "Delete ${draft.name}?",
            message = "The preset and any picture imported for it are removed, and any game profile that " +
                "used it will show no crosshair.",
            confirmLabel = "Delete",
            onConfirm = {
                askingToDelete = false
                viewModel.delete(draft.id)
            },
            onDismiss = { askingToDelete = false },
        )
    }
}

/**
 * The crosshair, drawn by the renderer that will draw it over the game, in a box shaped like the screen.
 *
 * Placement is a drag or a tap that lands the centre under the finger, rather than a pair of sliders: the
 * point of a crosshair is where it sits relative to what the eye is looking at, and the way to say that is
 * to point at it. The stored value is still a fraction, so the same preset is centred on any screen.
 *
 * The drag stores on release for the same reason the sliders do — an encrypted row rewritten on every
 * pointer event would be forty writes a second — and the tap stores immediately, because a tap has no end
 * to wait for.
 */
@Composable
private fun PreviewCard(
    state: CrosshairUiState,
    preset: CrosshairPreset,
    onPosition: (Float, Float) -> Unit,
    onCommit: () -> Unit,
    onCentre: () -> Unit,
    onShow: () -> Unit,
    onHide: () -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Preview",
        subtitle = "Drag or tap in the frame to place it",
        modifier = modifier,
        action = {
            StatusChip(
                text = if (state.isCrosshairVisible) "On screen" else "Off",
                tone = if (state.isCrosshairVisible) Tone.Good else Tone.Muted,
            )
        },
    ) {
        var canvas by remember { mutableStateOf(IntSize.Zero) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(screenAspectRatio())
                .clip(RoundedCornerShape(12.dp))
                .background(OverlayPalette.PanelPlate)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .onSizeChanged { canvas = it }
                .pointerInput(canvas) {
                    if (canvas.width <= 0 || canvas.height <= 0) return@pointerInput
                    detectTapGestures { offset ->
                        onPosition(offset.x / canvas.width, offset.y / canvas.height)
                        onCommit()
                    }
                }
                .pointerInput(canvas) {
                    if (canvas.width <= 0 || canvas.height <= 0) return@pointerInput
                    detectDragGestures(
                        onDragEnd = onCommit,
                        onDrag = { change, _ ->
                            onPosition(
                                change.position.x / canvas.width,
                                change.position.y / canvas.height,
                            )
                        },
                    )
                },
        ) {
            CentreGuides()
            CrosshairOverlay(preset = preset)
        }
        SectionGap(10)
        KeyValueRow(
            label = "Position",
            value = "x ${percent(preset.xFraction)} · y ${percent(preset.yFraction)}",
        )
        SectionGap(4)
        ActionRow {
            TextButton(onClick = onCentre) { Text("Centre it") }
            Spacer(modifier = Modifier.weight(1f))
            if (state.isCrosshairVisible) {
                TextButton(onClick = onHide, enabled = state.canToggleOverlay) { Text("Hide") }
            } else {
                Button(onClick = onShow, enabled = state.canToggleOverlay) { Text("Show it") }
            }
        }
        PreviewNote(state = state, onNavigate = onNavigate)
    }
}

/**
 * The one line under the preview that says why the crosshair is not on screen, or what it is doing there.
 *
 * Ordered by what stops the next tap from working: no permission means nothing can appear at all, a profile
 * in charge means the toggle is locked, and neither of those being true is the point at which it is worth
 * saying that edits are landing on the live window.
 */
@Composable
private fun PreviewNote(state: CrosshairUiState, onNavigate: (Destination) -> Unit) {
    SectionGap(6)
    when {
        !state.hasOverlayPermission -> NoteBanner(
            text = "GameCore cannot draw over other apps yet, so nothing will appear over a game.",
            tone = Tone.Warning,
            icon = Icons.Filled.Info,
            action = {
                TextButton(onClick = { onNavigate(Destination.Permissions) }) { Text("Grant") }
            },
        )

        state.isDrivenByProfile -> NoteBanner(
            text = "${state.drivingGameLabel.ifBlank { "A game" }}'s profile is in charge of the " +
                "overlays while it is running. This preset can still be adjusted.",
            tone = Tone.Accent,
            icon = Icons.Filled.Info,
        )

        state.isEditingLive -> NoteBanner(
            text = "This is the crosshair on screen. Changes appear over your game as you make them — " +
                "sliders when you let go of them.",
            tone = Tone.Accent,
            icon = Icons.Filled.Info,
        )

        else -> Unit
    }
}

/** Where dead centre is, so a placement can be judged against something. */
@Composable
private fun BoxScope.CentreGuides() {
    val colour = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxWidth()
            .height(1.dp)
            .background(colour),
    )
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxHeight()
            .width(1.dp)
            .background(colour),
    )
}

/**
 * Every saved preset, with the one being edited and the one on screen marked.
 *
 * Two different labels because they are two different things: "Editing" is what the controls below belong
 * to, "On screen" is what is over the game. They are usually the same preset and occasionally not — a
 * profile can put one crosshair up while the user adjusts another.
 */
@Composable
private fun PresetsCard(
    state: CrosshairUiState,
    onSelect: (Long) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Presets",
        subtitle = "${state.presets.size} saved",
        modifier = modifier,
        action = { TextButton(onClick = onCreate) { Text("Add") } },
    ) {
        state.presets.forEach { preset ->
            val isOnScreen = preset.id == state.activeId && state.isCrosshairVisible
            NavRow(
                title = preset.name,
                onClick = { onSelect(preset.id) },
                description = preset.design.label,
                trailing = when {
                    isOnScreen -> "On screen"
                    preset.id == state.draft?.id -> "Editing"
                    else -> null
                },
                trailingTone = if (isOnScreen) Tone.Good else Tone.Accent,
            )
        }
    }
}

/**
 * The shape, and the imported picture that can replace it.
 *
 * The picture is a separate block rather than a tenth chip in the list, because choosing it is not the same
 * kind of act as choosing a shape: it opens the system picker, it can fail, and what comes back is checked
 * before it is stored. The design only switches over once an image has actually decoded — a preset saying
 * "custom image" with nothing behind it draws nothing at all over the game.
 */
@Composable
private fun DesignCard(
    preset: CrosshairPreset,
    isImporting: Boolean,
    hasBrokenImage: Boolean,
    onDesign: (CrosshairDesign) -> Unit,
    onImport: () -> Unit,
    onClearImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Design", subtitle = preset.design.description, modifier = modifier) {
        ChoiceRow(
            options = DRAWN_DESIGNS,
            selected = preset.design.takeIf { it.isDrawn },
            onSelect = onDesign,
            label = { it.label },
            perRow = 2,
        )
        RowDivider()
        KeyValueRow(
            label = "Your own picture",
            value = if (preset.imagePath == null) "None imported" else "Imported",
            tone = if (preset.imagePath == null) Tone.Muted else Tone.Good,
        )
        Text(
            text = "A PNG or JPEG is decoded, shrunk to 512 px and re-saved by GameCore before it is " +
                "stored, so a file that is not really an image fails here rather than over your game.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionGap(6)
        ActionRow {
            if (isImporting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(text = "Reading it…", style = MaterialTheme.typography.bodySmall)
            } else {
                TextButton(onClick = onImport) {
                    Text(if (preset.imagePath == null) "Import a picture" else "Replace it")
                }
                if (preset.imagePath != null && preset.design.isDrawn) {
                    TextButton(onClick = { onDesign(CrosshairDesign.CUSTOM_IMAGE) }) { Text("Use it") }
                }
                if (preset.imagePath != null) {
                    TextButton(onClick = onClearImage) {
                        Text(text = "Remove", color = Tone.Danger.colour())
                    }
                }
            }
        }
        if (hasBrokenImage) {
            SectionGap(6)
            NoteBanner(
                text = "This preset is set to a custom picture but none is stored, so it would draw " +
                    "nothing. Import one, or pick a drawn design.",
                tone = Tone.Warning,
                icon = Icons.Filled.Info,
            )
        }
    }
}

/**
 * Size, thickness, gap, angle and opacity.
 *
 * The controls that an imported picture ignores are disabled rather than hidden, so switching to a drawn
 * design does not make four new sliders appear from nowhere — and a disabled slider showing its current
 * value is also the answer to "what would this be if I switched back".
 */
@Composable
private fun ShapeCard(
    preset: CrosshairPreset,
    isDrawn: Boolean,
    onSize: (Int) -> Unit,
    onThickness: (Int) -> Unit,
    onGap: (Int) -> Unit,
    onRotation: (Int) -> Unit,
    onOpacity: (Int) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Size and shape", modifier = modifier) {
        SliderRow(
            title = "Size",
            value = preset.sizeDp,
            range = CrosshairPreset.MIN_SIZE_DP..CrosshairPreset.MAX_SIZE_DP,
            onValueChange = onSize,
            valueLabel = "${preset.sizeDp} dp",
            onValueChangeFinished = onCommit,
        )
        SliderRow(
            title = "Line thickness",
            value = preset.thicknessDp,
            range = CrosshairPreset.MIN_THICKNESS_DP..CrosshairPreset.MAX_THICKNESS_DP,
            onValueChange = onThickness,
            valueLabel = "${preset.thicknessDp} dp",
            description = "Used by the drawn designs. An imported picture keeps its own lines.",
            enabled = isDrawn,
            onValueChangeFinished = onCommit,
        )
        SliderRow(
            title = "Centre gap",
            value = preset.centreGapDp,
            range = 0..CrosshairPreset.MAX_SIZE_DP / 2,
            onValueChange = onGap,
            valueLabel = "${preset.centreGapDp} dp",
            description = "Space left open in the middle, so the exact centre stays clear.",
            enabled = isDrawn,
            onValueChangeFinished = onCommit,
        )
        SliderRow(
            title = "Rotation",
            value = preset.rotationDegrees,
            range = 0..MAX_ROTATION_DEGREES,
            onValueChange = onRotation,
            valueLabel = "${preset.rotationDegrees}°",
            enabled = isDrawn,
            onValueChangeFinished = onCommit,
        )
        SliderRow(
            title = "Opacity",
            value = preset.opacityPercent,
            range = CrosshairPreset.MIN_OPACITY_PERCENT..100,
            onValueChange = onOpacity,
            valueLabel = "${preset.opacityPercent}%",
            description = "Never goes fully transparent: a crosshair nobody can see is one nobody can " +
                "find to switch off.",
            onValueChangeFinished = onCommit,
        )
    }
}

/**
 * Colour, the two detail switches, the name, and the way to delete the preset.
 *
 * The colour control is a row of swatches with a picker folded up behind it, rather than one or the other.
 * The swatches are what almost every choice actually is — eight colours chosen to read against a game, plus
 * whatever the user has mixed before — and a picker as the only route would put an HSV square between a
 * player and "make it green". The picker is there because eight is not every colour and a team's shade is
 * not negotiable.
 *
 * Folded up rather than in a dialog, unlike everything else on this screen that reveals itself: a dialog
 * would cover the live preview, and the preview is how the user knows whether the colour works. That is
 * also why it is not a separate screen. [com.gamecore.ui.components.ConfirmDialog] stays the one dialog in
 * the app.
 */
@Composable
private fun AppearanceCard(
    preset: CrosshairPreset,
    isDrawn: Boolean,
    customColours: List<Int>,
    onColour: (Int) -> Unit,
    onCustomColour: (Int) -> Unit,
    onShowDot: (Boolean) -> Unit,
    onShowOutline: (Boolean) -> Unit,
    onName: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Transient, so it is held here rather than in the ViewModel: whether a picker is unfolded is not a
    // fact about the crosshair, and a user who leaves the screen and comes back has not asked to be put
    // back inside an HSV square.
    var pickerOpen by remember { mutableStateOf(false) }

    SectionCard(title = "Colour and detail", modifier = modifier) {
        // One row of both lists rather than two rows, because they are the same kind of thing — a colour
        // one tap away — and a user who mixed a colour once wants it beside the built-in eight, not in a
        // second row labelled "yours". The built-ins come first so their positions never move.
        ColourSwatches(
            colours = (CROSSHAIR_COLOURS + customColours).map { Color(it) },
            selected = Color(preset.colorArgb),
            onSelect = { onColour(it.toArgb()) },
            perRow = 4,
        )
        SectionGap(4)
        ActionRow {
            TextButton(onClick = { pickerOpen = !pickerOpen }) {
                Text(if (pickerOpen) "Fewer colours" else "More colours")
            }
        }
        if (pickerOpen) {
            ColourPicker(
                initialArgb = preset.colorArgb,
                // Folds itself away on the way out, so the preview the colour was chosen against is
                // uncovered at the moment there is something to look at.
                onPick = { argb ->
                    onCustomColour(argb)
                    pickerOpen = false
                },
            )
        }
        if (!isDrawn) {
            SectionGap(8)
            NoteBanner(
                text = "An imported picture is drawn with its own colours. The colour and the two " +
                    "switches below apply to the drawn designs.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
        }
        SectionGap(6)
        SwitchRow(
            title = "Centre dot",
            checked = preset.showDot,
            onCheckedChange = onShowDot,
            description = "A filled point at the exact centre, for the ring and bracket designs.",
            enabled = isDrawn,
        )
        SwitchRow(
            title = "Dark outline",
            checked = preset.showOutline,
            onCheckedChange = onShowOutline,
            description = "Drawn a little wider in black underneath, so a thin crosshair does not " +
                "disappear against a bright scene.",
            enabled = isDrawn,
        )
        RowDivider()
        TextFieldRow(
            label = "Name",
            value = preset.name,
            onValueChange = onName,
            placeholder = "Crosshair",
            maxLength = CrosshairPreset.MAX_NAME_LENGTH,
        )
        RowDivider()
        TextButton(onClick = onDelete) {
            Text(text = "Delete this preset", color = Tone.Danger.colour())
        }
    }
}

/**
 * What the crosshair is, in the app rather than only in the source.
 *
 * §24 draws a line between an overlay and an aim assist, and this is the screen where a user might wonder
 * which side of it they are on. The answer belongs where they can read it: a shape in GameCore's own
 * window, which cannot see the game and cannot be touched.
 */
@Composable
private fun SafetyCard(modifier: Modifier = Modifier) {
    PlainCard(modifier = modifier) {
        Text(text = "What this actually is", style = MaterialTheme.typography.titleSmall)
        SectionGap(6)
        Text(
            text = "A shape drawn in GameCore's own window, on top of whatever is underneath. It does not " +
                "read the game, does not know where the game's own sight is, does not move on its own, " +
                "and cannot be tapped — touches pass straight through to the app below. It is a sticker " +
                "on the glass.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadingCard(modifier: Modifier = Modifier) {
    PlainCard(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
    }
}

/**
 * The designs the renderer draws, which is every one except the imported picture.
 *
 * Filtered here rather than in the model: the enum is the renderer's list and the picture belongs in it,
 * but a chip the user cannot meaningfully tap — it needs a file first — does not belong in a chip row.
 */
private val DRAWN_DESIGNS: List<CrosshairDesign> = CrosshairDesign.entries.filter { it.isDrawn }

/** 359 rather than 360, which is the same angle as 0 and would make the slider's top end a no-op. */
private const val MAX_ROTATION_DEGREES = 359

private fun percent(fraction: Float): String = "${(fraction * 100).roundToInt()}%"
