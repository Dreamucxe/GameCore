package com.gamecore.core.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamecore.core.model.OverlayConfig
import com.gamecore.core.model.PillDisplayMode
import com.gamecore.domain.monitoring.PillFormat
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
 * The stats pill of §3.
 *
 * Every figure comes from a [StatReading], which is either a value or a reason there is none — so the
 * one thing this composable cannot do is invent a number. All formatting — the unit spacing, the compact
 * line, and the word an absent reading shows — lives in [PillFormat], not here, so this composable only
 * chooses a shape and draws the strings that object hands back.
 *
 * Two shapes, from [OverlayConfig.displayMode]:
 *  - [PillDisplayMode.COMPACT]: one glanceable line, "62°C · 74% · 120 Hz", with the same thermal dot the
 *    floating button carries. Absent stats are dropped — a gap in a single line makes the eye stop on the
 *    one thing missing — and if nothing is readable the line falls back to the word rather than an empty
 *    plate.
 *  - [PillDisplayMode.DETAILED]: the labelled card the pill has always drawn, a row or a column per
 *    [OverlayConfig.isVertical]. An absent stat keeps its slot and shows "Unavailable" ([PillFormat]).
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
    thermalDot: ThermalDot = ThermalDot.NONE,
) {
    val plate = modifier
        .alpha(config.opacityPercent / 100f)
        .background(OverlayPalette.Plate, RoundedCornerShape(config.cornerRadiusDp.dp))
        .padding(horizontal = 8.dp, vertical = 5.dp)

    when (config.displayMode) {
        PillDisplayMode.COMPACT -> CompactPill(readings = readings, config = config, thermalDot = thermalDot, modifier = plate)
        PillDisplayMode.DETAILED -> DetailedPill(readings = readings, config = config, modifier = plate)
    }
}

/** The labelled card (spec §3): one [PillStat] per reading, a row or a column per [OverlayConfig.isVertical]. */
@Composable
private fun DetailedPill(
    readings: List<StatReading>,
    config: OverlayConfig,
    modifier: Modifier,
) {
    val content: @Composable () -> Unit = {
        readings.forEach { reading ->
            PillStat(reading = reading, config = config)
        }
    }
    if (config.isVertical) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.Start,
            content = { content() },
        )
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = { content() },
        )
    }
}

@Composable
private fun CompactPill(
    readings: List<StatReading>,
    config: OverlayConfig,
    thermalDot: ThermalDot,
    modifier: Modifier,
) {
    val size = config.textSizeSp.sp
    // Falls back to the word rather than an empty plate when every chosen stat is unreadable on this phone.
    val compact = PillFormat.compactLine(readings)
    val line = compact ?: PillFormat.UNAVAILABLE
    val lineAvailable = compact != null
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thermalDot != ThermalDot.NONE) {
            val dotColour = if (thermalDot == ThermalDot.CRITICAL) OverlayPalette.Danger else OverlayPalette.Warning
            Box(
                modifier = Modifier
                    .size(config.textSizeSp.dp * 0.6f)
                    .background(dotColour, CircleShape)
                    // Not colour alone — the line carries the numbers; the dot names itself for a reader.
                    .semantics { contentDescription = thermalDot.word },
            )
        }
        BasicText(
            text = line,
            style = TextStyle(
                color = if (lineAvailable) OverlayPalette.Text else OverlayPalette.Absent,
                fontSize = size,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            ),
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
            text = PillFormat.cellValue(reading),
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
