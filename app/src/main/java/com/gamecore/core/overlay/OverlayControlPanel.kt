package com.gamecore.core.overlay

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.ScreenLockRotation
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamecore.domain.monitoring.StatReading
import kotlin.math.roundToInt

/**
 * What the floating button expands into: §7's control panel and §16's gaming tools, over the game.
 *
 * The panel is the one overlay window besides the button that takes touches, and it is the only one that
 * closes itself. `FLAG_WATCH_OUTSIDE_TOUCH` on its window delivers `ACTION_OUTSIDE` here when the user
 * taps the game behind it, which is handled as a dismissal — without that the panel would stay open over
 * whatever the player was doing until they found the button again, and a modal overlay over a live game
 * is the worst kind of dead zone.
 *
 * Every action is drawn from [OverlayAction], so an action the service knows about and the panel forgot
 * cannot exist. An action that is [OverlayPanelState.isUsable] false renders greyed *with the reason
 * under it* rather than silently doing nothing when tapped — flashlight on a device with no flash unit,
 * Do Not Disturb without notification-policy access, recording before consent. That is §24's "say it is
 * not supported" applied to the surface where the user is most likely to try. The two [OverlayLevel]
 * sliders follow the same rule.
 *
 * [maxHeightDp] is the space the service found between the floating button and the edge of the screen.
 * The panel opens directly against the button, so how much room it has depends on where the user parked
 * it; capping and scrolling here means a button dragged near the bottom of a short screen gets a panel
 * that is shorter, not one whose last row of buttons is off-screen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OverlayControlPanel(
    state: OverlayPanelState,
    readings: List<StatReading>,
    accent: Color,
    maxHeightDp: Int,
    onAction: (OverlayAction) -> Unit,
    onDragLevel: (OverlayLevel, Int) -> Unit,
    onCommitLevel: (OverlayLevel) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(PANEL_WIDTH_DP.dp)
            .heightIn(max = maxHeightDp.coerceAtLeast(MIN_PANEL_HEIGHT_DP).dp)
            .background(OverlayPalette.PanelPlate, RoundedCornerShape(18.dp))
            // Inside the plate and outside the padding, so the padding scrolls with the content: a panel
            // that has been cut short still ends in a margin rather than in a clipped row.
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .pointerInteropFilter { event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    onDismiss()
                    true
                } else {
                    // Touches inside the panel belong to the buttons. Returning false lets Compose's
                    // own gesture handling see them, which is what makes `clickable` work at all.
                    false
                }
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PanelHeader(state = state, accent = accent)
        if (readings.isNotEmpty()) {
            PanelStats(readings = readings)
        }
        PanelLevels(
            state = state,
            accent = accent,
            onDragLevel = onDragLevel,
            onCommitLevel = onCommitLevel,
        )
        // Chunked by hand rather than with `FlowRow`, which is still experimental: a fixed four-per-row
        // grid is what the panel wants anyway, and the width is fixed so nothing has to reflow.
        OverlayAction.entries.chunked(ACTIONS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { action ->
                    ActionButton(
                        action = action,
                        state = state,
                        accent = accent,
                        onAction = onAction,
                        modifier = Modifier.width(ACTION_WIDTH_DP.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(state: OverlayPanelState, accent: Color) {
    Column(modifier = Modifier.width(HEADER_TEXT_WIDTH_DP.dp)) {
        BasicText(
            text = state.gameLabel.ifEmpty { "GameCore" },
            maxLines = 1,
            style = TextStyle(
                color = OverlayPalette.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        BasicText(
            // No session is a state worth naming rather than a blank: the panel can be opened from the
            // home screen, and "Not tracking" tells the user why the timer is missing.
            text = state.sessionElapsed?.let { "Session $it" } ?: "Not tracking",
            style = TextStyle(
                color = if (state.sessionElapsed != null) accent else OverlayPalette.Muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}

/** The same readings the pill shows, in a single dense row, so the panel does not need the pill open. */
@Composable
private fun PanelStats(readings: List<StatReading>) {
    Row(
        modifier = Modifier
            .background(OverlayPalette.Plate, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        readings.take(PANEL_STATS).forEach { reading ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(
                    text = reading.display(withUnit = false),
                    style = TextStyle(
                        color = if (reading.isAvailable) OverlayPalette.Text else OverlayPalette.Absent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                BasicText(
                    text = reading.stat.shortLabel,
                    style = TextStyle(color = OverlayPalette.Muted, fontSize = 9.sp),
                )
            }
        }
    }
}

/**
 * The volume and brightness sliders of §16 — the two adjustments a player makes without leaving a game.
 *
 * A Compose slider is stateless: it draws where its `value` says and reports where the finger went. The
 * finger's position therefore has to live somewhere, and it lives in the service's [OverlayPanelState]
 * rather than in a `remember` here — the same arrangement the in-app sliders use with their ViewModel.
 * That is not only for consistency. Held locally, the panel would show the requested level while the
 * service knew the real one, and when the device rounds a request — a media stream has about fifteen
 * steps, so most percentages are not reachable — the two disagree with no event to reconcile them: the
 * thumb would sit at 43% on a device that went to 40% and stay there for as long as the panel is open.
 * With one number in one place, the read-back after the write corrects the thumb.
 *
 * [onDragLevel] moves the thumb and writes nothing; [onCommitLevel] is the write, once, when the finger
 * lifts. Writing per drag frame would mean sixty `settings put` calls a second, each one possibly
 * through the elevated shell.
 */
@Composable
private fun PanelLevels(
    state: OverlayPanelState,
    accent: Color,
    onDragLevel: (OverlayLevel, Int) -> Unit,
    onCommitLevel: (OverlayLevel) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OverlayPalette.Plate, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        OverlayLevel.entries.forEach { level ->
            LevelRow(
                level = level,
                levelState = state.levelFor(level),
                accent = accent,
                onDragLevel = onDragLevel,
                onCommitLevel = onCommitLevel,
            )
        }
    }
}

/**
 * One slider.
 *
 * Unreadable and unwritable are different failures, and they are drawn differently. A level that could
 * not be read at all shows "--" where the percentage goes, because there is no honest number to put
 * there. A level that reads but cannot be changed shows the number and dims the track, with the reason
 * under it — brightness on a device where GameCore holds neither WRITE_SETTINGS nor a live Shizuku
 * connection is the ordinary case of that, and a slider that moves and changes nothing is exactly what
 * §32 rules out.
 */
@Composable
private fun LevelRow(
    level: OverlayLevel,
    levelState: OverlayLevelState,
    accent: Color,
    onDragLevel: (OverlayLevel, Int) -> Unit,
    onCommitLevel: (OverlayLevel) -> Unit,
) {
    val usable = levelState.isUsable
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = level.icon(),
                contentDescription = level.label,
                tint = if (usable) OverlayPalette.Text else OverlayPalette.Absent,
                modifier = Modifier.size(16.dp),
            )
            Slider(
                value = (levelState.percent ?: 0).toFloat(),
                onValueChange = { onDragLevel(level, it.roundToInt().coerceIn(0, LEVEL_MAX)) },
                modifier = Modifier.weight(1f),
                enabled = usable,
                valueRange = 0f..LEVEL_MAX.toFloat(),
                onValueChangeFinished = { onCommitLevel(level) },
                // Explicit colours because no `MaterialTheme` wraps an overlay window — see
                // [OverlayViewHost]. Left to its defaults the slider would draw itself in the light
                // scheme's purple on a near-black plate.
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = OverlayPalette.Divider,
                    disabledThumbColor = OverlayPalette.Absent,
                    disabledActiveTrackColor = OverlayPalette.Absent,
                    disabledInactiveTrackColor = OverlayPalette.Divider,
                ),
            )
            BasicText(
                text = levelState.percent?.let { "$it%" } ?: "--",
                maxLines = 1,
                style = TextStyle(
                    color = if (usable) OverlayPalette.Text else OverlayPalette.Absent,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
                ),
                // Fixed width so the slider does not shrink by a few pixels as the readout crosses from
                // two digits to three, which reads as the thumb drifting under a still finger.
                modifier = Modifier.width(LEVEL_READOUT_WIDTH_DP.dp),
            )
        }
        levelState.reason?.let { reason ->
            BasicText(
                text = reason,
                maxLines = 2,
                style = TextStyle(color = OverlayPalette.Absent, fontSize = 8.5.sp),
                modifier = Modifier.padding(start = 22.dp, bottom = 2.dp),
            )
        }
    }
}

