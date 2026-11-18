package com.gamecore.core.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.gamecore.core.model.FloatingButtonStyle
import kotlin.math.cos
import kotlin.math.sin

/**
 * The floating button's three looks, drawn once and shared by the live overlay and the settings
 * preview.
 *
 * A single visual is the point: the old settings preview drew its own circle and gamepad, so it could
 * disagree with the button over a game. Here the preview and the window are the same composable fed the
 * same [FloatingButtonStyle], [accent] and size, and only the interaction state differs.
 *
 * [FloatingButtonStyle.CLASSIC] deliberately draws exactly the pre-5.6 button — same plate, border,
 * glyph, thermal dot and icon size — and ignores every 5.6 effect. That is what makes "revert to the
 * old one" a real revert rather than a new look with the old name.
 *
 * The effects are all one-shot and driven by the caller: [burst] runs 0 → 1 when a surface opens,
 * [charge] runs 0 → 1 while the long press is held, and [ringTurn] turns the segmented ring when the
 * button is expanded. Nothing here animates on its own, so a parked button costs no frames over a game.
 */
@Composable
fun FloatingButtonVisual(
    style: FloatingButtonStyle,
    sizeDp: Int,
    accent: Color,
    expanded: Boolean,
    thermalDot: ThermalDot,
    modifier: Modifier = Modifier,
    burst: Float = 1f,
    charge: Float = 0f,
    ringTurn: Float = 0f,
    /**
     * Whether the visual names itself for a screen reader. The live overlay needs it; the settings
     * picker does not, because each card is already a labelled radio button and a second "GameCore
     * controls" stop inside it would read the same choice twice.
     */
    describe: Boolean = true,
) {
    val description = buildString {
        append("GameCore controls")
        if (expanded) append(", panel open")
        when (thermalDot) {
            ThermalDot.HOT -> append(", device hot")
            ThermalDot.CRITICAL -> append(", device critical")
            ThermalDot.NONE -> Unit
        }
    }
    val semantics = if (describe) {
        modifier.clearAndSetSemantics { contentDescription = description }
    } else {
        modifier
    }

    when (style) {
        FloatingButtonStyle.CLASSIC -> ClassicButton(
            accent = accent,
            sizeDp = sizeDp,
            thermalDot = thermalDot,
            modifier = semantics,
        )

        FloatingButtonStyle.REACTOR -> ReactorButton(
            accent = accent,
            sizeDp = sizeDp,
            expanded = expanded,
            thermalDot = thermalDot,
            burst = burst,
            charge = charge,
            ringTurn = ringTurn,
            modifier = semantics,
        )

        FloatingButtonStyle.VANGUARD -> VanguardButton(
            accent = accent,
            sizeDp = sizeDp,
            expanded = expanded,
            thermalDot = thermalDot,
            burst = burst,
            charge = charge,
            modifier = semantics,
        )
    }
}

// --------------------------------------------------------------------------------------------- classic

/**
 * The button exactly as it shipped before 5.6.
 *
 * No scale, no ring, no sweep, and the glyph stays the gamepad even while the panel is open, because
 * those are all 5.6 additions. The only shared thing is the thermal dot, which was already there.
 */
@Composable
private fun ClassicButton(
    accent: Color,
    sizeDp: Int,
    thermalDot: ThermalDot,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(OverlayPalette.Plate, CircleShape)
            .border(BORDER_DP.dp, accent.copy(alpha = BORDER_ALPHA), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(expanded = false, accent = accent, sizeDp = sizeDp, fraction = CLASSIC_ICON_FRACTION)
        ThermalDotBadge(thermalDot = thermalDot, sizeDp = sizeDp)
    }
}

// --------------------------------------------------------------------------------------------- reactor

/**
 * A segmented energy ring around a lit core.
 *
 * The ring is two arcs rather than a full circle: a bright sweep and a dimmer counterweight. When the
 * button is expanded the pair turns a quarter turn, so opening the panel is visible in the rim itself.
 * The core carries the glyph on a radial accent wash, which keeps the icon readable without turning the
 * whole button into a bright blob over a dark game.
 */
