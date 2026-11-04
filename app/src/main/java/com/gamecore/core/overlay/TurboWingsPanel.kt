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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
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
import com.gamecore.core.model.TurboGaugeChoice
import com.gamecore.domain.media.MediaCommand
import com.gamecore.domain.media.NowPlaying
import com.gamecore.domain.monitoring.StatReading
import com.gamecore.domain.overlay.QuickApp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The turbo-wings panel: two angled plates fold in from the screen's edges, the game stays visible between
 * them, and each plate carries one of the configurable 3D gauges.
 *
 * This is [com.gamecore.core.model.PanelLayoutStyle.TURBO_WINGS], the shape a fresh install now opens.
 * It is a separate composable from [OverlaySplitPanel] because the two
 * disagree about their silhouette and their centre of gravity: the split plates are rectangles attached to
 * the edges, while these are bent blades with the controls arranged around a gauge the way the turbo panels
 * on gaming phones arrange theirs. What they share is the content, and that is shared for real — every
 * control below is the same `internal` composable the other layouts draw, so an action added to the
 * grid appears in every layout or in none.
 *
 * The window is full-screen and touchable, exactly as the split layout's is. The game in the middle is
 * visible but not touch-through: Android gives one rectangular window here, and a game receiving a shot
 * through the middle of an open panel is worse than a tap that closes it. The centre gap is the dismiss
 * target, the header carries a close button, and there is no resize grip because both plate widths are
 * derived from the screen rather than from [FloatingButtonConfig.panelWidthDp].
 *
 * ### The gauges
 *
 * The left and right rings are not decorative duplicates. Each is bound to a [TurboGaugeChoice] the user
 * chose, and that choice changes both the reading and the 3D structure of the object:
 *
 *  - [TurboGaugeChoice.CPU] draws an amber segmented ring with circuit pads.
 *  - [TurboGaugeChoice.GPU] draws a cyan faceted ring whose individual facets light up.
 *  - [TurboGaugeChoice.RAM] draws a green stack of four memory bands.
 *  - [TurboGaugeChoice.TEMPERATURE] draws a heat ring with hot ticks and a glowing end bead.
 *
 * The number inside the ring is always the one [StatReading] handed down, or "n/a" with the reason under
 * it. There is no default zero, no synthetic clock and no invented figure: a stat this device cannot read
 * shows the empty track and the explanation, which is the same rule the stats pill and the HUD follow.
 *
 * Tapping the caption pill under a ring cycles that side's choice, so the gauge is configurable in the
 * game as well as in the settings screen. The choice is stored on [FloatingButtonConfig] and applied by
 * the same preference write the settings row uses.
 */
