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
import com.gamecore.data.repository.SessionRepository
import com.gamecore.domain.cpu.CpuAffinityController
import com.gamecore.domain.display.DisplaySizeController
import com.gamecore.domain.gaming.ProfileSuggester
import com.gamecore.domain.network.PreLaunchNetworkCheck
import com.gamecore.domain.optimization.DeviceCapabilityChecker
import com.gamecore.ui.Destination
import com.gamecore.ui.components.PendingLaunch
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
    private val cpuAffinity: CpuAffinityController,
    private val preLaunchCheck: PreLaunchNetworkCheck,
    private val sessions: SessionRepository,
    preferences: SecurePreferenceStore,
) : ViewModel() {

    /** The package this editor was opened for, or [Destination.NEW_PROFILE] for a new one. */
    private val argument: String =
        savedState.get<String>(Destination.ARG_PACKAGE) ?: Destination.NEW_PROFILE

    /**
     * True when Home's §4 suggestion card sent the user here to review a derived starting profile.
     *
     * A nav argument, read the same way [argument] is. It only matters for a package with no saved
     * profile: when set, [load] fills the editor with [ProfileSuggester]'s draft instead of an empty form.
     * Read with a literal key rather than a [Destination] constant so this file compiles independently of
     * the route change that supplies it; the navArgument the route registers must be named exactly [SEED_ARG].
     */
    private val isSeeded: Boolean = savedState.get<Boolean>(SEED_ARG) ?: false

    private val editing = MutableStateFlow(
        ProfileEditorUiState(
            isNew = argument == Destination.NEW_PROFILE,
            confirmOnDiscard = preferences.settings.value.confirmBeforeDiscard,
        ),
    )

    val state: StateFlow<ProfileEditorUiState> = editing.asStateFlow()

    init {
        // Every block below reads `editing.value` *after* its suspending call and never inside the
        // `copy(...)` that consumes it. Kotlin evaluates a call's receiver before its arguments, so
        // `editing.value.copy(field = read())` captures the state as it was when the coroutine started,
        // suspends for the read, and then writes that stale snapshot back — and with seven of these running
        // at once, the slowest one reverts every field the faster ones had already filled in. The core
        // layout is the case that made it visible: it is a shell-free `/sys` read, so it always landed
        // first and was always overwritten by `wm size` answering later, and since nothing re-reads it the
        // section sat on "Measuring…" for the life of the screen.
        viewModelScope.launch { load() }
        viewModelScope.launch {
            val capabilities = capabilityChecker.current()
            editing.value = editing.value.copy(capabilities = capabilities)
        }
        // Its own launch and not part of the capability read, because it is a shell round trip on a screen
        // whose other fields are local: the editor draws with the display-size control absent and fills it
        // in when `wm size` answers, rather than holding the whole form back on the slowest read.
        viewModelScope.launch {
            val display = displaySize.state()
            editing.value = editing.value.copy(display = display)
        }
        // Its own launch again, and not folded into the one above even though both are reads of what the
        // device is: this one walks `/sys/devices/system/cpu` and the one above runs a shell command, so a
        // device where the shell is absent would otherwise hold the core layout behind a call that is
        // going to fail. They are unrelated questions and they fail for unrelated reasons.
        viewModelScope.launch {
            val layout = cpuAffinity.layout()
            editing.value = editing.value.copy(cpuLayout = layout)
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
        val installed = installedApps.isInstalled(argument)
        // A game with no saved profile that arrived through Home's suggestion card: fill the form with the
        // derived draft rather than the "no longer saved" message, which would be false — this game never
        // had a profile, and the point of the card was to offer it one.
        if (existing == null && isSeeded) {
            loadSuggested(installed)
            return
        }
        editing.value = editing.value.copy(
            isLoaded = true,
            isNew = existing == null,
            profile = existing,
            isGameInstalled = installed,
            message = if (existing == null) "That profile is no longer saved." else null,
        )
    }

    /**
     * Fills the editor with the §4 suggested starting profile for [argument].
     *
     * The draft is [ProfileSuggester]'s own output — a real [GameProfile] with only the measurement-backed
     * fields set — marked dirty and new so Save writes it and Back warns, exactly as though the user had
     * set those switches by hand. Nothing is saved or applied here; this is a draft for them to review.
     *
     * The suggestion is recomputed rather than carried from Home, so the editor reads the same measurements
     * from the same source and cannot show a field Home's card did not, or miss one it did. If the history
     * no longer supports a suggestion — a session deleted in the moment between the card and this screen —
     * the game falls back to an empty profile for that package rather than a stale or invented draft.
     */
    private suspend fun loadSuggested(installed: Boolean) {
        val forPackage = sessions.sessionsFor(argument).first()
        val label = forPackage.firstOrNull()?.gameLabel ?: argument
        val suggested = ProfileSuggester.suggest(
            packageName = argument,
            label = label,
            existingProfile = null,
            sessions = forPackage,
        )
        editing.value = editing.value.copy(
            isLoaded = true,
            isNew = true,
            profile = suggested?.profile ?: GameProfile.forGame(argument, label),
            isGameInstalled = installed,
            isDirty = true,
            message = if (suggested != null) SUGGESTION_NOTE else null,
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
     *
     * The §C4 network check measures the **draft**, not the saved profile: the switches the user is
     * looking at are what they mean by "this game", even before they save them.
     */
    fun play() {
        val profile = editing.value.profile ?: return
        viewModelScope.launch {
            val warning = preLaunchCheck.evaluate(profile)
            if (warning != null) {
                editing.value = editing.value.copy(
                    pendingLaunch = PendingLaunch(profile.packageName, warning.reason),
                )
                return@launch
            }
            start(profile.packageName)
        }
    }

    /** Starts the game, reporting only a refusal. Shared by [play] and the §C4 dialog's two launches. */
    private suspend fun start(packageName: String) {
        val outcome = launcher.launch(packageName)
        if (!outcome.isApplied) {
            editing.value = editing.value.copy(message = outcome.message)
        }
    }

    /** Launches the game the §C4 warning is holding, leaving every setting alone. */
    fun confirmPendingLaunch() {
        val pending = editing.value.pendingLaunch ?: return
        editing.value = editing.value.copy(pendingLaunch = null)
        viewModelScope.launch { start(pending.packageName) }
    }

    /**
     * Launches the game and stops this profile asking again.
     *
     * The one write this screen does outside [save], and it is kept to a single field on purpose: the
     * *saved* profile is re-read and only `networkPreLaunchWarn` is flipped on it, so the user's unsaved
     * draft is not written to the database behind their back. The draft is then brought into line without
     * being marked dirty — the flag really is saved, so claiming unsaved work would be false. A profile
     * that has never been saved has nothing to write to, so there the choice rides along in the draft and
     * is stored with it.
     */
    fun dontWarnPendingLaunch() {
        val pending = editing.value.pendingLaunch ?: return
        editing.value = editing.value.copy(pendingLaunch = null)
        viewModelScope.launch {
            val saved = profiles.profileFor(pending.packageName)
            if (saved != null) {
                profiles.save(saved.copy(networkPreLaunchWarn = false))
                editing.value.profile?.let { draft ->
                    editing.value = editing.value.copy(
                        profile = draft.copy(networkPreLaunchWarn = false),
                    )
                }
            } else {
                edit { it.copy(networkPreLaunchWarn = false) }
            }
            start(pending.packageName)
        }
    }

    /** Drops the held launch. Nothing starts and nothing is saved. */
    fun dismissPendingLaunch() {
        editing.value = editing.value.copy(pendingLaunch = null)
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

    private companion object {
        /**
         * The SavedStateHandle key carrying Home's "review this suggestion" flag.
         *
         * A literal, not a [Destination] constant, so this ViewModel compiles without the route change that
         * feeds it — but the navArgument the ProfileEditor route registers must use this exact name.
         */
        const val SEED_ARG = "seed"

        /** Says the form was filled from history and, like every other unsaved edit, is not yet applied. */
        const val SUGGESTION_NOTE = "Suggested from this game's recorded sessions. Nothing is applied " +
            "until you save it — change anything you like first, or leave the screen to discard it."
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
