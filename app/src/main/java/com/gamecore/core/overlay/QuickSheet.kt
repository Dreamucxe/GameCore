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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Which screen edge the quick sheet is anchored to (spec §4: "anchored to the screen edge nearest the
 * button (user can force left or right)").
 *
 * The window's actual gravity is set by the service when it lays the sheet out — this composable cannot move
 * its own window. The side is passed in so the *content* agrees with that gravity: the close affordance and
 * the title cluster sit against the same edge the sheet hugs, and if the composable is ever given a
 * full-width modifier it aligns itself to the correct edge rather than centring.
 */
enum class QuickSheetSide { LEFT, RIGHT }

/**
 * One pinned toggle as the sheet needs to draw it: which [QuickToggle] it is, whether it is currently on, and
 * what to do when it is pressed.
 *
 * The composable is handed a ready list of these rather than reaching for state itself — it never asks a
 * preference store what is pinned or a service whether a toggle is on, because it runs in a service
 * composition with neither. The service builds this list from [QuickSheetPins.normalise] and the live toggle
 * states, and the sheet just paints it. [onToggle] is the toggle's real action (spec §0: unchanged), wired by
 * the service.
 */
data class QuickToggleState(
    val toggle: QuickToggle,
    val isOn: Boolean,
    val onToggle: () -> Unit,
)

/**
 * The quick sheet of spec §4 — the tap-and-back surface — as **pure UI**.
 *
 * This composable owns no state and knows nothing about the service, Hilt, preferences, or the window it is
 * drawn in. Every value it shows arrives as a parameter and every action it triggers is a lambda the service
 * supplies, exactly like [PerformancePill]. That is what lets the same logic be previewed and reasoned about
 * without a running overlay, and it is what keeps the behaviour (auto-close in [QuickSheetAutoClose], pin
 * rules in [QuickSheetPins]) out of the drawing code where it could not be tested.
 *
 * It draws, top to bottom, the §4 layout: a header (the game's icon slot, its name, the session clock, and a
 * close X), a 3×2 grid of pinned [QuickToggleState]s (accent-filled with a check when on, outlined when off,
 * always icon-plus-label so state is never colour alone — spec §6), single-track brightness and volume
 * sliders with a percent value, and a More / Close button pair. It uses [OverlayPalette] rather than
 * `MaterialTheme` for the same reason the pill does: this is drawn over a game GameCore has never seen, and
 * light text on its own dark plate is the only thing legible over both a white loading screen and a night
 * map. The user's [accent] is passed in and used only for the "on" fill and the primary button, where it sits
 * on GameCore's own plate.
 *
 * Icons are drawn as a foundation-only glyph (the label's initial in a chip) so this file depends on nothing
 * beyond Compose foundation; the service can swap in a real icon set later without changing this contract.
 *
 * @param gameLabel the running game's name for the header.
 * @param clock the session clock, already formatted by the caller (e.g. "12:34").
 * @param pins the pinned toggles to draw, already normalised to at most six by [QuickSheetPins].
 * @param brightness current brightness as a percent 0f..100f; [onBrightnessChange] receives the new
 *   percent on drag/tap. Percent, not 0f..1f, so the sheet lives in the same value-space as
 *   [OverlayLevel.PERCENT_RANGE] and the shared [OverlaySlider] end to end — no ×100 round trip.
 * @param volume current volume as a percent 0f..100f; [onVolumeChange] receives the new percent.
 * @param onMore open the full panel (spec §4 "More").
 * @param onClose close the sheet; the header X and the Close button both call this — they are the same intent.
 * @param modifier applied to the sheet's outer plate.
 * @param side which edge the sheet hugs; aligns the content to match the window gravity the service sets.
 * @param accent the user's accent, used only for the "on" fill and the primary button.
 * @param gameIcon optional slot for the game's real app icon; when null a lettered placeholder chip is drawn.
 */
@Composable
fun QuickSheet(
    gameLabel: String,
    clock: String,
    pins: List<QuickToggleState>,
    brightness: Float,
    onBrightnessChange: (Float) -> Unit,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onMore: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    side: QuickSheetSide = QuickSheetSide.RIGHT,
    accent: Color = OverlayPalette.Good,
    gameIcon: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .widthIn(min = 220.dp, max = 300.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(OverlayPalette.PanelPlate)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = if (side == QuickSheetSide.LEFT) Alignment.Start else Alignment.End,
    ) {
        Header(
            gameLabel = gameLabel,
            clock = clock,
            gameIcon = gameIcon,
            onClose = onClose,
        )

        ToggleGrid(pins = pins, accent = accent)

        // The shared §6 slider, in percent space. Per-frame `onValueChange` is fine here: the service
        // debounces the commit (`onQuickLevel`, 120 ms), so a drag is one device write, not sixty.
        OverlaySlider(
            label = "Brightness",
            value = brightness,
            min = 0f,
            max = 100f,
            step = 1f,
            unit = "%",
            onValueChange = onBrightnessChange,
            modifier = Modifier.fillMaxWidth(),
            accent = accent,
        )
        OverlaySlider(
            label = "Volume",
            value = volume,
            min = 0f,
            max = 100f,
            step = 1f,
            unit = "%",
            onValueChange = onVolumeChange,
            modifier = Modifier.fillMaxWidth(),
            accent = accent,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SheetButton(label = "More", onClick = onMore, filled = false, accent = accent, modifier = Modifier.weight(1f))
            SheetButton(label = "Close", onClick = onClose, filled = true, accent = accent, modifier = Modifier.weight(1f))
        }
    }
}

/** Header: icon, game name and clock on one edge, a close X on the other (spec §4). */
@Composable
private fun Header(
    gameLabel: String,
    clock: String,
    gameIcon: (@Composable () -> Unit)?,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(OverlayPalette.Plate),
            contentAlignment = Alignment.Center,
        ) {
            if (gameIcon != null) {
                gameIcon()
            } else {
                // Foundation-only placeholder: the game's initial until the service wires a real icon.
                BasicText(
                    text = gameLabel.trim().take(1).uppercase().ifEmpty { "•" },
                    style = TextStyle(color = OverlayPalette.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = gameLabel.ifBlank { "Game" },
                maxLines = 1,
                style = TextStyle(color = OverlayPalette.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            )
            BasicText(
                text = clock,
                style = TextStyle(color = OverlayPalette.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .background(OverlayPalette.Plate),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = "✕",
                style = TextStyle(color = OverlayPalette.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}

/** The 3×2 pinned-toggle grid (spec §4). Rows of three, padded with empty cells so widths stay even. */
@Composable
private fun ToggleGrid(pins: List<QuickToggleState>, accent: Color) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        pins.chunked(COLUMNS).forEach { rowPins ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowPins.forEach { state ->
                    ToggleCell(state = state, accent = accent, modifier = Modifier.weight(1f))
                }
                // Keep the last, short row's cells the same width as full rows.
                repeat(COLUMNS - rowPins.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * One grid cell (spec §6 toggle rule): accent-filled with a check when on, outlined when off, always with the
 * icon glyph and the label so the state is legible without colour.
 */
@Composable
private fun ToggleCell(state: QuickToggleState, accent: Color, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val base = modifier
        .heightIn(min = 60.dp)
        .clip(shape)
        .clickable(onClick = state.onToggle)
    val styled = if (state.isOn) {
        base.background(accent)
    } else {
        base
            .background(OverlayPalette.Plate)
            .border(1.dp, OverlayPalette.Muted, shape)
    }
    val contentColour = if (state.isOn) OverlayPalette.Plate else OverlayPalette.Text
    Column(
        modifier = styled.padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Foundation-only icon placeholder: the toggle's initial.
        BasicText(
            text = state.toggle.label.take(1).uppercase(),
            style = TextStyle(color = contentColour, fontSize = 16.sp, fontWeight = FontWeight.Bold),
        )
        BasicText(
            text = if (state.isOn) "✓ ${state.toggle.label}" else state.toggle.label,
            maxLines = 1,
            style = TextStyle(
                color = contentColour,
                fontSize = 11.sp,
                fontWeight = if (state.isOn) FontWeight.SemiBold else FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/** A sheet button: accent-filled for the primary (Close), outlined for the secondary (More). */
@Composable
private fun SheetButton(label: String, onClick: () -> Unit, filled: Boolean, accent: Color, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val base = modifier
        .heightIn(min = 44.dp)
        .clip(shape)
        .clickable(onClick = onClick)
    val styled = if (filled) base.background(accent) else base.border(1.dp, OverlayPalette.Muted, shape)
    Box(modifier = styled.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        BasicText(
            text = label,
            style = TextStyle(
                color = if (filled) OverlayPalette.Plate else OverlayPalette.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

/** The grid's column count, matching the 3×2 shape [QuickSheetPins.MAX_PINS] fills. */
private const val COLUMNS = 3
