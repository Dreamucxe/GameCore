package com.gamecore.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamecore.ui.components.Readout
import com.gamecore.ui.components.Sparkline
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.theme.StatusGood
import com.gamecore.ui.theme.StatLabelStyle
import com.gamecore.ui.theme.StatValueStyle
import com.gamecore.ui.theme.ThermalCritical
import com.gamecore.ui.theme.ThermalWarning
import com.gamecore.ui.theme.readableOn
import kotlin.math.ceil
import kotlin.math.sqrt

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
 * The theme colours every tile is drawn from.
 *
 * The bodies are built out of the same surface ramp as the cards around them, so an object reads as part
 * of the dashboard rather than a bright illustration pasted on top of it. Only [accent] carries the app's
 * primary colour (or a semantic warning colour); everything else is neutral.
 */
private data class MetricPalette(
    val accent: Color,
    val value: Color,
    val bodyTop: Color,
    val bodyBottom: Color,
    val edge: Color,
    val face: Color,
    val ink: Color,
    val muted: Color,
    val track: Color,
    val pin: Color,
)

/**
 * A Home reading whose *whole tile* is the instrument.
 *
 * There is no standard card beneath this composable and no small logo parked beside the number. The CPU
 * tile is a pinned processor package whose die is split into one cell per core, with that core's current
 * clock. RAM is the pinned module from the reference art, Battery is the portrait pack with its terminal and
 * bolt, Temperature is a vertical bulb thermometer with a printed scale, and Refresh is a monitor on its
 * stand. The bounds remain equal so the two-column grid stays stable, but transparent corners expose each
 * object's actual silhouette.
 *
 * @param cpuCores one entry per logical core, in kernel order. Empty until a sample has been read; the
 *   die then stays blank rather than inventing clocks for cores the device has not reported.
 */
