package com.gamecore.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.PENDING
import com.gamecore.ui.components.Readout
import com.gamecore.ui.components.Sparkline
import com.gamecore.ui.theme.StatLabelStyle
import com.gamecore.ui.theme.StatValueStyle
import kotlin.math.cos
import kotlin.math.sin

/** The five Home readings. Every entry owns a different full-card silhouette. */
internal enum class HomeMetricShape {
    CPU,
    MEMORY,
    BATTERY,
    TEMPERATURE,
    REFRESH,
    UNKNOWN;

    companion object {
        fun fromLabel(label: String): HomeMetricShape = when (label) {
            HomeLabels.CPU -> CPU
            HomeLabels.MEMORY -> MEMORY
            HomeLabels.BATTERY -> BATTERY
            HomeLabels.TEMPERATURE -> TEMPERATURE
            HomeLabels.REFRESH -> REFRESH
            else -> UNKNOWN
        }
    }
}

/**
 * A Home reading whose *whole tile* is the instrument.
 *
 * There is no standard card beneath this composable and no small logo parked beside the number. The CPU
 * tile is the processor package, Battery is the battery case and terminal, RAM is a DIMM, Temperature is
 * a horizontal glass thermometer, and Refresh is a monitor on its stand. The bounds remain equal so the
 * two-column grid stays stable, but transparent corners expose each object's actual silhouette.
 */
@Composable
internal fun HomeMetric3DCard(
    readout: Readout,
    trend: List<Float>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = HomeMetricShape.fromLabel(readout.label)
    val accent = metricAccent(shape)
    val level = readout.fraction?.coerceIn(0f, 1f) ?: when (shape) {
        HomeMetricShape.TEMPERATURE -> 0.64f
        HomeMetricShape.REFRESH -> 0.78f
        else -> 0.52f
    }
    val interaction = remember { MutableInteractionSource() }
    val spoken = buildString {
        append(readout.label).append(", ").append(readout.value)
        readout.detail?.let { append(", ").append(it) }
        append(". Open Performance")
    }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = METRIC_CARD_HEIGHT.dp)
            // The object itself supplies press feedback through its bright rim. A rectangular Material
            // ripple would briefly reveal the exact old card this design is replacing.
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick(label = "Open Performance") {
                    onClick()
                    true
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            when (shape) {
                HomeMetricShape.CPU -> drawCpuCard(level, accent)
                HomeMetricShape.MEMORY -> drawMemoryCard(level, accent)
                HomeMetricShape.BATTERY -> drawBatteryCard(level, accent)
                HomeMetricShape.TEMPERATURE -> drawThermometerCard(level, accent)
                HomeMetricShape.REFRESH -> drawRefreshCard(level, accent)
                HomeMetricShape.UNKNOWN -> drawFallbackCard(accent)
            }
        }
        MetricCopy(
            shape = shape,
            readout = readout,
            trend = trend,
            accent = accent,
            modifier = Modifier
                .fillMaxSize()
                .padding(metricContentPadding(shape)),
        )
    }
}

/** Text is engraved into the object face, never placed in a second rectangular panel. */
@Composable
private fun MetricCopy(
    shape: HomeMetricShape,
    readout: Readout,
    trend: List<Float>,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val available = readout.value != ABSENT && readout.value != PENDING
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = readout.label.uppercase(),
            style = StatLabelStyle,
            color = Color(0xFFD5DEE5),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = readout.value,
            style = StatValueStyle,
            color = if (available) accent else Color(0xFF9CA7AF),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (trend.size >= 2 && shape in setOf(HomeMetricShape.MEMORY, HomeMetricShape.TEMPERATURE)) {
            Spacer(modifier = Modifier.height(5.dp))
            Sparkline(values = trend, colour = accent, height = 22)
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }
        readout.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB4BFC7),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Space reserved for pins, terminals, bulbs and the monitor stand. */
private fun metricContentPadding(shape: HomeMetricShape) = when (shape) {
    HomeMetricShape.CPU -> androidx.compose.foundation.layout.PaddingValues(25.dp, 28.dp, 25.dp, 24.dp)
    HomeMetricShape.MEMORY -> androidx.compose.foundation.layout.PaddingValues(19.dp, 27.dp, 19.dp, 29.dp)
    HomeMetricShape.BATTERY -> androidx.compose.foundation.layout.PaddingValues(19.dp, 31.dp, 35.dp, 27.dp)
    HomeMetricShape.TEMPERATURE -> androidx.compose.foundation.layout.PaddingValues(46.dp, 29.dp, 17.dp, 27.dp)
    HomeMetricShape.REFRESH -> androidx.compose.foundation.layout.PaddingValues(22.dp, 24.dp, 21.dp, 43.dp)
    HomeMetricShape.UNKNOWN -> androidx.compose.foundation.layout.PaddingValues(23.dp)
}

