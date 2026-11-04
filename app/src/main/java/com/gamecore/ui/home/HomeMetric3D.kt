package com.gamecore.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The five deliberately different 3D instruments used by Home's live tiles.
 *
 * These are drawn by the host instead of being image assets so they remain sharp at every density and
 * can use the live reading as part of the object: CPU lights its four-core die, RAM fills its modules,
 * the battery fills its own case, and the thermostat raises its column. Refresh rate is a display with
 * a circular sweep rather than another generic chip. Shape, not colour, is the primary distinction.
 */
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

/** A compact sculpted object; the neighbouring label and value carry all accessibility semantics. */
@Composable
internal fun HomeMetric3D(
    label: String,
    fraction: Float?,
    modifier: Modifier = Modifier,
    size: Dp = 54.dp,
) {
    val shape = HomeMetricShape.fromLabel(label)
    val surface = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.size(size)) {
        val level = fraction?.coerceIn(0f, 1f) ?: when (shape) {
            HomeMetricShape.TEMPERATURE -> 0.64f
            HomeMetricShape.REFRESH -> 0.78f
            else -> 0.52f
        }
        when (shape) {
            HomeMetricShape.CPU -> drawCpu3D(level)
            HomeMetricShape.MEMORY -> drawMemory3D(level)
            HomeMetricShape.BATTERY -> drawBattery3D(level)
            HomeMetricShape.TEMPERATURE -> drawThermostat3D(level)
            HomeMetricShape.REFRESH -> drawRefresh3D(level)
            HomeMetricShape.UNKNOWN -> drawUnknown3D(primary, surface, outline)
        }
    }
}

/** A processor package with pins, a raised die and four separately lit cores. */
private fun DrawScope.drawCpu3D(level: Float) {
    val w = size.width
    val h = size.height
    val amber = Color(0xFFFFB84D)
    val body = Color(0xFF27313A)
    val x = w * 0.18f
    val y = h * 0.18f
    val side = w * 0.62f
    val pin = w * 0.08f
    val shadow = Offset(w * 0.045f, h * 0.075f)

    drawRoundRect(
        color = Color.Black.copy(alpha = 0.40f),
        topLeft = Offset(x, y) + shadow,
        size = Size(side, side),
        cornerRadius = CornerRadius(w * 0.09f),
    )
    repeat(4) { index ->
        val step = side / 5f
        val p = y + step * (index + 1)
        val q = x + step * (index + 1)
        val lit = index < (level * 4f).toInt().coerceIn(0, 4)
        val pinColour = if (lit) amber else Color(0xFF7D8790)
        drawLine(pinColour, Offset(x - pin, p), Offset(x, p), w * 0.035f, StrokeCap.Round)
        drawLine(pinColour, Offset(x + side, p), Offset(x + side + pin, p), w * 0.035f, StrokeCap.Round)
        drawLine(pinColour, Offset(q, y - pin), Offset(q, y), w * 0.035f, StrokeCap.Round)
        drawLine(pinColour, Offset(q, y + side), Offset(q, y + side + pin), w * 0.035f, StrokeCap.Round)
    }

    // Dark right and bottom faces provide the extrusion before the polished face is laid over them.
    val depth = w * 0.055f
    drawPath(
        path = Path().apply {
            moveTo(x + side, y + depth)
            lineTo(x + side + depth, y + depth * 1.7f)
            lineTo(x + side + depth, y + side - depth * 0.2f)
            lineTo(x + side, y + side)
            close()
        },
        color = Color(0xFF080B0E),
    )
    drawPath(
        path = Path().apply {
            moveTo(x + depth, y + side)
            lineTo(x + side, y + side)
            lineTo(x + side + depth, y + side - depth * 0.2f)
            lineTo(x + depth * 1.7f, y + side + depth)
            close()
        },
        color = Color(0xFF11171C),
    )
    drawRoundRect(
        brush = metalBrush(body, Offset(x, y), Offset(x + side, y + side)),
        topLeft = Offset(x, y),
        size = Size(side, side),
        cornerRadius = CornerRadius(w * 0.08f),
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.20f),
        topLeft = Offset(x, y),
        size = Size(side, side),
        cornerRadius = CornerRadius(w * 0.08f),
        style = Stroke(w * 0.018f),
    )

    val dieSide = side * 0.57f
    val dieX = x + (side - dieSide) / 2f
    val dieY = y + (side - dieSide) / 2f
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(lerp(amber, Color.White, 0.36f), amber, Color(0xFF8A4700)),
            Offset(dieX, dieY),
            Offset(dieX + dieSide, dieY + dieSide),
        ),
        topLeft = Offset(dieX, dieY),
        size = Size(dieSide, dieSide),
        cornerRadius = CornerRadius(w * 0.04f),
    )
    val gap = dieSide * 0.08f
    val coreSide = (dieSide - gap * 3f) / 2f
    repeat(4) { index ->
        val cx = dieX + gap + (index % 2) * (coreSide + gap)
        val cy = dieY + gap + (index / 2) * (coreSide + gap)
        val active = level * 4f > index
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = if (active) {
                    listOf(Color(0xFFFFF0B0), Color(0xFFFF9E2C))
                } else {
                    listOf(Color(0xFF5D4B35), Color(0xFF2A241D))
                },
                start = Offset(cx, cy),
                end = Offset(cx + coreSide, cy + coreSide),
            ),
            topLeft = Offset(cx, cy),
            size = Size(coreSide, coreSide),
            cornerRadius = CornerRadius(w * 0.018f),
        )
    }
}

