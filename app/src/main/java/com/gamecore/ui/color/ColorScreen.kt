package com.gamecore.ui.color

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.model.ColorField
import com.gamecore.core.model.ColorPreset
import com.gamecore.core.model.ColorVisionFilter
import com.gamecore.core.model.GammaMode
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.NavRow
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.OnResume
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.ReadoutRow
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SectionGap
import com.gamecore.ui.components.SliderRow
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.TextFieldRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour

/**
 * The whole colour correction feature, on one screen: eleven values, a filter, and the presets they save as.
 *
 * The three quick sliders in the overlay panel are the part of this that fits over a game. This is the rest
 * of it, and it is deliberately not a shorter version: a gain is set per channel, gamma is one slider or
 * three, and every figure can be typed rather than dragged, because a hue rotation is 361 positions wide and
 * "−12" is not a thumb position.
 *
 * Three things run through the layout.
 *
 *  - **Nothing is hidden because it will not work.** A device that cannot express contrast still gets a
 *    contrast slider, with the reason underneath it, because the value is stored, travels in a preset and
 *    may well be honoured on the next device — and a control that vanishes teaches the user nothing. What
 *    would be dishonest is the slider *pretending*, which is what the line under it exists to prevent.
 *  - **Write access is stated once, at the top, permanently.** It is the one fact that decides whether any
 *    of this reaches the display, so it is not a toast that appears after the first failed drag.
 *  - **Every control writes through**, as on the crosshair screen: a slider on release, everything else as
 *    it is touched. There is no save button, and "Save as preset" means something else — a named copy.
 */
