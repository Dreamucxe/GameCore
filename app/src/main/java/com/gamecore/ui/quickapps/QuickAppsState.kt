package com.gamecore.ui.quickapps

import com.gamecore.core.model.FloatingButtonConfig

/**
 * The quick-launch row, being chosen.
 *
 * Two lists for the reason [com.gamecore.ui.settings.NeverCloseUiState] keeps two: [chosen] is the row as
 * it will be drawn over the game, and [apps] is everything the picker can offer. The same package appears
 * in both while the picker is open, and [PickableQuickApp.isChosen] is what marks it there rather than the
 * picker quietly dropping it — a row that vanishes when tapped reads as a bug.
 *
 * One thing this screen has that the never-close list does not: **order is meaning**. The row is drawn left
 * to right in stored order, six positions under a thumb on a phone held in two hands, so the first entry is
 * the easiest to reach and the last is the hardest. That is why [chosen] is a `List` whose order is
 * preserved end to end — model, storage, resolve and draw — and why the screen has up/down controls at all.
 *
 * Nothing here is a draft. Every add, removal and move is written as it happens, for the reason the rest of
 * the settings screens have no save button.
 */
data class QuickAppsUiState(
    val isLoaded: Boolean = false,
    val isEnabled: Boolean = false,
    val chosen: List<ChosenQuickApp> = emptyList(),
    val isPickerOpen: Boolean = false,
    val apps: List<PickableQuickApp> = emptyList(),
    val isLoadingApps: Boolean = false,
    val showSystemApps: Boolean = false,
) {
    /**
     * True when the row holds all six.
     *
     * [FloatingButtonConfig.MAX_QUICK_APPS] is enforced on write, so a screen that kept accepting taps
     * would be dropping them on the way to storage. The cap is not arbitrary and the screen says why: six
     * icons is what fits across the narrowest panel the user can drag to without the tiles becoming too
     * small to hit.
     */
    val isFull: Boolean get() = chosen.size >= FloatingButtonConfig.MAX_QUICK_APPS

    /**
     * What the row is doing right now, as one line for the header.
     *
     * The empty case is called out separately from the switched-off case because they need different
     * actions from the user, and §24 asks for the reason rather than a blank. A row that is turned on and
     * empty draws nothing at all, which looks identical to a row that is turned off — so this is the only
     * place that difference is visible.
     */
    val summary: String
        get() = when {
            !isLoaded -> "Reading your settings"
            chosen.isEmpty() && !isEnabled -> "Off, and nothing chosen"
            chosen.isEmpty() -> "On, but no apps chosen yet"
            !isEnabled -> "${chosen.size} chosen, row turned off"
            else -> "${chosen.size} of ${FloatingButtonConfig.MAX_QUICK_APPS} in the row"
        }
}

/**
 * One app in the row, in the position the user put it.
 *
 * [isInstalled] is shown and not acted on, exactly as it is on the never-close list: an entry whose app has
 * been uninstalled keeps its place, because GameCore was told to keep this list and silently editing it
 * would be GameCore deciding the user changed their mind. The panel draws the same entry as unavailable —
 * see [com.gamecore.domain.overlay.QuickApp] — so the two screens agree about what is missing.
 */
data class ChosenQuickApp(
    val packageName: String,
    val label: String,
    val isInstalled: Boolean,
)

/** One row in the picker. [isChosen] is what makes an app already in the row unselectable. */
data class PickableQuickApp(
    val packageName: String,
    val label: String,
    val isChosen: Boolean,
)
