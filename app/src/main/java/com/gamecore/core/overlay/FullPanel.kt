package com.gamecore.core.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import com.gamecore.domain.monitoring.StatReading
import com.gamecore.domain.overlay.QuickApp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A control the full panel draws, resolved to what the panel needs to show it (spec §5).
 *
 * The panel never decides *what a control does* — that stays in the service, unchanged (spec §0). It only
 * needs, per control: a stable [id] matching [PanelReachability] (so the reachability test and the panel
 * agree), a [label], whether it is [enabled] on this device, the [reason] it is not (shown as secondary
 * text with an info affordance, per §5's "Requires Shizuku" rule), and a [content] slot the caller fills
 * with the real control (an [OverlaySlider], an [OverlayToggle], or a value-row that opens a sub-view).
 *
 * Passing the control as a slot rather than a config keeps this file from having to know every control
 * type: the service builds an [OverlayToggle] for Crosshair and a value-row for Refresh rate, and the
 * panel just places them under the right tab with a consistent disabled/reason treatment around them.
 */
class PanelControl(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val reason: String? = null,
    val content: @Composable () -> Unit,
)

/**
 * The full panel of spec §5 — Display / Overlays / Capture / Session tabs — as **pure UI**.
 *
 * State in, lambdas out, like every overlay surface: the service hands it the selected [tab], the
 * per-tab [controls], the media-row state, and the hold-to-end timing, and the panel draws them. It owns
 * no sampler, no preferences, no window; it draws with [OverlayPalette], not `MaterialTheme`, because it
 * is composed by a service over a game (see [PerformancePill]). The user's [accent] is used only for the
 * selected-tab underline, the media controls and the end-session ring — GameCore's own marks on its own
 * plate.
 *
 * Reachability (§5 "every control from the audit is reachable, nothing removed") is not left to chance:
 * the service builds [controls] keyed by [PanelTab] from the same [PanelReachability] map the reachability
 * test asserts against, so a control that exists in the audit but is missing from a tab shows up as a
 * failing test, not a silently dropped row.
 *
 * The footer is the press-and-hold "End session" (§5). This composable is given [endHoldProgress] (0f..1f,
 * the ring fill) and the press callbacks; the *timing* — when the hold confirms, the once-only fire — is
 * [HoldToConfirm] arithmetic the service runs against its clock, so the panel neither owns a timer nor can
 * double-fire. [onEndPressStart]/[onEndPressRelease] are the finger down/up; [onEndConfirmed] is wired by
 * the service to fire once on the hold's rising edge.
 *
 * @param tab the selected tab.
 * @param onTabSelected switch tabs.
 * @param gameLabel the running game's name, shown in the header.
 * @param clock the session clock, pre-formatted.
 * @param controls the controls to show, keyed by tab; the selected tab's list is drawn.
 * @param readings the pill's stats, shown as a dense strip above the tabs so the panel does not need the
 *   pill open; empty draws nothing.
 * @param quickApps the quick-launch row (Overlays tab); empty draws nothing.
 * @param onLaunchApp launch a quick-launch app.
 * @param mediaPlaying whether media is playing — the media row only takes space when true (§5).
 * @param mediaPreviousEnabled/mediaPlayPauseEnabled/mediaNextEnabled whether each transport button is
 *   actionable right now. Three flags, not one, because a session that allows only Next must not draw a
 *   tappable-but-dead Prev — the failure [MediaButton]'s own KDoc calls out.
 * @param mediaIsPlaying whether the current track is playing, so the play/pause glyph matches.
 * @param onMediaPrevious/onMediaPlayPause/onMediaNext media transport actions.
 * @param endHoldProgress the end-session ring fill, 0f..1f, from [HoldToConfirm.progressAt].
 * @param endEnabled whether there is a session to end; false dims the footer and blocks the hold.
 * @param endReason why there is no session to end, shown under the dimmed footer (§24: never a dead
 *   control with no explanation).
 * @param onEndPressStart/onEndPressRelease finger down / up on the End button.
 * @param onClose the header close X.
 * @param modifier applied to the panel's outer plate.
 * @param maxHeightDp the tallest the content column may be before it scrolls; 0 or less means unbounded.
 * @param accent the user's accent for highlights.
 */
@Composable
fun FullPanel(
    tab: PanelTab,
    onTabSelected: (PanelTab) -> Unit,
    gameLabel: String,
    clock: String,
    controls: Map<PanelTab, List<PanelControl>>,
    readings: List<StatReading>,
    quickApps: List<QuickApp>,
    onLaunchApp: (QuickApp) -> Unit,
    mediaPlaying: Boolean,
    mediaPreviousEnabled: Boolean,
    mediaPlayPauseEnabled: Boolean,
    mediaNextEnabled: Boolean,
    mediaIsPlaying: Boolean,
    onMediaPrevious: () -> Unit,
    onMediaPlayPause: () -> Unit,
    onMediaNext: () -> Unit,
    endHoldProgress: Float,
    endEnabled: Boolean,
    endReason: String?,
    onEndPressStart: () -> Unit,
    onEndPressRelease: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeightDp: Int = 0,
    accent: Color = OverlayPalette.Good,
) {
    Column(
        modifier = modifier
            .background(OverlayPalette.PanelPlate, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header, stats strip and tabs stay pinned; only the tab's controls scroll, so a tall Display tab
        // on a short screen does not scroll its own title away (§5, and the §7 "scrolls, not clips" rule).
        PanelHeader(gameLabel = gameLabel, clock = clock, onClose = onClose)
        if (readings.isNotEmpty()) {
            PanelStats(readings = readings)
        }
        TabRow(selected = tab, onSelected = onTabSelected, accent = accent)

        val scrollModifier = if (maxHeightDp > 0) {
            Modifier.heightIn(max = maxHeightDp.dp).verticalScroll(rememberScrollState())
        } else {
            Modifier
        }
        Column(
            modifier = scrollModifier,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            controls[tab].orEmpty().forEach { control ->
                ControlSlot(control = control)
            }

            // The quick-launch row sits under the Overlays controls, where the audit's overlay windows are.
            if (tab == PanelTab.OVERLAYS && quickApps.isNotEmpty()) {
                QuickApps(apps = quickApps, accent = accent, onLaunch = onLaunchApp)
            }

            // The media row lives under Session but only takes space when something is playing (§5).
            if (tab == PanelTab.SESSION && mediaPlaying) {
                MediaRow(
                    previousEnabled = mediaPreviousEnabled,
                    playPauseEnabled = mediaPlayPauseEnabled,
                    nextEnabled = mediaNextEnabled,
                    isPlaying = mediaIsPlaying,
                    onPrevious = onMediaPrevious,
                    onPlayPause = onMediaPlayPause,
                    onNext = onMediaNext,
                    accent = accent,
                )
            }

            // The footer end-session is only shown on Session, where the audit's End control lives.
            if (tab == PanelTab.SESSION) {
                EndSessionFooter(
                    progress = endHoldProgress,
                    enabled = endEnabled,
                    reason = endReason,
                    onPressStart = onEndPressStart,
                    onPressRelease = onEndPressRelease,
                    accent = accent,
                )
            }
        }
    }
}

/** Header: "GameCore" with the game name and clock, and a close X (spec §5). */
@Composable
private fun PanelHeader(gameLabel: String, clock: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = "GameCore",
                style = TextStyle(color = OverlayPalette.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            )
            BasicText(
                text = if (gameLabel.isBlank()) clock else "${gameLabel.trim()}  ·  $clock",
                maxLines = 1,
                style = TextStyle(color = OverlayPalette.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
            )
        }
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .background(OverlayPalette.Plate)
                .semantics { contentDescription = "Close panel" },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(text = "✕", style = TextStyle(color = OverlayPalette.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold))
        }
    }
}

