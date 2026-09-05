package com.gamecore.ui.storage

import com.gamecore.core.model.CacheClearReport
import com.gamecore.core.model.GameStorage
import com.gamecore.core.common.valueOrNull

/**
 * The game-storage screen, as the screen needs it.
 *
 * [rows] is the measured list and nothing else: the two things that change per row while the user is
 * on the screen — whether a clear is in flight, and what the last one reported — are held beside it
 * keyed by package rather than folded into the row. That is what lets a re-measure replace every row
 * without losing the sentence under the one the user just pressed.
 *
 * [needsUsageAccess] and [canClear] are separate and neither implies the other. Usage access buys the
 * figures; Shizuku buys the clearing. A user with one and not the other gets a screen that works as far
 * as it can and says which half is missing — sizes with no button, or a button whose result cannot be
 * measured — instead of a blank screen with one generic warning.
 */
data class GameStorageUiState(
    val isLoaded: Boolean = false,
    val isRefreshing: Boolean = false,

    /** Whether the shell the clearing needs is running. Checked on every refresh, not cached. */
    val canClear: Boolean = false,

    /** Whether the figures are missing for the one reason the user can do something about. */
    val needsUsageAccess: Boolean = false,

    val rows: List<GameStorage> = emptyList(),

    /** Packages with a clear in flight, so their button shows progress and cannot be pressed twice. */
    val clearing: Set<String> = emptySet(),

    /** The last thing each clear reported, kept until the screen is left. */
    val reports: Map<String, CacheClearReport> = emptyMap(),

    /** The row whose confirmation dialog is open, or null. */
    val confirming: GameStorage? = null,

    /** A one-off failure to say out loud — a Settings page that would not open. */
    val message: String? = null,
) {

    fun isClearing(row: GameStorage): Boolean = row.packageName in clearing

    fun reportFor(row: GameStorage): CacheClearReport? = reports[row.packageName]

    /**
     * Every measured cache added up, or null when not one of them could be read.
     *
     * Rows the platform would not measure are left out of the sum rather than counted as zero, so the
     * total is a total of what is known. It is a subtitle and not a headline for that reason: on a
     * device that answered for six of eight games it is a floor, not the figure.
     */
    val measuredTotal: Long?
        get() {
            val measured = rows.mapNotNull { it.cacheBytes.valueOrNull }
            return if (measured.isEmpty()) null else measured.sum()
        }

    /** True once a refresh has finished and found nothing to list. */
    val isEmpty: Boolean get() = isLoaded && !isRefreshing && rows.isEmpty()
}
