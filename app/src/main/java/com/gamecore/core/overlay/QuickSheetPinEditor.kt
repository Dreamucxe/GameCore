package com.gamecore.core.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The quick sheet's pin editor (spec §4): choose which ≤6 toggles are pinned to the grid, and in what
 * order.
 *
 * This is a **pure view over the committed pin rules** in [QuickSheetPins]. It owns no policy of its own —
 * it never decides how many pins fit, whether a toggle may be added, or where a reorder lands. It renders
 * the [pinned] list it is handed and turns each affordance into a single call back to the caller, which is
 * expected to route it straight through [QuickSheetPins.add], [QuickSheetPins.remove] or
 * [QuickSheetPins.move] and hand back the new list. Because those functions are no-ops when a rule would
 * break (full list, duplicate, unavailable, a move off either end), this composable can stay honest by
 * simply *disabling* the affordance in exactly the cases the rule would reject — the button and the rule
 * never disagree, and the rule remains the single source of truth even if a disabled button were somehow
 * pressed.
 *
 * Three regions, top to bottom:
 *  - **Pinned**, in order. Each row carries move-up, move-down and remove. Move-up is disabled on the first
 *    row and move-down on the last, because [QuickSheetPins.move] treats the ends as walls, not a wrap — a
 *    button that pretended otherwise would fire a call that changes nothing.
 *  - **Add**, the unpinned-but-available toggles. Each offers an add button, disabled for all of them at
 *    once when the list is already at [QuickSheetPins.MAX_PINS]; a cap indicator ("5 / 6 pinned") says why.
 *  - **Unavailable**, never hidden. A toggle this device cannot do is drawn dimmed with the caller's short
 *    [reasonFor] string ("Requires Shizuku"), so the user learns the control exists and why it can't be
 *    pinned rather than wondering where it went.
 *
 * Pure UI in the overlay's terms: state in ([pinned] plus the [isAvailable]/[reasonFor] predicates),
 * lambdas out, drawn with [OverlayPalette] and Compose foundation primitives rather than `MaterialTheme`,
 * because — like every overlay surface — it is composed by a service with no theme installed and is meant
 * to read over a game.
 *
 * @param pinned the current pinned toggles, already normalised by the caller, in grid order.
 * @param isAvailable whether a toggle can work on this device here and now (the caller's device probe).
 * @param reasonFor a short human reason a toggle is unavailable, e.g. "Requires Shizuku"; may be null.
 * @param onAdd fire to pin a toggle; route through [QuickSheetPins.add].
 * @param onRemove fire to unpin a toggle; route through [QuickSheetPins.remove].
 * @param onMove fire to shift a pin by a delta of -1 (towards the front) or +1 (towards the back); route
 *   through [QuickSheetPins.move].
 * @param accent the user's accent, used only for the enabled add / move affordances on GameCore's own
 *   surface, never over the game.
 */
@Composable
fun QuickSheetPinEditor(
    pinned: List<QuickToggle>,
    isAvailable: (QuickToggle) -> Boolean,
    reasonFor: (QuickToggle) -> String?,
    onAdd: (QuickToggle) -> Unit,
    onRemove: (QuickToggle) -> Unit,
    onMove: (QuickToggle, Int) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = OverlayPalette.Good,
) {
    val atCap = pinned.size >= QuickSheetPins.MAX_PINS
    // Every toggle this build knows, minus the ones already pinned, split by whether the device can do it.
    val unpinned = QuickToggle.entries.filter { it !in pinned }
    val addable = unpinned.filter(isAvailable)
    val unavailable = unpinned.filterNot(isAvailable)

    Column(
        modifier = modifier
            .background(OverlayPalette.PanelPlate, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Header: the section title and the cap indicator that explains a disabled Add.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("Pinned toggles")
            BasicText(
                text = "${pinned.size} / ${QuickSheetPins.MAX_PINS} pinned",
                style = TextStyle(
                    color = if (atCap) accent else OverlayPalette.Muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }

        if (pinned.isEmpty()) {
            BasicText(
                text = "No toggles pinned. Add up to ${QuickSheetPins.MAX_PINS} below.",
                style = TextStyle(color = OverlayPalette.Muted, fontSize = 13.sp),
            )
        } else {
            pinned.forEachIndexed { index, toggle ->
                PinnedRow(
                    toggle = toggle,
                    isFirst = index == 0,
                    isLast = index == pinned.lastIndex,
                    onMove = onMove,
                    onRemove = onRemove,
                    accent = accent,
                )
            }
        }

        if (addable.isNotEmpty()) {
            Divider()
            SectionLabel("Add")
            addable.forEach { toggle ->
                AddRow(toggle = toggle, atCap = atCap, onAdd = onAdd, accent = accent)
            }
        }

        if (unavailable.isNotEmpty()) {
            Divider()
            SectionLabel("Unavailable on this device")
            unavailable.forEach { toggle ->
                UnavailableRow(toggle = toggle, reason = reasonFor(toggle))
            }
        }
    }
}

/** A pinned toggle with its reorder and remove affordances. */
@Composable
private fun PinnedRow(
    toggle: QuickToggle,
    isFirst: Boolean,
    isLast: Boolean,
    onMove: (QuickToggle, Int) -> Unit,
    onRemove: (QuickToggle) -> Unit,
    accent: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OverlayPalette.Plate, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLabel(text = toggle.label, modifier = Modifier.weight(1f))
        // -1 towards the front; a wall on the first row, matching QuickSheetPins.move.
        EditorButton(
            glyph = "▲",
            description = "Move ${toggle.label} up",
            enabled = !isFirst,
            accent = accent,
            onClick = { onMove(toggle, -1) },
        )
        // +1 towards the back; a wall on the last row.
        EditorButton(
            glyph = "▼",
            description = "Move ${toggle.label} down",
            enabled = !isLast,
            accent = accent,
            onClick = { onMove(toggle, 1) },
        )
        EditorButton(
            glyph = "✕",
            description = "Remove ${toggle.label}",
            enabled = true,
            accent = OverlayPalette.Danger,
            onClick = { onRemove(toggle) },
        )
    }
}

/** An unpinned, available toggle offered as an add candidate. */
@Composable
private fun AddRow(
    toggle: QuickToggle,
    atCap: Boolean,
    onAdd: (QuickToggle) -> Unit,
    accent: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLabel(text = toggle.label, modifier = Modifier.weight(1f))
        // Disabled for every candidate at once when the grid is full; the header cap indicator says why.
        EditorButton(
            glyph = "+ Add",
            description = if (atCap) {
                "Add ${toggle.label} (grid full, remove one first)"
            } else {
                "Add ${toggle.label}"
            },
            enabled = !atCap,
            accent = accent,
            onClick = { onAdd(toggle) },
        )
    }
}

/** A toggle the device cannot do: never hidden, always shown with its reason. */
@Composable
private fun UnavailableRow(toggle: QuickToggle, reason: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(0.6f)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics {
                disabled()
                contentDescription = reason
                    ?.let { "${toggle.label} unavailable: $it" }
                    ?: "${toggle.label} unavailable"
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLabel(text = toggle.label, modifier = Modifier.weight(1f), color = OverlayPalette.Absent)
        BasicText(
            text = reason ?: "Unavailable",
            style = TextStyle(
                color = OverlayPalette.Absent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

/** A small tappable glyph button. Colour alone never carries meaning — each one names itself for TalkBack. */
@Composable
private fun EditorButton(
    glyph: String,
    description: String,
    enabled: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val tint = if (enabled) accent else OverlayPalette.Absent
    Box(
        modifier = Modifier
            .border(BorderStroke(1.dp, tint), RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics {
                contentDescription = description
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = glyph,
            style = TextStyle(
                color = tint,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

/** A section heading run: muted, small, uppercase-weighted. */
@Composable
private fun SectionLabel(text: String) {
    BasicText(
        text = text,
        style = TextStyle(
            color = OverlayPalette.Muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        ),
    )
}

/** The toggle's own name, the primary run of a row. */
@Composable
private fun RowLabel(text: String, modifier: Modifier = Modifier, color: Color = OverlayPalette.Text) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        ),
    )
}

/** A hairline rule between regions, the panel's own divider colour. */
@Composable
private fun Divider() {
    Spacer(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(OverlayPalette.Divider),
    )
}