private fun metricAccent(shape: HomeMetricShape): Color = when (shape) {
    HomeMetricShape.CPU -> Color(0xFFFFB84D)
    HomeMetricShape.MEMORY -> Color(0xFF4BE39B)
    HomeMetricShape.BATTERY -> Color(0xFF5CE67B)
    HomeMetricShape.TEMPERATURE -> Color(0xFFFF6A62)
    HomeMetricShape.REFRESH -> Color(0xFF66DAFF)
    HomeMetricShape.UNKNOWN -> Color(0xFF9C83FF)
}

/** The complete CPU tile is a pinned, extruded package with an etched core grid. */
private fun DrawScope.drawCpuCard(level: Float, accent: Color) {
    val w = size.width
    val h = size.height
    val left = w * 0.105f
    val top = h * 0.105f
    val right = w * 0.865f
    val bottom = h * 0.855f
    val body = Size(right - left, bottom - top)
    val depth = minOf(w, h) * 0.045f
    val pinColour = Color(0xFF87939C)

    drawRoundRect(
        color = Color.Black.copy(alpha = 0.42f),
        topLeft = Offset(left + depth, top + depth * 1.4f),
        size = body,
        cornerRadius = CornerRadius(13.dp.toPx()),
    )
    repeat(5) { index ->
        val y = top + body.height * (index + 1) / 6f
        val x = left + body.width * (index + 1) / 6f
        val live = index < (level * 5f).toInt().coerceIn(0, 5)
        val metal = if (live) accent else pinColour
        drawLine(metal, Offset(w * 0.025f, y), Offset(left, y), 3.2.dp.toPx(), StrokeCap.Round)
        drawLine(metal, Offset(right, y), Offset(w * 0.955f, y), 3.2.dp.toPx(), StrokeCap.Round)
        drawLine(metal, Offset(x, h * 0.025f), Offset(x, top), 3.2.dp.toPx(), StrokeCap.Round)
        drawLine(metal, Offset(x, bottom), Offset(x, h * 0.965f), 3.2.dp.toPx(), StrokeCap.Round)
    }
    drawPath(
        Path().apply {
            moveTo(right, top + depth)
            lineTo(right + depth, top + depth * 1.8f)
            lineTo(right + depth, bottom - depth * 0.2f)
            lineTo(right, bottom)
            close()
        },
        Color(0xFF070A0D),
    )
    drawPath(
        Path().apply {
            moveTo(left + depth, bottom)
            lineTo(right, bottom)
            lineTo(right + depth, bottom - depth * 0.2f)
            lineTo(left + depth * 1.7f, bottom + depth)
            close()
        },
        Color(0xFF11171C),
    )
    drawRoundRect(
        brush = metalBrush(Color(0xFF252E36), Offset(left, top), Offset(right, bottom)),
        topLeft = Offset(left, top),
        size = body,
        cornerRadius = CornerRadius(13.dp.toPx()),
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.18f),
        topLeft = Offset(left, top),
        size = body,
        cornerRadius = CornerRadius(13.dp.toPx()),
        style = Stroke(1.2.dp.toPx()),
    )
    // Corner core cells and circuit traces leave the central readout clear.
    val cell = minOf(w, h) * 0.10f
    listOf(
        Offset(left + body.width * 0.13f, top + body.height * 0.12f),
        Offset(right - body.width * 0.13f - cell, top + body.height * 0.12f),
        Offset(left + body.width * 0.13f, bottom - body.height * 0.12f - cell),
        Offset(right - body.width * 0.13f - cell, bottom - body.height * 0.12f - cell),
    ).forEachIndexed { index, cellTop ->
        val active = level * 4f > index
        drawRoundRect(
            brush = Brush.linearGradient(
                if (active) listOf(Color(0xFFFFE59A), accent, Color(0xFF8E4D0A))
                else listOf(Color(0xFF59616A), Color(0xFF181D22)),
                cellTop,
                cellTop + Offset(cell, cell),
            ),
            topLeft = cellTop,
            size = Size(cell, cell),
            cornerRadius = CornerRadius(3.dp.toPx()),
        )
        val centre = cellTop + Offset(cell / 2f, cell / 2f)
        drawLine(accent.copy(alpha = 0.42f), centre, Offset(w / 2f, h / 2f), 1.dp.toPx())
    }
}

