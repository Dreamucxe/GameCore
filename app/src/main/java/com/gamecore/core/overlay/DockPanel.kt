package com.gamecore.core.overlay

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One toggle as the compact [DockPanel] draws it (feature 1): which control it is by [label], whether it
 * is currently on, and what to run when it is pressed.
 *
 * The panel is handed a ready list of these rather than reaching for state itself — like [QuickSheet]'s
 * [QuickToggleState] it never asks a preference store what is on, because it runs in a service composition
 * with neither store nor Hilt. The service builds the list from the live toggle states and the panel just
 * paints it; [onToggle] is the toggle's real action, wired service-side.
 *
 * @param enabled false when this device, or this moment, cannot do the thing (§3.7.1, feature 5). The cell
 *   is still **drawn** — it is on the dock because the user put it there, and silently dropping it would
 *   read as the arrangement having been forgotten — but it does not respond. Defaults true so the 3.7.0
 *   call sites, which had no notion of availability, keep behaving exactly as they did.
 * @param reason the short human answer to "why is this greyed out", drawn under the label the way
 *   [OverlayControlPanel]'s own action buttons draw [OverlayPanelState.reasonFor]. Null when [enabled], and
 *   a disabled toggle with a null reason is a bug being honest rather than a sentence being invented.
 */
data class DockToggle(
    val label: String,
    val isOn: Boolean,
    val onToggle: () -> Unit,
    val enabled: Boolean = true,
    val reason: String? = null,
)

/**
 * One momentary action as the [DockPanel] draws it (feature 1): the [label] for the chip and what to run
 * on a tap.
 *
 * An action has no on/off state — it is a one-tap command, not a toggle — so unlike [DockToggle] it carries
 * no `isOn`; the chip is momentary, the sibling of [QuickSheet]'s [MacroChip]. The service resolves names
 * and wires [onRun] before the panel ever sees one.
 *
 * @param enabled and @param reason as [DockToggle]'s, for the same reason and with the same default.
 */
data class DockAction(
    val label: String,
    val onRun: () -> Unit,
    val enabled: Boolean = true,
    val reason: String? = null,
)

/**
 * The compact control panel the [FloatingDock] expands into (feature 1) — **pure UI**.
 *
 * This is what a tap on the dock handle opens: a small, `WRAP_CONTENT`-friendly plate of the toggles and
 * one-tap actions the user pinned to the dock, plus a close affordance. It is deliberately **not** the
 * [QuickSheet]: the quick sheet is a fuller surface with a game header, a session clock and brightness /
 * volume sliders, opened from the floating button; the dock panel is the smaller sibling — no header
 * chrome, no sliders — so the dock stays a light "handful of controls where I parked it" surface rather
 * than a second copy of the sheet. The toggle-cell and chip styling is shared with the sheet on purpose so
 * the two read as one family.
 *
 * Like [QuickSheet] and [PerformancePill] it owns no state and knows nothing about the service, Hilt,
 * preferences or the window it is drawn in: every value it shows arrives as a parameter and every action it
 * triggers is a lambda the service supplies. That is what lets it be previewed without a running overlay
 * and keeps any behaviour out of the drawing code. It uses [OverlayPalette] rather than `MaterialTheme` for
 * the same reason the sheet does — it is drawn over a game GameCore has never seen, and light text on its
 * own dark plate is the only thing legible over both a white loading screen and a night map. The user's
 * [accent] is passed in and used only for the "on" fill and an action chip's outline, where it sits on
 * GameCore's own plate.
 *
 * Icons are drawn as a foundation-only glyph (a ✓/▶/✕ marker and the label) so this file depends on nothing
 * beyond Compose foundation; the service can swap in a real icon set later without changing this contract.
 * State is shown by shape and word, never colour alone (spec §6): an "on" cell is filled *and* prefixed
 * with a ✓, an "off" cell is outlined, an unavailable one is prefixed with a ✕ *and* carries its reason in
 * words, so the state survives a colour-blind read or a washed-out screen.
 *
 * **Width comes from the window, not from here** (§3.7.1, feature 4). The dock's panel width is a setting
 * the user drags between [DockConfig.MIN_PANEL_WIDTH_DP] and [DockConfig.MAX_PANEL_WIDTH_DP], and the
 * service latches it into the overlay's `LayoutParams`; a plate that also clamped itself to a 200–280dp
 * band would leave a transparent gutter inside a wide window and overflow a narrow one, so the plate fills
 * whatever width it is given and the setting is the single place width is decided.
 *
 * **The controls scroll, the header does not.** With everything switched on the dock draws up to
 * [DockActions.MAX_VISIBLE] controls, which is taller than a landscape phone has room for; the grids sit in
 * a [PANEL_MAX_CONTENT_HEIGHT]-capped scrolling column so a full dock is reachable rather than clipped,
 * while the close ✕ stays pinned where the user can always reach it. A dock holding only a few controls is
 * shorter than the cap and so does not scroll at all.
 *
 * @param toggles the dock's pinned toggles, drawn as a two-column grid; empty draws no grid at all.
 * @param actions the dock's one-tap actions, drawn as a two-column row of momentary chips under the grid;
 *   empty draws no action row, so a dock with only toggles (or only actions) shows exactly what it has.
 * @param onClose close the panel; the header ✕ calls this. The service collapses the dock back to its
 *   handle and flips `expanded` off.
 * @param modifier applied to the panel's outer plate.
 * @param title the panel's heading; defaults to [PANEL_TITLE].
 * @param accent the user's accent, used only for the "on" fill and an action chip's outline.
 */
