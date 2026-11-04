package com.gamecore.core.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
 * The turbo-deck panel: one tall GAMETURBO plate anchored to the left edge, the game still visible on
 * the right.
 *
 * This is [com.gamecore.core.model.PanelLayoutStyle.TURBO_DECK], the fourth layout reference. Its
 * silhouette is the fourth design's: a near-black plate with rounded outer corners, a chamfered
 * top-right corner, a glowing cyan wordmark and a body split unevenly between a narrow rail of quick
 * tiles and a wider column of switches and a primary action. Everything under that finish is the same
 * content the other three layouts draw — [PanelStats], [PanelLevels], [MediaControls], [QuickApps] and
 * the action grid all come from the shared `internal` composables — so a control added to the panel
 * appears here too. The division of the action set below is the only local decision, and
 * [TURBO_DECK_ACTION_GROUPS] plus its test make it exhaustive: every [OverlayAction] is drawn exactly
 * once.
 *
 * The centred panel is dismissed by a touch outside its window. This one, like the two other edge
 * layouts, owns the whole screen and therefore has no outside: the strip of game on the right is the
 * dismiss target (it says so), and the header carries a close button. The plate scrolls on its own
 * because the full height of the screen is available but the content is still taller than a phone.
 *
 * The window is full screen and touchable for [OverlaySplitPanel]'s reason: Android gives one
 * rectangular window, and passing a shot through the middle of an open panel would be worse than
 * swallowing the tap. There is no resize grip, for the same reason the other two edge layouts have
 * none — the plate width is derived from the screen, not from
 * [FloatingButtonConfig.panelWidthDp]. The stored width belongs to the centred layout and is left
 * alone for when the user switches back.
 */
@Composable
fun TurboDeckPanel(
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
    // Held exactly as the other layouts hold it: a half-typed number belongs to the panel that asked
    // for it and is forgotten when that panel closes.
    var editing by remember { mutableStateOf<OverlayLevel?>(null) }
    var typed by remember { mutableStateOf("") }

    val deckWidth = turboDeckWidth(screenWidthDp)
    val contentWidth = (deckWidth - PANEL_PADDING_DP * 2).coerceAtLeast(1)

    Row(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .padding(
                    start = TURBO_DECK_EDGE_INSET_DP.dp,
                    top = TURBO_DECK_EDGE_INSET_DP.dp,
                    bottom = TURBO_DECK_EDGE_INSET_DP.dp,
                )
                .width(deckWidth.dp)
                .fillMaxHeight()
                .clip(turboDeckShape())
                .background(
                    Brush.verticalGradient(listOf(TURBO_DECK_PLATE_TOP, TURBO_DECK_PLATE_BOTTOM)),
                ),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawDeckFinish(size = size)
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PANEL_PADDING_DP.dp, vertical = TURBO_DECK_INSET_DP.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TurboDeckHeader(
                    state = state,
                    onDismiss = onDismiss,
                    compact = contentWidth < TURBO_DECK_COMPACT_HEADER_DP,
                )

                if (readings.isNotEmpty()) {
                    PanelStats(
                        readings = readings,
                        perRow = if (contentWidth < TURBO_DECK_WIDE_STATS_DP) 3 else 5,
                    )
                }

                TurboDeckQuickSplit(
                    state = state,
                    accent = accent,
                    onAction = onAction,
                    onLongAction = onLongAction,
                )

                TurboDeckSectionLabel(text = "TOOLS")
                TurboDeckActionGrid(
                    state = state,
                    accent = accent,
                    contentWidthDp = contentWidth,
                    onAction = onAction,
                    onLongAction = onLongAction,
                )
                TurboDeckWideActions(
                    state = state,
                    accent = accent,
                    onAction = onAction,
                )

                // Revealed rows stay below the grid, in the same order as the other panel layouts, so a
                // long press and the chips it opened never drift apart.
                if (state.presetsExpanded) {
                    PresetChips(state = state, accent = accent, widthDp = contentWidth, onPreset = onPreset)
                }
                if (state.aspectsExpanded) {
                    AspectChips(state = state, accent = accent, widthDp = contentWidth, onAspect = onAspect)
                }
                if (state.refreshRatesExpanded) {
                    RateChips(state = state, accent = accent, widthDp = contentWidth, onRate = onRate)
                }
                if (state.crosshairExpanded) {
                    CrosshairChips(
                        state = state,
                        accent = accent,
                        widthDp = contentWidth,
                        onCrosshairDesign = onCrosshairDesign,
                        onCrosshairColour = onCrosshairColour,
                    )
                }

                QuickApps(apps = quickApps, accent = accent, onLaunch = onLaunchApp)

                TurboDeckSectionLabel(text = "LEVELS & MEDIA")
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
                MediaControls(
                    nowPlaying = nowPlaying,
                    accent = accent,
                    widthDp = contentWidth,
                    onCommand = onMedia,
                    onEnableMedia = onEnableMedia,
                )
            }
        }

        // The game. Visible, never touch-through, and the only unmarked exit besides the header's ✕.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.BottomCenter,
        ) {
            BasicText(
                text = TURBO_DECK_DISMISS_HINT,
                maxLines = 2,
                style = TextStyle(
                    color = OverlayPalette.Absent,
                    fontSize = 8.5.sp,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.padding(bottom = TURBO_DECK_EDGE_INSET_DP.dp, start = 2.dp, end = 2.dp),
            )
        }
    }
}

