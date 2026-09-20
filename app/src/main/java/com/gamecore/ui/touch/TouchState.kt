package com.gamecore.ui.touch

import com.gamecore.core.input.TouchLog
import com.gamecore.core.input.TouchPoint
import com.gamecore.core.input.TouchSnapshot
import com.gamecore.core.input.TouchSummary
import com.gamecore.core.input.TouchViewMode
import com.gamecore.data.repository.DiagnosticsExport

/**
 * The touch heatmap's state: what GameCore's own capture pad saw, and nothing else.
 *
 * The boundary here is not a policy decision, it is the platform's. An Android app receives the touch
 * events that are dispatched *to its own windows*; it is given no access to what a finger did in another
 * app, to what is on screen, or to anything typed. So this screen records the pad below it and says so,
 * rather than presenting a partial picture as though it were a record of how the device is used.
 */
data class TouchUiState(
    val isCapturing: Boolean = false,
    val mode: TouchViewMode = TouchViewMode.HEATMAP,
    val snapshot: TouchSnapshot = TouchSnapshot.empty(TouchLog.DEFAULT_COLUMNS, TouchLog.DEFAULT_ROWS),
    val recentEvents: List<TouchPoint> = emptyList(),
    val isExporting: Boolean = false,
    val export: DiagnosticsExport? = null,
    val message: String? = null,
) {
    val summary: TouchSummary get() = snapshot.summary

    /** Whether anything has been captured yet. Drives the empty state under the pad. */
    val hasData: Boolean get() = summary.totalEvents > 0

    /** Fingers down right now, which is the one figure that is live rather than accumulated. */
    val activePointers: Int get() = snapshot.activePointers

    val lastExport: DiagnosticsExport.Written? get() = export as? DiagnosticsExport.Written
}
