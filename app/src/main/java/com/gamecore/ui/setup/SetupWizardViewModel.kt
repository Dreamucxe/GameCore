package com.gamecore.ui.setup

import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.BuildConfig
import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.DeviceCapabilities
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.PerformanceMode
import com.gamecore.core.model.RefreshRateMechanism
import com.gamecore.core.permissions.GamePermission
import com.gamecore.core.permissions.PermissionChecker
import com.gamecore.core.shizuku.ShizukuManager
import com.gamecore.core.system.InstalledAppLister
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.GameProfileRepository
import com.gamecore.domain.optimization.DeviceCapabilityChecker
import com.gamecore.domain.setup.PermissionNeed
import com.gamecore.domain.setup.PresetSupport
import com.gamecore.domain.setup.SetupPreset
import com.gamecore.domain.setup.WizardFeatureChoices
import com.gamecore.domain.setup.WizardStep
import com.gamecore.domain.setup.nextStep
import com.gamecore.domain.setup.presetSummary
import com.gamecore.domain.setup.previousStep
import com.gamecore.domain.setup.requiredPermissionSteps
import com.gamecore.domain.setup.resumeAt
import com.gamecore.domain.setup.setupChoices
import com.gamecore.domain.setup.setupStep
import com.gamecore.domain.setup.visibleSteps
import com.gamecore.ui.games.AppOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The first-run wizard's brain (spec §A1/§A2).
 *
 * Every decision about *what the wizard does* is delegated to [com.gamecore.domain.setup.SetupWizardLogic]
 * — [visibleSteps], [nextStep], [previousStep], [resumeAt], [requiredPermissionSteps], [presetSummary].
 * This class exists to do the three things a pure function cannot: ask Android what is actually granted,
 * persist where the user got to, and write the profile at the end. When a method here looks like it is
 * deciding something, check again — it is almost always translating a device fact into the engine's
 * vocabulary and handing it over.
 *
 * A single [MutableStateFlow] rather than a `combine` of the live sources, which is the opposite of
 * [com.gamecore.ui.shizuku.ShizukuViewModel] and deliberate. The wizard is a *draft* in exactly the sense
 * [com.gamecore.ui.games.ProfileEditorViewModel] is: the user's ticks, their picked game and their preset
 * are work in progress, and a background emission from the capability checker arriving mid-flow and
 * replacing the state object would discard it. The facts that genuinely change under the user — a
 * permission granted in Settings, Shizuku's service being started in another app — are re-read by
 * [onResume], which is the only correct trigger anyway: Android gives an app no callback for the user
 * coming back from a Settings page.
 *
 * Nothing here touches Room. The wizard's own state lives in [SecurePreferenceStore] (§A2's "current step
 * saved in the settings store"); the one database write in the whole flow is the profile the user opts
 * into on step 5, through the repository that already owns profiles.
 */