@Composable
private fun ReactorButton(
    accent: Color,
    sizeDp: Int,
    expanded: Boolean,
    thermalDot: ThermalDot,
    burst: Float,
    charge: Float,
    ringTurn: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(OverlayPalette.Plate, CircleShape)
            .border(BORDER_DP.dp, accent.copy(alpha = REACTOR_BORDER_ALPHA), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val diameter = size.minDimension
            val centre = center
            val ringInset = diameter * RING_INSET_FRACTION
            val ringDiameter = diameter - ringInset * 2f
            val ringTopLeft = Offset(
                x = (size.width - ringDiameter) / 2f,
                y = (size.height - ringDiameter) / 2f,
            )
            val ringSize = Size(ringDiameter, ringDiameter)

            // A soft accent halo behind the plate, strongest at the rim and gone at the centre.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                    center = centre,
                    radius = diameter / 2f,
                ),
                radius = diameter / 2f,
                center = centre,
            )

            // The unlit track, so the two bright arcs read as segments of one ring.
            drawArc(
                color = accent.copy(alpha = 0.18f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = ringTopLeft,
                size = ringSize,
                style = Stroke(width = 1.dp.toPx()),
            )
            drawArc(
                color = accent,
                startAngle = -90f + ringTurn,
                sweepAngle = REACTOR_BRIGHT_SWEEP,
                useCenter = false,
                topLeft = ringTopLeft,
                size = ringSize,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(
                color = accent.copy(alpha = 0.55f),
                startAngle = 110f + ringTurn,
                sweepAngle = REACTOR_DIM_SWEEP,
                useCenter = false,
                topLeft = ringTopLeft,
                size = ringSize,
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
            )

            // A node at the bright arc's leading end, so the ring has a direction rather than a seam.
            val nodeAngle = Math.toRadians((-90f + ringTurn + REACTOR_BRIGHT_SWEEP).toDouble())
            val nodeRadius = ringDiameter / 2f
            drawCircle(
                color = accent,
                radius = diameter * 0.022f,
                center = Offset(
                    x = centre.x + cos(nodeAngle).toFloat() * nodeRadius,
                    y = centre.y + sin(nodeAngle).toFloat() * nodeRadius,
                ),
            )

            drawCharge(
                charge = charge,
                diameter = diameter,
                ringInset = ringInset,
                accent = accent,
            )
            drawShockwave(burst = burst, diameter = diameter, accent = accent)
        }

        Box(
            modifier = Modifier
                .fillMaxSize(REACTOR_CORE_FRACTION)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.34f),
                            OverlayPalette.PanelPlate,
                        ),
                    ),
                    shape = CircleShape,
                )
                .border(1.dp, accent.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(
                expanded = expanded,
                accent = accent,
                sizeDp = sizeDp,
                fraction = REACTOR_ICON_FRACTION,
            )
        }

        ThermalDotBadge(thermalDot = thermalDot, sizeDp = sizeDp, insetDp = 1f)
    }
}

// -------------------------------------------------------------------------------------------- vanguard

/**
 * A rounded armour plate with lit corner brackets.
 *
 * The silhouette is a rounded square rather than a circle, so it is distinguishable at 36 dp even
 * before the brackets are read. The brackets sit inside the rounded corners and the opening sweep is
 * clipped by the plate, which makes the light feel like it crosses the object instead of a rectangle
 * laid over it.
 */