/** Two raised memory modules with separate chips and gold contacts. */
private fun DrawScope.drawMemory3D(level: Float) {
    val w = size.width
    val h = size.height
    val green = Color(0xFF42E39B)
    val litChips = (level * 8f).toInt().coerceIn(0, 8)

    rotate(degrees = -7f, pivot = Offset(w / 2f, h / 2f)) {
        repeat(2) { row ->
            val x = w * (0.10f + row * 0.05f)
            val y = h * (0.22f + row * 0.34f)
            val width = w * 0.76f
            val height = h * 0.25f
            val depth = h * 0.055f
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.36f),
                topLeft = Offset(x + depth, y + depth * 1.5f),
                size = Size(width, height),
                cornerRadius = CornerRadius(h * 0.05f),
            )
            drawRoundRect(
                brush = Brush.linearGradient(
                    listOf(Color(0xFF2C7259), Color(0xFF103B2C), Color(0xFF071B14)),
                    Offset(x, y),
                    Offset(x, y + height),
                ),
                topLeft = Offset(x, y),
                size = Size(width, height),
                cornerRadius = CornerRadius(h * 0.045f),
            )
            drawLine(
                color = Color.White.copy(alpha = 0.20f),
                start = Offset(x + width * 0.05f, y + h * 0.025f),
                end = Offset(x + width * 0.95f, y + h * 0.025f),
                strokeWidth = h * 0.018f,
                cap = StrokeCap.Round,
            )
            repeat(4) { chip ->
                val chipW = width * 0.16f
                val chipX = x + width * 0.07f + chip * width * 0.225f
                val active = row * 4 + chip < litChips
                drawRoundRect(
                    brush = Brush.linearGradient(
                        if (active) listOf(lerp(green, Color.White, 0.25f), Color(0xFF15845A))
                        else listOf(Color(0xFF25302D), Color(0xFF090D0C)),
                        Offset(chipX, y + height * 0.18f),
                        Offset(chipX + chipW, y + height * 0.78f),
                    ),
                    topLeft = Offset(chipX, y + height * 0.18f),
                    size = Size(chipW, height * 0.52f),
                    cornerRadius = CornerRadius(h * 0.018f),
                )
                drawRect(
                    color = Color(0xFFFFCB5C),
                    topLeft = Offset(chipX + chipW * 0.16f, y + height * 0.83f),
                    size = Size(chipW * 0.68f, height * 0.12f),
                )
            }
        }
    }
}

/** A literal battery pack: raised shell, terminal and a live fill inside the case. */
private fun DrawScope.drawBattery3D(level: Float) {
    val w = size.width
    val h = size.height
    val x = w * 0.08f
    val y = h * 0.29f
    val bodyW = w * 0.72f
    val bodyH = h * 0.43f
    val depth = h * 0.07f
    val green = when {
        level <= 0.20f -> Color(0xFFFF5A5F)
        level <= 0.40f -> Color(0xFFFFB547)
        else -> Color(0xFF42E384)
    }

    drawRoundRect(
        color = Color.Black.copy(alpha = 0.40f),
        topLeft = Offset(x + depth, y + depth * 1.4f),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(h * 0.09f),
    )
    drawRoundRect(
        brush = metalBrush(Color(0xFF222A31), Offset(x, y), Offset(x, y + bodyH)),
        topLeft = Offset(x, y),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(h * 0.09f),
    )
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(Color(0xFFADB8C0), Color(0xFF3F4951), Color(0xFF171C21)),
            Offset(x + bodyW, y),
            Offset(x + bodyW + w * 0.14f, y + bodyH),
        ),
        topLeft = Offset(x + bodyW, y + bodyH * 0.29f),
        size = Size(w * 0.13f, bodyH * 0.42f),
        cornerRadius = CornerRadius(h * 0.04f),
    )
    val inset = h * 0.075f
    val innerW = bodyW - inset * 2f
    val fillW = (innerW * level).coerceAtLeast(if (level > 0f) h * 0.045f else 0f)
    drawRoundRect(
        color = Color(0xFF060809),
        topLeft = Offset(x + inset, y + inset),
        size = Size(innerW, bodyH - inset * 2f),
        cornerRadius = CornerRadius(h * 0.045f),
    )
    if (fillW > 0f) {
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(lerp(green, Color.White, 0.34f), green, lerp(green, Color.Black, 0.42f)),
                startY = y + inset,
                endY = y + bodyH - inset,
            ),
            topLeft = Offset(x + inset, y + inset),
            size = Size(fillW, bodyH - inset * 2f),
            cornerRadius = CornerRadius(h * 0.04f),
        )
    }
    drawRoundRect(
        color = Color.White.copy(alpha = 0.25f),
        topLeft = Offset(x, y),
        size = Size(bodyW, bodyH),
        cornerRadius = CornerRadius(h * 0.09f),
        style = Stroke(h * 0.018f),
    )
}

