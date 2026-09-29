package com.gamecore.ui.wheels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.model.AppSettings
import com.gamecore.core.overlay.OverlayPalette
import com.gamecore.core.overlay.WheelOverlay
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SectionGap
import com.gamecore.ui.components.SliderRow
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.screenAspectRatio

/**
 * §Wheels' high-sensitivity wheels: a feel-only guide ring drawn over the game, and the genuine sensitivity
 * remap that guide only hints at, which runs where GameCore is actually allowed to change the feel.
 *
 * The screen is built around that split, because the split is the honest thing about the feature. The guide
 * is a ring in GameCore's own window — a sticker on the glass that reads nothing, takes no touch, and cannot
 * re-scale another game's stick because the platform offers no way to inject touches into a process GameCore
 * does not own. The ring's job is to show the throw a player is working with. The remap that makes a stick
 * genuinely faster lives inside GameCore's own Aim Lab training surface, where there is a real stick to
 * re-scale and no other app to reach into; this screen edits the one preference both the ring's placement
 * and the surface's gain read, and the copy says plainly which half does what.
 *
 * There is no save button, exactly as on the crosshair screen: the switch writes through the moment it
 * flips, and each slider stores once when the finger lifts. The preview is the real renderer —
 * [WheelOverlay], the same composable the overlay window hosts, in a box shaped like this device's screen —
 * so the ring drawn here is the ring that will be drawn there, at the size the slider is set to.
 *
 * A leaf screen: it takes only [onBack]. The one place it would want to send the user — "display over other
 * apps", when the permission is missing — is reachable from Settings, so the note names it rather than
 * offering a jump this screen has no navigator for.
 */
@Composable
fun WheelsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WheelsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // "Display over other apps" is granted on a screen belonging to Settings, and Android gives no callback
    // for coming back from it, so the permission is re-read whenever this screen resumes.
    OnResume { viewModel.refreshPermission() }

    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "High-sensitivity wheels",
                subtitle = "A feel guide on the glass, and the real remap in Aim Lab",
                onBack = onBack,
            )
        }
        item { PreviewCard(state = state, modifier = padded) }
        item {
            GuideCard(
                state = state,
                onToggle = viewModel::setGuideEnabled,
                onRadius = viewModel::setRadius,
                onRadiusCommitted = viewModel::commitRadius,
                modifier = padded,
            )
        }
        item {
            SensitivityCard(
                state = state,
                onSensitivity = viewModel::setSensitivity,
                onSensitivityCommitted = viewModel::commitSensitivity,
                modifier = padded,
            )
        }
        item { SafetyCard(modifier = padded) }
    }
}

/**
 * The ring, drawn by the renderer that will draw it over the game, in a box shaped like the screen.
 *
 * The preview uses [WheelOverlay] directly — the overlay window's own composable — so the resting place and
 * the size read here are exactly what a game will get, scaled to this device's aspect ratio. There is
 * nothing to place: the ring sits where a left thumb rests, low and to the left, and only its size is the
 * user's to set, so unlike the crosshair preview there is no drag target here, only a picture that follows
 * the size slider below.
 */
@Composable
private fun PreviewCard(state: WheelsUiState, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Preview",
        subtitle = "Where the ring sits, at the size you set",
        modifier = modifier,
        action = {
            StatusChip(
                text = if (state.isGuideOn) "On screen" else "Off",
                tone = if (state.isGuideOn) Tone.Good else Tone.Muted,
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(screenAspectRatio())
                .clip(RoundedCornerShape(12.dp))
                .background(OverlayPalette.PanelPlate)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        ) {
            WheelOverlay(radiusPercent = state.guideRadiusPercent)
        }
        PreviewNote(state = state)
    }
}

/**
 * The one line under the preview that says why the ring is not on screen, or what it is doing there.
 *
 * Ordered by what stops the ring from appearing: no permission means nothing can be drawn over another app
 * at all, a profile in charge means the toggle is locked to the game in front, and neither of those being
 * true is the point at which it is worth saying that the size slider is landing on the live ring. A leaf
 * screen, so the permission note names where the grant lives rather than offering a jump.
 */
@Composable
private fun PreviewNote(state: WheelsUiState) {
    SectionGap(6)
    when {
        !state.hasOverlayPermission -> NoteBanner(
            text = "GameCore cannot draw over other apps yet, so the guide ring will not appear over a " +
                "game. Grant \"display over other apps\" in Settings to switch it on.",
            tone = Tone.Warning,
            icon = Icons.Filled.Info,
        )

        state.isDrivenByProfile -> NoteBanner(
            text = "${state.drivingGameLabel.ifBlank { "A game" }}'s profile is in charge of the overlays " +
                "while it is running. The ring's size can still be adjusted.",
            tone = Tone.Accent,
            icon = Icons.Filled.Info,
        )

        state.isGuideOn -> NoteBanner(
            text = "The guide is on screen. Its size changes over your game as you adjust it — the slider " +
                "when you let go of it.",
            tone = Tone.Accent,
            icon = Icons.Filled.Info,
        )

        else -> Unit
    }
}