@Composable
internal fun HomeMetric3DCard(
    readout: Readout,
    trend: List<Float>,
    cpuCores: List<HomeCpuCore>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = HomeMetricShape.fromLabel(readout.label)
    val scheme = MaterialTheme.colorScheme
    // One app accent for every object, with the semantic colours taking over only when the reading
    // itself is a warning. The silhouettes already distinguish the five instruments; a rainbow of
    // hardware paint made them read as stickers beside the theme rather than instruments inside it.
    val accent = when (readout.tone) {
        Tone.Good -> StatusGood
        Tone.Warning -> ThermalWarning
        Tone.Danger -> ThermalCritical
        else -> scheme.primary
    }
    val palette = MetricPalette(
        accent = accent,
        value = if (readout.isAvailable) readout.tone.colour() else scheme.onSurfaceVariant,
        bodyTop = lerp(scheme.surfaceVariant, scheme.onSurface, 0.08f),
        bodyBottom = lerp(scheme.surface, scheme.surfaceVariant, 0.55f),
        edge = scheme.outline,
        face = lerp(scheme.surface, scheme.background, 0.35f),
        ink = scheme.onSurface,
        muted = scheme.onSurfaceVariant,
        track = scheme.surfaceVariant,
        pin = lerp(scheme.outline, scheme.onSurfaceVariant, 0.35f),
    )
    // Artwork may only be filled when there is a reading to fill it with. A missing temperature is an
    // empty tube, not a thermometer frozen at a plausible-looking middle.
    val level = readout.fraction?.coerceIn(0f, 1f) ?: when {
        !readout.isAvailable -> 0f
        shape == HomeMetricShape.TEMPERATURE -> 0.55f
        shape == HomeMetricShape.REFRESH -> 0.78f
        else -> 0.52f
    }
    val interaction = remember { MutableInteractionSource() }
    val spoken = buildString {
        append(readout.label).append(", ").append(readout.value)
        readout.detail?.let { append(", ").append(it) }
        if (shape == HomeMetricShape.CPU && cpuCores.isNotEmpty()) {
            append(". Core clocks: ")
            cpuCores.forEachIndexed { index, core ->
                if (index > 0) append(", ")
                append("core ").append(core.index).append(' ').append(core.speed)
            }
        }
        append(". Open Performance")
    }
    Box(
        modifier = modifier
            .height(METRIC_CARD_HEIGHT.dp)
            // A rectangular Material ripple would briefly reveal the exact old card this design is
            // replacing; the object and its semantic rim already show the press target.
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
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val width = maxWidth
            val height = maxHeight
            Canvas(modifier = Modifier.fillMaxSize()) {
                when (shape) {
                    HomeMetricShape.CPU -> drawCpuCard(palette, cpuCores)
                    HomeMetricShape.MEMORY -> drawMemoryCard(palette)
                    HomeMetricShape.BATTERY -> drawBatteryCard(level, palette)
                    HomeMetricShape.TEMPERATURE -> drawThermometerCard(level, palette)
                    HomeMetricShape.REFRESH -> drawRefreshCard(palette)
                    HomeMetricShape.UNKNOWN -> drawFallbackCard(palette)
                }
            }
            when (shape) {
                HomeMetricShape.CPU -> CpuCopy(readout, cpuCores, palette, width, height)
                HomeMetricShape.MEMORY -> MemoryCopy(readout, trend, palette, width, height)
                HomeMetricShape.BATTERY -> BatteryCopy(readout, palette, width, height)
                HomeMetricShape.TEMPERATURE -> TemperatureCopy(readout, palette, width, height)
                HomeMetricShape.REFRESH -> RefreshCopy(readout, palette, width, height)
                HomeMetricShape.UNKNOWN -> FallbackCopy(readout, palette)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ CPU

/**
 * The package die is divided by this rule: four columns for the usual 7–12 core phone, a narrower grid
 * for four or six, and a square-ish grid beyond that. Returned as (columns, rows).
 */
internal fun cpuCoreGrid(coreCount: Int): Pair<Int, Int> {
    if (coreCount <= 0) return 0 to 0
    val columns = when {
        coreCount <= 2 -> coreCount
        coreCount <= 4 -> 2
        coreCount <= 6 -> 3
        coreCount <= 12 -> 4
        else -> ceil(sqrt(coreCount.toDouble())).toInt()
    }
    return columns to (coreCount + columns - 1) / columns
}

@Composable
private fun CpuCopy(
    readout: Readout,
    cores: List<HomeCpuCore>,
    palette: MetricPalette,
    width: Dp,
    height: Dp,
) {
    Row(
        modifier = Modifier
            .offset(
                x = width * CPU_BODY_LEFT + width * 0.025f,
                y = height * 0.155f,
            )
            .width(width * (CPU_BODY_RIGHT - CPU_BODY_LEFT) - width * 0.05f)
            .height(height * 0.185f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "CPU",
                style = StatLabelStyle.copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 1.sp),
                color = palette.ink,
                maxLines = 1,
            )
            readout.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialCaption,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = readout.value,
            style = StatValueStyle.copy(fontSize = 23.sp, lineHeight = 25.sp),
            color = palette.value,
            maxLines = 1,
        )
    }
    CpuCoreGrid(
        cores = cores,
        palette = palette,
        modifier = Modifier
            .offset(
                x = width * CPU_DIE_LEFT,
                y = height * CPU_DIE_TOP,
            )
            .width(width * (CPU_DIE_RIGHT - CPU_DIE_LEFT))
            .height(height * (CPU_DIE_BOTTOM - CPU_DIE_TOP)),
    )
}

@Composable
private fun CpuCoreGrid(cores: List<HomeCpuCore>, palette: MetricPalette, modifier: Modifier) {
    val (columns, rows) = cpuCoreGrid(cores.size)
    if (columns == 0 || rows == 0) return
    Column(modifier = modifier) {
        repeat(rows) { row ->
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                repeat(columns) { column ->
                    val core = cores.getOrNull(row * columns + column)
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (core != null) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "C${core.index}",
                                    style = MaterialCaption.copy(fontSize = 6.5.sp, lineHeight = 8.sp),
                                    color = if (core.isOnline) palette.muted else palette.muted.copy(alpha = 0.55f),
                                    maxLines = 1,
                                )
                                Text(
                                    text = core.speed,
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 8.sp,
                                        lineHeight = 9.sp,
                                    ),
                                    color = if (core.isOnline) palette.ink else palette.muted.copy(alpha = 0.55f),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A square package with pins and traces on all four sides, like the reference processor art. */
private fun DrawScope.drawCpuCard(palette: MetricPalette, cores: List<HomeCpuCore>) {
    val w = size.width
    val h = size.height
    val left = w * CPU_BODY_LEFT
    val top = h * CPU_BODY_TOP
    val right = w * CPU_BODY_RIGHT
    val bottom = h * CPU_BODY_BOTTOM
    val bodyW = right - left
    val bodyH = bottom - top
    val pinLength = h * 0.038f
    val pinColour = palette.pin
    val traceColour = palette.edge.copy(alpha = 0.72f)

    // Traces and nodes first, so the package face covers their inner ends and they read as legs.
    repeat(5) { index ->
        val fraction = (index + 1) / 6f
        val topX = left + bodyW * fraction
        val bottomX = left + bodyW * fraction
        val leftY = top + bodyH * fraction
        val rightY = top + bodyH * fraction
        val sway = if (index % 2 == 0) -w * 0.018f else w * 0.018f
        drawLine(
            traceColour,
            Offset(topX, top - pinLength),
            Offset((topX + sway).coerceIn(w * 0.02f, w * 0.98f), h * 0.018f),
            1.dp.toPx(),
            StrokeCap.Round,
        )
        drawCircle(pinColour, 1.5.dp.toPx(), Offset((topX + sway).coerceIn(w * 0.02f, w * 0.98f), h * 0.018f))
        drawLine(
            traceColour,
            Offset(bottomX, bottom + pinLength),
            Offset((bottomX + sway).coerceIn(w * 0.02f, w * 0.98f), h * 0.982f),
            1.dp.toPx(),
            StrokeCap.Round,
        )
        drawCircle(pinColour, 1.5.dp.toPx(), Offset((bottomX + sway).coerceIn(w * 0.02f, w * 0.98f), h * 0.982f))
        drawLine(
            traceColour,
            Offset(left - pinLength, leftY),
            Offset(w * 0.018f, (leftY + sway).coerceIn(h * 0.02f, h * 0.98f)),
            1.dp.toPx(),
            StrokeCap.Round,
        )
        drawCircle(pinColour, 1.5.dp.toPx(), Offset(w * 0.018f, (leftY + sway).coerceIn(h * 0.02f, h * 0.98f)))
        drawLine(
            traceColour,
            Offset(right + pinLength, rightY),
            Offset(w * 0.982f, (rightY + sway).coerceIn(h * 0.02f, h * 0.98f)),
            1.dp.toPx(),
            StrokeCap.Round,
        )
        drawCircle(pinColour, 1.5.dp.toPx(), Offset(w * 0.982f, (rightY + sway).coerceIn(h * 0.02f, h * 0.98f)))

        drawLine(pinColour, Offset(topX, top), Offset(topX, top - pinLength), 3.dp.toPx(), StrokeCap.Round)
        drawLine(pinColour, Offset(bottomX, bottom), Offset(bottomX, bottom + pinLength), 3.dp.toPx(), StrokeCap.Round)
        drawLine(pinColour, Offset(left, leftY), Offset(left - pinLength, leftY), 3.dp.toPx(), StrokeCap.Round)
        drawLine(pinColour, Offset(right, rightY), Offset(right + pinLength, rightY), 3.dp.toPx(), StrokeCap.Round)
    }

    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(palette.bodyTop, palette.bodyBottom, palette.face),
            Offset(left, top),
            Offset(right, bottom),
        ),
        topLeft = Offset(left, top),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(16.dp.toPx()),
    )
    drawRoundRect(
        color = palette.edge,
        topLeft = Offset(left, top),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(16.dp.toPx()),
        style = Stroke(1.4.dp.toPx()),
    )
    drawRoundRect(
        color = palette.edge.copy(alpha = 0.28f),
        topLeft = Offset(left + 2.dp.toPx(), top + 2.dp.toPx()),
        size = Size(bodyW - 4.dp.toPx(), bodyH - 4.dp.toPx()),
        cornerRadius = CornerRadius(14.dp.toPx()),
        style = Stroke(0.8.dp.toPx()),
    )
    // The header rule separates the printed CPU figure from the die, like a package lid.
    drawLine(
        color = palette.edge.copy(alpha = 0.65f),
        start = Offset(left + 3.dp.toPx(), h * 0.365f),
        end = Offset(right - 3.dp.toPx(), h * 0.365f),
        strokeWidth = 1.dp.toPx(),
    )

    val dieLeft = w * CPU_DIE_LEFT
    val dieTop = h * CPU_DIE_TOP
    val dieRight = w * CPU_DIE_RIGHT
    val dieBottom = h * CPU_DIE_BOTTOM
    val dieW = dieRight - dieLeft
    val dieH = dieBottom - dieTop
    drawRoundRect(
        color = palette.face,
        topLeft = Offset(dieLeft, dieTop),
        size = Size(dieW, dieH),
        cornerRadius = CornerRadius(6.dp.toPx()),
    )
    drawRoundRect(
        color = palette.accent.copy(alpha = 0.42f),
        topLeft = Offset(dieLeft, dieTop),
        size = Size(dieW, dieH),
        cornerRadius = CornerRadius(6.dp.toPx()),
        style = Stroke(1.dp.toPx()),
    )

    val (columns, rows) = cpuCoreGrid(cores.size)
    if (columns == 0 || rows == 0) return
    val cellW = dieW / columns
    val cellH = dieH / rows
    val gap = 1.6.dp.toPx()
    cores.forEachIndexed { index, core ->
        val column = index % columns
        val row = index / columns
        val cellLeft = dieLeft + cellW * column + gap
        val cellTop = dieTop + cellH * row + gap
        val cellInnerW = cellW - gap * 2f
        val cellInnerH = cellH - gap * 2f
        drawRoundRect(
            color = palette.track.copy(alpha = 0.55f),
            topLeft = Offset(cellLeft, cellTop),
            size = Size(cellInnerW, cellInnerH),
            cornerRadius = CornerRadius(3.dp.toPx()),
        )
        drawRoundRect(
            color = if (core.isOnline) palette.accent.copy(alpha = 0.22f) else palette.edge.copy(alpha = 0.55f),
            topLeft = Offset(cellLeft, cellTop),
            size = Size(cellInnerW, cellInnerH),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = Stroke(0.9.dp.toPx()),
        )
        val load = core.load?.takeIf { core.isOnline }?.coerceIn(0f, 1f) ?: return@forEachIndexed
        val barWidth = (cellInnerW - 3.dp.toPx()) * load
        if (barWidth > 0f) {
            drawRoundRect(
                color = palette.accent.copy(alpha = 0.85f),
                topLeft = Offset(cellLeft + 1.5.dp.toPx(), cellTop + cellInnerH - 3.dp.toPx()),
                size = Size(barWidth, 1.8.dp.toPx()),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

// ----------------------------------------------------------------------------------------------- RAM

/** The reference RAM module: a grey frame, a dark centre face, and pins above and below. */
private fun DrawScope.drawMemoryCard(palette: MetricPalette) {
    val w = size.width
    val h = size.height
    val left = w * 0.06f
    val top = h * 0.13f
    val right = w * 0.94f
    val bottom = h * 0.87f
    val bodyW = right - left
    val bodyH = bottom - top
    val pinColour = palette.pin.copy(alpha = 0.78f)

    repeat(10) { index ->
        val x = left + bodyW * (0.055f + index * 0.098f)
        drawRoundRect(
            color = pinColour,
            topLeft = Offset(x, top - h * 0.068f),
            size = Size(w * 0.022f, h * 0.068f),
            cornerRadius = CornerRadius(1.2.dp.toPx()),
        )
        drawRoundRect(
            color = pinColour,
            topLeft = Offset(x, bottom),
            size = Size(w * 0.022f, h * 0.068f),
            cornerRadius = CornerRadius(1.2.dp.toPx()),
        )
    }

    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(palette.bodyTop, palette.bodyBottom),
            Offset(left, top),
            Offset(right, bottom),
        ),
        topLeft = Offset(left, top),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(8.dp.toPx()),
    )
    drawRoundRect(
        color = palette.edge,
        topLeft = Offset(left, top),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(8.dp.toPx()),
        style = Stroke(1.3.dp.toPx()),
    )
    val faceLeft = w * 0.105f
    val faceTop = h * 0.19f
    val faceRight = w * 0.895f
    val faceBottom = h * 0.81f
    drawRoundRect(
        color = palette.face,
        topLeft = Offset(faceLeft, faceTop),
        size = Size(faceRight - faceLeft, faceBottom - faceTop),
        cornerRadius = CornerRadius(4.dp.toPx()),
    )
    drawRoundRect(
        color = palette.accent.copy(alpha = 0.34f),
        topLeft = Offset(faceLeft, faceTop),
        size = Size(faceRight - faceLeft, faceBottom - faceTop),
        cornerRadius = CornerRadius(4.dp.toPx()),
        style = Stroke(1.1.dp.toPx()),
    )
    // The keying notch on the bottom edge keeps the module recognisable at a glance.
    val notch = w * 0.045f
    drawRoundRect(
        color = palette.face,
        topLeft = Offset(w * 0.50f - notch / 2f, bottom - h * 0.018f),
        size = Size(notch, h * 0.018f),
        cornerRadius = CornerRadius(1.dp.toPx()),
    )
}

@Composable
private fun MemoryCopy(
    readout: Readout,
    trend: List<Float>,
    palette: MetricPalette,
    width: Dp,
    height: Dp,
) {
    Column(
        modifier = Modifier
            .offset(x = width * 0.125f, y = height * 0.205f)
            .width(width * 0.75f)
            .height(height * 0.59f)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "RAM",
            style = StatLabelStyle.copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 1.4.sp),
            color = palette.muted,
            maxLines = 1,
        )
        Text(
            text = readout.value,
            style = StatValueStyle.copy(fontSize = 24.sp, lineHeight = 27.sp),
            color = palette.value,
            maxLines = 1,
        )
        readout.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialCaption,
                color = palette.muted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            trend.size >= 2 -> {
                Spacer(modifier = Modifier.height(4.dp))
                Sparkline(
                    values = trend,
                    colour = palette.accent,
                    height = 16,
                    modifier = Modifier.fillMaxWidth(0.72f),
                )
            }
            readout.fraction != null -> {
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.track),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(readout.fraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(palette.accent),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------- Battery

/** The reference pack: portrait body, top terminal, green charge and a white lightning mark. */
private fun DrawScope.drawBatteryCard(level: Float, palette: MetricPalette) {
    val w = size.width
    val h = size.height
    val left = w * 0.30f
    val top = h * 0.13f
    val right = w * 0.70f
    val bottom = h * 0.90f
    val bodyW = right - left
    val bodyH = bottom - top

    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(palette.bodyTop, palette.bodyBottom),
            h * 0.055f,
            h * 0.13f,
        ),
        topLeft = Offset(w * 0.42f, h * 0.055f),
        size = Size(w * 0.16f, h * 0.075f),
        cornerRadius = CornerRadius(3.dp.toPx()),
    )
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(palette.bodyTop, palette.bodyBottom),
            Offset(left, top),
            Offset(right, bottom),
        ),
        topLeft = Offset(left, top),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(18.dp.toPx()),
    )
    drawRoundRect(
        color = palette.edge,
        topLeft = Offset(left, top),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(18.dp.toPx()),
        style = Stroke(1.8.dp.toPx()),
    )

    val inset = 5.dp.toPx()
    val innerLeft = left + inset
    val innerTop = top + inset
    val innerW = bodyW - inset * 2f
    val innerH = bodyH - inset * 2f
    drawRoundRect(
        color = palette.face,
        topLeft = Offset(innerLeft, innerTop),
        size = Size(innerW, innerH),
        cornerRadius = CornerRadius(13.dp.toPx()),
    )
    if (level > 0f) {
        val fillHeight = (innerH * level).coerceAtLeast(3.dp.toPx())
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(
                    lerp(palette.accent, Color.White, 0.14f),
                    palette.accent,
                    lerp(palette.accent, palette.face, 0.38f),
                ),
                innerTop + innerH - fillHeight,
                innerTop + innerH,
            ),
            topLeft = Offset(innerLeft, innerTop + innerH - fillHeight),
            size = Size(innerW, fillHeight),
            cornerRadius = CornerRadius(minOf(13.dp.toPx(), fillHeight / 2f)),
        )
    }
    drawRoundRect(
        color = palette.edge.copy(alpha = 0.55f),
        topLeft = Offset(innerLeft, innerTop),
        size = Size(innerW, innerH),
        cornerRadius = CornerRadius(13.dp.toPx()),
        style = Stroke(1.dp.toPx()),
    )

    val boltTop = h * 0.505f
    val boltHeight = h * 0.19f
    val boltWidth = bodyW * 0.36f
    val centreX = (left + right) / 2f
    drawPath(
        path = Path().apply {
            moveTo(centreX + boltWidth * 0.10f, boltTop)
            lineTo(centreX - boltWidth * 0.44f, boltTop + boltHeight * 0.58f)
            lineTo(centreX - boltWidth * 0.04f, boltTop + boltHeight * 0.58f)
            lineTo(centreX - boltWidth * 0.22f, boltTop + boltHeight)
            lineTo(centreX + boltWidth * 0.46f, boltTop + boltHeight * 0.38f)
            lineTo(centreX + boltWidth * 0.06f, boltTop + boltHeight * 0.38f)
            close()
        },
        color = if (level > 0.37f) readableOn(palette.accent) else palette.ink,
    )
}