@Composable
private fun VanguardButton(
    accent: Color,
    sizeDp: Int,
    expanded: Boolean,
    thermalDot: ThermalDot,
    burst: Float,
    charge: Float,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = VANGUARD_CORNER_PERCENT)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(OverlayPalette.PanelPlate, OverlayPalette.Plate),
                ),
                shape = shape,
            )
            .border(BORDER_DP.dp, accent.copy(alpha = VANGUARD_BORDER_ALPHA), shape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val diameter = size.minDimension
            val inset = VANGUARD_BRACKET_INSET_DP.dp.toPx()
            val arm = diameter * VANGUARD_BRACKET_ARM_FRACTION
            val stroke = VANGUARD_BRACKET_STROKE_DP.dp.toPx()
            val bracket = accent.copy(alpha = 0.85f)
            val right = size.width - inset
            val bottom = size.height - inset

            // Four L brackets, each drawn as two strokes meeting at the corner.
            drawLine(bracket, Offset(inset, inset + arm), Offset(inset, inset), stroke, StrokeCap.Round)
            drawLine(bracket, Offset(inset, inset), Offset(inset + arm, inset), stroke, StrokeCap.Round)
            drawLine(bracket, Offset(right - arm, inset), Offset(right, inset), stroke, StrokeCap.Round)
            drawLine(bracket, Offset(right, inset), Offset(right, inset + arm), stroke, StrokeCap.Round)
            drawLine(bracket, Offset(inset, bottom - arm), Offset(inset, bottom), stroke, StrokeCap.Round)
            drawLine(bracket, Offset(inset, bottom), Offset(inset + arm, bottom), stroke, StrokeCap.Round)
            drawLine(bracket, Offset(right - arm, bottom), Offset(right, bottom), stroke, StrokeCap.Round)
            drawLine(bracket, Offset(right, bottom), Offset(right, bottom - arm), stroke, StrokeCap.Round)

            // Two centre ticks, the HUD mark that says this is a control rather than a tile.
            val tick = diameter * 0.055f
            val tickInset = VANGUARD_TICK_INSET_DP.dp.toPx()
            drawLine(
                color = accent.copy(alpha = 0.42f),
                start = Offset(center.x - tick, tickInset),
                end = Offset(center.x + tick, tickInset),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = accent.copy(alpha = 0.42f),
                start = Offset(center.x - tick, size.height - tickInset),
                end = Offset(center.x + tick, size.height - tickInset),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )

            drawCharge(
                charge = charge,
                diameter = diameter,
                ringInset = VANGUARD_CHARGE_INSET_FRACTION * diameter,
                accent = accent,
            )
            drawShockwave(burst = burst, diameter = diameter, accent = accent)
            drawSweep(burst = burst, diameter = diameter)
        }

        Glyph(
            expanded = expanded,
            accent = accent,
            sizeDp = sizeDp,
            fraction = VANGUARD_ICON_FRACTION,
        )
        ThermalDotBadge(
            thermalDot = thermalDot,
            sizeDp = sizeDp,
            insetDp = VANGUARD_DOT_INSET_DP,
        )
    }
}

// -------------------------------------------------------------------------------------------- shared

@Composable
private fun Glyph(
    expanded: Boolean,
    accent: Color,
    sizeDp: Int,
    fraction: Float,
) {
    Icon(
        imageVector = if (expanded) Icons.Rounded.Close else Icons.Rounded.SportsEsports,
        // The visual's root carries the state description; a second stop on the glyph would read the
        // button twice.
        contentDescription = null,
        tint = accent,
        modifier = Modifier.size((sizeDp * fraction).dp),
    )
}

/**
 * The thermal dot from spec §2, drawn in the plate colour so it reads against both the glyph and a
 * bright game behind a translucent button.
 */
@Composable
private fun BoxScope.ThermalDotBadge(
    thermalDot: ThermalDot,
    sizeDp: Int,
    insetDp: Float = 0f,
) {
    if (thermalDot == ThermalDot.NONE) return
    val colour = if (thermalDot == ThermalDot.CRITICAL) {
        OverlayPalette.Danger
    } else {
        OverlayPalette.Warning
    }
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = insetDp.dp, end = insetDp.dp)
            .size((sizeDp * DOT_RING_FRACTION).dp)
            .background(OverlayPalette.Plate, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size((sizeDp * DOT_FRACTION).dp)
                .background(colour, CircleShape),
        )
    }
}

