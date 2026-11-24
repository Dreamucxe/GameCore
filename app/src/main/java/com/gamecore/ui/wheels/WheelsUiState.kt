package com.gamecore.ui.wheels

import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.WheelButtonConfig
import com.gamecore.core.model.WheelConfig
import com.gamecore.core.model.WheelProfile
import com.gamecore.core.wheel.WheelRuntimeState

/** One launchable app offered by the wheel's per-game profile bindings. */
data class WheelBindingApp(
    val packageName: String,
    val label: String,
    val isLikelyGame: Boolean,
    val isInstalled: Boolean,
    val boundProfileId: String? = null,
)

/**
 * Immutable state for the full High Sensitivity Wheel control surface.
 *
 * Durable configuration and runtime mode are deliberately separate. [config] contains profiles, bindings
 * and the emergency-button configuration; [runtime] starts disabled in every process and is never written
 * to storage. [editingProfile] and [editingButton] are slider drafts owned by the ViewModel, so a drag can
 * update this screen without rewriting the encrypted preference file on every pointer frame.
 */
data class WheelsUiState(
    val hasOverlayPermission: Boolean = false,
    val isGuideOn: Boolean = false,
    val guideRadiusPercent: Int = AppSettings.WHEEL_RADIUS_DEFAULT,
    val aimLabSensitivityPercent: Int = AppSettings.WHEEL_SENSITIVITY_DEFAULT,
    val isDrivenByProfile: Boolean = false,
    val drivingGameLabel: String = "",
    val config: WheelConfig = WheelConfig.DEFAULT,
    val runtime: WheelRuntimeState = WheelRuntimeState(),
    val editingProfile: WheelProfile = WheelProfile.DEFAULT,
    val editingButton: WheelButtonConfig = WheelButtonConfig.DEFAULT,
    val precisionMultiplier: Float = WheelConfig.DEFAULT_PRECISION_MULTIPLIER,
    val turboMultiplier: Float = WheelConfig.DEFAULT_TURBO_MULTIPLIER,
    val wheelButtonVisible: Boolean = false,
    val wheelStickVisible: Boolean = false,
    val apps: List<WheelBindingApp> = emptyList(),
    val appsLoading: Boolean = false,
    val remoteDiagnostics: String = "",
) {
    val activeProfile: WheelProfile get() = config.activeProfile

    /** The older guide is still driven by game profiles, unlike the real wheel's global emergency handle. */
    val canToggleGuide: Boolean get() = hasOverlayPermission && !isDrivenByProfile

    /** Any mode other than disabled captures the wheel's bounded touch area. */
    val wheelEnabled: Boolean get() = runtime.mode != com.gamecore.core.model.WheelOperatingMode.DISABLED
        || runtime.arming

    val boundApps: List<WheelBindingApp> get() = apps.filter { it.boundProfileId != null }
    val unboundApps: List<WheelBindingApp> get() = apps.filter { it.isInstalled && it.boundProfileId == null }
}
