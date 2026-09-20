package com.gamecore.aimlab.ui.history

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.aimlab.AimLabRepository
import com.gamecore.aimlab.engine.ConfigCodec
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.data.repository.AimLabExport
import com.gamecore.data.repository.AimLabExporter
import com.gamecore.data.repository.DiagnosticsFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The stored session history: what is in it, what the user is looking at, and the three things they can do
 * to it — delete one, clear all, write it to a file.
 *
 * The history itself is observed, never copied into local state: [AimLabRepository.sessions] is the one
 * source, so a run finished on a mode screen appears here without this class being told, and a session
 * deleted here disappears from every other screen watching the same flow. What *is* local is the part the
 * repository has no opinion about — the two filters, which confirmation is open, and the last export.
 *
 * The filters are resolved against the current history on every emission rather than trusted from the last
 * one: deleting the only reaction session while "Reaction test" is selected would otherwise leave the screen
 * filtered to a mode that no longer exists, showing nothing and offering no way back.
 *
 * Nothing destructive happens without a confirmation, and both confirmations are held here so they survive
 * a rotation. Nothing here writes a session; this screen only ever removes them.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: AimLabRepository,
    private val exporter: AimLabExporter,
) : ViewModel() {

    /** Filters, open dialogs and the last export. Everything the repository does not own. */
    private val local = MutableStateFlow(HistoryState())

    val state: StateFlow<HistoryState> = combine(
        local,
        repository.sessions,
    ) { base, sessions ->
        // The DAO already orders by start time descending; sorting here as well makes the screen's
        // "newest first" promise a property of this class rather than of a query it cannot see.
        val ordered = sessions.sortedByDescending { it.startedAtMillis }
        base.copy(
            sessions = ordered,
            loaded = true,
            // A filter that no longer matches anything in the history is dropped rather than left
            // selecting an empty list.
            mode = base.mode?.takeIf { m -> ordered.any { it.mode == m } },
            difficulty = base.difficulty?.takeIf { d -> ordered.any { it.difficulty == d } },
            // A session deleted from under an open confirmation is already gone; the dialog closes.
            pendingDelete = base.pendingDelete?.let { pending ->
                ordered.firstOrNull { it.id == pending.id }
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        initialValue = HistoryState(),
    )

    // ------------------------------------------------------------------------------ filtering

    /** Narrows the list to one mode, or to all of them when [mode] is null. */
    fun onModeSelected(mode: TrainingMode?) = local.update { it.copy(mode = mode) }

    fun onDifficultySelected(difficulty: Difficulty?) = local.update { it.copy(difficulty = difficulty) }

    fun clearFilters() = local.update { it.copy(mode = null, difficulty = null) }

    // ------------------------------------------------------------------------------ deleting

    /** Opens the confirmation for one session. Nothing is removed until [confirmDelete]. */
    fun askDelete(session: SessionSummary) = local.update { it.copy(pendingDelete = session, confirmClear = false) }

    /** Opens the confirmation for the whole history. */
    fun askClearAll() = local.update { it.copy(confirmClear = true, pendingDelete = null) }

    /** Closes whichever confirmation is open, having done nothing. */
    fun dismissConfirmation() = local.update { it.copy(pendingDelete = null, confirmClear = false) }

    /**
     * Deletes the session the confirmation names.
     *
     * The row vanishes because the repository's flow re-emits without it, not because this class edits a
     * list it holds — there is no local copy that could disagree with the database.
     */
    fun confirmDelete() {
        val target = state.value.pendingDelete ?: return
        local.update { it.copy(pendingDelete = null) }
        viewModelScope.launch {
            repository.deleteSession(target.id)
            local.update { it.copy(message = "That session was deleted.") }
        }
    }

    /**
     * Clears the history, and with it the personal records derived from it.
     *
     * [AimLabRepository.clearHistory] removes both together — records without the sessions that set them
     * would be numbers with nothing behind them — and the message says so, because a user who expected only
     * the list to empty should not discover their records are gone by visiting that screen.
     */
    fun confirmClearAll() {
        if (!state.value.confirmClear) return
        local.update { it.copy(confirmClear = false) }
        viewModelScope.launch {
            repository.clearHistory()
            local.update {
                it.copy(message = "History cleared. The personal records set by those sessions went with them.")
            }
        }
    }

    // ------------------------------------------------------------------------------ exporting

    /**
     * Writes a file, and the two formats write genuinely different things.
     *
     * CSV is the session table — one row per session currently listed, filters included, since that is what
     * the user is looking at. JSON is the round-trippable configuration bundle: weapons, sensitivity
     * profiles and control layouts, the form [com.gamecore.aimlab.engine.ConfigCodec] can read back. The
     * exporter draws that line, not this class, and the screen labels each button with what it produces
     * rather than implying the two are the same data in two encodings.
     *
     * Layouts are re-read one by one because the observed list carries their names without their controls,
     * and a layout exported without its controls is not a layout.
     */
    fun export(format: DiagnosticsFormat) {
        val current = state.value
        if (current.exporting) return
        local.update { it.copy(exporting = true, message = null, export = null) }
        viewModelScope.launch {
            val bundle = ConfigCodec.ConfigBundle(
                weapons = repository.weapons.first(),
                sensitivities = repository.sensitivities.first(),
                layouts = repository.layouts.first().mapNotNull { repository.layout(it.id) },
            )
            val result = exporter.exportConfig(
                bundle = bundle,
                sessions = current.visible,
                format = format,
            )
            local.update {
                it.copy(exporting = false, export = result, message = messageFor(result, format))
            }
        }
    }

    /**
     * The share intent for the file just written, or null when there is nothing to share.
     *
     * A `content://` URI from GameCore's own provider with a one-shot read grant — the file never leaves
     * the app's storage unless the user picks a target themselves.
     */
    fun shareIntent(): Intent? {
        val written = state.value.lastExport ?: return null
        val uri = written.uri ?: return null
        return Intent(Intent.ACTION_SEND)
            .setType(written.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun onIntentFailed() = local.update { it.copy(message = NO_SHARE_TARGET) }

    fun dismissMessage() = local.update { it.copy(message = null) }

    private fun messageFor(result: AimLabExport, format: DiagnosticsFormat): String? = when (result) {
        is AimLabExport.Written -> null
        AimLabExport.Empty -> when (format) {
            DiagnosticsFormat.JSON -> "There is no saved setup to write yet — no weapons, sensitivity " +
                "profiles or control layouts."
            else -> "There are no sessions in this list to write."
        }
        is AimLabExport.Failed -> "That file could not be written (${result.detail})."
    }

    private companion object {
        /** Long enough to survive a rotation, short enough that a closed screen stops observing Room. */
        const val SUBSCRIPTION_GRACE_MILLIS = 1_000L

        const val NO_SHARE_TARGET =
            "No app on this device offered to take that file. It is still saved inside GameCore."
    }
}