@Composable
private fun BatteryCopy(
    readout: Readout,
    palette: MetricPalette,
    width: Dp,
    height: Dp,
) {
    val level = readout.fraction?.coerceIn(0f, 1f) ?: 0f
    val bodyLeft = width * 0.30f
    val bodyWidth = width * 0.40f
    val onFill = readableOn(palette.accent)
    val labelColour = if (level > 0.90f) onFill else palette.muted
    val valueColour = when {
        !readout.isAvailable -> palette.muted
        level > 0.70f -> onFill
        else -> palette.value
    }
    val detailColour = if (level > 0.20f) onFill else palette.muted
    Box(modifier = Modifier.fillMaxSize()) {
        CenteredText(
            text = "BATTERY",
            modifier = Modifier
                .offset(x = bodyLeft, y = height * 0.155f)
                .width(bodyWidth)
                .height(height * 0.08f),
            style = StatLabelStyle.copy(fontSize = 8.sp, lineHeight = 10.sp, letterSpacing = 0.8.sp),
            color = labelColour,
        )
        CenteredText(
            text = readout.value,
            modifier = Modifier
                .offset(x = bodyLeft, y = height * 0.28f)
                .width(bodyWidth)
                .height(height * 0.18f),
            style = StatValueStyle.copy(fontSize = 23.sp, lineHeight = 26.sp),
            color = valueColour,
        )
        readout.detail?.let { detail ->
            CenteredText(
                text = detail,
                modifier = Modifier
                    .offset(x = bodyLeft, y = height * 0.735f)
                    .width(bodyWidth)
                    .height(height * 0.135f),
                style = MaterialCaption.copy(fontSize = 8.sp, lineHeight = 9.5.sp),
                color = detailColour,
                maxLines = 2,
            )
        }
    }
}

