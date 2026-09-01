package com.gamecore.ui.hud

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.model.HudLayout
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.HudWidget
import com.gamecore.core.overlay.HudWidgetView
import com.gamecore.core.overlay.OverlayPalette
import com.gamecore.domain.monitoring.StatReading
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.ColourSwatches
import com.gamecore.ui.components.ConfirmDialog
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
import kotlin.math.roundToInt

/**
 * The visual HUD builder of §9: drag a stat where you want it, and see what it will actually say.
 *
 * The preview is not a mock-up. It draws each widget with [HudWidgetView] — the same function the overlay
 * service uses over the game — fed by real readings from the shared sampler, placed by the same
 * fraction-to-pixel arithmetic as [com.gamecore.core.overlay.HudOverlay]. So a stat this device does not
 * report shows "n/a" here, at the moment the user adds it, rather than after they have saved a layout
 * built around a number that will never arrive. That is §24B's FPS-honesty rule applied at the point of
 * configuration, which is the only place it can prevent disappointment rather than merely explain it.
 *
 * Nothing is written until Save. The layout being edited may be the one a running game is drawing right
 * now, so a builder that persisted each drag would be editing a live overlay with no way to cancel.
 */
@Composable
fun HudEditorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HudEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Cold flow: this collection is what starts the sampling loop, and leaving the screen stops it (§26).
    val readings by viewModel.readings.collectAsStateWithLifecycle(emptyMap())
    var askingToDiscard by remember { mutableStateOf(false) }

    val leave: () -> Unit = {
        if (state.isDirty && state.confirmOnDiscard) askingToDiscard = true else onBack()
    }

    LaunchedEffect(state.isFinished) { if (state.isFinished) onBack() }

    BackHandler(enabled = state.isDirty) { leave() }

    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = if (state.isNew) "Build a HUD" else "Edit layout",
                subtitle = "${state.widgets.size} of ${HudLayout.MAX_WIDGETS} stats placed",
                onBack = leave,
                action = {
                    TextButton(onClick = viewModel::save, enabled = state.canSave && state.isDirty) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Save")
                        }
                    }
                },
            )
        }

        if (state.message != null) {
            item {
                NoteBanner(
                    text = state.message.orEmpty(),
                    tone = Tone.Accent,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                    action = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
                )
            }
        }

        if (!state.isLoaded) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            }
            return@LazyColumn
        }

        item {
            PreviewCard(
                state = state,
                readings = readings,
                onMove = viewModel::move,
                onSelect = viewModel::select,
                modifier = padded,
            )
        }

        item {
            SectionCard(title = "Name", icon = Icons.Filled.Tune, modifier = padded) {
                TextFieldRow(
                    label = "What to call this layout",
                    value = state.name,
                    onValueChange = viewModel::setName,
                    placeholder = "My layout",
                    maxLength = HudLayout.MAX_NAME_LENGTH,
                    description = "Shown in the HUD list and when a game's profile picks a layout.",
                )
            }
        }

        item {
            SelectedWidgetCard(
                widget = state.selected,
                reading = state.selected?.let { readings[it.stat] },
                onTextSize = viewModel::setTextSize,
                onOpacity = viewModel::setOpacity,
                onShowLabel = viewModel::setShowLabel,
                onShowBackground = viewModel::setShowBackground,
                onColour = viewModel::setColour,
                onRemove = viewModel::remove,
                modifier = padded,
            )
        }

        item {
            AddStatCard(
                state = state,
                onAdd = viewModel::addStat,
                modifier = padded,
            )
        }
    }

    if (askingToDiscard) {
        ConfirmDialog(
            title = "Discard these changes?",
            message = "This layout has edits that have not been saved. Leaving now forgets them.",
            confirmLabel = "Discard",
            onConfirm = {
                askingToDiscard = false
                onBack()
            },
            onDismiss = { askingToDiscard = false },
        )
    }
}

/**
 * The preview, plus what it has learned about this device.
 *
 * The note under it lists the stats on this draft that have a reading of *nothing* — not the ones still
 * waiting for the sampler's first tick, which look identical on screen and mean the opposite. A builder
 * that announced "no reading for CPU on this device" for the first second of every visit would train the
 * user to ignore the one message on this screen that is worth reading.
 */
@Composable
private fun PreviewCard(
    state: HudEditorUiState,
    readings: Map<HudStat, StatReading>,
    onMove: (String, Float, Float) -> Unit,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val absent = state.widgets
        .map { it.stat }
        .distinct()
        .filter { stat ->
            val reading = readings[stat]
            reading != null && !reading.isAvailable && !reading.isAwaitingFirstSample
        }

    SectionCard(
        title = "Preview",
        subtitle = "Live readings, in the shape of this screen",
        icon = Icons.Filled.Visibility,
        modifier = modifier,
    ) {
        LayoutPreview(state = state, readings = readings, onMove = onMove, onSelect = onSelect)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Drag a stat to move it. Tap one to change how it looks.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (absent.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            NoteBanner(
                text = "This device reports no ${absent.joinToString { it.label.lowercase() }}. " +
                    "Those will read \"${StatReading.PLACEHOLDER}\" over the game rather than a number.",
                tone = Tone.Warning,
                icon = Icons.Filled.Info,
            )
        }
    }
}

