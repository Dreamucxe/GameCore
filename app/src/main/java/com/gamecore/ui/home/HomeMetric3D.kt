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
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour
import com.gamecore.ui.theme.StatusGood
import com.gamecore.ui.theme.StatLabelStyle
import com.gamecore.ui.theme.StatValueStyle
import com.gamecore.ui.theme.ThermalCritical
import com.gamecore.ui.theme.ThermalWarning
import com.gamecore.ui.theme.readableOn
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin
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
 * tile is a pinned processor package whose die stacks the fastest core tier down the left and groups the
 * slower cores beside it, each labelled with its current clock. RAM is the square pinned chip package from
 * the reference art, Battery is the portrait pack with its terminal and bolt, Temperature is a vertical
 * bulb thermometer with a printed scale, and Refresh is the rounded card with its recessed face and
 * layered figure-eight glyph. The bounds remain equal so the two-column grid stays stable, but transparent
 * corners expose each object's actual silhouette.
 *
 * @param cpuCores one entry per logical core, in kernel order. Empty until a sample has been read; the
 *   die then stays blank rather than inventing clocks for cores the device has not reported.
 */
@Composable
internal fun HomeMetric3DCard(
    readout: Readout,
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
                HomeMetricShape.MEMORY -> MemoryCopy(readout, palette, width, height)
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
 * Columns and rows for the ordinary weighted grid, used when the die has no separate performance tier:
 * four columns for the usual 7–12 core phone, a narrower grid for four or six, and a square-ish grid
 * beyond that.
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

/**
 * One core's rectangle on the die, as fractions of the die's width and height.
 *
 * This follows the reference processor art rather than an even grid. The fastest reported tier — the
 * performance cores — is stacked down the left edge as large blocks, one per row. Every slower core is
 * grouped on the right in one or two columns, filling rows top to bottom. The split gives the performance
 * side enough width to look like the reference at the common two-prime/six-efficiency layout, while a
 * slower core's cell still shrinks when its ceiling is lower than its neighbours'. A core whose ceiling
 * the kernel hides falls back to the average of the cores that do report one, and when every core has the
 * same ceiling the die keeps a regular grid instead of inventing a performance tier.
 */
internal fun cpuCoreCells(cores: List<HomeCpuCore>): List<CpuCoreCell> {
    if (cores.isEmpty()) return emptyList()
    val columns = cpuCoreGrid(cores.size).first
    if (columns == 0) return emptyList()
    val weights = effectiveCoreWeights(cores)
    val fastest = weights.max()
    val fastIndices = weights.indices.filter { weights[it] >= fastest * FAST_TIER_TOLERANCE }
    // One fast core among many would make an invented "performance" section rather than a cluster, and
    // when every core is equally fast there is nothing to separate. Both keep the ordinary grid.
    if (fastIndices.size <= 1 || fastIndices.size == cores.size) {
        return weightedCoreCells(cores, weights, columns)
    }

    val fastSet = fastIndices.toHashSet()
    val slowIndices = weights.indices.filterNot { it in fastSet }
    val fastCount = fastIndices.size
    val fastWidth = (0.30f + 0.52f * fastCount / cores.size.toFloat())
        .coerceIn(0.36f, 0.58f)
    val cells = ArrayList<CpuCoreCell>(cores.size)

    // Performance cores: one wide block per row, stacked vertically on the left.
    fastIndices.forEachIndexed { row, index ->
        cells += CpuCoreCell(
            core = cores[index],
            left = 0f,
            top = row.toFloat() / fastCount,
            width = fastWidth,
            height = 1f / fastCount,
        )
    }

    // Everything else is grouped on the right. Two columns keep the six-core efficiency clusters of a
    // typical phone in the same 2 x 3 shape as the reference; one or two slow cores simply stack.
    val slowCores = slowIndices.map { cores[it] }
    val slowWeights = slowIndices.map { weights[it] }
    val slowColumns = if (slowCores.size <= 2) 1 else 2
    val slowCells = weightedCoreCells(slowCores, slowWeights, slowColumns)
    val slowLeft = fastWidth
    val slowWidth = 1f - fastWidth
    slowCells.forEach { cell ->
        cells += cell.copy(
            left = slowLeft + cell.left * slowWidth,
            width = cell.width * slowWidth,
        )
    }
    return cells.sortedBy { it.core.index }
}

/** A cell layout that lets each row's height follow its clocks and each cell's width follow its core. */
private fun weightedCoreCells(
    cores: List<HomeCpuCore>,
    weights: List<Float>,
    columns: Int,
): List<CpuCoreCell> {
    if (cores.isEmpty() || columns <= 0) return emptyList()
    val total = weights.sum()
    val rows = (cores.size + columns - 1) / columns
    val cells = ArrayList<CpuCoreCell>(cores.size)
    var top = 0f
    for (row in 0 until rows) {
        val start = row * columns
        val end = minOf(start + columns, cores.size)
        val rowWeight = weights.subList(start, end).sum()
        val height = rowWeight / total
        // A partial last row keeps the same cell width as a full row and centres itself, rather than
        // stretching two cores across the whole die.
        val occupied = (end - start).toFloat() / columns
        var left = (1f - occupied) / 2f
        for (index in start until end) {
            val width = occupied * weights[index] / rowWeight
            cells += CpuCoreCell(
                core = cores[index],
                left = left,
                top = top,
                width = width,
                height = height,
            )
            left += width
        }
        top += height
    }
    return cells
}

/** Unknown ceilings take the average of the cores that do report one, never an invented clock. */
private fun effectiveCoreWeights(cores: List<HomeCpuCore>): List<Float> {
    val known = cores.mapNotNull { core -> core.maxKHz.takeIf { it > 0L }?.toFloat() }
    val fallback = if (known.isEmpty()) 1f else known.average().toFloat()
    return cores.map { core -> core.maxKHz.takeIf { it > 0L }?.toFloat() ?: fallback }
}

/** One core's place inside the die, in 0..1 fractions so the Canvas and the text agree exactly. */
internal data class CpuCoreCell(
    val core: HomeCpuCore,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

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
                y = height * CPU_HEADER_TOP,
            )
            .width(width * (CPU_BODY_RIGHT - CPU_BODY_LEFT) - width * 0.05f)
            .height(height * CPU_HEADER_HEIGHT),
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
    val cells = remember(cores) { cpuCoreCells(cores) }
    if (cells.isEmpty()) return
    BoxWithConstraints(modifier = modifier) {
        val dieWidth = maxWidth
        val dieHeight = maxHeight
        cells.forEach { cell ->
            // The performance blocks on the left are several times the area of an efficiency cell, so
            // they get their own, larger stamp instead of the same 8 sp figure floating in a big box.
            val large = cell.height >= 0.33f && cell.width >= 0.30f
            val compact = !large && (cell.height < 0.22f || cell.width < 0.24f)
            Column(
                modifier = Modifier
                    .offset(x = dieWidth * cell.left, y = dieHeight * cell.top)
                    .width(dieWidth * cell.width)
                    .height(dieHeight * cell.height),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "C${cell.core.index}",
                    style = MaterialCaption.copy(
                        fontSize = when {
                            large -> 8.sp
                            compact -> 5.5.sp
                            else -> 6.5.sp
                        },
                        lineHeight = when {
                            large -> 9.5.sp
                            compact -> 7.sp
                            else -> 8.sp
                        },
                    ),
                    color = if (cell.core.isOnline) {
                        palette.muted
                    } else {
                        palette.muted.copy(alpha = 0.55f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                Text(
                    text = cell.core.speed,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = when {
                            large -> 11.sp
                            compact -> 6.8.sp
                            else -> 8.sp
                        },
                        lineHeight = when {
                            large -> 13.sp
                            compact -> 8.sp
                            else -> 9.sp
                        },
                    ),
                    color = if (cell.core.isOnline) palette.ink else palette.muted.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
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
        cornerRadius = CornerRadius(12.dp.toPx()),
    )
    drawRoundRect(
        color = palette.edge,
        topLeft = Offset(left, top),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(12.dp.toPx()),
        style = Stroke(0.8.dp.toPx()),
    )
    // The header rule separates the printed CPU figure from the die, like a package lid.
    drawLine(
        color = palette.edge.copy(alpha = 0.55f),
        start = Offset(left + 3.dp.toPx(), h * CPU_RULE_Y),
        end = Offset(right - 3.dp.toPx(), h * CPU_RULE_Y),
        strokeWidth = 0.7.dp.toPx(),
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
        style = Stroke(0.8.dp.toPx()),
    )

    val cells = cpuCoreCells(cores)
    val gap = CPU_CELL_GAP.dp.toPx()
    cells.forEach { cell ->
        val core = cell.core
        val cellLeft = dieLeft + dieW * cell.left + gap
        val cellTop = dieTop + dieH * cell.top + gap
        val cellInnerW = dieW * cell.width - gap * 2f
        val cellInnerH = dieH * cell.height - gap * 2f
        if (cellInnerW <= 0f || cellInnerH <= 0f) return@forEach
        val corner = CornerRadius(minOf(3.dp.toPx(), cellInnerW / 4f, cellInnerH / 4f))
        drawRoundRect(
            color = palette.track.copy(alpha = 0.55f),
            topLeft = Offset(cellLeft, cellTop),
            size = Size(cellInnerW, cellInnerH),
            cornerRadius = corner,
        )
        drawRoundRect(
            color = if (core.isOnline) palette.accent.copy(alpha = 0.22f) else palette.edge.copy(alpha = 0.55f),
            topLeft = Offset(cellLeft, cellTop),
            size = Size(cellInnerW, cellInnerH),
            cornerRadius = corner,
            style = Stroke(0.9.dp.toPx()),
        )
        val load = core.load?.takeIf { core.isOnline }?.coerceIn(0f, 1f) ?: return@forEach
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

/**
 * The reference RAM package: a square body with pins on all four sides, a printed "RAM" header and a
 * horizontal meter. The percentage and used/total pair are overlaid by [MemoryCopy] so they stay real
 * text and scale with the user's font setting.
 */
private fun DrawScope.drawMemoryCard(palette: MetricPalette) {
    // The chip is square in the reference, so it is limited by the shorter axis and centred in the tile.
    val side = minOf(size.width * 0.70f, size.height * 0.64f)
    val left = (size.width - side) / 2f
    val top = (size.height - side) / 2f
    val bottom = top + side
    val pinLength = side * 0.075f
    val pinHalf = side * 0.018f
    val pinColour = palette.pin.copy(alpha = 0.85f)
    val pinRadius = CornerRadius(1.dp.toPx())
    val overlap = 1.dp.toPx()

    // Eight pins per edge, drawn first so the package face hides where each one enters the body.
    repeat(8) { index ->
        val fraction = 0.11f + index / 7f * 0.78f
        val x = left + side * fraction
        val y = top + side * fraction
        drawRoundRect(
            color = pinColour,
            topLeft = Offset(x - pinHalf, top - pinLength),
            size = Size(pinHalf * 2f, pinLength + overlap),
            cornerRadius = pinRadius,
        )
        drawRoundRect(
            color = pinColour,
            topLeft = Offset(x - pinHalf, bottom - overlap),
            size = Size(pinHalf * 2f, pinLength + overlap),
            cornerRadius = pinRadius,
        )
        drawRoundRect(
            color = pinColour,
            topLeft = Offset(left - pinLength, y - pinHalf),
            size = Size(pinLength + overlap, pinHalf * 2f),
            cornerRadius = pinRadius,
        )
        drawRoundRect(
            color = pinColour,
            topLeft = Offset(left + side - overlap, y - pinHalf),
            size = Size(pinLength + overlap, pinHalf * 2f),
            cornerRadius = pinRadius,
        )
    }

    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(palette.bodyTop, palette.bodyBottom),
            Offset(left, top),
            Offset(left + side, bottom),
        ),
        topLeft = Offset(left, top),
        size = Size(side, side),
        cornerRadius = CornerRadius(12.dp.toPx()),
    )
    drawRoundRect(
        color = palette.edge,
        topLeft = Offset(left, top),
        size = Size(side, side),
        cornerRadius = CornerRadius(12.dp.toPx()),
        style = Stroke(1.4.dp.toPx()),
    )
    // A shallow inner rim reads as the pressed lid of a chip package without adding a shadow.
    val rim = 3.dp.toPx()
    drawRoundRect(
        color = palette.accent.copy(alpha = 0.24f),
        topLeft = Offset(left + rim, top + rim),
        size = Size(side - rim * 2f, side - rim * 2f),
        cornerRadius = CornerRadius(9.dp.toPx()),
        style = Stroke(0.9.dp.toPx()),
    )
}

@Composable
private fun MemoryCopy(
    readout: Readout,
    palette: MetricPalette,
    width: Dp,
    height: Dp,
) {
    val side = minOf(width * 0.70f, height * 0.64f)
    val left = (width - side) / 2f
    val top = (height - side) / 2f
    val fraction = readout.fraction?.coerceIn(0f, 1f) ?: 0f
    Column(
        modifier = Modifier
            .offset(x = left, y = top)
            .size(side)
            .padding(horizontal = side * 0.10f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(side * 0.13f))
        Text(
            text = "RAM",
            style = StatLabelStyle.copy(fontSize = 15.sp, lineHeight = 17.sp, letterSpacing = 2.sp),
            color = palette.ink,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height(side * 0.09f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(side * 0.17f)
                    .clip(RoundedCornerShape(50))
                    .background(palette.face),
            ) {
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(palette.accent),
                    )
                }
            }
            Spacer(modifier = Modifier.width(side * 0.07f))
            Text(
                text = readout.value,
                style = StatValueStyle.copy(fontSize = 19.sp, lineHeight = 21.sp),
                color = palette.value,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.height(side * 0.035f))
        readout.detail?.let { detail ->
            Text(
                text = detail,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialCaption.copy(fontSize = 8.5.sp, lineHeight = 10.sp),
                color = palette.muted,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

/**
 * The reference refresh card: a rounded outer shell, a recessed face, a layered figure-eight glyph and
 * the rate printed large beneath it. The glyph is built from accent-coloured strokes only; there is no
 * glow or cast shadow, so light, dark, AMOLED and custom-accent themes all keep control of the colour.
 */
private fun DrawScope.drawRefreshCard(palette: MetricPalette) {
    val w = size.width
    val h = size.height
    val left = w * 0.055f
    val top = h * 0.06f
    val right = w * 0.945f
    val bottom = h * 0.94f
    val outerRadius = CornerRadius(20.dp.toPx())
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(palette.bodyTop, palette.bodyBottom),
            Offset(left, top),
            Offset(right, bottom),
        ),
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        cornerRadius = outerRadius,
    )
    drawRoundRect(
        color = palette.edge,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        cornerRadius = outerRadius,
        style = Stroke(1.4.dp.toPx()),
    )

    val inset = 9.dp.toPx()
    val panelLeft = left + inset
    val panelTop = top + inset
    val panelRight = right - inset
    val panelBottom = bottom - inset
    val panelWidth = panelRight - panelLeft
    val panelHeight = panelBottom - panelTop
    val panelRadius = CornerRadius(15.dp.toPx())
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(lerp(palette.face, palette.accent, 0.07f), palette.face),
            Offset(panelLeft, panelTop),
            Offset(panelRight, panelBottom),
        ),
        topLeft = Offset(panelLeft, panelTop),
        size = Size(panelWidth, panelHeight),
        cornerRadius = panelRadius,
    )
    drawRoundRect(
        color = palette.accent.copy(alpha = 0.40f),
        topLeft = Offset(panelLeft, panelTop),
        size = Size(panelWidth, panelHeight),
        cornerRadius = panelRadius,
        style = Stroke(1.1.dp.toPx()),
    )

    // A vertical figure eight drawn as six nested filaments. The outermost is faintest, so the glyph
    // reads as layered strokes rather than a halo.
    val centreX = panelLeft + panelWidth / 2f
    val centreY = panelTop + panelHeight * 0.30f
    val halfWidth = panelWidth * 0.25f
    val halfHeight = panelHeight * 0.17f
    val steps = 96
    repeat(6) { layer ->
        val scale = 1f - layer * 0.09f
        val path = Path()
        repeat(steps + 1) { step ->
            val t = step / steps.toFloat() * 2f * PI.toFloat()
            val x = centreX + halfWidth * scale * sin(2f * t)
            val y = centreY + halfHeight * scale * sin(t)
            if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = palette.accent.copy(alpha = 0.30f + layer * 0.11f),
            style = Stroke(
                width = (1.5f - layer * 0.12f).dp.toPx(),
                cap = StrokeCap.Round,
            ),
        )
    }
}

@Composable
private fun RefreshCopy(
    readout: Readout,
    palette: MetricPalette,
    width: Dp,
    height: Dp,
) {
    val inset = 9.dp
    val panelLeft = width * 0.055f + inset
    val panelTop = height * 0.06f + inset
    val panelWidth = width * 0.89f - inset * 2f
    val panelHeight = height * 0.88f - inset * 2f
    val value = readout.value.trim()
    val unit = if (value.endsWith(" Hz")) "Hz" else ""
    val amount = if (unit.isEmpty()) value else value.removeSuffix(" Hz").trim()
    Column(
        modifier = Modifier
            .offset(x = panelLeft, y = panelTop)
            .width(panelWidth)
            .height(panelHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(panelHeight * 0.52f))
        Row {
            Text(
                text = amount,
                modifier = Modifier.alignByBaseline(),
                style = StatValueStyle.copy(fontSize = 34.sp, lineHeight = 38.sp),
                color = palette.ink,
                maxLines = 1,
            )
            if (unit.isNotEmpty()) {
                Spacer(modifier = Modifier.width(panelWidth * 0.025f))
                Text(
                    text = unit,
                    modifier = Modifier.alignByBaseline(),
                    style = StatValueStyle.copy(fontSize = 14.sp, lineHeight = 16.sp),
                    color = palette.ink,
                    maxLines = 1,
                )
            }
        }
        readout.detail?.let { detail ->
            Spacer(modifier = Modifier.height(panelHeight * 0.02f))
            Text(
                text = detail,
                style = MaterialCaption.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = palette.muted,
                textAlign = TextAlign.Center,
                maxLines = 1,
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
private const val CPU_BODY_LEFT = 0.125f
private const val CPU_BODY_RIGHT = 0.875f
private const val CPU_BODY_TOP = 0.175f
private const val CPU_BODY_BOTTOM = 0.885f
private const val CPU_HEADER_TOP = 0.187f
private const val CPU_HEADER_HEIGHT = 0.170f
private const val CPU_RULE_Y = 0.372f
private const val CPU_DIE_LEFT = 0.17f
private const val CPU_DIE_RIGHT = 0.83f
private const val CPU_DIE_TOP = 0.385f
private const val CPU_DIE_BOTTOM = 0.858f
private const val CPU_CELL_GAP = 1.6f

/** Ceilings within 5% of the fastest count as the same performance tier; cpufreq values are noisy. */
private const val FAST_TIER_TOLERANCE = 0.95f

private val MaterialCaption = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 8.5.sp,
    lineHeight = 10.5.sp,
)

private const val METRIC_CARD_HEIGHT = 190
