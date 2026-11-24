package com.gamecore.ui.wheels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.model.WheelAccent
import com.gamecore.core.model.WheelButtonConfig
import com.gamecore.core.model.WheelButtonIcon
import com.gamecore.core.model.WheelButtonStyle
import com.gamecore.core.model.WheelConfig
import com.gamecore.core.model.WheelOperatingMode
import com.gamecore.core.model.WheelProfile
import com.gamecore.core.model.WheelResponseCurve
import com.gamecore.core.model.WheelVisualStyle
import com.gamecore.core.system.InstalledAppLister
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.domain.overlay.OverlayController
import com.gamecore.core.wheel.WheelController
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the wheel settings screen and nothing visual.
 *
 * The encrypted [SecurePreferenceStore] remains the source of truth. Slider drafts live in [LocalState]
 * only for the duration of a gesture, then commit once on release. Runtime actions go to
 * [WheelController], whose mode is intentionally process-only; no method in this ViewModel persists an
 * enabled state. Installed apps are discovered through the existing launcher-visible app boundary, with no
 * new package-visibility or foreground-detection permission.
 */
@HiltViewModel
class WheelsViewModel @Inject constructor(
    private val overlay: OverlayController,
    private val preferences: SecurePreferenceStore,
    private val wheel: WheelController,
    private val installedApps: InstalledAppLister,
) : ViewModel() {

    private data class LocalState(
        val hasPermission: Boolean = false,
        val guideRadiusDraft: Int? = null,
        val aimLabSensitivityDraft: Int? = null,
        val profileDraft: WheelProfile? = null,
        val buttonDraft: WheelButtonConfig? = null,
        val precisionMultiplierDraft: Float? = null,
        val turboMultiplierDraft: Float? = null,
        val apps: List<com.gamecore.core.model.InstalledApp> = emptyList(),
        val appsLoading: Boolean = false,
        val remoteDiagnostics: String = "",
    )

    private data class WheelPair(
        val config: WheelConfig,
        val runtime: com.gamecore.core.wheel.WheelRuntimeState,
    )

    private val local = MutableStateFlow(LocalState())
    private val wheelPair = combine(preferences.wheel, wheel.state, ::WheelPair)

    val state: StateFlow<WheelsUiState> = combine(
        preferences.settings,
        overlay.desired,
        overlay.status,
        wheelPair,
        local,
    ) { settings, desired, reported, current, own ->
        val config = current.config.normalised()
        val installed = own.apps.map { app ->
            WheelBindingApp(
                packageName = app.packageName,
                label = app.label,
                isLikelyGame = app.isLikelyGame,
                isInstalled = true,
                boundProfileId = config.bindings[app.packageName],
            )
        }
        val knownPackages = installed.mapTo(HashSet()) { it.packageName }
        val missing = config.bindings.keys
            .filterNot { it in knownPackages }
            .map { packageName ->
                WheelBindingApp(
                    packageName = packageName,
                    label = packageName,
                    isLikelyGame = false,
                    isInstalled = false,
                    boundProfileId = config.bindings[packageName],
                )
            }
        WheelsUiState(
            hasOverlayPermission = own.hasPermission,
            isGuideOn = desired.wheel,
            guideRadiusPercent = own.guideRadiusDraft ?: settings.wheelGuideRadiusPercent,
            aimLabSensitivityPercent = own.aimLabSensitivityDraft ?: settings.wheelSensitivityPercent,
            isDrivenByProfile = desired.fromProfile,
            drivingGameLabel = desired.gameLabel,
            config = config,
            runtime = current.runtime,
            editingProfile = own.profileDraft?.takeIf { it.id == config.activeProfileId }
                ?: config.activeProfile,
            editingButton = own.buttonDraft ?: config.button,
            precisionMultiplier = own.precisionMultiplierDraft ?: config.precisionMultiplier,
            turboMultiplier = own.turboMultiplierDraft ?: config.turboMultiplier,
            wheelButtonVisible = reported.wheelButtonVisible,
            wheelStickVisible = reported.wheelStickVisible,
            apps = (installed + missing).sortedWith(
                compareByDescending<WheelBindingApp> { it.boundProfileId != null }
                    .thenByDescending { it.isLikelyGame }
                    .thenBy { it.label.lowercase() },
            ),
            appsLoading = own.appsLoading,
            remoteDiagnostics = own.remoteDiagnostics,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        WheelsUiState(),
    )

    init {
        local.value = local.value.copy(hasPermission = overlay.hasPermission())
        loadApps()
    }

    /** Re-read after a trip to Android's display-over-other-apps page. */
    fun refreshPermission() {
        local.value = local.value.copy(hasPermission = overlay.hasPermission())
    }

    // Runtime ---------------------------------------------------------------------------------------

    fun setMode(mode: WheelOperatingMode) = wheel.setMode(mode)

    fun toggleWheel() {
        wheel.toggle()
    }

    /** Accepts the injection warning and arms the wheel in one deliberate action. */
    fun acceptRisk() = wheel.acceptRisk()

    fun dismissRisk() = wheel.dismissRiskPrompt()

    fun toggleLock() = wheel.toggleLock()

    fun refreshBackend() = wheel.refreshBackend()

    fun refreshDiagnostics() {
        viewModelScope.launch {
            val detail = withContext(Dispatchers.IO) { wheel.readDiagnostics() }
            local.value = local.value.copy(remoteDiagnostics = detail)
        }
    }

    // Active profile --------------------------------------------------------------------------------

    fun selectProfile(id: String) {
        local.value = local.value.copy(profileDraft = null)
        wheel.selectProfile(id)
    }

    private fun draftProfile(transform: (WheelProfile) -> WheelProfile) {
        val base = local.value.profileDraft?.takeIf { it.id == preferences.wheel.value.activeProfileId }
            ?: preferences.wheel.value.normalised().activeProfile
        local.value = local.value.copy(profileDraft = transform(base).normalised())
    }

    fun commitProfileDraft() {
        val draft = local.value.profileDraft ?: return
        preferences.updateWheel { it.withProfile(draft) }
        local.value = local.value.copy(profileDraft = null)
    }

    private fun updateActiveProfile(transform: (WheelProfile) -> WheelProfile) {
        val id = preferences.wheel.value.normalised().activeProfileId
        preferences.updateWheel { config ->
            val current = config.profiles.firstOrNull { it.id == id } ?: return@updateWheel config
            config.withProfile(transform(current))
        }
        local.value = local.value.copy(profileDraft = null)
    }

    fun setSensitivity(value: Float) = draftProfile { it.copy(sensitivity = value) }
    fun setXSensitivity(value: Float) = draftProfile { it.copy(xSensitivity = value) }
    fun setYSensitivity(value: Float) = draftProfile { it.copy(ySensitivity = value) }
    fun setRadius(value: Int) = draftProfile { it.copy(radiusPx = value) }
    fun setDeadzone(value: Int) = draftProfile { it.copy(deadzonePx = value) }
    fun setDiameter(value: Int) = draftProfile { it.copy(diameterDp = value) }
    fun setOpacity(value: Float) = draftProfile { it.copy(opacity = value) }
    fun setSmoothing(value: Float) = draftProfile { it.copy(smoothing = value) }

    fun setCurve(value: WheelResponseCurve) = updateActiveProfile { it.copy(curve = value) }
    fun setStyle(value: WheelVisualStyle) = updateActiveProfile { it.copy(style = value) }
    fun setAccent(value: WheelAccent) = updateActiveProfile { it.copy(accent = value) }
    fun setInvertX(value: Boolean) = updateActiveProfile { it.copy(invertX = value) }
    fun setInvertY(value: Boolean) = updateActiveProfile { it.copy(invertY = value) }
    fun setShowVector(value: Boolean) = updateActiveProfile { it.copy(showVector = value) }
    fun setProfileHaptics(value: Boolean) = updateActiveProfile { it.copy(haptics = value) }

    fun resetPosition() = updateActiveProfile {
        it.copy(
            xFraction = WheelProfile.DEFAULT_X_FRACTION,
            yFraction = WheelProfile.DEFAULT_Y_FRACTION,
        )
    }

    fun createProfile(name: String) {
        val current = preferences.wheel.value.normalised()
        if (current.profiles.size >= WheelConfig.MAX_PROFILES) return
        val profile = WheelProfile.DEFAULT.copy(id = newProfileId(), name = name).normalised()
        preferences.updateWheel { it.plusProfile(profile).withActiveProfile(profile.id) }
        local.value = local.value.copy(profileDraft = null)
    }

    fun duplicateActiveProfile(name: String) {
        val current = preferences.wheel.value.normalised()
        if (current.profiles.size >= WheelConfig.MAX_PROFILES) return
        val profile = current.activeProfile.duplicated(newProfileId(), name)
        preferences.updateWheel { it.plusProfile(profile).withActiveProfile(profile.id) }
        local.value = local.value.copy(profileDraft = null)
    }

    fun renameActiveProfile(name: String) = updateActiveProfile { it.copy(name = name) }

    fun deleteProfile(id: String) {
        preferences.updateWheel { it.minusProfile(id) }
        local.value = local.value.copy(profileDraft = null)
    }

    fun restoreActiveProfileDefaults() {
        updateActiveProfile { current ->
            WheelProfile.DEFAULT.copy(id = current.id, name = current.name)
        }
    }

    // Per-game bindings -----------------------------------------------------------------------------

    fun setAutoSwitch(enabled: Boolean) {
        preferences.updateWheel { it.copy(autoSwitch = enabled) }
    }

    fun bindGame(packageName: String, profileId: String) {
        preferences.updateWheel { config ->
            if (config.profiles.none { it.id == profileId }) config
            else config.copy(bindings = config.bindings + (packageName to profileId))
        }
    }

    fun unbindGame(packageName: String) {
        preferences.updateWheel { it.copy(bindings = it.bindings - packageName) }
    }

    fun loadApps() {
        if (local.value.appsLoading) return
        local.value = local.value.copy(appsLoading = true)
        viewModelScope.launch {
            val listed = installedApps.list(includeSystemApps = false)
            local.value = local.value.copy(apps = listed, appsLoading = false)
        }
    }

    // Emergency button ------------------------------------------------------------------------------

    private fun draftButton(transform: (WheelButtonConfig) -> WheelButtonConfig) {
        val base = local.value.buttonDraft ?: preferences.wheel.value.normalised().button
        local.value = local.value.copy(buttonDraft = transform(base).normalised())
    }

    fun commitButtonDraft() {
        val draft = local.value.buttonDraft ?: return
        wheel.updateButton { draft }
        local.value = local.value.copy(buttonDraft = null)
    }

    fun setButtonVisible(value: Boolean) = wheel.setButtonVisible(value)
    fun setButtonSize(value: Int) = draftButton { it.copy(sizeDp = value) }
    fun setButtonOpacity(value: Float) = draftButton { it.copy(opacity = value) }
    fun setButtonStyle(value: WheelButtonStyle) = wheel.updateButton { it.copy(style = value) }
    fun setButtonIcon(value: WheelButtonIcon) = wheel.updateButton { it.copy(icon = value) }
    fun setButtonAccent(value: WheelAccent) = wheel.updateButton { it.copy(accent = value) }
    fun setButtonHaptics(value: Boolean) = wheel.updateButton { it.copy(haptics = value) }
    fun setButtonIdleFade(value: Boolean) = wheel.updateButton { it.copy(idleFade = value) }
    fun setButtonSnapToEdge(value: Boolean) = wheel.updateButton { it.copy(snapToEdge = value) }

    // Runtime modifier configuration ----------------------------------------------------------------

    fun setPrecisionMultiplier(value: Float) {
        local.value = local.value.copy(
            precisionMultiplierDraft = value.coerceIn(
                WheelConfig.MIN_PRECISION_MULTIPLIER,
                WheelConfig.MAX_PRECISION_MULTIPLIER,
            ),
        )
    }

    fun commitPrecisionMultiplier() {
        val value = local.value.precisionMultiplierDraft ?: return
        preferences.updateWheel { it.copy(precisionMultiplier = value) }
        local.value = local.value.copy(precisionMultiplierDraft = null)
    }

    fun setTurboMultiplier(value: Float) {
        local.value = local.value.copy(
            turboMultiplierDraft = value.coerceIn(
                WheelConfig.MIN_TURBO_MULTIPLIER,
                WheelConfig.MAX_TURBO_MULTIPLIER,
            ),
        )
    }

    fun commitTurboMultiplier() {
        val value = local.value.turboMultiplierDraft ?: return
        preferences.updateWheel { it.copy(turboMultiplier = value) }
        local.value = local.value.copy(turboMultiplierDraft = null)
    }

    // Legacy guide and Aim Lab ----------------------------------------------------------------------

    fun setGuideEnabled(enabled: Boolean) {
        preferences.updateSettings { it.copy(wheelGuideEnabled = enabled) }
        overlay.setWheel(enabled)
    }

    fun setGuideRadius(percent: Int) {
        local.value = local.value.copy(guideRadiusDraft = percent)
    }

    fun commitGuideRadius() {
        val draft = local.value.guideRadiusDraft ?: return
        preferences.updateSettings { it.copy(wheelGuideRadiusPercent = draft) }
        local.value = local.value.copy(guideRadiusDraft = null)
    }

    fun setAimLabSensitivity(percent: Int) {
        local.value = local.value.copy(aimLabSensitivityDraft = percent)
    }

    fun commitAimLabSensitivity() {
        val draft = local.value.aimLabSensitivityDraft ?: return
        preferences.updateSettings { it.copy(wheelSensitivityPercent = draft) }
        local.value = local.value.copy(aimLabSensitivityDraft = null)
    }

    fun resetConfiguration() {
        wheel.disengage()
        preferences.updateWheel { WheelConfig.DEFAULT }
        local.value = local.value.copy(
            profileDraft = null,
            buttonDraft = null,
            precisionMultiplierDraft = null,
            turboMultiplierDraft = null,
            remoteDiagnostics = "",
        )
    }

    private fun newProfileId(): String = UUID.randomUUID().toString()

    private companion object {
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L
    }
}
