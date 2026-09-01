package com.gamecore.ui.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.NavRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour

/**
 * The overlay hub: HUD layouts, and the way to everything else that draws over other apps.
 *
 * The four overlay windows are configured on three screens — the pill and the floating button share one,
 * the crosshair has its own, and layouts are here — so this screen carries a row for each with its current
 * state as the trailing text. A user who cannot see their stats can find out from one screen which of the
 * four is actually up, which is the question the overlay permission being revoked always produces.
 */
@Composable
fun HudScreen(
    onOpenEditor: (Long) -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HudViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<HudLayoutRow?>(null) }

    // "Display over other apps" is a system screen, so the answer changes while this screen is alive.
    OnResume { viewModel.refreshPermission() }

    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "HUD",
                subtitle = subtitleFor(state),
                action = {
                    TextButton(onClick = { onOpenEditor(Destination.NEW_LAYOUT) }) { Text("New") }
                },
            )
        }

        if (state.message != null) {
            item {
                NoteBanner(
                    text = state.message.orEmpty(),
                    tone = Tone.Accent,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                    action = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
                )
            }
        }

        item {
            OnScreenCard(
                state = state,
                onShow = viewModel::showLayout,
                onHide = viewModel::hideHud,
                onNavigate = onNavigate,
                modifier = padded,
            )
        }

        item { OtherOverlaysCard(state = state, onNavigate = onNavigate, modifier = padded) }

        if (state.isEmpty) {
            item {
                EmptyState(
                    icon = Icons.Filled.GridView,
                    title = "No layouts yet",
                    message = "A layout is a set of stats placed where you want them on screen. Build " +
                        "one, then point a game's profile at it.",
                    actionLabel = "Build a layout",
                    onAction = { onOpenEditor(Destination.NEW_LAYOUT) },
                )
            }
        }

        items(state.layouts, key = { it.id }) { row ->
            LayoutCard(
                row = row,
                isShowing = row.isActive && state.isHudVisible,
                canShow = state.canToggleOverlays,
                onEdit = { onOpenEditor(row.id) },
                onShow = { viewModel.showLayout(row.id) },
                onDelete = { pendingDelete = row },
                modifier = padded,
            )
        }
    }

    val doomed = pendingDelete
    if (doomed != null) {
        ConfirmDialog(
            title = "Delete this layout?",
            message = "${doomed.name} and its ${doomed.widgetCount} stats will be forgotten. Any " +
                "profile that used it will show no HUD.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.delete(doomed.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

private fun subtitleFor(state: HudUiState): String = when {
    !state.isLoaded -> "Loading"
    state.layouts.isEmpty() -> "Nothing built yet"
    state.isHudVisible -> "On screen now"
    else -> "${state.layouts.size} saved"
}

/**
 * Whether a HUD is on screen, and the switch that puts one there.
 *
 * The switch is disabled with a reason rather than hidden in the three cases where it cannot work: no
 * overlay permission, no layout to show, or a game's profile currently driving the overlay. Each of those
 * is a different sentence, and a screen that showed one greyed switch for all three would leave the user
 * guessing which.
 */
@Composable
private fun OnScreenCard(
    state: HudUiState,
    onShow: (Long) -> Unit,
    onHide: () -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Prefer the layout the user last chose; fall back to the first one that would actually draw
    // something, so a first-time switch-on does not put an empty layout on screen.
    val target = state.activeLayoutId ?: state.layouts.firstOrNull { it.widgetCount > 0 }?.id
    SectionCard(
        title = "On screen",
        icon = Icons.Filled.Visibility,
        modifier = modifier,
        action = {
            StatusChip(
                text = if (state.isHudVisible) "Showing" else "Hidden",
                tone = if (state.isHudVisible) Tone.Good else Tone.Muted,
            )
        },
    ) {
        SwitchRow(
            title = "Show a HUD over other apps",
            checked = state.isHudVisible,
            onCheckedChange = { wanted ->
                if (wanted) target?.let(onShow) else onHide()
            },
            enabled = state.canToggleOverlays && target != null,
            description = state.layouts.firstOrNull { it.isActive }?.let { "Showing ${it.name}." }
                ?: "Pick a layout below, or build one first.",
        )
        if (!state.hasOverlayPermission) {
            Spacer(modifier = Modifier.height(6.dp))
            NoteBanner(
                text = "GameCore cannot draw over other apps yet. Nothing will appear until that is " +
                    "granted.",
                tone = Tone.Warning,
                icon = Icons.Filled.Info,
                action = {
                    TextButton(onClick = { onNavigate(Destination.Permissions) }) { Text("Grant") }
                },
            )
        } else if (state.isDrivenByProfile) {
            Spacer(modifier = Modifier.height(6.dp))
            NoteBanner(
                text = "${state.drivingGameLabel.ifBlank { "A game" }}'s profile is in charge of the " +
                    "overlay right now. Your own choice comes back when the game stops.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
        }
    }
}

/**
 * The other three windows, each with what it is actually doing right now.
 *
 * Rows rather than switches: the pill and the crosshair both need a configuration to be meaningful — a
 * pill with no stats or a crosshair with no preset is a blank window — so the toggle lives on the screen
 * where that configuration is, and this card's job is to say what state each one is in.
 */
@Composable
private fun OtherOverlaysCard(
    state: HudUiState,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "Other overlays", icon = Icons.Filled.Layers, modifier = modifier) {
        NavRow(
            title = "Stats pill",
            description = "A small floating readout you can drag anywhere.",
            icon = Icons.Filled.Layers,
            trailing = if (state.isPillVisible) "On screen" else "Off",
            trailingTone = if (state.isPillVisible) Tone.Good else Tone.Muted,
            onClick = { onNavigate(Destination.Overlay) },
        )
        NavRow(
            title = "Floating button",
            description = "Opens GameCore's control panel over the game.",
            icon = Icons.Filled.TouchApp,
            trailing = if (state.isButtonVisible) "On screen" else "Off",
            trailingTone = if (state.isButtonVisible) Tone.Good else Tone.Muted,
            onClick = { onNavigate(Destination.Overlay) },
        )
        NavRow(
            title = "Crosshair",
            description = "A centre marker drawn over the game.",
            icon = Icons.Filled.CenterFocusStrong,
            trailing = if (state.isCrosshairVisible) "On screen" else "Off",
            trailingTone = if (state.isCrosshairVisible) Tone.Good else Tone.Muted,
            onClick = { onNavigate(Destination.Crosshair) },
        )
    }
}

/** One saved layout: its name, how many stats are on it, and the three things to do with it. */
@Composable
private fun LayoutCard(
    row: HudLayoutRow,
    isShowing: Boolean,
    canShow: Boolean,
    onEdit: () -> Unit,
    onShow: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlainCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isShowing) {
                Spacer(modifier = Modifier.width(8.dp))
                StatusChip(text = "Showing", tone = Tone.Good)
            } else if (row.isActive) {
                Spacer(modifier = Modifier.width(8.dp))
                StatusChip(text = "Selected", tone = Tone.Muted)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        ActionRow {
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onShow, enabled = canShow && row.widgetCount > 0 && !isShowing) {
                Text("Show")
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDelete) {
                Text(text = "Delete", color = Tone.Danger.colour())
            }
        }
    }
}