/** The complete RAM tile is one DIMM, including PCB, raised chips, contacts and centre notch. */
private fun DrawScope.drawMemoryCard(level: Float, accent: Color) {
    val w = size.width
    val h = size.height
    val left = w * 0.035f
    val top = h * 0.13f
    val boardW = w * 0.89f
    val boardH = h * 0.72f
    val depth = h * 0.045f
    drawRoundRect(
        Color.Black.copy(alpha = 0.40f),
        Offset(left + depth, top + depth * 1.5f),
        Size(boardW, boardH),
        CornerRadius(11.dp.toPx()),
    )
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(Color(0xFF31745B), Color(0xFF123C2D), Color(0xFF071C15)),
            Offset(left, top),
            Offset(left + boardW, top + boardH),
        ),
        topLeft = Offset(left, top),
        size = Size(boardW, boardH),
        cornerRadius = CornerRadius(11.dp.toPx()),
    )
    drawRoundRect(
        Color.White.copy(alpha = 0.16f),
        Offset(left, top),
        Size(boardW, boardH),
        CornerRadius(11.dp.toPx()),
        style = Stroke(1.dp.toPx()),
    )
    val chips = 8
    val active = (level * chips).toInt().coerceIn(0, chips)
    repeat(4) { index ->
        val chipW = boardW * 0.135f
        val chipH = boardH * 0.18f
        val x = left + boardW * (0.08f + index * 0.235f)
        listOf(top + boardH * 0.10f, top + boardH * 0.70f).forEachIndexed { row, y ->
            val lit = row * 4 + index < active
            drawRoundRect(
                brush = Brush.linearGradient(
                    if (lit) listOf(lerp(accent, Color.White, 0.25f), Color(0xFF128557))
                    else listOf(Color(0xFF313B38), Color(0xFF090D0C)),
                    Offset(x, y),
                    Offset(x + chipW, y + chipH),
                ),
                topLeft = Offset(x, y),
                size = Size(chipW, chipH),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
    }
    // Gold edge contacts and the physical key notch identify this as RAM even without colour.
    repeat(11) { index ->
        val gapStart = left + boardW * 0.47f
        val gapEnd = left + boardW * 0.55f
        val x = left + boardW * (0.035f + index * 0.086f)
        if (x !in gapStart..gapEnd) {
            drawRoundRect(
                Color(0xFFFFCB62),
                Offset(x, top + boardH * 0.91f),
                Size(boardW * 0.045f, boardH * 0.09f),
                CornerRadius(1.5.dp.toPx()),
            )
        }
    }
    drawPath(
        Path().apply {
            moveTo(left + boardW * 0.47f, top + boardH)
            lineTo(left + boardW * 0.49f, top + boardH * 0.93f)
            lineTo(left + boardW * 0.53f, top + boardH * 0.93f)
            lineTo(left + boardW * 0.55f, top + boardH)
            close()
        },
        Color(0xFF030807),
    )
}

/** The complete Battery tile is the pack: shell, terminal, charge fill and level separators. */
private fun DrawScope.drawBatteryCard(level: Float, accent: Color) {
    val w = size.width
    val h = size.height
    val left = w * 0.035f
    val top = h * 0.18f
    val bodyW = w * 0.80f
    val bodyH = h * 0.64f
    val terminalW = w * 0.11f
    val depth = h * 0.04f
    val fillColour = when {
        level <= 0.20f -> Color(0xFFFF5F66)
        level <= 0.40f -> Color(0xFFFFB64D)
        else -> accent
    }
    drawRoundRect(
        Color.Black.copy(alpha = 0.42f),
        Offset(left + depth, top + depth * 1.6f),
        Size(bodyW, bodyH),
        CornerRadius(14.dp.toPx()),
    )
    drawRoundRect(
        brush = metalBrush(Color(0xFF293139), Offset(left, top), Offset(left, top + bodyH)),
        topLeft = Offset(left, top),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(14.dp.toPx()),
    )
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(Color(0xFFC1CBD1), Color(0xFF59636A), Color(0xFF171C21)),
            Offset(left + bodyW, top),
            Offset(left + bodyW + terminalW, top + bodyH),
        ),
        topLeft = Offset(left + bodyW, top + bodyH * 0.31f),
        size = Size(terminalW, bodyH * 0.38f),
        cornerRadius = CornerRadius(4.dp.toPx()),
    )
    val inset = 8.dp.toPx()
    val innerW = bodyW - inset * 2f
    val innerH = bodyH - inset * 2f
    drawRoundRect(
        Color(0xFF050809),
        Offset(left + inset, top + inset),
        Size(innerW, innerH),
        CornerRadius(9.dp.toPx()),
    )
    if (level > 0f) {
        val fillW = (innerW * level).coerceAtLeast(5.dp.toPx())
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(lerp(fillColour, Color.White, 0.34f), fillColour.copy(alpha = 0.56f), fillColour.copy(alpha = 0.28f)),
                top + inset,
                top + bodyH - inset,
            ),
            topLeft = Offset(left + inset, top + inset),
            size = Size(fillW, innerH),
            cornerRadius = CornerRadius(9.dp.toPx()),
        )
    }
    repeat(3) { index ->
        val x = left + inset + innerW * (index + 1) / 4f
        drawLine(Color.White.copy(alpha = 0.14f), Offset(x, top + inset), Offset(x, top + bodyH - inset), 1.dp.toPx())
    }
    drawRoundRect(
        Color.White.copy(alpha = 0.24f),
        Offset(left, top),
        Size(bodyW, bodyH),
        CornerRadius(14.dp.toPx()),
        style = Stroke(1.2.dp.toPx()),
    )
}

