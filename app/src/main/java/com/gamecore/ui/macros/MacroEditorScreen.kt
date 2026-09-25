package com.gamecore.ui.macros

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.overlay.Macro
import com.gamecore.core.overlay.MacroLibrary
import com.gamecore.core.overlay.OverlayAction
import com.gamecore.ui.components.ChoiceChip
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.TextFieldRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.theme.Spacing

/**
 * The §14 macro editor: name, order and delete the one-tap macros that ride on the quick sheet, and choose
 * which panel actions each one replays. The sibling screen of `QuickAppsScreen`.
 *
 * It is a *render-store* editor, not a working-copy one: every card is drawn straight from the stored,
 * normalised macro list ([MacroEditorViewModel.state]), and every edit writes back through the same store,
 * so what the user shapes here is exactly what the quick sheet draws — the two cannot drift. There is no
 * Save-the-whole-screen button because there is no draft to save; the one exception is a macro's *name*,
 * which commits on an explicit "Save name" so a half-typed name is never sanitised out from under the cursor.
 *
 * "Add" is enabled only below [MacroLibrary.MAX_MACROS], each reorder arrow only when there is somewhere to
 * go, and a macro's last action's Remove is disabled — a macro must keep at least one action, and the way to
 * be rid of it entirely is to delete the whole macro. Those disabled states mirror the no-ops in
 * [MacroLibrary] exactly, so the screen never offers an edit the model would silently refuse (§32).
 */
@Composable
fun MacroEditorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MacroEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val padded = Modifier.padding(horizontal = ScreenPadding)
    var pendingDelete by remember { mutableStateOf<Macro?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ScreenHeader(
                title = "Macros",
                subtitle = state.summary,
                onBack = onBack,
                action = {
                    TextButton(onClick = viewModel::addMacro, enabled = state.isLoaded && !state.isFull) {
                        Text("Add")
                    }
                },
            )
        }
        item {
            PlainCard(modifier = padded) {
                Text(text = WHAT_A_MACRO_IS, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.isLoaded && state.macros.isEmpty()) {
            item {
                NoteBanner(
                    text = "No macros yet. Tap Add to make one, then choose the toggles it runs and their order.",
                    tone = Tone.Muted,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                )
            }
        }
        itemsIndexed(state.macros) { index, macro ->
            MacroCard(
                macro = macro,
                position = index + 1,
                canMoveEarlier = index > 0,
                canMoveLater = index < state.macros.lastIndex,
                onRename = { viewModel.rename(macro.id, it) },
                onMove = { viewModel.moveMacro(macro.id, it) },
                onDelete = { pendingDelete = macro },
                onAddAction = { viewModel.addAction(macro.id, it) },
                onRemoveAction = { viewModel.removeAction(macro.id, it) },
                onMoveAction = { action, delta -> viewModel.moveAction(macro.id, action, delta) },
                modifier = padded,
            )
        }
    }

    pendingDelete?.let { macro ->
        ConfirmDialog(
            title = "Delete this macro?",
            message = "\"${macro.name}\" will be removed from the quick sheet. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.removeMacro(macro.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * One macro's card: its position and name in the header, a reorder/delete cluster in the header's action
 * slot, and — as content — the name editor and the ordered list of actions the macro replays.
 *
 * The title is "Macro N" and the name is the subtitle rather than the title, so a long name never fights the
 * three header buttons for the row, and the name stays editable in the field below without the header
 * jumping. [position] is the 1-based place in the list, which is also the id the store re-derives on each
 * read, so it is the honest thing to number the card by.
 */
@Composable
private fun MacroCard(
    macro: Macro,
    position: Int,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    onRename: (String) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onAddAction: (OverlayAction) -> Unit,
    onRemoveAction: (OverlayAction) -> Unit,
    onMoveAction: (OverlayAction, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Macro $position",
        subtitle = macro.name,
        modifier = modifier,
        action = {
            MacroCardControls(
                name = macro.name,
                canMoveEarlier = canMoveEarlier,
                canMoveLater = canMoveLater,
                onMove = onMove,
                onDelete = onDelete,
            )
        },
    ) {
        MacroNameEditor(macro = macro, onRename = onRename)
        RowDivider()
        MacroActionsSection(
            macro = macro,
            onAddAction = onAddAction,
            onRemoveAction = onRemoveAction,
            onMoveAction = onMoveAction,
        )
    }
}

/** The header cluster: move this macro earlier or later, or delete it. Arrows dark at the ends, per the model. */
@Composable
private fun MacroCardControls(
    name: String,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onMove(-1) }, enabled = canMoveEarlier) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = "Move $name up", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = { onMove(1) }, enabled = canMoveLater) {
            Icon(Icons.Filled.ArrowDownward, contentDescription = "Move $name down", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Delete $name",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * The name field and its explicit Save. The buffer is local and re-seeds on the stored name, so a save (or
 * another edit landing) resets it; commit is a button rather than per-keystroke so the sanitiser never trims
 * the name mid-word. Save is dark until the text is a real, non-blank change — a blank rename is refused
 * upstream because it would drop the macro on the next decode.
 */
@Composable
private fun MacroNameEditor(macro: Macro, onRename: (String) -> Unit) {
    var name by remember(macro.id, macro.name) { mutableStateOf(macro.name) }
    TextFieldRow(label = "Name", value = name, onValueChange = { name = it })
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank() && name.trim() != macro.name) {
            Text("Save name")
        }
    }
}