/** A glass thermostat with a raised metal collar, scale marks and a live mercury column. */
private fun DrawScope.drawThermostat3D(level: Float) {
    val w = size.width
    val h = size.height
    val centerX = w * 0.47f
    val tubeTop = h * 0.08f
    val tubeBottom = h * 0.67f
    val tubeW = w * 0.25f
    val bulbRadius = w * 0.20f
    val red = Color(0xFFFF665F)
    val shadow = Offset(w * 0.055f, h * 0.06f)

    drawRoundRect(
        color = Color.Black.copy(alpha = 0.42f),
        topLeft = Offset(centerX - tubeW / 2f, tubeTop) + shadow,
        size = Size(tubeW, tubeBottom - tubeTop),
        cornerRadius = CornerRadius(tubeW / 2f),
    )
    drawCircle(
        color = Color.Black.copy(alpha = 0.42f),
        radius = bulbRadius,
        center = Offset(centerX, h * 0.72f) + shadow,
    )
    drawRoundRect(
        brush = Brush.horizontalGradient(
            listOf(Color(0xFF56616A), Color(0xFFE2E8EA), Color(0xFF45515A), Color(0xFF151B20)),
            startX = centerX - tubeW / 2f,
            endX = centerX + tubeW / 2f,
        ),
        topLeft = Offset(centerX - tubeW / 2f, tubeTop),
        size = Size(tubeW, tubeBottom - tubeTop),
        cornerRadius = CornerRadius(tubeW / 2f),
    )
    val boreW = tubeW * 0.34f
    val boreTop = tubeTop + h * 0.055f
    val boreBottom = h * 0.70f
    drawRoundRect(
        color = Color(0xFF080B0D),
        topLeft = Offset(centerX - boreW / 2f, boreTop),
        size = Size(boreW, boreBottom - boreTop),
        cornerRadius = CornerRadius(boreW / 2f),
    )
    val mercuryTop = boreBottom - (boreBottom - boreTop) * level.coerceIn(0.08f, 1f)
    drawRoundRect(
        brush = Brush.horizontalGradient(
            listOf(Color(0xFFFFC0A0), red, Color(0xFF9B161F)),
            startX = centerX - boreW / 2f,
            endX = centerX + boreW / 2f,
        ),
        topLeft = Offset(centerX - boreW / 2f, mercuryTop),
        size = Size(boreW, boreBottom - mercuryTop),
        cornerRadius = CornerRadius(boreW / 2f),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFFFD0B5), red, Color(0xFF76121A)),
            center = Offset(centerX - bulbRadius * 0.32f, h * 0.72f - bulbRadius * 0.34f),
            radius = bulbRadius * 1.5f,
        ),
        radius = bulbRadius,
        center = Offset(centerX, h * 0.72f),
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.30f),
        radius = bulbRadius,
        center = Offset(centerX, h * 0.72f),
        style = Stroke(h * 0.018f),
    )
    repeat(4) { index ->
        val tickY = tubeTop + h * (0.09f + index * 0.115f)
        val tickLength = if (index % 2 == 0) w * 0.15f else w * 0.10f
        drawLine(
            Color(0xFFFFB08B),
            Offset(centerX + tubeW * 0.68f, tickY),
            Offset(centerX + tubeW * 0.68f + tickLength, tickY),
            h * 0.018f,
            StrokeCap.Round,
        )
    }
}

