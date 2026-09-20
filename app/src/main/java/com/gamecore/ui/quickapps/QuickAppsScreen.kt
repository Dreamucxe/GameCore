package com.gamecore.ui.quickapps

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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
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
import com.gamecore.core.model.FloatingButtonConfig
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
 * The apps the control panel offers one tap away, and the order they sit in.
 *
 * A screen and not a section on the overlay settings list, for the reason
 * [com.gamecore.ui.settings.NeverCloseScreen] is a screen: it is a list of the user's own apps, and lists
 * of apps are scrolled and searched rather than folded into a card between two sliders.
 *
 * The switch lives at the top of *this* screen as well as on the overlay card, because the two things a
 * user arriving here wants are on this page: whether the row is drawn, and what is in it. The switch and
 * the list are independent on purpose — turning the row off keeps the apps, so a user who wants the panel
 * uncluttered for an evening does not have to pick six apps again afterwards.
 *
 * Nothing on this screen is a must-do. The row is off until someone turns it on and empty until someone
 * fills it, and a panel with neither is the panel GameCore has always drawn.
 */
@Composable
fun QuickAppsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QuickAppsViewModel = hiltViewModel(),
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
                title = "Quick-launch apps",
                subtitle = state.summary,
                onBack = onBack,
                action = {
                    TextButton(onClick = viewModel::openPicker, enabled = !state.isFull) { Text("Add") }
                },
            )
        }

        item {
            SwitchRow(
                title = "Show quick-launch apps",
                checked = state.isEnabled,
                onCheckedChange = viewModel::setEnabled,
                description = "Adds a row of app icons to the control panel, under the action tiles. " +
                    "Works with either panel layout.",
                modifier = padded,
            )
        }

        item {
            PlainCard(modifier = padded) {
                Text(text = WHAT_THE_ROW_IS, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Said only in the state where it is the answer to a question the user is about to ask: the row is
        // turned on, apps are listed, and nothing is appearing over the game.
        if (state.isEnabled && state.chosen.isEmpty() && state.isLoaded) {
            item {
                NoteBanner(
                    text = "The row is turned on but has no apps in it, so nothing is drawn. Add one and " +
                        "it appears the next time the panel opens.",
                    tone = Tone.Muted,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                )
            }
        }

        if (state.isFull) {
            item {
                NoteBanner(
                    text = "The row holds ${FloatingButtonConfig.MAX_QUICK_APPS} apps and it is full. " +
                        "That is what fits across the panel at its narrowest without the icons becoming " +
                        "too small to hit. Remove one to add another.",
                    tone = Tone.Warning,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                )
            }
        }

        if (state.isLoaded && state.chosen.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Apps,
                    title = "No apps in the row",
                    message = "Pick the apps you reach for while a game is running — a chat app, a " +
                        "browser, whatever is playing your music.",
                    actionLabel = "Add an app",
                    onAction = viewModel::openPicker,
                )
            }
        }

        items(state.chosen, key = { it.packageName }) { app ->
            val index = state.chosen.indexOf(app)
            ChosenRow(
                app = app,
                position = index + 1,
                canMoveEarlier = index > 0,
                canMoveLater = index < state.chosen.lastIndex,
                onMove = { delta -> viewModel.move(app, delta) },
                onRemove = { viewModel.remove(app) },
                modifier = padded,
            )
        }
    }
}

/**
 * One app in the row, with its position and the two arrows that change it.
 *
 * The position is numbered rather than left to the list order alone, because the row is drawn horizontally
 * and this list is vertical: "1" is the leftmost icon in the panel, and without the number the user has to
 * infer that top means left.
 *
 * Arrows rather than a drag handle — see [QuickAppsViewModel.move]. Each is disabled at the end of the
 * list it cannot move past, and disabled rather than absent so the control does not shuffle position as
 * the list is reordered. §32 is satisfied because a disabled arrow is not clickable: it is never a button
 * that was tapped and did nothing.
 */
@Composable
private fun ChosenRow(
    app: ChosenQuickApp,
    position: Int,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlainCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$position",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))
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
                // The same words the panel draws under the icon, so the two screens agree about what is
                // wrong. The entry is kept rather than dropped: reinstalling the app makes it work again.
                StatusChip(text = "Not installed", tone = Tone.Muted)
                Spacer(modifier = Modifier.width(4.dp))
            }
            IconButton(onClick = { onMove(-1) }, enabled = canMoveEarlier) {
                Icon(
                    imageVector = Icons.Filled.ArrowUpward,
                    contentDescription = "Move ${app.label} left in the row",
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = { onMove(1) }, enabled = canMoveLater) {
                Icon(
                    imageVector = Icons.Filled.ArrowDownward,
                    contentDescription = "Move ${app.label} right in the row",
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Take ${app.label} out of the row",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * The installed-app list, alphabetically, with the ones already in the row marked.
 *
 * Launchable apps only, which is [com.gamecore.core.system.InstalledAppLister]'s existing behaviour and
 * exactly right here: an app with no launcher activity has no launch intent, so offering it would be
 * offering an icon that can only ever draw as unavailable.
 *
 * System apps are behind a switch and off by default, as in every other picker in this app. The
 * description differs because the consequence does: here they work normally, they are simply not what
 * most people are looking for.
 */
@Composable
private fun AppPicker(
    state: QuickAppsUiState,
    onChoose: (PickableQuickApp) -> Unit,
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
                description = "System apps launch from the row like any other. They are hidden by " +
                    "default because there are a lot of them.",
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
 * One offered app, clickable only when it is not already in the row.
 *
 * A [PlainCard] for the chosen ones rather than a disabled [ClickableCard], for the reason the never-close
 * picker gives: the shared card has no disabled state and adding one for this screen would change how
 * every card in the app behaves. The chip is what says why this row does nothing.
 */
@Composable
private fun PickableRow(app: PickableQuickApp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (app.isChosen) {
        PlainCard(modifier = modifier.fillMaxWidth()) { PickableBody(app) }
    } else {
        ClickableCard(onClick = onClick, modifier = modifier.fillMaxWidth()) { PickableBody(app) }
    }
}

@Composable
private fun PickableBody(app: PickableQuickApp) {
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
        if (app.isChosen) {
            StatusChip(text = "In the row", tone = Tone.Accent)
        }
    }
}

/**
 * What the row does and what it costs, before the user picks anything.
 *
 * The last sentence is the one that matters and it is the one a user would otherwise have to test: opening
 * another app from here is the same as opening it any other way, so the session keeps recording and the
 * overlay comes back when they do. Without saying so, a user who has just spent an hour setting up game
 * detection will reasonably assume a button that leaves the game ends it.
 */
private const val WHAT_THE_ROW_IS =
    "The row sits under the action tiles in the control panel, up to six icons across, and a tap opens " +
        "that app. It uses the same launch that your home screen uses — no extra permissions, nothing to " +
        "set up. Opening an app from here does not close the overlay or stop game detection; it is the " +
        "same as switching apps any other way, and the panel is where you left it when you come back."
