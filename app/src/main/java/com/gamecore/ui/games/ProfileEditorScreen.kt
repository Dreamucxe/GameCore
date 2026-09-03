package com.gamecore.ui.games

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.unavailabilityText
import com.gamecore.core.model.AspectPreset
import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.DisplaySize
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.PerformanceMode
import com.gamecore.core.model.ScreenOrientationLock
import com.gamecore.ui.Destination
import com.gamecore.ui.components.ActionRow
import com.gamecore.ui.components.ChoiceRow
import com.gamecore.ui.components.ClickableCard
import com.gamecore.ui.components.ConfirmDialog
import com.gamecore.ui.components.EmptyState
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.PlainCard
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SliderRow
import com.gamecore.ui.components.StatusChip
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.TextFieldRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.colour

/**
 * One game's profile, field by field.
 *
 * Every control here writes a field that is nullable in [GameProfile], and null means *leave it alone*.
 * That distinction is the whole screen: a switch turns a setting from "GameCore does not touch this" into
 * "GameCore sets this and puts it back", and the slider underneath only exists in the second state. A
 * design where brightness always had a value would have every profile quietly seizing the brightness
 * slider of every game the user plays.
 *
 * Controls whose capability is missing are still offered, annotated with what is in the way. A profile is
 * an intention for later, and the user may be one settings screen away from making it work — but the
 * annotation is not optional, because the alternative is a profile that silently does four of its five
 * things.
 */
@Composable
fun ProfileEditorScreen(
    onBack: () -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var askingToDiscard by remember { mutableStateOf(false) }
    var askingToDelete by remember { mutableStateOf(false) }

    val leave: () -> Unit = {
        if (state.isDirty && state.confirmOnDiscard) askingToDiscard = true else onBack()
    }

    LaunchedEffect(state.isFinished) { if (state.isFinished) onBack() }

    BackHandler(enabled = state.isPickerOpen || state.isDirty) {
        if (state.isPickerOpen) viewModel.closePicker() else leave()
    }

    if (state.isPickerOpen) {
        AppPicker(
            state = state,
            onChoose = viewModel::choose,
            onSetShowSystemApps = viewModel::setShowSystemApps,
            onClose = if (state.profile == null) onBack else viewModel::closePicker,
            modifier = modifier,
        )
        return
    }

    val padded = Modifier.padding(horizontal = ScreenPadding)
    val profile = state.profile

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = profile?.label ?: "New profile",
                subtitle = profile?.packageName,
                onBack = leave,
                action = {
                    TextButton(onClick = viewModel::save, enabled = state.canSave && state.isDirty) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("Save")
                    }
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

        if (profile == null) {
            item {
                EmptyState(
                    icon = Icons.Filled.SportsEsports,
                    title = "Which game?",
                    message = "Pick the app this profile is for. Everything else on this screen " +
                        "applies to it and nothing else.",
                    actionLabel = "Choose an app",
                    onAction = viewModel::openPicker,
                )
            }
            return@LazyColumn
        }

        if (state.isMissingGame) {
            item {
                NoteBanner(
                    text = "This game is not installed at the moment, so nothing here will be " +
                        "applied until it is.",
                    tone = Tone.Muted,
                    icon = Icons.Filled.Info,
                    modifier = padded,
                )
            }
        }

        item { GameCard(state, viewModel::edit, viewModel::play, viewModel::openPicker, padded) }
        item { DisplaySection(state, viewModel::edit, onNavigate, padded) }
        item { AudioSection(state, viewModel::edit, padded) }
        item { OverlaySection(state, viewModel::edit, onNavigate, padded) }
        item { PerformanceSection(state, viewModel::edit, onNavigate, padded) }
        item { SessionSection(state, viewModel::edit, padded) }

        if (!state.isNew) {
            item {
                ActionRow {
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { askingToDelete = true },
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                    ) {
                        Text(text = "Delete this profile", color = Tone.Danger.colour())
                    }
                }
            }
        }
    }

    if (askingToDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            message = "The edits on this screen have not been saved.",
            confirmLabel = "Discard",
            dismissLabel = "Keep editing",
            onConfirm = {
                askingToDiscard = false
                onBack()
            },
            onDismiss = { askingToDiscard = false },
        )
    }

    if (askingToDelete) {
        ConfirmDialog(
            title = "Delete this profile?",
            message = "${profile?.label.orEmpty()}'s settings will be forgotten. Any session already " +
                "recorded for it is kept.",
            confirmLabel = "Delete",
            onConfirm = {
                askingToDelete = false
                viewModel.delete()
            },
            onDismiss = { askingToDelete = false },
        )
    }
}

