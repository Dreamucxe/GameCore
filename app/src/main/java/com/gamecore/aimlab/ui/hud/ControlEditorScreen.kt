package com.gamecore.aimlab.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.ControlRole
import com.gamecore.aimlab.engine.ControlShape
import com.gamecore.aimlab.engine.ControlWidget
import com.gamecore.aimlab.engine.LayoutPreset
import com.gamecore.aimlab.engine.SafeArea
import com.gamecore.aimlab.ui.input.ControlOverlay
import com.gamecore.aimlab.ui.input.TouchMath
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NavRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SliderRow
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.TextFieldRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.components.screenAspectRatio

/**
 * §11/§12's control editor: the on-screen buttons a training run is played with, arranged by dragging them.
 *
 * The screen has two faces, the same shape [com.gamecore.aimlab.ui.practice.PracticeScreen] uses. The
 * **panel** is an ordinary list — the saved layouts, the presets, and the sliders for whichever control is
 * selected. The **arrange surface** is the whole screen, and it is the whole screen for a reason: the
 * controls are stored as fractions of the display, so the only preview that tells the truth about where a
 * button will end up is one the exact size and shape of the display it will be drawn on. A thumbnail in a
 * card cannot do that, which is why the one in the panel is a picture and not a drag target.
 *
 * Dragging is raw multi-touch — `awaitPointerEvent` in a loop with per-[PointerId] bookkeeping, the same
 * idiom [com.gamecore.aimlab.ui.input.TrainingSurface] uses and for the same reasons: this project has no
 * opt-in for `pointerInteropFilter`, and `detectDragGestures` owns the pointer stream one gesture at a
 * time. Here it buys something concrete: two thumbs can move two controls at once, which is exactly how
 * somebody holding the phone the way they play arranges a claw layout.
 *
 * Every drag, and every resize, goes back through the view model and out through the engine's own
 * [ControlWidget.clampTo], so a control cannot be left off the edge or under a cutout. The dashed
 * rectangle on the arrange surface is that boundary, drawn from the same [SafeArea] the clamp uses — the
 * user can see why a button stopped following their finger.
 *
 * Shoot and ADS are two separate controls with their own width and height, and the sliders write to one
 * role at a time, so sizing one never touches the other (§11).
 *
 * @param onBack pop back to the Aim Lab home screen.
 */
@Composable
fun ControlEditorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ControlEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The device's real usable region, re-measured whenever the window changes shape. The view model
    // clamps the open layout into it, so a rotation pulls a control out from under the new cutout rather
    // than leaving it there until the next time it is touched.
    val safeArea = deviceSafeArea()
    LaunchedEffect(safeArea) { viewModel.onSafeAreaChanged(safeArea) }

    val draft = state.draft
    if (state.isArranging && draft != null) {
        ArrangeSurface(
            layout = draft,
            safeArea = state.safeArea,
            selectedRole = state.selectedRole,
            draggingRole = state.draggingRole,
            onGrab = viewModel::onControlGrabbed,
            onMove = viewModel::onControlMoved,
            onRelease = viewModel::onControlReleased,
            onDone = viewModel::stopArranging,
            modifier = modifier,
        )
    } else {
        EditorPanel(
            state = state,
            onBack = onBack,
            onSelectLayout = viewModel::select,
            onNew = viewModel::newLayout,
            onPreset = viewModel::applyPreset,
            onName = viewModel::onNameChanged,
            onArrange = viewModel::startArranging,
            onRole = viewModel::onControlSelected,
            onWidth = viewModel::onWidthPercentChanged,
            onHeight = viewModel::onHeightPercentChanged,
            onOpacity = viewModel::onOpacityChanged,
            onShape = viewModel::onShapeSelected,
            onEnabled = viewModel::onEnabledChanged,
            onSave = viewModel::save,
            onDuplicate = viewModel::duplicate,
            onRequestDelete = viewModel::requestDelete,
            onDismissDelete = viewModel::dismissDelete,
            onConfirmDelete = viewModel::confirmDelete,
            modifier = modifier,
        )
    }
}

// --------------------------------------------------------------------------------------------- arrange