/**
 * The long-press charge, as an arc that fills once around the ring.
 *
 * Only drawn while there is something to say: at rest [charge] is 0 and this costs one comparison.
 * The colour turns from warning to good at full charge, which is the same instant the picker opens.
 */
private fun DrawScope.drawCharge(
    charge: Float,
    diameter: Float,
    ringInset: Float,
    accent: Color,
) {
    if (charge <= 0.001f) return
    val safe = charge.coerceIn(0f, 1f)
    val chargeDiameter = (diameter - ringInset * 2f).coerceAtLeast(1f)
    val topLeft = Offset(
        x = (size.width - chargeDiameter) / 2f,
        y = (size.height - chargeDiameter) / 2f,
    )
    drawArc(
        color = if (safe >= 0.999f) OverlayPalette.Good else OverlayPalette.Warning,
        startAngle = -90f,
        sweepAngle = 360f * safe,
        useCenter = false,
        topLeft = topLeft,
        size = Size(chargeDiameter, chargeDiameter),
        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
    )
    // A quiet accent underline keeps the charge readable on the styles whose accent is already warm.
    if (safe < 0.999f) {
        drawArc(
            color = accent.copy(alpha = 0.30f),
            startAngle = -90f,
            sweepAngle = 360f * safe,
            useCenter = false,
            topLeft = topLeft,
            size = Size(chargeDiameter, chargeDiameter),
            style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** The one-shot ring that leaves the button when a surface opens. */
private fun DrawScope.drawShockwave(
    burst: Float,
    diameter: Float,
    accent: Color,
) {
    if (burst >= 0.999f) return
    val progress = burst.coerceIn(0f, 1f)
    drawCircle(
        color = accent.copy(alpha = (1f - progress) * 0.50f),
        radius = diameter * (0.34f + 0.26f * progress),
        center = center,
        style = Stroke(width = 2.dp.toPx()),
    )
}

/** The diagonal light band that crosses the Vanguard plate once when it opens. */
private fun DrawScope.drawSweep(
    burst: Float,
    diameter: Float,
) {
    if (burst >= 0.999f) return
    val progress = burst.coerceIn(0f, 1f)
    val band = diameter * 0.20f
    val x = -band + (size.width + band * 2f) * progress
    val path = Path().apply {
        moveTo(x, 0f)
        lineTo(x + band, 0f)
        lineTo(x + band + diameter * 0.55f, size.height)
        lineTo(x + diameter * 0.55f, size.height)
        close()
    }
    drawPath(
        path = path,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = 0.22f),
                Color.Transparent,
            ),
            start = Offset(x, 0f),
            end = Offset(x + band, size.height),
        ),
    )
}

// -------------------------------------------------------------------------------------------- tokens

/** The classic plate's border, unchanged from the pre-5.6 button. */
private const val BORDER_DP = 1.5f
private const val BORDER_ALPHA = 0.55f

/** The glyph and thermal dot as fractions of the button's diameter — also the classic values. */
private const val CLASSIC_ICON_FRACTION = 0.52f
private const val REACTOR_ICON_FRACTION = 0.40f
private const val VANGUARD_ICON_FRACTION = 0.46f
private const val DOT_FRACTION = 0.22f
private const val DOT_RING_FRACTION = 0.34f

private const val REACTOR_BORDER_ALPHA = 0.65f
private const val RING_INSET_FRACTION = 0.10f
private const val REACTOR_CORE_FRACTION = 0.64f
private const val REACTOR_BRIGHT_SWEEP = 72f
private const val REACTOR_DIM_SWEEP = 30f

private const val VANGUARD_BORDER_ALPHA = 0.62f
private const val VANGUARD_CORNER_PERCENT = 28
private const val VANGUARD_BRACKET_INSET_DP = 4f
private const val VANGUARD_BRACKET_ARM_FRACTION = 0.20f
private const val VANGUARD_BRACKET_STROKE_DP = 1.5f
private const val VANGUARD_TICK_INSET_DP = 2f
private const val VANGUARD_DOT_INSET_DP = 6f
private const val VANGUARD_CHARGE_INSET_FRACTION = 0.09f
