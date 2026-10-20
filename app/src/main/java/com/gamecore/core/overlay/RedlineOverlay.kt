package com.gamecore.core.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamecore.domain.monitoring.StatReading

/**
 * The Redline in-game look, folded onto the overlay panel when the user turns the skin on.
 *
 * This is the RedMagic / "Pulse" layout from the reference — the jagged cut-corner plate, the branded
 * header, and the CPU/GPU gauge rings — **recoloured to Redline's red**, not the reference's blue, by
 * the user's choice. It is pure drawing over the same state the plain panel receives: no sampler, no
 * handler, no new capability. [FullPanel] decides which look to draw from one boolean, so a disabled
 * skin is byte-for-byte the panel it has always been.
 *
 * Nothing here invents a figure. A gauge reads a [StatReading] that is either a value or a reason there
 * is none; an absent reading draws a dim ring and the em-dash the rest of the overlay uses, never a
 * zero that would read as "0% load".
 */
object RedlineOverlay {
    /** The signature red and the cool-dark plates the gauges and cards sit on. */
    val Red = Color(0xFFE8142D)
    val Plate = Color(0xF21A0E12)
    val PanelPlate = Color(0xF2140A0E)
    val GaugeTrack = Color(0x33E8142D)
    val Text = Color(0xFFF2ECEE)
    val Muted = Color(0xFFB08A92)
    val Absent = Color(0xFF6B4A52)

    /** The jagged cut-corner plate shape — the overlay echo of the app skin's [RedlineShapes]. */
    val CardShape = CutCornerShape(topStart = 12.dp, topEnd = 3.dp, bottomStart = 3.dp, bottomEnd = 12.dp)
    val ChipShape = CutCornerShape(6.dp)
}

/**
 * The branded header strip: "GAMECORE PULSE" with the game name beneath, and a close X in a cut-corner
 * chip — the reference's title bar, in GameCore's own words and colours. [onClose] is the same handler
 * the plain header uses; this only restyles.
 */
@Composable
internal fun RedlinePanelHeader(gameLabel: String, clock: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = "GAMECORE PULSE",
                style = TextStyle(
                    color = RedlineOverlay.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.5.sp,
                ),
            )
            val sub = listOf(gameLabel, clock).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotEmpty()) {
                BasicText(text = sub, style = TextStyle(color = RedlineOverlay.Muted, fontSize = 11.sp))
            }
        }
        Box(
            modifier = Modifier
                .size(30.dp)
                .semantics { contentDescription = "Close panel" }
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(text = "✕", style = TextStyle(color = RedlineOverlay.Red, fontSize = 15.sp))
        }
    }
}

/**
 * The CPU / GPU gauge pair of the reference, drawn as two rings with the figure centred — recoloured red.
 *
 * Fed the same [readings] the plain stats strip gets; it picks the two whose label matches CPU and GPU
 * and draws them as gauges, leaving the rest to [PanelStats]-style chips drawn by the caller. A reading
 * with no value draws an empty track and the em dash, so a device that does not report GPU load shows a
 * dim ring, never a full or a zero one.
 */
@Composable
internal fun RedlineGaugeRow(cpu: StatReading?, gpu: StatReading?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RedlineGauge(reading = cpu, fallbackLabel = "CPU", modifier = Modifier.weight(1f))
        RedlineGauge(reading = gpu, fallbackLabel = "GPU", modifier = Modifier.weight(1f))
    }
}

/**
 * One gauge: a ring filled to the reading's percentage, the figure in the centre, the label below.
 *
 * The percentage is parsed only to size the arc; the text drawn is always [StatReading.display], so a
 * reading the parser cannot read as a number still shows its real string and simply draws an empty
 * track. The arc is never drawn from a fabricated value — no value means no fill.
 */
@Composable
private fun RedlineGauge(reading: StatReading?, fallbackLabel: String, modifier: Modifier = Modifier) {
    val available = reading?.isAvailable == true
    val fraction = reading?.value?.let { parsePercent(it) }
    val figure = reading?.display(withUnit = false) ?: StatReading.PLACEHOLDER
    val label = reading?.stat?.shortLabel ?: fallbackLabel
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(modifier = Modifier.size(78.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(78.dp)) {
                val stroke = 7.dp.toPx()
                val inset = stroke / 2f
                val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                // The track: a full ring, always, so an absent gauge still reads as a gauge.
                drawArc(
                    color = RedlineOverlay.GaugeTrack,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                // The fill: only when there is a real percentage to show.
                if (available && fraction != null) {
                    drawArc(
                        color = RedlineOverlay.Red,
                        startAngle = 135f,
                        sweepAngle = 270f * fraction.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            BasicText(
                text = figure,
                style = TextStyle(
                    color = if (available) RedlineOverlay.Text else RedlineOverlay.Absent,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
        BasicText(
            text = label,
            style = TextStyle(color = RedlineOverlay.Muted, fontSize = 10.sp, letterSpacing = 1.sp),
        )
    }
}

/**
 * Reads a percentage string ("47", "47%", "1.82") as a 0..1 fraction for the arc, or null when it is not
 * a plain number. Null is honest here: an unparseable reading draws no fill rather than a guessed one.
 */
private fun parsePercent(value: String): Float? {
    val n = value.trim().removeSuffix("%").toFloatOrNull() ?: return null
    return (n / 100f)
}
