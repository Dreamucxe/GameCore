package com.gamecore.core.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamecore.core.model.PanelLayoutStyle

/**
 * The in-game panel-layout chooser, as **pure UI**.
 *
 * This is the surface the floating button opens on a long press, and the one the quick sheet's Layout
 * button opens. It exists so a user can change the shape of the panel without leaving the game for
 * Settings: four choices, each drawn with its own silhouette, and the current one marked in words as
 * well as in colour.
 *
 * Like [QuickSheet] and [PerformancePill] it owns no state and knows nothing about the service. The
 * selected style arrives as a parameter and a tap reports the style through [onSelect]; writing the
 * preference and rebuilding the open panel are the service's business. That is what keeps the choice a
 * single preference shared by this picker, the panel's own Layout tile and the settings screen.
 *
 * The silhouette is drawn rather than lettered because the four layouts differ in *shape* before they
 * differ in anything else, and a row of four words under a game asks the player to read instead of
 * glance. [PanelLayoutStyle.description] is deliberately not repeated here — it is a sentence for a
 * settings card, not for a plate the size of a thumb.
 */
@Composable
fun LayoutPicker(
    selected: PanelLayoutStyle,
    accent: Color,
    onSelect: (PanelLayoutStyle) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(OverlayPalette.PanelPlate)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    text = "Panel layout",
                    maxLines = 1,
                    style = TextStyle(
                        color = OverlayPalette.Text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                BasicText(
                    text = "Chooses the shape the panel opens in",
                    maxLines = 2,
                    style = TextStyle(color = OverlayPalette.Muted, fontSize = 10.sp),
                )
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss)
                    .background(OverlayPalette.Plate)
                    .semantics { contentDescription = "Close the layout picker" },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = "✕",
                    style = TextStyle(
                        color = OverlayPalette.Text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }

        // Two rows of two, in declaration order — the same order the settings card lists and the
        // panel's Layout tile cycles, so the four surfaces agree without a second ordering table.
        PanelLayoutStyle.entries.chunked(COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { style ->
                    LayoutChoice(
                        style = style,
                        selected = style == selected,
                        accent = accent,
                        onSelect = { onSelect(style) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(COLUMNS - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        BasicText(
            text = "Hold the floating button any time to change this in-game.",
            maxLines = 2,
            style = TextStyle(
                color = OverlayPalette.Absent,
                fontSize = 9.sp,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One layout tile: its silhouette, its name, and the word that says it is the one in force. */
@Composable
private fun LayoutChoice(
    style: PanelLayoutStyle,
    selected: Boolean,
    accent: Color,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val tint = if (selected) accent else OverlayPalette.Muted
    Column(
        modifier = modifier
            .heightIn(min = LAYOUT_CHOICE_HEIGHT_DP.dp)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.16f) else OverlayPalette.Plate)
            .border(
                width = 1.dp,
                color = if (selected) accent.copy(alpha = 0.75f) else OverlayPalette.Muted.copy(alpha = 0.28f),
                shape = shape,
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .semantics {
                contentDescription = if (selected) {
                    "${style.label}, current layout"
                } else {
                    style.label
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(modifier = Modifier.size(width = 44.dp, height = 24.dp)) {
            drawLayoutSilhouette(style = style, tint = tint)
        }
        BasicText(
            text = style.label,
            maxLines = 1,
            style = TextStyle(
                color = if (selected) OverlayPalette.Text else OverlayPalette.Muted,
                fontSize = 10.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
        )
        BasicText(
            // A line reserved in every tile, so the selected tile does not stand taller than its
            // neighbours and move the row under the finger that just tapped it.
            text = if (selected) "CURRENT" else " ",
            maxLines = 1,
            style = TextStyle(
                color = accent,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.9.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/** A tiny silhouette of each layout, drawn with the same outline the real surface takes. */
private fun DrawScope.drawLayoutSilhouette(style: PanelLayoutStyle, tint: Color) {
    val width = size.width
    val height = size.height
    val radius = CornerRadius(3.dp.toPx())
    when (style) {
        PanelLayoutStyle.CENTERED -> drawRoundRect(
            color = tint,
            topLeft = Offset(width * 0.28f, height * 0.10f),
            size = Size(width * 0.44f, height * 0.80f),
            cornerRadius = radius,
        )

        PanelLayoutStyle.SPLIT_EDGES -> {
            drawRoundRect(
                color = tint,
                topLeft = Offset(0f, 0f),
                size = Size(width * 0.22f, height),
                cornerRadius = radius,
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(width * 0.78f, 0f),
                size = Size(width * 0.22f, height),
                cornerRadius = radius,
            )
        }

        PanelLayoutStyle.TURBO_WINGS -> {
            // The bent inner edge is the shape's signature, so the glyph bends it too.
            drawPath(
                path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(width * 0.30f, height * 0.12f)
                    lineTo(width * 0.30f, height * 0.88f)
                    lineTo(0f, height)
                    close()
                },
                color = tint,
            )
            drawPath(
                path = Path().apply {
                    moveTo(width, 0f)
                    lineTo(width * 0.70f, height * 0.12f)
                    lineTo(width * 0.70f, height * 0.88f)
                    lineTo(width, height)
                    close()
                },
                color = tint,
            )
        }

        PanelLayoutStyle.TURBO_DECK -> drawRoundRect(
            color = tint,
            topLeft = Offset(0f, 0f),
            size = Size(width * 0.66f, height),
            cornerRadius = radius,
        )
    }
}

/** The picker's two-column grid. */
private const val COLUMNS = 2

/** Tall enough for the glyph, the name and the current line at a thumb's size. */
private const val LAYOUT_CHOICE_HEIGHT_DP = 82

/**
 * How wide the picker window opens, in dp.
 *
 * Fixed rather than wrap-content so the service can place the right edge exactly before the window is
 * measured — the same reason the quick sheet's width is fixed. Two tiles and the padding fit at this
 * width on the narrowest phone and still read on a tablet.
 */
internal const val LAYOUT_PICKER_WIDTH_DP = 252
