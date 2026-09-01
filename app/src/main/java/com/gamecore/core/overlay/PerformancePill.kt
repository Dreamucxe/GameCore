package com.gamecore.core.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamecore.core.model.OverlayConfig
import com.gamecore.domain.monitoring.StatReading

/**
 * The palette the overlay draws with.
 *
 * Deliberately not `MaterialTheme`. Two reasons, and both are about where this content runs:
 *
 *  - There is no theme in a service composition unless one is installed, and installing the app's
 *    theme would make the overlay follow the user's accent choice — which sounds nice until the accent
 *    is amber and the pill is drawn over a desert level in a shooter.
 *  - An overlay is drawn over content GameCore has never seen and cannot sample. The only thing that
 *    stays legible over both a white loading screen and a night map is light text on its own dark
 *    plate, so the overlay carries that palette rather than inheriting one.
 *
 * The accent is still the user's, but it is only used for the button and the panel's highlights, where
 * it sits on GameCore's own surface rather than over the game.
 */
object OverlayPalette {
    /** The plate every piece of overlay text sits on. */
    val Plate = Color(0xE6101318)

    /** A slightly lighter plate for the panel, which is bigger and reads as a surface. */
    val PanelPlate = Color(0xF2151A21)

    val Text = Color(0xFFF2F5F8)

    /** Labels and units: present, but not competing with the figure. */
    val Muted = Color(0xFF9AA6B2)

    /** A stat that has no reading. Dim enough to read as absent rather than as zero. */
    val Absent = Color(0xFF6B7683)

    val Warning = Color(0xFFFFB44D)
    val Danger = Color(0xFFFF6B6B)
    val Good = Color(0xFF6BE39A)

    val Divider = Color(0x1FFFFFFF)
}

/**
 * The stats pill of §8.
 *
 * Every figure comes from a [StatReading], which is either a value or a reason there is none — so the
 * one thing this composable cannot do is invent a number. A stat with no reading renders as `--` in
 * [OverlayPalette.Absent] and keeps its slot, because a pill whose contents reflow every time latency
 * drops out is harder to read than one with a gap in it.
 *
 * Digits are monospaced. With a proportional font the pill resizes every time CPU crosses from 9% to
 * 10%, and a control that twitches in the corner of a game is worse than one that is slightly wider
 * than it needs to be.
 *
 * [OverlayConfig.opacityPercent] is applied to the whole thing, plate included, rather than to the
 * text alone: fading the text against an opaque plate makes it less legible at every setting, while
 * fading both keeps the contrast ratio the same and just lets more of the game through.
 */
@Composable
fun PerformancePill(
    readings: List<StatReading>,
    config: OverlayConfig,
    modifier: Modifier = Modifier,
) {
    val content: @Composable () -> Unit = {
        readings.forEach { reading ->
            PillStat(reading = reading, config = config)
        }
    }
    val plate = modifier
        .alpha(config.opacityPercent / 100f)
        .background(OverlayPalette.Plate, RoundedCornerShape(config.cornerRadiusDp.dp))
        .padding(horizontal = 8.dp, vertical = 5.dp)

    if (config.isVertical) {
        Column(
            modifier = plate,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.Start,
            content = { content() },
        )
    } else {
        Row(
            modifier = plate,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = { content() },
        )
    }
}

/**
 * One stat.
 *
 * The label is drawn as a separate, smaller, dimmer run rather than as part of the value string so
 * that switching [OverlayConfig.showLabels] off does not change the value's baseline — the pill's
 * height stays put and only its width changes.
 */
@Composable
private fun PillStat(reading: StatReading, config: OverlayConfig) {
    val size = config.textSizeSp.sp
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (config.showLabels && reading.stat.shortLabel.isNotEmpty()) {
            BasicText(
                text = reading.stat.shortLabel,
                style = TextStyle(
                    color = OverlayPalette.Muted,
                    fontSize = size * 0.82f,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
        BasicText(
            text = reading.display(),
            style = TextStyle(
                color = if (reading.isAvailable) OverlayPalette.Text else OverlayPalette.Absent,
                fontSize = size,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
            ),
        )
    }
}
