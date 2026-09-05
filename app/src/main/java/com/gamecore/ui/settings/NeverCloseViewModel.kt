package com.gamecore.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 * The never-close list.
 *
 * The stored list is the source of truth and this class never holds a second copy of it: every add and
 * every removal is a [SecurePreferenceStore.updateSettings] call, and the screen redraws from the flow
 * that write lands in. That is what makes
 * [com.gamecore.core.model.AppSettings.normalised]'s validation unavoidable — an entry the sanitiser
 * rejects never appears, because what comes back is what was stored and not what was asked for.
 *
 * Labels are resolved from the package manager rather than stored beside the package name. A label is
 * the other developer's text and it changes with their updates; storing it would mean a list that shows
 * the name an app had when it was added, and it would put third-party strings in GameCore's own
 * settings block for no gain.
 */
@HiltViewModel
class NeverCloseViewModel @Inject constructor(
    private val preferences: SecurePreferenceStore,
    private val installedApps: InstalledAppLister,
) : ViewModel() {

    private val editing = MutableStateFlow(NeverCloseUiState())

    val state: StateFlow<NeverCloseUiState> = editing.asStateFlow()

    init {
        // Only this field. The settings flow emits on every preference in the app, and re-resolving a
        // couple of hundred labels through the package manager because the accent colour changed would
        // be work nobody asked for.
        viewModelScope.launch {
            preferences.settings
                .map { it.neverKillPackages }
                .distinctUntilChanged()
                .collect { stored -> editing.value = editing.value.withEntries(resolve(stored)) }
        }
    }

    private suspend fun resolve(stored: List<String>): List<ProtectedApp> = stored.map { packageName ->
        val installed = installedApps.describe(packageName)
        ProtectedApp(
            packageName = packageName,
            label = installed?.label ?: packageName,
            isInstalled = installed != null,
        )
    }

    private fun NeverCloseUiState.withEntries(resolved: List<ProtectedApp>) =
        copy(isLoaded = true, entries = resolved)

    // ---------------------------------------------------------------------------------- the list

    fun add(app: PickableApp) {
        if (editing.value.isFull) return
        editing.value = editing.value.copy(isPickerOpen = false)
        preferences.updateSettings { it.copy(neverKillPackages = it.neverKillPackages + app.packageName) }
    }

    fun remove(app: ProtectedApp) {
        preferences.updateSettings {
            it.copy(neverKillPackages = it.neverKillPackages - app.packageName)
        }
    }

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
     * No games-first sort, unlike the profile editor's picker: the apps worth protecting here are the
     * ones that are *not* the game — a recorder mid-take, a download, a heart-rate monitor — so ordering
     * by the platform's game flag would put the least relevant apps at the top.
     *
     * Reloaded on every open rather than cached, because the interesting case for this screen is an app
     * the user installed since they last looked at it.
     */
    private fun loadApps() {
        editing.value = editing.value.copy(isLoadingApps = true)
        viewModelScope.launch {
            val listed = installedApps.list(includeSystemApps = editing.value.showSystemApps)
            val already = editing.value.entries.map { it.packageName }.toSet()
            editing.value = editing.value.copy(
                apps = listed
                    .map {
                        PickableApp(
                            packageName = it.packageName,
                            label = it.label,
                            isListed = it.packageName in already,
                        )
                    }
                    .sortedBy { it.label.lowercase() },
                isLoadingApps = false,
            )
        }
    }
}