/**
 * The full-bleed arrangement surface: the screen, as the controls will really sit on it.
 *
 * The pointer handler tracks each finger by id. A finger that goes down on a control **grabs** it and
 * carries it until it lifts; a finger that goes down on empty space does nothing at all, so a stray palm
 * cannot teleport a button. The grab records the gap between the control's centre and the point the finger
 * landed on, and every move re-applies it — without that, grabbing the edge of the movement stick would
 * snap its centre under the thumb before the drag had started.
 *
 * Hit-testing is [TouchMath.controlAt], so the smallest overlapping control wins: a small reload button
 * sitting on top of a large stick is still the thing that gets picked up. That function only considers
 * enabled controls, which matches what is drawn — a control switched off is not on this surface at all,
 * and is edited from the panel's selector row instead.
 *
 * Nothing is clamped here. The surface reports where the finger is and the view model decides where the
 * control may be, which keeps one copy of §12's rule in one place.
 */
@Composable
private fun ArrangeSurface(
    layout: ControlLayout,
    safeArea: SafeArea,
    selectedRole: ControlRole?,
    draggingRole: ControlRole?,
    onGrab: (ControlRole) -> Unit,
    onMove: (ControlRole, Float, Float) -> Unit,
    onRelease: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    // The layout changes on every pointer move; capture the latest without re-arming the pointer filter,
    // which would drop any finger currently down mid-drag.
    val controls by rememberUpdatedState(layout.controls)
    val grab by rememberUpdatedState(onGrab)
    val move by rememberUpdatedState(onMove)
    val release by rememberUpdatedState(onRelease)
    val selected = layout.controls.firstOrNull { it.role == selectedRole }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background)
            .pointerInput(Unit) {
                val held = HashMap<PointerId, Grab>(4)

                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            when {
                                change.changedToDownIgnoreConsumed() -> {
                                    val arena = TouchMath.toArena(
                                        change.position.x,
                                        change.position.y,
                                        size.width,
                                        size.height,
                                    )
                                    val control = TouchMath.controlAt(arena, controls)
                                    if (control != null) {
                                        held[change.id] = Grab(
                                            role = control.role,
                                            offsetX = control.xFraction - arena.x,
                                            offsetY = control.yFraction - arena.y,
                                        )
                                        grab(control.role)
                                    }
                                }

                                change.changedToUpIgnoreConsumed() -> {
                                    if (held.remove(change.id) != null && held.isEmpty()) release()
                                }

                                change.pressed -> {
                                    val carried = held[change.id] ?: return@forEach
                                    val arena = TouchMath.toArena(
                                        change.position.x,
                                        change.position.y,
                                        size.width,
                                        size.height,
                                    )
                                    move(
                                        carried.role,
                                        arena.x + carried.offsetX,
                                        arena.y + carried.offsetY,
                                    )
                                }
                            }
                        }
                    }
                }
            },
    ) {
        // The boundary the clamp enforces, drawn under the controls so a button dragged into the corner
        // visibly stops at the line rather than appearing to be held back by nothing.
        val guide = scheme.outline
        Canvas(modifier = Modifier.fillMaxSize()) {
            val left = safeArea.left * size.width
            val top = safeArea.top * size.height
            val right = safeArea.right * size.width
            val bottom = safeArea.bottom * size.height
            drawRect(
                color = guide,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(
                    width = GUIDE_STROKE,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(GUIDE_DASH, GUIDE_DASH)),
                ),
            )
        }

        ControlOverlay(
            layout = layout,
            tint = scheme.primary,
            // The control under the finger is drawn brighter; with nothing held, the selected one is,
            // so the panel's sliders and this surface always agree about which button is being worked on.
            pressedRole = (draggingRole ?: selectedRole)?.name,
        )

        NoteBanner(
            text = "Drag a control to move it. It stops at the dashed line, which is this device's " +
                "usable area — the cutout and the system bars are outside it.",
            tone = Tone.Muted,
            icon = Icons.Filled.TouchApp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = ScreenPadding, vertical = 10.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(scheme.surface.copy(alpha = 0.82f))
                .padding(horizontal = ScreenPadding, vertical = 10.dp)
                // Clear of the floating navigation bar, which draws over this screen like every other.
                .padding(bottom = ScreenBottomPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null) {
                StatusChip(
                    text = "${selected.role.label} · ${ControlEditorState.percentOf(selected.xFraction)}%, " +
                        "${ControlEditorState.percentOf(selected.yFraction)}%",
                    tone = Tone.Accent,
                    icon = Icons.Filled.TouchApp,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onDone, modifier = Modifier.weight(1f)) {
                Icon(imageVector = Icons.Filled.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Done")
            }
        }
    }
}