@Composable
fun TurboWingsPanel(
    state: OverlayPanelState,
    readings: List<StatReading>,
    gaugeReadings: Map<TurboGaugeChoice, StatReading>,
    leftGauge: TurboGaugeChoice,
    rightGauge: TurboGaugeChoice,
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
    onLeftGaugeChange: (TurboGaugeChoice) -> Unit,
    onRightGaugeChange: (TurboGaugeChoice) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The keypad's state, held exactly as the other layouts hold it: a half-typed number is not a
    // fact about the device and is meant to be forgotten when the panel closes.
    var editing by remember { mutableStateOf<OverlayLevel?>(null) }
    var typed by remember { mutableStateOf("") }

    val wingWidth = turboWingWidth(screenWidthDp)
    val contentWidth = turboWingContentWidth(wingWidth)

    Row(modifier = modifier.fillMaxSize()) {
        // ---------------------------------------------------------------- left wing: readouts and levels
        TurboWing(widthDp = wingWidth, contentWidthDp = contentWidth, isLeft = true) {
            TurboWingHeader(
                state = state,
                accent = accent,
                onDismiss = onDismiss,
                compact = contentWidth < TURBO_COMPACT_HEADER_DP,
            )
            TurboGauge(
                choice = leftGauge,
                reading = gaugeReadings[leftGauge],
                accent = accent,
                onCycle = { onLeftGaugeChange(leftGauge.next()) },
            )
            if (readings.isNotEmpty()) {
                PanelStats(
                    readings = readings,
                    // Five figures and four gaps need about 160 dp of plate. A portrait wing has less,
                    // so the row splits rather than clipping the last reading behind the bent edge.
                    perRow = if (contentWidth < TURBO_WIDE_STATS_DP) 3 else TURBO_STATS_PER_ROW,
                )
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
            // The pad opens under the readout that opened it, on the same side of the screen, for the
            // same reason the split layout puts it here: a number pad is a reply to a tap, and putting the
            // reply an arm's width away means looking for it.
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

        // ---------------------------------------------------------------- the game, and the way out
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                // A ripple the width of the game would be a flash over what the player is watching, so
                // this is a bare tap detector rather than `clickable`.
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.BottomCenter,
        ) {
            BasicText(
                text = TURBO_DISMISS_HINT,
                maxLines = 2,
                style = TextStyle(
                    color = OverlayPalette.Absent,
                    fontSize = 8.5.sp,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.padding(bottom = TURBO_EDGE_INSET_DP.dp, start = 4.dp, end = 4.dp),
            )
        }

        // ---------------------------------------------------------------- right wing: actions and apps
        TurboWing(widthDp = wingWidth, contentWidthDp = contentWidth, isLeft = false) {
            TurboGauge(
                choice = rightGauge,
                reading = gaugeReadings[rightGauge],
                accent = accent,
                onCycle = { onRightGaugeChange(rightGauge.next()) },
            )
            TurboSectionLabel(text = "GAME TURBO", accent = accent)
            TurboActionGrid(
                state = state,
                accent = accent,
                widthDp = contentWidth,
                onAction = onAction,
                onLongAction = onLongAction,
            )
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
        }
    }
}

/**
 * One angled wing: a dark plate with a bent inner edge, an amber edge light and an independently
 * scrolling column of content.
 *
 * The content is laid out on a narrower column than the plate and aligned to the outer edge. That is what
 * keeps the bent silhouette from clipping the last row of the action grid: the plate can cut inward, but
 * the controls never follow it into the cut. The same arithmetic is why [turboWingContentWidth] has a
 * floor that keeps two action tiles readable even on the narrowest screen.
 */
@Composable
private fun TurboWing(
    widthDp: Int,
    contentWidthDp: Int,
    isLeft: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val narrow = contentWidthDp > widthDp - TURBO_NARROW_MARGIN_DP
    val shape = remember(isLeft, narrow) { turboWingShape(isLeft = isLeft, narrow = narrow) }
    Box(
        modifier = Modifier
            .width(widthDp.dp)
            .fillMaxHeight()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(TURBO_PLATE_TOP, TURBO_PLATE_BOTTOM),
                ),
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawWingFinish(
                shape = turboWingPath(size = size, isLeft = isLeft, narrow = narrow),
                size = size,
                isLeft = isLeft,
                narrow = narrow,
            )
        }
        Column(
            modifier = Modifier
                .align(if (isLeft) Alignment.CenterStart else Alignment.CenterEnd)
                .width(contentWidthDp.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = PANEL_PADDING_DP.dp,
                    end = PANEL_PADDING_DP.dp,
                    top = TURBO_EDGE_INSET_DP.dp,
                    bottom = TURBO_EDGE_INSET_DP.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/**
 * The plate's inner-edge light and surface texture.
 *
 * Drawn over the plate rather than as a border around it because the light is not uniform: it follows the
 * bent edge, brightest at the two blade tips, and fades into the plate at the top and bottom. A
 * `Modifier.border` would trace the whole silhouette in one flat colour and lose exactly the detail that
 * makes the shape read as bent metal.
 */
private fun DrawScope.drawWingFinish(shape: Path, size: Size, isLeft: Boolean, narrow: Boolean) {
    // The hot edge, from the top corner down to the tapered bottom.
    val edge = turboWingEdgePath(size = size, isLeft = isLeft, narrow = narrow)
    // A wide, faint bloom first, then the filament itself, then a near-white core. Three strokes rather
    // than a blur: RenderEffect needs API 31 and an overlay that changes appearance by OS version is one
    // more thing to explain.
    drawPath(
        path = edge,
        color = TURBO_AMBER.copy(alpha = 0.10f),
        style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(
        path = edge,
        color = TURBO_AMBER.copy(alpha = 0.45f),
        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(
        path = edge,
        color = TURBO_AMBER_PALE.copy(alpha = 0.92f),
        style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    // An amber wash inside the edge, so the plate is lit from the game rather than merely outlined.
    clipPath(shape) {
        drawRect(
            brush = if (isLeft) {
                Brush.horizontalGradient(
                    0.62f to Color.Transparent,
                    1f to TURBO_AMBER.copy(alpha = 0.12f),
                )
            } else {
                Brush.horizontalGradient(
                    0f to TURBO_AMBER.copy(alpha = 0.12f),
                    0.38f to Color.Transparent,
                )
            },
        )
        // Diagonal vent slashes in the lower outer corner. They are the same accent at a much lower
        // alpha, so they read as texture rather than as another control.
        val originX = if (isLeft) 0.02f else 0.98f
        val direction = if (isLeft) 1f else -1f
        for (index in 0..5) {
            val x = size.width * originX + direction * index * 13.dp.toPx()
            drawLine(
                color = TURBO_AMBER.copy(alpha = 0.045f),
                start = Offset(x, size.height * 0.68f),
                end = Offset(x + direction * 34.dp.toPx(), size.height),
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }

    // A thin cool rim keeps the plate from disappearing where the wash is absent.
    drawPath(
        path = shape,
        color = Color.White.copy(alpha = 0.06f),
        style = Stroke(width = 1.dp.toPx()),
    )
}

/** The header on the left wing: a wordmark, the game and session, and the one visible way out. */
@Composable
private fun TurboWingHeader(
    state: OverlayPanelState,
    accent: Color,
    onDismiss: () -> Unit,
    compact: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = TURBO_AMBER,
                    modifier = Modifier.size(if (compact) 15.dp else 17.dp),
                )
                Spacer(modifier = Modifier.width(3.dp))
                BasicText(
                    text = "GAMECORE",
                    maxLines = 1,
                    style = TextStyle(
                        color = OverlayPalette.Text,
                        fontSize = if (compact) 11.sp else 14.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = if (compact) 0.9.sp else 1.8.sp,
                    ),
                )
                if (!compact) {
                    Spacer(modifier = Modifier.width(5.dp))
                    BasicText(
                        text = "TURBO",
                        maxLines = 1,
                        style = TextStyle(
                            color = TURBO_AMBER,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                        ),
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            PanelHeader(state = state, accent = accent)
        }
        Box(
            modifier = Modifier
                .size(TURBO_CLOSE_TOUCH_DP.dp)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Close the panel",
                tint = OverlayPalette.Muted,
                modifier = Modifier.size(TURBO_CLOSE_ICON_DP.dp),
            )
        }
    }
}

/** A small uppercase label with an accent tick, used to separate the grid from the gauge above it. */
@Composable
private fun TurboSectionLabel(text: String, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(10.dp)
                .background(accent, RoundedCornerShape(2.dp)),
        )
        BasicText(
            text = text,
            style = TextStyle(
                color = OverlayPalette.Muted,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
            ),
        )
    }
}

/**
 * The 3D gauge: a bevelled ring drawn around a reading, one drawn structure per [TurboGaugeChoice].
 *
 * The caption under it is a real control. Tapping it cycles the choice, which is why it is drawn as a
 * pill with a highlight rather than as a static subtitle — the same choice is on the settings card, and
 * this is the path that does not require leaving the game.
 */
@Composable
internal fun TurboGauge(
    choice: TurboGaugeChoice,
    reading: StatReading?,
    accent: Color,
    onCycle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = gaugeTint(choice)
    val fraction = gaugeFraction(choice, reading)
    val available = reading?.isAvailable == true
    val unit = reading?.stat?.unit.orEmpty()
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TURBO_GAUGE_HEIGHT_DP.dp)
                .semantics {
                    contentDescription = gaugeDescription(choice, reading)
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawTurboGaugeRing(
                    choice = choice,
                    fraction = fraction,
                    tint = tint,
                    accent = accent,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicText(
                        text = reading?.display(withUnit = false) ?: StatReading.PLACEHOLDER,
                        maxLines = 1,
                        style = TextStyle(
                            color = if (available) OverlayPalette.Text else OverlayPalette.Absent,
                            fontSize = if (available) 25.sp else 17.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                    if (available && unit.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(2.dp))
                        BasicText(
                            text = unit,
                            maxLines = 1,
                            style = TextStyle(
                                color = OverlayPalette.Muted,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                    }
                }
                BasicText(
                    text = choice.label.uppercase(),
                    maxLines = 1,
                    style = TextStyle(
                        color = tint,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.8.sp,
                    ),
                )
            }
        }
        BasicText(
            text = choice.designLabel,
            maxLines = 2,
            style = TextStyle(
                color = tint,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(tint.copy(alpha = 0.10f))
                .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(50))
                .clickable(onClick = onCycle)
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .semantics {
                    contentDescription = "Gauge set to ${choice.label}. Tap to change it."
                },
        )
        reading?.reason?.takeIf { !available }?.let { reason ->
            Spacer(modifier = Modifier.height(3.dp))
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
 * The ring, its bevel and its choice-specific structure.
 *
 * All four choices share the same outer silhouette — a recessed dark face, a 270° open track and a bright
 * inner rim — because the gauge has to remain recognisably the same instrument whichever reading it shows.
 * What changes is how the lit part is built: separate circuit pads, glass facets, stacked memory bands or
 * a heat gradient. That is the "each option has its own 3D design" requirement expressed as drawing code
 * rather than as four unrelated illustrations.
 */
private fun DrawScope.drawTurboGaugeRing(
    choice: TurboGaugeChoice,
    fraction: Float,
    tint: Color,
    accent: Color,
) {
    val side = min(size.width, size.height)
    val center = Offset(size.width / 2f, size.height / 2f)
    val outer = side * 0.455f
    val stroke = side * 0.105f
    val radius = outer - stroke / 2f
    val arcTopLeft = Offset(center.x - radius, center.y - radius)
    val arcSize = Size(radius * 2f, radius * 2f)

    // Depth: a shadow cast down and to the right, then the raised outer rim, then the recessed face.
    drawCircle(
        color = Color.Black.copy(alpha = 0.55f),
        radius = outer + 1.5.dp.toPx(),
        center = center + Offset(0f, 3.dp.toPx()),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF202831), Color(0xFF080B0F)),
            center = center - Offset(side * 0.12f, side * 0.12f),
            radius = outer,
        ),
        radius = outer,
        center = center,
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.12f),
        radius = outer - 0.5.dp.toPx(),
        center = center,
        style = Stroke(width = 1.2.dp.toPx()),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF111820), Color(0xFF030507)),
            center = center - Offset(0f, outer * 0.20f),
            radius = outer * 0.95f,
        ),
        radius = (radius - stroke * 0.52f).coerceAtLeast(1f),
        center = center,
    )
    drawCircle(
        color = tint.copy(alpha = 0.18f),
        radius = (radius - stroke * 0.52f).coerceAtLeast(1f),
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )

    // The unlit groove every design sits in.
    drawArc(
        color = TURBO_TRACK,
        startAngle = GAUGE_START_ANGLE,
        sweepAngle = GAUGE_SWEEP_ANGLE,
        useCenter = false,
        topLeft = arcTopLeft,
        size = arcSize,
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )

    when (choice) {
        TurboGaugeChoice.CPU -> drawCpuRing(center, radius, stroke, fraction)
        TurboGaugeChoice.GPU -> drawGpuRing(center, radius, stroke, fraction)
        TurboGaugeChoice.RAM -> drawRamRing(center, radius, stroke, fraction)
        TurboGaugeChoice.TEMPERATURE -> drawHeatRing(center, radius, stroke, fraction)
    }

    // A single specular pass over all four, so the material reads as glass over metal rather than as
    // four unrelated flat fills.
    drawArc(
        color = Color.White.copy(alpha = 0.16f),
        startAngle = 202f,
        sweepAngle = 46f,
        useCenter = false,
        topLeft = arcTopLeft,
        size = arcSize,
        style = Stroke(width = stroke * 0.18f, cap = StrokeCap.Round),
    )
    drawArc(
        color = accent.copy(alpha = 0.08f),
        startAngle = GAUGE_START_ANGLE,
        sweepAngle = GAUGE_SWEEP_ANGLE * fraction.coerceIn(0f, 1f),
        useCenter = false,
        topLeft = arcTopLeft,
        size = arcSize,
        style = Stroke(width = stroke * 1.35f, cap = StrokeCap.Round),
    )
}

/** Amber circuit ring: 32 separated pads with radial traces and bright pads at the lit end. */
private fun DrawScope.drawCpuRing(center: Offset, radius: Float, stroke: Float, fraction: Float) {
    val segments = 32
    val gap = 1.3f
    val step = GAUGE_SWEEP_ANGLE / segments
    val arcTopLeft = Offset(center.x - radius, center.y - radius)
    val arcSize = Size(radius * 2f, radius * 2f)
    for (index in 0 until segments) {
        val lit = fraction * segments >= index + 1
        val colour = if (lit) {
            lerp(TURBO_AMBER_DEEP, TURBO_AMBER_PALE, index / (segments - 1f))
        } else {
            TURBO_TRACK
        }
        drawArc(
            color = colour,
            startAngle = GAUGE_START_ANGLE + index * step + gap / 2f,
            sweepAngle = step - gap,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt),
        )
    }

    // Circuit traces sit outside the rim, one every 22.5°, and only light where the ring has filled.
    for (index in 0..15) {
        val degrees = GAUGE_START_ANGLE + index * (GAUGE_SWEEP_ANGLE / 15f)
        val lit = fraction * 15f >= index
        val inner = polar(center, radius + stroke * 0.55f, degrees)
        val outer = polar(center, radius + stroke * 0.82f, degrees)
        drawLine(
            color = if (lit) TURBO_AMBER.copy(alpha = 0.85f) else TURBO_TRACK,
            start = inner,
            end = outer,
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
        if (lit) {
            drawCircle(
                color = TURBO_AMBER_PALE,
                radius = 1.6.dp.toPx(),
                center = outer,
            )
        }
    }

    // A thin inner bevel, offset from the pads so the ring looks milled rather than painted.
    drawArc(
        color = Color.White.copy(alpha = 0.20f),
        startAngle = GAUGE_START_ANGLE,
        sweepAngle = GAUGE_SWEEP_ANGLE * fraction.coerceIn(0f, 1f),
        useCenter = false,
        topLeft = Offset(center.x - radius + stroke * 0.32f, center.y - radius + stroke * 0.32f),
        size = Size((radius - stroke * 0.32f) * 2f, (radius - stroke * 0.32f) * 2f),
        style = Stroke(width = stroke * 0.10f, cap = StrokeCap.Round),
    )
}

/** Cyan facet ring: fourteen glass facets, each lit or dark as one piece. */
private fun DrawScope.drawGpuRing(center: Offset, radius: Float, stroke: Float, fraction: Float) {
    val facets = 14
    val gap = 1.6f
    val step = GAUGE_SWEEP_ANGLE / facets
    val outerRadius = radius + stroke / 2f
    val innerRadius = radius - stroke / 2f
    for (index in 0 until facets) {
        val lit = fraction * facets >= index + 1
        val start = GAUGE_START_ANGLE + index * step + gap / 2f
        val end = start + step - gap
        val outerStart = polar(center, outerRadius, start)
        val outerEnd = polar(center, outerRadius, end)
        val innerEnd = polar(center, innerRadius, end)
        val innerStart = polar(center, innerRadius, start)
        val path = Path().apply {
            moveTo(outerStart.x, outerStart.y)
            lineTo(outerEnd.x, outerEnd.y)
            lineTo(innerEnd.x, innerEnd.y)
            lineTo(innerStart.x, innerStart.y)
            close()
        }
        val top = if (lit) TURBO_CYAN_PALE else TURBO_TRACK
        val bottom = if (lit) TURBO_CYAN_DEEP else Color(0xFF151C23)
        drawPath(
            path = path,
            brush = Brush.linearGradient(
                colors = listOf(top, bottom),
                start = outerStart,
                end = innerEnd,
            ),
        )
        if (lit) {
            drawLine(
                color = Color.White.copy(alpha = 0.36f),
                start = outerStart,
                end = outerEnd,
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        // The ridge down the middle of each facet: a cheap way to make a flat trapezoid read as a bent
        // sheet of glass.
        val middle = start + (step - gap) / 2f
        drawLine(
            color = if (lit) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.25f),
            start = polar(center, outerRadius, middle),
            end = polar(center, innerRadius, middle),
            strokeWidth = 1.dp.toPx(),
        )
    }

    drawCircle(
        color = TURBO_CYAN.copy(alpha = 0.22f),
        radius = outerRadius,
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )
    drawCircle(
        color = TURBO_CYAN.copy(alpha = 0.14f),
        radius = innerRadius,
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )
}

/** Green memory-stack ring: four concentric bands with contact pins, lit from the bottom up. */
private fun DrawScope.drawRamRing(center: Offset, radius: Float, stroke: Float, fraction: Float) {
    val bands = 4
    val unit = stroke / bands
    val bandWidth = unit * 0.64f
    val gap = unit * 0.12f
    val firstRadius = radius - stroke / 2f + bandWidth / 2f
    val sweep = GAUGE_SWEEP_ANGLE * fraction.coerceIn(0f, 1f)
    val colours = listOf(
        TURBO_GREEN_DEEP,
        TURBO_GREEN,
        TURBO_GREEN_PALE,
        TURBO_GREEN,
    )
    for (index in 0 until bands) {
        val bandRadius = firstRadius + index * (bandWidth + gap)
        drawArc(
            color = Color(0xFF17241D),
            startAngle = GAUGE_START_ANGLE,
            sweepAngle = GAUGE_SWEEP_ANGLE,
            useCenter = false,
            topLeft = Offset(center.x - bandRadius, center.y - bandRadius),
            size = Size(bandRadius * 2f, bandRadius * 2f),
            style = Stroke(width = bandWidth, cap = StrokeCap.Round),
        )
        if (sweep > 0f) {
            drawArc(
                color = colours[index],
                startAngle = GAUGE_START_ANGLE,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(center.x - bandRadius, center.y - bandRadius),
                size = Size(bandRadius * 2f, bandRadius * 2f),
                style = Stroke(width = bandWidth, cap = StrokeCap.Round),
            )
            drawArc(
                color = Color.White.copy(alpha = 0.18f),
                startAngle = GAUGE_START_ANGLE + 6f,
                sweepAngle = (sweep - 12f).coerceAtLeast(0f),
                useCenter = false,
                topLeft = Offset(
                    center.x - bandRadius + bandWidth * 0.22f,
                    center.y - bandRadius + bandWidth * 0.22f,
                ),
                size = Size(
                    (bandRadius - bandWidth * 0.22f) * 2f,
                    (bandRadius - bandWidth * 0.22f) * 2f,
                ),
                style = Stroke(width = bandWidth * 0.14f, cap = StrokeCap.Round),
            )
        }
    }

    // Gold contact pins outside the stack, in the open bottom half.
    for (index in 0..9) {
        val degrees = 50f + index * 8f
        val lit = fraction * 9f >= index - 1
        drawLine(
            color = if (lit) TURBO_GREEN_PALE.copy(alpha = 0.75f) else Color(0xFF23352A),
            start = polar(center, radius + stroke * 0.52f, degrees),
            end = polar(center, radius + stroke * 0.70f, degrees),
            strokeWidth = 1.4.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** Heat ring: a hot gradient, ticked like a thermometer, with a glowing bead at the current value. */
private fun DrawScope.drawHeatRing(center: Offset, radius: Float, stroke: Float, fraction: Float) {
    val clamped = fraction.coerceIn(0f, 1f)
    val sweep = GAUGE_SWEEP_ANGLE * clamped
    val arcTopLeft = Offset(center.x - radius, center.y - radius)
    val arcSize = Size(radius * 2f, radius * 2f)
    val hot = lerp(TURBO_HEAT_YELLOW, TURBO_HEAT_RED, clamped)

    if (sweep > 0f) {
        // Heat bloom under the metal, then the metal itself, then a pale rim.
        drawArc(
            color = hot.copy(alpha = 0.13f),
            startAngle = GAUGE_START_ANGLE,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = stroke * 1.65f, cap = StrokeCap.Round),
        )
        drawArc(
            brush = Brush.linearGradient(
                colors = listOf(TURBO_HEAT_YELLOW, TURBO_HEAT_ORANGE, TURBO_HEAT_RED),
                start = Offset(size.width * 0.10f, size.height * 0.90f),
                end = Offset(size.width * 0.90f, size.height * 0.10f),
            ),
            startAngle = GAUGE_START_ANGLE,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawArc(
            color = Color.White.copy(alpha = 0.22f),
            startAngle = GAUGE_START_ANGLE + 4f,
            sweepAngle = (sweep - 8f).coerceAtLeast(0f),
            useCenter = false,
            topLeft = Offset(center.x - radius + stroke * 0.28f, center.y - radius + stroke * 0.28f),
            size = Size((radius - stroke * 0.28f) * 2f, (radius - stroke * 0.28f) * 2f),
            style = Stroke(width = stroke * 0.12f, cap = StrokeCap.Round),
        )

        // The bead at the end of the arc, with its own bloom: the one hot spot that reads as a pointer.
        val end = polar(center, radius, GAUGE_START_ANGLE + sweep)
        drawCircle(color = hot.copy(alpha = 0.22f), radius = 6.5.dp.toPx(), center = end)
        drawCircle(color = TURBO_HEAT_PALE, radius = 2.6.dp.toPx(), center = end)
    }

    // Thermometer ticks cut across the band. They are dark, so they read on the lit and unlit sections.
    for (index in 0..23) {
        val degrees = GAUGE_START_ANGLE + index * (GAUGE_SWEEP_ANGLE / 23f)
        drawLine(
            color = Color.Black.copy(alpha = 0.30f),
            start = polar(center, radius - stroke * 0.36f, degrees),
            end = polar(center, radius + stroke * 0.36f, degrees),
            strokeWidth = 1.dp.toPx(),
        )
    }

    // A faint hot core behind the figure, only as far as the reading has gone.
    if (clamped > 0f) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(hot.copy(alpha = 0.10f * clamped), Color.Transparent),
                center = center,
                radius = radius - stroke * 0.55f,
            ),
            radius = (radius - stroke * 0.55f).coerceAtLeast(1f),
            center = center,
        )
    }
}

/** The turbo action grid: the same actions as every other layout, drawn as glass tiles around an icon. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TurboActionGrid(
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
                    TurboActionTile(
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
 * One glass tile.
 *
 * The three states are the same three the other layouts draw: lit accent for an active toggle, plain for
 * an inactive one, dimmed and blocked for an unavailable one. The difference is the material — a rounded
 * glass square with the glyph inside it and the label underneath — which is what makes the turbo surface
 * read as an app launcher rather than as a settings grid.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TurboActionTile(
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
    val border = when {
        active -> accent.copy(alpha = 0.60f)
        usable -> Color.White.copy(alpha = 0.07f)
        else -> Color.White.copy(alpha = 0.03f)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(TURBO_TILE_CORNER_DP.dp))
            .background(
                if (active) accent.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.035f),
            )
            .border(1.dp, border, RoundedCornerShape(TURBO_TILE_CORNER_DP.dp))
            // Unusable actions are not clickable at all rather than clickable-and-ignored: a tile that
            // depresses and does nothing reads as broken, while one that does not respond reads as off.
            .combinedClickable(
                enabled = usable,
                onLongClick = if (action.heldRow != null) ({ onLongAction(action) }) else null,
                onClick = { onAction(action) },
            )
            .padding(vertical = 7.dp, horizontal = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(TURBO_TILE_ICON_PLATE_DP.dp)
                    .clip(RoundedCornerShape(TURBO_TILE_ICON_CORNER_DP.dp))
                    .background(Color.White.copy(alpha = if (active) 0.10f else 0.05f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = action.icon(),
                    contentDescription = action.label,
                    tint = tint,
                    modifier = Modifier.size(TURBO_TILE_ICON_DP.dp),
                )
                if (!usable) {
                    Icon(
                        imageVector = Icons.Rounded.Block,
                        contentDescription = null,
                        tint = OverlayPalette.Absent,
                        modifier = Modifier.size(TURBO_TILE_ICON_DP.dp),
                    )
                }
            }
            if (active) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(5.dp)
                        .background(accent, CircleShape),
                )
            }
        }
        BasicText(
            text = action.label,
            maxLines = 2,
            style = TextStyle(
                color = tint,
                fontSize = 9.sp,
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
                    fontSize = 7.5.sp,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

/** The colour a gauge's ring and caption use. One per choice, so the four never read as the same dial. */
private fun gaugeTint(choice: TurboGaugeChoice): Color = when (choice) {
    TurboGaugeChoice.CPU -> TURBO_AMBER
    TurboGaugeChoice.GPU -> TURBO_CYAN
    TurboGaugeChoice.RAM -> TURBO_GREEN
    TurboGaugeChoice.TEMPERATURE -> TURBO_HEAT_ORANGE
}

/**
 * How full a gauge ring is, from the reading it was handed.
 *
 * A reading with no value has no fill at all — not a zero, which would draw a gauge that looks like it
 * measured something. The three percentage stats and CPU temperature all arrive as bare numbers, so the
 * only difference is the scale: temperature is the one that is not already a percentage, and its 0–100 °C
 * range is the gauge's own range rather than a claim that a CPU at 100 °C is "full". It is a visual scale,
 * and the number inside the ring remains the measurement.
 */
internal fun gaugeFraction(choice: TurboGaugeChoice, reading: StatReading?): Float {
    val value = reading?.value?.toFloatOrNull() ?: return 0f
    val fraction = when (choice) {
        TurboGaugeChoice.TEMPERATURE -> value / TURBO_TEMPERATURE_SCALE
        else -> value / 100f
    }
    return fraction.coerceIn(0f, 1f)
}

/** The screen-reader sentence for a gauge, including the reason when there is no reading. */
internal fun gaugeDescription(choice: TurboGaugeChoice, reading: StatReading?): String {
    val statLabel = reading?.stat?.label ?: choice.label
    val value = if (reading != null && reading.isAvailable) {
        "${reading.display()} $statLabel"
    } else {
        "${reading?.reason ?: "No reading"} for $statLabel"
    }
    return "${choice.label} gauge, ${choice.designLabel}: $value"
}

/**
 * How wide each wing is on a screen [screenWidthDp] wide.
 *
 * Three bounds, and the order is the argument:
 *
 * 1. [TURBO_WING_FRACTION] of the screen is the target — about a third each, leaving a third for the game.
 * 2. Narrowed if that would leave less than [TURBO_MIN_GAME_DP] between the wings.
 * 3. Floored at [FloatingButtonConfig.MIN_PANEL_WIDTH_DP] *after* rule 2, because a wing too narrow to
 *    hold the two-column action grid is worse than a thin strip of game. On a portrait phone this is the
 *    bound that decides, exactly as it is for the split layout.
 * 4. Capped at half the screen and at [TURBO_WING_MAX_DP], so two wings can never overlap and a tablet
 *    gets a usable plate rather than a third of a very wide screen with a hand's width of empty plate.
 *
 * Sharing the model's floor with the centered panel because it is the same fact: that is the width at
 * which [actionsPerRow] still guarantees two tiles. What is *not* shared is the stored
 * [FloatingButtonConfig.panelWidthDp], which belongs to the centered layout and is left alone here.
 */
internal fun turboWingWidth(screenWidthDp: Int): Int {
    val preferred = (screenWidthDp * TURBO_WING_FRACTION).roundToInt()
    val leavingGap = (screenWidthDp - TURBO_MIN_GAME_DP) / 2
    val ceiling = minOf(screenWidthDp / 2, TURBO_WING_MAX_DP)
    return preferred
        .coerceAtMost(leavingGap)
        .coerceAtLeast(FloatingButtonConfig.MIN_PANEL_WIDTH_DP)
        .coerceAtMost(ceiling.coerceAtLeast(1))
}

/**
 * The width of the usable column inside a wing [wingWidthDp] wide.
 *
 * On wide wings this is [TURBO_WING_CONTENT_FRACTION] of the plate, aligned to the outer edge, so the bent
 * inner edge has a clear band to draw its light in. On narrow wings the full plate width is used instead:
 * the shape's own taper is reduced there, and a column narrower than [TURBO_MIN_CONTENT_DP] would make the
 * action grid's two guaranteed columns physically smaller than a finger. The `max` is what keeps that
 * promise at every width the floor permits, while the fraction keeps the silhouette on a tablet.
 */
internal fun turboWingContentWidth(wingWidthDp: Int): Int {
    val preferred = (wingWidthDp * TURBO_WING_CONTENT_FRACTION).roundToInt()
    val requiredForGrid = TURBO_MIN_CONTENT_DP
    return maxOf(preferred, minOf(wingWidthDp, requiredForGrid))
}

/**
 * The wing's bent inner edge as fractions of its width and height.
 *
 * The points are the silhouette, not the content: a short outward flare at the top, a notch, two blade
 * tips around the upper-middle, then a long taper toward the bottom. Mirrored for the right wing by
 * subtracting each x from one, so the two edges are the same curve reflected and cannot drift apart. The
 * final x is [TURBO_NARROW_TAPER] on a narrow plate and [TURBO_WIDE_TAPER] on a wide one; see
 * [turboWingContentWidth] for why the narrow case keeps more width.
 */
internal fun turboWingEdgeFractions(isLeft: Boolean, narrow: Boolean): List<Pair<Float, Float>> {
    val bottom = if (narrow) TURBO_NARROW_TAPER else TURBO_WIDE_TAPER
    val points = listOf(
        0.90f to 0.00f,
        0.98f to 0.07f,
        0.94f to 0.18f,
        1.00f to 0.30f,
        0.96f to 0.41f,
        1.00f to 0.52f,
        0.95f to 0.63f,
        0.93f to 0.75f,
        (bottom - 0.01f) to 0.88f,
        bottom to 1.00f,
    )
    return if (isLeft) points else points.map { (x, y) -> (1f - x) to y }
}

/** The shape of one wing. [narrow] chooses the bottom taper — see [turboWingEdgeFractions]. */
internal fun turboWingShape(isLeft: Boolean, narrow: Boolean): Shape = GenericShape { size, _ ->
    val edge = turboWingEdgeFractions(isLeft = isLeft, narrow = narrow)
    val outer = if (isLeft) 0f else size.width
    moveTo(outer, 0f)
    edge.forEach { (x, y) -> lineTo(x * size.width, y * size.height) }
    lineTo(outer, size.height)
    close()
}

/** The same edge as [turboWingShape], but as an open path for the light to follow. */
private fun turboWingEdgePath(size: Size, isLeft: Boolean, narrow: Boolean): Path = Path().apply {
    val edge = turboWingEdgeFractions(isLeft = isLeft, narrow = narrow)
    edge.forEachIndexed { index, (x, y) ->
        val px = x * size.width
        val py = y * size.height
        if (index == 0) moveTo(px, py) else lineTo(px, py)
    }
}

/** Full wing path for the surface wash and rim, in absolute pixels. */
private fun turboWingPath(size: Size, isLeft: Boolean, narrow: Boolean): Path = Path().apply {
    val edge = turboWingEdgeFractions(isLeft = isLeft, narrow = narrow)
    val outer = if (isLeft) 0f else size.width
    moveTo(outer, 0f)
    edge.forEach { (x, y) -> lineTo(x * size.width, y * size.height) }
    lineTo(outer, size.height)
    close()
}

/** A point on a circle. Compose angles run clockwise from three o'clock, which matches the arcs above. */
private fun polar(center: Offset, radius: Float, degrees: Float): Offset {
    val radians = degrees * PI / 180.0
    return Offset(
        x = center.x + (radius * cos(radians)).toFloat(),
        y = center.y + (radius * sin(radians)).toFloat(),
    )
}

/** The trail the stats pill and the HUD share: an absent reading is a reason, never a zero. */
private const val TURBO_TEMPERATURE_SCALE = 100f

private const val GAUGE_START_ANGLE = 135f
private const val GAUGE_SWEEP_ANGLE = 270f

/** About a third each, leaving a third for the game. */
private const val TURBO_WING_FRACTION = 0.36f

/** A tablet wing stops growing here. */
private const val TURBO_WING_MAX_DP = 360

/** The narrowest strip of game the wings aim to leave, as a target rather than a guarantee. */
internal const val TURBO_MIN_GAME_DP = 96

/** The share of a wide wing the scrolling content occupies. The rest is the bent edge's light band. */
private const val TURBO_WING_CONTENT_FRACTION = 0.86f

/** Below this, the content column needs the whole plate to keep two tiles a finger wide. */
private const val TURBO_NARROW_MARGIN_DP = 16

/** Below this content width the wordmark drops its TURBO tag and shrinks, so the close button fits. */
private const val TURBO_COMPACT_HEADER_DP = 210

/** Below this content width the stats row splits into two lines instead of tightening past readability. */
private const val TURBO_WIDE_STATS_DP = 240

/** The dense stats row's full width; the same five figures PanelStats takes by default. */
private const val TURBO_STATS_PER_ROW = 5

/** Two columns' worth of plate: [FloatingButtonConfig.MIN_PANEL_WIDTH_DP] plus the panel's two insets. */
private const val TURBO_MIN_CONTENT_DP = FloatingButtonConfig.MIN_PANEL_WIDTH_DP + PANEL_PADDING_DP * 2

private const val TURBO_NARROW_TAPER = 0.94f
private const val TURBO_WIDE_TAPER = 0.88f

/** The plate's inset from the top and bottom, keeping the first row off the status bar and cutout. */
private const val TURBO_EDGE_INSET_DP = 14

/** The 48 dp minimum, less the margin the header already sits inside. */
private const val TURBO_CLOSE_TOUCH_DP = 36

private const val TURBO_CLOSE_ICON_DP = 18

private const val TURBO_GAUGE_HEIGHT_DP = 150

private const val TURBO_TILE_CORNER_DP = 12

private const val TURBO_TILE_ICON_PLATE_DP = 34

private const val TURBO_TILE_ICON_CORNER_DP = 9

private const val TURBO_TILE_ICON_DP = 20

/** What the gap does, said where it does not cover the middle of the game. */
private const val TURBO_DISMISS_HINT = "Tap here to close"

private val TURBO_PLATE_TOP = Color(0xF21A1F26)
private val TURBO_PLATE_BOTTOM = Color(0xF207090C)
private val TURBO_TRACK = Color(0xFF242C35)
private val TURBO_AMBER = Color(0xFFFFC24B)
private val TURBO_AMBER_PALE = Color(0xFFFFE7A3)
private val TURBO_AMBER_DEEP = Color(0xFF9A5E00)
private val TURBO_CYAN = Color(0xFF57E7FF)
private val TURBO_CYAN_PALE = Color(0xFFBDF8FF)
private val TURBO_CYAN_DEEP = Color(0xFF0B5364)
private val TURBO_GREEN = Color(0xFF67F0A0)
private val TURBO_GREEN_PALE = Color(0xFFC8FFDC)
private val TURBO_GREEN_DEEP = Color(0xFF146B3D)
private val TURBO_HEAT_YELLOW = Color(0xFFFFD34D)
private val TURBO_HEAT_ORANGE = Color(0xFFFF8A3D)
private val TURBO_HEAT_RED = Color(0xFFFF4D3D)
private val TURBO_HEAT_PALE = Color(0xFFFFE0B2)
