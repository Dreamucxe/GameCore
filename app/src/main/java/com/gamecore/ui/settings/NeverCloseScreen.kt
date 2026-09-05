package com.gamecore.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.common.Formatters
import com.gamecore.core.model.AppSettings
import com.gamecore.ui.components.ClickableCard
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone

/**
 * The apps GameCore will never close, whatever a profile asks for.
 *
 * A screen and not a section on the settings list, because it is a list of the user's own apps and
 * lists of apps are scrolled. It exists at all because the automatic protections are the ones GameCore
 * can *see* — the launcher, the keyboard, the game in front, anything holding a foreground service —
 * and the reasons a user wants an app left alone are frequently invisible from here: a download that is
 * nearly finished, a recorder between takes, a messaging app whose notifications they are waiting on.
 *
 * The explanation at the top says what is already protected before offering the list, on purpose. A
 * user who does not know their launcher is safe will add it, and an allowlist that is mostly things
 * that were never at risk hides the two entries that matter.
 */
@Composable
fun NeverCloseScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NeverCloseViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler(enabled = state.isPickerOpen) { viewModel.closePicker() }

    if (state.isPickerOpen) {
        AppPicker(
            state = state,
            onChoose = viewModel::add,
            onSetShowSystemApps = viewModel::setShowSystemApps,
            onClose = viewModel::closePicker,
            modifier = modifier,
        )
        return
    }

    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ScreenHeader(
                title = "Never close these apps",
                subtitle = if (state.entries.isEmpty()) {
                    "Nothing added"
                } else {
                    Formatters.count(state.entries.size, "app")
                },
                onBack = onBack,
                action = {
                    TextButton(onClick = viewModel::openPicker, enabled = !state.isFull) { Text("Add") }
                },
            )
        }

        item {
            PlainCard(modifier = padded) {
                Text(text = ALREADY_PROTECTED, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (state.isFull) {
            item {
                NoteBanner(
                    text = "This list holds ${AppSettings.MAX_NEVER_KILL_ENTRIES} apps and it is full. " +
                        "Remove one to add another.",
                    tone = Tone.Warning,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                )
            }
        }

        if (state.isLoaded && state.entries.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Shield,
                    title = "Nothing on the list",
                    message = "GameCore's own protections still apply. Add an app here when you want it " +
                        "left running for a reason GameCore has no way to see.",
                    actionLabel = "Add an app",
                    onAction = viewModel::openPicker,
                )
            }
        }

        items(state.entries, key = { it.packageName }) { entry ->
            ProtectedRow(app = entry, onRemove = { viewModel.remove(entry) }, modifier = padded)
        }
    }
}

/**
 * One entry, with the reason it may look unfamiliar.
 *
 * The remove button is an icon and not a swipe: a swipe with no undo on a protection list is a way to
 * lose a protection without noticing, and there is nothing here worth building an undo for when the app
 * can simply be added back in two taps.
 */
@Composable
private fun ProtectedRow(app: ProtectedApp, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    PlainCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!app.isInstalled) {
                StatusChip(text = "Not installed", tone = Tone.Muted)
                Spacer(modifier = Modifier.width(8.dp))
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove ${app.label}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The installed-app list, alphabetically, with the already-listed ones marked.
 *
 * System apps are behind a switch and off by default for a different reason than in the profile
 * editor's picker: GameCore never closes a system app in the first place, so adding one here changes
 * nothing. The switch exists because a user looking for an app they do not find will otherwise assume
 * the list is broken, and the row says plainly that those are already safe.
 */
@Composable
private fun AppPicker(
    state: NeverCloseUiState,
    onChoose: (PickableApp) -> Unit,
    onSetShowSystemApps: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val padded = Modifier.padding(horizontal = ScreenPadding)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            ScreenHeader(
                title = "Choose an app",
                subtitle = if (state.isLoadingApps) {
                    "Reading the installed list"
                } else {
                    Formatters.count(state.apps.size, "app")
                },
                onBack = onClose,
            )
        }
        item {
            SwitchRow(
                title = "Include system apps",
                checked = state.showSystemApps,
                onCheckedChange = onSetShowSystemApps,
                description = "GameCore already leaves system apps alone, so adding one changes nothing.",
                modifier = padded,
            )
        }
        if (state.isLoadingApps) {
            item {
                Row(
                    modifier = padded.padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = "Listing apps", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        items(state.apps, key = { it.packageName }) { app ->
            PickableRow(app = app, onClick = { onChoose(app) }, modifier = padded)
        }
    }
}

/**
 * One offered app, clickable only when it is not already on the list.
 *
 * A [PlainCard] for the listed ones rather than a disabled [ClickableCard]: the shared card has no
 * disabled state, and giving it one for this screen would change how every card in the app behaves. The
 * row is identical either way and the chip is what says why this one does nothing.
 */
@Composable
private fun PickableRow(app: PickableApp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (app.isListed) {
        PlainCard(modifier = modifier.fillMaxWidth()) { PickableBody(app) }
    } else {
        ClickableCard(onClick = onClick, modifier = modifier.fillMaxWidth()) { PickableBody(app) }
    }
}

@Composable
private fun PickableBody(app: PickableApp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (app.isListed) {
            StatusChip(text = "On the list", tone = Tone.Accent)
        }
    }
}

/**
 * What is protected without anybody adding it, as the first thing on the screen.
 *
 * Written as the list it is rather than as a reassurance, because every item on it is a specific check
 * in [com.gamecore.domain.memory.ReclaimFilter] and the user is entitled to know which ones. The last
 * sentence is the honest limit of the whole feature: what this app can see of another app's state is
 * what Android will tell it, and on a device without Shizuku that is very little.
 */
private const val ALREADY_PROTECTED =
    "GameCore already leaves these alone without being asked: itself, the game being launched, your " +
        "home screen, your keyboard, anything running a foreground service — music, a recording, a " +
        "download, a navigation route — and every part of the system. Add an app here when you want it " +
        "left running for a reason none of that covers."