/** The four tabs; the selected one is accent-underlined and bold — shape+weight, not colour alone. */
@Composable
private fun TabRow(selected: PanelTab, onSelected: (PanelTab) -> Unit, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PanelTab.entries.forEach { entry ->
            val isSelected = entry == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelected(entry) }
                    .padding(vertical = 8.dp)
                    .semantics {
                        contentDescription = entry.label
                        stateDescription = if (isSelected) "Selected" else "Not selected"
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BasicText(
                    text = entry.label,
                    style = TextStyle(
                        color = if (isSelected) OverlayPalette.Text else OverlayPalette.Muted,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                )
                // The underline is the non-colour cue: present under the selected tab, absent otherwise.
                Box(
                    modifier = Modifier
                        .height(2.dp)
                        .fillMaxWidth()
                        .background(if (isSelected) accent else Color.Transparent),
                )
            }
        }
    }
}

/**
 * Wraps one control with the shared §5 disabled/reason treatment: an enabled control draws its own slot;
 * a disabled one dims, still shows the control (nothing removed), and adds the reason as secondary text
 * with an info glyph, so the honest "why" is one tap of context rather than a vanished row.
 */
@Composable
private fun ControlSlot(control: PanelControl) {
    // The dim lives on the control itself (an OverlaySlider/OverlayToggle drawn with enabled = false),
    // not here, so a disabled control also *blocks input* rather than only fading — a dimmed slot whose
    // content still dragged would be the §32 "moves and does nothing" failure. This slot owns only the
    // reason sentence, which the control does not draw when its own reason is left null.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        control.content()
        if (!control.enabled && control.reason != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BasicText(text = "ⓘ", style = TextStyle(color = OverlayPalette.Muted, fontSize = 11.sp))
                BasicText(
                    text = control.reason,
                    style = TextStyle(color = OverlayPalette.Muted, fontSize = 11.sp),
                )
            }
        }
    }
}