@HiltViewModel
class SetupWizardViewModel @Inject constructor(
    private val preferences: SecurePreferenceStore,
    private val permissions: PermissionChecker,
    private val shizuku: ShizukuManager,
    private val capabilityChecker: DeviceCapabilityChecker,
    private val installedApps: InstalledAppLister,
    private val profiles: GameProfileRepository,
) : ViewModel() {

    private val editing = MutableStateFlow(SetupWizardUiState())

    val state: StateFlow<SetupWizardUiState> = editing.asStateFlow()

    /**
     * Restores where the user was, then asks the device what is true.
     *
     * The order matters. The saved choices are read first because they decide which steps exist, and
     * [resumeAt] can only be asked about a step once [visibleSteps] is known — restoring the step before
     * the choices would resolve it against the wrong list and drop the user back to Welcome. Both are read
     * through [SecurePreferenceStore.preload] on an IO dispatcher first, so a cold start does not pay the
     * Keystore cost on the frame that draws step 1.
     */
    init {
        viewModelScope.launch {
            preferences.preload()
            val choices = preferences.setupChoices
            val saved = preferences.setupStep
            editing.value = editing.value.copy(
                choices = choices,
                step = resumeAt(saved, visibleSteps(choices)),
                isLoaded = true,
                isPersisting = preferences.isPersisting,
                isApi33OrHigher = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                smart = smartStatesFor(DeviceCapabilities.UNKNOWN),
            )
            recheck()
        }
    }

    // ------------------------------------------------------------------------------------ navigation

    /**
     * Moves to the next visible step, or finishes.
     *
     * The step is written to the store on every move rather than only on exit, because §A2 asks for the
     * position to survive *process death* and not merely a rotation — a process that is killed in the
     * background never gets an exit callback to write it in.
     */
    fun next() {
        val current = editing.value
        if (!current.canAdvanceNow) return
        if (current.isLastStep) {
            finish()
            return
        }
        goTo(nextStep(current.step, current.steps))
    }

    /**
     * Back, which is the same movement as [next] in the other direction and not a navigation pop.
     *
     * Deliberately not `NavController.popBackStack`: the wizard is one destination with seven states, so
     * there is nothing on the back stack between steps. Making each step a destination would put the
     * user's ticks behind a saved-state handle for no gain, and the system back gesture is routed to this
     * by the screen's own `BackHandler` so the two agree.
     */
    fun back() {
        val current = editing.value
        goTo(previousStep(current.step, current.steps))
    }

    /**
     * Skip: the same as [next], and named differently on purpose.
     *
     * Behaviourally identical because nothing on a step is mandatory — §A2's "each skippable" means the
     * forward button never blocks, so a separate Skip path would be dead code. What the second button buys
     * is the user knowing they are allowed to move on without acting, which a lone "Next" under an ungranted
     * permission does not say.
     */
    fun skip() = next()

    private fun goTo(step: WizardStep) {
        if (step == editing.value.step) return
        editing.value = editing.value.copy(step = step)
        preferences.setupStep = step
        // Landing on one of the two steps that report a device fact re-asks for it, so a user who granted
        // something on an earlier step and then walked forward sees the new answer rather than the one
        // read when the wizard opened.
        if (step == WizardStep.PERMISSIONS || step == WizardStep.SHIZUKU) recheck()
    }

    // -------------------------------------------------------------------------------- the feature picker

    /**
     * Ticks or unticks one feature, and re-derives everything downstream of it.
     *
     * Un-ticking is the interesting case. Dropping the overlay removes a permission step; dropping the last
     * Shizuku-needing feature removes the whole Shizuku step. [resumeAt] is re-applied for exactly that
     * reason — if the user is somehow standing on a step that just stopped existing, it moves them rather
     * than leaving them on a screen with no position in the bar.
     */
    fun setFeature(feature: WizardFeature, picked: Boolean) {
        val current = editing.value
        val choices = feature.applyTo(current.choices, picked)
        val steps = visibleSteps(choices)
        editing.value = current.copy(
            choices = choices,
            step = resumeAt(current.step, steps),
            permissionRows = permissionRowsFor(choices, current.isApi33OrHigher),
            smart = smartStatesFor(capabilityChecker.capabilities.value),
        )
        preferences.setupChoices = choices
    }

    // ---------------------------------------------------------------------------------- the re-check

    /**
     * Re-asks Android and Shizuku for everything the wizard reports, and rebuilds the rows from the answer.
     *
     * Called on every `ON_RESUME` by the screen (§A2 step 3's "re-check on resume through the real API")
     * and after any action that could have changed a grant. There is no caching layer in front of it:
     * [PermissionChecker] re-queries the platform on every call by design, and [DeviceCapabilityChecker]
     * is invalidated first so a Shizuku connection made since the last check is actually reflected rather
     * than served from its cache.
     */
    fun onResume() = recheck()

    fun recheck() {
        viewModelScope.launch {
            shizuku.refresh()
            capabilityChecker.invalidate()
            val capabilities = capabilityChecker.refresh()
            val current = editing.value
            editing.value = current.copy(
                permissionRows = permissionRowsFor(current.choices, current.isApi33OrHigher),
                shizuku = ShizukuSummary(
                    state = shizuku.state.value,
                    isInstalled = shizuku.state.value.isInstalled,
                    usableControls = capabilities.usableControlCount,
                    totalControls = capabilities.controlCount,
                ),
                presetViews = presetViewsFor(capabilities),
                smart = smartStatesFor(capabilities),
            )
        }
    }

    /**
     * One row per needed permission, each carrying the platform's current answer.
     *
     * [requiredPermissionSteps] decides *which* rows exist; this only fills in the state. The two arguments
     * it takes are the wizard's own reading of the situation and are worth stating: notifications are
     * offered when a picked feature will run a foreground service — the overlay and automatic profiles both
     * do — because that service is required to post a notification, and asking for the permission when
     * nothing will post is asking for something GameCore does not need. The API check is handed in rather
     * than read inside the engine, which has no `android.*` import by construction.
     */
    private fun permissionRowsFor(
        choices: WizardFeatureChoices,
        isApi33OrHigher: Boolean,
    ): List<PermissionRow> = requiredPermissionSteps(
        choices = choices,
        wantsNotifications = choices.overlay || choices.autoProfiles,
        isApi33OrHigher = isApi33OrHigher,
    ).map { need ->
        val catalogue = catalogueEntryFor(need)
        PermissionRow(
            need = need,
            catalogue = catalogue,
            isGranted = isGranted(need, catalogue),
        )
    }

    /**
     * Whether one need is satisfied right now.
     *
     * Routed through [PermissionChecker.isGranted] for everything with a catalogue entry, so the wizard and
     * the permissions centre can never disagree about what "granted" means — that class is explicit that
     * usage access is an app-op and not the manifest permission, and a second opinion here would be the bug
     * it exists to prevent. The one need with no catalogue entry is Shizuku, which is answered by the shell
     * being usable rather than by any permission at all.
     */
    private fun isGranted(need: PermissionNeed, catalogue: GamePermission?): Boolean = when {
        catalogue != null -> permissions.isGranted(catalogue)
        need == PermissionNeed.SHIZUKU -> shizuku.state.value.isUsable
        else -> false
    }

    // ------------------------------------------------------------------------- opening the right page

    /**
     * The exact Settings page for one permission, for this package (§A2 step 3's "Grant" button).
     *
     * Straight from [PermissionChecker.settingsIntentFor], which resolves every intent before returning it
     * and falls back to GameCore's own app-details page — so this never hands the screen something
     * `startActivity` will throw on. Null only for the rows that have no page: Shizuku, and a permission
     * granted at install.
     */
    fun settingsIntentFor(row: PermissionRow): Intent? =
        row.catalogue?.let { permissions.settingsIntentFor(it) }

    /** Opens Shizuku if it is installed; null when it is not, which is what makes the button absent. */
    fun shizukuLaunchIntent(): Intent? = shizuku.managerLaunchIntent()

    /**
     * Asks Shizuku for permission from inside the wizard, then re-reads rather than trusting the result.
     *
     * The re-read is the same point [com.gamecore.ui.shizuku.ShizukuViewModel.requestPermission] makes:
     * "the user pressed allow" and "the binder works" are different facts, and a device resuming from a
     * reboot produces the first without the second.
     */
    fun requestShizukuPermission() {
        viewModelScope.launch {
            shizuku.requestPermission()
            recheck()
        }
    }

    /** The screen calls this when `startActivity` threw, so a dead button says so instead of doing nothing. */
    fun onIntentFailed() {
        editing.value = editing.value.copy(message = INTENT_FAILED)
    }

    // ------------------------------------------------------------------------------- step 5: the game

    fun openPicker() {
        editing.value = editing.value.copy(isPickerOpen = true)
        if (editing.value.apps.isEmpty()) loadApps()
    }

    fun closePicker() {
        editing.value = editing.value.copy(isPickerOpen = false)
    }

    /**
     * Lists what is installed, games first.
     *
     * The same call and the same sort as [com.gamecore.ui.games.ProfileEditorViewModel]'s picker, because
     * §A2 step 5 says to reuse it: sorted by the developer's own game flag rather than filtered on it,
     * since plenty of games leave the flag unset and filtering would hide the app the user came to pick.
     * System apps are never included here — the wizard is asking for a game, and a first-run screen is the
     * worst place to offer six hundred packages.
     */
    private fun loadApps() {
        editing.value = editing.value.copy(isLoadingApps = true)
        viewModelScope.launch {
            val taken = profiles.profiles.first().map { it.packageName }.toSet()
            val listed = installedApps.list(includeSystemApps = false)
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

    fun choose(option: AppOption) {
        editing.value = editing.value.copy(pickedGame = option, isPickerOpen = false)
    }

    /** Clears the pick, which puts step 5 back to its skippable state rather than deleting anything. */
    fun clearGame() {
        editing.value = editing.value.copy(pickedGame = null, preset = null)
    }

    fun choosePreset(preset: SetupPreset) {
        editing.value = editing.value.copy(preset = preset)
    }

    /**
     * The three preset summaries, built from what this device supports.
     *
     * [PresetSupport] is where the device's real answer enters the engine, and each flag is a fact rather
     * than an assumption:
     *  - refresh rate follows [RefreshRateMechanism], which is [RefreshRateMechanism.NONE] exactly when the
     *    panel has fewer than two rates or the keys cannot be written. It is the same gate the refresh-rate
     *    control itself uses, so the preset cannot promise something the profile would then skip.
     *  - thermal downshift rides on the same mechanism, because downshifting *is* writing a lower rate.
     *    A device that cannot be pinned cannot be stepped down either.
     *  - Do Not Disturb is the notification-policy grant, asked of [PermissionChecker] at this moment.
     *  - animation scale is its own capability and is the one most often absent, since the keys are
     *    `Settings.Global` and need the elevated shell.
     *
     * Everything unsupported comes back as [com.gamecore.domain.setup.PresetLine.NotAvailable] with the
     * engine's reason attached, which is what the screen prints. Nothing is dropped and nothing is guessed.
     */
    private fun presetViewsFor(capabilities: DeviceCapabilities): List<PresetChoiceView> {
        val canWriteRate = capabilities.refreshRateMechanism != RefreshRateMechanism.NONE
        val support = PresetSupport(
            refreshRate = canWriteRate,
            thermalDownshift = canWriteRate,
            dnd = permissions.hasNotificationPolicyAccess(),
            animationScale = capabilities.animationScaleControl == CapabilityStatus.AVAILABLE,
        )
        return SetupPreset.entries.map { preset ->
            PresetChoiceView(preset = preset, lines = presetSummary(preset, support))
        }
    }

    // -------------------------------------------------------------------------- step 6: smart features

    fun setSmartThermal(enabled: Boolean) {
        editing.value = editing.value.copy(smart = editing.value.smart.copy(thermalDownshift = enabled))
    }

    fun setSmartNetwork(enabled: Boolean) {
        editing.value = editing.value.copy(smart = editing.value.smart.copy(networkCheck = enabled))
    }

    fun setSmartFullPerformance(enabled: Boolean) {
        editing.value = editing.value.copy(smart = editing.value.smart.copy(fullPerformance = enabled))
    }

    /**
     * Step 6's switch states, with a reason attached to each one this device cannot honour.
     *
     * Defaults are off and stay off — [SmartFeatureStates] never turns anything on here, it only decides
     * what may be turned on. A switch whose reason is non-null is drawn disabled *with the sentence beside
     * it*, which is §A2 step 6's actual requirement: "disabled with the reason if Shizuku is missing" is
     * not satisfied by a grey switch.
     *
     * The reasons come from the device's own answer rather than from "is Shizuku running", because those
     * are not the same question. [DeviceCapabilities.batterySaverControl] already distinguishes
     * [CapabilityStatus.REQUIRES_SHIZUKU] — a missing shell, which the user can fix — from
     * [CapabilityStatus.UNSUPPORTED], which they cannot, and telling a user to install Shizuku for a
     * control their build does not expose would be a wasted trip.
     *
     * The network check is never blocked. It needs no permission and no elevated shell — reading the link
     * type is an ordinary `ConnectivityManager` call, and the optional latency probe is a socket any app
     * may open.
     */
    private fun smartStatesFor(capabilities: DeviceCapabilities): SmartFeatureStates {
        val current = editing.value.smart
        val thermalReason = when {
            capabilities.refreshRateMechanism == RefreshRateMechanism.NONE && capabilities.shizuku.isUsable ->
                "This display reports one refresh rate, so there is nothing to step down to."
            !capabilities.shizuku.isUsable ->
                "Needs Shizuku running. GameCore can read the temperature without it, but not change the " +
                    "refresh rate in response."
            else -> null
        }
        val fullPerformanceReason = when (capabilities.batterySaverControl) {
            CapabilityStatus.AVAILABLE -> null
            CapabilityStatus.REQUIRES_SHIZUKU ->
                "Needs Shizuku running. Android's battery saver cannot be switched off by an ordinary app."
            CapabilityStatus.REQUIRES_PERMISSION ->
                "Needs a permission GameCore does not have yet."
            CapabilityStatus.UNSUPPORTED ->
                "This build does not expose the battery-saver setting, so GameCore cannot turn it off."
        }
        return SmartFeatureStates(
            // A switch the device cannot honour is forced off rather than left on from a previous state:
            // the user may have turned it on while Shizuku was connected and then stopped the service.
            thermalDownshift = current.thermalDownshift && thermalReason == null,
            networkCheck = current.networkCheck,
            fullPerformance = current.fullPerformance && fullPerformanceReason == null,
            thermalBlockedReason = thermalReason,
            fullPerformanceBlockedReason = fullPerformanceReason,
        )
    }

    // ------------------------------------------------------------------------------------ finishing

    /**
     * Writes the profile the user opted into, records the completion, and lets the screen leave.
     *
     * The profile is saved only when a game was picked — step 5 is skippable, and finishing without one is
     * a complete, successful setup rather than a half-done one. The completion flag is written either way:
     * the wizard asks once, and a user who skipped every step has still answered the question it was asking.
     *
     * The version is recorded rather than a bare boolean, which is what makes §A1's upgrade case work at
     * all: a later release can offer the Home card by comparing what was completed against what is running,
     * without a second flag that has to be reset by hand.
     */
    fun finish() {
        if (editing.value.isSaving) return
        editing.value = editing.value.copy(isSaving = true)
        viewModelScope.launch {
            val picked = editing.value.pickedGame
            if (picked != null) {
                profiles.save(buildProfile(picked))
            }
            markCompleted()
            editing.value = editing.value.copy(isSaving = false, isFinished = true)
        }
    }

    /**
     * The profile step 5 and step 6 describe, built from fields that already exist.
     *
     * Every value below is a field on [GameProfile] today and the preset maps onto [PerformanceMode], which
     * is the app's own existing vocabulary for the same three intentions — so the profile the wizard writes
     * is indistinguishable from one made by hand in the editor, and is editable there immediately.
     *
     * The refresh rate is the only value that reads the device: "highest" and "60 Hz" are meaningless as
     * literals, so the highest is the panel's own maximum and the lowest is its own minimum. When the panel
     * reports one rate, the field stays null — writing a target equal to the only rate would be a setting
     * that claims to do something and cannot.
     */
    private fun buildProfile(option: AppOption): GameProfile {
        val capabilities = capabilityChecker.capabilities.value
        val rates = capabilities.supportedRefreshRates
        val smart = editing.value.smart
        val base = GameProfile.forGame(option.packageName, option.label)
        return base.copy(
            performanceMode = when (editing.value.preset) {
                SetupPreset.MAX_PERFORMANCE -> PerformanceMode.PERFORMANCE
                SetupPreset.BATTERY_SAVER -> PerformanceMode.BATTERY_SAVER
                SetupPreset.BALANCED -> PerformanceMode.BALANCED
                null -> base.performanceMode
            },
            targetRefreshRate = when (editing.value.preset) {
                SetupPreset.MAX_PERFORMANCE -> rates.maxOrNull().takeIf { rates.size > 1 }
                SetupPreset.BATTERY_SAVER -> rates.minOrNull().takeIf { rates.size > 1 }
                else -> base.targetRefreshRate
            },
            enableDoNotDisturb = editing.value.choices.dnd && permissions.hasNotificationPolicyAccess(),
            showPerformancePill = editing.value.choices.overlay,
            showFloatingButton = editing.value.choices.overlay,
            thermalDownshiftEnabled = smart.thermalDownshift,
            networkCheckEnabled = smart.networkCheck,
            fullPerformanceEnabled = smart.fullPerformance,
        )
    }

    /**
     * Records that the wizard ran to completion at this version.
     *
     * [BuildConfig.VERSION_CODE] rather than the version name, because it is the monotonic one — a name is
     * a marketing string that can go backwards, and [com.gamecore.domain.setup.decideEntry] compares with
     * `>=`.
     */
    private fun markCompleted() {
        preferences.updateSettings { it.copy(setupCompletedVersion = BuildConfig.VERSION_CODE) }
        preferences.setupStep = null
    }

    /**
     * The user's "don't ask again" (§A1), which is a stronger statement than finishing.
     *
     * Completion is per-version and a later release may offer the Home card again; dismissal is permanent
     * until the user opens Setup health themselves. [com.gamecore.domain.setup.decideEntry] checks it
     * first, before anything else, which is what makes it mean what it says.
     */
    fun dismissForGood() {
        preferences.updateSettings { it.copy(setupDismissed = true) }
        preferences.setupStep = null
        editing.value = editing.value.copy(isFinished = true)
    }

    fun dismissMessage() {
        editing.value = editing.value.copy(message = null)
    }

    private companion object {
        const val INTENT_FAILED =
            "Android would not open that screen on this device. The same setting can be reached from " +
                "Settings › Apps › GameCore."
    }
}
