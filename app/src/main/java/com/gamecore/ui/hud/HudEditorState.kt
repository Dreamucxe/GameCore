package com.gamecore.ui.hud

import com.gamecore.core.model.HudLayout
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.HudWidget

/**
 * The layout builder's state: a draft layout, and which widget the controls below the preview belong to.
 *
 * The whole draft is held in memory and written once, on save. A builder that persisted each drag would
 * be simpler and wrong twice over: this layout may be the one a running game is currently drawing, and
 * "cancel" would have nothing to undo.
 *
 * [selectedId] rather than a selected widget, so the selection survives an edit to the widget it points
 * at — a data class held by identity would go stale on the first slider movement.
 */
data class HudEditorUiState(
    val isLoaded: Boolean = false,
    val isNew: Boolean = true,
    val name: String = "",
    val widgets: List<HudWidget> = emptyList(),
    val selectedId: String? = null,
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
    val isFinished: Boolean = false,
    /** From settings: whether backing out of unsaved edits should ask first. */
    val confirmOnDiscard: Boolean = true,
    val message: String? = null,
) {
    val selected: HudWidget? get() = widgets.firstOrNull { it.id == selectedId }

    val canSave: Boolean get() = name.isNotBlank() && !isSaving

    val isFull: Boolean get() = widgets.size >= HudLayout.MAX_WIDGETS

    /** Stats not already on the layout. One widget per stat: two copies of "CPU" is a bug, not a layout. */
    val addableStats: List<HudStat>
        get() = HudStat.entries.filterNot { stat -> widgets.any { it.stat == stat } }
}

/**
 * The colours a widget can be drawn in.
 *
 * A short list rather than a colour picker, and every one of them chosen to stay legible on a light game
 * scene as well as a dark one — the widget is drawn over someone else's app, and GameCore cannot know
 * what is behind it. White first, because that is what reads on the most backgrounds.
 */
internal val WIDGET_COLOURS: List<Int> = listOf(
    0xFFFFFFFF.toInt(),
    0xFF00E5FF.toInt(),
    0xFF4CE07A.toInt(),
    0xFFFFB300.toInt(),
    0xFFFF5A87.toInt(),
    0xFF9C6BFF.toInt(),
)

/**
 * Where a newly added widget lands.
 *
 * Staggered down the left edge by the number of widgets already placed, so adding four stats in a row
 * produces four readable rows rather than four widgets stacked on the same pixel. Wraps back to the top
 * once it would run off the bottom.
 */
internal fun defaultPositionFor(index: Int): Pair<Float, Float> {
    val column = index / ROWS_PER_COLUMN
    val row = index % ROWS_PER_COLUMN
    return (0.04f + column * 0.30f).coerceAtMost(0.90f) to (0.04f + row * 0.07f)
}

private const val ROWS_PER_COLUMN = 8
