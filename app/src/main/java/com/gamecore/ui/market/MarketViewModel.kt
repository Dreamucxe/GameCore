package com.gamecore.ui.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.plugin.PluginApi
import com.gamecore.core.plugin.market.MarketEntry
import com.gamecore.core.plugin.market.MarketEntryState
import com.gamecore.core.plugin.market.MarketEntryStatus
import com.gamecore.core.plugin.market.MarketUpdates
import com.gamecore.data.market.MarketInstallOutcome
import com.gamecore.data.market.MarketRepository
import com.gamecore.core.plugin.PluginRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One lens over the curated index. [UPDATES] is the only one that is not a category. */
enum class MarketFilter(val label: String) {
    ALL("All"),
    UPDATES("Updates"),
    THEME("Themes"),
    PERFORMANCE("Performance"),
    PILL("Game Pill"),
    AIMLAB("Aim Lab"),
    AUTOMATION("Automations"),
    STATS("Stats"),
    OTHER("Other"),
    ;

    fun accepts(status: MarketEntryStatus): Boolean = when (this) {
        ALL -> true
        UPDATES -> status.state == MarketEntryState.UPDATE_AVAILABLE
        THEME -> status.entry.category == MarketEntry.Category.THEME
        PERFORMANCE -> status.entry.category == MarketEntry.Category.PERFORMANCE
        PILL -> status.entry.category == MarketEntry.Category.PILL
        AIMLAB -> status.entry.category == MarketEntry.Category.AIMLAB
        AUTOMATION -> status.entry.category == MarketEntry.Category.AUTOMATION
        STATS -> status.entry.category == MarketEntry.Category.STATS
        OTHER -> status.entry.category == MarketEntry.Category.OTHER
    }
}

data class MarketUiState(
    val isLoading: Boolean = true,
    val statuses: List<MarketEntryStatus> = emptyList(),
    val filter: MarketFilter = MarketFilter.ALL,
    val query: String = "",
    val workingId: String? = null,
    val message: String? = null,
    val error: String? = null,
    val skippedEntries: Int = 0,
) {
    val visible: List<MarketEntryStatus> get() = statuses.filter { filter.accepts(it) }
        .filter { matchesQuery(it.entry, query) }

    /** Updates are counted across the whole index, not the current filter. */
    val updateCount: Int
        get() = statuses.count { it.state == MarketEntryState.UPDATE_AVAILABLE }

    val installableCount: Int get() = statuses.count { it.state.canInstall }

    private fun matchesQuery(entry: MarketEntry, raw: String): Boolean {
        val needle = raw.trim().lowercase()
        if (needle.isEmpty()) return true
        return entry.name.lowercase().contains(needle) ||
            entry.developer.lowercase().contains(needle) ||
            entry.summary.lowercase().contains(needle) ||
            entry.id.lowercase().contains(needle)
    }
}

private data class Transient(
    val filter: MarketFilter = MarketFilter.ALL,
    val query: String = "",
    val workingId: String? = null,
    val message: String? = null,
)

/**
 * The discover screen's state holder.
 *
 * Update detection is recomputed from the live registry on every emission, never stored in the index
 * result: installing a plugin must move its row from Install to Up to date in the same frame, without
 * waiting for a network refresh. The repository owns bytes and the index; this class owns only the
 * reading of them.
 */
@HiltViewModel
class MarketViewModel @Inject constructor(
    private val repository: MarketRepository,
    registry: PluginRegistry,
) : ViewModel() {

    private val transient = MutableStateFlow(Transient())

    val state: StateFlow<MarketUiState> = combine(
        repository.state,
        registry.installed,
        transient,
    ) { catalog, installed, own ->
        val byId = installed.associateBy { it.id }
        MarketUiState(
            isLoading = catalog.isLoading,
            statuses = catalog.entries.map { entry ->
                MarketUpdates.statusOf(
                    entry = entry,
                    installed = byId[entry.id],
                    hostVersionCode = PluginApi.HOST_VERSION_CODE,
                )
            },
            filter = own.filter,
            query = own.query,
            workingId = own.workingId,
            message = own.message,
            error = catalog.error,
            skippedEntries = catalog.skippedEntries,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MarketUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    fun setFilter(filter: MarketFilter) {
        transient.update { it.copy(filter = filter) }
    }

    fun setQuery(query: String) {
        transient.update { it.copy(query = query.take(80)) }
    }

    /**
     * Downloads, verifies and installs one entry.
     *
     * The row is locked by id while the work runs, so a second tap cannot start a duplicate download.
     * The message names what happened rather than "done": an update deliberately clears the grant and
     * switches the plugin off, and a user who is not told that will read the newly empty permission
     * switches as the update having failed.
     */
    fun install(entry: MarketEntry) {
        if (transient.value.workingId != null) return
        transient.update { it.copy(workingId = entry.id, message = null) }
        viewModelScope.launch {
            val outcome = repository.install(entry)
            transient.update {
                it.copy(
                    workingId = null,
                    message = when (outcome) {
                        is MarketInstallOutcome.Installed -> if (outcome.wasUpdate) {
                            "${outcome.pluginName} was updated and switched off. Its permissions were " +
                                "cleared by the update; review them and turn it back on when you are ready."
                        } else {
                            "${outcome.pluginName} was installed. Open it from the plugin list to " +
                                "review its permissions before turning it on."
                        }

                        is MarketInstallOutcome.Refused -> outcome.message
                    },
                )
            }
        }
    }

    fun dismissMessage() {
        transient.update { it.copy(message = null) }
        repository.dismissError()
    }
}
