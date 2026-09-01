package com.gamecore.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.gamecore.core.common.Observed
import com.gamecore.core.common.isAwaitingSample
import com.gamecore.core.common.unavailabilityText

/**
 * A reading after the ViewModel has finished with it: a label, a figure, and nothing else.
 *
 * This type is why the screens in this app are short. §24A.2 asks that what reaches the UI contain only
 * the fields the UI consumes, and the dashboard consumes a *string* — the difference between 41 °C and
 * "41 °C" is a `Formatters` call and a locale, both of which belong on the ViewModel's side of the line.
 * Passing [com.gamecore.core.model.PerformanceSnapshot] to a composable instead would hand every screen
 * the whole device: per-core frequencies, sensor lists, network byte counters, a battery voltage.
 *
 * The absent case survives the conversion. [value] is [ABSENT] and [detail] carries the reason, so a
 * missing reading is still visibly missing rather than a zero that arrived from a `?: 0f`.
 */
data class Readout(
    val label: String,
    val value: String,
    val detail: String? = null,
    val fraction: Float? = null,
    val tone: Tone = Tone.Neutral,
) {
    val isAvailable: Boolean get() = value != ABSENT && value != PENDING
}

/**
 * Turns an [Observed] into a [Readout], calling [format] only when there is something to format.
 *
 * The one function every ViewModel in this app uses to prepare a figure for display. Because [format]
 * cannot be reached for a restricted or failed reading, there is no path by which an unavailable value
 * gets rendered as a number — the compiler and this function together are the honesty requirement.
 */
fun <T> Observed<T>.readout(
    label: String,
    tone: Tone = Tone.Neutral,
    fractionOf: ((T) -> Float)? = null,
    detailOf: ((T) -> String)? = null,
    format: (T) -> String,
): Readout = when {
    this is Observed.Value -> Readout(
        label = label,
        value = format(value),
        detail = detailOf?.invoke(value),
        fraction = fractionOf?.invoke(value),
        tone = tone,
    )
    isAwaitingSample -> Readout(label = label, value = PENDING, tone = Tone.Muted)
    else -> Readout(
        label = label,
        value = ABSENT,
        detail = unavailabilityText(),
        tone = Tone.Muted,
    )
}

/** A [Readout] for something that is always there — a battery level, a screen resolution. */
fun readoutOf(
    label: String,
    value: String,
    detail: String? = null,
    fraction: Float? = null,
    tone: Tone = Tone.Neutral,
): Readout = Readout(label, value, detail, fraction, tone)

/** Draws a [Readout] as a dashboard tile. */
@Composable
fun ReadoutTile(
    readout: Readout,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    StatTile(
        label = readout.label,
        value = readout.value,
        modifier = modifier,
        icon = icon,
        tone = readout.tone,
        detail = readout.detail,
        fraction = readout.fraction,
        onClick = onClick,
    )
}

/**
 * Draws a [Readout] as a row, for the screens that list more of them than a grid can hold.
 *
 * The detail is rendered under the pair rather than dropped, because for an absent reading the detail
 * *is* the reason — a list of rows reading "—" with no explanation is the failure this app is built to
 * avoid, and it is also where a measured figure gets to say "device-wide, every app" rather than being
 * mistaken for the game's own.
 */
@Composable
fun ReadoutRow(readout: Readout, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        KeyValueRow(
            label = readout.label,
            value = readout.value,
            tone = readout.tone,
        )
        readout.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}