/** The complete Temperature tile is a glass-and-metal horizontal thermometer. */
private fun DrawScope.drawThermometerCard(level: Float, accent: Color) {
    val w = size.width
    val h = size.height
    val barrelLeft = w * 0.13f
    val barrelTop = h * 0.20f
    val barrelW = w * 0.80f
    val barrelH = h * 0.62f
    val bulb = Offset(w * 0.16f, h * 0.69f)
    val bulbRadius = minOf(w, h) * 0.14f
    val depth = 5.dp.toPx()
    drawRoundRect(
        Color.Black.copy(alpha = 0.42f),
        Offset(barrelLeft + depth, barrelTop + depth),
        Size(barrelW, barrelH),
        CornerRadius(barrelH / 2f),
    )
    drawCircle(Color.Black.copy(alpha = 0.42f), bulbRadius, bulb + Offset(depth, depth))
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color(0xFF74818A), Color(0xFFE1E7E9), Color(0xFF3B464E), Color(0xFF12181C)),
            barrelTop,
            barrelTop + barrelH,
        ),
        topLeft = Offset(barrelLeft, barrelTop),
        size = Size(barrelW, barrelH),
        cornerRadius = CornerRadius(barrelH / 2f),
    )
    val glassTop = barrelTop + barrelH * 0.64f
    val glassLeft = bulb.x
    val glassRight = barrelLeft + barrelW * 0.90f
    drawRoundRect(
        Color(0xFF080B0D),
        Offset(glassLeft, glassTop),
        Size(glassRight - glassLeft, barrelH * 0.13f),
        CornerRadius(barrelH * 0.065f),
    )
    val mercuryRight = glassLeft + (glassRight - glassLeft) * level.coerceIn(0.04f, 1f)
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color(0xFFFFC0A1), accent, Color(0xFF8A1720)), glassTop, glassTop + barrelH * 0.13f),
        topLeft = Offset(glassLeft, glassTop),
        size = Size((mercuryRight - glassLeft).coerceAtLeast(3.dp.toPx()), barrelH * 0.13f),
        cornerRadius = CornerRadius(barrelH * 0.065f),
    )
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFFFFD3BD), accent, Color(0xFF72151B)),
            bulb - Offset(bulbRadius * 0.3f, bulbRadius * 0.3f),
            bulbRadius * 1.5f,
        ),
        bulbRadius,
        bulb,
    )
    drawCircle(Color.White.copy(alpha = 0.28f), bulbRadius, bulb, style = Stroke(1.2.dp.toPx()))
    repeat(5) { index ->
        val x = barrelLeft + barrelW * (0.29f + index * 0.12f)
        drawLine(
            Color(0xFFFFB7A0),
            Offset(x, barrelTop + barrelH * 0.12f),
            Offset(x, barrelTop + barrelH * (if (index % 2 == 0) 0.23f else 0.19f)),
            1.3.dp.toPx(),
            StrokeCap.Round,
        )
    }
}