/** The app this profile belongs to, a way to start it, and the master switch. */
@Composable
private fun GameCard(
    state: ProfileEditorUiState,
    onEdit: ((GameProfile) -> GameProfile) -> Unit,
    onPlay: () -> Unit,
    onChangeApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile ?: return
    PlainCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = profile.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Only when the game is on the device: a profile kept for an uninstalled game is worth
            // editing, and a play button for something that cannot start is the dead control §32 names.
            if (state.isGameInstalled) {
                IconButton(onClick = onPlay) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Start ${profile.label}",
                    )
                }
            }
            if (state.isNew) {
                TextButton(onClick = onChangeApp) { Text("Change") }
            }
        }
        RowDivider()
        SwitchRow(
            title = "Use this profile",
            checked = profile.isEnabled,
            onCheckedChange = { on -> onEdit { it.copy(isEnabled = on) } },
            description = "Turn off to leave the game alone without deleting any of this.",
        )
    }
}

/**
 * Refresh rate, brightness, orientation, screen timeout, and the colour preset the game runs under.
 *
 * The refresh-rate control lists only rates this panel actually reported, and says which mechanism will
 * be used to set them. §24B's MediaTek case is called out by name where the chipset is one of the known
 * unreliable ones: the standard API accepts the request and ignores it, so the profile says the change
 * will be verified and reported rather than assumed.
 *
 * The colour preset is here rather than with the overlays because it is not one: it changes the display
 * itself, for every app on screen, which is the same category as brightness and refresh rate.
 */
