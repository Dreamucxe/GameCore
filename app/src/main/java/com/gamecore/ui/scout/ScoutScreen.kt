package com.gamecore.ui.scout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.model.AppSettings
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
import java.util.Locale

/**
 * §Scout's zoom pane: magnify the centre of the screen into a corner, and lift a dark scene so it reads.
 *
 * The magnifier's sibling, and this screen is built from the same parts as the loupe's controls on purpose.
 * There is no save button. The show/hide switch writes through as it is flicked, and the two sliders store
 * when they are released — because a zoom is judged by looking at it, and a user who drags the zoom up
 * expects the pane over their game to change, not to wait for a commit they were never shown.
 *
 * This is a leaf screen: it takes an [onBack] and nothing else, because Scout has nowhere further to
 * navigate. The one place a user might otherwise be sent — "display over other apps" — is named in the
 * note under the switch rather than linked, so the screen makes no promise it cannot keep from here.
 *
 * What Scout draws is honest screen-space work on the shared capture feed: a crop, a scale and an optional
 * brightness lift (§Scout). It reads no game state and adds nothing to aim (§24); the [SafetyCard] says so
 * where a user can read it.
 */
@Composable
fun ScoutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScoutViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // "Display over other apps" is granted on a screen belonging to Settings, and Android gives no callback
    // for coming back from it, so the permission is re-read every time this screen resumes.
    OnResume { viewModel.refreshPermission() }

    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Scout",
                subtitle = "A magnified view of the centre of the screen",
                onBack = onBack,
            )
        }
        item {
            ControlCard(
                state = state,
                onToggle = viewModel::setVisible,
                modifier = padded,
            )
        }
        item {
            SettingsCard(
                state = state,
                onZoom = viewModel::setZoom,
                onZoomCommit = viewModel::commitZoom,
                onLift = viewModel::setLift,
                onLiftCommit = viewModel::commitLift,
                modifier = padded,
            )
        }
        item { SafetyCard(modifier = padded) }
    }
}

/**
 * The switch that puts Scout on the glass, with a status chip and the one line that says why it is or is
 * not there.
 *
 * The chip reads "On"/"Off" from the request, not from a confirmed picture, because there is no report to
 * read — see [ScoutUiState]. The note under the switch carries the honesty that costs the chip its
 * certainty: Scout only paints while a live feed runs, and its switch is not remembered across a launch.
 */
@Composable
private fun ControlCard(
    state: ScoutUiState,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Scout",
        subtitle = "Zoom into the centre of the screen",
        modifier = modifier,
        action = {
            StatusChip(
                text = if (state.isScoutOn) "On" else "Off",
                tone = if (state.isScoutOn) Tone.Good else Tone.Muted,
            )
        },
    ) {
        SwitchRow(
            title = "Show Scout now",
            checked = state.isScoutOn,
            onCheckedChange = onToggle,
            description = "Draws a zoomed crop of the centre of the screen into a corner. It reads only " +
                "the shared screen feed, never the game.",
            enabled = state.canToggleOverlay,
        )
        ScoutNote(state = state)
    }
}

/**
 * The one line under the switch that says why Scout is not on screen, or what it is doing there.
 *
 * Ordered by what stops the next tap from working: no permission means nothing can appear at all, a profile
 * in charge means the switch is locked, and past both of those the note turns to the live feed — the thing
 * that decides whether an "on" Scout is actually painting, and the reason it does not survive a relaunch.
 */
