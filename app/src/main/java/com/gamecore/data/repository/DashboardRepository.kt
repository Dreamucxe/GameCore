package com.gamecore.data.repository

import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.model.DashboardLayout
import com.gamecore.data.database.DashboardDao
import com.gamecore.data.database.DashboardMappers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Composed dashboards and their items.
 *
 * A dashboard spans two tables, so this is the class that decides what "a dashboard" means. The v1
 * composer manages the single *selected* dashboard, so the one flow it needs is [selectedLayout]: the
 * selected layout row joined with its items, re-emitting whenever either changes. That join is done
 * here with [flatMapLatest] rather than in the DAO because the item query keys on the selected id,
 * which is itself a value on the first flow — a plain `combine` could not re-point the second query at
 * a newly selected layout.
 *
 * Everything written goes through [DashboardLayout.normalised] first — deduped, capped at
 * [DashboardLayout.MAX_PLUGIN_WIDGET_ITEMS], positions densified — because this is the one entry point
 * a save (and a future imported layout) comes through, and the stored order must be canonical. Reads go
 * through [DashboardMappers.toModel], which drops any item whose kind a newer build wrote and this one
 * does not know, so a save never trusts and a read never throws.
 */
@Singleton
class DashboardRepository @Inject constructor(
    private val dao: DashboardDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * The selected dashboard with its items, or null when none has been saved yet.
     *
     * `null` is the honest first-run value: the composer shows an empty working dashboard rather than
     * writing a row to disk merely because the screen was opened — the first row is written by the
     * first real edit.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedLayout: Flow<DashboardLayout?> =
        dao.observeSelectedLayout().flatMapLatest { entity ->
            if (entity == null) {
                flowOf(null)
            } else {
                dao.observeItems(entity.id).map { items -> DashboardMappers.toModel(entity, items) }
            }
        }

    /** A dashboard with its items. Null if the id no longer exists. */
    suspend fun layout(id: Long): DashboardLayout? = withContext(io) {
        val entity = dao.layout(id) ?: return@withContext null
        DashboardMappers.toModel(entity, dao.items(id))
    }

    /**
     * Saves a dashboard and its items, returning the resolved id.
     *
     * The layout is normalised rather than trusted — this is the entry point an imported dashboard
     * would also come through — so the cap and the dense ordering hold for every row that reaches disk.
     */
    suspend fun save(layout: DashboardLayout): Long = withContext(io) {
        val now = System.currentTimeMillis()
        val canonical = layout.normalised()
        val entity = DashboardMappers.toEntity(canonical, now)
        val items = canonical.items.map { item -> DashboardMappers.toItemEntity(item, entity.id) }
        dao.save(entity, items)
    }

    /** Marks one dashboard the selected one, clearing the flag on every other, in a single transaction. */
    suspend fun select(id: Long) {
        withContext(io) { dao.select(id, System.currentTimeMillis()) }
    }

    /** Deletes a dashboard and its items. */
    suspend fun delete(id: Long) {
        withContext(io) { dao.deleteWithItems(id) }
    }

    /** The number of stored dashboards, for the first-run decision the composer makes. */
    suspend fun count(): Int = withContext(io) { dao.layoutCount() }
}