/**
 * This screen, in miniature, with the draft on it.
 *
 * Shaped by [screenAspectRatio] rather than a fixed 9:16, and placed by the same fraction × measured-size
 * arithmetic as [com.gamecore.core.overlay.HudOverlay] — clamp included, so a stat at `x = 0.98` sits
 * against the right edge here exactly as it will over the game. Getting that wrong would be worse in the
 * builder than in the renderer: the user would drag a widget to where they wanted it and the game would
 * put it somewhere else.
 *
 * The container's pixel size is captured through [onSizeChanged] because drags arrive in pixels and
 * positions are stored as fractions; the conversion needs the size the fractions are relative to.
 */
@Composable
private fun LayoutPreview(
    state: HudEditorUiState,
    readings: Map<HudStat, StatReading>,
    onMove: (String, Float, Float) -> Unit,
    onSelect: (String?) -> Unit,
) {
    var canvas by remember { mutableStateOf(IntSize.Zero) }
    val widgets = state.widgets

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(screenAspectRatio())
            .clip(RoundedCornerShape(12.dp))
            .background(OverlayPalette.PanelPlate)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .onSizeChanged { canvas = it }
            // Tapping the backdrop deselects. The controls below belong to one widget, and there has to
            // be a way to put them away that is not deleting something.
            .pointerInput(Unit) { detectTapGestures { onSelect(null) } },
    ) {
        if (widgets.isEmpty()) {
            Text(
                text = "Nothing on this layout yet.\nAdd a stat below.",
                style = MaterialTheme.typography.bodyMedium,
                color = OverlayPalette.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            return@Box
        }
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                widgets.forEach { widget ->
                    DraggableWidget(
                        widget = widget,
                        reading = readings[widget.stat] ?: StatReading(widget.stat, value = null),
                        isSelected = widget.id == state.selectedId,
                        canvas = canvas,
                        onSelect = { onSelect(widget.id) },
                        onMove = { x, y -> onMove(widget.id, x, y) },
                    )
                }
            },
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val placeables = measurables.map { it.measure(loose) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { index, placeable ->
                    val widget = widgets[index]
                    val maxX = (constraints.maxWidth - placeable.width).coerceAtLeast(0)
                    val maxY = (constraints.maxHeight - placeable.height).coerceAtLeast(0)
                    placeable.place(
                        x = (widget.xFraction * constraints.maxWidth).roundToInt().coerceIn(0, maxX),
                        y = (widget.yFraction * constraints.maxHeight).roundToInt().coerceIn(0, maxY),
                    )
                }
            }
        }
    }
}

/**
 * One widget in the preview: the real renderer, wrapped in the gestures the builder needs.
 *
 * [HudWidgetView] is used unchanged and nothing is added to its size — the selection ring is a `border`,
 * which draws inside the bounds rather than growing them — so placement here stays pixel-identical to the
 * overlay's. A roomier hit area would have been easier to grab and would have moved every widget a few dp
 * from where it is actually going to appear.
 *
 * The drag reads the widget through [rememberUpdatedState] rather than from the captured parameter.
 * `pointerInput` keeps its block alive for the whole gesture, so a block closing over `widget` would add
 * every delta to the position the widget had when the finger went down: a drag that lagged behind the
 * finger and stopped a fraction of the way to where it was pointed.
 */
@Composable
private fun DraggableWidget(
    widget: HudWidget,
    reading: StatReading,
    isSelected: Boolean,
    canvas: IntSize,
    onSelect: () -> Unit,
    onMove: (Float, Float) -> Unit,
) {
    val live = rememberUpdatedState(widget)
    val ring = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        modifier = Modifier
            .border(1.dp, ring, RoundedCornerShape(6.dp))
            .pointerInput(widget.id, canvas) {
                // Before the first measurement there is nothing to divide by, and a drag then would
                // send the widget to an undefined fraction.
                if (canvas.width <= 0 || canvas.height <= 0) return@pointerInput
                detectDragGestures(
                    onDragStart = { onSelect() },
                    onDrag = { _, delta ->
                        val current = live.value
                        onMove(
                            current.xFraction + delta.x / canvas.width,
                            current.yFraction + delta.y / canvas.height,
                        )
                    },
                )
            }
            .pointerInput(widget.id) { detectTapGestures { onSelect() } },
    ) {
        HudWidgetView(widget = widget, reading = reading)
    }
}

