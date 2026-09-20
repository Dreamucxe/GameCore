package com.gamecore.aimlab.ui.history

import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.data.repository.AimLabExport

/**
 * Everything the session-history screen draws, in one value.
 *
 * [sessions] is the whole stored history, newest first, exactly as the repository holds it — no page, no
 * cap. What the list *shows* is [visible], the same sessions narrowed by [mode] and [difficulty]. Keeping
 * both means the filter chips can be built from what is actually in the history rather than from the enums:
 * a user who has only ever run flick training is not offered five filters that would each empty the screen.
 *
 * A null [mode] or [difficulty] means "every one of them", which is why the option lists below carry a
 * leading null. That null is not a fabricated mode — it is the real "no filter" case — and it follows the
 * `ChoiceRow` idiom already used for optional gear on the practice screen.
 *
 * Nothing here is derived from a number the engine did not produce. A row's score is shown only when the
 * mode is scored ([TrainingMode.scored] is false for free practice, and the engine writes 0 for it), and a
 * row's accuracy only when shots were fired — tracking and gyro runs have no discrete shots at all, so
 * "0 %" would describe a mode that never measured it.
 *
 * [pendingDelete] and [confirmClear] are the two destructive confirmations, held here rather than in the
 * composable so the dialog survives a rotation and so at most one of them can be open.
 */
data class HistoryState(
    val sessions: List<SessionSummary> = emptyList(),
    val mode: TrainingMode? = null,
    val difficulty: Difficulty? = null,
    val loaded: Boolean = false,
    val pendingDelete: SessionSummary? = null,
    val confirmClear: Boolean = false,
    val exporting: Boolean = false,
    val export: AimLabExport? = null,
    val message: String? = null,
) {

    /** The sessions the list draws: the stored history narrowed by whichever filters are set. */
    val visible: List<SessionSummary>
        get() = sessions.filter { session ->
            (mode == null || session.mode == mode) && (difficulty == null || session.difficulty == difficulty)
        }

    /** True once the repository has answered. Before that the screen shows its header and nothing else. */
    val isEmpty: Boolean get() = loaded && sessions.isEmpty()

    /** There is history, but the current filters exclude all of it. A different sentence from [isEmpty]. */
    val filteredToNothing: Boolean get() = sessions.isNotEmpty() && visible.isEmpty()

    /** Whether any filter is narrowing the list. What the "Show all" affordance keys off. */
    val isFiltered: Boolean get() = mode != null || difficulty != null

    // --- filter options, built from the history itself ---

    /**
     * The modes that actually appear in the history, in the enum's own order, behind a leading "All".
     *
     * Built from the stored sessions rather than from [TrainingMode.entries]: a filter that can only ever
     * produce an empty list is a dead control, and offering one implies the user has runs they do not have.
     */
    val modeOptions: List<TrainingMode?>
        get() = listOf<TrainingMode?>(null) + TrainingMode.entries.filter { m -> sessions.any { it.mode == m } }

    val difficultyOptions: List<Difficulty?>
        get() = listOf<Difficulty?>(null) +
            Difficulty.entries.filter { d -> sessions.any { it.difficulty == d } }

    /** Whether the mode filter is worth drawing. One mode in the history needs no filter for it. */
    val hasModeFilter: Boolean get() = modeOptions.size > 2

    val hasDifficultyFilter: Boolean get() = difficultyOptions.size > 2

    val hasAnyFilter: Boolean get() = hasModeFilter || hasDifficultyFilter

    /** The last export that produced a file, for the filename/size row and the share button. */
    val lastExport: AimLabExport.Written? get() = export as? AimLabExport.Written
}
