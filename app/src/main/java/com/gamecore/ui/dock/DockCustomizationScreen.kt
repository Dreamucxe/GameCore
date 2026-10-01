package com.gamecore.ui.dock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dock
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.model.DockActionId
import com.gamecore.core.model.DockActions
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.KeyValueRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour

/**
 * Which controls the floating dock offers and in what order, with the two resets that put it back (§3.7.1,
 * features 4–6).
 *
 * A screen and not a card on the overlay settings list, for the reason
 * [com.gamecore.ui.quickapps.QuickAppsScreen] is a screen: it is a list of the user's own arrangement with
 * per-row controls, and a fourteen-row list folded between two sliders is a list nobody scrolls to the bottom
 * of. It is also written to *look* like that screen — numbered positions, two arrows, a switch per row —
 * because it is the same kind of task and the gestures should not have to be learned twice.
 *
 * ## The one thing this screen does not do
 *
 * It holds no appearance control. Size, opacity, panel width, snap-to-edge, vibrate-on-drag and reset-position
 * are all on `OverlayScreen`'s Floating dock card and stay there, so there is exactly one place to change each
 * of them. The split is stated in prose at the top of the screen rather than left for the user to discover,
 * because a user who came here looking for the size slider needs to be sent somewhere, not left searching a
 * screen that will never have it.
 *
 * The *resets* are the exception, and both of them are here. That is not a contradiction: feature 6's whole
 * content is that the dock's appearance and the dock's arrangement reset **separately**, and a pair of buttons
 * split across two screens cannot say that. Side by side, each one's copy can name what the other keeps, which
 * is the only way the separation is visible before the user commits to it.
 *
 * ## The two master switches
 *
 * Both are reported and neither is offered. When `dockCustomizationEnabled` is off the list stays editable and
 * a banner says the dock is drawing its shipped arrangement meanwhile — that flag promises the saved order is
 * *kept*, so a screen that locked itself would imply otherwise. When `quickActionsEnabled` is off the action
 * rows are replaced by the reason rather than drawn inert, which is what that flag's KDoc asks for and what
 * [com.gamecore.core.overlay.isGatedByQuickActions] explains at length: a group that is not drawn at all is a
 * different fact from seven greyed chips, and dressing one as the other misdescribes what happened.
 *
 * No save button. Every switch and arrow is written as it is touched, because the thing being arranged is
 * often on screen over a game while this list is open.
 */