/**
 * The glyph per level. Here rather than on the enum for the same reason [OverlayAction.icon] is.
 *
 * The auto-mirrored volume icon, not the plain one: the plain `Icons.Rounded.VolumeUp` is deprecated
 * precisely because a speaker with its waves on the right is wrong in an RTL layout.
 */
private fun OverlayLevel.icon(): ImageVector = when (this) {
    OverlayLevel.VOLUME -> Icons.AutoMirrored.Rounded.VolumeUp
    OverlayLevel.BRIGHTNESS -> Icons.Rounded.BrightnessMedium
}

/**
 * One action.
 *
 * The three visual states are on, off, and unavailable, and they are distinguishable without reading:
 * an active toggle is tinted with the accent on a filled plate, an inactive one is plain, and an
 * unavailable one is dimmed with a struck-through icon. The reason is drawn under the label at a smaller
 * size — it is the answer to "why is this greyed out", and the panel is where the question gets asked.
 */
@Composable
private fun ActionButton(
    action: OverlayAction,
    state: OverlayPanelState,
    accent: Color,
    onAction: (OverlayAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val usable = state.isUsable(action)
    val active = usable && action.isToggle && state.isActive(action)
    val tint = when {
        !usable -> OverlayPalette.Absent
        active -> accent
        action == OverlayAction.STOP_SESSION -> OverlayPalette.Danger
        else -> OverlayPalette.Text
    }
    Column(
        modifier = modifier
            .background(
                color = if (active) accent.copy(alpha = ACTIVE_PLATE_ALPHA) else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            // Unusable actions are not clickable at all rather than clickable-and-ignored: a button that
            // depresses and does nothing reads as broken, while one that does not respond reads as off.
            .clickable(enabled = usable) { onAction(action) }
            .padding(vertical = 7.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = action.icon(),
                contentDescription = action.label,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            if (!usable) {
                Icon(
                    imageVector = Icons.Rounded.Block,
                    contentDescription = null,
                    tint = OverlayPalette.Absent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        BasicText(
            text = action.label,
            maxLines = 1,
            style = TextStyle(
                color = tint,
                fontSize = 9.5.sp,
                textAlign = TextAlign.Center,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
        state.reasonFor(action)?.let { reason ->
            BasicText(
                text = reason,
                maxLines = 2,
                style = TextStyle(
                    color = OverlayPalette.Absent,
                    fontSize = 8.sp,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

/**
 * The glyph per action.
 *
 * Here rather than on the enum so [OverlayAction] stays free of Compose types — the service executes
 * these and has no business importing an `ImageVector`.
 */
private fun OverlayAction.icon(): ImageVector = when (this) {
    OverlayAction.PILL -> Icons.Rounded.Speed
    OverlayAction.CROSSHAIR -> Icons.Rounded.GpsFixed
    OverlayAction.HUD -> Icons.Rounded.Dashboard
    OverlayAction.SCREENSHOT -> Icons.Rounded.PhotoCamera
    OverlayAction.RECORD -> Icons.Rounded.FiberManualRecord
    OverlayAction.FLASHLIGHT -> Icons.Rounded.FlashlightOn
    OverlayAction.DO_NOT_DISTURB -> Icons.Rounded.DoNotDisturbOn
    OverlayAction.ROTATION_LOCK -> Icons.Rounded.ScreenLockRotation
    OverlayAction.STOP_SESSION -> Icons.Rounded.StopCircle
    OverlayAction.OPEN_APP -> Icons.AutoMirrored.Rounded.OpenInNew
}

/**
 * The panel's fixed width, which the service needs as well.
 *
 * Internal rather than private because the window has to be positioned before its content is measured:
 * the panel is anchored to the same screen edge as the button that opened it, and the x for that is
 * `screenWidth - width`. A fixed width is what makes that exact — the alternative is adding the window
 * at x = 0 and moving it after the first layout, which is a visible jump.
 */
internal const val PANEL_WIDTH_DP = 268

private const val ACTIONS_PER_ROW = 4

/**
 * The floor on the height the service is allowed to hand down.
 *
 * A button parked a few pixels from the bottom of the screen leaves almost no room below it, and the
 * service opens the panel on the side with more room — but on a short screen in landscape both sides can
 * be thin. Rather than draw a panel two rows tall, the cap is never taken below this, and the panel is
 * allowed to overhang: an overlay window is not clipped to the space under the button, only positioned
 * from it.
 */
private const val MIN_PANEL_HEIGHT_DP = 220

private const val LEVEL_MAX = 100

/** Room for "100%" in monospace at 10.sp. */
private const val LEVEL_READOUT_WIDTH_DP = 30

/** Four buttons, their gaps and the panel's padding, inside [PANEL_WIDTH_DP]. */
private const val ACTION_WIDTH_DP = 57

private const val HEADER_TEXT_WIDTH_DP = 240

/** How many of the pill's stats fit across the panel without shrinking the type. */
private const val PANEL_STATS = 5

private const val ACTIVE_PLATE_ALPHA = 0.18f
