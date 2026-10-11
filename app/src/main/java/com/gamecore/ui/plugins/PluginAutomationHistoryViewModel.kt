package com.gamecore.ui.plugins

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.plugin.ApiResult
import com.gamecore.core.plugin.PluginAutomationHistoryStore
import com.gamecore.core.plugin.PluginRegistry
import com.gamecore.ui.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The state holder for GameCore's own automation-history screen.
 *
 * The one thing this class does that matters is tell "not allowed" apart from "nothing has run", and it
 * does so by reading the shape of the registry's answer rather than its contents. [PluginRegistry.automationHistory]
 * gates the read exactly as the overlay and dashboard surfaces are gated: a plugin that no longer holds
 * [com.gamecore.core.plugin.DataScope.AUTOMATION_HISTORY] — because the user revoked it, the kill switch
 * is on, or the plugin is disabled or suspended — comes back as a non-[ApiResult.Ok], and a plugin that
 * holds it comes back [ApiResult.Ok] with the trail, which may legitimately be empty. So [load] maps
 * [ApiResult.Ok] to [LoadPhase.Ready] and *every other* result to [LoadPhase.Denied]. That is wider than
 * the implementation, which only returns [ApiResult.Error] for the refusal — but it is the honest
 * default: an unexpected rate-limit or error is "we could not show you this", which is the Denied panel,
 * never an empty-looking "nothing ran".
 *
 * Like the consent screen's holder, this takes the narrow [PluginRegistry] port rather than a concrete
 * registry: everything it is allowed to do — read the trail, clear the trail — is one of two gated
 * methods, and both re-check the scope, so this class cannot reach the history store directly or clear a
 * trail the user's plugin was never allowed to see. The whole working set is read once ([HISTORY_LIMIT]
 * lines, the store's own retention cap); the search, the outcome filter and the sort are then applied in
 * memory by the pure reducers in [PluginAutomationHistoryState], and there is no pager.
 *
 * The id arrives as a nav argument, trimmed and read as a string: a blank or unknown id resolves to the
 * registry's refusal (an unknown plugin is an error there), which lands on the Denied panel rather than
 * throwing — a screen reached from a stale back-stack entry must not crash.
 */
@HiltViewModel
class PluginAutomationHistoryViewModel @Inject constructor(
    private val registry: PluginRegistry,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** The plugin that asked GameCore to show this record. Trimmed, never coerced; blank is an unknown id. */
    private val pluginId: String = savedStateHandle.get<String>(Destination.ARG_PLUGIN_ID)?.trim().orEmpty()

    private val editing = MutableStateFlow(PluginAutomationHistoryUiState())
    val state: StateFlow<PluginAutomationHistoryUiState> = editing.asStateFlow()

    init {
        load()
    }

    /**
     * Reads the whole trail once and settles the load phase.
     *
     * An [ApiResult.Ok] is [LoadPhase.Ready] with its records (possibly empty — nothing ran). Anything
     * else is [LoadPhase.Denied]: the records are cleared so a stale list cannot sit under a "not
     * allowed" panel. The view controls (query, filter, sort) are preserved across the read, so a reload
     * after a clear does not throw away a search the user had typed.
     */
    private fun load() {
        viewModelScope.launch {
            editing.value = when (val result = registry.automationHistory(pluginId, limit = HISTORY_LIMIT)) {
                is ApiResult.Ok -> editing.value.copy(phase = LoadPhase.Ready, records = result.value)
                else -> editing.value.copy(phase = LoadPhase.Denied, records = emptyList())
            }
        }
    }

    /** Updates the in-memory search. The rows are re-derived by [visibleRecords]; nothing is re-read. */
    fun onSearchChange(query: String) {
        editing.value = editing.value.copy(query = query)
    }

    /** Narrows to one outcome, or clears the filter when [outcome] is null. In memory, no re-read. */
    fun onOutcomeFilterChange(outcome: String?) {
        editing.value = editing.value.copy(outcomeFilter = outcome)
    }

    /** Flips newest-first and oldest-first. The sort is a reorder of the loaded set, done in memory. */
    fun onToggleSort() {
        editing.value = editing.value.copy(newestFirst = !editing.value.newestFirst)
    }

    /**
     * Clears the whole trail, then re-reads it.
     *
     * The clear is confirmed on screen before this is ever called — a plugin cannot drive it silently.
     * The result of the clear is not inspected on its own: the reload that follows is the honest report,
     * because it re-runs the same gate. A successful clear reloads to [LoadPhase.Ready] with an empty
     * trail ("nothing has run"); a clear refused because the scope was revoked in between reloads to
     * [LoadPhase.Denied], which is the truth the moment it happened.
     */
    fun onConfirmClear() {
        viewModelScope.launch {
            registry.clearAutomationHistory(pluginId)
            load()
        }
    }

    private companion object {
        /**
         * How many lines to pull in the one read. The store's own retention cap, so this is the whole
         * durable trail rather than an arbitrary page — there is no server-side pagination to continue
         * past it, and everything the screen does to the list is done over this set in memory.
         */
        const val HISTORY_LIMIT = PluginAutomationHistoryStore.MAX_RETAINED
    }
}