@Composable
fun DockCustomizationScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DockCustomizationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ScreenHeader(
                title = "Dock controls",
                subtitle = state.summary,
                onBack = onBack,
            )
        }

        item {
            PlainCard(modifier = padded) {
                Text(text = WHAT_THIS_SCREEN_IS, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Guarded on isLoaded so the first frame, before the store has answered, cannot flash a warning about
        // a switch that is in fact on and then take it back in front of the user.
        if (state.isLoaded && !state.isCustomizationEnabled) {
            item {
                NoteBanner(
                    text = CUSTOMISATION_OFF,
                    tone = Tone.Warning,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                )
            }
        }

        item { DockPreviewCard(state = state, modifier = padded) }

        // The honest cap. Normally absent; when present it names the rows that are switched on and not on the
        // dock, because a silently dropped control is the failure this screen exists to prevent.
        state.overflowNote?.let { note ->
            item {
                NoteBanner(
                    text = note,
                    tone = Tone.Warning,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                )
            }
        }

        item {
            ToggleSection(
                state = state,
                onSetShown = viewModel::setShown,
                onMove = viewModel::move,
                modifier = padded,
            )
        }

        item {
            QuickActionSection(
                state = state,
                onSetShown = viewModel::setShown,
                onMove = viewModel::move,
                modifier = padded,
            )
        }

        item {
            ResetCard(
                onResetAppearance = viewModel::askResetAppearance,
                onResetActions = viewModel::askResetActions,
                modifier = padded,
            )
        }
    }

    // Both dialogs sit outside the LazyColumn on purpose. A dialog declared inside an item is torn down the
    // moment that item scrolls out of the viewport, which would dismiss a confirmation the user is reading.
    if (state.pendingResetAppearance) {
        ConfirmDialog(
            title = "Reset the dock's appearance?",
            message = APPEARANCE_RESET_WARNING,
            confirmLabel = "Reset appearance",
            onConfirm = viewModel::confirmResetAppearance,
            onDismiss = viewModel::cancelPending,
        )
    }

    if (state.pendingResetActions) {
        ConfirmDialog(
            title = "Reset the dock's quick actions?",
            message = ACTIONS_RESET_WARNING,
            confirmLabel = "Reset the arrangement",
            onConfirm = viewModel::confirmResetActions,
            onDismiss = viewModel::cancelPending,
        )
    }
}

/**
 * What the panel will draw, read back from the arrangement — the answer to "did that do what I wanted".
 *
 * Read-only, and it is the one part of this screen that is not a control. The lists below hold every control
 * there is, most of them switched off, so working out what the panel will actually look like means filtering
 * them by eye; this card does that filtering once, in the panel's own two groups and in the panel's own order.
 *
 * The lists come from [DockCustomizationUiState.dockToggles] and [DockCustomizationUiState.dockActions], which
 * are [DockActions.visibleOfKind] calls — the same call the live panel makes. Re-deriving them here from the
 * row list would be a second implementation of "what is on the dock", and the first time the two drifted this
 * card would be confidently wrong about the thing it exists to confirm.
 */
@Composable
private fun DockPreviewCard(state: DockCustomizationUiState, modifier: Modifier = Modifier) {
    SectionCard(
        title = "What the panel will show",
        subtitle = "Worked out with the same rules the dock itself uses",
        icon = Icons.Filled.Dock,
        modifier = modifier,
    ) {
        GroupLabel("TOGGLE CELLS, IN ORDER")
        Text(
            text = state.dockToggles.joinToString(" · ") { it.label }.ifEmpty { "None switched on" },
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(modifier = Modifier.height(8.dp))

        GroupLabel("ACTION CHIPS, IN ORDER")
        Text(
            text = when {
                // Said here as well as in the section below, because this card is the one a user checks
                // before leaving the screen, and "no chips" would otherwise look like a mistake they made.
                !state.areQuickActionsEnabled -> "Not drawn at all — quick actions are off in Settings"
                state.dockActions.isEmpty() -> "None switched on"
                else -> state.dockActions.joinToString(" · ") { it.label }
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        RowDivider()
        KeyValueRow(
            label = "Places used",
            value = "${state.onDockCount} of ${DockActions.MAX_VISIBLE}",
            tone = if (state.isFull) Tone.Warning else Tone.Neutral,
        )
    }
}

/**
 * The toggle controls, in the user's order.
 *
 * Toggles and actions are two cards rather than one list of everything because the panel draws them as two
 * grids: a cell that holds an on/off state and a chip that fires once are different promises, and the user is
 * arranging two groups whether or not this screen admits it. The split is also what lets the action card be
 * replaced wholesale when quick actions are switched off, with the toggles unaffected — exactly the scope that
 * flag has.
 *
 * The positions shown are positions in the **whole** arrangement, not within this card, because that is what
 * [DockActions.MAX_VISIBLE] is spent out of: the cap crosses both kinds, so a toggle can be pushed off the
 * dock by chips. Numbering each card from one would hide that completely.
 */
@Composable
private fun ToggleSection(
    state: DockCustomizationUiState,
    onSetShown: (DockActionId, Boolean) -> Unit,
    onMove: (DockActionId, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Toggles",
        subtitle = "Cells that hold an on/off state",
        icon = Icons.Filled.Tune,
        modifier = modifier,
    ) {
        state.toggleRows.forEachIndexed { index, row ->
            if (index > 0) RowDivider()
            ArrangementRow(
                row = row,
                canSwitchOn = !state.isFull,
                onSetShown = { shown -> onSetShown(row.id, shown) },
                onMove = { delta -> onMove(row.id, delta) },
            )
        }
    }
}

/**
 * The one-shot controls, or the reason there are none.
 *
 * When `quickActionsEnabled` is off this card does not list the rows disabled — it says what happened instead.
 * That is the behaviour `AppSettings.quickActionsEnabled`'s KDoc specifies ("the customisation screen says why
 * rather than showing an inert action list") and that
 * [com.gamecore.core.overlay.isGatedByQuickActions] justifies: the chips are not drawn on the panel at all, so
 * seven greyed rows here would describe a dimming that is not what is happening, and would invite the user to
 * arrange controls that cannot appear.
 *
 * The second banner is the part a user could not otherwise work out. [DockActions.visible] caps across both
 * kinds and knows nothing about this flag, so chips the user switched on are still holding their places out of
 * [DockActions.MAX_VISIBLE] while nothing draws them — an arrangement of three toggles and five chips becomes
 * a dock with three cells on it and five places spent on nothing. Not saying so would leave the user looking
 * at five empty slots with no way to account for them.
 */
@Composable
private fun QuickActionSection(
    state: DockCustomizationUiState,
    onSetShown: (DockActionId, Boolean) -> Unit,
    onMove: (DockActionId, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Quick actions",
        subtitle = "Chips that do one thing and close",
        icon = Icons.Filled.Bolt,
        modifier = modifier,
    ) {
        if (!state.areQuickActionsEnabled) {
            EmptyState(
                icon = Icons.Filled.Bolt,
                title = "Quick actions are off",
                message = QUICK_ACTIONS_OFF,
            )
            // The cap crosses both kinds and knows nothing about the flag, so chips switched on are still
            // spending places while nothing draws them. See this function's KDoc.
            if (state.dockActions.isNotEmpty()) {
                NoteBanner(
                    text = heldPlacesNote(state.dockActions.size),
                    tone = Tone.Warning,
                    icon = Icons.Filled.Info,
                )
            }
        } else {
            state.actionRows.forEachIndexed { index, row ->
                if (index > 0) RowDivider()
                ArrangementRow(
                    row = row,
                    canSwitchOn = !state.isFull,
                    onSetShown = { shown -> onSetShown(row.id, shown) },
                    onMove = { delta -> onMove(row.id, delta) },
                )
            }
        }
    }
}

/**
 * One control: its place on the dock, its name, the two arrows that move it and the switch that puts it there.
 *
 * Built here rather than from [com.gamecore.ui.components.SwitchRow] because that component has no trailing
 * slot, and giving it one would change the shape of every switch in the app for the sake of this screen —
 * the reason [com.gamecore.ui.quickapps.QuickAppsScreen] gives for not adding a disabled state to the shared
 * card. The row is two lines instead of one so the description gets the full width: squeezed between a number,
 * two icon buttons and a switch it would wrap to four lines on a phone and the sentence would not be read.
 *
 * The number is the control's place among the ones switched on, and an en dash when it is switched off — a
 * switched-off control has no place, and showing one would imply the dock had reserved it. It is tinted
 * [Tone.Warning] when the control is switched on but past the cap, which is the per-row half of
 * [DockCustomizationUiState.overflowNote]: the note names them, the chip under the row marks them.
 *
 * Both arrows are disabled rather than absent at the ends of the list, so the controls do not shuffle sideways
 * as rows are reordered, and §32 holds because a disabled arrow is not clickable — it is never a button that
 * was tapped and did nothing. The switch obeys the same rule at the cap: at [DockActions.MAX_VISIBLE] it is
 * disabled on the rows that are off, because [DockActions.show] would refuse the write and a switch that slid
 * back by itself is a control that appears to have failed.
 */
@Composable
private fun ArrangementRow(
    row: DockActionRow,
    canSwitchOn: Boolean,
    onSetShown: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.position?.toString() ?: "–",
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    row.isOnDock -> MaterialTheme.colorScheme.primary
                    row.isShown -> Tone.Warning.colour()
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.width(24.dp),
            )
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onMove(-1) }, enabled = row.canMoveEarlier) {
                Icon(
                    imageVector = Icons.Filled.ArrowUpward,
                    contentDescription = "Move ${row.label} earlier on the dock",
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = { onMove(1) }, enabled = row.canMoveLater) {
                Icon(
                    imageVector = Icons.Filled.ArrowDownward,
                    contentDescription = "Move ${row.label} later on the dock",
                    modifier = Modifier.size(18.dp),
                )
            }
            Switch(
                checked = row.isShown,
                onCheckedChange = onSetShown,
                enabled = row.isShown || canSwitchOn,
            )
        }
        Text(
            text = row.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (row.isShown && !row.isOnDock) {
            Spacer(modifier = Modifier.height(4.dp))
            StatusChip(text = "Switched on, not on the dock", tone = Tone.Warning)
        }
    }
}

/**
 * The two resets, side by side, each saying what the other one keeps.
 *
 * They are together because their separation *is* feature 6. Two buttons on two screens would make the user
 * take on trust that resetting the arrangement leaves the dock where they parked it; two buttons in one card,
 * each with a line naming what it does not touch, says it before either is pressed. The store backs the claim
 * rather than the copy making it alone —
 * [com.gamecore.data.preferences.SecurePreferenceStore.resetDockAppearance] and
 * [com.gamecore.data.preferences.SecurePreferenceStore.resetDockActions] write disjoint sets of keys, which is
 * what makes "nothing else is touched" a fact about the file and not a promise about this screen.
 *
 * One button per [ActionRow] rather than both in one, because the labels have to name which half they reset
 * and two such labels on one line are clipped on a narrow phone — a reset button whose label is cut off is the
 * last button in the app that should be ambiguous.
 */
@Composable
private fun ResetCard(
    onResetAppearance: () -> Unit,
    onResetActions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Reset",
        subtitle = "Two halves, reset separately",
        icon = Icons.Filled.SettingsBackupRestore,
        modifier = modifier,
    ) {
        Text(
            text = APPEARANCE_RESET_KEEPS,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionRow {
            TextButton(onClick = onResetAppearance) { Text("Reset dock appearance") }
        }

        RowDivider()

        Text(
            text = ACTIONS_RESET_KEEPS,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionRow {
            TextButton(onClick = onResetActions) { Text("Reset quick actions") }
        }
    }
}

/**
 * The §7 sub-heading style, reproduced here because `OverlayScreen`'s own `GroupLabel` is private to it.
 *
 * The same redeclaration `MacroEditorScreen` makes, and a real heading rather than a styled `Text` for the
 * reason the original gives: the word is the only thing carrying the grouping for a screen reader, since the
 * divider above it is not announced.
 */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() },
    )
}

