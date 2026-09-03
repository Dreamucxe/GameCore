package com.gamecore.ui.overlay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.model.FloatingButtonConfig
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.OverlayConfig
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.domain.monitoring.HudStatReader
import com.gamecore.domain.monitoring.PerformanceMonitor
import com.gamecore.domain.monitoring.StatReading
import com.gamecore.domain.overlay.OverlayController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The configuration screen for the two windows that are on screen all session: §7's button and §8's pill.
 *
 * Nothing here draws anything and nothing here holds a `WindowManager` token. Every visibility change is a
 * write to [OverlayController], which is the one place in the app that starts the overlay service, and
 * every setting is a write to [SecurePreferenceStore], which the service is already collecting — so an edit
 * made while a game is running lands on the live window within a frame rather than on the next launch.
 *
 * Two things are stored per change rather than one, and both are needed. The controller's request is what
 * puts a window up now; the preference is what brings it back the next time GameCore is opened. Writing
 * only the first would give a floating button that vanished on every restart, and only the second a switch
 * that did nothing until then.
 *
 * The drafts are the slider convention from Settings, with one addition: because the pill this screen
 * previews is the same composable the service draws, applying the draft to the state that feeds the
 * preview makes the preview follow the thumb while the write still happens once, on release.
 */
@HiltViewModel
class OverlayViewModel @Inject constructor(
    private val preferences: SecurePreferenceStore,
    private val overlay: OverlayController,
    monitor: PerformanceMonitor,
) : ViewModel() {

    /**
     * What this screen owns rather than observes.
     *
     * The drafts are null unless a drag is in progress, so a config changed anywhere else — a profile
     * applying, the pill being dragged across the screen by hand — is picked up immediately instead of
     * being held off by a draft nobody released.
     */
    private data class LocalState(
        val isLoaded: Boolean = false,
        val hasPermission: Boolean = false,
        val pillDraft: OverlayConfig? = null,
        val buttonDraft: FloatingButtonConfig? = null,
        val message: String? = null,
    )

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<OverlayUiState> = combine(
        preferences.overlay,
        preferences.floatingButton,
        overlay.desired,
        overlay.status,
        local,
    ) { pill, button, desired, status, own ->
        OverlayUiState(
            isLoaded = own.isLoaded,
            hasPermission = own.hasPermission,
            button = own.buttonDraft ?: button,
            pill = own.pillDraft ?: pill,
            isServiceRunning = status.serviceRunning,
            isButtonVisible = status.buttonVisible,
            isPillVisible = status.pillVisible,
            isCrosshairVisible = status.crosshairVisible,
            isHudVisible = status.hudVisible,
            isDrivenByProfile = desired.fromProfile,
            drivingGameLabel = desired.gameLabel,
            // The permission is this screen's own fresh read rather than the one the service reported
            // with, which can predate a trip to Settings the user has just come back from.
            statusSummary = status.copy(hasPermission = own.hasPermission).summary,
            message = own.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS), OverlayUiState())

    /**
     * What the pill would read right now, in the order it draws them.
     *
     * The same reader the service feeds the real pill with, so the preview is not a mock-up: a stat this
     * device does not publish shows the placeholder here exactly as it would over a game, which is the
     * point of previewing it at all. Recomputed from [state] rather than from the stored config so a stat
     * added or moved appears in the preview on the next tick instead of after a navigation.
     */
    val readings: Flow<List<StatReading>> = combine(state, monitor.snapshots) { ui, snapshot ->
        HudStatReader.readAll(ui.pill.stats, snapshot)
    }

    init {
        viewModelScope.launch {
            preferences.preload()
            local.value = local.value.copy(isLoaded = true, hasPermission = overlay.hasPermission())
        }
    }

    /** Re-read after a trip to "display over other apps", which is a different app's screen. */
    fun refreshPermission() {
        local.value = local.value.copy(hasPermission = overlay.hasPermission())
    }

    // ------------------------------------------------------------------------- the floating button

    /**
     * Shows or hides the button, and remembers which.
     *
     * Refuses rather than pretends when the permission is missing: the controller would publish the
     * request, find no permission, report nothing visible, and the switch would flick back on its own.
     */
    fun setButtonVisible(visible: Boolean) {
        if (visible && !overlay.hasPermission()) {
            local.value = local.value.copy(message = NEEDS_PERMISSION)
            return
        }
        preferences.updateFloatingButton { it.copy(show = visible) }
        overlay.setButton(visible)
    }

    /** During a drag. Nothing is stored until [commitButton]. */
    fun editButton(transform: (FloatingButtonConfig) -> FloatingButtonConfig) {
        val base = local.value.buttonDraft ?: preferences.floatingButton.value
        local.value = local.value.copy(buttonDraft = transform(base).normalised())
    }

    /**
     * Stores the button settings the finger was left on.
     *
     * The position is taken from the stored config rather than from the draft, because the button on
     * screen can be dragged while this screen is open — it is drawn over it — and a slider release must
     * not put it back where it was when the drag started.
     */
    fun commitButton() {
        val draft = local.value.buttonDraft ?: return
        local.value = local.value.copy(buttonDraft = null)
        preferences.updateFloatingButton { current -> draft.copy(x = current.x, y = current.y) }
    }

    /** A switch or a choice: one tap, one write, and no draft to go stale. */
    fun updateButton(transform: (FloatingButtonConfig) -> FloatingButtonConfig) {
        local.value = local.value.copy(buttonDraft = null)
        preferences.updateFloatingButton(transform)
    }

    fun resetButtonPosition() {
        val defaults = FloatingButtonConfig()
        preferences.updateButtonPosition(defaults.x, defaults.y)
        local.value = local.value.copy(message = BUTTON_MOVED)
    }

    /**
     * Puts the control panel back to the width it shipped at.
     *
     * Through [SecurePreferenceStore.updatePanelWidth] rather than [updateButton], for the reason that
     * writer exists: the panel can be open over this screen while the button is being dragged, and
     * rewriting the whole config here would carry a stale x and y along with the width.
     *
     * The draft goes with it, because the card reads the draft in preference to the stored config — leaving
     * one behind would reset the width and go on showing the figure it was reset from.
     */
    fun resetPanelWidth() {
        preferences.updatePanelWidth(FloatingButtonConfig.DEFAULT_PANEL_WIDTH_DP)
        local.value = local.value.copy(buttonDraft = null, message = PANEL_WIDTH_RESET)
    }

    // ----------------------------------------------------------------------------------- the pill

    fun setPillVisible(visible: Boolean) {
        if (visible && !overlay.hasPermission()) {
            local.value = local.value.copy(message = NEEDS_PERMISSION)
            return
        }
        if (visible && preferences.overlay.value.stats.isEmpty()) {
            local.value = local.value.copy(message = NEEDS_STATS)
            return
        }
        preferences.updateOverlay { it.copy(showPill = visible) }
        overlay.setPill(visible)
    }

    fun editPill(transform: (OverlayConfig) -> OverlayConfig) {
        val base = local.value.pillDraft ?: preferences.overlay.value
        local.value = local.value.copy(pillDraft = transform(base).normalised())
    }

    /** As [commitButton]: the dragged position wins over the draft's copy of it. */
    fun commitPill() {
        val draft = local.value.pillDraft ?: return
        local.value = local.value.copy(pillDraft = null)
        preferences.updateOverlay { current -> draft.copy(pillX = current.pillX, pillY = current.pillY) }
    }

    fun updatePill(transform: (OverlayConfig) -> OverlayConfig) {
        local.value = local.value.copy(pillDraft = null)
        preferences.updateOverlay(transform)
    }

    fun setInterval(tenths: Int) = editPill {
        it.copy(updateIntervalMillis = tenths * OverlayUiState.TENTH_MILLIS)
    }

    fun resetPillPosition() {
        val defaults = OverlayConfig()
        preferences.updatePillPosition(defaults.pillX, defaults.pillY)
        local.value = local.value.copy(message = PILL_MOVED)
    }

    fun addStat(stat: HudStat) = updatePill { config ->
        if (config.stats.size >= OverlayConfig.MAX_STATS) {
            config
        } else {
            config.copy(stats = config.stats + stat)
        }
    }

    /**
     * Takes a stat off the pill, and hides the pill if that was the last one.
     *
     * An empty pill is a small dark rectangle with nothing in it, sitting over a game. Hiding it and
     * saying so is better than leaving the user to work out why their stats turned into a smudge.
     */
    fun removeStat(stat: HudStat) {
        updatePill { it.copy(stats = it.stats - stat) }
        if (preferences.overlay.value.stats.isNotEmpty()) return
        if (preferences.overlay.value.showPill) {
            preferences.updateOverlay { it.copy(showPill = false) }
            overlay.setPill(false)
        }
        local.value = local.value.copy(message = PILL_EMPTIED)
    }

    /** Moves one stat along the pill. [delta] is -1 or 1; the ends are walls rather than a wrap. */
    fun moveStat(stat: HudStat, delta: Int) = updatePill { config ->
        val stats = config.stats.toMutableList()
        val from = stats.indexOf(stat)
        val to = from + delta
        if (from < 0 || to !in stats.indices) {
            config
        } else {
            stats.removeAt(from)
            stats.add(to, stat)
            config.copy(stats = stats)
        }
    }

    // ------------------------------------------------------------------------------------ the lot

    /**
     * Takes everything down: both windows on this screen, and the crosshair and HUD as well.
     *
     * [OverlayController.hideAll] is the panic button — it clears the whole request, so the stored flags
     * for the other two windows are cleared with it. Leaving those set would give a HUD screen with its
     * switch on and no HUD, which is the disagreement between request and reality this app exists to
     * avoid publishing.
     */
    fun hideEverything() {
        preferences.updateOverlay { it.copy(showPill = false) }
        preferences.updateFloatingButton { it.copy(show = false) }
        preferences.showCrosshairOverlay = false
        preferences.showHudOverlay = false
        overlay.hideAll()
        local.value = local.value.copy(message = ALL_HIDDEN)
    }

    fun dismissMessage() {
        local.value = local.value.copy(message = null)
    }

    private companion object {
        /** Long enough to survive a rotation, short enough to stop the sampler when the screen is left. */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L

        const val NEEDS_PERMISSION =
            "GameCore cannot draw over other apps yet, so nothing would appear. Grant that first and the " +
                "switch will stay on."

        const val NEEDS_STATS =
            "The pill has no stats on it, so it would be an empty plate over your game. Add at least one " +
                "below."

        const val PILL_EMPTIED =
            "That was the last stat, so the pill has been switched off rather than left blank over your " +
                "game. Add a stat and it can be shown again."

        const val PILL_MOVED = "The pill is back at the left edge, a little below the top."

        const val BUTTON_MOVED = "The button is back at the left edge, about halfway down."

        const val PANEL_WIDTH_RESET =
            "The control panel is back to ${FloatingButtonConfig.DEFAULT_PANEL_WIDTH_DP} dp wide. It never " +
                "opens wider than the screen it opens on, so on a narrow screen in portrait it may still " +
                "be drawn narrower than that."

        const val ALL_HIDDEN =
            "Every overlay window is down — the button, the stats pill, the crosshair and any HUD. The " +
                "overlay service has stopped with them."
    }
}