/**
 * The guide's on/off and its size — the overlay half of the feature.
 *
 * The switch is locked, not hidden, while the permission is missing or a profile is driving the overlays,
 * the crosshair screen's rule: the honest answer is that nothing draws over other apps without the
 * permission and the game in front is in charge until it stops, and a switch that flicks itself back off
 * with no explanation is worse than one that will not move. The size slider stays live either way, so the
 * ring can be sized against the preview before it is ever switched on. Radius is a percent of the shorter
 * screen edge (§Wheels), so the ring keeps its size across displays; the slider commits on release, exactly
 * like the crosshair's sliders, to keep an encrypted write off every pointer frame.
 */
@Composable
private fun GuideCard(
    state: WheelsUiState,
    onToggle: (Boolean) -> Unit,
    onRadius: (Int) -> Unit,
    onRadiusCommitted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Guide ring", modifier = modifier) {
        SwitchRow(
            title = "Show the guide ring",
            checked = state.isGuideOn,
            onCheckedChange = onToggle,
            description = "A ring over the stick's resting place, drawn in GameCore's own untouchable " +
                "window. It reads nothing and takes no touch — a sticker on the glass.",
            enabled = state.canToggleGuide,
        )
        SliderRow(
            title = "Ring size",
            value = state.guideRadiusPercent,
            range = AppSettings.WHEEL_RADIUS_MIN..AppSettings.WHEEL_RADIUS_MAX,
            onValueChange = onRadius,
            valueLabel = "${state.guideRadiusPercent}%",
            description = "As a share of the shorter screen edge, so the ring is the same size on any " +
                "display.",
            onValueChangeFinished = onRadiusCommitted,
        )
    }
}

/**
 * The sensitivity multiplier — the honest half of the feature, applied where GameCore is allowed to.
 *
 * This gain does not touch the guide ring and does not touch another app. It is read by
 * [com.gamecore.aimlab.engine.WheelDisplacementMath] inside GameCore's own Aim Lab training surface, where a
 * real on-screen stick can be re-scaled, and this screen and that surface share the one preference — raising
 * the multiplier here raises it there. Shown as a multiplier because that is what it means to the hand:
 * x1.00 is neutral, and the range runs x0.50–x3.00. Commits on release like every other slider in the app.
 */
@Composable
private fun SensitivityCard(
    state: WheelsUiState,
    onSensitivity: (Int) -> Unit,
    onSensitivityCommitted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Aim Lab sensitivity", modifier = modifier) {
        SliderRow(
            title = "Wheel multiplier",
            value = state.sensitivityPercent,
            range = AppSettings.WHEEL_SENSITIVITY_MIN..AppSettings.WHEEL_SENSITIVITY_MAX,
            onValueChange = onSensitivity,
            valueLabel = multiplierLabel(state.sensitivityPercent),
            description = "Applied to the stick inside GameCore's Aim Lab training, where there is a real " +
                "stick to re-scale. x1.00 leaves the feel unchanged; higher turns faster for the same throw.",
            onValueChangeFinished = onSensitivityCommitted,
        )
    }
}

/**
 * What this actually is, in the app rather than only in the source.
 *
 * §24 draws a line between an overlay and an aim assist, and the wheels feature is the one place a user might
 * reasonably wonder which side of it they are on, because half of it genuinely changes the feel. The answer
 * belongs where they can read it: the ring is a shape in GameCore's own window that cannot see or touch the
 * game, and the remap runs only inside GameCore's own training arena, never in another app.
 */
@Composable
private fun SafetyCard(modifier: Modifier = Modifier) {
    PlainCard(modifier = modifier) {
        Text(text = "What this actually is", style = MaterialTheme.typography.titleSmall)
        SectionGap(6)
        Text(
            text = "Two things. The ring is a shape drawn in GameCore's own window, on top of whatever is " +
                "underneath: it does not read the game, does not move on its own, and cannot be tapped — " +
                "touches pass straight through to the app below. It cannot re-scale another game's stick, " +
                "because nothing outside a game is allowed to inject touches into it. The multiplier is the " +
                "real remap, and it runs only inside GameCore's own Aim Lab, on a stick GameCore draws " +
                "itself. Neither half reaches into a game you are playing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The sensitivity percent as the multiplier the hand feels: 150 becomes "x1.50", 50 "x0.50", 300 "x3.00".
 *
 * Built from integer arithmetic rather than a locale-formatted float, so the decimal point is a point on
 * every device and the label never drifts to a comma in a locale that would format it that way.
 */
private fun multiplierLabel(percent: Int): String {
    val whole = percent / 100
    val hundredths = percent % 100
    return "x$whole.${hundredths.toString().padStart(2, '0')}"
}