/**
 * The sentence for chips that are switched on while the grid they belong to is not being drawn.
 *
 * A function rather than a constant because the figure is the whole point of it — "five places" is actionable
 * and "some places" is not.
 */
private fun heldPlacesNote(held: Int): String =
    "$held of the ${DockActions.MAX_VISIBLE} places on the dock are still held by chips you switched on, and " +
        "nothing is drawing them while quick actions are off. Switch them off here to give those places back " +
        "to the toggles, or turn quick actions back on in Settings."

/**
 * What this screen is for, and — the part that matters — where the rest of the dock's settings are.
 *
 * The last sentence is here because this screen is where a user will look for the size slider, and a screen
 * that simply does not have it leaves them scrolling. Naming the card it is on costs one line and is the only
 * alternative to duplicating the control, which this codebase's own KDoc forbids: two places to change one
 * thing is two places that disagree the first time one of them is edited.
 */
private val WHAT_THIS_SCREEN_IS =
    "Tap the dock and it opens a compact panel with two grids: toggle cells that hold an on/off state, then " +
        "action chips that do one thing and close. This screen decides which of GameCore's " +
        "${DockActionId.entries.size} dock controls are in those grids and what order they come in — up to " +
        "${DockActions.MAX_VISIBLE} between the two of them. How the dock looks is set elsewhere: size, " +
        "opacity, panel width, snap-to-edge, vibrate-on-drag and putting it back where it started are on the " +
        "Floating dock card under Overlays, and only there."

