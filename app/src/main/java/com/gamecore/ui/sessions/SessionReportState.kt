package com.gamecore.ui.sessions

import com.gamecore.ui.components.Readout
import com.gamecore.ui.components.Tone

/**
 * §21's report on one recorded session, already turned into strings and plottable floats.
 *
 * The graphs are the reason this is a separate state from [SessionsUiState]: a session of an hour at one
 * sample every two seconds is around 1,800 [com.gamecore.core.model.SessionSample] rows, and the report is
 * the only screen that loads them. They are reduced to `List<Float>` per series here, in the ViewModel, so
 * no composable ever holds the samples themselves.
 *
 * [confidence] carries what the figures rest on. Ten samples and four hundred are different claims about
 * the same session, and a report that prints an average without saying which it has is inviting the reader
 * to assume the second one.
 */
data class SessionReportUiState(
    val title: String = "",
    val subtitle: String = "",
    val isLoading: Boolean = true,
    /** Set when the id in the route does not resolve — a deleted session, or a stale back stack entry. */
    val isMissing: Boolean = false,
    val headline: List<Readout> = emptyList(),
    val figures: List<Readout> = emptyList(),
    val graphs: List<SessionGraph> = emptyList(),
    val confidence: String = "",
    /** The interrupted case: what stopped the recording, when that makes the duration a floor. */
    val interruption: String? = null,
    val profileNote: String? = null,
    val pendingDelete: Boolean = false,
    val confirmBeforeDelete: Boolean = true,
    val isDeleted: Boolean = false,
    val message: String? = null,
) {
    val hasGraphs: Boolean get() = graphs.any { it.isPlottable }
}

/**
 * One plot on the report.
 *
 * [points] is already `mapNotNull`-ed: a device that could not read a temperature contributes a shorter
 * list rather than a run of zeroes, and [isPlottable] is what the card checks before drawing anything. The
 * range travels with the series because a percentage belongs against 0–100 and a temperature does not.
 */
data class SessionGraph(
    val title: String,
    val points: List<Float>,
    val range: ClosedFloatingPointRange<Float>,
    val unit: String,
    val seriesLabel: String? = null,
    val secondary: List<Float> = emptyList(),
    val secondaryLabel: String? = null,
    /** Said instead of drawing, when there is nothing honest to plot. */
    val emptyMessage: String = "Not enough samples to draw a line.",
    val tone: Tone = Tone.Accent,
) {
    val isPlottable: Boolean get() = points.size >= MIN_POINTS

    companion object {
        /** Matches [com.gamecore.core.model.MetricHistory.MIN_PLOTTABLE], which the canvas enforces too. */
        const val MIN_POINTS = 3
    }
}
