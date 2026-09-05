package com.gamecore.ui.storage

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.model.GameStorage
import com.gamecore.core.permissions.GamePermission
import com.gamecore.core.permissions.PermissionChecker
import com.gamecore.domain.storage.GameCacheCleaner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The game-storage screen's state.
 *
 * Every figure on this screen is read on demand and none of it is cached between visits. Storage sizes
 * go stale the moment a game is played, and a screen offering to delete something on the strength of a
 * number from ten minutes ago is offering to delete something the user cannot see.
 *
 * A clear re-measures the whole list rather than patching the row it touched. The delete changes one
 * directory, but the figure it moves is the platform's, and the cheapest way to be sure the screen
 * agrees with the device is to ask the device again.
 */
@HiltViewModel
class GameStorageViewModel @Inject constructor(
    private val cleaner: GameCacheCleaner,
    private val permissions: PermissionChecker,
) : ViewModel() {

    private val local = MutableStateFlow(GameStorageUiState())

    val state: StateFlow<GameStorageUiState> = local.asStateFlow()

    init {
        refresh()
    }

    /**
     * Re-reads everything: the shell's availability, the grant, and every game's figures.
     *
     * The shell is re-checked here and not remembered from the last visit because Shizuku stops when
     * the device reboots, and a button that says it will work because it worked yesterday is the
     * failure this screen is meant to avoid.
     */
    fun refresh() {
        local.value = local.value.copy(isRefreshing = true)
        viewModelScope.launch {
            val canClear = cleaner.canClear()
            val rows = cleaner.list()
            local.value = local.value.copy(
                isLoaded = true,
                isRefreshing = false,
                canClear = canClear,
                needsUsageAccess = !permissions.hasUsageAccess(),
                rows = rows,
            )
        }
    }

    // -------------------------------------------------------------------------- clearing

    fun askToClear(row: GameStorage) {
        local.value = local.value.copy(confirming = row)
    }

    fun dismissConfirmation() {
        local.value = local.value.copy(confirming = null)
    }

    /**
     * Clears one game's cache, then re-measures.
     *
     * The report is kept whatever it says. A clear that freed nothing, or one the shell refused, is
     * information the user asked for by pressing the button, and a screen that only shows the sentence
     * when it is a good one is a screen that cannot be believed when it is quiet.
     */
    fun clear(row: GameStorage) {
        val packageName = row.packageName
        if (packageName in local.value.clearing) return
        local.value = local.value.copy(
            confirming = null,
            clearing = local.value.clearing + packageName,
        )
        viewModelScope.launch {
            val report = cleaner.clear(packageName)
            local.value = local.value.copy(
                clearing = local.value.clearing - packageName,
                reports = local.value.reports + (packageName to report),
            )
            if (report.isCleared) refresh()
        }
    }

    // --------------------------------------------------------------------------- intents

    /** The usage-access page, for the grant that buys the figures. Null when no page resolves. */
    fun usageAccessIntent(): Intent? =
        permissions.settingsIntentFor(GamePermission.USAGE_ACCESS) ?: report(NO_SETTINGS_PAGE)

    /**
     * Android's own storage page for one game.
     *
     * Offered on every row, whatever the shell's state, because it is the route that needs nothing
     * granted and reaches more than GameCore can: the cache inside the app's private storage, which no
     * command here can touch, is one tap away on that page.
     */
    fun appStorageIntent(row: GameStorage): Intent =
        permissions.appDetailsIntent(row.packageName)

    /** The screen calls this when `startActivity` threw, so the failure is stated rather than silent. */
    fun onIntentFailed() {
        report(LAUNCH_FAILED)
    }

    fun dismissMessage() {
        local.value = local.value.copy(message = null)
    }

    private fun report(message: String): Intent? {
        local.value = local.value.copy(message = message)
        return null
    }

    private companion object {
        const val LAUNCH_FAILED = "That screen could not be opened on this device."
        const val NO_SETTINGS_PAGE =
            "This build has no usage-access page. Grant it from Android's settings, under special app " +
                "access."
    }
}
