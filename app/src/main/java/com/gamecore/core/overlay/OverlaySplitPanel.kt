package com.gamecore.core.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamecore.core.model.AspectChoice
import com.gamecore.core.model.CrosshairDesign
import com.gamecore.core.model.FloatingButtonConfig
import com.gamecore.domain.media.MediaCommand
import com.gamecore.domain.media.NowPlaying
import com.gamecore.domain.monitoring.StatReading
import com.gamecore.domain.overlay.QuickApp
import kotlin.math.roundToInt

/**
 * The same control panel as [OverlayControlPanel], split against both screen edges with the game between.
 *
 * The second of the two layouts [com.gamecore.core.model.PanelLayoutStyle] offers, and a separate
 * composable rather than a branch inside the first one. They disagree about almost everything structural:
 * the centered panel is one plate of a width the user chose, positioned from the button, closing itself on
 * a touch that lands outside it; this is two plates of a width derived from the screen, ignoring the
 * button's position entirely, in a window that has no outside. What they share is their *content*, and
 * that is shared for real — every piece below is the same `internal` composable the centered panel draws,
 * so an action added to the grid appears in both layouts or neither.
 *
 * Three things about this window are worth being plain about, because each of them is a cost:
 *
 * The window covers the screen, so the game underneath receives no touches at all while the panel is
 * open — not even in the gap between the plates. That gap shows the game; it is not a hole through to it.
 * Android gives an app one shape per window and this window is a rectangle, so the alternative would be
 * three windows whose positions had to be kept in step, and a game that took a touch through the middle
 * of an open panel would be a game taking a shot the player was not aiming.
 *
 * That is also why the gap is the dismiss target and why the header carries a close button. The centered
 * panel is dismissed by `ACTION_OUTSIDE`, which arrives from `FLAG_WATCH_OUTSIDE_TOUCH` when the user taps
 * past its edge — a full-screen window never generates one, so that path is not merely unused here, it
 * cannot fire. Handling the tap in the middle replaces it, and the ✕ exists because a full-screen overlay
 * whose only exit is an unmarked area of the screen is a trap.
 *
 * There is no resize grip. The plate width is [splitPlateWidth] of the screen rather than
 * [FloatingButtonConfig.panelWidthDp], so there is nothing here for a grip to set: dragging one would have
 * to either move a boundary the arithmetic recomputes on the next layout, or introduce a second stored
 * width that means nothing in the other layout. The stored width is left untouched and takes effect again
 * when the user switches back, which the settings screen says.
 */
