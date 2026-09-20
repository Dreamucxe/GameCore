package com.gamecore.ui.quickapps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.model.FloatingButtonConfig
import com.gamecore.core.system.InstalledAppLister
import com.gamecore.data.preferences.SecurePreferenceStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The quick-launch row's contents, and the switch that decides whether it is drawn.
 *
 * The stored list is the source of truth and this class never holds a second copy: every add, removal and
 * move is a [SecurePreferenceStore.updateQuickApps] call, and the screen redraws from the flow that write
 * lands in. That is what makes [FloatingButtonConfig.normalised]'s validation unavoidable — the cap at six,
 * the de-duplication and the package-name check are applied on the way to storage, and what comes back is
 * what was stored rather than what was asked for.
 *
 * Labels are resolved through [InstalledAppLister] rather than stored beside the package name, for the
 * reason [com.gamecore.ui.settings.NeverCloseViewModel] gives: a label is another developer's text, it
 * changes when they update, and a copy in GameCore's settings block would be a stale name plus a
 * third-party string in a file that has no other reason to hold one.
 *
 * Only two fields are watched. [SecurePreferenceStore.floatingButton] also carries the button's position,
 * which a drag rewrites frame by frame; re-resolving the row's labels because the user moved the button
 * would be the background work §26 rules out.
 */
@HiltViewModel
class QuickAppsViewModel @Inject constructor(
    private val preferences: SecurePreferenceStore,
    private val installedApps: InstalledAppLister,
) : ViewModel() {

    private val editing = MutableStateFlow(QuickAppsUiState())

    val state: StateFlow<QuickAppsUiState> = editing.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.floatingButton
                .map { it.showQuickApps to it.quickAppPackages }
                .distinctUntilChanged()
                .collect { (enabled, stored) ->
                    editing.value = editing.value.copy(
                        isLoaded = true,
                        isEnabled = enabled,
                        chosen = resolve(stored),
                    )
                }
        }
    }

    private suspend fun resolve(stored: List<String>): List<ChosenQuickApp> = stored.map { packageName ->
        val installed = installedApps.describe(packageName)
        ChosenQuickApp(
            packageName = packageName,
            label = installed?.label ?: packageName,
            isInstalled = installed != null,
        )
    }

    // ---------------------------------------------------------------------------------- the switch

    /**
     * Turns the row on or off without touching the list.
     *
     * Separate from the list on purpose: a user who turns the row off to see the panel without it should
     * find their six apps still there when they turn it back on. Emptying the list would be the app
     * throwing away a choice it was not asked to throw away.
     */
    fun setEnabled(enabled: Boolean) {
        preferences.updateFloatingButton { it.copy(showQuickApps = enabled) }
    }

    // ---------------------------------------------------------------------------------- the row

    fun add(app: PickableQuickApp) {
        if (editing.value.isFull) return
        editing.value = editing.value.copy(isPickerOpen = false)
        preferences.updateQuickApps(packages() + app.packageName)
    }

    fun remove(app: ChosenQuickApp) {
        preferences.updateQuickApps(packages() - app.packageName)
    }

    /**
     * Moves one app one place along, [delta] being -1 for earlier and 1 for later.
     *
     * A swap rather than a drag handle, matching how the HUD screen reorders its stats. Six rows do not
     * need a drag-and-drop implementation, and two arrow buttons are reachable by an accessibility service
     * in a way a long-press drag is not.
     *
     * Out-of-range moves return rather than clamp: the screen disables the arrow at each end, so a move
     * that would run off the list is a state that should not arrive here, and clamping would silently
     * accept it as a no-op write to the keystore.
     */
    fun move(app: ChosenQuickApp, delta: Int) {
        val current = packages()
        val from = current.indexOf(app.packageName)
        val to = from + delta
        if (from < 0 || to !in current.indices) return
        val reordered = current.toMutableList()
        reordered[from] = reordered[to]
        reordered[to] = app.packageName
        preferences.updateQuickApps(reordered)
    }

    /**
     * The stored order as plain package names.
     *
     * Read from [editing] rather than from the preference flow, because it is the list the user is looking
     * at and every write above is a change to *that*. They are the same list — the flow is what filled it —
     * and taking it from here means an edit cannot be applied to a version the screen has not shown yet.
     */
    private fun packages(): List<String> = editing.value.chosen.map { it.packageName }

    // --------------------------------------------------------------------------------- the picker

    fun openPicker() {
        editing.value = editing.value.copy(isPickerOpen = true)
        loadApps()
    }

    fun closePicker() {
        editing.value = editing.value.copy(isPickerOpen = false)
    }

    fun setShowSystemApps(show: Boolean) {
        editing.value = editing.value.copy(showSystemApps = show)
        loadApps()
    }

    /**
     * Lists what is installed, alphabetically.
     *
     * No games-first sort, for a reason opposite to the profile editor's and the same as the never-close
     * list's: what belongs in this row is what the user wants *while* a game is running — a chat app, a
     * browser, a guide, music — so ordering by the platform's game flag would put the least useful apps
     * first.
     *
     * [InstalledAppLister] queries launcher activities only, which is exactly the right filter here for
     * once without argument: an app with no launcher activity has no launch intent, so it could be added
     * to the row and would then draw as unavailable forever.
     *
     * Reloaded on every open rather than cached, because the interesting case is an app installed since
     * the user last looked.
     */
    private fun loadApps() {
        editing.value = editing.value.copy(isLoadingApps = true)
        viewModelScope.launch {
            val listed = installedApps.list(includeSystemApps = editing.value.showSystemApps)
            val already = packages().toSet()
            editing.value = editing.value.copy(
                apps = listed
                    .map {
                        PickableQuickApp(
                            packageName = it.packageName,
                            label = it.label,
                            isChosen = it.packageName in already,
                        )
                    }
                    .sortedBy { it.label.lowercase() },
                isLoadingApps = false,
            )
        }
    }
}
