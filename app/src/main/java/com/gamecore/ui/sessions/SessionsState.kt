package com.gamecore.ui.sessions

import com.gamecore.core.model.SessionSort
import com.gamecore.ui.components.Readout
import com.gamecore.ui.components.Tone

/**
 * The history list, already turned into text.
 *
 * §24A.2 applies here as much as anywhere: a [com.gamecore.core.model.GameSession] carries nine nullable
 * aggregates, a stop reason and a sample count, and the list draws four strings per row. The conversion
 * happens once in the ViewModel, which is also where the "at least" prefix for an interrupted session is
 * decided — a rule worth having in exactly one testable place.
 */
data class SessionsUiState(
    val rows: List<SessionRow> = emptyList(),
    /** The summary across everything recorded, not across the current filter. */
    val statistics: List<Readout> = emptyList(),
    val sort: SessionSort = SessionSort.NEWEST_FIRST,
    val filters: List<SessionFilter> = emptyList(),
    val filterPackage: String? = null,
    /** True when history is empty because nothing has been recorded, rather than because of a filter. */
    val isEmpty: Boolean = false,
    val isLoading: Boolean = true,
    val live: LiveSession? = null,
    val trackingEnabled: Boolean = true,
    val pendingDelete: SessionRow? = null,
    val confirmBeforeDelete: Boolean = true,
    val pendingClear: Boolean = false,
    val message: String? = null,
) {
    val hasFilter: Boolean get() = filterPackage != null

    val filterLabel: String
        get() = filters.firstOrNull { it.packageName == filterPackage }?.label ?: "All games"
}

/** One row in the history list. Everything on it is a string the row draws as-is. */
data class SessionRow(
    val id: Long,
    val label: String,
    val started: String,
    val duration: String,
    val figures: List<Readout>,
    /** Set when the recording was cut short, so the duration is a floor rather than a length. */
    val note: String? = null,
)

/** One entry in the filter row: a game, or "All games" when [packageName] is null. */
data class SessionFilter(
    val packageName: String?,
    val label: String,
    val count: Int,
)

/**
 * The session being recorded right now, shown above the list.
 *
 * Separate from [SessionRow] because it is not a record yet: its duration is still growing, its
 * aggregates are as of the last flush, and it cannot be deleted or opened as a report.
 */
data class LiveSession(
    val label: String,
    val duration: String,
    val detail: String,
    val tone: Tone = Tone.Accent,
)