@Composable
fun OverlaySplitPanel(
    state: OverlayPanelState,
    readings: List<StatReading>,
    nowPlaying: NowPlaying,
    quickApps: List<QuickApp>,
    accent: Color,
    screenWidthDp: Int,
    onAction: (OverlayAction) -> Unit,
    onLongAction: (OverlayAction) -> Unit,
    onPreset: (Long) -> Unit,
    onAspect: (AspectChoice) -> Unit,
    onRate: (Float?) -> Unit,
    onCrosshairDesign: (CrosshairDesign) -> Unit,
    onCrosshairColour: (Int) -> Unit,
    onDragLevel: (OverlayLevel, Int) -> Unit,
    onCommitLevel: (OverlayLevel) -> Unit,
    onMedia: (MediaCommand) -> Unit,
    onEnableMedia: () -> Unit,
    onLaunchApp: (QuickApp) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The keypad's state, held exactly as the centered panel holds it and for the same reasons: a
    // half-typed number is not a fact about the device and is meant to be forgotten when the panel closes.
    var editing by remember { mutableStateOf<OverlayLevel?>(null) }
    var typed by remember { mutableStateOf("") }

    val plateWidth = splitPlateWidth(screenWidthDp)

    Row(modifier = modifier.fillMaxSize()) {
        // ---------------------------------------------------------------- readouts, one edge
        SplitPlate(widthDp = plateWidth, alignEnd = false) {
            SplitHeaderRow(state = state, accent = accent, onDismiss = onDismiss)
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
                    editing = if (editing == level) null else level
                    typed = ""
                },
            )
            // The pad opens under the readout that opened it, rather than on the far plate as the
            // controls do. A number pad is a reply to a tap, and putting the reply an arm's width away
            // from the tap on a landscape phone means looking for it.
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
        }

        // ---------------------------------------------------------------- the game, and the way out
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                // `detectTapGestures` rather than `clickable`, which would draw an indication across the
                // whole gap: a ripple the width of the game is a flash over what the player is watching.
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.BottomCenter,
        ) {
            BasicText(
                text = SPLIT_DISMISS_HINT,
                style = TextStyle(color = OverlayPalette.Absent, fontSize = 9.sp),
                modifier = Modifier.padding(bottom = SPLIT_EDGE_INSET_DP.dp),
            )
        }

        // ---------------------------------------------------------------- controls, the other edge
        SplitPlate(widthDp = plateWidth, alignEnd = true) {
            PanelActions(
                state = state,
                accent = accent,
                widthDp = plateWidth,
                onAction = onAction,
                onLongAction = onLongAction,
            )
            if (state.presetsExpanded) {
                PresetChips(state = state, accent = accent, widthDp = plateWidth, onPreset = onPreset)
            }
            if (state.aspectsExpanded) {
                AspectChips(state = state, accent = accent, widthDp = plateWidth, onAspect = onAspect)
            }
            if (state.refreshRatesExpanded) {
                RateChips(state = state, accent = accent, widthDp = plateWidth, onRate = onRate)
            }
            if (state.crosshairExpanded) {
                CrosshairChips(
                    state = state,
                    accent = accent,
                    widthDp = plateWidth,
                    onCrosshairDesign = onCrosshairDesign,
                    onCrosshairColour = onCrosshairColour,
                )
            }
            // On the controls plate and in the same place in the order as in the centered layout: below
            // the grid and whatever a long press revealed, above the media strip. The apps are controls
            // — things to tap — so they belong on the plate the user reaches for controls on.
            QuickApps(apps = quickApps, accent = accent, onLaunch = onLaunchApp)
            // Last on the controls plate, exactly as it is last in the centered panel's column. On this
            // layout it is also where it has the most room: the plate is full height, so the strip lands
            // in space the grid and its chip rows were never going to reach.
            //
            // Handed `plateWidth` rather than the screen width, since it is the plate the strip has to
            // fit inside — which is [splitPlateWidth]'s answer, and on a portrait phone that is the
            // model's minimum, so the strip stacks and drops its artwork here where it would not on a
            // tablet. That is the responsiveness working, not the layout disagreeing with itself.
            MediaControls(
                nowPlaying = nowPlaying,
                accent = accent,
                widthDp = plateWidth,
                onCommand = onMedia,
                onEnableMedia = onEnableMedia,
            )
        }
    }
}

/**
 * One of the two plates: full height, fixed width, scrolling on its own.
 *
 * Independently scrolling rather than sharing one scroll state, because the two hold different amounts —
 * the controls plate is a grid of a dozen tiles and the readouts plate grows by a keypad when one is
 * open — and a shared scroll would drag the side the user is not touching.
 *
 * [alignEnd] rounds only the corners that face inward, so each plate reads as attached to its edge rather
 * than floating near it. Which is the whole visual idea: the plates are the frame, the game is the picture.
 */
@Composable
private fun SplitPlate(
    widthDp: Int,
    alignEnd: Boolean,
    content: @Composable () -> Unit,
) {
    val shape = if (alignEnd) {
        RoundedCornerShape(topStart = SPLIT_CORNER_DP.dp, bottomStart = SPLIT_CORNER_DP.dp)
    } else {
        RoundedCornerShape(topEnd = SPLIT_CORNER_DP.dp, bottomEnd = SPLIT_CORNER_DP.dp)
    }
    Column(
        modifier = Modifier
            .width(widthDp.dp)
            .fillMaxHeight()
            .background(OverlayPalette.PanelPlate, shape)
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = PANEL_PADDING_DP.dp,
                vertical = SPLIT_EDGE_INSET_DP.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

/**
 * [PanelHeader] with a close button beside it.
 *
 * The centered panel needs no such button — a tap anywhere past its edge closes it, and that edge is
 * visible. This window has no edge to tap past, so the exit has to be somewhere the user can point at.
 */
@Composable
private fun SplitHeaderRow(
    state: OverlayPanelState,
    accent: Color,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            PanelHeader(state = state, accent = accent)
        }
        Box(
            modifier = Modifier
                .size(SPLIT_CLOSE_TOUCH_DP.dp)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Close the panel",
                tint = OverlayPalette.Muted,
                modifier = Modifier.size(SPLIT_CLOSE_ICON_DP.dp),
            )
        }
    }
}

