package com.gamecore.ui.settings

import com.gamecore.core.model.AppSettings

/**
 * The never-close list, being edited.
 *
 * The screen behind the "free RAM on launch" switch, and the only place in GameCore where the user
 * names other people's apps. Two lists rather than one: [entries] is what they have chosen, and [apps]
 * is what the picker offers — the same package appears in both while the picker is open, which is why
 * [PickableApp.isListed] exists rather than the picker silently filtering listed apps out. A row that
 * vanishes reads as a bug; a row that says it is already on the list reads as an answer.
 *
 * Nothing here is a draft. Every add and every removal is written to the settings store as it happens,
 * for the reason the settings screen has no save button: a list of apps to protect that was lost
 * because the user backed out of the screen would be protection they think they have and do not.
 */
data class NeverCloseUiState(
    val isLoaded: Boolean = false,
    val entries: List<ProtectedApp> = emptyList(),
    val isPickerOpen: Boolean = false,
    val apps: List<PickableApp> = emptyList(),
    val isLoadingApps: Boolean = false,
    val showSystemApps: Boolean = false,
) {
    /**
     * True when the list is full.
     *
     * [AppSettings.MAX_NEVER_KILL_ENTRIES] is enforced on write, so a screen that let the user keep
     * adding would be dropping the additions on the way to storage. The cap is high enough that
     * reaching it means something other than a user protecting the apps they care about.
     */
    val isFull: Boolean get() = entries.size >= AppSettings.MAX_NEVER_KILL_ENTRIES
}

/**
 * One app the user has asked GameCore to leave running.
 *
 * [isInstalled] is shown and not acted on. An entry for an app that is not on the device right now
 * still protects it if it comes back, and dropping it on the user's behalf would be GameCore editing a
 * list it was told to keep — the same rule the Games screen follows for a profile whose game was
 * uninstalled.
 */
data class ProtectedApp(
    val packageName: String,
    val label: String,
    val isInstalled: Boolean,
)

/** One row in the picker. [isListed] is what makes an already-protected app unselectable. */
data class PickableApp(
    val packageName: String,
    val label: String,
    val isListed: Boolean,
)