// --------------------------------------------------------------------------------------- Temperature

/** The reference thermometer: cyan glass, red bulb, rising red column and a printed scale. */
private fun DrawScope.drawThermometerCard(level: Float, palette: MetricPalette) {
    val w = size.width
    val h = size.height
    val tubeLeft = w * 0.145f
    val tubeRight = w * 0.295f
    val tubeTop = h * 0.075f
    val tubeBottom = h * 0.735f
    val tubeW = tubeRight - tubeLeft
    val tubeH = tubeBottom - tubeTop
    val bulb = Offset(w * 0.22f, h * 0.805f)
    val bulbRadius = minOf(w, h) * 0.135f
    val outline = palette.accent.copy(alpha = 0.82f)
    val glass = palette.track
    val mercury = palette.accent

    drawRoundRect(
        color = outline,
        topLeft = Offset(tubeLeft, tubeTop),
        size = Size(tubeW, tubeH),
        cornerRadius = CornerRadius(tubeW / 2f),
    )
    drawCircle(color = outline, radius = bulbRadius, center = bulb)

    val glassInset = 4.dp.toPx()
    val glassLeft = tubeLeft + glassInset
    val glassTop = tubeTop + glassInset
    val glassW = tubeW - glassInset * 2f
    val glassH = tubeH - glassInset * 2f
    drawRoundRect(
        color = glass,
        topLeft = Offset(glassLeft, glassTop),
        size = Size(glassW, glassH),
        cornerRadius = CornerRadius(glassW / 2f),
    )
    drawCircle(color = glass, radius = bulbRadius - glassInset, center = bulb)
    drawCircle(color = mercury, radius = (bulbRadius - glassInset * 2f).coerceAtLeast(1f), center = bulb)

    // The column starts at the bulb and rises inside the glass. Zero means empty glass, not a cold red
    // block sitting at the bottom pretending to be a measurement.
    if (level > 0f) {
        val columnHeight = glassH * level
        drawRoundRect(
            color = mercury,
            topLeft = Offset(glassLeft, glassTop + glassH - columnHeight),
            size = Size(glassW, columnHeight + (bulb.y - (glassTop + glassH))),
            cornerRadius = CornerRadius(glassW / 2f),
        )
        drawRect(
            color = mercury,
            topLeft = Offset(glassLeft, glassTop + glassH - 1.dp.toPx()),
            size = Size(glassW, (bulb.y - (glassTop + glassH)) + 1.dp.toPx()),
        )
    }
    drawCircle(
        color = palette.ink.copy(alpha = 0.28f),
        radius = bulbRadius * 0.20f,
        center = bulb - Offset(bulbRadius * 0.34f, bulbRadius * 0.34f),
    )

    // Scale ticks on both sides of the tube. They are graduations, not printed temperatures: the exact
    // critical value depends on which sensor produced the reading, and an invented number would lie.
    repeat(11) { index ->
        val y = tubeTop + tubeH * (0.05f + index * 0.09f)
        val long = index % 5 == 0
        drawLine(
            color = palette.accent.copy(alpha = 0.72f),
            start = Offset(tubeRight + 2.dp.toPx(), y),
            end = Offset(tubeRight + (if (long) 9.dp else 5.dp).toPx(), y),
            strokeWidth = if (long) 1.6.dp.toPx() else 1.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = palette.accent.copy(alpha = 0.38f),
            start = Offset(tubeLeft - 2.dp.toPx(), y),
            end = Offset(tubeLeft - (if (long) 7.dp else 4.dp).toPx(), y),
            strokeWidth = if (long) 1.3.dp.toPx() else 0.9.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun TemperatureCopy(
    readout: Readout,
    palette: MetricPalette,
    width: Dp,
    height: Dp,
) {
    Column(
        modifier = Modifier
            .offset(x = width * 0.345f, y = height * 0.13f)
            .width(width * 0.625f)
            .height(height * 0.75f),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "TEMPERATURE",
            style = StatLabelStyle.copy(fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 0.4.sp),
            color = palette.muted,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = readout.value,
            style = StatValueStyle.copy(fontSize = 24.sp, lineHeight = 27.sp),
            color = palette.value,
            maxLines = 1,
        )
        readout.detail?.let { detail ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = detail,
                style = MaterialCaption.copy(fontSize = 8.5.sp, lineHeight = 11.sp),
                color = palette.muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ------------------------------------------------------------------------------------------ Refresh

/** The refresh-rate tile is a display on a stand: theme-toned bezel, deep screen, physical base. */
private fun DrawScope.drawRefreshCard(palette: MetricPalette) {
    val w = size.width
    val h = size.height
    val left = w * 0.075f
    val top = h * 0.085f
    val right = w * 0.925f
    val bottom = h * 0.655f
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(palette.bodyTop, palette.bodyBottom),
            Offset(left, top),
            Offset(right, bottom),
        ),
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        cornerRadius = CornerRadius(12.dp.toPx()),
    )
    drawRoundRect(
        color = palette.edge,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        cornerRadius = CornerRadius(12.dp.toPx()),
        style = Stroke(1.6.dp.toPx()),
    )
    val screenLeft = w * 0.105f
    val screenTop = h * 0.12f
    val screenRight = w * 0.895f
    val screenBottom = h * 0.615f
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(
                lerp(palette.face, palette.accent, 0.10f),
                palette.face,
                lerp(palette.face, palette.accent, 0.05f),
            ),
            Offset(screenLeft, screenTop),
            Offset(screenRight, screenBottom),
        ),
        topLeft = Offset(screenLeft, screenTop),
        size = Size(screenRight - screenLeft, screenBottom - screenTop),
        cornerRadius = CornerRadius(6.dp.toPx()),
    )
    drawRoundRect(
        color = palette.accent.copy(alpha = 0.55f),
        topLeft = Offset(screenLeft, screenTop),
        size = Size(screenRight - screenLeft, screenBottom - screenTop),
        cornerRadius = CornerRadius(6.dp.toPx()),
        style = Stroke(1.2.dp.toPx()),
    )
    // A few scan lines give the panel depth without adding a shadow.
    repeat(3) { index ->
        val y = screenTop + (screenBottom - screenTop) * (0.22f + index * 0.26f)
        drawLine(
            color = palette.accent.copy(alpha = 0.10f),
            start = Offset(screenLeft + 4.dp.toPx(), y),
            end = Offset(screenRight - 4.dp.toPx(), y),
            strokeWidth = 1.dp.toPx(),
        )
    }
    drawPath(
        path = Path().apply {
            moveTo(w * 0.43f, bottom)
            lineTo(w * 0.57f, bottom)
            lineTo(w * 0.61f, h * 0.84f)
            lineTo(w * 0.39f, h * 0.84f)
            close()
        },
        brush = Brush.verticalGradient(
            listOf(palette.bodyTop, palette.bodyBottom, palette.face),
            bottom,
            h * 0.84f,
        ),
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(palette.bodyTop, palette.bodyBottom, palette.face),
            h * 0.84f,
            h * 0.91f,
        ),
        topLeft = Offset(w * 0.30f, h * 0.84f),
        size = Size(w * 0.40f, h * 0.07f),
        cornerRadius = CornerRadius(3.dp.toPx()),
    )
}

