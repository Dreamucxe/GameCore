package com.gamecore.ui.games

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.common.Formatters
import com.gamecore.core.model.ColorCorrection
import com.gamecore.core.model.ColorVisionFilter
import com.gamecore.core.model.GameProfile
import com.gamecore.core.system.AppLauncher
import com.gamecore.core.system.InstalledAppLister
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.ColorPresetRepository
import com.gamecore.data.repository.CrosshairRepository
import com.gamecore.data.repository.GameProfileRepository
import com.gamecore.data.repository.HudLayoutRepository
import com.gamecore.domain.display.DisplaySizeController
import com.gamecore.domain.optimization.DeviceCapabilityChecker
import com.gamecore.ui.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One profile, being edited.
 *
 * The edit is held here and written once, on save. A field-by-field autosave would be simpler and worse:
 * this profile may be the one currently applied to a running game, and rewriting it on every slider
 * movement would have the applier reading a half-finished configuration.
 *
 * A single [MutableStateFlow] rather than a `combine`, because everything on this screen is either the
 * draft itself or a list the draft points into — none of it is live, and a screen where a background
 * emission could replace what the user is typing is a screen that loses work.
 */
@HiltViewModel
class ProfileEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val profiles: GameProfileRepository,
    private val installedApps: InstalledAppLister,
    private val launcher: AppLauncher,
    private val hudLayouts: HudLayoutRepository,
    private val crosshairs: CrosshairRepository,
    private val colours: ColorPresetRepository,
    private val capabilityChecker: DeviceCapabilityChecker,
    private val displaySize: DisplaySizeController,
    preferences: SecurePreferenceStore,
) : ViewModel() {

    /** The package this editor was opened for, or [Destination.NEW_PROFILE] for a new one. */
    private val argument: String =
        savedState.get<String>(Destination.ARG_PACKAGE) ?: Destination.NEW_PROFILE

    private val editing = MutableStateFlow(
        ProfileEditorUiState(
            isNew = argument == Destination.NEW_PROFILE,
            confirmOnDiscard = preferences.settings.value.confirmBeforeDiscard,
        ),
    )

    val state: StateFlow<ProfileEditorUiState> = editing.asStateFlow()

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            editing.value = editing.value.copy(capabilities = capabilityChecker.current())
        }
        // Its own launch and not part of the capability read, because it is a shell round trip on a screen
        // whose other fields are local: the editor draws with the display-size control absent and fills it
        // in when `wm size` answers, rather than holding the whole form back on the slowest read.
        viewModelScope.launch {
            editing.value = editing.value.copy(display = displaySize.state())
        }
        viewModelScope.launch {
            val layouts = hudLayouts.layouts.first().map {
                NamedOption(
                    id = it.id,
                    name = it.name,
                    detail = Formatters.count(it.widgetCount, "stat"),
                )
            }
            editing.value = editing.value.copy(hudLayouts = layouts)
        }
        viewModelScope.launch {
            crosshairs.seedDefaultsIfEmpty()
            val presets = crosshairs.presets.first().map {
                NamedOption(id = it.id, name = it.name, detail = it.design.label)
            }
            editing.value = editing.value.copy(crosshairs = presets)
        }
        // Seeded here as well as on the colour screen, for the same reason the crosshairs are: a user who
        // reaches the profile editor first should be choosing between the seven built-ins, not reading
        // "nothing saved yet" about a feature that ships with presets.
        viewModelScope.launch {
            colours.seedDefaultsIfEmpty()
            val presets = colours.presets.first().map {
                NamedOption(id = it.id, name = it.name, detail = it.correction.optionDetail())
            }
            editing.value = editing.value.copy(colourPresets = presets)
        }
    }

    private suspend fun load() {
        if (argument == Destination.NEW_PROFILE) {
            editing.value = editing.value.copy(isLoaded = true)
            openPicker()
            return
        }
        val existing = profiles.profileFor(argument)
        editing.value = editing.value.copy(
            isLoaded = true,
            isNew = existing == null,
            profile = existing,
            isGameInstalled = installedApps.isInstalled(argument),
            message = if (existing == null) "That profile is no longer saved." else null,
        )
    }

    // -------------------------------------------------------------------------------- the app picker

    fun openPicker() {
        editing.value = editing.value.copy(isPickerOpen = true)
        if (editing.value.apps.isEmpty()) loadApps()
    }

    fun closePicker() {
        editing.value = editing.value.copy(isPickerOpen = false)
    }

    fun setShowSystemApps(show: Boolean) {
        editing.value = editing.value.copy(showSystemApps = show)
        loadApps()
    }

    /**
     * Lists what is installed, games first.
     *
     * Sorted rather than filtered by [com.gamecore.core.model.InstalledApp.isLikelyGame]: the category
     * flag is the developer's own declaration and plenty of games leave it unset, so filtering on it
     * would hide the very app the user came here to pick.
     */
    private fun loadApps() {
        editing.value = editing.value.copy(isLoadingApps = true)
        viewModelScope.launch {
            val taken = profiles.profiles.first().map { it.packageName }.toSet()
            val listed = installedApps.list(includeSystemApps = editing.value.showSystemApps)
                .map {
                    AppOption(
                        packageName = it.packageName,
                        label = it.label,
                        isLikelyGame = it.isLikelyGame,
                        hasProfile = it.packageName in taken,
                    )
                }
                .sortedWith(compareByDescending<AppOption> { it.isLikelyGame }.thenBy { it.label })
            editing.value = editing.value.copy(apps = listed, isLoadingApps = false)
        }
    }

    /**
     * Chooses the game this profile is for.
     *
     * Picking a package that already has a profile loads that profile rather than starting a second one
     * for the same game — the package is the identity, and two profiles for one game would leave the
     * applier picking one arbitrarily.
     */
    fun choose(option: AppOption) {
        viewModelScope.launch {
            val existing = profiles.profileFor(option.packageName)
            editing.value = editing.value.copy(
                isNew = existing == null,
                profile = existing ?: GameProfile.forGame(option.packageName, option.label),
                isGameInstalled = true,
                isPickerOpen = false,
                isDirty = existing == null,
                message = if (existing != null) {
                    "${option.label} already has a profile. You are editing that one."
                } else {
                    null
                },
            )
        }
    }

    // ------------------------------------------------------------------------------------- the edit

    /** Every field change goes through here, so "dirty" cannot be forgotten at one call site. */
    fun edit(transform: (GameProfile) -> GameProfile) {
        val current = editing.value.profile ?: return
        editing.value = editing.value.copy(profile = transform(current), isDirty = true)
    }

    fun save() {
        val profile = editing.value.profile ?: return
        if (editing.value.isSaving) return
        editing.value = editing.value.copy(isSaving = true)
        viewModelScope.launch {
            profiles.save(profile)
            editing.value = editing.value.copy(isSaving = false, isDirty = false, isFinished = true)
        }
    }

    /**
     * Starts the game being configured, leaving the editor where it is.
     *
     * Deliberately not a save-and-launch. Unsaved edits stay unsaved and the screen stays open behind the
     * game, so a user who tapped this to see what a setting looks like comes back to their work rather
     * than to a profile that was written on their behalf. Only a refusal is reported, for the reason
     * [GamesViewModel.play] gives.
     */
    fun play() {
        val profile = editing.value.profile ?: return
        viewModelScope.launch {
            val outcome = launcher.launch(profile.packageName)
            if (!outcome.isApplied) {
                editing.value = editing.value.copy(message = outcome.message)
            }
        }
    }

    fun delete() {
        val profile = editing.value.profile ?: return
        editing.value = editing.value.copy(isSaving = true)
        viewModelScope.launch {
            profiles.delete(profile.packageName)
            editing.value = editing.value.copy(isSaving = false, isDirty = false, isFinished = true)
        }
    }

    fun dismissMessage() {
        editing.value = editing.value.copy(message = null)
    }
}

/**
 * A colour preset in four words or fewer, for the chip beside the picker.
 *
 * Not [com.gamecore.core.model.ColorCorrection.summary], which names every changed value and is right on the
 * colour screen and far too long for a chip. The count comes first because it is what distinguishes two
 * presets in a list; the filter and the inversion are named only when they are the whole of what a preset
 * does, which is exactly the case for the three colour-vision built-ins.
 */
private fun ColorCorrection.optionDetail(): String = when {
    changedFields.isNotEmpty() -> Formatters.count(changedFields.size, "value")
    visionFilter != ColorVisionFilter.NONE -> visionFilter.label
    invertColors -> "Inverted"
    else -> "No change"
}
