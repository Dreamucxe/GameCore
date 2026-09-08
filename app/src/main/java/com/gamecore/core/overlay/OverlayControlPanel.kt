package com.gamecore.core.overlay

import android.view.MotionEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenLockRotation
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamecore.core.common.Formatters
import com.gamecore.core.model.AspectChoice
import com.gamecore.core.model.CrosshairDesign
import com.gamecore.domain.monitoring.StatReading
import kotlin.math.roundToInt

/**
 * What the floating button expands into: §7's control panel and §16's gaming tools, over the game.
 *
 * The panel is the one overlay window besides the button that takes touches, and it is the only one that
 * closes itself. `FLAG_WATCH_OUTSIDE_TOUCH` on its window delivers `ACTION_OUTSIDE` here when the user
 * taps the game behind it, which is handled as a dismissal — without that the panel would stay open over
 * whatever the player was doing until they found the button again, and a modal overlay over a live game
 * is the worst kind of dead zone.
 *
 * Every action is drawn from [OverlayAction], so an action the service knows about and the panel forgot
 * cannot exist. An action that is [OverlayPanelState.isUsable] false renders greyed *with the reason
 * under it* rather than silently doing nothing when tapped — flashlight on a device with no flash unit,
 * Do Not Disturb without notification-policy access, recording before consent. That is §24's "say it is
 * not supported" applied to the surface where the user is most likely to try. The two [OverlayLevel]
 * sliders follow the same rule.
 *
 * [maxHeightDp] is the space the service found between the floating button and the edge of the screen.
 * The panel opens directly against the button, so how much room it has depends on where the user parked
 * it; capping and scrolling here means a button dragged near the bottom of a short screen gets a panel
 * that is shorter, not one whose last row of buttons is off-screen.
 *
 * [widthDp] is the user's own choice, from the settings screen or from the grip in the corner below, and
 * it arrives as a plain value rather than being held here: the service owns the in-flight width during a
 * resize so that the window and the plate inside it change size in the same frame. A grip that moved the
 * plate and left the window behind would show a panel clipped by its own window for as long as the drag
 * lasted.
 *
 * This is the centered layout, and one of two. [OverlaySplitPanel] is the other, and it reuses the
 * content composables below rather than reimplementing them — which is why several of them are `internal`
 * and not `private`. What it does not reuse is anything on this function: the plate, the height cap, the
 * grip and the `ACTION_OUTSIDE` handling are all specific to a window that has an outside and a width.
 * [com.gamecore.core.model.PanelLayoutStyle.CENTERED] is the default and stays the default.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OverlayControlPanel(
    state: OverlayPanelState,
    readings: List<StatReading>,
    accent: Color,
    maxHeightDp: Int,
    widthDp: Int,
    onAction: (OverlayAction) -> Unit,
    onLongAction: (OverlayAction) -> Unit,
    onPreset: (Long) -> Unit,
    onAspect: (AspectChoice) -> Unit,
    onRate: (Float?) -> Unit,
    onCrosshairDesign: (CrosshairDesign) -> Unit,
    onCrosshairColour: (Int) -> Unit,
    onDragLevel: (OverlayLevel, Int) -> Unit,
    onCommitLevel: (OverlayLevel) -> Unit,
    onResize: (Int) -> Unit,
    onResizeFinished: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which readout was tapped, and what has been typed into it. Local, unlike the levels themselves:
    // a half-typed number is not a fact about the device, nothing outside this composition can act on
    // it, and it is meant to be forgotten when the panel closes. The value only becomes the service's
    // business when the user presses OK, which goes out through the same two callbacks a drag does.
    var editing by remember { mutableStateOf<OverlayLevel?>(null) }
    var typed by remember { mutableStateOf("") }

    // Two columns rather than one, and the split is what makes the grip reachable. The content scrolls;
    // the grip must not, or a panel long enough to need scrolling would keep its resize handle below the
    // fold. So the outer column carries the plate, the width and the height cap, and the inner one is the
    // part that scrolls inside them.
    Column(
        modifier = modifier
            .width(widthDp.dp)
            .heightIn(max = maxHeightDp.coerceAtLeast(MIN_PANEL_HEIGHT_DP).dp)
            .background(OverlayPalette.PanelPlate, RoundedCornerShape(18.dp))
            .pointerInteropFilter { event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    onDismiss()
                    true
                } else {
                    // Touches inside the panel belong to the buttons. Returning false lets Compose's
                    // own gesture handling see them, which is what makes `clickable` work at all.
                    false
                }
            },
    ) {
        Column(
            modifier = Modifier
                // `fill = false` so a panel with little in it stays short instead of stretching to the
                // cap and leaving the grip stranded at the bottom of an empty plate.
                .weight(1f, fill = false)
                // Inside the plate and outside the padding, so the padding scrolls with the content: a
                // panel that has been cut short still ends in a margin rather than in a clipped row.
                .verticalScroll(rememberScrollState())
                .padding(PANEL_PADDING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PanelHeader(state = state, accent = accent)
            if (readings.isNotEmpty()) {
                PanelStats(readings = readings)
            }
            PanelLevels(
                state = state,
                accent = accent,
                editing = editing,
                onDragLevel = onDragLevel,
                onCommitLevel = onCommitLevel,
                onEditLevel = { level ->
                    // A second tap on the same readout closes the pad. Otherwise the only way out of it
                    // would be Cancel, and the readout that opened it would look inert.
                    editing = if (editing == level) null else level
                    typed = ""
                },
            )
            editing?.let { level ->
                LevelKeypad(
                    level = level,
                    typed = typed,
                    accent = accent,
                    onType = { typed = it },
                    onCancel = {
                        editing = null
                        typed = ""
                    },
                    onConfirm = { value ->
                        onDragLevel(level, value)
                        onCommitLevel(level)
                        editing = null
                        typed = ""
                    },
                )
            }
            PanelActions(
                state = state,
                accent = accent,
                widthDp = widthDp,
                onAction = onAction,
                onLongAction = onLongAction,
            )
            if (state.presetsExpanded) {
                PresetChips(state = state, accent = accent, widthDp = widthDp, onPreset = onPreset)
            }
            if (state.aspectsExpanded) {
                AspectChips(state = state, accent = accent, widthDp = widthDp, onAspect = onAspect)
            }
            if (state.refreshRatesExpanded) {
                RateChips(state = state, accent = accent, widthDp = widthDp, onRate = onRate)
            }
            if (state.crosshairExpanded) {
                CrosshairChips(
                    state = state,
                    accent = accent,
                    widthDp = widthDp,
                    onCrosshairDesign = onCrosshairDesign,
                    onCrosshairColour = onCrosshairColour,
                )
            }
        }
        PanelResizeGrip(
            widthDp = widthDp,
            accent = accent,
            onResize = onResize,
            onResizeFinished = onResizeFinished,
        )
    }
}

/**
 * The action grid, reflowed to whatever width the panel has.
 *
 * Chunked by hand rather than with `FlowRow`, which is still experimental, and by [actionsPerRow] rather
 * than by a constant, which is the whole of the difference a resizable panel makes here: the tiles share
 * the row through `weight` instead of each taking a fixed width, so a narrow panel drops to two across
 * and a wide one spreads to seven without a tile ever being clipped by the plate's edge.
 *
 * The short final row is padded with spacers for the same reason
 * [com.gamecore.ui.components.ChoiceRow] pads its own: three actions left over in a four-wide grid
 * should be three tiles the size of the ones above them, not three stretched to fill the row.
 */
