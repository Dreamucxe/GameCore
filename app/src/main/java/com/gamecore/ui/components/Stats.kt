package com.gamecore.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamecore.core.common.Observed
import com.gamecore.core.common.isAwaitingSample
import com.gamecore.core.common.shortUnavailabilityText
import com.gamecore.core.common.unavailabilityText
import com.gamecore.ui.theme.StatLabelStyle
import com.gamecore.ui.theme.StatValueCompactStyle
import com.gamecore.ui.theme.StatValueStyle

/**
 * How a reading is drawn, and how an *absent* reading is drawn.
 *
 * This file is where the honesty requirement stops being a type and becomes pixels. [Observed] makes
 * "unavailable" a case the compiler forces every caller to handle; [ObservedStat] and [ObservedRow]
 * decide what that case looks like, once, so no screen can quietly render an absent temperature as 0 °C
 * or an unmeasurable frame rate as a plausible-looking number.
 *
 * The absent case is drawn in the muted colour with an em dash and the reason underneath. Not red: a
 * device that does not expose a CPU temperature is not in a failure state, and colouring it as one
 * would train the user to ignore the colour that means something is actually wrong.
 */

/**
 * The big figure on a dashboard card.
 *
 * Monospaced via [com.gamecore.ui.theme.StatValueStyle], for the same reason the overlay pill is: the
 * width of "9%" and "10%" differ in a proportional font, and six cards that jiggle every two seconds
 * are unreadable even when every figure in them is right.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: Tone = Tone.Neutral,
    detail: String? = null,
    fraction: Float? = null,
    onClick: (() -> Unit)? = null,
) {
    val body: @Composable () -> Unit = {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = label.uppercase(),
                    style = StatLabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = StatValueStyle,
                color = tone.colour(),
                maxLines = 1,
            )
            if (fraction != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Meter(fraction = fraction, tone = tone)
            }
            if (detail != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    val sized = modifier.defaultMinSize(minHeight = 96.dp)
    if (onClick == null) {
        PlainCard(modifier = sized) { body() }
    } else {
        ClickableCard(modifier = sized, onClick = onClick) { body() }
    }
}

/**
 * A stat tile fed by an [Observed], which is the honest version of [StatTile].
 *
 * [format] is only ever called with a real value, so a screen cannot accidentally format a placeholder.
 * When the reading is absent the tile shows an em dash and the short reason — "No permission", "Not on
 * this device" — and the long reason goes underneath where there is room for it.
 *
 * A rate still waiting for its second sample is the one absence drawn differently: ellipsis, no reason.
 * It resolves itself within one interval, and explaining it would be a paragraph the user reads once and
 * then watches disappear.
 */
@Composable
fun <T> ObservedTile(
    label: String,
    observed: Observed<T>,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: Tone = Tone.Neutral,
    fractionOf: ((T) -> Float)? = null,
    detailOf: ((T) -> String)? = null,
    onClick: (() -> Unit)? = null,
    format: (T) -> String,
) {
    when {
        observed is Observed.Value -> StatTile(
            label = label,
            value = format(observed.value),
            modifier = modifier,
            icon = icon,
            tone = tone,
            detail = detailOf?.invoke(observed.value),
            fraction = fractionOf?.invoke(observed.value),
            onClick = onClick,
        )
        observed.isAwaitingSample -> StatTile(
            label = label,
            value = PENDING,
            modifier = modifier,
            icon = icon,
            tone = Tone.Muted,
            onClick = onClick,
        )
        else -> StatTile(
            label = label,
            value = ABSENT,
            modifier = modifier,
            icon = icon,
            tone = Tone.Muted,
            detail = observed.unavailabilityText(),
            onClick = onClick,
        )
    }
}

/**
 * The same idea in a list row: label on the left, figure or reason on the right.
 *
 * Used wherever a screen has more readings than a grid of tiles can carry without becoming a wall —
 * the Performance screen's detail sections, the Shizuku screen's capability list.
 */
@Composable
fun <T> ObservedRow(
    label: String,
    observed: Observed<T>,
    tone: Tone = Tone.Neutral,
    modifier: Modifier = Modifier,
    format: (T) -> String,
) {
    when {
        observed is Observed.Value -> KeyValueRow(label, format(observed.value), tone, modifier)
        observed.isAwaitingSample -> KeyValueRow(label, PENDING, Tone.Muted, modifier)
        else -> KeyValueRow(
            label = label,
            value = observed.shortUnavailabilityText() ?: ABSENT,
            tone = Tone.Muted,
            modifier = modifier,
        )
    }
}

/**
 * The reason a reading is missing, as a sentence under the thing that is missing.
 *
 * Draws nothing at all for a present reading, so a caller can place this unconditionally beneath a
 * section and it disappears on devices where the section works.
 */
@Composable
fun ObservedNote(observed: Observed<*>, modifier: Modifier = Modifier) {
    if (observed.isAwaitingSample) return
    val text = observed.unavailabilityText() ?: return
    NoteBanner(text = text, tone = Tone.Muted, icon = Icons.Filled.Info, modifier = modifier)
}

/**
 * A horizontal bar for a fraction of something.
 *
 * `LinearProgressIndicator` with a determinate value, so it inherits the platform's track/indicator
 * treatment rather than being a hand-rolled `Box`. Clamped, because a fraction assembled from a reading
 * and a total can exceed 1 the moment a vendor reports an "available" larger than a "total".
 */
@Composable
fun Meter(fraction: Float, tone: Tone = Tone.Accent, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier
            .fillMaxWidth()
            .height(5.dp),
        color = tone.colour(),
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/**
 * A figure in a row rather than on a card — the session report's summary grid, a list item's headline.
 */
@Composable
fun CompactStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = StatLabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, style = StatValueCompactStyle, color = tone.colour(), maxLines = 1)
    }
}

/** One cell of a [StatStrip]. A named triple, so call sites read as a table rather than as tuples. */
data class StatEntry(val label: String, val value: String, val tone: Tone = Tone.Neutral)

/**
 * A row of [CompactStat]s that divides the width evenly.
 *
 * Even weights rather than wrapped content: a report where "2 h 14 m" and "38 %" are laid out by their
 * own widths shifts its columns between one session and the next, and the user reads these as a table.
 */
@Composable
fun StatStrip(entries: List<StatEntry>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        entries.forEach { entry ->
            CompactStat(
                label = entry.label,
                value = entry.value,
                tone = entry.tone,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Tiles side by side, sharing the width.
 *
 * Children take `Modifier.weight(1f)`. Their heights are left to them and evened out by [StatTile]'s own
 * minimum rather than by an intrinsic measurement of the row, which would make the whole dashboard's
 * layout depend on the longest unavailability sentence any one device happens to produce.
 */
@Composable
fun TileRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** An em dash: the app's one way of writing "there is no figure here". */
const val ABSENT = "—"

/** A rate that needs one more sample. Resolves itself, so it is not an absence with a reason. */
const val PENDING = "…"