/**
 * The media transport row (§5): previous / play-pause / next, each disabled independently.
 *
 * Three separate enables, not one shared flag: a session that reports it can skip forward but not back must
 * draw Prev as dead and Next as live, or the row is a button that depresses and does nothing — the exact
 * thing [MediaButton]'s own reasoning forbids. The play/pause glyph flips with [isPlaying] so the button
 * shows what the tap will do, not a static combined symbol.
 */
@Composable
private fun MediaRow(
    previousEnabled: Boolean,
    playPauseEnabled: Boolean,
    nextEnabled: Boolean,
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    accent: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OverlayPalette.Plate, RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaButton(glyph = "⏮", description = "Previous track", enabled = previousEnabled, accent = accent, onClick = onPrevious)
        MediaButton(
            glyph = if (isPlaying) "⏸" else "▶",
            description = if (isPlaying) "Pause" else "Play",
            enabled = playPauseEnabled,
            accent = accent,
            onClick = onPlayPause,
        )
        MediaButton(glyph = "⏭", description = "Next track", enabled = nextEnabled, accent = accent, onClick = onNext)
    }
}

@Composable
private fun MediaButton(glyph: String, description: String, enabled: Boolean, accent: Color, onClick: () -> Unit) {
    val tint = if (enabled) accent else OverlayPalette.Absent
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text = glyph, style = TextStyle(color = tint, fontSize = 18.sp))
    }
}

/**
 * The end-session footer (§5): a "Press and hold to end" button whose fill grows with [progress] (0f..1f),
 * the ring the [HoldToConfirm] state machine drives. The finger-down/up map to press start/release; the
 * confirm-once logic is the service's, so this only paints the progress and reports the press.
 */
@Composable
private fun EndSessionFooter(
    progress: Float,
    enabled: Boolean,
    reason: String?,
    onPressStart: () -> Unit,
    onPressRelease: () -> Unit,
    accent: Color,
) {
    val clamped = progress.coerceIn(0f, 1f)
    val stateWord = when {
        !enabled && reason != null -> reason
        clamped >= 1f -> "Ending session"
        else -> "Hold to end session"
    }
    Column(
        modifier = Modifier.alpha(if (enabled) 1f else 0.5f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(OverlayPalette.Plate)
                .border(1.dp, OverlayPalette.Danger, RoundedCornerShape(12.dp))
                // No session to end ⇒ no hold gesture, so the footer cannot be held to fire nothing.
                .then(if (enabled) Modifier.pressHold(onPressStart = onPressStart, onPressRelease = onPressRelease) else Modifier)
                .semantics {
                    contentDescription = "End session"
                    stateDescription = stateWord
                    if (!enabled) disabled()
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            // The progress fill: a Danger-tinted band growing left-to-right as the hold completes.
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .height(48.dp)
                    .background(OverlayPalette.Danger.copy(alpha = 0.35f)),
            )
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                BasicText(
                    text = "Press and hold to end session",
                    style = TextStyle(color = OverlayPalette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                )
            }
        }
        // §24: a dimmed control says why, in words, not by greying alone.
        if (!enabled && reason != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BasicText(text = "ⓘ", style = TextStyle(color = OverlayPalette.Muted, fontSize = 11.sp))
                BasicText(text = reason, style = TextStyle(color = OverlayPalette.Muted, fontSize = 11.sp))
            }
        }
    }
}

/** A press-and-hold gesture surface: fires [onPressStart] on finger-down, [onPressRelease] on up or cancel. */
@Composable
private fun Modifier.pressHold(onPressStart: () -> Unit, onPressRelease: () -> Unit): Modifier =
    this.pointerInput(Unit) {
        detectTapGestures(
            onPress = {
                onPressStart()
                // Suspends until the finger lifts or the gesture is cancelled; either way the hold ends.
                tryAwaitRelease()
                onPressRelease()
            },
        )
    }