@Composable
fun DockPanel(
    toggles: List<DockToggle>,
    actions: List<DockAction>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = PANEL_TITLE,
    accent: Color = OverlayPalette.Good,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(OverlayPalette.PanelPlate)
            .border(BORDER_DP.dp, OverlayPalette.Divider, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DockHeader(title = title, onClose = onClose)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = PANEL_MAX_CONTENT_HEIGHT.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Drawn only when the dock actually has some, so a dock with just actions shows just the action
            // row and vice versa — no empty grid holding open a gap the user never filled.
            if (toggles.isNotEmpty()) {
                ToggleGrid(toggles = toggles, accent = accent)
            }

            // §14-style momentary chips: a one-tap command, no on/off state (unlike the grid above), so each
            // is a plain accent-outlined button rather than a toggle cell.
            if (actions.isNotEmpty()) {
                ActionGrid(actions = actions, accent = accent)
            }

            // A dock the user emptied entirely. Says so, rather than drawing a plate with a title and a
            // void under it, and names where the arrangement lives so the way back is obvious.
            if (toggles.isEmpty() && actions.isEmpty()) {
                BasicText(
                    text = EMPTY_DOCK_NOTE,
                    modifier = Modifier.fillMaxWidth(),
                    style = TextStyle(
                        color = OverlayPalette.Muted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }
}

/** Header: the panel title on one edge, a close ✕ on the other (the [QuickSheet]'s close, minus the clock). */
@Composable
private fun DockHeader(title: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            text = title.ifBlank { PANEL_TITLE },
            maxLines = 1,
            modifier = Modifier.weight(1f),
            style = TextStyle(color = OverlayPalette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .background(OverlayPalette.Plate)
                .semantics { contentDescription = CLOSE_DESCRIPTION },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = "✕",
                style = TextStyle(color = OverlayPalette.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

/** The pinned-toggle grid, rows of [TOGGLE_COLUMNS], padded with empty cells so widths stay even. */
@Composable
private fun ToggleGrid(toggles: List<DockToggle>, accent: Color) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        toggles.chunked(TOGGLE_COLUMNS).forEach { rowToggles ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowToggles.forEach { toggle ->
                    ToggleCell(toggle = toggle, accent = accent, modifier = Modifier.weight(1f))
                }
                // Keep the last, short row's cells the same width as full rows.
                repeat(TOGGLE_COLUMNS - rowToggles.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * One grid cell (spec §6 toggle rule), in one of three states.
 *
 * **On** is accent-filled and prefixed with a ✓. **Off** is outlined. **Unavailable** is outlined in
 * [OverlayPalette.Absent], prefixed with a ✕, and carries its reason under the label — the same three
 * states [OverlayControlPanel]'s own action buttons draw, in the glyphs this foundation-only panel has.
 * In every case the label is drawn, so the state is legible without colour.
 *
 * An unavailable cell drops `.clickable` entirely rather than taking the tap and ignoring it: a cell that
 * depresses and then does nothing reads as broken, while one that does not respond reads as off.
 */
@Composable
private fun ToggleCell(toggle: DockToggle, accent: Color, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val base = modifier
        .heightIn(min = 52.dp)
        .clip(shape)
    val tappable = if (toggle.enabled) base.clickable(onClick = toggle.onToggle) else base
    val styled = when {
        !toggle.enabled -> tappable
            .background(OverlayPalette.Plate)
            .border(BORDER_DP.dp, OverlayPalette.Absent, shape)
        toggle.isOn -> tappable.background(accent)
        else -> tappable
            .background(OverlayPalette.Plate)
            .border(BORDER_DP.dp, OverlayPalette.Muted, shape)
    }
    val contentColour = when {
        !toggle.enabled -> OverlayPalette.Absent
        toggle.isOn -> OverlayPalette.Plate
        else -> OverlayPalette.Text
    }
    val prefix = when {
        !toggle.enabled -> "$UNAVAILABLE_GLYPH "
        toggle.isOn -> "$ON_GLYPH "
        else -> ""
    }
    Column(
        modifier = styled.padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BasicText(
            text = "$prefix${toggle.label}",
            maxLines = 1,
            style = TextStyle(
                color = contentColour,
                fontSize = 12.sp,
                fontWeight = if (toggle.isOn && toggle.enabled) FontWeight.SemiBold else FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
        )
        // Only when it is both unavailable and has something to say: the reason answers "why is this greyed
        // out", which is a question nobody asks of a control that works.
        if (!toggle.enabled) {
            toggle.reason?.let { reason ->
                BasicText(
                    text = reason,
                    maxLines = 2,
                    style = TextStyle(
                        color = OverlayPalette.Absent,
                        fontSize = 9.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }
}

/** The one-tap action grid, rows of [ACTION_COLUMNS], padded like [ToggleGrid] so widths stay even. */
@Composable
private fun ActionGrid(actions: List<DockAction>, accent: Color) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.chunked(ACTION_COLUMNS).forEach { rowActions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowActions.forEach { action ->
                    ActionChip(action = action, accent = accent, modifier = Modifier.weight(1f))
                }
                repeat(ACTION_COLUMNS - rowActions.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * One action chip: an accent-outlined momentary button — a ▶ glyph and the action's name, no fill (no state).
 *
 * Unavailable swaps the outline and glyph to [OverlayPalette.Absent] / ✕ and drops `.clickable`, matching
 * [ToggleCell]'s third state so the two grids read as one family, with the reason beside the label.
 */
@Composable
private fun ActionChip(action: DockAction, accent: Color, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val outline = if (action.enabled) accent else OverlayPalette.Absent
    val base = modifier
        .heightIn(min = 40.dp)
        .clip(shape)
        .background(OverlayPalette.Plate)
        .border(BORDER_DP.dp, outline, shape)
    val tappable = if (action.enabled) base.clickable(onClick = action.onRun) else base
    Row(
        modifier = tappable.padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BasicText(
            text = if (action.enabled) ACTION_GLYPH else UNAVAILABLE_GLYPH,
            style = TextStyle(color = outline, fontSize = 11.sp, fontWeight = FontWeight.Bold),
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BasicText(
                text = action.label,
                maxLines = 1,
                style = TextStyle(
                    color = if (action.enabled) OverlayPalette.Text else OverlayPalette.Absent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            if (!action.enabled) {
                action.reason?.let { reason ->
                    BasicText(
                        text = reason,
                        maxLines = 2,
                        style = TextStyle(color = OverlayPalette.Absent, fontSize = 9.sp),
                    )
                }
            }
        }
    }
}

private const val BORDER_DP = 1f

/**
 * How tall the scrolling control area may get before it scrolls instead of growing.
 *
 * Sized so a full dock — [DockActions.MAX_VISIBLE] controls, four rows of 52dp cells plus the gaps — is
 * reachable on a landscape phone, where the whole screen is often under 400dp tall. Past this the column
 * scrolls; under it the panel simply wraps, so a three-control dock is still a three-control-tall plate.
 */
private const val PANEL_MAX_CONTENT_HEIGHT = 300

/** Two columns for both grids — a name has room in the narrow panel. */
private const val TOGGLE_COLUMNS = 2
private const val ACTION_COLUMNS = 2

// ------------------------------------------------------------------------------- user-facing copy
private const val PANEL_TITLE = "Dock"
private const val CLOSE_DESCRIPTION = "Close dock panel"
private const val EMPTY_DOCK_NOTE = "No controls on the dock.\nAdd some in Settings › Dock customisation."

/** Glyphs, not icons: this panel is foundation-only, and each of the three states gets its own mark. */
private const val ON_GLYPH = "✓"
private const val ACTION_GLYPH = "▶"
private const val UNAVAILABLE_GLYPH = "✕"