/**
 * How the selected widget looks, or the sentence explaining why this card is empty.
 *
 * The card is present with nothing selected rather than absent, so the screen does not change height the
 * first time a widget is tapped and the controls do not appear somewhere the user was not looking.
 *
 * The trailing chip is the widget's current reading. It is the same value the preview draws, repeated here
 * because a user adjusting a stat's colour and size is looking at this card, and "n/a" beside the title is
 * where they will notice that this device has nothing to put in it.
 */
@Composable
private fun SelectedWidgetCard(
    widget: HudWidget?,
    reading: StatReading?,
    onTextSize: (String, Int) -> Unit,
    onOpacity: (String, Int) -> Unit,
    onShowLabel: (String, Boolean) -> Unit,
    onShowBackground: (String, Boolean) -> Unit,
    onColour: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (widget == null) {
        SectionCard(title = "Appearance", icon = Icons.Filled.Tune, modifier = modifier) {
            Text(
                text = "Tap a stat in the preview to change its size, opacity, colour and label.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val id = widget.id
    val hasLabel = widget.stat.shortLabel.isNotEmpty()

    SectionCard(
        title = widget.stat.label,
        subtitle = "x ${percent(widget.xFraction)} · y ${percent(widget.yFraction)}",
        icon = Icons.Filled.Tune,
        modifier = modifier,
        action = {
            StatusChip(
                text = reading?.display() ?: StatReading.PLACEHOLDER,
                tone = if (reading?.isAvailable == true) Tone.Good else Tone.Muted,
            )
        },
    ) {
        SliderRow(
            title = "Text size",
            value = widget.textSizeSp,
            range = HudWidget.MIN_TEXT_SIZE_SP..HudWidget.MAX_TEXT_SIZE_SP,
            onValueChange = { onTextSize(id, it) },
            valueLabel = "${widget.textSizeSp} sp",
        )
        SliderRow(
            title = "Opacity",
            value = widget.opacityPercent,
            range = HudWidget.MIN_OPACITY_PERCENT..100,
            onValueChange = { onOpacity(id, it) },
            valueLabel = "${widget.opacityPercent}%",
            description = "Stops short of invisible: a widget faded to nothing cannot be found again.",
        )
        SwitchRow(
            title = "Show the label",
            checked = widget.showLabel && hasLabel,
            onCheckedChange = { onShowLabel(id, it) },
            enabled = hasLabel,
            description = if (hasLabel) {
                "Draws \"${widget.stat.shortLabel}\" in front of the figure."
            } else {
                "This stat has no short label, so there is nothing to draw in front of it."
            },
        )
        SwitchRow(
            title = "Draw a plate behind it",
            checked = widget.showBackground,
            onCheckedChange = { onShowBackground(id, it) },
            description = "Keeps the figure readable over a bright scene.",
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = "Colour", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))
        ColourSwatches(
            colours = WIDGET_COLOURS.map { Color(it) },
            selected = Color(widget.colorArgb),
            onSelect = { onColour(id, it.toArgb()) },
        )
        val note = widget.stat.conditionNote()
        if (note != null) {
            Spacer(modifier = Modifier.height(10.dp))
            NoteBanner(text = note, tone = Tone.Muted, icon = Icons.Filled.Info)
        }
        RowDivider()
        ActionRow {
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { onRemove(id) }) {
                Text(text = "Remove from layout", color = Tone.Danger.colour())
            }
        }
    }
}

/**
 * The stats not yet on this layout.
 *
 * One widget per stat, so a chip leaves the list once it is placed: two CPU readouts on one HUD is a
 * mistake rather than a configuration, and what is left is also the answer to "what have I already got".
 *
 * Conditional stats are marked, not hidden and not greyed out. Whether this device publishes a CPU
 * temperature is not knowable until it has been asked, the preview answers it within one tick of being
 * added, and a list that left them out would stop a user who *does* have the sensor from using it.
 */
@Composable
private fun AddStatCard(
    state: HudEditorUiState,
    onAdd: (HudStat) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Add a stat",
        subtitle = "${state.widgets.size} of ${HudLayout.MAX_WIDGETS} placed",
        icon = Icons.Filled.Add,
        modifier = modifier,
    ) {
        if (state.isFull) {
            NoteBanner(
                text = "This layout is holding all ${HudLayout.MAX_WIDGETS} stats it can. " +
                    "Remove one to add another.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
            return@SectionCard
        }
        if (state.addableStats.isEmpty()) {
            NoteBanner(
                text = "Every stat GameCore can draw is already on this layout.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
            return@SectionCard
        }
        ChoiceRow(
            options = state.addableStats,
            selected = null,
            onSelect = onAdd,
            label = { if (it.isAlwaysAvailable) it.label else "${it.label} •" },
            perRow = 2,
        )
        if (state.addableStats.any { !it.isAlwaysAvailable }) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "• needs a device, a permission or a network that may not be there. Add one and " +
                    "the preview shows what it actually reads on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun percent(fraction: Float): String = "${(fraction * 100).roundToInt()}%"