@Composable
private fun DisplaySection(
    state: ProfileEditorUiState,
    onEdit: ((GameProfile) -> GameProfile) -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile ?: return
    val capabilities = state.capabilities
    SectionCard(title = "Display", icon = Icons.Filled.PhoneAndroid, modifier = modifier) {
        Text(
            text = "Refresh rate",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (state.refreshRateChoices.isEmpty()) {
            NoteBanner(
                text = "This panel runs at one rate only, so there is nothing to choose.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
        } else {
            ChoiceRow(
                options = listOf<Float?>(null) + state.refreshRateChoices,
                selected = profile.targetRefreshRate,
                onSelect = { rate -> onEdit { it.copy(targetRefreshRate = rate) } },
                label = { rate -> if (rate == null) "Leave alone" else Formatters.hertz(rate) },
                perRow = 3,
            )
            Spacer(modifier = Modifier.height(6.dp))
            NoteBanner(
                text = if (capabilities.isKnownUnreliableRefreshChipset) {
                    "This chipset accepts a refresh-rate change and sometimes ignores it. GameCore " +
                        "reads the rate back afterwards and will tell you if it did not take."
                } else {
                    capabilities.refreshRateMechanism.explanation
                },
                tone = if (capabilities.isKnownUnreliableRefreshChipset) Tone.Warning else Tone.Muted,
                icon = Icons.Filled.Info,
                action = if (capabilities.wantsShizukuForRefresh) {
                    { TextButton(onClick = { onNavigate(Destination.Shizuku) }) { Text("Set up") } }
                } else {
                    null
                },
            )
        }

        RowDivider()
        DisplaySizeRows(state = state, profile = profile, onEdit = onEdit, onNavigate = onNavigate)

        RowDivider()
        OptionalPercent(
            title = "Brightness",
            value = profile.brightnessPercent,
            status = capabilities.brightnessControl,
            onChange = { percent -> onEdit { it.copy(brightnessPercent = percent) } },
        )

        RowDivider()
        Text(text = "Orientation", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(6.dp))
        ChoiceRow(
            options = listOf<ScreenOrientationLock?>(null) + ScreenOrientationLock.entries,
            selected = profile.rotationLock,
            onSelect = { lock -> onEdit { it.copy(rotationLock = lock) } },
            label = { lock ->
                when (lock) {
                    null -> "Leave alone"
                    ScreenOrientationLock.CURRENT -> "Lock as-is"
                    else -> lock.label
                }
            },
            perRow = 2,
        )
        capabilities.rotationControl.profileNote()?.let {
            Spacer(modifier = Modifier.height(6.dp))
            NoteBanner(text = it, tone = Tone.Muted, icon = Icons.Filled.Info)
        }

        RowDivider()
        Text(text = "Screen timeout", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(6.dp))
        ChoiceRow(
            options = listOf<Long?>(null) + TIMEOUT_CHOICES,
            selected = profile.screenTimeoutMillis,
            onSelect = { millis -> onEdit { it.copy(screenTimeoutMillis = millis) } },
            label = { millis ->
                if (millis == null) "Leave alone" else Formatters.durationCoarse(millis)
            },
            perRow = 3,
        )
        capabilities.screenTimeoutControl.profileNote()?.let {
            Spacer(modifier = Modifier.height(6.dp))
            NoteBanner(text = it, tone = Tone.Muted, icon = Icons.Filled.Info)
        }

        RowDivider()
        OptionPicker(
            title = "Colour correction",
            options = state.colourPresets,
            selectedId = profile.colorPresetId,
            onSelect = { id -> onEdit { it.copy(colorPresetId = id) } },
            emptyMessage = "No colour presets saved yet.",
            emptyActionLabel = "Make one",
            onEmptyAction = { onNavigate(Destination.Colour) },
            icon = Icons.Filled.Palette,
        )
        if (profile.colorPresetId != null) {
            Spacer(modifier = Modifier.height(6.dp))
            NoteBanner(
                text = "Applied to the whole display when this game starts, and put back when it closes. " +
                    "A device that will not let GameCore write display settings skips it, and the session " +
                    "report says so rather than claiming it worked.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
        }
    }
}

/**
 * The display's shape: a row of ratios, a size the user can type, and what either one will do.
 *
 * The most consequential control in this editor, and the copy is written accordingly. Three facts have to
 * survive the user's skim, and none of them are decoration:
 *
 *  - It **stretches**. A game handed a shorter logical display draws its scene into that shape and the
 *    compositor spreads the result over the panel. It is not a wider field of view, and the note says so in
 *    those words because "aspect ratio" is exactly the phrase people expect a wider view from.
 *  - It **outlives a reboot**. `wm size` is not a runtime setting, so the restore is the app's promise
 *    rather than the platform's — and the note points at the Home screen, where an outstanding change is
 *    listed until GameCore has actually put it back.
 *  - Only sizes **smaller than the panel** are offered. A larger logical display is supersampling, which
 *    costs frame rate and widens nothing; [DisplaySize.rejectionFor] refuses it with that sentence.
 *
 * The whole control is absent, with the reason, on a device whose panel size could not be read. Every shape
 * here is computed from that size and so is every rejection, so there is nothing to draw that would not be
 * a guess — see [ProfileEditorUiState.display].
 */
@Composable
private fun DisplaySizeRows(
    state: ProfileEditorUiState,
    profile: GameProfile,
    onEdit: ((GameProfile) -> GameProfile) -> Unit,
    onNavigate: (Destination) -> Unit,
) {
    Text(text = "Display size", style = MaterialTheme.typography.bodyLarge)
    Spacer(modifier = Modifier.height(6.dp))
    val physical = state.physicalSize
    if (physical == null) {
        NoteBanner(
            text = state.display.unavailabilityText() ?: DISPLAY_SIZE_UNREADABLE,
            tone = Tone.Muted,
            icon = Icons.Filled.Info,
            action = if (state.displayNeedsShizuku) {
                { TextButton(onClick = { onNavigate(Destination.Shizuku) }) { Text("Set up") } }
            } else {
                null
            },
        )
        return
    }

    // Turned to match the panel before it is compared to anything. A user who typed 1920 × 1080 on a
    // portrait phone meant the same display as 1080 × 1920, and a row that highlighted neither chip would
    // be the editor disagreeing with itself about what the profile holds.
    val chosen = profile.displaySize?.orientedLike(physical)
    val presetSizes = state.aspectChoices.map { it.size }
    val custom = chosen?.takeIf { size -> presetSizes.none { it.matches(size) } }
    ChoiceRow(
        options = listOf<DisplaySize?>(null) + presetSizes + listOfNotNull(custom),
        selected = chosen,
        onSelect = { size -> onEdit { it.copy(displaySize = size) } },
        label = { size ->
            if (size == null) "Leave alone" else AspectPreset.of(size, physical)?.label ?: "Custom"
        },
        perRow = 3,
    )
    Spacer(modifier = Modifier.height(6.dp))
    NoteBanner(text = DISPLAY_SIZE_STRETCH, tone = Tone.Muted, icon = Icons.Filled.AspectRatio)
    if (chosen != null) {
        Spacer(modifier = Modifier.height(6.dp))
        NoteBanner(
            text = "Sets this display to ${chosen.label} — ${chosen.aspectLabel} — while the game is " +
                "open, and puts it back when it closes. A size override outlives a reboot, so GameCore " +
                "records what it found first and the Home screen lists the change until it is undone.",
            tone = Tone.Muted,
            icon = Icons.Filled.Info,
        )
    }
    CustomSizeFields(physical = physical, chosen = chosen, onEdit = onEdit)
}

/**
 * Two numbers, for the shape none of the chips is.
 *
 * The text lives here rather than in the profile, and that is the whole point of the composable: a
 * half-typed width is not a size. Writing each keystroke through would put "1 × 1080" into the profile on
 * the way to "1080 × 1080" and leave it there if the user navigated away mid-number, and GameCore would
 * then refuse its own saved size at the next game launch. So the fields carry text, the profile only ever
 * receives a pair that passes [DisplaySize.rejectionFor], and a pair that does not is shown the reason.
 *
 * Keyed on the size the profile holds, so the fields and the chips stay one control: tapping 4:3 fills them
 * in, tapping "Leave alone" empties them, and a size typed here that resolves to a ratio lights that chip.
 *
 * Emptying both fields clears the profile's size. The alternative — ignoring an emptied pair — leaves the
 * chips claiming a size the fields no longer show, which is the editor lying about what it will do.
 */
@Composable
private fun CustomSizeFields(
    physical: DisplaySize,
    chosen: DisplaySize?,
    onEdit: ((GameProfile) -> GameProfile) -> Unit,
) {
    var width by remember(chosen) { mutableStateOf(chosen?.widthPixels?.toString() ?: "") }
    var height by remember(chosen) { mutableStateOf(chosen?.heightPixels?.toString() ?: "") }

    // Takes the text as arguments rather than reading the state, so it commits what was just typed instead
    // of what this composition was drawn with.
    fun commit(rawWidth: String, rawHeight: String) {
        if (rawWidth.isBlank() && rawHeight.isBlank()) {
            onEdit { it.copy(displaySize = null) }
            return
        }
        val size = DisplaySize.parse("${rawWidth}x$rawHeight")?.orientedLike(physical) ?: return
        if (size.rejectionFor(physical) == null) onEdit { it.copy(displaySize = size) }
    }

    val typed = DisplaySize.parse("${width}x$height")?.orientedLike(physical)
    val rejection = typed?.rejectionFor(physical)
    Spacer(modifier = Modifier.height(2.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextFieldRow(
            label = "Custom width",
            value = width,
            onValueChange = { text ->
                width = text.filter(Char::isDigit)
                commit(width, height)
            },
            placeholder = physical.widthPixels.toString(),
            maxLength = SIZE_FIELD_DIGITS,
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f),
        )
        TextFieldRow(
            label = "Custom height",
            value = height,
            onValueChange = { text ->
                height = text.filter(Char::isDigit)
                commit(width, height)
            },
            placeholder = physical.heightPixels.toString(),
            maxLength = SIZE_FIELD_DIGITS,
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f),
        )
    }
    NoteBanner(
        text = rejection ?: "This panel is ${physical.label}. Either way round means the same display — " +
            "GameCore turns the pair to match the panel. Leave both empty to leave the size alone.",
        tone = if (rejection == null) Tone.Muted else Tone.Warning,
        icon = Icons.Filled.Info,
    )
}

/**
 * A percentage a profile may or may not set.
 *
 * The switch is the null boundary and the slider only exists past it. Turning the switch on picks a
 * starting value rather than leaving the field at zero, because a profile that had just been asked to
 * control brightness and set it to 0% would black the screen out on the next game launch.
 */
@Composable
private fun OptionalPercent(
    title: String,
    value: Int?,
    status: CapabilityStatus,
    onChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    defaultPercent: Int = 60,
) {
    Column(modifier = modifier) {
        SwitchRow(
            title = title,
            checked = value != null,
            onCheckedChange = { on -> onChange(if (on) defaultPercent else null) },
            description = if (value == null) "Left as the device has it." else null,
        )
        if (value != null) {
            SliderRow(
                title = "Set to",
                value = value,
                range = 1..100,
                onValueChange = { onChange(it) },
                valueLabel = "$value%",
            )
        }
        status.profileNote()?.let {
            NoteBanner(text = it, tone = Tone.Muted, icon = Icons.Filled.Info)
        }
    }
}

/** Media volume and Do Not Disturb. */
@Composable
private fun AudioSection(
    state: ProfileEditorUiState,
    onEdit: ((GameProfile) -> GameProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile ?: return
    SectionCard(title = "Sound", icon = Icons.AutoMirrored.Filled.VolumeUp, modifier = modifier) {
        OptionalPercent(
            title = "Media volume",
            value = profile.mediaVolumePercent,
            status = state.capabilities.volumeControl,
            onChange = { percent -> onEdit { it.copy(mediaVolumePercent = percent) } },
            defaultPercent = 70,
        )
        RowDivider()
        SwitchRow(
            title = "Do not disturb",
            checked = profile.enableDoNotDisturb,
            onCheckedChange = { on -> onEdit { it.copy(enableDoNotDisturb = on) } },
            description = "Silences notifications while the game is running, and turns it off after.",
        )
        state.capabilities.doNotDisturbControl.profileNote()?.let {
            NoteBanner(text = it, tone = Tone.Muted, icon = Icons.Filled.Info)
        }
    }
}

/**
 * The three overlays, and which saved configuration each uses.
 *
 * The pickers list what the user has actually made. An empty list is not an empty dropdown: it is a
 * sentence and a link to the screen where layouts and presets are created, because a picker with nothing
 * in it reads as a broken control rather than as work not yet done.
 */
@Composable
private fun OverlaySection(
    state: ProfileEditorUiState,
    onEdit: ((GameProfile) -> GameProfile) -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile ?: return
    SectionCard(title = "Overlays", icon = Icons.Filled.Layers, modifier = modifier) {
        SwitchRow(
            title = "Floating button",
            checked = profile.showFloatingButton,
            onCheckedChange = { on -> onEdit { it.copy(showFloatingButton = on) } },
            description = "The draggable button that opens the control panel over the game.",
        )
        SwitchRow(
            title = "Stats pill",
            checked = profile.showPerformancePill,
            onCheckedChange = { on -> onEdit { it.copy(showPerformancePill = on) } },
            description = "The small live readout.",
        )
        SwitchRow(
            title = "Crosshair",
            checked = profile.showCrosshair,
            onCheckedChange = { on -> onEdit { it.copy(showCrosshair = on) } },
        )
        state.capabilities.overlay.profileNote()?.let {
            NoteBanner(text = it, tone = Tone.Muted, icon = Icons.Filled.Info)
        }

        if (profile.showCrosshair) {
            RowDivider()
            OptionPicker(
                title = "Crosshair",
                options = state.crosshairs,
                selectedId = profile.crosshairPresetId,
                onSelect = { id -> onEdit { it.copy(crosshairPresetId = id) } },
                emptyMessage = "No crosshairs saved yet.",
                emptyActionLabel = "Make one",
                onEmptyAction = { onNavigate(Destination.Crosshair) },
                icon = Icons.Filled.CenterFocusStrong,
            )
        }

        RowDivider()
        OptionPicker(
            title = "HUD layout",
            options = state.hudLayouts,
            selectedId = profile.hudLayoutId,
            onSelect = { id -> onEdit { it.copy(hudLayoutId = id) } },
            emptyMessage = "No HUD layouts saved yet.",
            emptyActionLabel = "Build one",
            onEmptyAction = { onNavigate(Destination.Hud) },
            icon = Icons.Filled.GridView,
        )
    }
}

/**
 * Picks one saved thing, or none.
 *
 * "None" is always an option and always first: a profile that shows the stats pill without a HUD layout
 * is a normal arrangement, not an incomplete one.
 */
@Composable
private fun OptionPicker(
    title: String,
    options: List<NamedOption>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    emptyMessage: String,
    emptyActionLabel: String,
    onEmptyAction: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            val chosen = options.firstOrNull { it.id == selectedId }
            if (chosen?.detail != null) StatusChip(text = chosen.detail, tone = Tone.Muted, icon = icon)
        }
        Spacer(modifier = Modifier.height(6.dp))
        if (options.isEmpty()) {
            NoteBanner(
                text = emptyMessage,
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
                action = { TextButton(onClick = onEmptyAction) { Text(emptyActionLabel) } },
            )
        } else {
            ChoiceRow(
                options = listOf<Long?>(null) + options.map { it.id },
                selected = selectedId,
                onSelect = onSelect,
                label = { id ->
                    if (id == null) "None" else options.first { it.id == id }.name
                },
                perRow = 2,
            )
        }
    }
}

/**
 * The performance mode, with what it actually does printed under it.
 *
 * [PerformanceMode.explanation] is rendered verbatim rather than summarised. Those sentences are where
 * §14's honesty requirement lives — "it does not raise CPU or GPU clocks — no app can" — and a screen
 * that paraphrased them into "optimises performance" would undo the whole point of writing them.
 */
@Composable
private fun PerformanceSection(
    state: ProfileEditorUiState,
    onEdit: ((GameProfile) -> GameProfile) -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile ?: return
    SectionCard(title = "Performance", icon = Icons.Filled.Bolt, modifier = modifier) {
        ChoiceRow(
            options = PerformanceMode.entries,
            selected = profile.performanceMode,
            onSelect = { mode -> onEdit { it.copy(performanceMode = mode) } },
            label = { it.label },
            perRow = 2,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = profile.performanceMode.explanation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowDivider()
        SwitchRow(
            title = "Use Shizuku where it helps",
            checked = profile.useShizukuOptimizations,
            onCheckedChange = { on -> onEdit { it.copy(useShizukuOptimizations = on) } },
            description = "For the settings Android will not let an ordinary app write. Skipped, not " +
                "failed, when Shizuku is not running.",
        )
        if (profile.useShizukuOptimizations && !state.capabilities.hasElevatedAccess) {
            NoteBanner(
                text = "Shizuku is not connected at the moment, so these will be skipped.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
                action = { TextButton(onClick = { onNavigate(Destination.Shizuku) }) { Text("Set up") } },
            )
        }
        if (profile.performanceMode.needsElevatedShellUsually &&
            !profile.useShizukuOptimizations &&
            !state.capabilities.hasElevatedAccess
        ) {
            NoteBanner(
                text = "${profile.performanceMode.label} usually needs the elevated shell to change " +
                    "anything. Without it, most of it will be skipped.",
                tone = Tone.Warning,
                icon = Icons.Filled.Info,
            )
        }
    }
}

/** Whether this game's play is recorded. */
@Composable
private fun SessionSection(
    state: ProfileEditorUiState,
    onEdit: ((GameProfile) -> GameProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile ?: return
    SectionCard(title = "Session", icon = Icons.Filled.History, modifier = modifier) {
        SwitchRow(
            title = "Record a session",
            checked = profile.trackSession,
            onCheckedChange = { on -> onEdit { it.copy(trackSession = on) } },
            description = "Length, battery used, and the CPU, memory and temperature readings taken " +
                "while you played. Stored on this device only.",
        )
    }
}

/**
 * The installed-app list, as a whole screen rather than a dialog.
 *
 * A device has hundreds of packages and this list is scrolled, not glanced at. Games sort to the top by
 * the platform's own category flag, and everything else follows alphabetically — the flag is a heuristic
 * and is used only to order, never to hide, because a game whose developer left the category unset is
 * still the app the user came here for.
 */
@Composable
private fun AppPicker(
    state: ProfileEditorUiState,
    onChoose: (AppOption) -> Unit,
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
                description = "Most games are not system apps. Off keeps the list short.",
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
            AppRow(app = app, onClick = { onChoose(app) }, modifier = padded)
        }
    }
}

@Composable
private fun AppRow(app: AppOption, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ClickableCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
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
            if (app.hasProfile) {
                StatusChip(text = "Has a profile", tone = Tone.Accent)
            } else if (app.isLikelyGame) {
                StatusChip(text = "Game", tone = Tone.Good, icon = Icons.Filled.SportsEsports)
            }
        }
    }
}

/**
 * What a display-size change actually does, in the two sentences it takes to be honest about it.
 *
 * The second sentence is the one that matters and the reason this is a constant rather than an inline
 * string: it is the same claim §3 requires the overlay's chip row to make, and the two should not be free
 * to drift apart into a strict version and a friendly one.
 */
private const val DISPLAY_SIZE_STRETCH =
    "This stretches the display: the game is handed a shorter screen and the picture is reshaped to fill " +
        "the panel. It is not a wider field of view, and no game sees more of the world because of it."

/** Said when `wm size` could not be read and the [Observed] reason came back empty. */
private const val DISPLAY_SIZE_UNREADABLE =
    "This display's size could not be read, so GameCore has nothing to compute shapes from."

/** Four digits reaches 9999, which is past every panel that has shipped and every one that will fit. */
private const val SIZE_FIELD_DIGITS = 4