/**
 * How wide each edge plate is drawn on a screen [screenWidthDp] wide.
 *
 * A pure function of the screen and nothing else, which is what makes the split layout have no width
 * setting rather than an ignored one. Three bounds, applied in this order, and the order is the argument:
 *
 * 1. [SPLIT_PLATE_FRACTION] of the screen is what is wanted. Roughly a third each leaves roughly a third
 *    of the game between them, which is the proportion the layout exists for.
 * 2. Narrowed if that would leave less than [SPLIT_MIN_GAME_DP] between the plates. A split layout whose
 *    plates meet in the middle is the centered layout with extra steps.
 * 3. Then floored at [FloatingButtonConfig.MIN_PANEL_WIDTH_DP] — *after* rule 2, so the floor wins over
 *    the gap. That is deliberate: below that width the action grid drops to one tile per row, and a plate
 *    too narrow to be a grid is a worse outcome than a thin strip of visible game. In portrait on a phone
 *    this is the bound that decides, and it is why the settings screen says the game is a strip there.
 * 4. Finally capped at half the screen, so two plates can never overlap whatever the rules above asked
 *    for, and at [FloatingButtonConfig.MAX_PANEL_WIDTH_DP], so a tablet gets a usable plate rather than a
 *    third of a very wide screen with a hand's width of empty plate in it.
 *
 * Sharing the model's two width bounds rather than inventing new ones, because they are the same facts:
 * the floor is what the grid needs and the ceiling is where a plate stops using the space it is given.
 * What is *not* shared is [FloatingButtonConfig.panelWidthDp] — the user's stored figure belongs to the
 * other layout and is left alone here.
 */
internal fun splitPlateWidth(screenWidthDp: Int): Int {
    val preferred = (screenWidthDp * SPLIT_PLATE_FRACTION).roundToInt()
    val leavingGap = (screenWidthDp - SPLIT_MIN_GAME_DP) / 2
    val ceiling = minOf(screenWidthDp / 2, FloatingButtonConfig.MAX_PANEL_WIDTH_DP)
    return preferred
        .coerceAtMost(leavingGap)
        .coerceAtLeast(FloatingButtonConfig.MIN_PANEL_WIDTH_DP)
        .coerceAtMost(ceiling.coerceAtLeast(1))
}

/**
 * What share of the screen each plate asks for.
 *
 * A third rather than a half because the gap is the feature. Two plates at 0.32 leave 0.36 of the width
 * to the game, which on a landscape phone is enough to keep a minimap or a health bar in sight — the
 * reason to prefer this layout over one card that covers the middle of the screen.
 */
private const val SPLIT_PLATE_FRACTION = 0.32f

/**
 * The narrowest strip of game the split is allowed to aim for, and a target rather than a guarantee.
 *
 * Overruled by the plate's own floor on a narrow screen — see [splitPlateWidth], rules 2 and 3. 96 dp is
 * about a thumb and a half: enough to see something through, not enough to play through.
 *
 * `internal` rather than private so the test can state the gap rule as the inequality it is, instead of
 * repeating the figure and then failing for the wrong reason when it changes. Same reason
 * [MIN_ACTION_WIDTH_DP] is.
 */
internal const val SPLIT_MIN_GAME_DP = 96

/**
 * The plates' inset from the top and bottom of the screen.
 *
 * The window carries `FLAG_LAYOUT_NO_LIMITS`, so it extends under the status bar and into a cutout. This
 * keeps the first row of content off both without asking for insets the overlay host does not apply.
 */
private const val SPLIT_EDGE_INSET_DP = 14

/** Rounded on the inward-facing side only. See [SplitPlate]. */
private const val SPLIT_CORNER_DP = 18

/** The 48 dp minimum, less the plate padding the row already sits inside. */
private const val SPLIT_CLOSE_TOUCH_DP = 36

private const val SPLIT_CLOSE_ICON_DP = 18

/**
 * What the gap does, said once, at the bottom where it does not sit over the middle of the game.
 *
 * Needed because the gap is doing two jobs that look like one thing: it shows the game and it closes the
 * panel. Without the line the second is undiscoverable, and a user hunting for a way out taps the game —
 * which works, but only by accident.
 */
private const val SPLIT_DISMISS_HINT = "Tap here to close"
