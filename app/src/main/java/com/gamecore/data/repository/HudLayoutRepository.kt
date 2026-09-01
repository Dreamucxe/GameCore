package com.gamecore.data.repository

import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.model.HudLayout
import com.gamecore.core.model.HudWidget
import com.gamecore.data.database.HudLayoutDao
import com.gamecore.data.database.HudLayoutEntity
import com.gamecore.data.database.Mappers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HUD layouts and their widgets.
 *
 * A layout spans two tables, so this is the class that decides what "a layout" means. The list
 * flow deliberately returns layouts with **empty** widget lists: the HUD screen shows a card per
 * layout with a name and a widget count, and joining every widget row for every layout to render
 * that would read the whole HUD table to display a handful of numbers. [layout] loads the widgets
 * when one is actually opened.
 *
 * That split is visible in the API rather than hidden — [widgetCounts] exists precisely so the
 * list screen can show a count without pretending the widgets are loaded.
 */
@Singleton
class HudLayoutRepository @Inject constructor(
    private val dao: HudLayoutDao,
    private val profiles: GameProfileRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Layouts without their widgets, newest edit first. See the class note. */
    val layouts: Flow<List<HudLayout>> = dao.observeLayouts().map { rows ->
        rows.map { entity -> Mappers.toModel(entity, emptyList()) }
    }

    /**
     * Widget counts by layout id.
     *
     * One read of the widget table, grouped in memory, rather than a query per layout. The table
     * holds at most [HudLayout.MAX_WIDGETS] rows per layout and a user has a handful of layouts,
     * so this is a few dozen narrow rows — cheaper than the round trips it replaces.
     */
    suspend fun widgetCounts(): Map<Long, Int> = withContext(io) {
        dao.allWidgets().groupingBy { it.layoutId }.eachCount()
    }

    /** A layout with its widgets. Null if the id no longer exists. */
    suspend fun layout(id: Long): HudLayout? = withContext(io) {
        val entity = dao.layout(id) ?: return@withContext null
        Mappers.toModel(entity, dao.widgets(id))
    }

    /**
     * Saves a layout and its widgets, returning the id.
     *
     * The widget list is capped and normalised here rather than trusted, because this is the entry
     * point an imported layout file would also come through. `take` rather than a rejection: a
     * layout with thirteen widgets is a layout with twelve usable ones, and failing the whole save
     * would lose the user's other work.
     */
    suspend fun save(layout: HudLayout): Long = withContext(io) {
        val now = System.currentTimeMillis()
        val entity: HudLayoutEntity = Mappers.toEntity(layout, now)
        val widgets = layout.widgets
            .take(HudLayout.MAX_WIDGETS)
            .map { widget -> Mappers.toEntity(widget, entity.id) }
        dao.save(entity, widgets)
    }

    /**
     * Deletes a layout, its widgets, and every profile's reference to it.
     *
     * The reference clearing is not optional and not deferred: a profile pointing at a deleted
     * layout would apply successfully and show nothing, which is exactly the silent failure this
     * app is built to avoid.
     */
    suspend fun delete(id: Long) {
        withContext(io) { dao.deleteWithWidgets(id) }
        profiles.clearHudLayoutReferences(id)
    }

    /**
     * Adds or replaces a single widget on a stored layout.
     *
     * Used by the builder's undo path and by the "add stat" action on an already-saved layout. It
     * reloads, applies, and re-saves rather than writing one row, because the layout's
     * `updated_at` and the widget cap are properties of the layout as a whole.
     */
    suspend fun putWidget(layoutId: Long, widget: HudWidget): Boolean {
        val current = layout(layoutId) ?: return false
        if (current.widgets.none { it.id == widget.id } &&
            current.widgetCount >= HudLayout.MAX_WIDGETS
        ) {
            return false
        }
        save(current.withWidget(widget))
        return true
    }

    suspend fun removeWidget(layoutId: Long, widgetId: String): Boolean {
        val current = layout(layoutId) ?: return false
        save(current.withoutWidget(widgetId))
        return true
    }
}