/**
 * The plate: a glowing pixel wordmark, the game and session, and the visible way out.
 *
 * "Boost performance" is the design's own subtitle and stays as a strap line, while the game and the
 * session clock sit on the line below it so this surface still answers the two questions every other
 * layout's header answers — what is running, and for how long.
 */
@Composable
private fun TurboDeckHeader(
    state: OverlayPanelState,
    onDismiss: () -> Unit,
    compact: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = "GAMETURBO",
                    maxLines = 1,
                    style = TextStyle(
                        color = TURBO_DECK_CYAN_PALE,
                        fontSize = if (compact) 16.sp else 20.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = if (compact) 1.2.sp else 2.2.sp,
                        shadow = Shadow(color = TURBO_DECK_CYAN, blurRadius = 18f),
                    ),
                )
                if (!compact) {
                    TurboDeckDashAccent()
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = "BOOST PERFORMANCE",
                    maxLines = 1,
                    style = TextStyle(
                        color = TURBO_DECK_CYAN,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                    ),
                )
                Spacer(modifier = Modifier.weight(1f))
                BasicText(
                    text = state.sessionElapsed?.let { "SESSION $it" } ?: "NOT TRACKING",
                    maxLines = 1,
                    style = TextStyle(
                        color = if (state.sessionElapsed != null) OverlayPalette.Muted else OverlayPalette.Absent,
                        fontSize = 7.5.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
            Spacer(modifier = Modifier.height(1.dp))
            BasicText(
                text = state.gameLabel.ifEmpty { "GameCore" },
                maxLines = 1,
                style = TextStyle(
                    color = OverlayPalette.Muted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
        Box(
            modifier = Modifier
                .size(TURBO_DECK_CLOSE_TOUCH_DP.dp)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Close the panel",
                tint = OverlayPalette.Muted,
                modifier = Modifier.size(TURBO_DECK_CLOSE_ICON_DP.dp),
            )
        }
    }
}

/** Three slanted cyan dashes, the design reference's diagonal accent beside the wordmark. */
@Composable
private fun TurboDeckDashAccent() {
    Canvas(
        modifier = Modifier
            .padding(start = 8.dp)
            .size(width = 30.dp, height = 14.dp),
    ) {
        val stroke = 1.8.dp.toPx()
        repeat(3) { index ->
            val x = index * 9.dp.toPx()
            drawLine(
                color = TURBO_DECK_CYAN.copy(alpha = 0.35f + index * 0.18f),
                start = Offset(x + 2.dp.toPx(), size.height),
                end = Offset(x + 8.dp.toPx(), 0f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * The asymmetric body of the design: a narrow rail of two square quick tiles on the left, three switch
 * rows and the primary record button on the right.
 *
 * The two tiles are [TURBO_DECK_FEATURE_ACTIONS] — the capture pair a player reaches for mid-game —
 * and the switches are the three overlays the design's rows stood for. Splitting them out of the grid
 * is what lets those controls keep the design's larger targets without duplicating an action.
 */
@Composable
private fun TurboDeckQuickSplit(
    state: OverlayPanelState,
    accent: Color,
    onAction: (OverlayAction) -> Unit,
    onLongAction: (OverlayAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Column(
            modifier = Modifier.weight(TURBO_DECK_RAIL_WEIGHT),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TURBO_DECK_FEATURE_ACTIONS.forEach { action ->
                TurboDeckFeatureTile(
                    action = action,
                    state = state,
                    accent = accent,
                    onAction = onAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
            }
        }
        Column(
            modifier = Modifier.weight(TURBO_DECK_MAIN_WEIGHT),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            TURBO_DECK_SWITCH_ACTIONS.forEach { action ->
                TurboDeckSwitchRow(
                    action = action,
                    state = state,
                    accent = accent,
                    onAction = onAction,
                    onLongAction = onLongAction,
                )
            }
            TurboDeckPrimaryButton(
                action = TURBO_DECK_PRIMARY_ACTION,
                state = state,
                onAction = onAction,
            )
        }
    }
}

/** One square quick tile, the design's "clear memory / free storage" slot with a real action in it. */
@Composable
private fun TurboDeckFeatureTile(
    action: OverlayAction,
    state: OverlayPanelState,
    accent: Color,
    onAction: (OverlayAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val usable = state.isUsable(action)
    val active = usable && action.isToggle && state.isActive(action)
    val tint = when {
        !usable -> OverlayPalette.Absent
        active -> accent
        else -> OverlayPalette.Text
    }
    val shape = RoundedCornerShape(TURBO_DECK_TILE_CORNER_DP.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (active) accent.copy(alpha = 0.16f) else TURBO_DECK_TILE)
            .border(1.dp, if (active) accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.06f), shape)
            .clickable(enabled = usable) { onAction(action) }
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .semantics { contentDescription = deckActionDescription(action, state) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = action.icon(),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(TURBO_DECK_FEATURE_ICON_DP.dp),
        )
        Spacer(modifier = Modifier.height(4.dp))
        BasicText(
            text = action.label,
            maxLines = 1,
            style = TextStyle(
                color = tint,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            ),
        )
        BasicText(
            text = state.reasonFor(action) ?: deckFeatureCaption(action),
            maxLines = 1,
            style = TextStyle(
                color = if (usable) OverlayPalette.Muted else OverlayPalette.Absent,
                fontSize = 7.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/** One switch row: the state word is always drawn, not only the colour of the track. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TurboDeckSwitchRow(
    action: OverlayAction,
    state: OverlayPanelState,
    accent: Color,
    onAction: (OverlayAction) -> Unit,
    onLongAction: (OverlayAction) -> Unit,
) {
    val usable = state.isUsable(action)
    val active = usable && state.isActive(action)
    val tint = when {
        !usable -> OverlayPalette.Absent
        active -> accent
        else -> OverlayPalette.Text
    }
    val shape = RoundedCornerShape(TURBO_DECK_ROW_CORNER_DP.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TURBO_DECK_SWITCH_HEIGHT_DP.dp)
            .clip(shape)
            .background(if (active) accent.copy(alpha = 0.11f) else Color.White.copy(alpha = 0.035f))
            .border(1.dp, if (active) accent.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.06f), shape)
            .combinedClickable(
                enabled = usable,
                onLongClick = if (action.heldRow != null) ({ onLongAction(action) }) else null,
                onClick = { onAction(action) },
            )
            .padding(horizontal = 8.dp, vertical = 7.dp)
            .semantics { contentDescription = deckActionDescription(action, state) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = action.icon(),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(TURBO_DECK_SWITCH_ICON_DP.dp),
        )
        Spacer(modifier = Modifier.width(7.dp))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = action.label,
                maxLines = 1,
                style = TextStyle(
                    color = tint,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            BasicText(
                text = state.reasonFor(action) ?: if (active) "ON" else "OFF",
                maxLines = 1,
                style = TextStyle(
                    color = if (!usable) OverlayPalette.Absent else if (active) accent else OverlayPalette.Muted,
                    fontSize = 7.5.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.6.sp,
                ),
            )
        }
        TurboDeckToggleTrack(active = active, usable = usable, accent = accent)
    }
}

/** The switch itself, drawn rather than borrowed from Material so it keeps the deck's small scale. */
@Composable
private fun TurboDeckToggleTrack(active: Boolean, usable: Boolean, accent: Color) {
    val track = when {
        !usable -> Color.White.copy(alpha = 0.05f)
        active -> accent
        else -> Color.White.copy(alpha = 0.10f)
    }
    Box(
        modifier = Modifier
            .width(TURBO_DECK_TRACK_WIDTH_DP.dp)
            .height(TURBO_DECK_TRACK_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(50))
            .background(track)
            .border(1.dp, if (active) accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
            .padding(2.dp),
        contentAlignment = if (active) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(TURBO_DECK_TRACK_KNOB_DP.dp)
                .background(if (active) TURBO_DECK_INK else OverlayPalette.Muted, CircleShape),
        )
    }
}

/**
 * The design's glossy primary button, bound to [OverlayAction.RECORD].
 *
 * It is a real action rather than a decorative "boost": the label changes to Stop while a recording is
 * running, and an unsupported capture path draws the button dimmed with the reason instead of promising
 * something the device will refuse. The cyan gradient is the design's; the words stay the action's.
 */
@Composable
private fun TurboDeckPrimaryButton(
    action: OverlayAction,
    state: OverlayPanelState,
    onAction: (OverlayAction) -> Unit,
) {
    val usable = state.isUsable(action)
    val active = usable && state.isActive(action)
    val shape = RoundedCornerShape(TURBO_DECK_PRIMARY_CORNER_DP.dp)
    val fill = when {
        !usable -> Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.03f)))
        active -> Brush.horizontalGradient(listOf(TURBO_DECK_CYAN_DEEP, TURBO_DECK_CYAN))
        else -> Brush.horizontalGradient(listOf(TURBO_DECK_CYAN, TURBO_DECK_CYAN_PALE))
    }
    val content = if (usable) TURBO_DECK_INK else OverlayPalette.Absent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TURBO_DECK_PRIMARY_HEIGHT_DP.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, if (usable) TURBO_DECK_CYAN_PALE.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.05f), shape)
            .clickable(enabled = usable) { onAction(action) }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = deckActionDescription(action, state) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (active) Icons.Rounded.FiberManualRecord else Icons.Rounded.Bolt,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(TURBO_DECK_PRIMARY_ICON_DP.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = if (active) "Stop recording" else "Record gameplay",
                maxLines = 1,
                style = TextStyle(
                    color = content,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                ),
            )
            BasicText(
                text = state.reasonFor(action) ?: if (active) "Recording now" else "Boost performance",
                maxLines = 1,
                style = TextStyle(
                    color = content.copy(alpha = 0.72f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
        if (active) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(OverlayPalette.Danger, CircleShape),
            )
        }
    }
}

/** The design's equal bottom tiles: every action not already promoted above is drawn here once. */
@Composable
private fun TurboDeckActionGrid(
    state: OverlayPanelState,
    accent: Color,
    contentWidthDp: Int,
    onAction: (OverlayAction) -> Unit,
    onLongAction: (OverlayAction) -> Unit,
) {
    val perRow = actionsPerRow(contentWidthDp)
    Column(verticalArrangement = Arrangement.spacedBy(ACTION_GAP_DP.dp)) {
        TURBO_DECK_GRID_ACTIONS.chunked(perRow).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ACTION_GAP_DP.dp),
            ) {
                row.forEach { action ->
                    TurboDeckActionTile(
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

/** One square glass tool tile. The three states are the same ones every layout draws. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TurboDeckActionTile(
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
    val shape = RoundedCornerShape(TURBO_DECK_TILE_CORNER_DP.dp)
    Column(
        modifier = modifier
            .heightIn(min = TURBO_DECK_ACTION_TILE_DP.dp)
            .clip(shape)
            .background(if (active) accent.copy(alpha = 0.13f) else TURBO_DECK_TILE)
            .border(1.dp, if (active) accent.copy(alpha = 0.50f) else Color.White.copy(alpha = 0.06f), shape)
            .combinedClickable(
                enabled = usable,
                onLongClick = if (action.heldRow != null) ({ onLongAction(action) }) else null,
                onClick = { onAction(action) },
            )
            .padding(horizontal = 3.dp, vertical = 7.dp)
            .semantics { contentDescription = deckActionDescription(action, state) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = action.icon(),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(TURBO_DECK_ACTION_ICON_DP.dp),
        )
        BasicText(
            text = action.label,
            maxLines = 2,
            style = TextStyle(
                color = tint,
                fontSize = 9.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            ),
        )
        state.reasonFor(action)?.let { reason ->
            BasicText(
                text = reason,
                maxLines = 1,
                style = TextStyle(
                    color = OverlayPalette.Absent,
                    fontSize = 7.sp,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

/**
 * The two wide buttons at the bottom of the design, bound to the two actions that change the session's
 * context rather than the device: ending it, and opening GameCore.
 */
@Composable
private fun TurboDeckWideActions(
    state: OverlayPanelState,
    accent: Color,
    onAction: (OverlayAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ACTION_GAP_DP.dp),
    ) {
        TURBO_DECK_WIDE_ACTIONS.forEach { action ->
            TurboDeckWideButton(
                action = action,
                state = state,
                accent = accent,
                onAction = onAction,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** A wide outlined button, sized for a thumb rather than for the action grid's square rhythm. */
@Composable
private fun TurboDeckWideButton(
    action: OverlayAction,
    state: OverlayPanelState,
    accent: Color,
    onAction: (OverlayAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val usable = state.isUsable(action)
    val tint = when {
        !usable -> OverlayPalette.Absent
        action == OverlayAction.STOP_SESSION -> OverlayPalette.Danger
        else -> OverlayPalette.Text
    }
    val shape = RoundedCornerShape(TURBO_DECK_ROW_CORNER_DP.dp)
    Row(
        modifier = modifier
            .heightIn(min = TURBO_DECK_WIDE_HEIGHT_DP.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, if (usable) accent.copy(alpha = 0.34f) else Color.White.copy(alpha = 0.05f), shape)
            .clickable(enabled = usable) { onAction(action) }
            .padding(horizontal = 9.dp, vertical = 7.dp)
            .semantics { contentDescription = deckActionDescription(action, state) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = action.icon(),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(TURBO_DECK_WIDE_ICON_DP.dp),
        )
        Spacer(modifier = Modifier.width(7.dp))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = action.label,
                maxLines = 1,
                style = TextStyle(
                    color = tint,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            state.reasonFor(action)?.let { reason ->
                BasicText(
                    text = reason,
                    maxLines = 1,
                    style = TextStyle(
                        color = OverlayPalette.Absent,
                        fontSize = 7.sp,
                    ),
                )
            }
        }
    }
}

/** A section label with the design's short cyan tick and a hairline that fades out to the right. */
@Composable
private fun TurboDeckSectionLabel(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(10.dp)
                .background(TURBO_DECK_CYAN, RoundedCornerShape(2.dp)),
        )
        BasicText(
            text = text,
            maxLines = 1,
            style = TextStyle(
                color = OverlayPalette.Muted,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
            ),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(TURBO_DECK_CYAN.copy(alpha = 0.22f), Color.Transparent),
                    ),
                ),
        )
    }
}

/** The plate's cyan edge light, inner rim, and the diagonal vent dashes from the reference. */
private fun DrawScope.drawDeckFinish(size: Size) {
    val path = deckPath(size)
    // A cool rim keeps the near-black plate off a dark game without boxing it in.
    drawPath(
        path = path,
        color = Color.White.copy(alpha = 0.055f),
        style = Stroke(width = 1.dp.toPx()),
    )
    // The right edge is the lit one in the design: a single cyan filament falling away at both ends.
    drawLine(
        brush = Brush.verticalGradient(
            listOf(Color.Transparent, TURBO_DECK_CYAN.copy(alpha = 0.55f), Color.Transparent),
        ),
        start = Offset(size.width - 0.8.dp.toPx(), size.height * 0.12f),
        end = Offset(size.width - 0.8.dp.toPx(), size.height * 0.88f),
        strokeWidth = 1.4.dp.toPx(),
    )
    drawLine(
        brush = Brush.horizontalGradient(
            listOf(TURBO_DECK_CYAN.copy(alpha = 0.38f), Color.Transparent),
        ),
        start = Offset(size.width * 0.04f, 0.8.dp.toPx()),
        end = Offset(size.width * 0.62f, 0.8.dp.toPx()),
        strokeWidth = 1.dp.toPx(),
    )

    // Three slanted vents under the chamfer, at a much lower alpha than the wordmark accent so they
    // read as texture rather than as another control.
    repeat(3) { index ->
        val x = size.width - 18.dp.toPx() - index * 9.dp.toPx()
        drawLine(
            color = TURBO_DECK_CYAN.copy(alpha = 0.08f + index * 0.03f),
            start = Offset(x, size.height * 0.20f),
            end = Offset(x + 18.dp.toPx(), size.height * 0.20f - 18.dp.toPx()),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }

    // A faint wash from the lit edge, so the plate is lit rather than only outlined.
    drawRect(
        brush = Brush.horizontalGradient(
            0.72f to Color.Transparent,
            1f to TURBO_DECK_CYAN.copy(alpha = 0.045f),
        ),
    )
}

/** The deck silhouette: rounded outer corners and the design's chamfered top-right. */
internal fun turboDeckShape(): Shape = GenericShape { size, _ ->
    val path = deckPath(size)
    moveTo(path.getBounds().left, path.getBounds().top)
    addPath(path)
}

/** The same silhouette as a path, for both the clip and the finish pass. */
private fun deckPath(size: Size): Path = Path().apply {
    val radius = size.minDimension * TURBO_DECK_CORNER_FRACTION
    val chamfer = size.width * TURBO_DECK_CHAMFER_FRACTION
    moveTo(radius, 0f)
    lineTo(size.width - chamfer, 0f)
    lineTo(size.width, chamfer)
    lineTo(size.width, size.height - radius)
    quadraticTo(size.width, size.height, size.width - radius, size.height)
    lineTo(radius, size.height)
    quadraticTo(0f, size.height, 0f, size.height - radius)
    lineTo(0f, radius)
    quadraticTo(0f, 0f, radius, 0f)
    close()
}

/** The spoken sentence for a tile, including the reason when the action cannot run. */
private fun deckActionDescription(action: OverlayAction, state: OverlayPanelState): String {
    val reason = state.reasonFor(action)
    val active = action.isToggle && state.isActive(action)
    return buildString {
        append(action.label)
        if (action.isToggle) append(if (active) ", on" else ", off")
        if (reason != null) append(", unavailable: ").append(reason)
    }
}

/** The two lines under a quick tile's label, so the tile says what its action will do. */
private fun deckFeatureCaption(action: OverlayAction): String = when (action) {
    OverlayAction.SCREENSHOT -> "Save frame"
    OverlayAction.REPLAY -> "Last seconds"
    else -> ""
}

/**
 * How wide the deck plate is on a screen [screenWidthDp] wide.
 *
 * The reference takes about 85% of the screen, and the remaining strip is the point of the layout, so
 * three bounds apply in this order:
 *
 * 1. [TURBO_DECK_WIDTH_FRACTION] of the screen is the target.
 * 2. Narrowed so the edge inset plus [TURBO_DECK_MIN_GAME_DP] of game always remains.
 * 3. Floored at [FloatingButtonConfig.MIN_PANEL_WIDTH_DP] where the screen can hold it, because below
 *    that the action grid drops to one tile per row; and capped at [TURBO_DECK_MAX_DP] so a tablet does
 *    not get a hand's width of plate.
 *
 * Unlike the centred panel this does not read [FloatingButtonConfig.panelWidthDp]: that figure is the
 * centred layout's own and is kept untouched for when the user switches back.
 */
internal fun turboDeckWidth(screenWidthDp: Int): Int {
    val preferred = (screenWidthDp * TURBO_DECK_WIDTH_FRACTION).roundToInt()
    val leavingGame = screenWidthDp - TURBO_DECK_EDGE_INSET_DP - TURBO_DECK_MIN_GAME_DP
    val ceiling = minOf(leavingGame.coerceAtLeast(1), TURBO_DECK_MAX_DP)
    val floor = minOf(FloatingButtonConfig.MIN_PANEL_WIDTH_DP, ceiling)
    return preferred.coerceAtMost(ceiling).coerceAtLeast(floor).coerceAtLeast(1)
}

/**
 * The action set this layout divides between its feature rail, switch rows, primary button, grid and
 * wide footer — every [OverlayAction] exactly once.
 *
 * A list rather than five copies of the grid, because the deck is not one flat grid: the design's
 * composition promotes six controls out of it. The test holds the union to [OverlayAction.entries], so
 * an action added later cannot silently lose its only tile on this layout.
 */
internal val TURBO_DECK_ACTION_GROUPS: List<List<OverlayAction>> = listOf(
    listOf(OverlayAction.SCREENSHOT, OverlayAction.REPLAY),
    listOf(OverlayAction.PILL, OverlayAction.CROSSHAIR, OverlayAction.HUD),
    listOf(OverlayAction.RECORD),
    listOf(
        OverlayAction.MAGNIFIER,
        OverlayAction.SCOUT,
        OverlayAction.HUNT,
        OverlayAction.WHEEL,
        OverlayAction.FLASHLIGHT,
        OverlayAction.DO_NOT_DISTURB,
        OverlayAction.ROTATION_LOCK,
        OverlayAction.COLOR,
        OverlayAction.ASPECT,
        OverlayAction.REFRESH_RATE,
        OverlayAction.PANEL_LAYOUT,
    ),
    listOf(OverlayAction.STOP_SESSION, OverlayAction.OPEN_APP),
)

private val TURBO_DECK_FEATURE_ACTIONS = TURBO_DECK_ACTION_GROUPS[0]
private val TURBO_DECK_SWITCH_ACTIONS = TURBO_DECK_ACTION_GROUPS[1]
private val TURBO_DECK_PRIMARY_ACTION = TURBO_DECK_ACTION_GROUPS[2].single()
private val TURBO_DECK_GRID_ACTIONS = TURBO_DECK_ACTION_GROUPS[3]
private val TURBO_DECK_WIDE_ACTIONS = TURBO_DECK_ACTION_GROUPS[4]

/** About 85% of the screen, the reference's proportion. */
private const val TURBO_DECK_WIDTH_FRACTION = 0.85f

/** A tablet plate stops growing here; the rest is game. */
internal const val TURBO_DECK_MAX_DP = 560

/** The strip of game the plate leaves on the right, as a target rather than a guarantee. */
internal const val TURBO_DECK_MIN_GAME_DP = 44

/** The margin between the plate and the left, top and bottom edges. */
internal const val TURBO_DECK_EDGE_INSET_DP = 8

/** Inner padding, separate from the edge inset so the plate keeps a dark border around its content. */
private const val TURBO_DECK_INSET_DP = 11

/** Below this content width the wordmark shrinks and drops its dash accent so the close button fits. */
private const val TURBO_DECK_COMPACT_HEADER_DP = 238

/** Below this content width the stats row splits rather than tightening past readability. */
private const val TURBO_DECK_WIDE_STATS_DP = 262

/** The rail/main split from the reference: roughly 28% and 72%. */
private const val TURBO_DECK_RAIL_WEIGHT = 0.30f
private const val TURBO_DECK_MAIN_WEIGHT = 0.70f

/** The 48 dp minimum, less the header's own inset. */
private const val TURBO_DECK_CLOSE_TOUCH_DP = 36
private const val TURBO_DECK_CLOSE_ICON_DP = 18

private const val TURBO_DECK_TILE_CORNER_DP = 12
private const val TURBO_DECK_ROW_CORNER_DP = 10
private const val TURBO_DECK_PRIMARY_CORNER_DP = 14

private const val TURBO_DECK_FEATURE_ICON_DP = 22
private const val TURBO_DECK_SWITCH_ICON_DP = 16
private const val TURBO_DECK_ACTION_ICON_DP = 19
private const val TURBO_DECK_PRIMARY_ICON_DP = 20
private const val TURBO_DECK_WIDE_ICON_DP = 17

private const val TURBO_DECK_SWITCH_HEIGHT_DP = 42
private const val TURBO_DECK_ACTION_TILE_DP = 62
private const val TURBO_DECK_PRIMARY_HEIGHT_DP = 54
private const val TURBO_DECK_WIDE_HEIGHT_DP = 44

private const val TURBO_DECK_TRACK_WIDTH_DP = 34
private const val TURBO_DECK_TRACK_HEIGHT_DP = 18
private const val TURBO_DECK_TRACK_KNOB_DP = 12

/** The corner radius and chamfer as fractions of the plate, so the shape needs no density. */
private const val TURBO_DECK_CORNER_FRACTION = 0.045f
private const val TURBO_DECK_CHAMFER_FRACTION = 0.07f

/** What the visible strip of game does, said where it does not cover the middle of the game. */
private const val TURBO_DECK_DISMISS_HINT = "Tap here to close"

private val TURBO_DECK_PLATE_TOP = Color(0xF512121A)
private val TURBO_DECK_PLATE_BOTTOM = Color(0xF50A0A10)
private val TURBO_DECK_TILE = Color(0xFF161622)
private val TURBO_DECK_CYAN = Color(0xFF00AAFF)
private val TURBO_DECK_CYAN_PALE = Color(0xFF8BE9FF)
private val TURBO_DECK_CYAN_DEEP = Color(0xFF0066B8)
private val TURBO_DECK_INK = Color(0xFF04121D)