/**
 * The ordered list of actions the macro runs, then the chips for the ones it could still add. The add block
 * disappears once the macro is at [MacroLibrary.MAX_ACTIONS] or there is nothing left to offer — the same
 * conditions [MacroLibrary.addAction] would no-op on.
 */
@Composable
private fun MacroActionsSection(
    macro: Macro,
    onAddAction: (OverlayAction) -> Unit,
    onRemoveAction: (OverlayAction) -> Unit,
    onMoveAction: (OverlayAction, Int) -> Unit,
) {
    GroupLabel("RUNS THESE, IN ORDER")
    macro.actions.forEachIndexed { index, action ->
        MacroActionRow(
            action = action,
            canMoveEarlier = index > 0,
            canMoveLater = index < macro.actions.lastIndex,
            // A macro must keep at least one action; the way to remove the last is to delete the macro.
            canRemove = macro.actions.size > 1,
            onMove = { onMoveAction(action, it) },
            onRemove = { onRemoveAction(action) },
        )
    }
    val addable = MacroLibrary.addableActions(macro)
    if (macro.actions.size < MacroLibrary.MAX_ACTIONS && addable.isNotEmpty()) {
        Spacer(modifier = Modifier.height(Spacing.sm))
        GroupLabel("ADD AN ACTION")
        AddActionChips(addable = addable, onAdd = onAddAction)
    }
}

/** One action in the macro: its label, then move-earlier / move-later / remove. Remove is dark at one action. */
@Composable
private fun MacroActionRow(
    action: OverlayAction,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    canRemove: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = action.label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onMove(-1) }, enabled = canMoveEarlier) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = "Move ${action.label} earlier", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = { onMove(1) }, enabled = canMoveLater) {
            Icon(Icons.Filled.ArrowDownward, contentDescription = "Move ${action.label} later", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onRemove, enabled = canRemove) {
            Icon(Icons.Filled.Close, contentDescription = "Remove ${action.label}", modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * The addable actions as tap-to-add chips, wrapped [ADD_COLUMNS] to a row with the short row padded so widths
 * stay even — the [ChoiceChip] idiom `ChoiceRow` uses, but momentary: a tap adds and the chip is gone next
 * frame (the action is now in the list above), so none is ever drawn selected.
 */
@Composable
private fun AddActionChips(addable: List<OverlayAction>, onAdd: (OverlayAction) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        addable.chunked(ADD_COLUMNS).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { action ->
                    ChoiceChip(
                        text = action.label,
                        isSelected = false,
                        onClick = { onAdd(action) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(ADD_COLUMNS - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/** The §7 sub-heading style, reproduced here because `OverlayScreen`'s own `GroupLabel` is private to it. */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() },
    )
}

/** Addable-action chips per row — three, the `ChoiceRow` default, comfortable for the short action labels. */
private const val ADD_COLUMNS = 3

private const val WHAT_A_MACRO_IS =
    "A macro runs several panel controls with one tap, in the order you list them. It composes controls the " +
        "panel already has — turning the stats pill and the crosshair on, taking a screenshot — into a single " +
        "chip on the quick sheet. Toggles are forced on rather than flipped, so a macro lands on the same " +
        "result whatever state you were in. Your macros appear as a row under the tiles on the sheet."





