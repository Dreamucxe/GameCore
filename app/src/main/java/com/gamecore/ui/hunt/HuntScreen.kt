package com.gamecore.ui.hunt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.model.HuntFilter
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SectionGap
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone

/**
 * §Hunt's hunting filter: pick a full-screen colour grade, and switch it on over the game.
 *
 * A leaf screen, so it takes no navigation: the one thing it might send the user elsewhere for — the
 * permission to draw over other apps — is stated in a note rather than handed a button, because there is no
 * preset to build and nowhere else on this screen to go. Everything writes through, as on the crosshair and
 * colour screens: the master switch stores its flag and asks for the grade in the same tap, and a grade is
 * stored as it is picked and swapped over the game at once when the filter is already on.
 *
 * The switch shows the request, not a report. The overlay status was never given a Hunt field, so "on"
 * here is read from what GameCore is asking [com.gamecore.domain.overlay.OverlayController] to draw — which
 * is also why a capture grade left on across a restart shows as off until it is turned on again, the honest
 * consequence of a screen feed whose consent does not survive the process.
 */
@Composable
fun HuntScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HuntViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // "Display over other apps" is granted on a screen belonging to Settings, and Android gives no
    // callback for coming back from it.
    OnResume { viewModel.refreshPermission() }

    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Hunting filter",
                subtitle = state.filter.label,
                onBack = onBack,
            )
        }
        state.message?.let { message ->
            item {
                NoteBanner(
                    text = message,
                    tone = Tone.Warning,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                    action = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
                )
            }
        }
        if (!state.isLoaded) {
            item { LoadingCard(modifier = padded) }
            return@LazyColumn
        }
        item {
            FilterCard(
                state = state,
                onToggle = viewModel::setEnabled,
                modifier = padded,
            )
        }
        item {
            GradesCard(
                state = state,
                onSelect = viewModel::select,
                modifier = padded,
            )
        }
        item { SafetyCard(modifier = padded) }
    }
}

/**
 * The master switch, and the one line that says why the grade is or is not reaching the game.
 *
 * The switch shows the request, not a report: the overlay status has no Hunt field, so "on" here means
 * GameCore is asking for the grade. It is locked without the overlay permission and while a game's profile
 * owns the overlays, for the same reason the crosshair's is — a switch that flicked back on its own with no
 * explanation is worse than one that says why it did not move.
 */
@Composable
private fun FilterCard(
    state: HuntUiState,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Hunting filter",
        subtitle = "A full-screen grade that separates targets from the scene",
        modifier = modifier,
        action = {
            StatusChip(
                text = if (state.isOn) "On screen" else "Off",
                tone = if (state.isOn) Tone.Good else Tone.Muted,
            )
        },
    ) {
        SwitchRow(
            title = "Grade the screen",
            checked = state.isOn,
            onCheckedChange = onToggle,
            description = "Lays the chosen grade over everything on screen. It reads only the pixels that " +
                "are already there, never the game.",
            enabled = state.canToggle,
        )
        HuntNote(state = state)
    }
}
/**
 * The one line under the switch that says why the grade is not on screen, or what it is doing there.
 *
 * Ordered by what stops the next tap from working: no permission means nothing can appear at all, a profile
 * in charge means the switch is locked, and past those the note discloses what an on grade is actually
 * doing — a capture grade re-grades the screen GameCore captures and Android shows its own banner, while
 * the warm grade tints GameCore's own glass and needs no capture at all.
 */
@Composable
private fun HuntNote(state: HuntUiState) {
    SectionGap(6)
    when {
        !state.hasOverlayPermission -> NoteBanner(
            text = "GameCore cannot draw over other apps yet, so no grade will appear over a game.",
            tone = Tone.Warning,
            icon = Icons.Filled.Info,
        )

        state.isDrivenByProfile -> NoteBanner(
            text = "${state.drivingGameLabel.ifBlank { "A game" }}'s profile is in charge of the overlays " +
                "while it is running. The grade can still be chosen below.",
            tone = Tone.Accent,
            icon = Icons.Filled.Info,
        )

        state.isOn && state.gradeNeedsCapture -> NoteBanner(
            text = "This grade re-grades the screen GameCore captures, so Android shows a screen-capture " +
                "banner while it is on. It uses the same feed as the magnifier and Scout, and reads " +
                "nothing about the game.",
            tone = Tone.Accent,
            icon = Icons.Filled.Info,
        )

        state.isOn -> NoteBanner(
            text = "The warm grade is drawn over GameCore's own glass, so the game shows through and no " +
                "screen capture is needed.",
            tone = Tone.Accent,
            icon = Icons.Filled.Info,
        )

        else -> Unit
    }
}
/**
 * Every grade, with the chosen one ticked and the ones that need screen capture badged.
 *
 * A grade can be picked whether or not the filter is on — it is a stored preference — and picking one while
 * the filter is on swaps the look over the game as the row is tapped. The badge is the honest half of
 * §Hunt's split: a badged grade re-grades the captured screen and comes with a capture banner, an unbadged
 * one tints GameCore's own window and does not.
 */
@Composable
private fun GradesCard(
    state: HuntUiState,
    onSelect: (HuntFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Grade",
        subtitle = "How the screen is graded when the filter is on",
        modifier = modifier,
    ) {
        HuntFilter.entries.forEach { grade ->
            GradeRow(
                grade = grade,
                selected = grade == state.filter,
                onSelect = onSelect,
            )
        }
    }
}

/**
 * One grade: its name, what it does, whether it needs capture, and whether it is the chosen one.
 *
 * Built rather than reusing [com.gamecore.ui.components.NavRow] because a grade carries two marks at once —
 * the capture badge and the selection tick — and a row with one trailing slot cannot show both. The tick's
 * width is reserved on every row so the labels do not shift as the choice moves.
 */
@Composable
private fun GradeRow(
    grade: HuntFilter,
    selected: Boolean,
    onSelect: (HuntFilter) -> Unit,
) {
    Surface(
        onClick = { onSelect(grade) },
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = grade.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = grade.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (grade.needsCapture) {
                Spacer(modifier = Modifier.width(8.dp))
                StatusChip(text = "Screen capture", tone = Tone.Muted)
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Spacer(modifier = Modifier.size(20.dp))
            }
        }
    }
}
/**
 * What the hunting filter is, in the app rather than only in the source.
 *
 * §24 draws a line between grading the glass and reading the game, and this is the screen where a user
 * might wonder which side of it they are on. Every grade is a function of the pixels already on screen; the
 * capture grades re-paint the screen GameCore captures with the user's own consent, which Android announces
 * for the whole session, and none of them read another app, another player, or anything the game does not
 * already draw.
 */
@Composable
private fun SafetyCard(modifier: Modifier = Modifier) {
    PlainCard(modifier = modifier) {
        Text(text = "What this actually is", style = MaterialTheme.typography.titleSmall)
        SectionGap(6)
        Text(
            text = "A colour grade laid over the screen. The warm \"Movie\" grade tints GameCore's own " +
                "window, so the game shows straight through and nothing is captured. The other three " +
                "re-grade the screen GameCore captures with your consent — the same feed the magnifier " +
                "uses — and Android shows a capture banner the whole time. Every grade works from the " +
                "pixels already on the glass: none reads the game, another app, or where any player is.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadingCard(modifier: Modifier = Modifier) {
    PlainCard(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
    }
}
