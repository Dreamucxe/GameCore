package com.gamecore.ui.games

import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.DeviceCapabilities
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.RefreshRateMechanism

/**
 * The profile editor's state.
 *
 * [profile] is the whole [GameProfile] rather than a reduced DTO, and that is the one place in this app
 * where handing a domain model to a composable is right: the editor's entire purpose is to read and write
 * every field of it. §24A.2 forbids passing *internal or platform* objects through to the UI — a
 * `PackageInfo`, a `PerformanceSnapshot` — not the user's own saved configuration, which has no hidden
 * surface for a screen to reach into.
 *
 * Null [profile] means the editor is still waiting for the user to choose an app, which is the only state
 * a new profile starts in. Everything else on the screen is disabled until then, because a profile
 * without a package is not a thing that can be saved.
 */
data class ProfileEditorUiState(
    val isNew: Boolean = true,
    val isLoaded: Boolean = false,
    val profile: GameProfile? = null,
    /** Whether the profile's package is on the device. True until the check says otherwise. */
    val isGameInstalled: Boolean = true,
    val capabilities: DeviceCapabilities = DeviceCapabilities.UNKNOWN,
    val hudLayouts: List<NamedOption> = emptyList(),
    val crosshairs: List<NamedOption> = emptyList(),
    val apps: List<AppOption> = emptyList(),
    val isPickerOpen: Boolean = false,
    val isLoadingApps: Boolean = false,
    val showSystemApps: Boolean = false,
    val isDirty: Boolean = false,
    /** From settings: whether backing out of unsaved edits should ask first. */
    val confirmOnDiscard: Boolean = true,
    val isSaving: Boolean = false,
    /** Set once the save has landed, so the screen knows to navigate back. */
    val isFinished: Boolean = false,
    val message: String? = null,
) {
    val canSave: Boolean get() = profile != null && !isSaving

    /** True when this profile's game is not on the device — worth saying, not worth blocking. */
    val isMissingGame: Boolean get() = isLoaded && profile != null && !isGameInstalled

    /**
     * The panel's rates, or an empty list when the display offers no choice.
     *
     * A single-rate panel is §31's 60 Hz edge case, and it gets a sentence rather than a row of one
     * chip: a control with one option looks broken, and the user has nothing to decide.
     */
    val refreshRateChoices: List<Float>
        get() = if (capabilities.hasVariableRefreshRate) capabilities.supportedRefreshRates else emptyList()
}

/** One app in the picker. Carries what the list row draws, and the reason a row may be unselectable. */
data class AppOption(
    val packageName: String,
    val label: String,
    val isLikelyGame: Boolean,
    /** True when a profile for this package already exists, so the picker can say so. */
    val hasProfile: Boolean,
)

/** A saved thing the user can point a profile at: a HUD layout, a crosshair preset. */
data class NamedOption(val id: Long, val name: String, val detail: String? = null)

/**
 * What a capability's current state means for a setting the user is configuring *now* for *later*.
 *
 * A profile is an intention, so an unavailable control is not a reason to prevent the user from
 * expressing it — they may be about to grant the permission. It is a reason to say plainly that the
 * setting will be skipped until then, which is the difference between this and pretending it will work.
 */
internal fun CapabilityStatus.profileNote(): String? = when (this) {
    CapabilityStatus.AVAILABLE -> null
    CapabilityStatus.REQUIRES_PERMISSION -> "Needs a permission GameCore does not have yet — this will " +
        "be skipped until it is granted."
    CapabilityStatus.REQUIRES_SHIZUKU -> "Needs Shizuku running — this will be skipped until it is."
    CapabilityStatus.UNSUPPORTED -> "Not supported on this device. Saving it will have no effect."
}

/**
 * True when the refresh-rate control would work, or work more reliably, with the elevated shell running.
 *
 * The MediaTek case from §24B is included deliberately: on those builds the standard path is accepted
 * and ignored, so a device that looks capable is exactly the one where offering the Shizuku route
 * matters most.
 */
internal val DeviceCapabilities.wantsShizukuForRefresh: Boolean
    get() = !shizuku.isUsable && (
        refreshRateMechanism == RefreshRateMechanism.NONE ||
            refreshRateMechanism == RefreshRateMechanism.SHIZUKU_SETTINGS ||
            isKnownUnreliableRefreshChipset
        )

/** The screen-timeout values a profile can pick, as milliseconds. */
internal val TIMEOUT_CHOICES = listOf(
    30_000L,
    60_000L,
    120_000L,
    300_000L,
    600_000L,
    1_800_000L,
)
