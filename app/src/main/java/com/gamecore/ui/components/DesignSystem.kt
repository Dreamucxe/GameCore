package com.gamecore.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamecore.core.model.ProfileChip
import com.gamecore.core.model.ProfileChipKind
import com.gamecore.ui.theme.StatusColor
import com.gamecore.ui.theme.colour

/**
 * The redesign's §2 components that were not already in [Layout], [Stats] or [Controls].
 *
 * Everything the spec's component list names that the app already had — a card, a section header, a
 * confirm dialog, a metric tile, an empty state, a settings row, a filter chip — is reused from those
 * files rather than rebuilt here (that is the §0 "reuse existing tokens" rule). This file adds only the
 * three the app genuinely lacked: a *tiny* inline sparkline distinct from the full [LineGraph], the
 * tool-grid tile the reorganised navigation needs (§9), and a status chip driven by the semantic
 * [StatusColor] set rather than the older [Tone].
 */

/**
 * A minimal line, no grid, no scale, no legend — the [LineGraph]'s small sibling.
 *
 * For the places a trend belongs *inside* a row or a hero rather than on a card of its own: the Home
 * hero's last-session line, a session-list row's shape-at-a-glance. [LineGraph] draws gridlines, a fill
 * and enough structure to be read as a measurement with a scale beside it; a sparkline is deliberately
 * none of that — it is a shape, and the number it belongs to is printed next to it.
 *
 * Follows the same two honesty rules as [LineGraph]: a gap is not a zero (the caller hands in a list that
 * is already `mapNotNull`-ed, so a missing sample shortens the line rather than dropping it to the floor),
 * and with fewer than two points there is nothing to draw, so it draws nothing rather than a single dot
 * pretending to be a trend.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    colour: Color = MaterialTheme.colorScheme.primary,
    height: Int = 28,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp),
    ) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        // A flat series has span 0; use 1 so it draws a centred horizontal line rather than dividing by
        // zero and landing every point on the top edge.
        val span = (max - min).takeIf { it > 0f } ?: 1f
        val stroke = 2f * density
        val top = stroke / 2f
        val usable = (size.height - stroke).coerceAtLeast(1f)
        val stepX = if (values.size > 1) size.width / (values.size - 1) else size.width

        val path = Path()
        values.forEachIndexed { index, value ->
            val x = stepX * index
            val fraction = ((value - min) / span).coerceIn(0f, 1f)
            val y = top + usable * (1f - fraction)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = colour,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * A square-ish icon-over-label tile for the Tools grid (§9).
 *
 * The reorganised navigation moves the HUD builder, crosshair editor, colour tools and the rest off the
 * bottom bar and into a grid of these on a Tools screen — so a tool is one tap with a clear target and a
 * label, rather than a row in a menu. Deliberately a peer of [StatTile] in look (the same card, the same
 * outline) but built for an action rather than a reading: it is always clickable, centres its content, and
 * holds a 48.dp-plus touch target via [defaultMinSize].
 *
 * [enabled] draws the tile muted and drops its click, for a tool a device cannot offer (no HUD without
 * "display over other apps", say) — visible, so the user learns the tool exists, but honestly inert rather
 * than tappable-to-nothing (§32).
 */
@Composable
fun ToolTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val contentTint = if (enabled) scheme.onSurface else scheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 96.dp),
        shape = MaterialTheme.shapes.large,
        color = scheme.surface,
        contentColor = contentTint,
        border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = contentTint,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A status chip driven by the semantic [StatusColor] set, for the callers that classify with
 * [com.gamecore.core.model.ThermalClassifier] and its neighbours rather than picking a [Tone].
 *
 * The older [StatusChip] (in [Layout]) takes a [Tone] and stays for the many call sites that already use
 * it; this overload exists so a caller holding a `StatusColor` — the thing the shared classifier now
 * returns — does not have to translate it back into a `Tone` just to draw a chip, which is exactly the
 * kind of lossy round-trip that let the word and the colour disagree in the first place.
 *
 * §2's rule holds: colour is never the only signal. [text] is always present and is the classifier's own
 * word ("Warm", "Critical"), so the chip reads correctly to someone who cannot tell the tints apart.
 */
@Composable
fun StatusChip(text: String, status: StatusColor, icon: ImageVector? = null) {
    val colour = status.colour()
    Surface(
        shape = MaterialTheme.shapes.small,
        color = colour.copy(alpha = 0.14f),
        contentColor = colour,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(text = text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * The tone a [ProfileChipKind] draws in, keeping the meaning→colour decision in the UI layer.
 *
 * [ProfileChips][com.gamecore.core.model.profileChips] deliberately names no colours; this is where its
 * four kinds become tones. Device settings are neutral (they are the ordinary case), overlay is the
 * accent (it is GameCore's own UI), the two process-level effects are a warning tint because they touch
 * other apps and the game's own process and are the least reversible, and tracking is muted.
 */
@Composable
private fun ProfileChipKind.tone(): Tone = when (this) {
    ProfileChipKind.DEVICE -> Tone.Neutral
    ProfileChipKind.OVERLAY -> Tone.Accent
    ProfileChipKind.PROCESS -> Tone.Warning
    ProfileChipKind.TRACKING -> Tone.Muted
}

/**
 * A wrapping row of a profile's [ProfileChip]s, for the redesigned Games card (§5).
 *
 * Each chip is a small tinted pill; the tint comes from its [ProfileChipKind] via [tone], so the two
 * process-level effects stand out from the ordinary device settings. An empty list draws nothing — the
 * card shows its own "changes nothing yet" note instead, so this never renders an empty pill.
 *
 * Chunked by hand into rows of [perRow] rather than with `FlowRow`, matching the decision recorded in
 * [com.gamecore.core.overlay.OverlayControlPanel]: `FlowRow` is still experimental in the pinned Compose
 * BOM and this app does not opt into experimental layout APIs for a chip row. A short final row is left
 * ragged (chips are their own width, unlike [ChoiceRow]'s equal-weight options), which is what a chip row
 * should look like.
 */
@Composable
fun ProfileChipRow(chips: List<ProfileChip>, modifier: Modifier = Modifier, perRow: Int = 3) {
    if (chips.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.chunked(perRow.coerceAtLeast(1)).forEach { rowChips ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowChips.forEach { chip ->
                    val colour = chip.kind.tone().colour()
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = colour.copy(alpha = 0.14f),
                        contentColor = colour,
                    ) {
                        Text(
                            text = chip.text,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
