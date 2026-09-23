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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
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
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.AspectPreset
import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.CpuAffinityPreset
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
import com.gamecore.ui.components.PreLaunchWarningDialog
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
        item { CpuSection(state, viewModel::edit, onNavigate, padded) }
        item { SmartFeaturesSection(state, viewModel::edit, onNavigate, padded) }
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

    // §C4. Shown when starting the game from this editor measured a poor connection and the draft asked
    // to be warned. "Don't warn for this game" is the one write this screen does outside Save, and it
    // writes a single field of the saved profile — never the unsaved draft.
    state.pendingLaunch?.let { pending ->
        PreLaunchWarningDialog(
            reason = pending.reason,
            onLaunchAnyway = viewModel::confirmPendingLaunch,
            onDontWarn = viewModel::dontWarnPendingLaunch,
            onCancel = viewModel::dismissPendingLaunch,
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
 *
 * "Free RAM on launch" sits here rather than in Settings because it is a per-game decision, and its
 * description names the apps that are never closed rather than promising a figure. It is the one switch
 * in this editor whose effect lands on the user's *other* apps, so what it will not touch is the part
 * worth reading before it goes on.
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
        RowDivider()
        SwitchRow(
            title = "Free RAM on launch",
            checked = profile.freeRamOnLaunch,
            onCheckedChange = { on -> onEdit { it.copy(freeRamOnLaunch = on) } },
            description = "Closes apps sitting in the background when this game starts, so it gets " +
                "the memory they were holding. Your launcher, keyboard, anything playing or recording, " +
                "and anything on your never-close list are left alone.",
        )
        if (profile.freeRamOnLaunch && !state.capabilities.hasElevatedAccess) {
            NoteBanner(
                text = "Without Shizuku, GameCore asks Android to close each app and Android does not " +
                    "say what it did — so the summary will say what it asked for rather than what it " +
                    "closed, and system apps stay out of reach either way.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
                action = { TextButton(onClick = { onNavigate(Destination.Shizuku) }) { Text("Set up") } },
            )
        }
    }
}

/**
 * Which CPU cores this game's process is allowed on, marked experimental and staying that way.
 *
 * The only section in this editor whose subject is the game's own process rather than a device setting, and
 * the only one that opens with its caveat instead of closing with it. [CpuAffinityPreset.HONESTY] is
 * rendered verbatim and above the chips deliberately: it is the sentence that says this does not make the
 * device faster and may make a given game worse, and a user who reads the chip labels and stops reading has
 * then read the wrong half.
 *
 * The chips are computed from the device's own core layout, never from a list of presets this app believes
 * in. A preset the layout cannot express — both of them on a CPU whose cores all report one ceiling, and
 * "all cores, minus one" on a layout with a single slow core — is named with the device's reason rather than
 * dropped, because a user comparing their phone to a friend's is owed the difference. And the whole control
 * is a single note on a device whose `cpufreq` files are out of app reach, which is most of them: there is
 * no chip to draw that would not be a guess about which cores are the fast ones, and no "Set up" button
 * beside it, because Shizuku does not make those files readable either.
 *
 * Shizuku *is* needed to write the mask, so the note about it appears once a preset is chosen — the same
 * rule the rest of this screen follows. A profile may hold a preset the device cannot act on today; that is
 * an intention, and [CapabilityStatus.profileNote] says why this editor does not prevent one.
 */