@Composable
private fun ScoutNote(state: ScoutUiState) {
    SectionGap(6)
    when {
        !state.hasOverlayPermission -> NoteBanner(
            text = "GameCore cannot draw over other apps yet, so Scout has nowhere to appear. Grant " +
                "\"display over other apps\" in Settings and come back.",
            tone = Tone.Warning,
            icon = Icons.Filled.Info,
        )

        state.isDrivenByProfile -> NoteBanner(
            text = "${state.drivingGameLabel.ifBlank { "A game" }}'s profile is in charge of the overlays " +
                "while it is running. Scout's zoom and lift can still be set here.",
            tone = Tone.Accent,
            icon = Icons.Filled.Info,
        )

        state.isScoutOn -> NoteBanner(
            text = if (state.isServiceRunning) {
                "Scout is on. It magnifies the centre of the screen into a corner from the live screen " +
                    "feed, and appears only while that feed is running."
            } else {
                "Scout is switched on, but the overlay service is not up yet, so nothing is on screen. It " +
                    "appears once a live screen feed starts."
            },
            tone = if (state.isServiceRunning) Tone.Accent else Tone.Warning,
            icon = Icons.Filled.Info,
        )

        else -> NoteBanner(
            text = "Scout draws from the live screen feed, so it comes up only while one is running and is " +
                "not kept across a relaunch — it starts off each time you open GameCore.",
            tone = Tone.Muted,
            icon = Icons.Filled.Info,
        )
    }
}

/**
 * The zoom, and the dark-scene lift that rides on top of it.
 *
 * Two sliders, both committing on release like the crosshair's: the label under the thumb follows the
 * finger, the pane over the game catches up when the finger lifts, because the value is written to an
 * encrypted file and doing that per frame would be a write a frame. The zoom's label is a factor rather
 * than the raw tenths it is stored as — a user thinks in "×3", not "30" — and the lift reads "Off" at zero,
 * where Scout draws a faithful crop with no filter at all rather than an identity matrix that costs a pass.
 */
@Composable
private fun SettingsCard(
    state: ScoutUiState,
    onZoom: (Int) -> Unit,
    onZoomCommit: () -> Unit,
    onLift: (Int) -> Unit,
    onLiftCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Zoom and brightness", modifier = modifier) {
        SliderRow(
            title = "Zoom",
            value = state.zoomTenths,
            range = AppSettings.SCOUT_ZOOM_MIN_TENTHS..AppSettings.SCOUT_ZOOM_MAX_TENTHS,
            onValueChange = onZoom,
            valueLabel = zoomLabel(state.zoomTenths),
            description = "How far the centre crop is magnified, from ×2.0 to ×8.0. Higher reaches " +
                "further but shows a smaller slice of the screen.",
            onValueChangeFinished = onZoomCommit,
        )
        SliderRow(
            title = "Dark-scene lift",
            value = state.liftPercent,
            range = 0..AppSettings.SCOUT_LIFT_MAX,
            onValueChange = onLift,
            valueLabel = if (state.isLiftOn) "${state.liftPercent}%" else "Off",
            description = "Raises the brightness of the zoomed crop so a shadowed corner reads. At zero " +
                "the crop is drawn faithfully, with no lift at all.",
            onValueChangeFinished = onLiftCommit,
        )
    }
}

/**
 * What Scout is, in the app rather than only in the source.
 *
 * §24 draws a line between an overlay and an aim assist, and this is a screen where a user might wonder
 * which side of it they are on — a zoom that reaches across the map could be mistaken for something that
 * reads it. The answer belongs where they can read it: a crop of their own screen, scaled up in GameCore's
 * own window, that sees nothing the player cannot already see.
 */
@Composable
private fun SafetyCard(modifier: Modifier = Modifier) {
    PlainCard(modifier = modifier) {
        Text(text = "What this actually is", style = MaterialTheme.typography.titleSmall)
        SectionGap(6)
        Text(
            text = "A crop of the centre of the screen, scaled up and drawn in GameCore's own corner " +
                "window (§Scout). It works on the same shared screen feed the magnifier uses — a crop, a " +
                "scale and an optional brightness lift, all in screen space. It does not read the game, " +
                "does not find or follow another player, and adds nothing to your aim (§24). The zoom and " +
                "the lift are remembered; whether Scout is showing is not, because it needs a live feed " +
                "that a fresh launch does not have.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The stored tenths as the factor the copy shows: 25 becomes "×2.5". US locale so the point is a point. */
private fun zoomLabel(tenths: Int): String = "×%.1f".format(Locale.US, tenths / 10f)