@Composable
internal fun PanelActions(
    state: OverlayPanelState,
    accent: Color,
    widthDp: Int,
    onAction: (OverlayAction) -> Unit,
    onLongAction: (OverlayAction) -> Unit,
) {
    val perRow = actionsPerRow(widthDp)
    Column(verticalArrangement = Arrangement.spacedBy(ACTION_GAP_DP.dp)) {
        OverlayAction.entries.chunked(perRow).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ACTION_GAP_DP.dp),
            ) {
                row.forEach { action ->
                    ActionButton(
                        action = action,
                        state = state,
                        accent = accent,
                        onAction = onAction,
                        onLongAction = onLongAction,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(perRow - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * The handle in the bottom-right corner that sets how wide the panel is.
 *
 * Inside the panel's own window rather than in a second overlay: a resize handle in a window of its own
 * would need its own position kept in step with this one on every frame of the drag, and the two would
 * come apart the moment one update was dropped.
 *
 * The gesture reports an absolute width rather than a delta, and it reports it against the width the
 * panel is *currently* drawn at, which is the width the service has already clamped. That is what stops
 * a drag past either end of the range from building up travel that has to be dragged back through before
 * anything moves — push against the maximum for half a second and the panel starts narrowing the instant
 * the finger turns around. The fractional dp left over from each event is carried rather than rounded
 * away, so a slow drag on a 2.75× screen moves the edge smoothly instead of in threes.
 */
@Composable
private fun PanelResizeGrip(
    widthDp: Int,
    accent: Color,
    onResize: (Int) -> Unit,
    onResizeFinished: () -> Unit,
) {
    val density = LocalDensity.current.density
    // Read inside a gesture lambda that is remembered once, so it has to be a state read rather than a
    // captured Int: `pointerInput(Unit)` keeps the first lambda it was given, and re-keying it on the
    // width would restart the detector in the middle of the drag it is meant to be tracking.
    val current by rememberUpdatedState(widthDp)
    var dragging by remember { mutableStateOf(false) }
    var carriedDp by remember { mutableFloatStateOf(0f) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = PANEL_PADDING_DP.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The figure only while the finger is down. A permanent readout of the panel's own width is a
        // number the user has no use for once they have stopped choosing it.
        BasicText(
            text = if (dragging) "$widthDp dp" else "",
            style = TextStyle(color = accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace),
        )
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(GRIP_TOUCH_DP.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragging = true
                            carriedDp = 0f
                        },
                        onDragEnd = {
                            dragging = false
                            carriedDp = 0f
                            onResizeFinished()
                        },
                        onDragCancel = {
                            dragging = false
                            carriedDp = 0f
                            onResizeFinished()
                        },
                    ) { _, deltaPx ->
                        carriedDp += deltaPx / density
                        val whole = carriedDp.toInt()
                        if (whole != 0) {
                            carriedDp -= whole
                            onResize(current + whole)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.CompareArrows,
                contentDescription = "Drag to change the panel's width",
                tint = if (dragging) accent else OverlayPalette.Muted,
                modifier = Modifier.size(GRIP_ICON_DP.dp),
            )
        }
    }
}

@Composable
internal fun PanelHeader(state: OverlayPanelState, accent: Color) {
    Column(modifier = Modifier.fillMaxWidth()) {
        BasicText(
            text = state.gameLabel.ifEmpty { "GameCore" },
            maxLines = 1,
            style = TextStyle(
                color = OverlayPalette.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        BasicText(
            // No session is a state worth naming rather than a blank: the panel can be opened from the
            // home screen, and "Not tracking" tells the user why the timer is missing.
            text = state.sessionElapsed?.let { "Session $it" } ?: "Not tracking",
            style = TextStyle(
                color = if (state.sessionElapsed != null) accent else OverlayPalette.Muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}

/** The same readings the pill shows, in a single dense row, so the panel does not need the pill open. */
@Composable
internal fun PanelStats(readings: List<StatReading>) {
    Row(
        modifier = Modifier
            .background(OverlayPalette.Plate, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        readings.take(PANEL_STATS).forEach { reading ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(
                    text = reading.display(withUnit = false),
                    style = TextStyle(
                        color = if (reading.isAvailable) OverlayPalette.Text else OverlayPalette.Absent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                BasicText(
                    text = reading.stat.shortLabel,
                    style = TextStyle(color = OverlayPalette.Muted, fontSize = 9.sp),
                )
            }
        }
    }
}

/**
 * The volume and brightness sliders of §16, and the three colour sliders under them.
 *
 * A Compose slider is stateless: it draws where its `value` says and reports where the finger went. The
 * finger's position therefore has to live somewhere, and it lives in the service's [OverlayPanelState]
 * rather than in a `remember` here — the same arrangement the in-app sliders use with their ViewModel.
 * That is not only for consistency. Held locally, the panel would show the requested level while the
 * service knew the real one, and when the device rounds a request — a media stream has about fifteen
 * steps, so most percentages are not reachable — the two disagree with no event to reconcile them: the
 * thumb would sit at 43% on a device that went to 40% and stay there for as long as the panel is open.
 * With one number in one place, the read-back after the write corrects the thumb.
 *
 * [onDragLevel] moves the thumb and writes nothing; [onCommitLevel] is the write, once, when the finger
 * lifts. Writing per drag frame would mean sixty `settings put` calls a second, each one possibly
 * through the elevated shell — and for the colour levels, sixty projections and eight reads each.
 *
 * Two plates rather than five rows on one, because the colour three are a different kind of thing: they
 * are the quick-access face of a preset with fourteen values, and the tile in the grid below opens the
 * rest. The label says so. Which of the five are in which plate is [OverlayLevel.isColour]'s answer, not
 * a list repeated here.
 */
@Composable
internal fun PanelLevels(
    state: OverlayPanelState,
    accent: Color,
    editing: OverlayLevel?,
    onDragLevel: (OverlayLevel, Int) -> Unit,
    onCommitLevel: (OverlayLevel) -> Unit,
    onEditLevel: (OverlayLevel) -> Unit,
) {
    val (colour, device) = OverlayLevel.entries.partition { it.isColour }
    LevelPlate(
        levels = device,
        state = state,
        accent = accent,
        editing = editing,
        onDragLevel = onDragLevel,
        onCommitLevel = onCommitLevel,
        onEditLevel = onEditLevel,
    )
    LevelPlate(
        levels = colour,
        state = state,
        accent = accent,
        editing = editing,
        title = COLOUR_SECTION_TITLE,
        onDragLevel = onDragLevel,
        onCommitLevel = onCommitLevel,
        onEditLevel = onEditLevel,
    )
}

/** One rounded plate of slider rows, with an optional section label above them. */
@Composable
private fun LevelPlate(
    levels: List<OverlayLevel>,
    state: OverlayPanelState,
    accent: Color,
    editing: OverlayLevel?,
    onDragLevel: (OverlayLevel, Int) -> Unit,
    onCommitLevel: (OverlayLevel) -> Unit,
    onEditLevel: (OverlayLevel) -> Unit,
    title: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OverlayPalette.Plate, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        title?.let {
            BasicText(
                text = it,
                style = TextStyle(
                    color = OverlayPalette.Muted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        levels.forEach { level ->
            LevelRow(
                level = level,
                levelState = state.levelFor(level),
                accent = accent,
                isEditing = editing == level,
                onDragLevel = onDragLevel,
                onCommitLevel = onCommitLevel,
                onEditLevel = onEditLevel,
            )
        }
    }
}

/**
 * One slider.
 *
 * Unreadable and unwritable are different failures, and they are drawn differently. A level that could
 * not be read at all shows "--" where the value goes, because there is no honest number to put there. A
 * level that reads but cannot be changed shows the number and dims the track, with the reason under it —
 * brightness on a device where GameCore holds neither WRITE_SETTINGS nor a live Shizuku connection is
 * the ordinary case of that, and a slider that moves and changes nothing is exactly what §32 rules out.
 *
 * There is a third case, and it belongs to the colour levels alone: a slider that writes and stores
 * perfectly well while *this* display has no control that can express it — a hue rotation on any
 * Android, because a rotation is a colour matrix. Dimming that one would be wrong twice over: the value
 * is real, it travels with the preset to a device that honours more, and a disabled slider would strand
 * it wherever the last drag left it. So it draws live with [OverlayLevelState.note] under it, which says
 * what will not happen. Both sentences arrive as [OverlayLevelState.detail] and neither is invented
 * here — the service asks `ColorProjection`.
 *
 * The readout is a button. Tapping it opens [LevelKeypad], because a 268dp slider divides a hue's 360
 * degrees into about four per pixel and there is no dragging your way to exactly +90°.
 */
@Composable
private fun LevelRow(
    level: OverlayLevel,
    levelState: OverlayLevelState,
    accent: Color,
    isEditing: Boolean,
    onDragLevel: (OverlayLevel, Int) -> Unit,
    onCommitLevel: (OverlayLevel) -> Unit,
    onEditLevel: (OverlayLevel) -> Unit,
) {
    val usable = levelState.isUsable
    val range = level.range
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = level.icon(),
                contentDescription = level.label,
                tint = if (usable) OverlayPalette.Text else OverlayPalette.Absent,
                modifier = Modifier.size(16.dp),
            )
            Slider(
                // Not zero for a level that could not be read: a hue's neutral is the middle of its
                // range, and a thumb parked at the far left would read as "rotated fully anticlockwise"
                // rather than as "unknown". The dimmed track and the "--" readout carry that.
                value = (levelState.value ?: range.neutral()).toFloat(),
                onValueChange = { onDragLevel(level, it.roundToInt().coerceIn(range)) },
                modifier = Modifier.weight(1f),
                enabled = usable,
                valueRange = range.first.toFloat()..range.last.toFloat(),
                onValueChangeFinished = { onCommitLevel(level) },
                // Explicit colours because no `MaterialTheme` wraps an overlay window — see
                // [OverlayViewHost]. Left to its defaults the slider would draw itself in the light
                // scheme's purple on a near-black plate.
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = OverlayPalette.Divider,
                    disabledThumbColor = OverlayPalette.Absent,
                    disabledActiveTrackColor = OverlayPalette.Absent,
                    disabledInactiveTrackColor = OverlayPalette.Divider,
                ),
            )
            BasicText(
                text = levelState.value?.let { level.format(it) } ?: "--",
                maxLines = 1,
                style = TextStyle(
                    color = when {
                        isEditing -> accent
                        usable -> OverlayPalette.Text
                        else -> OverlayPalette.Absent
                    },
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
                ),
                // Fixed width so the slider does not shrink by a few pixels as the readout crosses from
                // two digits to three, which reads as the thumb drifting under a still finger.
                modifier = Modifier
                    .width(LEVEL_READOUT_WIDTH_DP.dp)
                    .clickable(enabled = usable) { onEditLevel(level) },
            )
        }
        levelState.detail?.let { detail ->
            BasicText(
                text = detail,
                maxLines = 3,
                style = TextStyle(color = OverlayPalette.Absent, fontSize = 8.5.sp),
                modifier = Modifier.padding(start = 22.dp, bottom = 2.dp),
            )
        }
    }
}

/**
 * The middle of a range, which is where a slider with nothing to show should sit.
 *
 * Zero for every level the panel has, since the colour ranges are symmetric and the device ones start
 * there — but written as arithmetic rather than as `0` so a range that is not symmetric still parks its
 * thumb somewhere defensible.
 */
private fun IntRange.neutral(): Int = (first + last) / 2

/**
 * The number pad for exact entry, drawn by GameCore rather than asked for from the system.
 *
 * The system keyboard is not available here and must not be made available. Every overlay window this
 * app adds carries `FLAG_NOT_FOCUSABLE`, and the reason is in [OverlayWindows]: a focusable overlay
 * takes the hardware back button and the IME away from the app underneath, so a player who opens the
 * chat box in a game would find their typing going nowhere. Dropping that flag to collect three digits
 * would break the running game to fill in a slider — so the pad is twelve buttons in the window that is
 * already open, which needs no focus at all.
 *
 * [typed] is a string rather than an Int because "−" and "" are both states a half-entered number
 * passes through, and neither is a number. OK is offered only once it parses; the range is printed
 * beside the entry, so a 200 clamped to 100 is a rule the user was told rather than a value that
 * changed itself.
 */
@Composable
internal fun LevelKeypad(
    level: OverlayLevel,
    typed: String,
    accent: Color,
    onType: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val range = level.range
    val parsed = typed.toIntOrNull()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OverlayPalette.Plate, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(
                text = level.label,
                style = TextStyle(
                    color = OverlayPalette.Muted,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            BasicText(
                text = "${range.first} … ${range.last}",
                style = TextStyle(
                    color = OverlayPalette.Absent,
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
        BasicText(
            text = typed.ifEmpty { "0" },
            maxLines = 1,
            style = TextStyle(
                color = if (typed.isEmpty()) OverlayPalette.Absent else accent,
                fontSize = 20.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        KEYPAD_ROWS.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                row.forEach { key ->
                    KeypadButton(
                        key = key,
                        accent = accent,
                        enabled = key.isEnabled(typed, range, parsed),
                        onPress = {
                            when (key) {
                                KeypadKey.SIGN -> onType(typed.toggleSign())
                                KeypadKey.BACK -> onType(typed.dropLast(1))
                                // Clamped here rather than refused, and the range above the entry is
                                // what makes that honest: a user who types 200 into a ±100 field is
                                // told what the field accepts and gets the nearest value it does.
                                KeypadKey.OK -> parsed?.let { onConfirm(it.coerceIn(range)) }
                                else -> onType((typed + key.label).take(KEYPAD_MAX_CHARS))
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        BasicText(
            text = "Cancel",
            style = TextStyle(
                color = OverlayPalette.Muted,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCancel() }
                .padding(vertical = 4.dp),
        )
    }
}

/** One key. Flat plates rather than Material buttons, so the pad matches the panel it sits in. */
@Composable
private fun KeypadButton(
    key: KeypadKey,
    accent: Color,
    enabled: Boolean,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val confirm = key == KeypadKey.OK
    Box(
        modifier = modifier
            .background(
                color = when {
                    !enabled -> Color.Transparent
                    confirm -> accent.copy(alpha = ACTIVE_PLATE_ALPHA)
                    else -> OverlayPalette.Divider
                },
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(enabled = enabled) { onPress() }
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = key.label,
            maxLines = 1,
            style = TextStyle(
                color = when {
                    !enabled -> OverlayPalette.Absent
                    confirm -> accent
                    else -> OverlayPalette.Text
                },
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (confirm) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/**
 * The keys, as a closed set so the pad's `when` cannot miss one.
 *
 * [SIGN] is offered on every level and disabled on the ones whose range does not go below zero, rather
 * than left out of those layouts: a pad that changes shape between volume and hue moves OK under the
 * finger that was about to press it.
 */
private enum class KeypadKey(val label: String) {
    ONE("1"), TWO("2"), THREE("3"),
    FOUR("4"), FIVE("5"), SIX("6"),
    SEVEN("7"), EIGHT("8"), NINE("9"),
    SIGN("−"), ZERO("0"), BACK("⌫"),
    OK("OK"),
    ;

    fun isEnabled(typed: String, range: IntRange, parsed: Int?): Boolean = when (this) {
        SIGN -> range.first < 0
        BACK -> typed.isNotEmpty()
        OK -> parsed != null
        else -> typed.length < KEYPAD_MAX_CHARS
    }
}

/** Adds or removes the leading minus, keeping the digits. */
private fun String.toggleSign(): String =
    if (startsWith("-")) drop(1) else "-$this"

/**
 * The saved presets, as chips, revealed by a long press on the colour tile.
 *
 * Names come from the user, and they are drawn with `maxLines = 1` and a width cap for the reason
 * §24A gives: a preset called with three hundred characters of newlines would otherwise be able to push
 * every action button off a panel floating over a game. The stored name is already sanitised at the
 * database boundary; this is the render half of the same rule.
 *
 * The chip for [OverlayPanelState.activePresetId] gets the filled accent plate — the same treatment the
 * active toggles in the grid get, for the same reason: it is the one whose values are on screen.
 *
 * The row reflows with the panel's width on the same rule the action grid does, and it has to: a narrowed
 * panel that kept three fixed chips across would put the third one through the edge of its own plate.
 */
@Composable
internal fun PresetChips(
    state: OverlayPanelState,
    accent: Color,
    widthDp: Int,
    onPreset: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (state.presets.isEmpty()) {
            BasicText(
                text = "No saved colour presets yet. Save one from the colour screen.",
                maxLines = 2,
                style = TextStyle(color = OverlayPalette.Absent, fontSize = 9.sp),
            )
            return@Column
        }
        val perRow = presetsPerRow(widthDp)
        state.presets.chunked(perRow).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PRESET_GAP_DP.dp),
            ) {
                row.forEach { preset ->
                    val active = preset.id == state.activePresetId
                    BasicText(
                        text = preset.name,
                        maxLines = 1,
                        style = TextStyle(
                            color = if (active) accent else OverlayPalette.Text,
                            fontSize = 9.5.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                color = if (active) {
                                    accent.copy(alpha = ACTIVE_PLATE_ALPHA)
                                } else {
                                    OverlayPalette.Divider
                                },
                                shape = RoundedCornerShape(9.dp),
                            )
                            .clickable { onPreset(preset.id) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                    )
                }
                repeat(perRow - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * The shapes this display can be stretched to, revealed by a tap on the Aspect tile.
 *
 * Each chip carries both halves of what it means — "16:9" over what 16:9 is in pixels here — because a
 * ratio on its own is not something a player can check against the screen in front of them, and the pixel
 * figure is the thing a profile stores. They apply on the tap with no confirm step, like every other
 * control in this panel, and Native is first so the way back is a chip rather than a menu.
 *
 * The sentence above them is deliberate and unsoftened: this stretches the display. A game handed a
 * shorter frame draws the same scene into it, so the picture is reshaped and the field of view is not
 * widened. Wording it as anything else would be selling a competitive advantage the platform cannot give.
 *
 * An empty list is a device whose panel size could not be read, which in practice means no elevated
 * shell. The tile above is already dimmed with that reason, so this row says the part the tile cannot: the
 * shapes are missing because the size is unknown, not because the display has none.
 */
@Composable
internal fun AspectChips(
    state: OverlayPanelState,
    accent: Color,
    widthDp: Int,
    onAspect: (AspectChoice) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (state.aspects.isEmpty()) {
            BasicText(
                text = state.aspectNote ?: ASPECT_SIZE_UNKNOWN,
                maxLines = 3,
                style = TextStyle(color = OverlayPalette.Absent, fontSize = 9.sp),
            )
            return@Column
        }
        BasicText(
            text = ASPECT_STRETCH_NOTE,
            maxLines = 3,
            style = TextStyle(color = OverlayPalette.Absent, fontSize = 9.sp),
        )
        val perRow = presetsPerRow(widthDp)
        state.aspects.chunked(perRow).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PRESET_GAP_DP.dp),
            ) {
                row.forEach { choice ->
                    AspectChip(
                        choice = choice,
                        active = choice.preset == state.activeAspect,
                        accent = accent,
                        onClick = { onAspect(choice) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(perRow - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
        state.aspectNote?.let { note ->
            BasicText(
                text = note,
                maxLines = 2,
                style = TextStyle(color = OverlayPalette.Absent, fontSize = 9.sp),
            )
        }
    }
}

/**
 * One shape, as a two-line chip: the ratio, and what that ratio is in pixels on this display.
 *
 * Two lines rather than the single [BasicText] a colour preset gets, because a preset's name is the whole
 * of what the user needs and a ratio is not. "16:9" does not say whether it will cost height or width here,
 * and the pixel pair does; it is also the value that ends up in the profile, so seeing it before the tap is
 * the difference between choosing a size and accepting one.
 *
 * Both lines are `maxLines = 1`. Neither string comes from the user — the labels are this app's and the
 * detail is computed from the panel's own size — so the cap is here for a narrow panel rather than for
 * §24A's reason, and the row's reflow does the rest of that job.
 */
@Composable
private fun AspectChip(
    choice: AspectChoice,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(
                color = if (active) accent.copy(alpha = ACTIVE_PLATE_ALPHA) else OverlayPalette.Divider,
                shape = RoundedCornerShape(9.dp),
            )
            .clickable { onClick() }
            .padding(vertical = 5.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            text = choice.label,
            maxLines = 1,
            style = TextStyle(
                color = if (active) accent else OverlayPalette.Text,
                fontSize = 9.5.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            ),
        )
        BasicText(
            text = choice.detail,
            maxLines = 1,
            style = TextStyle(
                color = OverlayPalette.Absent,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/**
 * The rates this panel advertises, revealed by a tap on the Refresh tile.
 *
 * Shaped like [AspectChips] because it is the same kind of control — a short closed list, applied on the tap,
 * with the way back as the first chip — and it deliberately offers the same set the profile editor's refresh
 * row does, in the same order and with the same words. A player who set 90 Hz in a profile and then reaches
 * for this row should not have to work out whether they are looking at the same control.
 *
 * "Leave alone" is that first chip, and it is the profile editor's label rather than a livelier one for the
 * same reason. What it does here is not quite what it does there: in a profile it means *do not touch the
 * rate when this game starts*, and here it releases the bounds GameCore wrote. The row's own sentence says
 * so, because the difference is real and a chip label is not the place to explain it.
 *
 * No chip is filled unless GameCore confirmed the rate — [OverlayPanelState.pinnedRefreshRate] holds that,
 * and holds null for every other state including a change the platform accepted and ignored. An unfilled row
 * over a display that is in fact running 120 Hz is the deliberate choice: this row reports what GameCore
 * knows it did, not what it hopes happened.
 */
@Composable
internal fun RateChips(
    state: OverlayPanelState,
    accent: Color,
    widthDp: Int,
    onRate: (Float?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (state.refreshRates.isEmpty()) {
            BasicText(
                text = state.refreshRateNote ?: RATE_UNKNOWN,
                maxLines = 3,
                style = TextStyle(color = OverlayPalette.Absent, fontSize = 9.sp),
            )
            return@Column
        }
        BasicText(
            text = RATE_RELEASE_NOTE,
            maxLines = 3,
            style = TextStyle(color = OverlayPalette.Absent, fontSize = 9.sp),
        )
        // Null first, as the release chip. The same `listOf<Float?>(null) + rates` the profile editor builds,
        // so the two rows cannot drift into offering different things.
        val choices: List<Float?> = listOf<Float?>(null) + state.refreshRates
        val perRow = presetsPerRow(widthDp)
        choices.chunked(perRow).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PRESET_GAP_DP.dp),
            ) {
                row.forEach { rate ->
                    RateChip(
                        rateHz = rate,
                        active = rate == state.pinnedRefreshRate,
                        accent = accent,
                        onClick = { onRate(rate) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(perRow - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
        state.refreshRateNote?.let { note ->
            BasicText(
                text = note,
                maxLines = 3,
                style = TextStyle(color = OverlayPalette.Absent, fontSize = 9.sp),
            )
        }
    }
}

/**
 * One rate, or the release chip when [rateHz] is null.
 *
 * A single line, unlike [AspectChip]'s two: a rate needs no second figure to be understood, and "120 Hz" is
 * already the whole of what the chip means. [com.gamecore.core.common.Formatters.hertz] rather than a local
 * format so 119.998 reads "120 Hz" here exactly as it does in the profile editor and the HUD.
 */
@Composable
private fun RateChip(
    rateHz: Float?,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = rateHz?.let(Formatters::hertz) ?: RATE_RELEASE_LABEL,
        maxLines = 1,
        style = TextStyle(
            color = if (active) accent else OverlayPalette.Text,
            fontSize = 9.5.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
        ),
        modifier = modifier
            .background(
                color = if (active) accent.copy(alpha = ACTIVE_PLATE_ALPHA) else OverlayPalette.Divider,
                shape = RoundedCornerShape(9.dp),
            )
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 4.dp),
    )
}

/**
 * The active crosshair's design and colour, revealed by a long press on the Crosshair tile.
 *
 * The one row in this panel that *edits* a saved thing rather than selecting one. The colour presets load a
 * preset, the shapes and rates set a device property; these two rows change two fields of the crosshair the
 * user already has, and the change is stored — a design tapped here is the design the crosshair screen shows
 * afterwards, because it is the same row in the same table. That is the whole point of the feature and it is
 * also its cost, said plainly in [CROSSHAIR_EDITS_NOTE] rather than left for the user to discover: there is
 * no session-only crosshair, so this is not a way to try a shape for one match.
 *
 * The name is drawn above the chips for that reason. A player with three saved crosshairs needs to know
 * which one is about to change, and "Crosshair" versus "Sniper" is the only thing that tells them.
 *
 * [CrosshairDesign.CUSTOM_IMAGE] is not offered. It is the one design that needs a file chosen through a
 * picker, and a chip that switched to it would leave the overlay drawing nothing at all on a preset with no
 * image imported — so the drawn designs are the whole row, and the image case stays where the picker is.
 *
 * Designs come from the enum here rather than from the state, unlike the colours. The difference is real:
 * the drawn designs are the same list on every device and every session, so carrying them through the window
 * state would be shipping a constant across a process boundary, while the colours are a list the settings
 * layer owns and can grow.
 */
@Composable
internal fun CrosshairChips(
    state: OverlayPanelState,
    accent: Color,
    widthDp: Int,
    onCrosshairDesign: (CrosshairDesign) -> Unit,
    onCrosshairColour: (Int) -> Unit,
) {
    val crosshair = state.crosshair
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (crosshair == null) {
            BasicText(
                text = CROSSHAIR_NONE_SAVED,
                maxLines = 2,
                style = TextStyle(color = OverlayPalette.Absent, fontSize = 9.sp),
            )
            return@Column
        }
        BasicText(
            text = crosshair.name,
            maxLines = 1,
            style = TextStyle(
                color = OverlayPalette.Muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        val perRow = presetsPerRow(widthDp)
        DRAWN_DESIGNS.chunked(perRow).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PRESET_GAP_DP.dp),
            ) {
                row.forEach { design ->
                    val active = design == crosshair.design
                    BasicText(
                        text = design.label,
                        maxLines = 1,
                        style = TextStyle(
                            color = if (active) accent else OverlayPalette.Text,
                            fontSize = 9.5.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                color = if (active) {
                                    accent.copy(alpha = ACTIVE_PLATE_ALPHA)
                                } else {
                                    OverlayPalette.Divider
                                },
                                shape = RoundedCornerShape(9.dp),
                            )
                            .clickable { onCrosshairDesign(design) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                    )
                }
                repeat(perRow - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
        if (state.crosshairColours.isNotEmpty()) {
            CrosshairSwatches(
                colours = state.crosshairColours,
                selectedArgb = crosshair.colorArgb,
                onSelect = onCrosshairColour,
            )
        }
        BasicText(
            text = CROSSHAIR_EDITS_NOTE,
            maxLines = 3,
            style = TextStyle(color = OverlayPalette.Absent, fontSize = 9.sp),
        )
    }
}

/**
 * The colours, as the colours themselves rather than as their names.
 *
 * A swatch is the one chip in this panel whose label would be worse than its subject: "Amber" over a game
 * means nothing until it is on the screen, and the colour drawn at 22 dp is the answer to the only question
 * being asked — will the eye find this against what is behind it.
 *
 * Which is also why the selected swatch is marked with a ring drawn *outside* the colour rather than with the
 * filled accent plate the other chips use. A plate tinted with the accent behind a coloured square changes
 * how the square reads, so the panel would be recolouring the very thing the user is trying to judge. The
 * ring is [OverlayPalette.Text] on the outside and the plate colour on the inside, so it reads on a white
 * swatch and on a red one.
 *
 * Wrapped by hand at a fixed swatch size instead of sharing the row with `weight`, because a colour stretched
 * to fill a plate is a different amount of colour on a narrow panel than on a wide one, and a swatch's whole
 * job is to be the same patch every time.
 */
@Composable
private fun CrosshairSwatches(
    colours: List<Int>,
    selectedArgb: Int,
    onSelect: (Int) -> Unit,
) {
    val perRow = SWATCHES_PER_ROW
    Column(verticalArrangement = Arrangement.spacedBy(SWATCH_GAP_DP.dp)) {
        colours.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(SWATCH_GAP_DP.dp)) {
                row.forEach { argb ->
                    val selected = argb == selectedArgb
                    Box(
                        modifier = Modifier
                            .size(SWATCH_DP.dp)
                            .background(
                                color = if (selected) OverlayPalette.Text else Color.Transparent,
                                shape = RoundedCornerShape(SWATCH_CORNER_DP.dp),
                            )
                            .clickable { onSelect(argb) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size((SWATCH_DP - if (selected) SWATCH_RING_DP * 2 else 0).dp)
                                .background(
                                    color = Color(argb),
                                    shape = RoundedCornerShape(SWATCH_CORNER_DP.dp),
                                ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The drawn designs, in the order the enum declares them.
 *
 * The same expression [com.gamecore.ui.crosshair.CrosshairScreen]'s own design grid is built from, and
 * deliberately not shared with it: one is a settings screen in the app process and one is an overlay in a
 * service, and a constant they both read would put a `ui` package on the overlay's import list to save a
 * `filter`. What matters is that both derive it from [CrosshairDesign.isDrawn] rather than listing designs by
 * hand, so a design added to the enum appears in both without either being edited.
 */
private val DRAWN_DESIGNS: List<CrosshairDesign> = CrosshairDesign.entries.filter { it.isDrawn }

/**
 * The glyph per level. Here rather than on the enum for the same reason [OverlayAction.icon] is.
 *
 * The auto-mirrored volume icon, not the plain one: the plain `Icons.Rounded.VolumeUp` is deprecated
 * precisely because a speaker with its waves on the right is wrong in an RTL layout.
 */
private fun OverlayLevel.icon(): ImageVector = when (this) {
    OverlayLevel.VOLUME -> Icons.AutoMirrored.Rounded.VolumeUp
    OverlayLevel.BRIGHTNESS -> Icons.Rounded.BrightnessMedium
    OverlayLevel.SATURATION -> Icons.Rounded.Opacity
    OverlayLevel.CONTRAST -> Icons.Rounded.Contrast
    OverlayLevel.HUE -> Icons.Rounded.ColorLens
}

/**
 * One action.
 *
 * The three visual states are on, off, and unavailable, and they are distinguishable without reading:
 * an active toggle is tinted with the accent on a filled plate, an inactive one is plain, and an
 * unavailable one is dimmed with a struck-through icon. The reason is drawn under the label at a smaller
 * size — it is the answer to "why is this greyed out", and the panel is where the question gets asked.
 *
 * A long press is offered only where there is something behind it, which is [OverlayAction.heldRow] — the
 * colour tile, whose saved presets are a list no grid cell can hold, and the crosshair tile, whose design
 * and colour are the same kind of set. Every other button passes `null` and keeps the plain `clickable`, so
 * nothing gains a hidden gesture: a long press that does nothing on all but two of these buttons teaches
 * the user that long pressing does nothing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionButton(
    action: OverlayAction,
    state: OverlayPanelState,
    accent: Color,
    onAction: (OverlayAction) -> Unit,
    onLongAction: (OverlayAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val usable = state.isUsable(action)
    val active = usable && action.isToggle && state.isActive(action)
    val tint = when {
        !usable -> OverlayPalette.Absent
        active -> accent
        action == OverlayAction.STOP_SESSION -> OverlayPalette.Danger
        else -> OverlayPalette.Text
    }
    Column(
        modifier = modifier
            .background(
                color = if (active) accent.copy(alpha = ACTIVE_PLATE_ALPHA) else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            // Unusable actions are not clickable at all rather than clickable-and-ignored: a button that
            // depresses and does nothing reads as broken, while one that does not respond reads as off.
            .combinedClickable(
                enabled = usable,
                onLongClick = if (action.heldRow != null) ({ onLongAction(action) }) else null,
                onClick = { onAction(action) },
            )
            .padding(vertical = 7.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = action.icon(),
                contentDescription = action.label,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            if (!usable) {
                Icon(
                    imageVector = Icons.Rounded.Block,
                    contentDescription = null,
                    tint = OverlayPalette.Absent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        BasicText(
            text = action.label,
            maxLines = 1,
            style = TextStyle(
                color = tint,
                fontSize = 9.5.sp,
                textAlign = TextAlign.Center,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
        state.reasonFor(action)?.let { reason ->
            BasicText(
                text = reason,
                maxLines = 2,
                style = TextStyle(
                    color = OverlayPalette.Absent,
                    fontSize = 8.sp,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

/**
 * The glyph per action.
 *
 * Here rather than on the enum so [OverlayAction] stays free of Compose types — the service executes
 * these and has no business importing an `ImageVector`.
 */
private fun OverlayAction.icon(): ImageVector = when (this) {
    OverlayAction.PILL -> Icons.Rounded.Speed
    OverlayAction.CROSSHAIR -> Icons.Rounded.GpsFixed
    OverlayAction.HUD -> Icons.Rounded.Dashboard
    OverlayAction.SCREENSHOT -> Icons.Rounded.PhotoCamera
    OverlayAction.RECORD -> Icons.Rounded.FiberManualRecord
    OverlayAction.FLASHLIGHT -> Icons.Rounded.FlashlightOn
    OverlayAction.DO_NOT_DISTURB -> Icons.Rounded.DoNotDisturbOn
    OverlayAction.ROTATION_LOCK -> Icons.Rounded.ScreenLockRotation
    OverlayAction.COLOR -> Icons.Rounded.Tune
    OverlayAction.ASPECT -> Icons.Rounded.AspectRatio
    OverlayAction.REFRESH_RATE -> Icons.Rounded.Refresh
    // Two panes divided down the middle, which is the shape the tile switches to rather than a picture of
    // switching: the split layout is two plates with the game visible between them.
    OverlayAction.PANEL_LAYOUT -> Icons.Rounded.VerticalSplit
    OverlayAction.STOP_SESSION -> Icons.Rounded.StopCircle
    OverlayAction.OPEN_APP -> Icons.AutoMirrored.Rounded.OpenInNew
}

/**
 * How many action tiles fit across a panel [widthDp] wide.
 *
 * Shared arithmetic, and it has to be shared by all three of the places that care: the panel lays its
 * grid out with this, the settings screen's preview draws that many placeholder tiles with it, and a test
 * holds it to exactly two at [com.gamecore.core.model.FloatingButtonConfig.MIN_PANEL_WIDTH_DP], which is
 * where that floor came from. A second copy of the sum on the settings screen would be a preview that
 * promises a row the panel does not draw.
 *
 * `n` tiles carry `n - 1` gaps between them, so the answer is the largest `n` satisfying
 * `n × MIN_ACTION_WIDTH_DP + (n − 1) × ACTION_GAP_DP ≤ available`, which is the division below. The
 * result is floored at [MIN_ACTIONS_PER_ROW] rather than at one: the model's clamp means a narrower panel
 * than that cannot be stored, and if one ever arrives from an older install's preferences a single tile
 * per row is a list, not a grid.
 */
internal fun actionsPerRow(widthDp: Int): Int {
    val available = widthDp - PANEL_PADDING_DP * 2
    val fit = (available + ACTION_GAP_DP) / (MIN_ACTION_WIDTH_DP + ACTION_GAP_DP)
    return fit.coerceIn(MIN_ACTIONS_PER_ROW, OverlayAction.entries.size)
}

/**
 * The panel's own inset, which the width arithmetic has to know about.
 *
 * On the scrolling column rather than on the plate, so the margin scrolls with the content; the grip row
 * below it re-uses the same figure on its left so its readout lines up with the text above it.
 *
 * `internal` along with the two below because the test for [actionsPerRow] asserts the inequality in its
 * KDoc — that `n` tiles and their gaps fit inside the plate at every width the model allows — and a test
 * that retyped these three figures could not catch one of them changing, which is the only way that
 * inequality ever stops holding.
 */
internal const val PANEL_PADDING_DP = 12

/** The gap between action tiles, and between the rows of them. */
internal const val ACTION_GAP_DP = 6

/**
 * The narrowest an action tile may be drawn: an icon with "Rotation" under it, in 8.5.sp.
 *
 * A minimum rather than the fixed size the grid used to give every tile. Tiles now share the row through
 * `weight`, so this only decides *how many* of them a row gets — which is what makes a resizable panel
 * reflow instead of clip. At the default width it yields the four across the panel has always had, and
 * the two dp by which four fixed 57 dp tiles used to overhang the plate go away with it.
 */
internal const val MIN_ACTION_WIDTH_DP = 56

/** Below two the grid stops being one. See [actionsPerRow]. */
internal const val MIN_ACTIONS_PER_ROW = 2

/** The grip's touch target: the 48 dp minimum, less the padding the row it sits in already provides. */
private const val GRIP_TOUCH_DP = 40

private const val GRIP_ICON_DP = 18

/**
 * The floor on the height the service is allowed to hand down.
 *
 * A button parked a few pixels from the bottom of the screen leaves almost no room below it, and the
 * service opens the panel on the side with more room — but on a short screen in landscape both sides can
 * be thin. Rather than draw a panel two rows tall, the cap is never taken below this, and the panel is
 * allowed to overhang: an overlay window is not clipped to the space under the button, only positioned
 * from it.
 */
internal const val MIN_PANEL_HEIGHT_DP = 220

/** Room for "100%" — and for "−180°" — in monospace at 10.sp. */
private const val LEVEL_READOUT_WIDTH_DP = 34

/** How many of the pill's stats fit across the panel without shrinking the type. */
private const val PANEL_STATS = 5

internal const val ACTIVE_PLATE_ALPHA = 0.18f

/** Names the colour sliders as a group, so their section is not mistaken for two more device levels. */
private const val COLOUR_SECTION_TITLE = "Colour"

/**
 * Four characters: a sign and three digits, which is exactly "−180" and "−100".
 *
 * A cap rather than a validity check, so the pad refuses the fifth keystroke instead of accepting a
 * number it will silently clamp. Both happen — the range is printed above the entry — but a key that
 * does nothing is better than a digit that appears and is then thrown away.
 */
private const val KEYPAD_MAX_CHARS = 4

/** The pad, as it is laid out: three digits a row, then the sign, zero and backspace, then OK. */
private val KEYPAD_ROWS: List<List<KeypadKey>> = listOf(
    listOf(KeypadKey.ONE, KeypadKey.TWO, KeypadKey.THREE),
    listOf(KeypadKey.FOUR, KeypadKey.FIVE, KeypadKey.SIX),
    listOf(KeypadKey.SEVEN, KeypadKey.EIGHT, KeypadKey.NINE),
    listOf(KeypadKey.SIGN, KeypadKey.ZERO, KeypadKey.BACK),
    listOf(KeypadKey.OK),
)

/**
 * How many chips fit across a panel [widthDp] wide. [actionsPerRow]'s arithmetic, over chips.
 *
 * Floored at one rather than at two: a chip carries a name the user wrote, and one wide chip per row is a
 * perfectly good list of them, where one wide action *tile* per row is a grid that has stopped being one.
 *
 * The shape chips reflow on this same rule and not one of their own. There are only ever four of them and
 * their labels are short, so a second cap tuned to them would buy nothing but a row that breaks at a
 * different width from the row above it.
 */
internal fun presetsPerRow(widthDp: Int): Int {
    val available = widthDp - PANEL_PADDING_DP * 2
    val fit = (available + PRESET_GAP_DP) / (MIN_PRESET_CHIP_WIDTH_DP + PRESET_GAP_DP)
    return fit.coerceAtLeast(1)
}

internal const val PRESET_GAP_DP = 5

/**
 * The narrowest a preset chip may be drawn, which is three across at the panel's default width — where
 * it fits a 40-character name at 9.5.sp without truncating most of them.
 */
internal const val MIN_PRESET_CHIP_WIDTH_DP = 77

/**
 * What the shape chips do, said plainly, above them.
 *
 * The one piece of copy in this feature that is not free to be shorter. Reshaping the display is the thing
 * players reach for expecting a wider field of view, and it is not one: the game is handed a frame of a
 * different shape and draws the same scene into it. "Stretches" and "not a wider view" are both here
 * because either alone gets read as the other — a note that only said "stretches the picture" would be
 * taken for a description of how the extra view arrives.
 */
private const val ASPECT_STRETCH_NOTE =
    "Stretches the picture into a different shape. It is not a wider view of the game."

/**
 * Why there are no shapes to offer.
 *
 * The panel's own size is only readable through the elevated shell, and every shape here is computed from
 * it, so without one there is nothing honest to draw — not even Native, whose pixel figure would be a
 * guess. Says which fact is missing rather than "unavailable", because the two have different fixes.
 */
private const val ASPECT_SIZE_UNKNOWN =
    "This display's size could not be read, so there are no shapes to offer."

/** The release chip, labelled as the profile editor labels the same choice. See [RateChips]. */
private const val RATE_RELEASE_LABEL = "Leave alone"

/**
 * What the rate row does, above it.
 *
 * Two facts, and both of them are ones a user would otherwise have to discover by watching their battery.
 * Pinning a rate holds it — that is the point of pinning rather than raising a ceiling — and holding a high
 * one costs power whether or not anything on screen is moving. The release chip is named so the sentence has
 * something to point at, because "Leave alone" on its own reads as "do nothing", which is not what it does
 * in a panel sitting over a running game.
 */
private const val RATE_RELEASE_NOTE =
    "Holds the display at the rate you pick, which uses more power. Leave alone releases it."

/**
 * Why there are no rates to offer.
 *
 * Deliberately not the same sentence as [ASPECT_SIZE_UNKNOWN]'s, because the usual cause is different: a
 * panel with one mode is the ordinary case here and needs no elevated shell to explain, where a display size
 * that will not read almost always means no Shizuku. This is the fallback for the row being opened with an
 * empty list and no note, which the probe should make impossible — the specific reasons come from
 * [OverlayPanelState.refreshRateNote] and are the display's own.
 */
private const val RATE_UNKNOWN =
    "This display's rates could not be read, so there are none to offer."

/**
 * The one thing about the crosshair row a chip cannot show, said under it.
 *
 * Every other row in this panel is a temporary act on the device: a shape comes off, a rate is released, a
 * colour preset is one of several. This one writes to the crosshair the user authored, so a design tapped
 * during a match is the design that is there tomorrow — and a player who expected to be trying something on
 * for one round would find their own crosshair quietly replaced.
 *
 * Said rather than prevented. A session-only crosshair would mean a second piece of crosshair state living
 * beside the saved one, and two crosshairs that disagree about which is real is a worse outcome than a
 * sentence: the user can undo this from the same row, and the crosshair screen shows the same values.
 */
private const val CROSSHAIR_EDITS_NOTE =
    "Changes the saved crosshair, not just this session."

/**
 * The row with no crosshair to change.
 *
 * Reachable only where the presets table is empty, which the defaults seeded at first launch normally rule
 * out. It is still here rather than left as an empty row, because "nothing loaded" and "nothing exists" look
 * identical on a panel over a game, and the second one has a fix the user can act on.
 */
private const val CROSSHAIR_NONE_SAVED =
    "No crosshair saved yet. Make one from the crosshair screen."

/**
 * How many swatches share a row, fixed rather than derived from the panel's width.
 *
 * The one row here that does not reflow, and deliberately: a swatch is a fixed 22 dp, so a narrow panel
 * cannot clip one the way it would clip a chip with a word in it — the row simply wraps sooner and the
 * colours stay the same size. Four across leaves the fixed contrast set as two tidy rows on the narrowest
 * plate the split layout allows.
 */
private const val SWATCHES_PER_ROW = 4

private const val SWATCH_DP = 22

private const val SWATCH_GAP_DP = 5

private const val SWATCH_CORNER_DP = 6

/**
 * The width of the selection ring drawn around the active swatch.
 *
 * Inset from the outside rather than drawn over the colour, so the patch the user is judging is never
 * partly covered by the mark that says it is chosen. See [CrosshairSwatches].
 */
private const val SWATCH_RING_DP = 3