/** The complete Refresh tile is a perspective display, bezel, sweep and physical stand. */
private fun DrawScope.drawRefreshCard(level: Float, accent: Color) {
    val w = size.width
    val h = size.height
    val shadow = Path().apply {
        moveTo(w * 0.10f, h * 0.13f)
        lineTo(w * 0.92f, h * 0.09f)
        lineTo(w * 0.96f, h * 0.75f)
        lineTo(w * 0.14f, h * 0.80f)
        close()
    }
    val face = Path().apply {
        moveTo(w * 0.055f, h * 0.07f)
        lineTo(w * 0.875f, h * 0.035f)
        lineTo(w * 0.915f, h * 0.695f)
        lineTo(w * 0.095f, h * 0.74f)
        close()
    }
    drawPath(shadow, Color.Black.copy(alpha = 0.43f))
    drawPath(
        face,
        Brush.linearGradient(
            listOf(Color(0xFF69747E), Color(0xFF1C232A), Color(0xFF06090C)),
            Offset(w * 0.05f, h * 0.03f),
            Offset(w * 0.92f, h * 0.75f),
        ),
    )
    val screen = Path().apply {
        moveTo(w * 0.105f, h * 0.12f)
        lineTo(w * 0.82f, h * 0.09f)
        lineTo(w * 0.85f, h * 0.61f)
        lineTo(w * 0.135f, h * 0.65f)
        close()
    }
    drawPath(
        screen,
        Brush.linearGradient(
            listOf(Color(0xFF102C43), Color(0xFF101528), Color(0xFF231A43)),
            Offset(w * 0.11f, h * 0.1f),
            Offset(w * 0.85f, h * 0.65f),
        ),
    )
    drawLine(Color.White.copy(alpha = 0.25f), Offset(w * 0.12f, h * 0.14f), Offset(w * 0.80f, h * 0.11f), 1.2.dp.toPx())
    val sweepCentre = Offset(w * 0.76f, h * 0.22f)
    val radius = minOf(w, h) * 0.075f
    val sweep = 290f * level.coerceIn(0.18f, 1f)
    drawArc(
        accent.copy(alpha = 0.72f),
        startAngle = 135f,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = sweepCentre - Offset(radius, radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round),
    )
    val angle = Math.toRadians((135f + sweep).toDouble())
    drawCircle(
        Color.White,
        1.8.dp.toPx(),
        Offset(
            sweepCentre.x + cos(angle).toFloat() * radius,
            sweepCentre.y + sin(angle).toFloat() * radius,
        ),
    )
    drawPath(
        Path().apply {
            moveTo(w * 0.43f, h * 0.73f)
            lineTo(w * 0.58f, h * 0.72f)
            lineTo(w * 0.64f, h * 0.90f)
            lineTo(w * 0.36f, h * 0.91f)
            close()
        },
        Brush.verticalGradient(listOf(Color(0xFF7B868E), Color(0xFF12171A)), h * 0.71f, h * 0.91f),
    )
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF707A82), Color(0xFF111518)), h * 0.87f, h * 0.98f),
        Offset(w * 0.25f, h * 0.89f),
        Size(w * 0.51f, h * 0.08f),
        CornerRadius(4.dp.toPx()),
    )
}

private fun DrawScope.drawFallbackCard(accent: Color) {
    val inset = 9.dp.toPx()
    drawRoundRect(
        brush = metalBrush(Color(0xFF2A2737), Offset(inset, inset), Offset(size.width - inset, size.height - inset)),
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2f, size.height - inset * 2f),
        cornerRadius = CornerRadius(16.dp.toPx()),
    )
    drawRoundRect(
        accent.copy(alpha = 0.35f),
        Offset(inset, inset),
        Size(size.width - inset * 2f, size.height - inset * 2f),
        CornerRadius(16.dp.toPx()),
        style = Stroke(1.dp.toPx()),
    )
}

private fun metalBrush(base: Color, start: Offset, end: Offset): Brush = Brush.linearGradient(
    listOf(lerp(base, Color.White, 0.34f), base, lerp(base, Color.Black, 0.55f)),
    start,
    end,
)

private const val METRIC_CARD_HEIGHT = 164