@Composable
private fun CpuSection(
    state: ProfileEditorUiState,
    onEdit: ((GameProfile) -> GameProfile) -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile ?: return
    SectionCard(
        title = "CPU cores",
        icon = Icons.Filled.Memory,
        modifier = modifier,
        action = { StatusChip(text = "Experimental", tone = Tone.Warning) },
    ) {
        NoteBanner(text = CpuAffinityPreset.HONESTY, tone = Tone.Warning, icon = Icons.Filled.Info)
        Spacer(modifier = Modifier.height(8.dp))

        val choices = state.cpuChoices
        if (choices.isEmpty()) {
            NoteBanner(
                text = state.cpuLayout.unavailabilityText() ?: CPU_LAYOUT_UNREADABLE,
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
            return@SectionCard
        }

        // A saved preset this device cannot express is still shown as the selected chip. Hiding it would
        // leave the row claiming "Leave to OS" while the profile holds something else — the editor
        // disagreeing with what it will save. The reason it will be skipped is printed below instead.
        val available = choices.filter { it.isAvailable }.map { it.preset }
        val chosen = profile.cpuAffinity
        val orphan = chosen?.takeIf { it !in available }
        ChoiceRow(
            options = listOf<CpuAffinityPreset?>(null) + available + listOfNotNull(orphan),
            selected = chosen,
            onSelect = { preset -> onEdit { it.copy(cpuAffinity = preset) } },
            label = { it?.label ?: "Leave to OS" },
            perRow = 2,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = chosen?.explanation ?: LEAVE_CORES_TO_OS,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (chosen != null) {
            Spacer(modifier = Modifier.height(6.dp))
            val cores = choices.first { it.preset == chosen }.coreIndices
            NoteBanner(
                text = if (cores.isEmpty()) {
                    "This device cannot express that grouping, so nothing will be changed while the " +
                        "game runs."
                } else {
                    "Restricts the game to ${Formatters.count(cores.size, "core")} of this device's " +
                        "${state.cpuLayout.valueOrNull?.coreCount ?: cores.size}, and puts the " +
                        "previous assignment back when it closes. The assignment belongs to the " +
                        "process, so a game GameCore does not get to finish with leaves nothing behind."
                },
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
            )
        }

        choices.filterNot { it.isAvailable }.forEach { choice ->
            val isChosen = choice.preset == chosen
            Spacer(modifier = Modifier.height(6.dp))
            NoteBanner(
                text = "\"${choice.preset.label}\" is not available on this device. " +
                    choice.unavailableBecause.orEmpty() +
                    if (isChosen) " This profile will skip it." else "",
                tone = if (isChosen) Tone.Warning else Tone.Muted,
                icon = Icons.Filled.Info,
            )
        }

        if (chosen != null && !state.capabilities.hasElevatedAccess) {
            Spacer(modifier = Modifier.height(6.dp))
            NoteBanner(
                text = "Android gives an app no way to change another process's cores, so this needs " +
                    "Shizuku running. Without it the setting stays saved and is skipped.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
                action = { TextButton(onClick = { onNavigate(Destination.Shizuku) }) { Text("Set up") } },
            )
        }
    }
}

/** Whether this game's play is recorded. */
/**
 * The 3.5 smart features (§B thermal auto-downshift, §C network check, §D full performance), each off by
 * default and each honest about what it needs.
 *
 * Thermal and full-performance need the same elevated shell the refresh-rate control does, so they carry
 * the same "needs Shizuku" note the Performance section uses, and full-performance states plainly what it
 * does not do — it removes battery saver's refresh-rate cap; it cannot push past hardware limits and does
 * not override thermal throttling. The network check needs no permission at all and says so. None of these
 * writes anything at apply time; they act only while the session runs.
 */
@Composable
private fun SmartFeaturesSection(
    state: ProfileEditorUiState,
    onEdit: ((GameProfile) -> GameProfile) -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile ?: return
    val elevated = state.capabilities.hasElevatedAccess
    val floorRates = state.refreshRateChoices
    SectionCard(title = "Smart features", icon = Icons.Filled.AutoAwesome, modifier = modifier) {
        // --- §B thermal auto-downshift ---
        SwitchRow(
            title = "Auto-cool when hot",
            checked = profile.thermalDownshiftEnabled,
            onCheckedChange = { on -> onEdit { it.copy(thermalDownshiftEnabled = on) } },
            description = "Steps the refresh rate down while the phone stays hot and back up as it " +
                "cools, never below the floor you set. It reports what it changed — it does not claim " +
                "to cool the device.",
            enabled = elevated || profile.thermalDownshiftEnabled,
        )
        if (profile.thermalDownshiftEnabled) {
            if (!elevated) {
                NoteBanner(
                    text = "Needs Shizuku running to change the refresh rate — it will be skipped until " +
                        "then.",
                    tone = Tone.Muted,
                    icon = Icons.Filled.Info,
                    action = { TextButton(onClick = { onNavigate(Destination.Shizuku) }) { Text("Set up") } },
                )
            }
            // Temperature limit, in whole °C (stored as tenths). 40–90 °C is the sane band §B2 asks for.
            val limitCelsius = (profile.thermalLimitDeciCelsius ?: DEFAULT_THERMAL_LIMIT_DECI) / 10
            SliderRow(
                title = "Cool above",
                value = limitCelsius,
                range = THERMAL_LIMIT_CELSIUS_RANGE,
                onValueChange = { c -> onEdit { it.copy(thermalLimitDeciCelsius = c * 10) } },
                valueLabel = "$limitCelsius°C",
                description = "The auto-cool kicks in once the device stays at or above this.",
            )
            if (floorRates.isNotEmpty()) {
                Text(text = "Never below", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(6.dp))
                ChoiceRow(
                    options = floorRates,
                    selected = profile.thermalFloorRateHz ?: floorRates.minOrNull(),
                    onSelect = { hz -> onEdit { it.copy(thermalFloorRateHz = hz) } },
                    label = { Formatters.hertz(it) },
                    perRow = 3,
                )
            }
        }

        RowDivider()
        // --- §C network check (no permission needed) ---
        SwitchRow(
            title = "Check the network",
            checked = profile.networkCheckEnabled,
            onCheckedChange = { on -> onEdit { it.copy(networkCheckEnabled = on) } },
            description = "Watches latency, jitter and loss to the host you already set, and can warn " +
                "before you launch. No new permission, and it never reads your Wi-Fi name.",
        )
        if (profile.networkCheckEnabled) {
            SwitchRow(
                title = "Warn before launch on a poor connection",
                checked = profile.networkPreLaunchWarn,
                onCheckedChange = { on -> onEdit { it.copy(networkPreLaunchWarn = on) } },
                description = "A quick check when you tap Play. It never blocks a launch, and it cannot " +
                    "check a game you start from outside GameCore.",
            )
            SwitchRow(
                title = "Alert during a session",
                checked = profile.networkAlertsEnabled,
                onCheckedChange = { on -> onEdit { it.copy(networkAlertsEnabled = on) } },
                description = "A notification when the connection turns poor mid-game, at most once a minute.",
            )
        }

        RowDivider()
        // --- §D full performance under battery saver ---
        SwitchRow(
            title = "Keep full performance",
            checked = profile.fullPerformanceEnabled,
            onCheckedChange = { on -> onEdit { it.copy(fullPerformanceEnabled = on) } },
            description = "Turns Android's battery saver off for this game so it stops capping the " +
                "refresh rate. It cannot push past your hardware's limits and does not override thermal " +
                "throttling — heat protection always wins.",
            enabled = elevated || profile.fullPerformanceEnabled,
        )
        if (profile.fullPerformanceEnabled && !elevated) {
            NoteBanner(
                text = "Needs Shizuku running to change the battery-saver setting — it will be skipped " +
                    "until then.",
                tone = Tone.Muted,
                icon = Icons.Filled.Info,
                action = { TextButton(onClick = { onNavigate(Destination.Shizuku) }) { Text("Set up") } },
            )
        }
    }
}

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

/**
 * Said when the core layout could not be derived and the [com.gamecore.core.common.Observed] reason came
 * back empty.
 *
 * Rarely reached — the reason is almost always present and is almost always that `cpufreq` is not readable
 * — but the sentence still names the file rather than saying "unavailable", because a user who wants to
 * know why has something to search for.
 */
private const val CPU_LAYOUT_UNREADABLE =
    "This device's per-core maximum frequencies could not be read, so GameCore cannot tell its faster " +
        "cores from its slower ones."

/** Printed under the chips when no preset is chosen, where a preset's own explanation would be. */
private const val LEAVE_CORES_TO_OS =
    "Changes nothing. Android's scheduler moves the game between cores as it sees fit, which is what it " +
        "is tuned to do and is right more often than not."
