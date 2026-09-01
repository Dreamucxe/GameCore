package com.gamecore.ui.hud

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.model.HudLayout
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.HudWidget
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.HudLayoutRepository
import com.gamecore.domain.monitoring.HudStatReader
import com.gamecore.domain.monitoring.PerformanceMonitor
import com.gamecore.domain.monitoring.StatReading
import com.gamecore.ui.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * One HUD layout, being built.
 *
 * Two streams come out of here and they are deliberately different shapes. [state] is the draft: hot, held
 * in a single [MutableStateFlow], never overwritten by anything in the background, because a builder where
 * a sampler emission could move the widget the user is dragging is a builder that loses work.
 *
 * [readings] is the live preview: **cold**, derived from the shared sampling loop. Collecting it is what
 * starts the loop and leaving the screen is what stops it, which is §26's "stop when not needed" falling
 * out of the subscription rather than being managed by hand.
 *
 * The preview shows real readings rather than sample text, and that is the honesty requirement rather than
 * a nicety: a stat that will render "n/a" over the game renders "n/a" here, in the builder, before the user
 * has saved a layout built around a number this device does not report.
 */
@HiltViewModel
class HudEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val layouts: HudLayoutRepository,
    private val monitor: PerformanceMonitor,
    preferences: SecurePreferenceStore,
) : ViewModel() {

    /**
     * The layout being edited, or [Destination.NEW_LAYOUT].
     *
     * Read as either a `Long` or a `String`, so this ViewModel does not depend on whether the route
     * declared its argument with a `NavType`. A route that changes its argument type should not silently
     * turn every edit into a new layout.
     */
    private val layoutId: Long = savedState.get<Long>(Destination.ARG_ID)
        ?: savedState.get<String>(Destination.ARG_ID)?.toLongOrNull()
        ?: Destination.NEW_LAYOUT

    private val editing = MutableStateFlow(
        HudEditorUiState(
            isNew = layoutId == Destination.NEW_LAYOUT,
            confirmOnDiscard = preferences.settings.value.confirmBeforeDiscard,
        ),
    )

    val state: StateFlow<HudEditorUiState> = editing.asStateFlow()

    /**
     * What each widget on the draft currently reads, keyed by stat.
     *
     * Recomputed when the draft changes as well as when a sample lands, so a stat added mid-preview gets
     * a value on the next tick instead of after a navigation.
     */
    val readings: Flow<Map<HudStat, StatReading>> =
        combine(editing, monitor.snapshots) { draft, snapshot ->
            HudStatReader
                .readAll(draft.widgets.map { it.stat }.distinct(), snapshot)
                .associateBy { it.stat }
        }

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        if (layoutId == Destination.NEW_LAYOUT) {
            editing.value = editing.value.copy(
                isLoaded = true,
                name = "My layout",
                widgets = HudStat.DEFAULT_SET.mapIndexed { index, stat -> widgetFor(stat, index) },
                // A new layout arrives with four stats already on it and is therefore already worth
                // saving. Marked dirty so backing out asks rather than discarding silently.
                isDirty = true,
            )
            return
        }
        val existing = layouts.layout(layoutId)
        editing.value = editing.value.copy(
            isLoaded = true,
            isNew = existing == null,
            name = existing?.name ?: "My layout",
            widgets = existing?.widgets.orEmpty(),
            message = if (existing == null) "That layout is no longer saved." else null,
        )
    }

    // ------------------------------------------------------------------------------- the draft

    fun setName(value: String) {
        editing.value = editing.value.copy(name = value, isDirty = true)
    }

    /** Adds a stat at the next free slot down the left edge. Refuses past the layout's own cap. */
    fun addStat(stat: HudStat) {
        val current = editing.value
        if (current.isFull) {
            editing.value = current.copy(
                message = "A layout holds ${HudLayout.MAX_WIDGETS} stats. Remove one to add another.",
            )
            return
        }
        val widget = widgetFor(stat, current.widgets.size)
        editing.value = current.copy(
            widgets = current.widgets + widget,
            selectedId = widget.id,
            isDirty = true,
        )
    }

    fun select(widgetId: String?) {
        editing.value = editing.value.copy(selectedId = widgetId)
    }

    /**
     * Moves a widget, in screen fractions.
     *
     * Fractions rather than pixels all the way through the drag: the preview is a different size from the
     * screen the HUD will be drawn on, and converting at save time would make the result depend on how
     * tall the builder's preview happened to be on this device.
     */
    fun move(widgetId: String, xFraction: Float, yFraction: Float) {
        update(widgetId) { it.copy(xFraction = xFraction, yFraction = yFraction).normalised() }
    }

    fun setTextSize(widgetId: String, sizeSp: Int) {
        update(widgetId) { it.copy(textSizeSp = sizeSp).normalised() }
    }

    fun setOpacity(widgetId: String, percent: Int) {
        update(widgetId) { it.copy(opacityPercent = percent).normalised() }
    }

    fun setShowLabel(widgetId: String, show: Boolean) {
        update(widgetId) { it.copy(showLabel = show) }
    }

    fun setShowBackground(widgetId: String, show: Boolean) {
        update(widgetId) { it.copy(showBackground = show) }
    }

    fun setColour(widgetId: String, argb: Int) {
        update(widgetId) { it.copy(colorArgb = argb) }
    }

    fun remove(widgetId: String) {
        val current = editing.value
        editing.value = current.copy(
            widgets = current.widgets.filterNot { it.id == widgetId },
            selectedId = if (current.selectedId == widgetId) null else current.selectedId,
            isDirty = true,
        )
    }

    /**
     * Saves the draft.
     *
     * The name is sanitised here rather than per keystroke — §24A.4's requirement is that it is clean
     * before storage and before render, and the full treatment collapses whitespace, which would delete
     * the space the user just typed. A name that sanitises down to nothing falls back to a default rather
     * than saving a layout the list cannot label.
     */
    fun save() {
        val current = editing.value
        if (current.isSaving) return
        val name = TextSanitizer
            .sanitizeName(current.name, HudLayout.MAX_NAME_LENGTH)
            .ifBlank { "My layout" }
        editing.value = current.copy(isSaving = true)
        viewModelScope.launch {
            layouts.save(
                HudLayout(
                    id = if (current.isNew) 0L else layoutId,
                    name = name,
                    widgets = current.widgets.map { it.normalised() },
                ),
            )
            editing.value = editing.value.copy(isSaving = false, isDirty = false, isFinished = true)
        }
    }

    fun dismissMessage() {
        editing.value = editing.value.copy(message = null)
    }

    // -------------------------------------------------------------------------------- internals

    private inline fun update(widgetId: String, transform: (HudWidget) -> HudWidget) {
        val current = editing.value
        editing.value = current.copy(
            widgets = current.widgets.map { if (it.id == widgetId) transform(it) else it },
            isDirty = true,
        )
    }

    /**
     * A new widget.
     *
     * The id is generated here because the builder creates, drags and deletes widgets before anything is
     * written — a widget that had to round-trip through Room to acquire an identity could not be dragged
     * until it had been saved.
     */
    private fun widgetFor(stat: HudStat, index: Int): HudWidget {
        val (x, y) = defaultPositionFor(index)
        return HudWidget(id = UUID.randomUUID().toString(), stat = stat, xFraction = x, yFraction = y)
    }
}