/** One finger's hold on one control: which, and where on it the finger landed. */
private data class Grab(val role: ControlRole, val offsetX: Float, val offsetY: Float)

// ----------------------------------------------------------------------------------------------- panel

@Composable
private fun EditorPanel(
    state: ControlEditorState,
    onBack: () -> Unit,
    onSelectLayout: (Long) -> Unit,
    onNew: () -> Unit,
    onPreset: (LayoutPreset) -> Unit,
    onName: (String) -> Unit,
    onArrange: () -> Unit,
    onRole: (ControlRole) -> Unit,
    onWidth: (Int) -> Unit,
    onHeight: (Int) -> Unit,
    onOpacity: (Int) -> Unit,
    onShape: (ControlShape) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onSave: () -> Unit,
    onDuplicate: () -> Unit,
    onRequestDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val padded = Modifier.padding(horizontal = ScreenPadding)
    val draft = state.draft
    val error = state.error

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
                    title = "Control editor",
                    subtitle = "The on-screen buttons a training run is played with. Drag them where your " +
                        "thumbs actually sit.",
                    onBack = onBack,
                    action = {
                        IconButton(onClick = onNew) {
                            Icon(Icons.Filled.Add, contentDescription = "New layout")
                        }
                    },
                )
            }

            if (error != null) {
                item {
                    NoteBanner(
                        text = error,
                        tone = Tone.Danger,
                        icon = Icons.Filled.Info,
                        modifier = padded,
                    )
                }
            }

            item {
                LayoutListCard(
                    state = state,
                    onSelect = onSelectLayout,
                    onNew = onNew,
                    modifier = padded,
                )
            }

            if (draft != null) {
                item {
                    LayoutCard(
                        state = state,
                        layout = draft,
                        onName = onName,
                        onArrange = onArrange,
                        modifier = padded,
                    )
                }

                item {
                    ControlCard(
                        state = state,
                        onRole = onRole,
                        onWidth = onWidth,
                        onHeight = onHeight,
                        onOpacity = onOpacity,
                        onShape = onShape,
                        onEnabled = onEnabled,
                        modifier = padded,
                    )
                }
            }

            item {
                PresetCard(
                    hasLayout = draft != null,
                    onPreset = onPreset,
                    modifier = padded,
                )
            }

            if (draft != null) {
                item {
                    Box(modifier = padded) {
                        ActionRow {
                            Button(
                                onClick = onSave,
                                modifier = Modifier.weight(1f),
                                enabled = state.canSave,
                            ) {
                                Icon(imageVector = Icons.Filled.Save, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (state.isNew) "Save" else "Update")
                            }
                            if (state.canDuplicate) {
                                OutlinedButton(onClick = onDuplicate) {
                                    Icon(
                                        imageVector = Icons.Filled.ContentCopy,
                                        contentDescription = "Duplicate",
                                    )
                                }
                            }
                            if (state.canDelete) {
                                OutlinedButton(
                                    onClick = onRequestDelete,
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Tone.Danger.colour(),
                                    ),
                                ) {
                                    Icon(imageVector = Icons.Filled.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }

                if (state.dirty) {
                    item {
                        NoteBanner(
                            text = "This arrangement has not been saved. Leaving the editor keeps the " +
                                "stored layout as it was.",
                            tone = Tone.Warning,
                            icon = Icons.Filled.Info,
                            modifier = padded,
                        )
                    }
                }
            }

            item {
                NoteBanner(
                    text = "Positions and sizes are fractions of the screen, not pixels, so a layout " +
                        "arranged here works the same after a rotation and on another device.",
                    tone = Tone.Muted,
                    icon = Icons.Filled.AspectRatio,
                    modifier = padded,
                )
            }
        }

        if (state.confirmingDelete && draft != null) {
            ConfirmDialog(
                title = "Delete this layout?",
                message = "\"${draft.name}\" and the arrangement of its ${draft.controls.size} controls " +
                    "are removed. Sessions already recorded with it keep their results, but the layout " +
                    "cannot be brought back.",
                confirmLabel = "Delete it",
                onConfirm = onConfirmDelete,
                onDismiss = onDismissDelete,
                isDestructive = true,
            )
        }
    }
}

/** The stored layouts. Empty means the table really is empty, and the way out of that is a preset. */
@Composable
private fun LayoutListCard(
    state: ControlEditorState,
    onSelect: (Long) -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Saved layouts",
        subtitle = "Pick one to arrange, or start from a finger-count preset.",
        icon = Icons.Filled.Layers,
        modifier = modifier,
    ) {
        when {
            state.loading -> {
                // Nothing is drawn for the one frame before the flow answers: an empty state here would
                // be a claim about the database that has not been checked yet.
                Text(
                    text = "Loading your layouts…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.layouts.isEmpty() -> EmptyState(
                icon = Icons.Filled.GridView,
                title = "No layouts yet",
                message = "A layout is the set of buttons you play with: where they sit, how big they " +
                    "are, and which of them appear at all.",
                actionLabel = "Start from the 3-finger preset",
                onAction = onNew,
            )

            else -> state.layouts.forEachIndexed { index, layout ->
                if (index > 0) RowDivider()
                NavRow(
                    title = layout.name,
                    onClick = { onSelect(layout.id) },
                    description = state.subtitleFor(layout),
                    icon = Icons.Filled.GridView,
                    trailing = if (layout.id == state.editingId) "Open" else null,
                    trailingTone = Tone.Accent,
                )
            }
        }
    }
}

/**
 * The open layout: its name, a true-to-shape picture of it, and the way into the arrange surface.
 *
 * The preview is deliberately not draggable. It lives inside a `LazyColumn`, and a drag handler here would
 * be fighting the list's scroll for the same finger; it is also the wrong shape to be honest about, being
 * a thumbnail rather than the screen. Moving controls happens on the full-bleed surface, where both
 * problems disappear. The thumbnail keeps the device's aspect ratio so the arrangement it shows is at
 * least the arrangement that exists, just smaller.
 */
@Composable
private fun LayoutCard(
    state: ControlEditorState,
    layout: ControlLayout,
    onName: (String) -> Unit,
    onArrange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    SectionCard(
        title = state.editorTitle,
        subtitle = if (state.isNew) "Not saved yet." else "Saved layout.",
        icon = Icons.Filled.Tune,
        modifier = modifier,
    ) {
        TextFieldRow(
            label = "Name",
            value = layout.name,
            onValueChange = onName,
            placeholder = "Claw grip",
            maxLength = ControlLayout.MAX_NAME_LENGTH,
            description = "How this layout appears when you pick controls for a run.",
        )

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .height(PreviewHeight)
                    .aspectRatio(screenAspectRatio())
                    .clip(MaterialTheme.shapes.medium)
                    .background(scheme.surfaceVariant),
            ) {
                ControlOverlay(
                    layout = layout,
                    tint = scheme.primary,
                    pressedRole = state.selectedRole?.name,
                )
            }
        }

        RowDivider()
        KeyValueRow(label = "Controls shown", value = "${state.enabledCount} of ${layout.controls.size}")
        ActionRow {
            Button(onClick = onArrange, modifier = Modifier.weight(1f)) {
                Icon(imageVector = Icons.Filled.TouchApp, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Arrange on screen")
            }
        }
    }
}