@Composable
fun ColorScreen(
    onBack: () -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ColorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ColorField?>(null) }
    var saving by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ColorPreset?>(null) }
    var deleting by remember { mutableStateOf<ColorPreset?>(null) }
    var resetting by remember { mutableStateOf(false) }

    // Shizuku is granted in another app's window, a profile can apply a preset while this screen is in the
    // background, and the overlay panel edits three of these values from over a game. All three are reasons
    // the screen re-reads rather than trusting what it drew last time.
    OnResume { viewModel.refresh() }

    val padded = Modifier.padding(horizontal = ScreenPadding)

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Colour correction",
                subtitle = state.presetName,
                onBack = onBack,
            )
        }
        state.message?.let { message ->
            item {
                NoteBanner(
                    text = message,
                    tone = Tone.Accent,
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
            AccessCard(
                state = state,
                onCheck = viewModel::checkEngagement,
                onNavigate = onNavigate,
                modifier = padded,
            )
        }
        item {
            PresetsCard(
                state = state,
                onLoad = viewModel::loadPreset,
                onSaveAs = { saving = true },
                onOverwrite = viewModel::overwrite,
                onRename = { renaming = it },
                onDelete = { deleting = it },
                onRestore = viewModel::restoreBuiltIns,
                modifier = padded,
            )
        }
        item {
            FieldCard(
                title = "Channel gain",
                subtitle = "How much of each primary the panel emits",
                fields = GAIN_FIELDS,
                state = state,
                onValue = viewModel::setField,
                onCommit = viewModel::commitField,
                onEdit = { editing = it },
                onResetFields = viewModel::resetFields,
                modifier = padded,
            )
        }
        item {
            GammaCard(
                state = state,
                onValue = viewModel::setField,
                onCommit = viewModel::commitField,
                onEdit = { editing = it },
                onResetFields = viewModel::resetFields,
                onMode = viewModel::setGammaMode,
                modifier = padded,
            )
        }
        item {
            FieldCard(
                title = "Image",
                subtitle = "The three quick sliders in the game panel, and one more",
                fields = IMAGE_FIELDS,
                state = state,
                onValue = viewModel::setField,
                onCommit = viewModel::commitField,
                onEdit = { editing = it },
                onResetFields = viewModel::resetFields,
                modifier = padded,
            )
        }
        item {
            FilterCard(
                state = state,
                onFilter = viewModel::setVisionFilter,
                onInvert = viewModel::setInvert,
                modifier = padded,
            )
        }
        item { ChangesCard(state = state, modifier = padded) }
        item {
            ResetCard(
                state = state,
                onReset = { resetting = true },
                modifier = padded,
            )
        }
    }

    editing?.let { field ->
        ValueDialog(
            field = field,
            value = state.correction.valueOf(field),
            onSet = { value ->
                editing = null
                viewModel.setField(field, value)
                viewModel.commitField()
            },
            onReset = {
                editing = null
                viewModel.resetField(field)
            },
            onDismiss = { editing = null },
        )
    }
    if (saving) {
        NameDialog(
            title = "Save as preset",
            confirmLabel = "Save",
            initial = "Colour ${state.presets.size + 1}",
            message = "These values are saved under a name of their own. The preset you started from is " +
                "left as it was, and there is no limit on how many you keep.",
            onConfirm = { name ->
                saving = false
                viewModel.saveAsNew(name)
            },
            onDismiss = { saving = false },
        )
    }
    renaming?.let { preset ->
        NameDialog(
            title = "Rename preset",
            confirmLabel = "Rename",
            initial = preset.name,
            message = "The values stay as they are. Any game profile using this preset follows the new name.",
            onConfirm = { name ->
                renaming = null
                viewModel.rename(preset.id, name)
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { preset ->
        ConfirmDialog(
            title = "Delete ${preset.name}?",
            message = "The preset goes, and any game profile that applied it stops applying a colour " +
                "correction. The values on the sliders are not touched, and the display is left as it is.",
            confirmLabel = "Delete",
            onConfirm = {
                deleting = null
                viewModel.delete(preset.id)
            },
            onDismiss = { deleting = null },
        )
    }
    if (resetting) {
        ConfirmDialog(
            title = "Reset everything?",
            message = "Every value goes back to neutral and GameCore puts each setting it changed back to " +
                "the reading it took before changing it. Your saved presets are not touched.",
            confirmLabel = "Reset",
            isDestructive = false,
            onConfirm = {
                resetting = false
                viewModel.resetAll()
            },
            onDismiss = { resetting = false },
        )
    }
}

/**
 * Whether GameCore may write a colour key at all, and what the display currently has engaged.
 *
 * First card on the screen, and the one that decides what everything under it means. A device where
 * `WRITE_SECURE_SETTINGS` cannot be held says so here, once, with the way to fix it beside it — rather than
 * letting the user drag eleven sliders and work it out.
 *
 * The engagement reading is a button rather than a live figure because it costs shell round trips, and it is
 * read from the *device* rather than from what GameCore last asked for: a user whose ROM runs its own night
 * schedule should see that, and should also see that this screen will not undo it.
 */
@Composable
private fun AccessCard(
    state: ColorUiState,
    onCheck: () -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Access and state",
        modifier = modifier,
        action = {
            StatusChip(
                text = if (state.isEngagedByGameCore) "Applied" else "Not applied",
                tone = if (state.isEngagedByGameCore) Tone.Good else Tone.Muted,
            )
        },
    ) {
        ReadoutRow(readout = state.accessRow)
        RowDivider()
        ReadoutRow(readout = state.engagementRow)
        ActionRow {
            TextButton(onClick = onCheck, enabled = !state.isChecking) {
                Text(if (state.isChecking) "Reading the display…" else "Check the display")
            }
        }
        if (!state.canWrite) {
            SectionGap(6)
            NoteBanner(
                text = "GameCore cannot write display settings on this device yet, so the values below are " +
                    "stored and shown but nothing reaches the screen. Shizuku is the way to grant it " +
                    "without root.",
                tone = Tone.Warning,
                icon = Icons.Filled.Info,
                action = {
                    TextButton(onClick = { onNavigate(Destination.Shizuku) }) { Text("Set up") }
                },
            )
        }
    }
}

/**
 * Every saved preset, and the four things that can be done with one.
 *
 * The seven that ship are in this list as ordinary rows, because that is what they are: seeded once, then
 * the user's. "Night" can be loaded, dragged, saved over, renamed or deleted, and the built-ins can be put
 * back afterwards — which is the difference between a starting point and a menu.
 *
 * The row for the loaded preset carries the two actions that only make sense for it, rather than every row
 * carrying a menu: overwriting and renaming apply to the values on screen, and the values on screen belong
 * to one preset at a time.
 */
@Composable
private fun PresetsCard(
    state: ColorUiState,
    onLoad: (Long) -> Unit,
    onSaveAs: () -> Unit,
    onOverwrite: (Long) -> Unit,
    onRename: (ColorPreset) -> Unit,
    onDelete: (ColorPreset) -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Presets",
        subtitle = "${state.presets.size} saved",
        modifier = modifier,
        action = { TextButton(onClick = onSaveAs) { Text("Save as") } },
    ) {
        if (state.presets.isEmpty()) {
            Text(
                text = "No presets saved. The seven GameCore ships with can be put back below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.presets.forEach { preset ->
            NavRow(
                title = preset.name,
                onClick = { onLoad(preset.id) },
                description = preset.correction.summary,
                trailing = if (preset.id == state.activePresetId) "Loaded" else null,
                trailingTone = Tone.Accent,
            )
        }
        val loaded = state.activePreset
        RowDivider()
        ActionRow {
            TextButton(onClick = onRestore) { Text("Restore built-ins") }
            if (loaded != null) {
                TextButton(onClick = { onOverwrite(loaded.id) }) { Text("Update") }
                TextButton(onClick = { onRename(loaded) }) { Text("Rename") }
                TextButton(onClick = { onDelete(loaded) }) {
                    Text(text = "Delete", color = Tone.Danger.colour())
                }
            }
        }
        if (loaded == null && state.presets.isNotEmpty()) {
            Text(
                text = "These values belong to no preset. Load one to rename, update or delete it, or save " +
                    "these as a new one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A group of numeric fields, with a reset for the group.
 *
 * Generic over the field list rather than written out twice, because a field already carries everything a
 * slider needs — its label, its range, its unit and its own description — and a card that hard-codes three
 * of them is a card that has to be edited when a twelfth is added.
 */
@Composable
private fun FieldCard(
    title: String,
    subtitle: String,
    fields: List<ColorField>,
    state: ColorUiState,
    onValue: (ColorField, Int) -> Unit,
    onCommit: () -> Unit,
    onEdit: (ColorField) -> Unit,
    onResetFields: (List<ColorField>) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        action = {
            if (fields.any { it in state.correction.changedFields }) {
                TextButton(onClick = { onResetFields(fields) }) { Text("Reset") }
            }
        },
    ) {
        fields.forEach { field ->
            FieldSlider(
                field = field,
                state = state,
                onValue = onValue,
                onCommit = onCommit,
                onEdit = onEdit,
            )
        }
    }
}

/**
 * Gamma, as one slider or three, with the toggle that decides which.
 *
 * Both sets of values are kept whatever the toggle says, so a user who tries per-channel and goes back finds
 * the combined slider where they left it. Only the visible set is projected, which is the model's rule and
 * not this card's — [com.gamecore.core.model.ColorCorrection.gammaFields] is what both agree on.
 */
@Composable
private fun GammaCard(
    state: ColorUiState,
    onValue: (ColorField, Int) -> Unit,
    onCommit: () -> Unit,
    onEdit: (ColorField) -> Unit,
    onResetFields: (List<ColorField>) -> Unit,
    onMode: (GammaMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fields = state.correction.gammaFields
    SectionCard(
        title = "Gamma",
        subtitle = "The curve between black and white",
        modifier = modifier,
        action = {
            if (fields.any { it in state.correction.changedFields }) {
                TextButton(onClick = { onResetFields(fields) }) { Text("Reset") }
            }
        },
    ) {
        ChoiceRow(
            options = GammaMode.entries,
            selected = state.correction.gammaMode,
            onSelect = onMode,
            label = { it.label },
            perRow = 2,
        )
        SectionGap(8)
        fields.forEach { field ->
            FieldSlider(
                field = field,
                state = state,
                onValue = onValue,
                onCommit = onCommit,
                onEdit = onEdit,
            )
        }
        Text(
            text = "The set you are not looking at keeps its values, so switching back does not lose them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One field: a slider, a figure that can be tapped to type, and the reason it may do nothing here.
 *
 * The slider is never disabled by a limit. The value is real — it is stored, it goes into a preset, and a
 * preset travels to a device that may well honour it — so what an unreachable field needs is not a dead
 * control but a sentence, in the warning colour, directly under the one that produced it. That is the
 * difference between an app that says "this device cannot do that" and one that appears broken.
 */
@Composable
private fun FieldSlider(
    field: ColorField,
    state: ColorUiState,
    onValue: (ColorField, Int) -> Unit,
    onCommit: () -> Unit,
    onEdit: (ColorField) -> Unit,
) {
    val value = state.correction.valueOf(field)
    SliderRow(
        title = field.label,
        value = value,
        range = field.range,
        onValueChange = { onValue(field, it) },
        valueLabel = field.format(value),
        description = field.description,
        onValueChangeFinished = onCommit,
        onValueClick = { onEdit(field) },
    )
    state.limitFor(field)?.let { limit ->
        Text(
            text = limit,
            style = MaterialTheme.typography.bodySmall,
            color = Tone.Warning.colour(),
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

/**
 * The colour-vision filters and the inversion switch.
 *
 * Apart from the rest because they are the one part of this feature Android implements as a feature rather
 * than as something GameCore approximates: the platform's daltonizer runs in the compositor and applies
 * device-wide. They are also not sliders — a filter is on or it is not — so a chip row is the honest control.
 */
@Composable
private fun FilterCard(
    state: ColorUiState,
    onFilter: (ColorVisionFilter) -> Unit,
    onInvert: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "Colour vision",
        subtitle = state.correction.visionFilter.description,
        modifier = modifier,
    ) {
        ChoiceRow(
            options = ColorVisionFilter.entries,
            selected = state.correction.visionFilter,
            onSelect = onFilter,
            label = { it.label },
            perRow = 3,
        )
        RowDivider()
        SwitchRow(
            title = "Invert colours",
            checked = state.correction.invertColors,
            onCheckedChange = onInvert,
            description = "Android's own inversion, device-wide. It applies on top of everything above.",
        )
    }
}

/**
 * What an apply actually does on this device, setting by setting.
 *
 * The one card on the screen that exists because of §24's "never claim an unsupported API works". Android
 * has no public per-channel colour matrix an app can write, so GameCore projects what the user asked for
 * onto the keys that do exist — night display, the display colour mode, the daltonizer, reduce-bright-
 * colours. A user is entitled to know that their red gain became a colour temperature, and that a value with
 * no sink became nothing at all.
 */
@Composable
private fun ChangesCard(state: ColorUiState, modifier: Modifier = Modifier) {
    SectionCard(
        title = "What this changes",
        subtitle = "The settings these values are projected onto",
        modifier = modifier,
    ) {
        // Before the list, and whether or not there is a list: Android exposes no per-channel colour matrix
        // to an app, and a screen full of RGB sliders that did not say so would be claiming otherwise.
        Text(
            text = "Android gives apps no direct colour matrix, so GameCore projects these values onto the " +
                "display settings it can write. Everything it would write on this device is listed here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowDivider()
        if (state.correction.changesNothing) {
            Text(
                text = "Nothing is asked for yet, so nothing would be written.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        if (state.changes.isEmpty()) {
            NoteBanner(
                text = "Nothing in this correction can be applied on this device. The values are kept and " +
                    "each slider says why above.",
                tone = Tone.Warning,
                icon = Icons.Filled.Info,
            )
        }
        state.changes.forEachIndexed { index, change ->
            if (index > 0) RowDivider()
            Text(
                text = change.what,
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.Good.colour(),
            )
            Text(
                text = change.why,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!state.isFullyReachable && state.changes.isNotEmpty()) {
            SectionGap(8)
            NoteBanner(
                text = "Some of these values have no setting on this device to go to. They are stored and " +
                    "kept in any preset you save, and each one says so above.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
        }
    }
}

/**
 * "Reset to default", and what putting the display back actually means.
 *
 * The button clears the values *and* hands the display back, which is not the same as applying a neutral
 * correction: neutral writes would mean deciding what this device's night display and colour mode were
 * before GameCore touched them, and guessing wrong switches off a warm display the user set themselves.
 * Restoring puts back the reading taken before each write instead.
 *
 * Disabled when there is nothing to undo — no stored values, and nothing of GameCore's in force — because a
 * reset button on an already-neutral screen is §32's button that does nothing.
 */
@Composable
private fun ResetCard(state: ColorUiState, onReset: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Reset",
        subtitle = "Every value back to neutral",
        modifier = modifier,
    ) {
        Text(
            text = "GameCore puts each setting it changed back to the reading it took before changing it, " +
                "rather than writing neutral values over the top. A night display or colour mode you set " +
                "yourself is left where it was, and your saved presets are not touched.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionRow {
            TextButton(onClick = onReset, enabled = state.canReset && !state.isApplying) {
                Text(if (state.isApplying) "Working…" else "Reset everything")
            }
        }
        if (!state.canReset) {
            Text(
                text = "Nothing to reset: every value is neutral and GameCore is not holding a setting.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
/** The first-open state: the presets are being seeded and the access read is in flight. */
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
/**
 * Typing a field's value, for the positions a thumb cannot land on.
 *
 * Hue is 361 steps wide and a gain is 201, so "−12%" is a value a user can want and cannot reliably drag to.
 * The keyboard is asked for as [KeyboardType.Number], which on Android has no minus key — hence a button
 * that flips the sign, rather than a hint telling the user to type a character their keyboard does not offer.
 * Fields whose range starts at zero never show it.
 *
 * "Set" stays disabled until what is typed is a number inside the field's range. Clamping silently would
 * accept "999" and apply 100, which is not the value the user typed and confirmed.
 *
 * "Reset this channel" is here as well as on each card, because this dialog is where someone who has just
 * read the range is most likely to decide the field was better left alone.
 */
@Composable
private fun ValueDialog(
    field: ColorField,
    value: Int,
    onSet: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Keyed on the field alone: an apply settling behind the dialog must not retype the user's entry.
    var text by remember(field) { mutableStateOf(value.toString()) }
    val entered = text.trim().toIntOrNull()
    val isValid = entered != null && entered in field.range
    val isSigned = field.range.first < 0
    val isNegative = text.startsWith("-")
    // Sign included, so "-180" fits and a fifth character cannot be typed into a three-digit range.
    val maxChars = maxOf(field.range.first.toString().length, field.range.last.toString().length)
    val rangeText = "${field.format(field.range.first)} to ${field.format(field.range.last)}"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = field.label, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                TextFieldRow(
                    label = "Value",
                    value = text,
                    // Digits, plus a minus this keyboard cannot type and the button below can. Filtering
                    // here rather than validating later keeps what is on screen and what "Set" would use
                    // the same string.
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }
                        text = if (isSigned && raw.startsWith("-")) "-$digits" else digits
                    },
                    placeholder = value.toString(),
                    maxLength = maxChars,
                    description = "${field.description} Anything from $rangeText.",
                    keyboardType = KeyboardType.Number,
                )
                if (isSigned) {
                    ActionRow {
                        TextButton(
                            onClick = {
                                val digits = text.removePrefix("-")
                                text = if (isNegative) digits else "-$digits"
                            },
                        ) {
                            Text(if (isNegative) "Make it positive" else "Make it negative")
                        }
                    }
                }
                if (!isValid && text.isNotBlank() && text != "-") {
                    Text(
                        text = "That is outside $rangeText.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tone.Warning.colour(),
                    )
                }
                RowDivider()
                ActionRow {
                    TextButton(onClick = onReset) { Text("Reset this channel") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { entered?.let(onSet) }, enabled = isValid) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    )
}
/**
 * Naming a preset, for both saving a new one and renaming one that exists.
 *
 * One dialog for both because the difference between them is a title, a button word and a sentence, and two
 * copies of a text field would be two places to forget the length cap. Nothing is sanitised here: the
 * ViewModel cleans the name at the storage boundary (§24A.4), so the one copy of that rule sits next to the
 * write rather than in front of every caller. What this does enforce is the cap and the single line, because
 * [TextFieldRow] does, and a name that arrives 400 characters long has already been rendered in a chip over
 * a game before anything gets a chance to trim it.
 *
 * The confirm button is disabled on a blank name rather than quietly substituting the default, which would
 * be a button that did something other than what it said.
 */
@Composable
private fun NameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    message: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextFieldRow(
                    label = "Name",
                    value = name,
                    onValueChange = { name = it },
                    placeholder = ColorPreset.DEFAULT_NAME,
                    maxLength = ColorPreset.MAX_NAME_LENGTH,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    )
}
/**
 * The two hand-picked groups. Gamma's set is not here because the model decides it: it depends on which mode
 * the user is in, and [com.gamecore.core.model.ColorCorrection.gammaFields] is the one place that knows.
 */
private val GAIN_FIELDS = listOf(ColorField.RED_GAIN, ColorField.GREEN_GAIN, ColorField.BLUE_GAIN)

private val IMAGE_FIELDS = listOf(
    ColorField.SATURATION,
    ColorField.CONTRAST,
    ColorField.HUE,
    ColorField.BRIGHTNESS_OFFSET,
)






