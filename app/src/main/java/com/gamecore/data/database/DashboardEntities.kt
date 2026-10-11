package com.gamecore.data.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * A composed dashboard, as it lives on disk (schema version 18).
 *
 * The parent of a two-table layout/items pair modelled on [HudLayoutEntity]/[HudWidgetEntity]: a layout row
 * holds the dashboard's identity and the item rows hold its ordered contents, joined by [DashboardItemEntity.layoutId].
 * [isSelected] marks the single dashboard currently shown; the schema permits several so a future build can
 * offer named dashboards without another migration, but the v1 composer keeps exactly one selected.
 *
 * [schemaVersion] records the dashboard *content* format (not the Room database version) so a later format
 * change can be migrated per layout. Columns carry the entity's own property names rather than snake_case
 * `@ColumnInfo` aliases, following [PluginAutomationEventEntity]: this is a self-contained new table read only
 * through [DashboardDao], there is no legacy name to preserve and no cross-table join that wants one.
 */
@Entity(tableName = "dashboard_layouts")
data class DashboardLayoutEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val schemaVersion: Int,
    val isSelected: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/**
 * One placed item on a dashboard.
 *
 * [kind] is the plain name of `DashboardItemKind` and [refId] is a plugin widget id or a built-in component
 * id depending on it — both stored as text and parsed back defensively above this line, so a row written by
 * a newer build (an item kind this build does not know) is dropped on read rather than throwing in generated
 * code. There is deliberately no foreign key to [DashboardLayoutEntity]: items are rewritten wholesale when a
 * layout is saved and deleted explicitly with their layout, the same "no cascade" discipline the other config
 * tables keep. [layoutId] is indexed because every read and the per-layout delete key on it.
 */
@Entity(
    tableName = "dashboard_items",
    indices = [Index("layoutId")],
)
data class DashboardItemEntity(
    @PrimaryKey
    val itemId: String,
    val layoutId: Long,
    val kind: String,
    val pluginId: String,
    val refId: String,
    val position: Int,
    val visible: Boolean,
)

/**
 * The dashboard tables.
 *
 * [save] mirrors [HudLayoutDao]'s transaction exactly: insert the layout, resolve its id (the freshly
 * assigned rowid for a new layout, the existing id otherwise), clear its old items, and write the new set
 * re-pointed at the resolved id — so a save is atomic and a layout's items can never be left pointing at a
 * stale parent. [select] is the one place the single-selected invariant is enforced: it clears every row's
 * flag and sets one, in a transaction, so two dashboards can never both read as selected.
 */
@Dao
interface DashboardDao {

    @Query("SELECT * FROM dashboard_layouts ORDER BY updatedAtMillis DESC")
    fun observeLayouts(): Flow<List<DashboardLayoutEntity>>

    @Query("SELECT * FROM dashboard_layouts WHERE isSelected = 1 LIMIT 1")
    fun observeSelectedLayout(): Flow<DashboardLayoutEntity?>

    @Query("SELECT * FROM dashboard_items WHERE layoutId = :layoutId ORDER BY position ASC")
    fun observeItems(layoutId: Long): Flow<List<DashboardItemEntity>>

    @Query("SELECT * FROM dashboard_layouts WHERE id = :id LIMIT 1")
    suspend fun layout(id: Long): DashboardLayoutEntity?

    @Query("SELECT * FROM dashboard_items WHERE layoutId = :layoutId ORDER BY position ASC")
    suspend fun items(layoutId: Long): List<DashboardItemEntity>

    @Query("SELECT COUNT(*) FROM dashboard_layouts")
    suspend fun layoutCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLayout(layout: DashboardLayoutEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<DashboardItemEntity>)

    @Query("DELETE FROM dashboard_items WHERE layoutId = :layoutId")
    suspend fun deleteItems(layoutId: Long)

    @Query("DELETE FROM dashboard_layouts WHERE id = :id")
    suspend fun deleteLayout(id: Long)

    @Query("UPDATE dashboard_layouts SET isSelected = 0")
    suspend fun clearSelection()

    @Query("UPDATE dashboard_layouts SET isSelected = 1, updatedAtMillis = :nowMillis WHERE id = :id")
    suspend fun markSelected(id: Long, nowMillis: Long)

    @Transaction
    suspend fun save(layout: DashboardLayoutEntity, items: List<DashboardItemEntity>): Long {
        val id = insertLayout(layout)
        val resolved = if (layout.id == 0L) id else layout.id
        deleteItems(resolved)
        if (items.isNotEmpty()) insertItems(items.map { it.copy(layoutId = resolved) })
        return resolved
    }

    @Transaction
    suspend fun select(id: Long, nowMillis: Long) {
        clearSelection()
        markSelected(id, nowMillis)
    }

    @Transaction
    suspend fun deleteWithItems(id: Long) {
        deleteItems(id)
        deleteLayout(id)
    }
}
