package com.gamecore.ui.sessions

import com.gamecore.core.model.KindChip
import com.gamecore.core.model.SessionFilterKind
import com.gamecore.core.model.SessionKind
import com.gamecore.core.model.SessionSort
import com.gamecore.core.model.SessionTile
import com.gamecore.core.model.kindLabel
import com.gamecore.core.model.sessionsSubtitle
import com.gamecore.ui.components.Readout

/**
 * The history list, already turned into text — and, since §6, already merged.
 *
 * §24A.2 applies here as much as anywhere: a [com.gamecore.core.model.GameSession] carries nine nullable
 * aggregates, a stop reason and a sample count, and a card draws a handful of strings. The conversion
 * happens once in the ViewModel, which is also where the "at least" prefix for an interrupted session is
 * decided — a rule worth having in exactly one testable place.
 *
 * What §6 changes is that a row is no longer always a game session. The screen now shows one list built
 * from two repositories that share no table and no model, so every row carries the [SessionRow.kind] it
 * came from and the composables ask that rather than guessing from a null field. The alternative — two
 * lists, or a game list with Aim Lab bolted on — is what produces the failure this redesign is guarding
 * against, where the "All" chip quietly means "all games".
 */
data class SessionsUiState(
    val rows: List<SessionRow> = emptyList(),
    /** The summary across everything recorded, not across the current filter. */
    val statistics: List<Readout> = emptyList(),
    val sort: SessionSort = SessionSort.NEWEST_FIRST,
    /** §6's All / Games / Aim Lab chips, each carrying its whole-history count. */
    val kinds: List<KindChip> = emptyList(),
    val kind: SessionFilterKind = SessionFilterKind.ALL,
    /** The subordinate per-game chips. Kept from the old screen; see [showsGameFilters]. */
    val games: List<SessionFilter> = emptyList(),
    val filterPackage: String? = null,
    /** Rows across both repositories before any filter, which is what the subtitle counts against. */
    val totalCount: Int = 0,
    /** True when nothing at all has been recorded, rather than when a filter is hiding everything. */
    val isEmpty: Boolean = false,
    val isLoading: Boolean = true,
    val isCompact: Boolean = false,
    val live: LiveSession? = null,
    val trackingEnabled: Boolean = true,
    /**
     * Whether an Aim Lab run's report can be reached from here.
     *
     * False when Aim Lab is switched off in Settings, because its routes are not registered in the
     * navigation graph then and a tap would silently land on Home. The runs are still listed — they are
     * real records and hiding them would be the same lie as never merging them — but their cards say why
     * they cannot be opened instead of pretending to be tappable.
     */
    val aimLabAvailable: Boolean = true,
    val pendingDelete: SessionRow? = null,
    val confirmBeforeDelete: Boolean = true,
    val pendingClear: Boolean = false,
    val message: String? = null,
) {
    /** True when any chip — kind or game — is narrowing the list, which the subtitle has to admit to. */
    val hasFilter: Boolean get() = kind != SessionFilterKind.ALL || filterPackage != null

    /** What the current selection is called, for the empty state and the subtitle. */
    val filterLabel: String
        get() = games.firstOrNull { it.packageName == filterPackage && it.packageName != null }?.label
            ?: kindLabel(kind)

    val subtitle: String
        get() = sessionsSubtitle(
            isLoading = isLoading,
            liveLabel = live?.label,
            shown = rows.size,
            total = totalCount,
            isFiltered = hasFilter,
        )

    /** How many game sessions exist in total — what "Clear" would delete, and nothing else. */
    val gameCount: Int
        get() = kinds.firstOrNull { it.kind == SessionFilterKind.GAMES }?.count ?: 0

    /**
     * How many Aim Lab runs exist in total — what "Clear" would *not* delete.
     *
     * Read by the clear dialog and by the retention card, both of which have to be accurate about the half
     * of this list that this screen only reads.
     */
    val aimLabCount: Int
        get() = kinds.firstOrNull { it.kind == SessionFilterKind.AIMLAB }?.count ?: 0

    /**
     * Whether the per-game chip row earns its height.
     *
     * Hidden under the Aim Lab chip, where it would filter nothing, and hidden with a single game, where
     * every chip in it selects the same rows the "Games" chip already selected. [games] always carries an
     * "All games" entry first, so two entries means one real game.
     */
    val showsGameFilters: Boolean
        get() = kind != SessionFilterKind.AIMLAB && games.size > 2
}

/**
 * One row in the history list, whichever repository recorded it.
 *
 * [key] rather than [id] is what the list is keyed on, and the distinction is load-bearing: the two
 * repositories number their rows independently, so a game session and an Aim Lab run can both be id 5.
 * Keying a `LazyColumn` on the raw id would throw on the first user who has both.
 *
 * [canOpen] and [canDelete] are carried per row instead of being derived in the composable because they
 * differ by kind for reasons the card cannot see: an Aim Lab run is read-only here (this screen reads the
 * Aim Lab repository, it does not write to it), and its report is only reachable while Aim Lab is on.
 */
data class SessionRow(
    val key: String,
    val id: Long,
    val kind: SessionKind,
    /** The game's package, for its real launcher icon. Null for an Aim Lab run, which has no package. */
    val packageName: String? = null,
    val label: String,
    val started: String,
    val duration: String,
    /** §6's three tiles, already decided — including which of them say "Unavailable", and why. */
    val tiles: List<SessionTile> = emptyList(),
    /**
     * The three tiles as one sentence.
     *
     * The strip is nine pieces of text in a card a user is scrolling past, and a reader that walks them
     * individually turns one session into nine stops. The strip is collapsed into this, and the reasons
     * behind any "Unavailable" are kept in it — they are the half that matters most to someone who
     * cannot see that the tile is greyed.
     */
    val spokenTiles: String = "",
    /** Set when something about this row needs a sentence: a cut-short recording, an unreachable report. */
    val note: String? = null,
    val canOpen: Boolean = true,
    val canDelete: Boolean = true,
)

/** One entry in the per-game filter row: a game, or "All games" when [packageName] is null. */
data class SessionFilter(
    val packageName: String?,
    val label: String,
    val count: Int,
)

/**
 * The session being recorded right now, shown above the list.
 *
 * Separate from [SessionRow] because it is not a record yet: its duration is still growing, its
 * aggregates are as of the last flush, and it cannot be deleted or opened as a report. It carries the
 * same three [tiles] a finished card does — the figures are real, and the ones with too few samples
 * behind them say so in their own detail rather than being hidden until the session ends.
 */
data class LiveSession(
    val packageName: String,
    val label: String,
    val duration: String,
    val detail: String,
    val tiles: List<SessionTile> = emptyList(),
)