/** A perspective display panel with a raised stand and circular high-refresh sweep. */
private fun DrawScope.drawRefresh3D(level: Float) {
    val w = size.width
    val h = size.height
    val cyan = Color(0xFF62D8FF)
    val violet = Color(0xFF8D72FF)
    val face = Path().apply {
        moveTo(w * 0.10f, h * 0.17f)
        lineTo(w * 0.84f, h * 0.10f)
        lineTo(w * 0.90f, h * 0.67f)
        lineTo(w * 0.16f, h * 0.73f)
        close()
    }
    val shadow = Path().apply {
        moveTo(w * 0.15f, h * 0.24f)
        lineTo(w * 0.89f, h * 0.17f)
        lineTo(w * 0.95f, h * 0.74f)
        lineTo(w * 0.21f, h * 0.80f)
        close()
    }
    drawPath(shadow, Color.Black.copy(alpha = 0.42f))
    drawPath(
        face,
        Brush.linearGradient(
            listOf(Color(0xFF4F5B68), Color(0xFF151B23), Color(0xFF070A0E)),
            Offset(w * 0.1f, h * 0.1f),
            Offset(w * 0.9f, h * 0.75f),
        ),
    )
    val screen = Path().apply {
        moveTo(w * 0.17f, h * 0.23f)
        lineTo(w * 0.78f, h * 0.18f)
        lineTo(w * 0.82f, h * 0.58f)
        lineTo(w * 0.21f, h * 0.63f)
        close()
    }
    drawPath(
        screen,
        Brush.linearGradient(
            listOf(Color(0xFF132A46), Color(0xFF101426), Color(0xFF221A45)),
            Offset(w * 0.18f, h * 0.2f),
            Offset(w * 0.82f, h * 0.62f),
        ),
    )
    drawLine(
        Color.White.copy(alpha = 0.28f),
        Offset(w * 0.18f, h * 0.245f),
        Offset(w * 0.77f, h * 0.195f),
        h * 0.018f,
        StrokeCap.Round,
    )
    val center = Offset(w * 0.50f, h * 0.42f)
    val radius = w * 0.17f
    val sweep = 265f * level.coerceIn(0.22f, 1f)
    drawArc(
        brush = Brush.sweepGradient(listOf(cyan, violet, cyan), center),
        startAngle = 135f,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(w * 0.055f, cap = StrokeCap.Round),
    )
    val arrowAngle = Math.toRadians((135f + sweep).toDouble())
    val tip = Offset(
        center.x + cos(arrowAngle).toFloat() * radius,
        center.y + sin(arrowAngle).toFloat() * radius,
    )
    drawCircle(Color.White.copy(alpha = 0.82f), w * 0.035f, tip)
    drawPath(
        Path().apply {
            moveTo(w * 0.46f, h * 0.70f)
            lineTo(w * 0.59f, h * 0.69f)
            lineTo(w * 0.65f, h * 0.87f)
            lineTo(w * 0.39f, h * 0.89f)
            close()
        },
        Brush.verticalGradient(listOf(Color(0xFF78838C), Color(0xFF151A1E)), h * 0.68f, h * 0.90f),
    )
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color(0xFF69737C), Color(0xFF111519)), h * 0.84f, h * 0.96f),
        topLeft = Offset(w * 0.29f, h * 0.86f),
        size = Size(w * 0.47f, h * 0.10f),
        cornerRadius = CornerRadius(h * 0.04f),
    )
}

/** A faceted fallback, used only if a future metric reaches this renderer before it gets its own art. */
private fun DrawScope.drawUnknown3D(primary: Color, surface: Color, outline: Color) {
    val w = size.width
    val h = size.height
    val top = Offset(w * 0.50f, h * 0.10f)
    val left = Offset(w * 0.15f, h * 0.32f)
    val right = Offset(w * 0.85f, h * 0.32f)
    val bottom = Offset(w * 0.50f, h * 0.88f)
    drawPath(
        Path().apply {
            moveTo(left.x + w * 0.05f, left.y + h * 0.06f)
            lineTo(top.x + w * 0.05f, top.y + h * 0.06f)
            lineTo(right.x + w * 0.05f, right.y + h * 0.06f)
            lineTo(bottom.x + w * 0.05f, bottom.y + h * 0.06f)
            close()
        },
        Color.Black.copy(alpha = 0.35f),
    )
    drawPath(Path().apply { moveTo(top.x, top.y); lineTo(left.x, left.y); lineTo(bottom.x, bottom.y); lineTo(w * 0.5f, h * 0.44f); close() }, lerp(primary, surface, 0.25f))
    drawPath(Path().apply { moveTo(top.x, top.y); lineTo(right.x, right.y); lineTo(bottom.x, bottom.y); lineTo(w * 0.5f, h * 0.44f); close() }, lerp(primary, Color.Black, 0.32f))
    drawPath(Path().apply { moveTo(top.x, top.y); lineTo(left.x, left.y); lineTo(w * 0.5f, h * 0.44f); lineTo(right.x, right.y); close() }, lerp(primary, Color.White, 0.30f))
    drawLine(outline, left, bottom, h * 0.018f)
}

/** Three-stop highlight and falloff shared by the raised metal shells. */
private fun metalBrush(base: Color, start: Offset, end: Offset): Brush = Brush.linearGradient(
    colors = listOf(lerp(base, Color.White, 0.34f), base, lerp(base, Color.Black, 0.55f)),
    start = start,
    end = end,
)