/** Said when `dockCustomizationEnabled` is off. The promise that the arrangement survives is the whole point. */
private val CUSTOMISATION_OFF =
    "Dock customisation is switched off in Settings, so the dock is drawing the " +
        "${DockActionId.DEFAULT_VISIBLE.size} controls it shipped with and ignoring the arrangement below. " +
        "Nothing here has been cleared — keep arranging if you like, and it is what the dock draws the moment " +
        "you switch customisation back on."

/**
 * Said in place of the action rows when `quickActionsEnabled` is off.
 *
 * It names the switch and where it is, because the fix belongs to the user and is two taps away — the
 * distinction [com.gamecore.core.overlay.DockUnavailableReason.FEATURE_SWITCHED_OFF] is careful about: telling
 * someone "not available" about their own preference sends them looking for a new phone.
 */
private const val QUICK_ACTIONS_OFF =
    "Quick actions are switched off in Settings, so the dock panel draws its toggle grid and no chips at " +
        "all. They are not greyed out over your game — they are simply not there. Turn quick actions back " +
        "on in Settings and this list comes back exactly as you left it."

/** The copy above the appearance reset. Says what survives, because that is what makes it safe to press. */
private const val APPEARANCE_RESET_KEEPS =
    "Puts the dock's size, opacity, panel width, snap-to-edge, vibrate-on-drag and parked position back to " +
        "the shipped values. Your arrangement of controls is kept, and the dock stays switched on."

/** The copy above the arrangement reset. The mirror of [APPEARANCE_RESET_KEEPS], and as narrow. */
private val ACTIONS_RESET_KEEPS =
    "Puts the dock back to the ${DockActionId.DEFAULT_VISIBLE.size} controls it shipped with, in the order " +
        "it shipped them. How the dock looks and where it sits are kept."

/**
 * The appearance dialog's message.
 *
 * It names what goes back **and** what does not, because the second half is the feature: a user who wants the
 * handle back at a sane size should not have to wonder whether they are about to lose the eight controls they
 * arranged to get it. `show` is called out for the reason
 * [com.gamecore.core.model.DockConfig.withDefaultAppearance] leaves it alone — a dock that vanished while the
 * user was looking at it reads as a crash rather than as a reset.
 */
private const val APPEARANCE_RESET_WARNING =
    "Size, opacity, panel width, snap-to-edge, vibrate-on-drag and the position you dragged the dock to all " +
        "go back to the values it shipped with. Which controls are on the dock, and the order you put them " +
        "in, are not touched. The dock stays switched on. This cannot be undone."

/** The arrangement dialog's message. The mirror of [APPEARANCE_RESET_WARNING], naming the other half. */
private val ACTIONS_RESET_WARNING =
    "The dock goes back to the ${DockActionId.DEFAULT_VISIBLE.size} controls it shipped with, in the order " +
        "it shipped them, and everything you switched on beyond those is switched off. The dock's size, " +
        "opacity, panel width, feedback and parked position are not touched, and it stays where you left it " +
        "on screen. This cannot be undone."
