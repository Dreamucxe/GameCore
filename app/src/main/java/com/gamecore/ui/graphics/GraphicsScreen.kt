package com.gamecore.ui.graphics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.Tone

/**
 * The Graphics screen: two device-level levers that lighten what the system draws — a lower render
 * resolution and a ceiling on the display's refresh rate — and a plain statement of what neither one can do.
 *
 * Both halves are read back from the device after every change, so what a chip shows selected is what the
 * device reports, not what was requested. When the elevated shell is absent the controls are disabled and
 * one banner says why, because a chip that looks tappable and silently does nothing is the one outcome this
 * screen must not produce.
 *
 * The bottom card is the honest boundary the user asked about: GameCore changes the display, never a game's
 * textures, filtering or shaders. Those live inside the game's own renderer, and no overlay app can reach
 * them.
 */
@Composable
fun GraphicsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GraphicsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val padded = Modifier.padding(horizontal = ScreenPadding)
    val controlsEnabled = state.shellAvailable && !state.isApplying

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Graphics",
                subtitle = "Trade pixels and frame ceiling for headroom",
                onBack = onBack,
                action = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.isLoading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Re-read the device")
                    }
                },
            )
        }

        state.message?.let { message ->
            item {
                NoteBanner(
                    text = message,
                    tone = Tone.Neutral,
                    modifier = padded,
                    action = {
                        Text(
                            text = "Dismiss",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .clickable(onClick = viewModel::dismissMessage)
                                .padding(start = 12.dp),
                        )
                    },
                )
            }
        }

        val shellReason = state.shellReason
        if (!state.shellAvailable && shellReason != null) {
            item {
                NoteBanner(
                    text = shellReason,
                    tone = Tone.Warning,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                )
            }
        }

        item {
            ResolutionCard(
                state = state,
                enabled = controlsEnabled,
                onSelect = viewModel::setResolution,
                modifier = padded,
            )
        }

        item {
            FrameCapCard(
                state = state,
                enabled = controlsEnabled,
                onSelect = viewModel::setFpsCap,
                modifier = padded,
            )
        }

        item {
            NoteBanner(
                text = "GameCore changes what the display draws, not what a game renders inside itself. " +
                    "A game's textures, filtering and shaders live in its own renderer and cannot be " +
                    "replaced or sharpened from outside it — this screen does not pretend to.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
                modifier = padded,
            )
        }
    }
}

/**
 * The render-resolution presets. Each chip is a percentage of the panel's own size; the line beneath names
 * the concrete pixels, read from the panel rather than typed.
 */
@Composable
private fun ResolutionCard(
    state: GraphicsUiState,
    enabled: Boolean,
    onSelect: (com.gamecore.core.model.ResolutionScale) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Render resolution",
        subtitle = state.nativeSizeLabel?.let { "Panel: $it" },
        modifier = modifier,
    ) {
        if (state.sizeUnreadable) {
            Text(
                text = "The display's own size could not be read, so the presets cannot be computed. " +
                    "Connect the elevated shell and refresh.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        val offered = state.resolutionChoices.map { it.scale }
        ChoiceRow(
            options = offered,
            selected = state.activeScale,
            onSelect = onSelect,
            label = { it.label },
            enabled = enabled,
            perRow = 3,
        )
        val active = state.resolutionChoices.firstOrNull { it.scale == state.activeScale }
        Text(
            text = when {
                active != null && active.scale.percent >= 100 ->
                    "${active.detail} — the panel's own resolution, with no override left behind."
                active != null -> "${active.detail} — ${active.scale.summary}"
                state.activeScale == null && state.resolutionChoices.isNotEmpty() ->
                    "The display is at a size GameCore did not set. Choosing a preset replaces it."
                else -> "No size is currently overridden."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The frame-rate cap: a ceiling on display refresh, with "Off" releasing it.
 *
 * The options are the rates phones commonly carry. A panel that has no mode at the chosen rate picks the
 * nearest one it does have — the platform owns that choice — so the copy says "cap", not "exactly".
 */
@Composable
private fun FrameCapCard(
    state: GraphicsUiState,
    enabled: Boolean,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Frame-rate cap",
        subtitle = "A ceiling on how high the display may refresh",
        modifier = modifier,
    ) {
        ChoiceRow<Int?>(
            options = CAP_OPTIONS,
            selected = state.fpsCap?.takeIf { it in CAP_OPTIONS.filterNotNull() },
            onSelect = onSelect,
            label = { hz -> hz?.let { "$it Hz" } ?: "Off" },
            enabled = enabled,
            perRow = 4,
        )
        Text(
            text = when (val cap = state.fpsCap) {
                null -> "No cap set. The display may run to its own peak."
                else -> "Currently capped at $cap Hz. The display picks its nearest supported mode at or " +
                    "below the cap."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Offered caps, "Off" first. A device clamps to a mode it actually has. */
private val CAP_OPTIONS: List<Int?> = listOf(null, 60, 90, 120)