/**
 * The selected control's own settings.
 *
 * The selector row lists every control in the layout, switched-off ones included — they are not drawn on
 * the preview and cannot be picked up there, so this row is the only way back to one.
 *
 * Width and height are separate sliders because they are separate fields. Shoot and ADS each have their
 * own pair, so making the fire button big and the aim button small is one control's change and nothing
 * else's (§11). Both sliders' ends are the engine's `MIN_SIZE`/`MAX_SIZE`, and opacity's floor is its
 * `MIN_OPACITY`, so nothing here can be set to a value `normalised()` would immediately take back.
 */
@Composable
private fun ControlCard(
    state: ControlEditorState,
    onRole: (ControlRole) -> Unit,
    onWidth: (Int) -> Unit,
    onHeight: (Int) -> Unit,
    onOpacity: (Int) -> Unit,
    onShape: (ControlShape) -> Unit,
    onEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val control = state.selected
    SectionCard(
        title = "Control",
        subtitle = control?.let { state.summaryFor(it) } ?: "Pick a control to change it.",
        icon = Icons.Filled.TouchApp,
        modifier = modifier,
    ) {
        ChoiceRow(
            options = state.roles,
            selected = state.selectedRole,
            onSelect = onRole,
            label = { it.label },
            perRow = 2,
        )

        if (control == null) return@SectionCard

        RowDivider()
        SwitchRow(
            title = "Show this control",
            checked = control.enabled,
            onCheckedChange = onEnabled,
            description = "A hidden control keeps its place and size — it is simply not drawn and not " +
                "pressable during a run.",
        )

        RowDivider()
        SliderRow(
            title = "Width",
            value = ControlEditorState.percentOf(control.widthFraction),
            range = ControlEditorState.SIZE_PERCENT,
            onValueChange = onWidth,
            valueLabel = "${ControlEditorState.percentOf(control.widthFraction)}%",
            description = "Across the screen. ${control.role.label} is sized on its own — the other " +
                "controls keep theirs.",
            enabled = control.enabled,
        )
        SliderRow(
            title = "Height",
            value = ControlEditorState.percentOf(control.heightFraction),
            range = ControlEditorState.SIZE_PERCENT,
            onValueChange = onHeight,
            valueLabel = "${ControlEditorState.percentOf(control.heightFraction)}%",
            description = "Down the screen.",
            enabled = control.enabled,
        )
        SliderRow(
            title = "Opacity",
            value = control.opacityPercent,
            range = ControlEditorState.OPACITY_PERCENT,
            onValueChange = onOpacity,
            valueLabel = "${control.opacityPercent}%",
            description = "The floor is ${ControlWidget.MIN_OPACITY}% so a control can always be found " +
                "again.",
            enabled = control.enabled,
        )

        RowDivider()
        ChoiceLabel("Shape")
        ChoiceRow(
            options = ControlEditorState.SHAPES,
            selected = control.shape,
            onSelect = onShape,
            label = { it.label },
            perRow = 3,
            enabled = control.enabled,
        )
        Text(
            text = "Shape is the outline only. A press is judged against the control's box either way, so " +
                "the corner of a round button is not dead.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The finger-count starting points of §12, and an honest line about what picking one costs. */
@Composable
private fun PresetCard(
    hasLayout: Boolean,
    onPreset: (LayoutPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Presets",
        subtitle = if (hasLayout) {
            "Replaces the arrangement above with this starting point. The layout keeps its name, and " +
                "nothing is written until you save."
        } else {
            "Starts a new layout from a thumb-friendly starting point."
        },
        icon = Icons.Filled.GridView,
        modifier = modifier,
    ) {
        ChoiceRow(
            options = ControlEditorState.PRESETS,
            // No preset is ever "current": the moment a control is dragged the layout is its own thing,
            // and highlighting one would claim the arrangement on screen is still that preset's.
            selected = null,
            onSelect = onPreset,
            label = { it.label },
            perRow = 2,
        )
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
 * This device's usable region, as the four fractions the engine clamps against.
 *
 * Measured from the window's safe-drawing insets — the status and navigation bars and the display cutout,
 * as the platform reports them for this window right now — rather than from a table of device models.
 * `MainActivity` runs edge to edge, so those insets are real numbers and not zeroes.
 *
 * Each inset is capped before it is turned into a bound. [SafeArea] rejects a degenerate rectangle by
 * throwing, and a window in a freeform or split-screen mode can report insets large enough to produce one;
 * capping means the worst a strange window can do is leave the editor a smaller area to work in.
 */
@Composable
private fun deviceSafeArea(): SafeArea {
    val configuration = LocalConfiguration.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val width = configuration.screenWidthDp.coerceAtLeast(1).toFloat()
    val height = configuration.screenHeightDp.coerceAtLeast(1).toFloat()
    return SafeArea(
        left = (insets.calculateLeftPadding(direction).value / width).coerceIn(0f, MAX_INSET_FRACTION),
        top = (insets.calculateTopPadding().value / height).coerceIn(0f, MAX_INSET_FRACTION),
        right = 1f - (insets.calculateRightPadding(direction).value / width)
            .coerceIn(0f, MAX_INSET_FRACTION),
        bottom = 1f - (insets.calculateBottomPadding().value / height).coerceIn(0f, MAX_INSET_FRACTION),
    )
}

/**
 * The tallest a single edge's inset may claim.
 *
 * A quarter of the screen is far more than any real cutout or navigation bar, and keeping both opposite
 * pairs under it guarantees `left < right` and `top < bottom`, which is what [SafeArea] requires.
 */
private const val MAX_INSET_FRACTION = 0.25f

/** How tall the panel's thumbnail is. Its width follows the device's aspect ratio, so it is not squashed. */
private val PreviewHeight = 240.dp

private const val GUIDE_STROKE = 2f
private const val GUIDE_DASH = 12f