@Composable
private fun RefreshCopy(
    readout: Readout,
    palette: MetricPalette,
    width: Dp,
    height: Dp,
) {
    Column(
        modifier = Modifier
            .offset(x = width * 0.115f, y = height * 0.135f)
            .width(width * 0.77f)
            .height(height * 0.47f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "REFRESH RATE",
            style = StatLabelStyle.copy(fontSize = 8.5.sp, lineHeight = 10.sp, letterSpacing = 0.5.sp),
            color = palette.muted,
            maxLines = 1,
        )
        Text(
            text = readout.value,
            style = StatValueStyle.copy(fontSize = 22.sp, lineHeight = 25.sp),
            color = palette.value,
            maxLines = 1,
        )
        readout.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialCaption.copy(fontSize = 8.sp, lineHeight = 10.sp),
                color = palette.muted,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ------------------------------------------------------------------------------------------- common

@Composable
private fun CenteredText(
    text: String,
    modifier: Modifier,
    style: TextStyle,
    color: Color,
    maxLines: Int = 1,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = style,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun DrawScope.drawFallbackCard(palette: MetricPalette) {
    val inset = 9.dp.toPx()
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(palette.bodyTop, palette.bodyBottom, palette.face),
            Offset(inset, inset),
            Offset(size.width - inset, size.height - inset),
        ),
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2f, size.height - inset * 2f),
        cornerRadius = CornerRadius(18.dp.toPx()),
    )
    drawRoundRect(
        color = palette.edge,
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2f, size.height - inset * 2f),
        cornerRadius = CornerRadius(18.dp.toPx()),
        style = Stroke(1.2.dp.toPx()),
    )
    drawRoundRect(
        color = palette.accent.copy(alpha = 0.28f),
        topLeft = Offset(inset * 2f, inset * 2f),
        size = Size(size.width - inset * 4f, size.height - inset * 4f),
        cornerRadius = CornerRadius(14.dp.toPx()),
        style = Stroke(0.8.dp.toPx()),
    )
}

@Composable
private fun FallbackCopy(readout: Readout, palette: MetricPalette) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = readout.label.uppercase(),
            style = StatLabelStyle,
            color = palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = readout.value,
            style = StatValueStyle,
            color = palette.value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        readout.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialCaption,
                color = palette.muted,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// CPU package fractions, shared by the Canvas and the text overlay so a core's label always sits inside
// the cell that was drawn for it.
private const val CPU_BODY_LEFT = 0.08f
private const val CPU_BODY_RIGHT = 0.92f
private const val CPU_BODY_TOP = 0.14f
private const val CPU_BODY_BOTTOM = 0.91f
private const val CPU_DIE_LEFT = 0.17f
private const val CPU_DIE_RIGHT = 0.83f
private const val CPU_DIE_TOP = 0.38f
private const val CPU_DIE_BOTTOM = 0.86f

private val MaterialCaption = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 8.5.sp,
    lineHeight = 10.5.sp,
)

private const val METRIC_CARD_HEIGHT = 190
